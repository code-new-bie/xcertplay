package com.shilapi.xcertplay.network

/** Offered channels are requests; Android still enforces radio and country restrictions. */
object WifiChannelPreference {
    const val AUTOMATIC = 0

    val channels: List<Int> = listOf(AUTOMATIC) + (1..14) +
        (36..64 step 4) + (100..144 step 4) + (149..177 step 4)

    fun sanitize(channel: Int): Int = channel.takeIf { it in channels } ?: AUTOMATIC

    fun frequency(channel: Int): Int? = when (val valid = sanitize(channel)) {
        AUTOMATIC -> null
        14 -> 2484
        in 1..13 -> 2407 + valid * 5
        else -> 5000 + valid * 5
    }

    /**
     * Group creation attempts in order: the requested frequency first, then the automatic 5 GHz
     * band when the radio rejects it. null means the automatic band.
     */
    fun creationFrequencies(channel: Int): List<Int?> =
        frequency(channel)?.let { listOf(it, null) } ?: listOf(null)
}
