package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayVoiceKeyTest {
    @Test fun firmwareLongPressInvokesOnceOnRelease() {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, 312)
        val up = KeyEvent(KeyEvent.ACTION_UP, 312)
        assertTrue(CarPlayVoiceKey.handles(down.keyCode))
        assertFalse(CarPlayVoiceKey.invokesSiri(down))
        assertTrue(CarPlayVoiceKey.invokesSiri(up))
    }

    @Test fun shortVoicePressAndOtherKeysRemainWithTheHeadUnit() {
        for (key in listOf(304, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertFalse(CarPlayVoiceKey.handles(key))
            assertFalse(CarPlayVoiceKey.invokesSiri(KeyEvent(KeyEvent.ACTION_UP, key)))
        }
    }

    @Test fun canceledPressDoesNotInvokeSiri() {
        val up = KeyEvent(0, 100, KeyEvent.ACTION_UP, 312, 0, 0, 0, 312, KeyEvent.FLAG_CANCELED)
        assertFalse(CarPlayVoiceKey.invokesSiri(up))
    }
}
