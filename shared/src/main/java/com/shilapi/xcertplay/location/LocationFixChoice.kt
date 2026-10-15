package com.shilapi.xcertplay.location

/**
 * Which Android fix goes to the iPhone. A fresh GPS fix always wins: taking the newest of GPS and
 * network let a network fix tens of metres off replace satellite positioning whenever it came a
 * moment later. Fused or network fixes are used only while GPS is stale. Ages are on the monotonic
 * clock, so a head-unit clock set wrong cannot make every fix look fresh or stale. Ported from
 * upstream 2bb1ade.
 */
internal object LocationFixChoice {
    const val GPS_FRESH_MILLIS = 2_000L
    private const val GPS = "gps"

    fun <T> choose(
        fixes: List<T>,
        provider: (T) -> String?,
        ageMillis: (T) -> Long,
        maxAgeMillis: Long,
    ): T? {
        val usable = fixes.filter { ageMillis(it) in 0..maxAgeMillis }
        return usable.filter { provider(it) == GPS && ageMillis(it) <= GPS_FRESH_MILLIS }.minByOrNull(ageMillis)
            ?: usable.minByOrNull(ageMillis)
    }
}
