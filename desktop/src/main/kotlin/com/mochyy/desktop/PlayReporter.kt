package com.mochyy.desktop

import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse.PlaybackTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// playback/YouTubePlayReporter.kt from the app, with the desktop's clock and stream lookup.

private fun now() = System.nanoTime() / 1_000_000

/**
 * Tells YouTube about each song really listened to, like its own app does: one play once it has
 * played 30 seconds, then how long it was heard every 40. That is what puts it in the account's
 * history and feeds its recommendations. Only signed in, and only while [enabled].
 */
class PlayReporter(private val scope: CoroutineScope, private val positionMs: () -> Long) {
    var enabled = true
        set(value) {
            field = value
            if (!value) drop()
        }

    // its own scope, so a song's last report still goes out while the app closes
    private val network = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listen: Listen? = null

    fun onSongStart(videoId: String, durationMs: Long) {
        onSongStop()
        if (!enabled || YouTube.cookie.isNullOrBlank()) return
        listen = Listen(videoId, durationMs, trackingFor(videoId))
        resume()
    }

    fun onPlayingChanged(playing: Boolean) {
        if (playing) resume() else pause()
    }

    /** a seek: the old spot ends what was heard, the new one starts the next stretch */
    fun onJump(fromMs: Long, toMs: Long) {
        val listen = listen ?: return
        if (listen.runningSince == 0L) return
        listen.timer?.cancel()
        endStretch(listen, fromMs)
        startStretch(listen, toMs)
    }

    fun onSongStop() {
        val listen = listen ?: return
        this.listen = null
        listen.timer?.cancel()
        endStretch(listen, positionMs())
        // skipped before it counted: nothing is sent at all
        if (listen.counted) report(listen, final = true) else listen.tracking.cancel()
    }

    private fun drop() {
        val listen = listen ?: return
        this.listen = null
        listen.timer?.cancel()
        listen.tracking.cancel()
    }

    private fun resume() {
        val listen = listen ?: return
        if (listen.runningSince == 0L) startStretch(listen, positionMs())
    }

    private fun pause() {
        val listen = listen ?: return
        listen.timer?.cancel()
        endStretch(listen, positionMs())
    }

    private fun startStretch(listen: Listen, atMs: Long) {
        listen.runningSince = now()
        listen.stretchStartMs = atMs
        val dueAtHeardMs = if (listen.counted) listen.reportedAtHeardMs + WATCHTIME_EVERY_MS else COUNT_AFTER_MS
        listen.timer = scope.launch {
            delay((dueAtHeardMs - listen.heardMs).coerceAtLeast(0))
            val nowMs = positionMs()
            endStretch(listen, nowMs)
            report(listen, final = false)
            startStretch(listen, nowMs)
        }
    }

    private fun endStretch(listen: Listen, atMs: Long) {
        if (listen.runningSince == 0L) return
        listen.heardMs += now() - listen.runningSince
        listen.runningSince = 0
        if (atMs > listen.stretchStartMs) listen.unsent += Stretch(listen.stretchStartMs, atMs)
    }

    // the first report sends the play; each one sends the stretches heard since the last
    private fun report(listen: Listen, final: Boolean) {
        val sendsPlay = !listen.counted
        listen.counted = true
        listen.reportedAtHeardMs = listen.heardMs
        val watchtime = listen.unsent.takeIf { it.isNotEmpty() }?.let { watchtimeParams(listen, it, final) }
        listen.unsent.clear()
        val previous = listen.sending
        listen.sending = network.launch {
            previous?.join()
            val tracking = listen.tracking.await() ?: return@launch
            val playUrl = tracking.videostatsPlaybackUrl?.baseUrl
            if (sendsPlay && playUrl != null) YouTube.registerPlayback(playbackTracking = playUrl, cpn = listen.cpn)
            val watchtimeUrl = tracking.videostatsWatchtimeUrl?.baseUrl
            if (watchtime != null && watchtimeUrl != null) YouTube.reportWatchtime(watchtimeUrl, listen.cpn, watchtime)
        }
    }

    // the play links: one ask of YouTube Music's web client as the song starts, with the account's cookie
    private fun trackingFor(videoId: String): Deferred<PlaybackTracking?> = network.async {
        repeat(TRACKING_TRIES) { attempt ->
            if (attempt > 0) delay(TRACKING_RETRY_MS * attempt)
            signedIn { YouTube.player(videoId, null, YouTubeClient.WEB_REMIX) }.getOrNull()?.playbackTracking?.let { return@async it }
        }
        null
    }

    private fun watchtimeParams(listen: Listen, stretches: List<Stretch>, final: Boolean) = buildMap {
        put("st", stretches.joinToString(",") { seconds(it.fromMs) })
        put("et", stretches.joinToString(",") { seconds(it.toMs) })
        put("cmt", seconds(stretches.last().toMs))
        put("rt", seconds(now() - listen.startedAt))
        put("state", if (final) "paused" else "playing")
        if (listen.lengthMs > 0) put("len", seconds(listen.lengthMs))
        if (final) put("final", "1")
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    private companion object {
        const val COUNT_AFTER_MS = 30_000L
        const val WATCHTIME_EVERY_MS = 40_000L
        const val TRACKING_TRIES = 3
        const val TRACKING_RETRY_MS = 5_000L
    }
}

private class Listen(val videoId: String, val lengthMs: Long, val tracking: Deferred<PlaybackTracking?>) {
    val cpn = YouTube.newPlaybackNonce()
    val startedAt = now()
    var heardMs = 0L
    var runningSince = 0L
    var stretchStartMs = 0L
    val unsent = mutableListOf<Stretch>()
    var counted = false
    var reportedAtHeardMs = 0L
    var timer: Job? = null
    var sending: Job? = null
}

private class Stretch(val fromMs: Long, val toMs: Long)
