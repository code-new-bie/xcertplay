package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Size-classed buffers keep compressed pictures out of ART's large-object allocation path. */
internal class VideoBufferPool(
    private val maxPerBucket: Int = 12,
    private val maxRetainedBytes: Int = 16 * 1024 * 1024,
) {
    private val buckets = HashMap<Int, ArrayDeque<ByteArray>>()
    private var retainedBytes = 0

    fun acquire(length: Int): Lease {
        require(length >= 0)
        val capacity = bucketCapacity(length)
        val bytes = synchronized(buckets) {
            buckets[capacity]?.pollFirst()?.also { retainedBytes -= it.size }
        } ?: ByteArray(capacity)
        return Lease(bytes, length) { release(bytes) }
    }

    private fun release(bytes: ByteArray) {
        synchronized(buckets) {
            val bucket = buckets.getOrPut(bytes.size) { ArrayDeque() }
            if (bucket.size < maxPerBucket && retainedBytes <= maxRetainedBytes - bytes.size) {
                bucket.addFirst(bytes)
                retainedBytes += bytes.size
            }
        }
    }

    internal class Lease(
        val bytes: ByteArray,
        val length: Int,
        private val recycle: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) recycle() }
    }

    private fun bucketCapacity(length: Int): Int {
        if (length <= MIN_CAPACITY) return MIN_CAPACITY
        return Integer.highestOneBit(length - 1).let { highest ->
            if (highest > Int.MAX_VALUE / 2) length else highest shl 1
        }
    }

    private companion object { const val MIN_CAPACITY = 16 * 1024 }
}

/** One complete Annex-B picture. The receiver must close it after copying or forwarding it. */
class VideoAccessUnit internal constructor(
    private val lease: VideoBufferPool.Lease,
    val size: Int,
) : Closeable {
    init { require(size in 0..lease.bytes.size) }
    val bytes: ByteArray get() = lease.bytes
    override fun close() = lease.close()
}
