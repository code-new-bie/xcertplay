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

/** Which of the iPhone's classic Bluetooth links wireless CarPlay turns off. */
object BluetoothHandoffSettings {
    private const val PREFS = "xcertplay_bluetooth_handoff"
    private const val KEY_CALLS = "disconnect_calls"
    private const val KEY_AUDIO = "disconnect_audio"

    /** Hands-free (HFP): calls go through CarPlay and the stock phone shows no popup. */
    fun callsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CALLS, true)

    fun setCallsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CALLS, enabled).apply()

    /** Bluetooth audio (A2DP): music goes through CarPlay instead of Bluetooth. */
    fun audioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUDIO, true)

    fun setAudioEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUDIO, enabled).apply()

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Does what CarPlay's disableBluetooth asks: while wireless CarPlay runs, the head unit's
 * hands-free (HFP client) and/or Bluetooth audio (A2DP sink) links to that iPhone are turned off,
 * so calls and media go through CarPlay.
 *
 * Each link's connection priority is saved and set to off, which BYD's Bluetooth stack checks
 * before any reconnect, and the link is disconnected. One instance serves the whole process, so a
 * reconnect (settings saved, reconnect button, brief drop) keeps the links off: [releaseLater]
 * restores them only if no session holds them again within [RELEASE_DELAY_MS]. The saved
 * priorities persist, so a process killed while holding is repaired on the next start.
 *
 * BYD's HeadsetClientService and A2dpSinkService only require BLUETOOTH_ADMIN; the methods are
 * hidden API and are called reflectively. All state lives on the main thread.
 */
class BluetoothHandoff private constructor(context: Context) {
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
    private val held = mutableSetOf<Link>()
    private var target: String? = null
    private var receiverRegistered = false
    private val guards = Link.values().associateWith { HandoffReconnectGuard() }
    private val delayedRelease = Runnable { releaseNow("no CarPlay session for ${RELEASE_DELAY_MS / 1000}s") }

    /** Where progress is written; the controller of the current connection sets it. */
    @Volatile
    var diagnostic: (String) -> Unit = {}

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val link = Link.values().firstOrNull { it.stateChanged == intent.action } ?: return
            if (link !in held) return
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

    init {
        // Repair priorities left off by a process that died while holding.
        handler.post {
            if (!prefs.getBoolean(KEY_STUCK_OFF_REPAIRED, false)) repairStuckPhones()
            val address = prefs.getString(KEY_HELD_ADDRESS, null) ?: return@post
            if (target != null) return@post
            report("Bluetooth handoff: restoring links left off by an earlier run for $address")
            for (link in Link.values()) restoreLink(link, address)
        }
    }

    /**
     * Earlier builds could restore a phone's HFP/A2DP to off (they saved their own off as the
     * original), which stops the head unit from ever reconnecting it. Once, turn those links on again
     * for paired phones and reconnect them.
     */
    private fun repairStuckPhones() {
        val phones = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE }
        var pending = Link.values().size
        for (link in Link.values()) {
            withProxy(link) { profile ->
                for (phone in phones) {
                    val priority = call(profile, "getPriority", phone) as? Int ?: continue
                    if (priority != PRIORITY_OFF) continue
                    val set = describe(call(profile, "setPriority", phone, PRIORITY_ON))
                    val reconnect = describe(call(profile, "connect", phone))
                    report("${link.label} to ${phone.address} was left off; turned on $set, reconnect $reconnect")
                }
                if (--pending == 0) prefs.edit().putBoolean(KEY_STUCK_OFF_REPAIRED, true).apply()
            }
        }
    }

    /**
     * The app is exiting: restore the links now instead of after [RELEASE_DELAY_MS], and wait up to
     * [timeoutMillis] for it. Call from a background thread.
     */
    fun releaseNowBlocking(timeoutMillis: Long): Boolean {
        handler.post {
            handler.removeCallbacks(delayedRelease)
            if (target != null) {
                releaseNow("app exit")
            } else {
                prefs.getString(KEY_HELD_ADDRESS, null)?.let { address ->
                    for (link in Link.values()) restoreLink(link, address)
                }
            }
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Link.values().none { prefs.contains(priorityKey(it)) }) return true
            Thread.sleep(50)
        }
        return false
    }

    /**
     * Turns off the iPhone's HFP when [calls] and A2DP when [audio] until released; a link no
     * longer wanted is restored. Cancels a pending delayed release.
     */
    fun hold(address: String, calls: Boolean, audio: Boolean) {
        handler.post {
            handler.removeCallbacks(delayedRelease)
            val normalized = address.trim().uppercase()
            if (!BluetoothAdapterAddress.isValid(normalized)) {
                report("Bluetooth handoff skipped: invalid iPhone Bluetooth address $address")
                return@post
            }
            if (target != null && target != normalized) releaseNow("another iPhone took over")
            target = normalized
            registerReceiver()
            val wanted = buildSet {
                if (calls) add(Link.CALLS)
                if (audio) add(Link.AUDIO)
            }
            for (link in Link.values()) {
                when {
                    link in wanted && link !in held -> {
                        held += link
                        guards.getValue(link).reset()
                        withProxy(link) { profile -> if (link in held) turnOff(link, profile, normalized) }
                    }
                    link in wanted -> report("${link.label} to $normalized still off for CarPlay")
                    link in held -> {
                        held -= link
                        restoreLink(link, normalized)
                    }
                    else -> report("${link.label} to $normalized left connected (setting off)")
                }
            }
        }
    }

    /** Restores the links after [RELEASE_DELAY_MS] unless a session holds them again first. */
    fun releaseLater() {
        handler.post {
            if (target == null) return@post
            handler.removeCallbacks(delayedRelease)
            handler.postDelayed(delayedRelease, RELEASE_DELAY_MS)
            report("Bluetooth handoff: restoring in ${RELEASE_DELAY_MS / 1000}s unless CarPlay reconnects")
        }
    }

    private fun releaseNow(reason: String) {
        val address = target ?: return
        handler.removeCallbacks(delayedRelease)
        report("Bluetooth handoff: restoring links ($reason)")
        target = null
        held.clear()
        unregisterReceiver()
        for (link in Link.values()) restoreLink(link, address)
    }

    private fun turnOff(link: Link, profile: BluetoothProfile, address: String) {
        val device = remoteDevice(address) ?: return
        val savedKey = priorityKey(link)
        if (!prefs.contains(savedKey)) {
            // Off is what this class sets; reading it means an earlier run never restored, so it is
            // not the user's choice and must not be saved as the original.
            val read = call(profile, "getPriority", device) as? Int
            val original = read?.takeIf { it > PRIORITY_OFF } ?: PRIORITY_ON
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

    private fun restoreLink(link: Link, address: String) {
        val key = priorityKey(link)
        if (!prefs.contains(key)) return
        val original = prefs.getInt(key, PRIORITY_ON).takeIf { it > PRIORITY_OFF } ?: PRIORITY_ON
        withProxy(link) { profile ->
            if (link in held) return@withProxy
            val device = remoteDevice(address) ?: return@withProxy
            val priority = describe(call(profile, "setPriority", device, original))
            val reconnect = if (original > PRIORITY_OFF) describe(call(profile, "connect", device)) else "skipped"
            val editor = prefs.edit().remove(key)
            if (Link.values().none { other -> other != link && prefs.contains(priorityKey(other)) }) {
                editor.remove(KEY_HELD_ADDRESS)
            }
            editor.commit()
            report("${link.label} to $address restored: priority $original $priority, reconnect $reconnect")
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

    companion object {
        /** Long enough for a settings save or reconnect to bring the session back. */
        const val RELEASE_DELAY_MS = 15_000L
        private const val PRIORITY_OFF = 0
        private const val PRIORITY_ON = 100
        private const val KEY_HELD_ADDRESS = "held_address"
        private const val KEY_STUCK_OFF_REPAIRED = "stuck_off_repaired"

        @Volatile
        private var instance: BluetoothHandoff? = null

        fun shared(context: Context): BluetoothHandoff =
            instance ?: synchronized(this) {
                instance ?: BluetoothHandoff(context).also { instance = it }
            }
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
