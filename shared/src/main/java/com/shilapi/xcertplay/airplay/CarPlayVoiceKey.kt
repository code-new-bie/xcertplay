package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/** BYD firmware reports a long voice-button press as a separate key, not a held short press. */
object CarPlayVoiceKey {
    const val BYD_LONG_PRESS = 312

    fun handles(keyCode: Int): Boolean = keyCode == BYD_LONG_PRESS

    fun invokesSiri(event: KeyEvent): Boolean =
        handles(event.keyCode) && event.action == KeyEvent.ACTION_UP && !event.isCanceled
}
