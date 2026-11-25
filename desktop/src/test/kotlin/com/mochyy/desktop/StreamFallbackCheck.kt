package com.mochyy.desktop

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which of the stream sources answer for a real song, one by one, so it's clear what the fallbacks
 * can do on this network. Prints a line per source; fails only if none at all works.
 * Needs internet; skipped on CI, where YouTube refuses data-centre addresses.
 */
class StreamFallbackCheck {
    @Test
    fun eachSourceOnARealSong() = runBlocking {
        if (System.getenv("CI") != null) return@runBlocking println("skipped on CI")
        ensureVisitorData()
        val song = YouTube.search("daft punk one more time", YouTube.SearchFilter.FILTER_SONG)
            .getOrThrow().items.filterIsInstance<SongItem>().first()
        val results = buildList {
            add("VISIONOS" to (visionos(song.id, true) != null))
            FALLBACK_CLIENTS.forEach { client -> add(client.clientName to (signed(song.id, client, true) != null)) }
            add("NewPipe" to (newPipe(song.id, true) != null))
        }
        results.forEach { (source, ok) -> println("stream source $source: ${if (ok) "plays" else "no"}") }
        println("yt-dlp installed: $hasYtDlp")
        println("chosen: ${findStream(song.id).source}")
        assertTrue(results.any { it.second } || hasYtDlp, "no source could play ${song.id}")
    }
}
