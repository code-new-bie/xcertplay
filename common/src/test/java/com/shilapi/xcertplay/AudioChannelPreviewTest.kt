package com.shilapi.xcertplay

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioChannelPreviewTest {
    @Test fun playbackUsesBackgroundThreadAndReleasesTrack() {
        val tracks = CopyOnWriteArrayList<AudioTrack>()
        val usages = CopyOnWriteArrayList<Int>()
        val threads = CopyOnWriteArrayList<Thread>()
        val bytes = AtomicInteger()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { track, data, _ ->
            if (tracks.addIfAbsent(track)) usages.add(track.audioAttributes.usage)
            threads.add(Thread.currentThread())
            bytes.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            AudioChannelPreview { fail("Preview should be available") }.use { preview ->
                preview.play(3, navigation = false)
                awaitPlayback(preview)
                assertEquals(1, tracks.size)
                assertEquals(listOf(AudioAttributes.USAGE_MEDIA), usages)
                assertEquals(48_000 * 2 * 600 / 1000, bytes.get())
                assertTrue(threads.all { it !== Looper.getMainLooper().thread })
                assertEquals(AudioTrack.STATE_UNINITIALIZED, tracks.single().state)
            }
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test fun changingSelectionCancelsOldPlaybackAndStopAllowsReuse() {
        val tracks = CopyOnWriteArrayList<AudioTrack>()
        val usages = CopyOnWriteArrayList<Int>()
        val firstWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { track, _, _ ->
            if (tracks.addIfAbsent(track)) {
                usages.add(track.audioAttributes.usage)
                if (tracks.size == 1) {
                    firstWrite.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
            }
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            AudioChannelPreview { fail("Preview should be available") }.use { preview ->
                preview.play(3, navigation = false)
                assertTrue(firstWrite.await(5, TimeUnit.SECONDS))
                preview.play(5, navigation = false)
                releaseWrite.countDown()
                awaitPlayback(preview)
                assertEquals(2, tracks.size)
                assertEquals(AudioAttributes.USAGE_NOTIFICATION, usages[1])
                assertTrue(tracks.all { it.state == AudioTrack.STATE_UNINITIALIZED })

                preview.stop()
                preview.play(0, navigation = true)
                awaitPlayback(preview)
                assertEquals(3, tracks.size)
                assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                    usages[2])
                assertEquals(AudioTrack.STATE_UNINITIALIZED, tracks[2].state)
            }
        } finally {
            releaseWrite.countDown()
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test fun stoppedRequestsDoNotShowFailureAndClosedPreviewDoesNotReplay() {
        val unavailable = mutableListOf<Int>()
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        val preview = AudioChannelPreview(unavailable::add)
        try {
            preview.play(3, navigation = false)
            awaitPlayback(preview)
            preview.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(unavailable.isEmpty())

            preview.play(5, navigation = false)
            awaitPlayback(preview)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(5), unavailable)

            preview.close()
            preview.close()
            preview.play(3, navigation = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(5), unavailable)
        } finally {
            preview.close()
            ShadowAudioTrack.enableIllegalStateOnPlay(false)
        }
    }

    private fun awaitPlayback(preview: AudioChannelPreview) {
        val worker = ReflectionHelpers.getField<ExecutorService>(preview, "worker")
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }
}
