package com.shilapi.xcertplay.media

/**
 * Waits for a decoder input buffer, draining decoded output before every attempt. A decoder whose
 * output buffers are full keeps all its input buffers, so waiting without draining can only time
 * out. Ported from DiPlay (daffd2de, 7d974f42); see [acquire] for the time limit.
 */
internal object DecoderInputPump {
    /**
     * The input buffer [index], or -1 when none came within the limit. [stalledNs] is how long the
     * decoder held every input buffer: from the first attempt that found none until one came or the
     * wait ended; 0 when the first attempt got one.
     */
    class Result(val index: Int, val stalledNs: Long)

    /**
     * Drains, then asks for an input buffer, until one comes or [timeoutNs] has passed since the
     * call. At least one drain and one attempt always happen, however long the first drain takes.
     */
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        timeoutNs: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): Result {
        val start = nanoTime()
        var stalledSince = -1L
        while (running()) {
            drain()
            val attemptStart = nanoTime()
            val index = dequeue()
            val now = nanoTime()
            if (index >= 0) return Result(index, if (stalledSince < 0) 0L else now - stalledSince)
            if (stalledSince < 0) stalledSince = attemptStart
            if (now - start >= timeoutNs) return Result(-1, now - stalledSince)
        }
        return Result(-1, if (stalledSince < 0) 0L else nanoTime() - stalledSince)
    }
}
