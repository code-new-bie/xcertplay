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

    /** A channel's operating frequency does not tell us the negotiated channel width. */
    fun label(channel: Int): String {
        val frequency = frequency(channel) ?: return "Automatic (5 GHz)"
        val band = if (frequency < 5000) "2.4 GHz" else "5 GHz"
        return "$band · Channel $channel · $frequency MHz"
    }

    /**
     * Group creation attempts in order: the requested frequency first, then the automatic 5 GHz
     * band when the radio rejects it. null means the automatic band.
     */
    fun creationFrequencies(channel: Int): List<Int?> =
        frequency(channel)?.let { listOf(it, null) } ?: listOf(null)
}
