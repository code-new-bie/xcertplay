package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Whether wireless CarPlay takes the iPhone's calls and audio off classic Bluetooth. */
object BluetoothHandoffSettings {
    private const val PREFS = "xcertplay_bluetooth_handoff"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Does what CarPlay's disableBluetooth asks: while a wireless session runs, the head unit's
 * hands-free (HFP client) and Bluetooth audio (A2DP sink) links to that iPhone are turned off, so
 * calls and media go only through CarPlay. Otherwise the iPhone keeps playing media to Bluetooth,
 * the stock phone shows call popups, and Bluetooth traffic competes with Wi-Fi for the radio.
 *
 * Each profile's connection priority is saved and set to off, which BYD's Bluetooth stack checks
 * before any reconnect, and the link is disconnected. When the session ends the priority is
 * restored and the link reconnected. The saved priorities persist, so a process killed while
 * holding is repaired the next time the app starts ([restoreLeftover]).
 *
 * BYD's HeadsetClientService and A2dpSinkService only require BLUETOOTH_ADMIN; the methods are
 * hidden API and are called reflectively. All state lives on the main thread.
 */
class BluetoothHandoff(
    context: Context,
    private val diagnostic: (String) -> Unit,
) : Closeable {
    private enum class Link(val profile: Int, val label: String, val stateChanged: String) {
        CALLS(16, "HFP", "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"),
        AUDIO(11, "A2DP", "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"),
    }

    private val app = context.applicationContext
    private val prefs = BluetoothHandoffSettings.prefs(app)
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = runCatching {
        app.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()
    private val proxies = mutableMapOf<Link, BluetoothProfile>()
    private val requested = mutableSetOf<Link>()
    private val pending = mutableMapOf<Link, MutableList<(BluetoothProfile) -> Unit>>()
    private var target: String? = null
    private var receiverRegistered = false
    private var closed = false
    private val guards = Link.values().associateWith { HandoffReconnectGuard() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val link = Link.values().firstOrNull { it.stateChanged == intent.action } ?: return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val address = target ?: return
            if (!device.address.equals(address, ignoreCase = true)) return
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
            if (guards.getValue(link).allow(SystemClock.elapsedRealtime())) {
                report("${link.label} to $address reconnected during CarPlay; disconnecting again")
                withProxy(link) { disconnect(link, it, address) }
            } else {
                report("${link.label} to $address keeps reconnecting; leaving it connected")
            }
        }
    }

    /** Turns off HFP and A2DP to [address] (the iPhone's Bluetooth address) until [release]. */
    fun hold(address: String) {
        handler.post {
            if (closed) return@post
            val normalized = address.trim().uppercase()
            if (!BluetoothAdapterAddress.isValid(normalized)) {
                report("Bluetooth handoff skipped: invalid iPhone Bluetooth address $address")
                return@post
            }
            if (target == normalized) return@post
            if (target != null) releaseNow()
            target = normalized
            guards.values.forEach(HandoffReconnectGuard::reset)
            registerReceiver()
            for (link in Link.values()) {
                withProxy(link) { profile ->
                    if (target != normalized) return@withProxy
                    turnOff(link, profile, normalized)
                }
            }
        }
    }

    /** Restores the saved priorities and reconnects. */
    fun release() {
        handler.post { releaseNow() }
    }

    /** Repairs priorities left off by a process that died while holding. */
    fun restoreLeftover() {
        if (!leftoverChecked.compareAndSet(false, true)) return
        handler.post {
            val address = prefs.getString(KEY_HELD_ADDRESS, null) ?: return@post
            if (target != null) return@post
            report("Bluetooth handoff: restoring priorities left from an earlier session for $address")
            restore(address)
        }
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            releaseNow()
            closed = true
            for ((link, profile) in proxies) runCatching { adapter?.closeProfileProxy(link.profile, profile) }
            proxies.clear()
        }
    }

    private fun turnOff(link: Link, profile: BluetoothProfile, address: String) {
        val device = remoteDevice(address) ?: return
        val savedKey = priorityKey(link)
        if (!prefs.contains(savedKey)) {
            val original = (call(profile, "getPriority", device) as? Int) ?: PRIORITY_ON
            prefs.edit().putString(KEY_HELD_ADDRESS, address).putInt(savedKey, original).commit()
        }
        val off = describe(call(profile, "setPriority", device, PRIORITY_OFF))
        val state = runCatching { profile.getConnectionState(device) }.getOrDefault(-1)
        val disconnected = if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
            describe(call(profile, "disconnect", device))
        } else {
            "not connected"
        }
        report("${link.label} to $address off for CarPlay: priority off $off, disconnect $disconnected")
    }

    private fun disconnect(link: Link, profile: BluetoothProfile, address: String) {
        val device = remoteDevice(address) ?: return
        report("${link.label} to $address disconnect: ${describe(call(profile, "disconnect", device))}")
    }

    private fun releaseNow() {
        val address = target ?: return
        target = null
        unregisterReceiver()
        restore(address)
    }

    private fun restore(address: String) {
        for (link in Link.values()) {
            val key = priorityKey(link)
            if (!prefs.contains(key)) continue
            val original = prefs.getInt(key, PRIORITY_ON).takeIf { it >= 0 } ?: PRIORITY_ON
            withProxy(link) { profile ->
                val device = remoteDevice(address) ?: return@withProxy
                val priority = describe(call(profile, "setPriority", device, original))
                val reconnect = if (original > PRIORITY_OFF) describe(call(profile, "connect", device)) else "skipped"
                prefs.edit().remove(key).apply {
                    if (Link.values().none { other -> other != link && prefs.contains(priorityKey(other)) }) {
                        remove(KEY_HELD_ADDRESS)
                    }
                }.commit()
                report("${link.label} to $address restored after CarPlay: priority $original $priority, reconnect $reconnect")
            }
        }
    }

    /** Calls a hidden profile method by reflection; returns its result or the failure. */
    private fun call(profile: BluetoothProfile, method: String, device: BluetoothDevice, vararg extra: Int): Any? =
        try {
            val types = arrayOf<Class<*>>(BluetoothDevice::class.java) + extra.map { Int::class.javaPrimitiveType!! }
            profile.javaClass.getMethod(method, *types).invoke(profile, device, *extra.toTypedArray())
        } catch (error: Throwable) {
            (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        }

    private fun describe(result: Any?): String = when (result) {
        true -> "ok"
        false -> "rejected"
        is Throwable -> "failed ${result.javaClass.simpleName}: ${result.message.orEmpty().take(120)}"
        else -> result.toString()
    }

    private fun withProxy(link: Link, action: (BluetoothProfile) -> Unit) {
        proxies[link]?.let {
            action(it)
            return
        }
        pending.getOrPut(link) { mutableListOf() }.add(action)
        if (!requested.add(link)) return
        val bluetooth = adapter
        if (bluetooth == null) {
            report("Bluetooth handoff unavailable: no Bluetooth adapter")
            return
        }
        val ok = runCatching {
            bluetooth.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, service: BluetoothProfile) {
                    handler.post {
                        if (closed) {
                            runCatching { bluetooth.closeProfileProxy(link.profile, service) }
                            return@post
                        }
                        proxies[link] = service
                        pending.remove(link)?.forEach { it(service) }
                    }
                }

                override fun onServiceDisconnected(profileId: Int) {
                    handler.post {
                        proxies.remove(link)
                        requested.remove(link)
                    }
                }
            }, link.profile)
        }.getOrDefault(false)
        if (!ok) {
            requested.remove(link)
            pending.remove(link)
            report("Bluetooth handoff: the head unit has no ${link.label} profile")
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply { Link.values().forEach { addAction(it.stateChanged) } }
        receiverRegistered = runCatching {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter, null, handler)
        }.onFailure { report("Bluetooth reconnect watch unavailable: ${it.javaClass.simpleName}") }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    private fun priorityKey(link: Link) = "saved_priority_${link.name.lowercase()}"

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }

    private companion object {
        const val PRIORITY_OFF = 0
        const val PRIORITY_ON = 100
        const val KEY_HELD_ADDRESS = "held_address"
        val leftoverChecked = AtomicBoolean(false)
    }
}

/** Disconnects at most [maxRetries] reconnects per [windowMillis], so a fight with the head unit stops. */
internal class HandoffReconnectGuard(
    private val maxRetries: Int = 3,
    private val windowMillis: Long = 60_000L,
) {
    private val attempts = ArrayDeque<Long>()

    fun allow(nowMillis: Long): Boolean {
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= windowMillis) attempts.removeFirst()
        if (attempts.size >= maxRetries) return false
        attempts.addLast(nowMillis)
        return true
    }

    fun reset() = attempts.clear()
}

internal object BluetoothAdapterAddress {
    private val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    fun isValid(address: String): Boolean = MAC.matches(address) && address != "00:00:00:00:00:00"
}
