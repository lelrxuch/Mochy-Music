package com.mochyy.desktop

import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.utils.parseCookieString
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where a song's audio comes from: a link mpv can play, the user agent googlevideo expects with it,
 * and which client found it. [viaYtDlp] means mpv hands the watch page to yt-dlp instead.
 */
data class Stream(val url: String, val userAgent: String?, val source: String) {
    val viaYtDlp get() = source == YT_DLP
}

private const val YT_DLP = "yt-dlp"

// the TV clients play what's age-restricted for a web page; their links are signed, which
// NewPipe undoes. IOS, IPADOS and the ANDROID_VR clients are left out on purpose: without a
// PoToken (which the app mints in a WebView) their links stop dead at ~786 KB, a half-minute in.
internal val FALLBACK_CLIENTS = listOf(
    YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    YouTubeClient.TVHTML5,
    YouTubeClient.WEB_REMIX,
)

// NewPipe's own audio formats, best first: opus 160k, aac 128k, opus 70k and 50k, aac 48k
private val NEWPIPE_AUDIO_ITAGS = listOf(251, 140, 250, 249, 139)

/**
 * The app's YTPlayerUtils, as far as it goes without PoTokens: VISIONOS first (direct links, no
 * token), then the TV and signed-in clients with NewPipe undoing the signature, then NewPipe's own
 * extractor, and last yt-dlp through mpv, with the session's cookies so restricted songs play.
 * Every link is tried with a two-byte read before it's handed to mpv, so a 403 moves on instead of
 * failing in mpv. [high] picks the best bitrate, otherwise the smallest.
 */
suspend fun findStream(videoId: String, high: Boolean = true): Stream {
    visionos(videoId, high)?.let { return it }
    for (client in FALLBACK_CLIENTS) {
        if (client.loginRequired && YouTube.cookie == null) continue
        signed(videoId, client, high)?.let { return it }
    }
    newPipe(videoId, high)?.let { return it }
    return Stream("https://music.youtube.com/watch?v=$videoId", null, YT_DLP)
}

/** just the link, for the checks that only want to know a stream answers */
suspend fun audioUrl(videoId: String, high: Boolean = true): String = findStream(videoId, high).url

internal suspend fun visionos(videoId: String, high: Boolean): Stream? {
    val client = YouTubeClient.VISIONOS
    suspend fun ask() = YouTube.player(videoId, null, client).getOrNull()
    var response = ask()
    // a bot check burns the visitor id: one fresh id and one more try, like the app
    if (response?.playabilityStatus?.status == "LOGIN_REQUIRED") {
        YouTube.refreshVisitorData(client)
        response = ask()
    }
    val formats = response?.takeIf { it.playabilityStatus.status == "OK" }
        ?.streamingData?.adaptiveFormats.orEmpty()
        .filter { it.isAudio && it.url != null }
        .sortedBy { if (high) -it.bitrate else it.bitrate }
    // one try per client: a link refused for one format is refused for all of them
    val url = formats.firstOrNull()?.url ?: return null
    return Stream(url, client.userAgent, client.clientName).takeIf { answers(url, client.userAgent) }
}

internal suspend fun signed(videoId: String, client: YouTubeClient, high: Boolean): Stream? {
    val timestamp = if (client.useSignatureTimestamp) NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull() else null
    val response = YouTube.player(videoId, null, client, timestamp).getOrNull()
        ?.takeIf { it.playabilityStatus.status == "OK" } ?: return null
    val formats = response.streamingData?.adaptiveFormats.orEmpty()
        .filter { it.isAudio }
        .sortedBy { if (high) -it.bitrate else it.bitrate }
    // the first format NewPipe can unsign, then one try: a refusal holds for the whole client
    val url = formats.firstNotNullOfOrNull { NewPipeExtractor.getStreamUrl(it, videoId) } ?: return null
    return Stream(url, client.userAgent, client.clientName).takeIf { answers(url, client.userAgent) }
}

internal fun newPipe(videoId: String, high: Boolean): Stream? {
    val streams = NewPipeExtractor.newPipePlayer(videoId).toMap()
    val order = if (high) NEWPIPE_AUDIO_ITAGS else NEWPIPE_AUDIO_ITAGS.reversed()
    val url = order.firstNotNullOfOrNull { streams[it] } ?: return null
    return Stream(url, null, "NewPipe").takeIf { answers(url, null) }
}

/** asks for the first two bytes: a link that's refused (403) or gone is no use to mpv */
private fun answers(url: String, userAgent: String?): Boolean = runCatching {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 6_000
    connection.readTimeout = 6_000
    connection.setRequestProperty("Range", "bytes=0-1")
    userAgent?.let { connection.setRequestProperty("User-Agent", it) }
    val code = connection.responseCode
    connection.disconnect()
    code in 200..206
}.getOrDefault(false)

/** whether yt-dlp, the last resort, is installed */
val hasYtDlp: Boolean by lazy {
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "yt-dlp").canExecute() }
}

/**
 * The session's cookie as a cookies.txt (Netscape format) for yt-dlp, private to the user, in the
 * runtime dir; null when signed out.
 */
fun ytDlpCookies(): File? {
    val cookie = YouTube.cookie ?: return null
    val dir = File(System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"))
    val file = File(dir, "mochy-ytdlp-cookies.txt")
    val lines = buildString {
        appendLine("# Netscape HTTP Cookie File")
        parseCookieString(cookie).forEach { (name, value) ->
            appendLine(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$name\t$value")
        }
    }
    return runCatching {
        val temp = File(dir, "mochy-ytdlp-cookies.tmp")
        temp.delete()
        Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        temp.writeText(lines)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        file.deleteOnExit()
        file
    }.getOrNull()
}
