package com.shilapi.xcertplay.hud

import android.content.Context

/** Optional vehicle reads are enabled explicitly and take effect on the next CarPlay session. */
object BydVehicleSettings {
    fun dcChargingEnabled(context: Context): Boolean = prefs(context).getBoolean("dc_charging", false)
    fun setDcChargingEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("dc_charging", enabled).apply() }
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_byd_vehicle", Context.MODE_PRIVATE)
    fun batteryEnabled(context: Context) = prefs(context).getBoolean("battery", false)
    fun speedEnabled(context: Context) = prefs(context).getBoolean("wheel_speed", false)
    fun capacityKwh(context: Context) = prefs(context).getFloat("capacity_kwh", 0f).toDouble()
    fun setBatteryEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("battery", enabled).apply()
    fun setSpeedEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("wheel_speed", enabled).apply()
    fun setCapacityKwh(context: Context, value: Double) {
        require(value.isFinite() && value in 0.0..200.0)
        prefs(context).edit().putFloat("capacity_kwh", value.toFloat()).apply()
    }
}
