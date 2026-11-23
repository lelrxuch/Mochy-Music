package com.mochyy.desktop

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

// Needs internet and mpv on PATH (skipped without it). Plays silently: --ao=null sends the sound nowhere.
class MpvCheck {
    @Test
    fun mpvPlaysPausesSeeksAndFinishesASong() = runBlocking {
        // GitHub's servers are a data centre, where YouTube refuses streams; this runs on a real PC
        if (System.getenv("CI") != null) return@runBlocking println("skipped on CI")
        if (runCatching { ProcessBuilder("mpv", "--version").start().waitFor() }.isFailure) return@runBlocking
        val ends = mutableListOf<String>()
        Mpv(listOf("--ao=null")) { synchronized(ends) { ends += it } }.use { mpv ->
            val song = YouTube.search("daft punk one more time", YouTube.SearchFilter.FILTER_SONG)
                .getOrThrow().items.filterIsInstance<SongItem>().first()
            mpv.play(audioUrl(song.id), song.title, YouTubeClient.VISIONOS.userAgent)
            withTimeout(30_000) { while (mpv.position.value < 2.0) delay(100) }
            assertTrue(mpv.duration.value > 30, "duration ${mpv.duration.value}")
            assertTrue(mpv.codec.value != null, "no codec reported")

            mpv.togglePause()
            withTimeout(5_000) { while (!mpv.paused.value) delay(50) }

            // the last seconds have to come through too, and the end has to be noticed
            mpv.seek(mpv.duration.value - 3)
            mpv.togglePause()
            withTimeout(30_000) { while (synchronized(ends) { "eof" !in ends }) delay(100) }
        }
    }
}
