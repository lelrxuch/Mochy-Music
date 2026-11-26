package com.mochyy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

// the settings page and its cards

// ---- settings: the Mochy page (ui/screens/settings) ----

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val list = rememberLazyListState()
    ScrollPage(list) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
            item { PageTitle(str("settings")) }
            item { AccountCard() }
            if (app.account.session != null) {
                item {
                    SettingsCard(str("desktop_report_title"), str("desktop_report_body")) {
                        ChoiceChipsInline(listOf(true, false), app.library.data.reportPlays, { str(if (it) "desktop_on" else "desktop_off") }, app.library::setReportPlays)
                    }
                }
            }
            item {
                SettingsCard("Audio Lab", str("desktop_audiolab_body")) {
                    PillButton(str("desktop_open_audiolab"), { app.nav.open(Route.AudioLab) })
                }
            }
            item {
                SettingsCard(str("audio_normalization"), str("desktop_normalize_body")) {
                    ChoiceChipsInline(listOf(true, false), app.library.data.normalize, { str(if (it) "desktop_on" else "desktop_off") }, app.library::setNormalize)
                }
            }
            item {
                SettingsCard(str("audio_quality"), str("desktop_quality_body")) {
                    ChoiceChipsInline(
                        listOf(true, false), app.library.data.highQuality,
                        { str(if (it) "audio_quality_high" else "audio_quality_low") }, app.library::setHighQuality,
                    )
                }
            }
            item {
                SettingsCard(str("history"), null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(str("clear_listen_history"), app.library::clearHistory, light = false)
                        PillButton(str("clear_search_history"), app.library::clearSearches, light = false)
                    }
                }
            }
            item {
                SettingsCard(str("desktop_diag_title"), str("desktop_diag_body")) {
                    PillButton(str("desktop_diag_run"), { app.showSheet { DiagnosticsSheet() } }, light = false)
                }
            }
            item {
                SettingsCard(str("desktop_about_title"), str("desktop_about_body")) {
                    Text("Mochy para Linux $APP_VERSION", color = Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    PillButton(str("desktop_open_github"), { openLink("https://github.com/realidkroo/mochy-music") })
                }
            }
        }
    }
}

const val APP_VERSION = "1.0.0"

/** the YouTube Music account: who's signed in, or the ways to sign in */
@Composable
private fun AccountCard() {
    val app = LocalApp.current
    val account = app.account
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<BrowserLogin>?>(null) }
    var pasted by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    fun signIn(cookie: String) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            account.signIn(cookie)
                .onSuccess {
                    found = null
                    pasted = ""
                    app.home.refresh()
                    app.sync.pull(adopt = true)
                }
                .onFailure { failed = true }
            busy = false
        }
    }

    val session = account.session
    if (session != null) {
        SettingsCard(str("account"), null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(session.avatar, Modifier.size(48.dp), CircleShape, size = 96)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(session.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = listOfNotNull(session.email, session.handle).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                PillButton(str("action_logout"), {
                    account.signOut()
                    app.home.refresh()
                }, light = false)
            }
        }
    } else {
        SettingsCard(str("account"), str("desktop_login_body")) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(str(if (busy) "desktop_login_busy" else "desktop_login_find"), {
                    if (!busy) {
                        busy = true
                        failed = false
                        scope.launch {
                            found = withContext(Dispatchers.IO) { BrowserCookies.find() }
                            busy = false
                        }
                    }
                })
                PillButton(str("desktop_login_open_ytm"), { openLink("https://music.youtube.com") }, light = false)
            }
            found?.let { logins ->
                Spacer(Modifier.height(12.dp))
                if (logins.isEmpty()) Text(str("desktop_login_none"), color = Dim, style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    logins.forEach { login -> PillButton(str("desktop_login_with", login.label), { signIn(login.cookie) }, light = false) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(str("desktop_login_paste"), color = Dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (pasted.isEmpty()) Text("SAPISID=…; SID=…", color = Dim, style = MaterialTheme.typography.bodyMedium)
                    // shown as dots: the cookie opens the account to whoever reads it off the screen
                    BasicTextField(
                        pasted, { pasted = it }, Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Spacer(Modifier.width(10.dp))
                PillButton(str("action_login"), { if (pasted.isNotBlank()) signIn(pasted) })
            }
            if (failed) {
                Text(str("desktop_login_failed"), Modifier.padding(top = 10.dp), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, body: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Raised).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, Modifier.padding(top = 4.dp), color = Dim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun <T> ChoiceChipsInline(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                Modifier.clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.08f)).clickable { onPick(option) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = if (on) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

fun openLink(url: String) {
    // Desktop.browse needs GTK libs that aren't always there; xdg-open is what every Linux desktop has
    runCatching { ProcessBuilder("xdg-open", url).start() }
        .onFailure { runCatching { Desktop.getDesktop().browse(URI(url)) } }
}
