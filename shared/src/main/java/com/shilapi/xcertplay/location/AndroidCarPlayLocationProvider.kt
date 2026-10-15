package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * A fresh GPS fix is sent whenever there is one; fused or network location only while GPS is stale
 * (see [LocationFixChoice]). GGA carries the fix's real quality and, for GPS, the satellites used.
 * The GNSS chip's own `$GPGSV` satellites-in-view sentences follow when they are fresh.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in locationManager.allProviders }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            if (provider in preferredProviders) {
                latestFixes[provider] = location
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    private val satellites = GpgsvCollector()
    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        satellites.onSentence(message, SystemClock.elapsedRealtime())
    }

    /** Satellites used in the latest GNSS fix, and when that was reported (elapsed realtime). */
    @Volatile private var satelliteStatus: Pair<Int, Long>? = null
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satelliteStatus = (0 until status.satelliteCount).count(status::usedInFix) to SystemClock.elapsedRealtime()
        }

        override fun onStopped() {
            satelliteStatus = null
        }
    }
    private var gnssStatusRegistered = false

    private var started = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            } else {
                try {
                    locationManager.addNmeaListener(nmeaListener, Handler(Looper.getMainLooper()))
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to raw GNSS NMEA; satellites are not sent", error)
                }
                gnssStatusRegistered = try {
                    locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to GNSS status; satellites used are not sent", error)
                    false
                }
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
        try {
            locationManager.removeNmeaListener(nmeaListener)
        } catch (_: Exception) {
            // Already unregistered.
        }
        if (gnssStatusRegistered) {
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            gnssStatusRegistered = false
        }
        satellites.clear()
        satelliteStatus = null
    }

    override fun latestNmea(): String? {
        val fix = latestFix() ?: return null
        return try {
            val satellitesInView = satellites.latest(SystemClock.elapsedRealtime()).orEmpty()
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                    fixQuality = fixQuality(fix),
                    satellitesUsed = satellitesUsed(fix),
                ),
            ) + satellitesInView
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    private fun latestFix(): Location? {
        val now = SystemClock.elapsedRealtime()
        return LocationFixChoice.choose(
            fixes = preferredProviders.mapNotNull(latestFixes::get),
            provider = { it.provider },
            ageMillis = { now - it.elapsedRealtimeNanos / NANOS_PER_MILLI },
            maxAgeMillis = preferredLocationAgeMillis,
        )
    }

    private fun fixQuality(fix: Location): Int? = when (fix.provider) {
        LocationManager.GPS_PROVIDER -> NmeaLocationEncoder.SATELLITE_FIX
        LocationManager.NETWORK_PROVIDER -> NmeaLocationEncoder.ESTIMATED_FIX
        else -> null
    }

    /**
     * Only a GPS fix is known to come from the satellites GnssStatus reports. A GPS fix needs at least
     * three, so none means the chip does not mark them, and the placeholder is sent instead.
     */
    private fun satellitesUsed(fix: Location): Int? {
        if (fix.provider != LocationManager.GPS_PROVIDER) return null
        val (count, at) = satelliteStatus ?: return null
        return count.takeIf { it > 0 && SystemClock.elapsedRealtime() - at in 0..SATELLITE_STATUS_MAX_AGE_MILLIS }
    }

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L
        private const val SATELLITE_STATUS_MAX_AGE_MILLIS = 3_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
