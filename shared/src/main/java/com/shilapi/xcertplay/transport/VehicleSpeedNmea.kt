package com.shilapi.xcertplay.transport

import android.os.SystemClock
import java.util.Locale

enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }

/** One wheel-speed reading; [elapsedMillis] is on the monotonic clock since boot. */
data class VehicleSpeedSample(val elapsedMillis: Long, val metersPerSecond: Double)

/** Wheel-speed readings collected since the last sentence, with the gear at the time. */
data class VehicleSpeedReading(val gear: VehicleGear, val samples: List<VehicleSpeedSample>)

/** Car wheel speed for dead reckoning; sampled in the background so the iAP2 loop never waits. */
interface VehicleSpeedSource {
    fun start()

    fun stop()

    /** Readings since the previous call, or null when there are none. */
    fun drain(): VehicleSpeedReading?
}

/**
 * Apple's `$PASCD` vehicle-speed sentence, sent in iAP2 0xFFFB next to GGA/RMC. The layout follows a
 * real head unit's log: `$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54` is the first sample's
 * time in seconds, `C`, the gear, `0`, the sample count, then offset/speed pairs. What `C` and the
 * `0` mean is not public, so they are copied as seen.
 *
 * The speed is in knots, as in NMEA's RMC: the iPhone dead-reckons in a tunnel at the rate it reads,
 * and sending m/s made it advance at about half the real speed (3.6 / 1.852). The time is seconds of
 * the UTC day (max 86399.999), also as a real head unit sends it; without [nowElapsedMillis] and
 * [utcMillis] the first sample's own clock is used, which keeps tests and logs independent.
 */
object PascdEncoder {
    /** Metres per second to knots; 1 knot = 1.852 km/h. */
    const val MPS_TO_KNOTS = 1.943844

    private const val SECONDS_PER_DAY = 86_400.0

    fun encode(reading: VehicleSpeedReading, nowElapsedMillis: Long? = null, utcMillis: Long? = null): String? {
        val samples = reading.samples.takeIf { it.isNotEmpty() } ?: return null
        val start = samples.first().elapsedMillis
        val seconds = if (nowElapsedMillis != null && utcMillis != null) {
            (utcMillis / 1000.0 - (nowElapsedMillis - start) / 1000.0).mod(SECONDS_PER_DAY)
        } else {
            start / 1000.0
        }
        val body = buildString {
            append("PASCD,").append(format("%.3f", seconds)).append(",C,")
            append(reading.gear.letter).append(",0,").append(samples.size)
            for (sample in samples) {
                append(',').append(format("%.2f", (sample.elapsedMillis - start) / 1000.0))
                append(',').append(format("%.3f", sample.metersPerSecond.coerceAtLeast(0.0) * MPS_TO_KNOTS))
            }
        }
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*${format("%02X", checksum)}\r\n"
    }

    private fun format(format: String, vararg arguments: Any): String = String.format(Locale.US, format, *arguments)
}

/**
 * Adds `$PASCD` to [position]'s GGA/RMC when the iPhone asked for vehicle speed. Speed is still sent
 * when there is no position fix, as in a tunnel, which is when the iPhone needs it most.
 */
class VehicleSpeedLocationProvider(
    private val position: Iap2LocationProvider,
    private val speed: VehicleSpeedSource,
    private val log: (String) -> Unit = {},
) : Iap2LocationProvider {
    @Volatile private var speedRequested = false
    private var lastLogMillis = 0L

    override fun onRequested(components: Set<Int>) {
        val requested = Iap2LocationMessages.VEHICLE_SPEED_DATA in components
        if (speedRequested && !requested) speed.stop()
        speedRequested = requested
        position.onRequested(components)
    }

    override fun start(): Boolean {
        val started = position.start()
        if (speedRequested) speed.start()
        return started || speedRequested
    }

    override fun stop() {
        speed.stop()
        position.stop()
    }

    override fun latestNmea(): String? {
        val fix = position.latestNmea()
        val reading = if (speedRequested) speed.drain() else null
        val pascd = reading?.let {
            logSpeed(it)
            PascdEncoder.encode(it, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        }
        return if (fix == null) pascd else fix + pascd.orEmpty()
    }

    /** Every 5 s, what the wheel-speed helper read, so a tunnel test can compare it with the dash. */
    private fun logSpeed(reading: VehicleSpeedReading) {
        val sample = reading.samples.lastOrNull() ?: return
        if (sample.elapsedMillis - lastLogMillis < 5_000L) return
        lastLogMillis = sample.elapsedMillis
        log(
            "Vehicle speed: %.1f km/h, sending %.2f kn, gear %s, %d samples".format(
                Locale.US,
                sample.metersPerSecond * 3.6,
                sample.metersPerSecond * PascdEncoder.MPS_TO_KNOTS,
                reading.gear.letter,
                reading.samples.size,
            ),
        )
    }
}
