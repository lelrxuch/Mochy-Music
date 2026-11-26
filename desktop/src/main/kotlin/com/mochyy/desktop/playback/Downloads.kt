package com.mochyy.desktop.playback

import com.mochyy.desktop.text.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import androidx.compose.runtime.mutableStateMapOf
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** a downloaded song, and its loudness so it's still evened out offline */
@Serializable
data class DownloadedSong(val song: SongItem, val loudnessDb: Double? = null)

/**
 * Songs kept on this computer to play without a connection (playback/DownloadUtil.kt and friends):
 * one at a time, through the same stream lookup as playing, into ~/.local/share/mochy-desktop/downloads.
 */
class Downloads(private val scope: CoroutineScope, private val library: LibraryStore, private val dir: File = dataFile("downloads")) {
    /** how far each song being fetched has got, 0 to 1; queued ones are at 0 */
    val progress = mutableStateMapOf<String, Float>()

    /** short notices for the player's message line */
    var onNotice: (String) -> Unit = {}

    private val waiting = Channel<SongItem>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (song in waiting) {
                val result = withContext(Dispatchers.IO) { runCatching { fetch(song) } }
                progress.remove(song.id)
                result.onSuccess { loudness ->
                    library.addDownload(DownloadedSong(song, loudness))
                    onNotice(str("desktop_downloaded", song.title))
                }.onFailure { onNotice(str("desktop_download_failed", song.title)) }
            }
        }
    }

    fun file(id: String) = File(dir, "$id.audio")

    fun isDownloaded(id: String) = library.data.downloads.any { it.song.id == id } && file(id).isFile

    fun download(songs: List<SongItem>) {
        val new = songs.distinctBy { it.id }.filter { !isDownloaded(it.id) && it.id !in progress }
        if (new.isEmpty()) return
        new.forEach { progress[it.id] = 0f }
        new.forEach { waiting.trySend(it) }
        onNotice(str("desktop_downloading", new.size))
    }

    fun remove(song: SongItem) {
        file(song.id).delete()
        library.removeDownload(song.id)
    }

    // the stream as playing would find it, written to a temp file and moved in place when whole
    private suspend fun fetch(song: SongItem): Double? {
        dir.mkdirs()
        val stream = findStream(song.id, library.data.highQuality)
        val temp = File(dir, "${song.id}.part")
        temp.delete()
        if (stream.viaYtDlp) {
            check(hasYtDlp) { "yt-dlp is missing" }
            val cookies = ytDlpCookies()
            val command = listOf("yt-dlp", "-f", "bestaudio/best", "--no-part", "--no-warnings", "-o", temp.path) +
                (cookies?.let { listOf("--cookies", it.path) } ?: emptyList()) + stream.url
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor() == 0 && temp.length() > 0) { "yt-dlp failed" }
        } else {
            fetchInChunks(stream.url, stream.userAgent, temp) { progress[song.id] = it }
        }
        check(temp.renameTo(file(song.id))) { "couldn't move the download in place" }
        return stream.loudnessDb
    }

    // googlevideo cuts a single long read short, so the file comes in 10 MB ranges
    private fun fetchInChunks(url: String, userAgent: String?, to: File, onProgress: (Float) -> Unit) {
        var done = 0L
        var total = -1L
        to.outputStream().buffered().use { out ->
            while (total < 0 || done < total) {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Range", "bytes=$done-${done + CHUNK - 1}")
                userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                val code = connection.responseCode
                check(code in 200..206) { "HTTP $code" }
                if (total < 0) {
                    total = connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: connection.contentLengthLong
                }
                val read = connection.inputStream.use { it.copyTo(out) }
                connection.disconnect()
                done += read
                // a server that ignores the range sent the whole file at once
                if (code == 200 || read == 0L) break
                if (total > 0) onProgress(done.toFloat() / total)
            }
        }
        check(total <= 0 || done >= total) { "download stopped at $done of $total bytes" }
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
    }
}
