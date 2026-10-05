package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** BYD-group options for the CarPlay song in the dashboard's music card. */
object BydClusterSongSettings {
    private const val PREFS = "xcertplay_byd_cluster_song"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        BydClusterSong.settingChanged()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** One state of the dashboard music card. */
internal data class ClusterCard(val text: String, val playing: Boolean) {
    companion object {
        /** An empty, stopped card: how the song leaves the dashboard. */
        val EMPTY = ClusterCard(" ", false)

        /** The dashboard takes at most 255 bytes of UTF-16LE. */
        private const val MAX_TEXT_BYTES = 255

        /**
         * "Title — Artist" from now playing, shortened without splitting a character; null without
         * a title. Music apps with car/Bluetooth lyrics put the current lyric line in the title.
         */
        fun of(state: Iap2NowPlayingState): ClusterCard? {
            val title = state.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = state.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$title — $it" } ?: title
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            val playing = state.status == Iap2PlaybackStatus.PLAYING ||
                state.status == Iap2PlaybackStatus.SEEKING_FORWARD ||
                state.status == Iap2PlaybackStatus.SEEKING_BACKWARD
            return ClusterCard(full.substring(0, end), playing)
        }
    }
}

/**
 * Shows the CarPlay song (and lyrics, which music apps put in the title) in the BYD dashboard's
 * music card, as DiPlay PR #101 does. Apps cannot write the cluster, but BYD's autoservice accepts
 * the adb shell user, so [BydVehicleDataTool] writes it from this APK under the local adb shell.
 * Only changes are written, the newest wins, and the card stops when CarPlay ends or the option
 * goes off. Without adb approval nothing happens.
 */
internal object BydClusterSong {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(32),
        { Thread(it, "xcertplay-cluster-control").apply { isDaemon = true } })
    private val coordinator = ClusterSongCoordinator(BydClusterSongSettings::enabled,
        { context, card, log -> ClusterSongWriterSession(context, card, log) }, { worker.execute(it) })

    fun begin(context: Context, owner: Any, session: Any, initial: Iap2NowPlayingState, log: (String) -> Unit) =
        coordinator.begin(context.applicationContext, owner, session, initial, log)
    fun onNowPlaying(owner: Any, state: Iap2NowPlayingState) = coordinator.onNowPlaying(owner, state)
    fun settingChanged() = coordinator.settingChanged()
    fun end(owner: Any, session: Any? = null, reason: String): CompletableFuture<Boolean>? =
        coordinator.end(owner, session, reason)
}

/** Ownership guards prevent a late callback from an old controller from stopping a new session. */
internal class ClusterSongCoordinator(
    private val enabled: (Context) -> Boolean,
    private val create: (Context, ClusterCard?, (String) -> Unit) -> ClusterSongSessionHandle,
    private val execute: (Runnable) -> Unit,
) {
    private class Active(
        val context: Context, val owner: Any, val session: Any, var card: ClusterCard?, val log: (String) -> Unit,
        var writer: ClusterSongSessionHandle? = null,
    )
    private val lock = Any()
    private var active: Active? = null
    private var previousClosed = CompletableFuture.completedFuture(true)

    fun begin(context: Context, owner: Any, session: Any, initial: Iap2NowPlayingState, log: (String) -> Unit) {
        synchronized(lock) {
            if (active?.owner === owner && active?.session === session) return
            active?.writer?.let { previousClosed = it.stop("session replaced") }
            active = Active(context, owner, session, ClusterCard.of(initial), log)
            reconcile(checkNotNull(active))
        }
    }

    fun onNowPlaying(owner: Any, state: Iap2NowPlayingState) = synchronized(lock) {
        val session = active?.takeIf { it.owner === owner } ?: return@synchronized
        session.card = ClusterCard.of(state)
        session.writer?.update(session.card)
    }

    fun settingChanged() = synchronized(lock) { active?.let(::reconcile) }

    fun end(owner: Any, session: Any?, reason: String): CompletableFuture<Boolean>? = synchronized(lock) {
        val current = active?.takeIf { it.owner === owner && (session == null || it.session === session) }
            ?: return@synchronized null
        previousClosed = current.writer?.stop(reason) ?: previousClosed
        active = null
        previousClosed
    }

    private fun reconcile(session: Active) {
        if (!enabled(session.context)) {
            session.writer?.let { previousClosed = it.stop("setting disabled") }
            session.writer = null
            return
        }
        if (session.writer != null) return
        val writer = create(session.context, session.card, session.log)
        session.writer = writer
        val predecessor = previousClosed
        try {
            execute(Runnable {
                try {
                    if (!predecessor.get(5_000, TimeUnit.MILLISECONDS)) throw IOException("previous writer exit unconfirmed")
                    synchronized(lock) {
                        if (active !== session || session.writer !== writer) { writer.stop("startup cancelled"); return@Runnable }
                        writer.start()
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    writer.stop("startup cancelled")
                    session.log("Cluster song: startup cancelled: ${error.javaClass.simpleName} ${error.message}")
                }
            })
        } catch (error: RuntimeException) {
            writer.stop("startup queue unavailable")
            session.log("Cluster song: startup queue unavailable: ${error.message}")
        }
    }
}
