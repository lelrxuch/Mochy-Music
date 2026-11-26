package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
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
        // signed in like the app, when a browser here has a YouTube session: TVHTML5 and WEB_REMIX need it
        val work = Files.createTempDirectory("mochy-streams").toFile()
        val login = BrowserCookies.find().firstOrNull()
        val signedIn = login != null && AccountStore(File(work, "account.json")).signIn(login.cookie).isSuccess
        println("signed in: $signedIn")
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
        if (hasYtDlp) {
            // what mpv would get from yt-dlp: the audio link, with the session's cookies when signed in
            val cookies = ytDlpCookies()
            val command = listOf("yt-dlp", "-g", "-f", "bestaudio/best", "--no-warnings") +
                (cookies?.let { listOf("--cookies", it.path) } ?: emptyList()) + "https://music.youtube.com/watch?v=${song.id}"
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            println("stream source yt-dlp: ${if (process.exitValue() == 0 && "googlevideo" in out) "plays" else "no (" + out.lines().lastOrNull { it.isNotBlank() }?.take(160) + ")"}")
        }
        println("chosen: ${findStream(song.id).source}")
        assertTrue(results.any { it.second } || hasYtDlp, "no source could play ${song.id}")
        work.deleteRecursively()
    }
}
