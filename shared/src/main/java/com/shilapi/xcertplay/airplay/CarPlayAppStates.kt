package com.shilapi.xcertplay.airplay

/**
 * Readable CarPlay app states from modesChanged: who owns the phone call, Siri and turn-by-turn
 * navigation. A WeChat call shows as phoneCall=iPhone without any iAP2 call state.
 */
internal object CarPlayAppStates {
    private const val SPEECH = 1L
    private const val PHONE_CALL = 2L
    private const val TURN_BY_TURN = 3L

    fun phoneCallActive(appStates: Any?): Boolean? {
        val state = (appStates as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { (it["appStateID"] as? Number)?.toLong() == PHONE_CALL } ?: return null
        return when ((state["entity"] as? Number)?.toLong()) {
            0L -> false
            1L, 2L -> true
            else -> null
        }
    }

    fun describe(appStates: Any?): String? {
        val states = (appStates as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return null
        fun state(id: Long) = states.firstOrNull { (it["appStateID"] as? Number)?.toLong() == id }
        fun owner(id: Long): String = when ((state(id)?.get("entity") as? Number)?.toLong()) {
            null -> "?"
            0L -> "off"
            1L -> "iPhone"
            2L -> "car"
            else -> state(id)?.get("entity").toString()
        }
        val speechMode = (state(SPEECH)?.get("speechMode") as? Number)?.toLong()
        val speech = owner(SPEECH) + when (speechMode) {
            null, -1L -> ""
            1L -> "(speaking)"
            2L -> "(recognizing)"
            else -> "(mode $speechMode)"
        }
        return "CarPlay app state: phoneCall=${owner(PHONE_CALL)} speech=$speech navigation=${owner(TURN_BY_TURN)}"
    }
}
