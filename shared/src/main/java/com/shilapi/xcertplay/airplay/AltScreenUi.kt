package com.shilapi.xcertplay.airplay

/**
 * The instrument cluster as CarPlay's alternate screen (type 111): /info declares it with an
 * initial URL, SETUP enables "altScreen" with "uiContext" (the iPhone builds alternate UI only with
 * both), and once the cluster's screen stream is set up, its SETUP response has gone out and the
 * event channel is ready, showUI names what it shows. Without that the iPhone may stream only a black
 * frame. After upstream AirPlayDisplayControlFactory and DiPlay's dashboard map.
 */
internal class AltScreenUi(private val send: (Map<String, Any?>) -> Boolean) {
    private var url: String? = null
    private var enabled = false
    private var streamReady = false
    private var eventReady = false
    private var sent = false

    /** The cluster content chosen at /info. */
    @Synchronized
    fun select(url: String?) {
        if (this.url != url) sent = false
        this.url = url
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        this.enabled = enabled
        if (!enabled) sent = false
        return flush()
    }

    /** The cluster's screen stream is set up and its SETUP response sent, or it was torn down. */
    @Synchronized
    fun setStreamReady(ready: Boolean): Boolean {
        streamReady = ready
        if (!ready) sent = false
        return flush()
    }

    @Synchronized
    fun setEventReady(ready: Boolean): Boolean {
        eventReady = ready
        if (!ready) sent = false
        return flush()
    }

    private fun flush(): Boolean {
        if (sent || !enabled || !streamReady || !eventReady) return false
        val target = url ?: return false
        sent = send(command(target))
        return sent
    }

    companion object {
        const val FEATURE = "altScreen"
        const val CONTEXT_FEATURE = "uiContext"

        fun command(url: String): Map<String, Any?> = linkedMapOf(
            "type" to "showUI",
            "params" to linkedMapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to url),
        )

        /**
         * What the cluster shows: [preferred] when the iPhone offers it (its /info request lists
         * altScreenURLs), else another instrument-cluster content it offers, else its first. With
         * nothing offered, [preferred] stays.
         */
        fun choose(preferred: String?, offered: List<String>): String? = when {
            offered.isEmpty() || preferred in offered -> preferred
            else -> offered.firstOrNull { it.startsWith(CLUSTER_URL_PREFIX) } ?: offered.first()
        }

        /** altScreenURLs from the iPhone's /info request body. */
        fun offered(request: Any?): List<String> = ((request as? Map<*, *>)?.get("altScreenURLs") as? List<*>)
            .orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }.distinct()

        private const val CLUSTER_URL_PREFIX = "maps:/car/instrumentcluster"
    }
}
