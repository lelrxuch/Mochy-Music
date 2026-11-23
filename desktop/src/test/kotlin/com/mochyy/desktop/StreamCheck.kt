package com.mochyy.desktop

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue

// Needs internet. Fails when YouTube changes something that breaks search or stream links.
class StreamCheck {
    @Test
    fun searchFindsASongAndItsAudioStreamAnswers() = runBlocking {
        val song = YouTube.search("daft punk one more time", YouTube.SearchFilter.FILTER_SONG)
            .getOrThrow().items.filterIsInstance<SongItem>().first()
        val url = audioUrl(song.id)
        assertTrue("googlevideo.com" in url, "VISIONOS gave no stream for ${song.id}, got $url")

        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-1023")
        connection.setRequestProperty("User-Agent", YouTubeClient.VISIONOS.userAgent)
        assertTrue(connection.responseCode in 200..206, "stream answered HTTP ${connection.responseCode}")
    }

    @Test
    fun lrcLinesGetTheirTimes() {
        val lines = parseLrc("[00:01.50]one\n[01:02.05][01:10]two\nno stamp")
        assertTrue(lines.map { it.timeMs } == listOf(1_500L, 62_050L, 70_000L), "got ${lines.map { it.timeMs }}")
        assertTrue(lines.indexAt(61_000) == 0 && lines.indexAt(62_100) == 1 && lines.indexAt(500) == -1)
    }
}
