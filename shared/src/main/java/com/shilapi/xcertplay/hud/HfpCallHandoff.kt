package com.shilapi.xcertplay.hud

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

/**
 * Hands the iPhone's calls to CarPlay, as CarPlay's disableBluetooth command asks: while a wireless
 * session runs, the head unit's Bluetooth hands-free (HFP client) link to that iPhone is
 * disconnected, so the stock BYD phone never learns of the call and shows no popup. Reconnects by
 * the head unit are disconnected again, within [HfpReconnectGuard]'s limit. When the session ends
 * the link is reconnected. Nothing persistent is changed, so a killed process leaves no residue.
 *
 * BYD's HeadsetClientService only requires BLUETOOTH_ADMIN for connect and disconnect; the methods
 * are hidden API and are called reflectively. All state lives on the main thread.
 */
class HfpCallHandoff(
    context: Context,
    private val diagnostic: (String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = runCatching {
        app.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()
    private var proxy: BluetoothProfile? = null
    private var proxyRequested = false
    private var pending: (() -> Unit)? = null
    private var target: String? = null
    private var disconnectedByUs = false
    private var receiverRegistered = false
    private var closed = false
    private val guard = HfpReconnectGuard()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CONNECTION_STATE_CHANGED) return
            val device = deviceExtra(intent) ?: return
            val address = target ?: return
            if (!device.address.equals(address, ignoreCase = true)) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (state != BluetoothProfile.STATE_CONNECTED) return
            if (guard.allow(SystemClock.elapsedRealtime())) {
                report("HFP to $address reconnected during CarPlay; disconnecting again")
                disconnect(address)
            } else {
                report("HFP to $address keeps reconnecting; leaving it connected")
            }
        }
    }

    /** Disconnects HFP to [address] (the iPhone's Bluetooth address) until [release]. */
    fun hold(address: String) {
        handler.post {
            if (closed) return@post
            val normalized = address.trim().uppercase()
            if (!BluetoothAdapterAddress.isValid(normalized)) {
                report("HFP handoff skipped: invalid iPhone Bluetooth address $address")
                return@post
            }
            if (target == normalized) return@post
            if (target != null) releaseNow()
            target = normalized
            guard.reset()
            registerReceiver()
            withProxy { disconnect(normalized) }
        }
    }

    /** Stops holding the link and reconnects HFP if it was disconnected here. */
    fun release() {
        handler.post { releaseNow() }
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            releaseNow()
            closed = true
            proxy?.let { profile -> runCatching { adapter?.closeProfileProxy(HEADSET_CLIENT, profile) } }
            proxy = null
        }
    }

    private fun releaseNow() {
        val address = target ?: return
        target = null
        pending = null
        unregisterReceiver()
        if (!disconnectedByUs) return
        disconnectedByUs = false
        withProxy {
            val result = invoke("connect", address)
            report("HFP to $address reconnect after CarPlay: $result")
        }
    }

    private fun disconnect(address: String) {
        val profile = proxy ?: return
        val device = remoteDevice(address) ?: return
        val state = runCatching { profile.getConnectionState(device) }.getOrDefault(-1)
        if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_CONNECTING) {
            report("HFP to $address not connected (state=$state); CarPlay already owns calls")
            return
        }
        val result = invoke("disconnect", address)
        if (result == "ok") disconnectedByUs = true
        report("HFP to $address disconnected for CarPlay calls: $result")
    }

    /** Calls a hidden BluetoothHeadsetClient method and describes the outcome for the log. */
    private fun invoke(method: String, address: String): String {
        val profile = proxy ?: return "no HFP client profile"
        val device = remoteDevice(address) ?: return "no Bluetooth adapter"
        return try {
            val accepted = profile.javaClass.getMethod(method, BluetoothDevice::class.java)
                .invoke(profile, device) as? Boolean
            if (accepted == true) "ok" else "rejected"
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            "failed ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(120)}"
        }
    }

    private fun withProxy(action: () -> Unit) {
        if (proxy != null) {
            action()
            return
        }
        pending = action
        if (proxyRequested) return
        val bluetooth = adapter
        if (bluetooth == null) {
            report("HFP handoff unavailable: no Bluetooth adapter")
            return
        }
        proxyRequested = true
        val requested = runCatching {
            bluetooth.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, service: BluetoothProfile) {
                    handler.post {
                        if (closed) {
                            runCatching { bluetooth.closeProfileProxy(HEADSET_CLIENT, service) }
                            return@post
                        }
                        proxy = service
                        pending?.also { pending = null }?.invoke()
                    }
                }

                override fun onServiceDisconnected(profileId: Int) {
                    handler.post {
                        proxy = null
                        proxyRequested = false
                    }
                }
            }, HEADSET_CLIENT)
        }.getOrElse { error ->
            report("HFP handoff unavailable: ${error.javaClass.simpleName}")
            false
        }
        if (!requested) {
            proxyRequested = false
            pending = null
            report("HFP handoff unavailable: the head unit has no HFP client profile")
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = runCatching {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, IntentFilter(CONNECTION_STATE_CHANGED), null, handler)
        }.onFailure { report("HFP reconnect watch unavailable: ${it.javaClass.simpleName}") }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }

    private companion object {
        /** BluetoothProfile.HEADSET_CLIENT, hidden in the public SDK. */
        const val HEADSET_CLIENT = 16
        const val CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    }
}

/** Disconnects at most [maxRetries] reconnects per [windowMillis], so a fight with the head unit stops. */
internal class HfpReconnectGuard(
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
