package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/** Explicit settings-page check; background readers never request key approval. */
object BydVehicleAccess {
    data class Result(val access: LocalAdb.Access, val speedAvailable: Boolean = false,
        val batteryAvailable: Boolean = false, val details: String = "")

    fun check(context: Context): Result = LocalAdb(AdbKeys.load(context)).use { adb ->
        val access = adb.connect(mayAsk = true)
        if (access != LocalAdb.Access.READY) return@use Result(access)
        val speed = adb.shell(BydSdkStream.command(context, "speed", once = true)).orEmpty()
        val speedAvailable = speed.lineSequence().any { BydVehicleData.speed(it, SystemClock.elapsedRealtime()) != null }
        val battery = adb.shell(BydSdkStream.command(context, "battery", once = true)).orEmpty()
        Result(access,
            speedAvailable,
            battery.lineSequence().any { BydVehicleData.battery(it, BydVehicleSettings.capacityKwh(context)) != null },
            listOf(speed, battery).filter { it.isNotBlank() }.joinToString("\n").take(800))
    }

    /** Runs the cluster call-info test through adb shell; may ask for adb approval. */
    fun callInfoTest(context: Context): Pair<LocalAdb.Access, List<String>> = LocalAdb(AdbKeys.load(context)).use { adb ->
        val access = adb.connect(mayAsk = true)
        if (access != LocalAdb.Access.READY) return@use access to emptyList()
        val lines = mutableListOf<String>()
        adb.stream(BydSdkStream.command(context, "calltest")) { line -> lines += line.removePrefix("XCERTPLAY ") }
        access to lines
    }
}
