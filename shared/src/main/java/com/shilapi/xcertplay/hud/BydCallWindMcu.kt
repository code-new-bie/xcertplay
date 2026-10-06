package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/** Sends the BYD MCU call-state event from the app UID, which owns the private BYD setting permission. */
internal class BydCallWindMcu(
    private val hasPermission: () -> Boolean,
    private val write: (Int) -> Int,
    private val emit: (String) -> Unit,
    private val uid: Int,
) {
    constructor(context: Context, emit: (String) -> Unit) : this(
        hasPermission = {
            context.checkSelfPermission(BYDAUTO_SETTING_COMMON) == PackageManager.PERMISSION_GRANTED
        },
        write = LazyMcuWriter(context)::set,
        emit = emit,
        uid = Process.myUid(),
    )

    private var writeAttempted = false

    fun activate() {
        val permissionGranted = hasPermission()
        emit("mcu app uid=$uid permission=$permissionGranted")
        check(permissionGranted) { "missing $BYDAUTO_SETTING_COMMON" }
        writeAttempted = true
        val result = write(MCU_CALL_ACTIVE)
        emit("mcu call-state active result=$result success=${result == MCU_SUCCESS}")
        check(result == MCU_SUCCESS) { "mcu call-state active failed result=$result" }
    }

    fun release(): String {
        if (!writeAttempted) return "not attempted"
        val result = write(MCU_CALL_RELEASED)
        emit("mcu call-state release result=$result success=${result == MCU_SUCCESS}")
        check(result == MCU_SUCCESS) { "mcu call-state release failed result=$result" }
        writeAttempted = false
        return "released"
    }

    private class LazyMcuWriter(context: Context) {
        private val device by lazy { McuState(context) }

        fun set(value: Int): Int = device.set(value)
    }

    private class McuState(context: Context) {
        private val valueType = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
        private val valueConstructor = valueType.getDeclaredConstructor()
        private val intValue = valueType.getField("intValue")
        private val deviceType = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
        private val device = deviceType.getMethod("getInstance", Context::class.java)
            .invoke(null, context)
        private val setter = deviceType.getMethod("set", IntArray::class.java, valueType)

        fun set(value: Int): Int {
            val eventValue = valueConstructor.newInstance()
            intValue.setInt(eventValue, value)
            return (setter.invoke(device, intArrayOf(MCU_CALL_STATE_EVENT), eventValue) as Number).toInt()
        }
    }

    private companion object {
        const val BYDAUTO_SETTING_COMMON = "android.permission.BYDAUTO_SETTING_COMMON"
        const val MCU_CALL_STATE_EVENT = -0x55fffead
        const val MCU_CALL_ACTIVE = 0
        const val MCU_CALL_RELEASED = 1
        const val MCU_SUCCESS = 0
    }
}
