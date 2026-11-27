package com.mochyy.desktop.playback

import com.mochyy.desktop.text.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** "OPUS · 48,0 kHz", the badge the player shows beside the title */
fun formatBadge(codec: String?, sampleRate: Int?): String? {
    if (codec == null) return null
    val rate = sampleRate?.let { String.format(Locale.getDefault(), " · %.1f kHz", it / 1000.0) }.orEmpty()
    return codec.uppercase() + rate
}

fun SongItem.artistNames() = artists.joinToString { it.name }

enum class Repeat { OFF, ALL, ONE }

/** The queue and what's playing, what the Android app's MusicService does, with mpv as the engine. */
/** the queue as it was when the app closed (playback/queues): picked up again, paused, at the same second */
@Serializable
private data class SavedQueue(val songs: List<SongItem>, val index: Int, val position: Double, val title: String? = null)

private val queueJson = Json { ignoreUnknownKeys = true }

class Player(
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val queueFile: File = dataFile("queue.json"),
) {
    /** songs kept on this computer; they play from disk, no connection needed */
    val downloads = Downloads(scope, library).also { it.onNotice = { text -> message = text } }

    var queue by mutableStateOf(emptyList<SongItem>())
        private set
    var index by mutableStateOf(-1)
        private set
    var queueTitle by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Repeat.OFF)
        private set
    var shuffled by mutableStateOf(false)
        private set
    var lyrics by mutableStateOf<Lyrics>(Lyrics.Missing)
        private set

    /** a short notice for the user, shown until the next one */
    var message by mutableStateOf<String?>(null)

    val current: SongItem? get() = queue.getOrNull(index)

    private var continuation: String? = null
    private var queueEndpoint: WatchEndpoint? = null
    private var unshuffled = emptyList<SongItem>()
    private var streamJob: Job? = null
    private var queueJob: Job? = null
    private var lyricsJob: Job? = null
    private var loadingMore = false

    // where to start the song restored from last time, once mpv has loaded it
    private var pendingSeek = 0.0

    /** when the sleep timer pauses, as epoch millis, or null */
    var sleepAt by mutableStateOf<Long?>(null)
        private set

    /** the sleep timer stops at the end of this song instead */
    var sleepAtSongEnd by mutableStateOf(false)
        private set
    private var sleepJob: Job? = null

    val mpv: Mpv? = runCatching {
        Mpv { reason ->
            scope.launch {
                if (reason == "eof") onTrackEnd() else message = str("desktop_cant_play")
            }
        }
    }.getOrNull()?.also { it.setVolume(library.data.volume) }

    /** tells YouTube what's listened to, for the account's history and recommendations */
    private val reporter = PlayReporter(scope) { ((mpv?.position?.value ?: 0.0) * 1000).toLong() }

    /** the Audio Lab settings into mpv; they stay on for every song after */
    fun applyAudioLab() {
        val player = mpv ?: return
        val lab = library.data.audioLab
        player.setFilters(audioFilters(lab))
        player.setSpeed(lab.speed)
    }

    init {
        applyAudioLab()
        restoreQueue()
        mpv?.let { player -> scope.launch { player.paused.collect { reporter.onPlayingChanged(!it) } } }
        // mpv died and came back empty: the song carries on from where it was if it was playing,
        // and if it was paused, play picks it up from there
        mpv?.let { player ->
            scope.launch {
                player.revived.collect { (position, wasPlaying) ->
                    pendingSeek = position
                    if (wasPlaying && current != null && !loading) startAt(index, keepSeek = true)
                }
            }
        }
        // the restored song starts where it was left, once mpv knows how long it is
        mpv?.let { player ->
            scope.launch {
                player.duration.collect { length ->
                    if (length > 0 && pendingSeek > 0) {
                        player.seek(pendingSeek.coerceAtMost(length - 1))
                        pendingSeek = 0.0
                    }
                }
            }
        }
    }

    /** one song: it plays at once and its radio fills the queue behind it, like the app */
    fun playSong(song: SongItem) {
        resetQueue(listOf(song), 0, null)
        val radio = WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")
        queueJob = scope.launch {
            val result = withContext(Dispatchers.IO) { YouTube.next(radio) }.getOrNull() ?: return@launch
            if (queue.singleOrNull()?.id != song.id) return@launch // something else started meanwhile
            queue = listOf(queue.single()) + result.items.filterNot { it.id == song.id }
            queueTitle = result.title
            continuation = result.continuation
            queueEndpoint = result.endpoint
        }
    }

    /** an album or playlist, from [start] or shuffled */
    fun playList(songs: List<SongItem>, start: Int = 0, title: String? = null, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        if (shuffle) {
            resetQueue(songs.shuffled(), 0, title)
            unshuffled = songs
            shuffled = true
        } else {
            resetQueue(songs, start, title)
        }
    }

    /** an endpoint YouTube builds the queue for: an artist's radio or shuffle, a playlist's mix */
    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null) {
        queueJob?.cancel()
        loading = true
        queueJob = scope.launch {
            val result = withContext(Dispatchers.IO) { YouTube.next(endpoint) }.getOrNull()
            if (result == null || result.items.isEmpty()) {
                loading = false
                message = str("desktop_cant_play")
                return@launch
            }
            resetQueue(result.items, result.currentIndex ?: 0, title ?: result.title)
            continuation = result.continuation
            queueEndpoint = result.endpoint
        }
    }

    fun playNext(song: SongItem) = playNext(listOf(song))

    fun addToQueue(song: SongItem) = addToQueue(listOf(song))

    /** puts [songs] right after the one playing, in their order; with nothing playing they start */
    fun playNext(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (current == null) return if (songs.size == 1) playSong(songs[0]) else playList(songs, 0, null)
        queue = queue.toMutableList().apply { addAll(index + 1, songs) }
    }

    fun addToQueue(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (current == null) return if (songs.size == 1) playSong(songs[0]) else playList(songs, 0, null)
        queue = queue + songs
    }

    fun removeAt(position: Int) {
        if (position == index || position !in queue.indices) return
        queue = queue.toMutableList().apply { removeAt(position) }
        if (position < index) index--
    }

    fun jump(position: Int) = startAt(position)

    fun next() {
        when {
            index + 1 < queue.size -> startAt(index + 1)
            repeat == Repeat.ALL && queue.isNotEmpty() -> startAt(0)
        }
    }

    fun previous() {
        val player = mpv ?: return
        if (player.position.value > 3 || index <= 0) player.seek(0.0) else startAt(index - 1)
    }

    fun togglePause() {
        val player = mpv ?: return
        // after a failed load there's nothing in mpv to unpause, so the song is asked for again
        if (player.duration.value <= 0.0 && current != null && !loading) startAt(index, keepSeek = true) else player.togglePause()
        // a play that reached no mpv says so instead of doing nothing
        if (!player.isRunning) message = str("desktop_cant_play")
    }

    fun seek(seconds: Double) {
        val player = mpv ?: return
        reporter.onJump((player.position.value * 1000).toLong(), (seconds * 1000).toLong())
        player.seek(seconds)
    }

    /** [save] writes it to the library, only once a drag ends rather than on every step of it */
    fun setVolume(percent: Double, save: Boolean) {
        val volume = percent.coerceIn(0.0, 100.0)
        mpv?.setVolume(volume)
        if (save) library.setVolume(volume)
    }

    fun cycleRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
    }

    fun toggleShuffle() {
        val playing = current ?: return
        if (shuffled) {
            // songs added while shuffled go after the original order
            val restored = unshuffled + queue.filterNot { song -> unshuffled.any { it.id == song.id } }
            queue = restored
            index = restored.indexOfFirst { it.id == playing.id }.coerceAtLeast(0)
        } else {
            unshuffled = queue
            queue = listOf(playing) + queue.filterIndexed { i, _ -> i != index }.shuffled()
            index = 0
        }
        shuffled = !shuffled
    }

    // ---- sleep timer (playback/SleepTimer.kt) ----

    fun sleepIn(minutes: Int) {
        cancelSleep()
        sleepAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            sleepAt = null
        }
    }

    fun sleepAfterThisSong() {
        cancelSleep()
        sleepAtSongEnd = true
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepAt = null
        sleepAtSongEnd = false
    }

    private fun pause() {
        val player = mpv ?: return
        if (!player.paused.value) player.togglePause()
    }

    fun close() {
        reporter.onSongStop()
        saveQueue(mpv?.position?.value ?: 0.0, now = true)
        mpv?.close()
    }

    // ---- the queue across restarts ----

    private fun restoreQueue() {
        val saved = runCatching { queueJson.decodeFromString<SavedQueue>(queueFile.readText()) }.getOrNull() ?: return
        if (saved.songs.isEmpty()) return
        queue = saved.songs
        index = saved.index.coerceIn(saved.songs.indices)
        queueTitle = saved.title
        pendingSeek = saved.position
        // nothing is loaded yet, so it shows as paused until play is pressed
        mpv?.togglePause()
    }

    private fun saveQueue(position: Double, now: Boolean = false) {
        // the songs around the current one, enough to come back to
        val from = (index - 50).coerceAtLeast(0)
        val saved = SavedQueue(queue.drop(from).take(300), index - from, position, queueTitle)
        val write = {
            runCatching {
                queueFile.parentFile.mkdirs()
                queueFile.writeText(queueJson.encodeToString(SavedQueue.serializer(), saved))
            }
        }
        if (now) write() else scope.launch(Dispatchers.IO) { write() }
    }

    private fun resetQueue(songs: List<SongItem>, start: Int, title: String?) {
        queueJob?.cancel()
        continuation = null
        queueEndpoint = null
        shuffled = false
        queue = songs
        queueTitle = title
        startAt(start)
    }

    private fun startAt(position: Int, keepSeek: Boolean = false) {
        val song = queue.getOrNull(position) ?: return
        index = position
        if (!keepSeek) pendingSeek = 0.0
        saveQueue(pendingSeek)
        // the song being left ends its listen here, where it was
        reporter.onSongStop()
        val player = mpv ?: run {
            message = str("desktop_mpv_missing")
            return
        }
        library.addHistory(song)
        message = null
        loading = true
        player.stop()
        streamJob?.cancel()
        streamJob = scope.launch {
            // a downloaded song plays from disk, with the loudness kept from when it was fetched
            val kept = library.data.downloads.firstOrNull { it.song.id == song.id }?.takeIf { downloads.file(song.id).isFile }
            val stream = if (kept != null) {
                Result.success(Stream(downloads.file(song.id).path, null, "download", kept.loudnessDb))
            } else {
                withContext(Dispatchers.IO) { runCatching { findStream(song.id, library.data.highQuality) } }
            }
            stream.onSuccess {
                if (it.viaYtDlp && !hasYtDlp) {
                    message = str("desktop_needs_ytdlp")
                } else {
                    // yt-dlp gets the session's cookies, so songs that need a signed-in account play too
                    val cookies = if (it.viaYtDlp) withContext(Dispatchers.IO) { ytDlpCookies() } else null
                    // Android's rule: the gain is minus the measured loudness, between -15 and +3 dB
                    val gain = if (library.data.normalize) (-(it.loudnessDb ?: 0.0)).coerceIn(-15.0, 3.0) else 0.0
                    player.play(it.url, "${song.title} - ${song.artistNames()}", it.userAgent, cookies, gain)
                    if (!player.isRunning) message = str("desktop_cant_play")
                    reporter.enabled = library.data.reportPlays
                    reporter.onSongStart(song.id, (song.duration ?: 0) * 1000L)
                }
            }.onFailure { message = str("desktop_cant_play") }
            loading = false
        }
        loadLyrics(song)
        if (position >= queue.size - 3) loadMore()
    }

    private fun onTrackEnd() {
        reporter.onSongStop()
        // the sleep timer was set to the end of this song
        if (sleepAtSongEnd) {
            sleepAtSongEnd = false
            return
        }
        when {
            repeat == Repeat.ONE -> startAt(index)
            index + 1 < queue.size -> startAt(index + 1)
            repeat == Repeat.ALL -> startAt(0)
            continuation != null -> scope.launch {
                loadMore()?.join()
                if (index + 1 < queue.size) startAt(index + 1)
            }
        }
    }

    /** the radio's next page, when YouTube has one */
    private fun loadMore(): Job? {
        val endpoint = queueEndpoint ?: return null
        val token = continuation ?: return null
        if (loadingMore) return null
        loadingMore = true
        return scope.launch {
            withContext(Dispatchers.IO) { YouTube.next(endpoint, token) }.getOrNull()?.let { page ->
                queue = queue + page.items.filterNot { song -> queue.any { it.id == song.id } }
                continuation = page.continuation
            }
            loadingMore = false
        }
    }

    private fun loadLyrics(song: SongItem) {
        lyricsJob?.cancel()
        lyrics = Lyrics.Loading
        lyricsJob = scope.launch {
            lyrics = withContext(Dispatchers.IO) { runCatching { fetchLyrics(song) }.getOrDefault(Lyrics.Missing) }
        }
    }
}
