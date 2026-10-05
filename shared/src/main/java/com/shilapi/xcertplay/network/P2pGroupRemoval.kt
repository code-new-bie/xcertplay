package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A removeGroup callback alone does not prove that the temporary CarPlay hotspot is gone. */
internal fun awaitP2pGroupRemoval(
    timeoutMillis: Long,
    remove: (WifiP2pManager.ActionListener) -> Unit,
    query: (WifiP2pManager.GroupInfoListener) -> Unit,
) {
    require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val removed = CountDownLatch(1)
    val rejection = AtomicReference<Int?>()
    fun await(latch: CountDownLatch): Boolean =
        latch.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    try {
        remove(object : WifiP2pManager.ActionListener {
            override fun onSuccess() = removed.countDown()
            override fun onFailure(reason: Int) {
                rejection.set(reason)
                removed.countDown()
            }
        })
        if (!await(removed)) throw IOException("Wi-Fi P2P removeGroup timed out")
        while (true) {
            val inspected = CountDownLatch(1)
            val group = AtomicReference<WifiP2pGroup?>()
            query { result ->
                group.set(result)
                inspected.countDown()
            }
            if (!await(inspected)) throw IOException("Wi-Fi P2P group disappearance was not confirmed")
            if (group.get() == null) return // A rejected removal is harmless if there is no group.
            rejection.get()?.let { throw IOException("Wi-Fi P2P removeGroup rejected: reason $it; group still present") }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Wi-Fi P2P group is still present after removal")
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)))
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while removing Wi-Fi P2P group", interrupted)
    } catch (failure: RuntimeException) {
        throw IOException("Wi-Fi P2P group removal could not be completed", failure)
    }
}
