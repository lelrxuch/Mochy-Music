package com.mochyy.desktop

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.lrclib.LrcLib

data class LyricLine(val timeMs: Long, val text: String)

sealed interface Lyrics {
    data object Loading : Lyrics
    data object Missing : Lyrics
    data class Synced(val lines: List<LyricLine>) : Lyrics
    data class Plain(val text: String) : Lyrics
}

/** LrcLib's timed lyrics first, like the app's default order, then YouTube Music's plain ones. */
suspend fun fetchLyrics(song: SongItem): Lyrics {
    val artist = song.artists.joinToString { it.name }
    LrcLib.getLyrics(song.title, artist, song.duration ?: -1, song.album?.name).getOrNull()
        ?.let { return toLyrics(it) }
    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.lyricsEndpoint ?: return Lyrics.Missing
    return YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() }?.let(Lyrics::Plain) ?: Lyrics.Missing
}

fun toLyrics(text: String): Lyrics {
    val lines = parseLrc(text)
    return if (lines.isNotEmpty()) Lyrics.Synced(lines) else Lyrics.Plain(text.trim())
}

private val TIMESTAMP = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** "[01:02.50]words" lines into timed lines; a line with several stamps repeats at each one. */
fun parseLrc(text: String): List<LyricLine> = text.lines().flatMap { line ->
    val stamps = TIMESTAMP.findAll(line).toList()
    val words = line.replace(TIMESTAMP, "").trim()
    stamps.map { stamp ->
        val (min, sec, frac) = stamp.destructured
        // ".5" is half a second and ".05" five hundredths, so the fraction is read by its length
        val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toInt()
        LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + ms, words)
    }
}.sortedBy { it.timeMs }

/** the line being sung at [positionMs], or -1 before the first one */
fun List<LyricLine>.indexAt(positionMs: Long): Int = indexOfLast { it.timeMs <= positionMs }
