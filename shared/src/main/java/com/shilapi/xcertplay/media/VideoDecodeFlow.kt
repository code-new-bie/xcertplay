package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Apply TCP backpressure to compressed pictures, never discard a reference on queue overflow.
 * Entries still queued at [close] go to [onDiscard], so pooled buffers are returned.
 */
internal class VideoWorkQueue<T>(capacity: Int, private val onDiscard: (T) -> Unit = {}) : Closeable {
    private data class Entry<T>(val value: T, val frame: Boolean)
    private val entries = LinkedBlockingQueue<Entry<T>>()
    private val slots = Semaphore(capacity)
    @Volatile private var closed = false
    val size: Int get() = entries.size

    /** False when the queue closed first; [value] then stays the caller's. */
    fun frame(value: T): Boolean {
        while (!closed) {
            if (!slots.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
            return synchronized(this) {
                if (closed) {
                    slots.release()
                    false
                } else {
                    entries.offer(Entry(value, true))
                }
            }
        }
        return false
    }

    @Synchronized
    fun control(value: T) {
        if (!closed) entries.offer(Entry(value, false))
    }

    fun poll(timeoutMs: Long): T? {
        val entry = entries.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (entry.frame) slots.release()
        return entry.value
    }

    @Synchronized
    override fun close() {
        closed = true
        while (true) {
            val entry = entries.poll() ?: break
            if (entry.frame) slots.release()
            onDiscard(entry.value)
        }
    }
}

/**
 * A tick must run even when the sender is idle. An unavailable input buffer is not a lost frame.
 * A picture's [release] runs once the codec has copied it, or when it is cleared.
 */
internal class VideoDecodePump(private val port: Port) {
    interface Port {
        fun drain()
        fun queue(bytes: ByteArray, size: Int, presentationTimeUs: Long): Boolean
    }
    private class Input(val bytes: ByteArray, val size: Int, val ptsUs: Long, val release: () -> Unit)
    private var pending: Input? = null
    val hasPending: Boolean get() = pending != null

    fun submit(bytes: ByteArray, size: Int, presentationTimeUs: Long, release: () -> Unit = {}) {
        check(pending == null)
        require(size in 0..bytes.size)
        pending = Input(bytes, size, presentationTimeUs, release)
    }

    fun tick() {
        port.drain()
        pending?.let {
            if (port.queue(it.bytes, it.size, it.ptsUs)) {
                pending = null
                it.release()
            }
        }
        port.drain()
    }

    fun clear() {
        pending?.release()
        pending = null
    }
}

/** Every new decoder needs random access. CRA's RASL pictures may refer to the discarded GOP. */
internal class VideoSyncGate(private val codec: VideoCodec) {
    private var waiting = true
    private var skipRasl = false
    val waitingForRandomAccess: Boolean get() = waiting

    fun accept(annexB: ByteArray, length: Int = annexB.size): Boolean {
        // Only the NAL headers decide; copying every unit would duplicate the whole picture.
        val headers = MediaCodecSupport.annexBNalHeaders(annexB, length)
        require(headers.isNotEmpty()) { "Empty or malformed video access unit" }
        val types = headers.map {
            require(it[0].toInt() and 0x80 == 0) { "Invalid NAL header" }
            if (codec == VideoCodec.H265) {
                require(it.size >= 2 && it[1].toInt() and 7 != 0) { "Invalid HEVC NAL header" }
                (it[0].toInt() ushr 1) and 0x3f
            } else it[0].toInt() and 0x1f
        }
        val randomAccess = types.any { if (codec == VideoCodec.H265) it in 16..21 else it == 5 }
        if (waiting) {
            if (!randomAccess) return false
            waiting = false
            skipRasl = codec == VideoCodec.H265
        }
        if (skipRasl) {
            if (types.any { it == 8 || it == 9 }) return false
            if (types.any { it in 0..5 || it in 10..15 }) skipRasl = false
        }
        return true
    }
}
