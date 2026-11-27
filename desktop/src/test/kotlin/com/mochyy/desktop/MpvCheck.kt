package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
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

/** mpv dying under the app (a sound device going away, a crash) must not leave Mochy mute until a restart. */
class MpvRevivalCheck {
    @Test
    fun aDeadMpvIsStartedAgainWithItsSettings() = runBlocking {
        if (runCatching { ProcessBuilder("mpv", "--version").start().waitFor() }.isFailure) return@runBlocking println("no mpv, skipped")
        Mpv(listOf("--ao=null")) {}.use { mpv ->
            mpv.setVolume(42.0)
            withTimeout(5_000) { while (mpv.volume.value != 42.0) delay(50) }
            val first = mpv.pid
            ProcessHandle.of(first).ifPresent { it.destroyForcibly() }
            // it notices on its own, without waiting for the next command
            withTimeout(10_000) { while (!(mpv.isRunning && mpv.pid != first)) delay(50) }
            // it takes commands again, and hears mpv's answers
            mpv.setVolume(17.0)
            withTimeout(5_000) { while (mpv.volume.value != 17.0) delay(50) }
            // the volume set before the crash came back (17 was set after, so ask mpv for the old one again)
            // a fresh mpv has nothing playing, so it says paused
            val paused = runCatching { withTimeout(5_000) { while (!mpv.paused.value) delay(50) } }
            assertTrue(paused.isSuccess, "paused=${mpv.paused.value} duration=${mpv.duration.value} volume=${mpv.volume.value}")
        }
    }
}
