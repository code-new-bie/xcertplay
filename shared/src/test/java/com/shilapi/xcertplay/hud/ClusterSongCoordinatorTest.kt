package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSongCoordinatorTest {
    private class Handle : ClusterSongSessionHandle {
        var starts = 0
        var stopCount = 0
        var card: ClusterCard? = null
        var stopResult = CompletableFuture.completedFuture(true)
        override fun start() { starts++ }
        override fun update(card: ClusterCard?) { this.card = card }
        override fun stop(reason: String): CompletableFuture<Boolean> { stopCount++; return stopResult }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val tasks = mutableListOf<Runnable>()
    private val handles = mutableListOf<Handle>()
    private var enabled = true
    private val coordinator = ClusterSongCoordinator({ enabled }, { _, card, _ ->
        Handle().also { it.card = card; handles += it }
    }, tasks::add)
    private fun begin(owner: Any, session: Any) = coordinator.begin(app, owner, session, Iap2NowPlayingState(title = "old"), {})

    @Test fun aLateOldSessionEndCannotStopTheNewWriter() {
        val owner = Any()
        val old = Any()
        val next = Any()
        begin(owner, old)
        tasks.removeAt(0).run()
        begin(owner, next)
        assertNull(coordinator.end(owner, old, "late old callback"))
        tasks.removeAt(0).run()
        assertEquals(1, handles[1].starts)
        assertEquals(0, handles[1].stopCount)
    }

    @Test fun oldControllersCannotPublishToOrCloseAnotherControllersSession() {
        val old = Any()
        val next = Any()
        begin(old, Any())
        begin(next, Any())
        coordinator.onNowPlaying(old, Iap2NowPlayingState(title = "stale"))
        assertNull(coordinator.end(old, null, "old controller closed"))
        tasks.forEach(Runnable::run)
        assertEquals("old", handles[1].card!!.text)
        assertEquals(1, handles[1].starts)
        assertEquals(0, handles[0].starts)
    }

    @Test fun disabledAndEndedSessionsNeverStartQueuedHelpers() {
        val owner = Any()
        begin(owner, Any())
        enabled = false
        coordinator.settingChanged()
        tasks.removeAt(0).run()
        assertEquals(0, handles[0].starts)
        enabled = true
        coordinator.settingChanged()
        coordinator.end(owner, null, "CarPlay ended")
        tasks.removeAt(0).run()
        assertEquals(0, handles[1].starts)
    }

    @Test fun anUnconfirmedOldExitPreventsOverlappingProcesses() {
        val owner = Any()
        begin(owner, Any())
        tasks.removeAt(0).run()
        handles[0].stopResult = CompletableFuture.completedFuture(false)
        begin(owner, Any())
        tasks.removeAt(0).run()
        assertEquals(0, handles[1].starts)
        assertTrue(handles[1].stopCount > 0)
    }

    @Test fun metadataChangesDoNotQueueNewProcesses() {
        val owner = Any()
        begin(owner, Any())
        repeat(100) { coordinator.onNowPlaying(owner, Iap2NowPlayingState(title = "$it")) }
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertEquals(1, handles.single().starts)
        assertEquals("99", handles.single().card!!.text)
    }
}
