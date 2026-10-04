package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** BYD-group options for the CarPlay song in the dashboard's music card. */
object BydClusterSongSettings {
    private const val PREFS = "xcertplay_byd_cluster_song"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ON_CHANGE = "only_on_change"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        BydClusterSong.settingChanged()
    }

    /** Show a new song for a few seconds only, then empty the card. */
    fun onlyOnChange(context: Context): Boolean = prefs(context).getBoolean(KEY_ON_CHANGE, false)

    fun setOnlyOnChange(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON_CHANGE, enabled).apply()
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
    private const val SOURCE_OTHERS = 11
    private const val STATE_PLAYING = 1
    private const val STATE_PAUSED = 2
    private const val STATE_STOPPED = 3
    private const val ON_CHANGE_MILLIS = 5_000L

    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "xcertplay-cluster-song").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var app: Context? = null
    @Volatile private var log: (String) -> Unit = {}
    private var current: ClusterCard? = null // the song now playing, followed even while the option is off
    private var wanted: ClusterCard? = null // what the dashboard should show; null = no card
    private var announced: String? = null // the last song shown in the "only on change" mode
    private var changeToken: Any? = null
    private var shown: ClusterCard? = null // writer thread
    private var adb: LocalAdb? = null // writer thread

    fun onNowPlaying(context: Context, state: Iap2NowPlayingState, diagnostic: (String) -> Unit) {
        app = context.applicationContext
        log = diagnostic
        val card = ClusterCard.of(state)
        synchronized(lock) {
            if (card == current) return
            current = card
        }
        apply(newSong = true)
    }

    fun settingChanged() {
        synchronized(lock) {
            announced = null
            changeToken = null
        }
        apply(newSong = true)
    }

    /** The CarPlay session ended: stop the card and drop the adb link. */
    fun end() {
        synchronized(lock) {
            current = null
            announced = null
            changeToken = null
            wanted = null
        }
        writer.execute {
            clear()
            adb?.close()
            adb = null
        }
    }

    private fun apply(newSong: Boolean) {
        val context = app ?: return
        val enabled = BydClusterSongSettings.enabled(context)
        val onChange = BydClusterSongSettings.onlyOnChange(context)
        synchronized(lock) {
            val song = current?.takeIf { enabled }
            wanted = when {
                song == null -> null
                !onChange -> song
                announced != song.text && newSong -> {
                    announced = song.text
                    val token = Any().also { changeToken = it }
                    writer.schedule({ endChange(token) }, ON_CHANGE_MILLIS, TimeUnit.MILLISECONDS)
                    song
                }
                changeToken != null -> song
                else -> ClusterCard.EMPTY
            }
        }
        writer.execute { sync() }
    }

    private fun endChange(token: Any) {
        synchronized(lock) {
            if (changeToken !== token) return
            changeToken = null
            if (wanted != null) wanted = ClusterCard.EMPTY
        }
        sync()
    }

    // Writer thread: brings the dashboard to the newest wanted card.
    private fun sync() {
        val card = synchronized(lock) { wanted }
        if (card == null) {
            clear()
            return
        }
        if (card == shown) return
        val text = Base64.encodeToString(card.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val state = when {
            card == ClusterCard.EMPTY -> STATE_STOPPED
            card.playing -> STATE_PLAYING
            else -> STATE_PAUSED
        }
        if (run("$SOURCE_OTHERS", "$state", text)) shown = card
    }

    private fun clear() {
        if (shown == null) return
        if (run("-", "$STATE_STOPPED", "-")) shown = null
    }

    private fun run(vararg args: String): Boolean {
        val context = app ?: return false
        val link = adb ?: LocalAdb(AdbKeys.load(context)).also { adb = it }
        val output = runCatching {
            if (link.connect(mayAsk = false) != LocalAdb.Access.READY) null
            else link.shell(BydSdkStream.helper(context, "clustersong", *args))
        }.getOrNull()
        if (output == null) {
            link.close()
            adb = null
            log("Cluster song: adb shell unavailable")
            return false
        }
        val line = output.lineSequence().firstOrNull { it.startsWith("XCERTPLAY") }?.removePrefix("XCERTPLAY ")
            ?: output.trim().take(160)
        val results = line.split(' ').filter { it.contains('=') }
        val ok = results.isNotEmpty() && results.all { it.substringAfter('=').toIntOrNull() == 0 }
        log("Cluster song: $line")
        return ok
    }
}
