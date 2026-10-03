package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class BydBatteryReading(val percent: Double, val rangeKm: Int, val remainingKwh: Double, val charging: Boolean?)

internal object BydVehicleData {
    fun battery(line: String, capacityKwh: Double): BydBatteryReading? {
        val fields = line.split(' ')
        if (fields.size != 6 || fields[0] != "XCERTPLAY" || fields[1] != "battery") return null
        val percent = fields[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        // The supplied Android 10 SDK reserves 1000 and 1023 as invalid/default electric range.
        val range = fields[3].toIntOrNull()?.takeIf { it in 0..999 } ?: return null
        val energy = if (capacityKwh > 0) capacityKwh * percent / 100 else fields[4].toDoubleOrNull()
        val remaining = energy?.takeIf { it.isFinite() && it in 0.0..200.0 && (percent == 0.0 || it > 0) } ?: return null
        val state = fields[5].toIntOrNull()?.takeIf { it in 0..13 }
        return BydBatteryReading(percent, range, remaining, state?.let { it == 1 })
    }

    fun speed(line: String, now: Long): Pair<VehicleSpeedSample, VehicleGear>? {
        val fields = line.split(' ')
        if (fields.size != 5 || fields[0] != "XCERTPLAY" || fields[1] != "speed") return null
        val elapsed = fields[2].toLongOrNull()?.takeIf { it >= 0 && now - it in 0..2_000L } ?: return null
        val kmh = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..300.0 } ?: return null
        val gear = when (fields[4].toIntOrNull()) {
            1 -> VehicleGear.PARK
            2 -> VehicleGear.REVERSE
            3 -> VehicleGear.NEUTRAL
            4, 5, 6 -> VehicleGear.DRIVE // D, manual and sport use the same forward direction.
            else -> return null
        }
        return VehicleSpeedSample(elapsed, kmh / 3.6) to gear
    }
}

/** One session's battery cache. ADB never runs on a phone callback, audio worker or UI thread. */
class BydBatteryProvider(context: Context, onProgress: (String) -> Unit = {}) : VehicleStatusProvider {
    private val capacityKwh = BydVehicleSettings.capacityKwh(context)
    private var reading: BydBatteryReading? = null
    private var receivedAt = 0L
    private var fullKwh: Double? = capacityKwh.takeIf { it > 0 }
    private val reader = BydSdkStream(context.applicationContext, "battery", { line ->
        BydVehicleData.battery(line, capacityKwh)?.let { sample ->
            synchronized(this) {
                reading = sample
                receivedAt = SystemClock.elapsedRealtime()
                if (capacityKwh == 0.0 && sample.percent >= 20) {
                    fullKwh = (sample.remainingKwh * 100 / sample.percent).takeIf { it.isFinite() && it > 0 && it <= 200 }
                }
            }
            Log.i("xcertplay-BYD-Battery", "Battery reading percent=${sample.percent} electricRangeKm=${sample.rangeKm}")
            onProgress("BYD battery percent=${sample.percent} electricRangeKm=${sample.rangeKm}")
        }
    }, { synchronized(this) { reading = null } }, onProgress)

    init { reader.start() }

    @Synchronized override fun snapshot(): VehicleStatusSnapshot? {
        val data = reading?.takeIf { SystemClock.elapsedRealtime() - receivedAt <= 90_000L } ?: return null
        val full = (fullKwh ?: if (data.percent > 0) data.remainingKwh * 100 / data.percent else return null)
            .takeIf { it.isFinite() && it > 0 && it <= 200 && it >= data.remainingKwh } ?: return null
        return VehicleStatusSnapshot(
            rangeKm = data.rangeKm,
            rangeWarning = data.percent <= 20,
            batteryPercent = data.percent,
            currentChargeWh = (data.remainingKwh * 1000).roundToLong(),
            maxChargeWh = (full * 1000).roundToLong(),
            maxRangeKm = if (data.percent > 0) (data.rangeKm * 100 / data.percent).roundToInt() else 0,
            charging = data.charging,
        )
    }

    override fun close() = reader.close()
}

/** Samples SDK speed at 4 Hz and gear at 1 Hz via one persistent shell process. */
class BydWheelSpeedSource(context: Context, private val onProgress: (String) -> Unit = {}) : VehicleSpeedSource {
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reader: BydSdkStream? = null
    private var generation = 0
    private val app = context.applicationContext

    @Synchronized override fun start() {
        if (reader != null) return
        val request = ++generation
        var firstSample = true
        reader = BydSdkStream(app, "speed", { line ->
            BydVehicleData.speed(line, SystemClock.elapsedRealtime())?.let { (sample, currentGear) ->
                synchronized(this) {
                    if (generation != request) return@synchronized
                    if (samples.size == 40) samples.removeAt(0)
                    samples.add(sample)
                    gear = currentGear
                    if (firstSample) {
                        firstSample = false
                        onProgress("BYD wheel speed first sample=${sample.metersPerSecond}m/s gear=${currentGear.letter}")
                    }
                }
            }
        }, { synchronized(this) { if (generation == request) { samples.clear(); gear = null } } }, onProgress).also { it.start() }
    }

    override fun stop() {
        val previous = synchronized(this) { reader.also { generation++; reader = null; samples.clear(); gear = null } }
        previous?.close()
    }

    @Synchronized override fun drain(): VehicleSpeedReading? {
        val currentGear = gear ?: return null
        val now = SystemClock.elapsedRealtime()
        val fresh = samples.filter { now - it.elapsedMillis in 0..2_000L }
        samples.clear()
        return fresh.takeIf { it.isNotEmpty() }?.let { VehicleSpeedReading(currentGear, it) }
    }
}
