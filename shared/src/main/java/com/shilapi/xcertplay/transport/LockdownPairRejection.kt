package com.shilapi.xcertplay.transport

/**
 * Lockdown errors that mean the iPhone no longer accepts a saved pair record, so pairing again is
 * the fix. InvalidHostID is the iPhone not knowing the record's host at all: it forgot this car
 * (trust reset, some iOS updates) or it is another iPhone. Ported from DiPlay.
 */
object LockdownPairRejection {
    private val CODES = listOf("InvalidPairRecord", "InvalidHostID")

    /** The Lockdown error code when [error] or one of its causes is such a rejection, else null. */
    fun of(error: Throwable): String? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_DEPTH) {
            val message = cause.message.orEmpty()
            CODES.firstOrNull { message.contains(it, ignoreCase = true) }?.let { return it }
            cause = cause.cause
            depth++
        }
        return null
    }

    private const val MAX_DEPTH = 8
}
