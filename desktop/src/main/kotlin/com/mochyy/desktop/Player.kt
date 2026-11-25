package com.mochyy.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
class Player(private val scope: CoroutineScope, private val library: LibraryStore) {
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

    val mpv: Mpv? = runCatching {
        Mpv { reason ->
            scope.launch {
                if (reason == "eof") onTrackEnd() else message = str("desktop_cant_play")
            }
        }
    }.getOrNull()?.also { it.setVolume(library.data.volume) }

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

    fun playNext(song: SongItem) {
        if (current == null) return playSong(song)
        queue = queue.toMutableList().apply { add(index + 1, song) }
    }

    fun addToQueue(song: SongItem) {
        if (current == null) return playSong(song)
        queue = queue + song
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
        if (player.duration.value <= 0.0 && current != null && !loading) startAt(index) else player.togglePause()
    }

    fun seek(seconds: Double) = mpv?.seek(seconds)

    /** [save] writes it to the library, only once a drag ends rather than on every step of it */
    fun setVolume(percent: Double, save: Boolean) {
        mpv?.setVolume(percent)
        if (save) library.setVolume(percent)
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

    fun close() = mpv?.close()

    private fun resetQueue(songs: List<SongItem>, start: Int, title: String?) {
        queueJob?.cancel()
        continuation = null
        queueEndpoint = null
        shuffled = false
        queue = songs
        queueTitle = title
        startAt(start)
    }

    private fun startAt(position: Int) {
        val song = queue.getOrNull(position) ?: return
        index = position
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
            val stream = withContext(Dispatchers.IO) { runCatching { findStream(song.id, library.data.highQuality) } }
            stream.onSuccess {
                if (it.viaYtDlp && !hasYtDlp) {
                    message = str("desktop_needs_ytdlp")
                } else {
                    // yt-dlp gets the session's cookies, so songs that need a signed-in account play too
                    val cookies = if (it.viaYtDlp) withContext(Dispatchers.IO) { ytDlpCookies() } else null
                    player.play(it.url, "${song.title} - ${song.artistNames()}", it.userAgent, cookies)
                }
            }.onFailure { message = str("desktop_cant_play") }
            loading = false
        }
        loadLyrics(song)
        if (position >= queue.size - 3) loadMore()
    }

    private fun onTrackEnd() {
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
