package com.mochyy.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Playback diagnostics (ui/screens/settings/PlaybackDiagnosticsScreen.kt): on this computer's real
 * network, which stream sources answer, whether yt-dlp works with the session, whether this mpv has
 * each Audio Lab filter, and whether downloads can be written. The report can be copied.
 */

/** one line of the report: [ok] null means it was skipped */
data class Check(val label: String, val ok: Boolean?, val detail: String = "")

private fun run(command: List<String>, seconds: Long = 30): Pair<Int, String> = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroy()
        return@runCatching -1 to "timeout"
    }
    process.exitValue() to out
}.getOrElse { -1 to (it.message ?: "couldn't start") }

/** mpv plays half a second of a test tone through [filter] into no device: errors mean the filter isn't there */
private fun mpvAccepts(filter: String?): Pair<Boolean, String> {
    val command = listOf("mpv", "--no-config", "--no-video", "--ao=null", "--msg-level=all=error") +
        (filter?.let { listOf("--af=lavfi=[$it]") } ?: emptyList()) + "av://lavfi:sine=frequency=440:duration=0.5"
    val (code, out) = run(command, 15)
    return (code == 0 && out.isBlank()) to out.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(120)
}

suspend fun runDiagnostics(app: AppState, onCheck: (Check) -> Unit) = withContext(Dispatchers.IO) {
    onCheck(Check("Mochy $APP_VERSION · Java ${System.getProperty("java.version")}", true))
    onCheck(Check(str("account"), app.account.session != null, app.account.session?.name.orEmpty()))
    ensureVisitorData()
    onCheck(Check("visitorData", YouTube.visitorData != null))

    val song = signedIn { YouTube.search("daft punk one more time", YouTube.SearchFilter.FILTER_SONG) }
        .getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()
    onCheck(Check(str("search"), song != null, song?.title.orEmpty()))
    if (song != null) {
        onCheck(Check("VISIONOS", visionos(song.id, true) != null))
        FALLBACK_CLIENTS.forEach { client ->
            val skip = client.loginRequired && YouTube.cookie == null
            onCheck(Check(client.clientName, if (skip) null else signed(song.id, client, true) != null))
        }
        onCheck(Check("NewPipe", newPipe(song.id, true) != null))
        if (hasYtDlp) {
            val cookies = ytDlpCookies()
            val (code, out) = run(
                listOf("yt-dlp", "-g", "-f", "bestaudio/best", "--no-warnings") +
                    (cookies?.let { listOf("--cookies", it.path) } ?: emptyList()) + "https://music.youtube.com/watch?v=${song.id}",
                60,
            )
            onCheck(Check("yt-dlp", code == 0 && "googlevideo" in out, if (code == 0) "" else out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)))
        } else {
            onCheck(Check("yt-dlp", false, "sudo dnf install yt-dlp"))
        }
        onCheck(Check(str("desktop_diag_chosen"), true, findStream(song.id).source))
    }

    val (mpvVersionCode, mpvVersion) = run(listOf("mpv", "--version"), 10)
    onCheck(Check("mpv", mpvVersionCode == 0, mpvVersion.lines().firstOrNull().orEmpty()))
    val (baseline, baselineError) = mpvAccepts(null)
    onCheck(Check(str("desktop_diag_test_tone"), baseline, baselineError))
    if (baseline) {
        listOf(
            str("desktop_equalizer") to "equalizer=f=1000:t=q:w=1.41:g=3",
            str("desktop_reverb") to "aecho=0.8:0.9:60|95:0.35|0.28",
            str("desktop_crossfeed") to "crossfeed=strength=0.25",
            str("desktop_mono") to "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1",
            str("desktop_8d") to "apulsator=hz=0.08:amount=0.85",
        ).forEach { (label, filter) ->
            val (ok, error) = mpvAccepts(filter)
            onCheck(Check("$label ($filter)".take(60), ok, error))
        }
    }

    val probe = File(dataFile("downloads"), ".probe")
    val writable = runCatching { probe.parentFile.mkdirs(); probe.writeText("ok"); probe.delete() }.isSuccess
    onCheck(Check(str("desktop_downloads"), writable, dataFile("downloads").path))
}

/** the report as plain text, to paste somewhere */
fun reportText(checks: List<Check>) = checks.joinToString("\n") { check ->
    val mark = when (check.ok) { true -> "OK"; false -> "FALLA"; null -> "omitido" }
    "[$mark] ${check.label}" + if (check.detail.isNotBlank()) " — ${check.detail}" else ""
}

@Composable
fun DiagnosticsSheet() {
    val app = LocalApp.current
    val checks = remember { mutableStateListOf<Check>() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runDiagnostics(app) { check -> app.scope.launch { checks.add(check) } }
        done = true
    }
    Sheet(str("desktop_diag_title")) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            checks.toList().forEach { check ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    val (mark, color) = when (check.ok) {
                        true -> "✓" to Color(0xFF8BE28B)
                        false -> "✗" to Color(0xFFFF8A80)
                        null -> "–" to Dim
                    }
                    Text(mark, Modifier.width(22.dp), color = color, style = MaterialTheme.typography.bodyLarge)
                    Column {
                        Text(check.label, style = MaterialTheme.typography.bodyMedium)
                        if (check.detail.isNotBlank()) Text(check.detail, color = Dim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!done) Text(str("desktop_diag_running"), Modifier.padding(top = 8.dp), color = Dim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(str("desktop_diag_copy"), { copyLink(app, reportText(checks.toList())) }, light = false)
            PillButton(str("desktop_done"), app::closeSheet)
        }
    }
}
