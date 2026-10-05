package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterSongWriterSessionTest {
    private fun now() = System.nanoTime() / 1_000_000L
    private val app get() = RuntimeEnvironment.getApplication().also {
        it.applicationInfo.sourceDir = "/data/app/xcertplay/base.apk"
    }

    private inner class Shell : ClusterWriterShell {
        val connected = CountDownLatch(1)
        val allowConnect = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<ClusterWriterFrame>()
        val firstApplied = CountDownLatch(1)
        val helperStarted = CountDownLatch(1)
        val commands = CopyOnWriteArrayList<String>()
        val clears = AtomicInteger()
        @Volatile var closed = false
        var readable = true
        var failStream = false
        var confirmsGone = true
        @Volatile var launches = 0
        override fun connect(): Boolean {
            connected.countDown()
            check(allowConnect.await(6, TimeUnit.SECONDS))
            return !closed
        }
        override fun shell(command: String): String {
            commands += command
            return if (command.contains("/proc/424242")) { if (confirmsGone) "XCERTPLAY-process-gone" else "" }
            else if (readable) "XCERTPLAY-readable" else ""
        }
        override fun stream(command: String, onLine: (String) -> Unit) {
            launches++
            val match = Regex("clustersongwatch ([a-f0-9]{32}) ([A-Za-z0-9+/=]+)").find(command)!!
            val token = match.groupValues[1]
            val path = String(Base64.getDecoder().decode(match.groupValues[2]), Charsets.UTF_8)
            onLine("XCERTPLAY clusterwriter starting token=$token pid=424242")
            helperStarted.countDown()
            if (failStream) throw IOException("ADB stream lost")
            ClusterWriterLoop(token, { ClusterWriterMailbox(File(path)).read() }, ::now, {
                if (closed) throw IOException("closed")
                Thread.sleep(it)
            }, { applied += it; firstApplied.countDown(); "state=0 text=0" },
                { clears.incrementAndGet(); "state=0 text=0" }, onLine).run()
            onLine("XCERTPLAY clusterwriter exited token=$token pid=424242")
        }
        override fun close() { closed = true; allowConnect.countDown() }
    }

    @Test fun manyLyricUpdatesUseOneProcessAndOnlyTheNewestSlotIsConsumed() {
        val shell = Shell()
        val creations = AtomicInteger()
        val lines = CopyOnWriteArrayList<String>()
        val session = ClusterSongWriterSession(app, ClusterCard("initial", true), lines::add,
            { creations.incrementAndGet(); shell }, ::now)
        session.start()
        try {
            assertTrue(shell.connected.await(2, TimeUnit.SECONDS))
            repeat(100) { session.update(ClusterCard("line-$it", true)) }
            val mailbox = ClusterWriterMailbox(File(app.getExternalFilesDir("cluster"), "${session.token}.state"))
            val deadline = now() + 2000
            while (mailbox.read()?.text != "line-99" && now() < deadline) Thread.sleep(5)
            assertEquals("${mailbox.file.absolutePath}: ${lines.joinToString()}", "line-99", mailbox.read()?.text)
            shell.allowConnect.countDown()
            assertTrue(shell.firstApplied.await(2, TimeUnit.SECONDS))
            assertEquals("line-99", shell.applied.first().text)
            assertTrue(session.stop("CarPlay disconnected").get(3, TimeUnit.SECONDS))
            assertEquals(1, creations.get())
            assertEquals(1, shell.launches)
            assertEquals(1, shell.clears.get())
            assertFalse(mailbox.file.exists())
            session.update(ClusterCard("stale after stop", true))
            assertFalse(mailbox.file.exists())
        } finally {
            session.stop("test complete").get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun stoppingDuringAuthenticationNeverLaunchesAHelperAfterwards() {
        val shell = Shell()
        val session = ClusterSongWriterSession(app, null, {}, { shell }, ::now)
        session.start()
        assertTrue(shell.connected.await(2, TimeUnit.SECONDS))
        assertTrue(session.stop("exit while authenticating").get(3, TimeUnit.SECONDS))
        assertEquals(0, shell.launches)
        assertTrue(shell.closed)
    }

    @Test fun anUnreadableAppDirectoryPreventsHelperLaunch() {
        val shell = Shell().also { it.readable = false; it.allowConnect.countDown() }
        val rejected = CountDownLatch(1)
        val session = ClusterSongWriterSession(app, null, {
            if (it.contains("cannot read")) rejected.countDown()
        }, { shell }, ::now)
        session.start()
        assertTrue(rejected.await(2, TimeUnit.SECONDS))
        assertTrue(session.stop("unreadable mailbox").get(3, TimeUnit.SECONDS))
        assertEquals(0, shell.launches)
    }

    @Test fun lostAdbUsesASessionGuardedPidCleanupAndDoesNotRestartPerLyric() {
        val first = Shell().also { it.failStream = true; it.allowConnect.countDown() }
        val cleanup = Shell().also { it.allowConnect.countDown() }
        val creations = AtomicInteger()
        val session = ClusterSongWriterSession(app, null, {},
            { if (creations.incrementAndGet() == 1) first else cleanup }, ::now)
        session.start()
        assertTrue(first.connected.await(2, TimeUnit.SECONDS))
        assertTrue(first.helperStarted.await(2, TimeUnit.SECONDS))
        assertTrue(session.stop("ADB lost").get(3, TimeUnit.SECONDS))
        repeat(50) { session.update(ClusterCard("$it", true)) }
        assertEquals(1, first.launches)
        assertEquals(2, creations.get())
        val kill = cleanup.commands.single()
        assertTrue(kill.contains("*${BydVehicleDataTool::class.java.name}*clustersongwatch*${session.token}*"))
        assertTrue(kill.contains("kill -TERM 424242"))
        assertFalse(kill.contains("pkill"))
    }

    @Test fun anUnexpectedHelperExitGetsOneSessionGuardedRestart() {
        val first = Shell().also { it.failStream = true; it.allowConnect.countDown() }
        val second = Shell().also { it.allowConnect.countDown() }
        val cleanup = Shell().also { it.allowConnect.countDown() }
        val shells = ArrayDeque(listOf(first, cleanup, second))
        val session = ClusterSongWriterSession(app, ClusterCard("initial", true), {},
            { shells.removeFirst() }, ::now)
        val originalToken = session.token
        session.start()
        try {
            assertTrue(first.helperStarted.await(2, TimeUnit.SECONDS))
            assertTrue(second.helperStarted.await(3, TimeUnit.SECONDS))
            assertNotEquals(originalToken, session.token)
            assertFalse(File(app.getExternalFilesDir("cluster"), "$originalToken.state").exists())
            assertTrue(session.stop("restart test").get(3, TimeUnit.SECONDS))
            assertEquals(1, first.launches)
            assertEquals(1, second.launches)
        } finally {
            session.stop("test complete").get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun unconfirmedOldPidBlocksRestartAndCannotReportSuccessfulStop() {
        val first = Shell().also { it.failStream = true; it.allowConnect.countDown() }
        val cleanup = Shell().also { it.confirmsGone = false; it.allowConnect.countDown() }
        val shells = ArrayDeque(listOf(first, cleanup))
        val blocked = CountDownLatch(1)
        val session = ClusterSongWriterSession(app, null, {
            if (it.contains("restart blocked")) blocked.countDown()
        }, { shells.removeFirst() }, ::now)
        session.start()
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        assertFalse(session.stop("test").get(3, TimeUnit.SECONDS))
        assertEquals(0, cleanup.launches)
        assertTrue(shells.isEmpty())
    }

    @Test fun stoppingBeforeStartupIsTerminalAndCreatesNoAdbClient() {
        val creations = AtomicInteger()
        val session = ClusterSongWriterSession(app, null, {}, { creations.incrementAndGet(); Shell() }, ::now)
        assertTrue(session.stop("closed before start").get())
        session.start()
        assertEquals(0, creations.get())
    }
}
