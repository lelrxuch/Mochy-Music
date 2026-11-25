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

// ---- artist (ui/screens/artist) ----

@Composable
fun ArtistScreen(id: String) {
    val app = LocalApp.current
    var attempt by remember { mutableIntStateOf(0) }
    val load = rememberLoad(Route.Artist(id), attempt) { YouTube.artist(id) }
    val list = rememberLazyListState()
    LoadView(load, { attempt++ }) { page ->
        val artist = page.artist
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
                item(key = "header") {
                    Box(Modifier.fillMaxWidth().height(460.dp)) {
                        Artwork(artist.thumbnail, Modifier.fillMaxSize(), RectangleShape, size = 1200)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black)))
                        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                            Artwork(artist.thumbnail, Modifier.size(96.dp), CircleShape, size = 300)
                            Spacer(Modifier.height(12.dp))
                            Text(artist.title.uppercase(), fontFamily = Bartle, fontSize = 64.sp, lineHeight = 64.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val counts = listOfNotNull(page.subscriberCountText, page.monthlyListenerCount).joinToString(" · ")
                            if (counts.isNotEmpty()) Text(counts, color = Dim, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                (artist.playEndpoint ?: artist.radioEndpoint)?.let { endpoint ->
                                    PillButton(str("play"), { app.player.playEndpoint(endpoint, artist.title) }, drawable("transport_play"))
                                }
                                artist.shuffleEndpoint?.let { endpoint ->
                                    PillButton(str("shuffle"), { app.player.playEndpoint(endpoint, artist.title) }, drawable("shuffle"), light = false)
                                }
                                artist.radioEndpoint?.let { endpoint ->
                                    PillButton(str("radio"), { app.player.playEndpoint(endpoint, artist.title) }, drawable("radio"), light = false)
                                }
                                SaveButton(artist)
                            }
                        }
                    }
                }
                page.sections.forEach { section(it.title, it.items) }
                page.description?.let { about -> item(key = "about") { AboutCard(str("about"), about) } }
            }
        }
    }
}

// ---- album (ui/screens/AlbumScreen.kt) ----

@Composable
fun AlbumScreen(id: String) {
    val app = LocalApp.current
    var attempt by remember { mutableIntStateOf(0) }
    val load = rememberLoad(Route.Album(id), attempt) { YouTube.album(id) }
    val list = rememberLazyListState()
    LoadView(load, { attempt++ }) { page ->
        val album = page.album
        val songs = page.songs
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = ChromeClearance)) {
                item(key = "header") {
                    CollectionHeader(
                        cover = album.thumbnail,
                        kind = listOfNotNull(str("desktop_album"), album.year?.toString()).joinToString(" · "),
                        title = album.title,
                        by = album.artists.orEmpty().joinToString { it.name },
                        onBy = album.artists?.firstOrNull { it.id != null }?.let { artist -> { app.nav.open(Route.Artist(artist.id!!)) } },
                        details = songsDetails(songs),
                        songs = songs,
                        item = album,
                    )
                }
                songList(songs, album.title, numbered = true)
                section(str("other_versions"), page.otherVersions)
                page.description?.let { about -> item(key = "about") { AboutCard(str("about"), about) } }
            }
        }
    }
}

// ---- playlist (ui/screens/playlist) ----

@Composable
fun PlaylistScreen(id: String) {
    val app = LocalApp.current
    var attempt by remember { mutableIntStateOf(0) }
    val load = rememberLoad(Route.Playlist(id), attempt) { YouTube.playlist(id) }
    val list = rememberLazyListState()
    LoadView(load, { attempt++ }) { page ->
        val playlist = page.playlist
        // long playlists come in pages, the next one loads as the end comes into view
        var songs by remember(page) { mutableStateOf(page.songs) }
        var token by remember(page) { mutableStateOf(page.songsContinuation) }
        LaunchedEffect(page) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
                .collect { (last, total) ->
                    val next = token
                    if (next != null && last != null && last >= total - 5) {
                        token = null
                        withContext(Dispatchers.IO) { YouTube.playlistContinuation(next) }.onSuccess {
                            songs = songs + it.songs
                            token = it.continuation
                        }
                    }
                }
        }
        // one of yours on YouTube Music: it can be renamed, deleted, reordered and have songs taken out
        val editable = playlist.isEditable && app.account.session != null
        val drag = remember { DragReorder() }
        var title by remember(page) { mutableStateOf(playlist.title) }
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = ChromeClearance)) {
                item(key = "header") {
                    CollectionHeader(
                        cover = playlist.thumbnail,
                        kind = str("desktop_playlist"),
                        title = title,
                        by = playlist.author?.name.orEmpty(),
                        onBy = playlist.author?.id?.let { author -> { app.nav.open(Route.Artist(author)) } },
                        details = playlist.songCountText ?: songsDetails(songs),
                        songs = songs,
                        item = playlist,
                        radio = playlist.radioEndpoint?.let { endpoint -> { app.player.playEndpoint(endpoint, playlist.title) } },
                        extra = {
                            if (editable) {
                                IconAction(drawable("edit"), str("desktop_rename"), {
                                    app.showSheet {
                                        NameSheet(str("desktop_rename"), str("save"), title) { name -> renameOnlinePlaylist(app, playlist, name) { title = name } }
                                    }
                                }, background = Color.White.copy(alpha = 0.12f), iconSize = 20.dp)
                                IconAction(drawable("delete"), str("delete"), {
                                    app.showSheet {
                                        ConfirmSheet(str("desktop_delete_title", title), str("desktop_delete_body"), str("delete")) { deleteOnlinePlaylist(app, playlist) }
                                    }
                                }, background = Color.White.copy(alpha = 0.12f), iconSize = 20.dp)
                            }
                        },
                    )
                }
                if (!editable) songList(songs, title, numbered = false)
                else itemsIndexed(songs, key = { i, song -> "$i-${song.id}" }) { i, song ->
                    SongRow(
                        song, onClick = { app.player.playList(songs, i, title) },
                        modifier = Modifier.reorderRow(drag, i, songs.size).padding(horizontal = 12.dp),
                        trailing = song.setVideoId?.let { setVideoId ->
                            { Row(verticalAlignment = Alignment.CenterVertically) {
                                IconAction(drawable("close"), str("remove_from_playlist"), {
                                    app.scope.launch {
                                        val removed = withContext(Dispatchers.IO) {
                                            signedIn { YouTube.removeFromPlaylist(playlist.id.removePrefix("VL"), song.id, setVideoId) }
                                        }
                                        if (removed.isSuccess) songs = songs.filterNot { it.setVideoId == setVideoId }
                                        else app.player.message = str("desktop_action_failed")
                                    }
                                }, size = 32.dp, iconSize = 16.dp, tint = Dim)
                                // dropped: YouTube is told which song now follows it
                                Icon(
                                    drawable("drag_handle"), null,
                                    Modifier.padding(start = 4.dp).size(22.dp).dragHandle(drag, i, songs.size) { from, to ->
                                        val moved = songs[from]
                                        val reordered = songs.toMutableList().apply { add(to, removeAt(from)) }
                                        songs = reordered
                                        val successor = reordered.getOrNull(to + 1)?.setVideoId
                                        moved.setVideoId?.let { movedId ->
                                            app.scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    signedIn { YouTube.moveSongPlaylist(playlist.id.removePrefix("VL"), movedId, successor) }
                                                }
                                                if (ok.isFailure) app.player.message = str("desktop_action_failed")
                                            }
                                        }
                                    },
                                    tint = Dim,
                                )
                            } }
                        },
                    )
                }
                page.related?.let { section(str("desktop_more"), it) }
            }
        }
    }
}

internal fun songsDetails(songs: List<SongItem>): String {
    val seconds = songs.sumOf { it.duration ?: 0 }
    return str("desktop_song_count", songs.size) + if (seconds > 0) " · " + formatTime(seconds.toDouble()) else ""
}

/** the cover, names and play buttons at the top of an album or playlist */
@Composable
private fun CollectionHeader(
    cover: String?,
    kind: String,
    title: String,
    by: String,
    onBy: (() -> Unit)?,
    details: String,
    songs: List<SongItem>,
    item: YTItem,
    radio: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val app = LocalApp.current
    Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
        Artwork(cover, Modifier.size(240.dp), RoundedCornerShape(20.dp), size = 800)
        Spacer(Modifier.width(28.dp))
        Column {
            Text(kind, color = Dim, style = MaterialTheme.typography.labelLarge)
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (by.isNotBlank()) {
                Text(
                    by, Modifier.clip(RoundedCornerShape(6.dp)).let { if (onBy != null) it.clickable(onClick = onBy) else it },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(details, color = Dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(str("play"), { app.player.playList(songs, 0, title) }, drawable("transport_play"))
                PillButton(str("shuffle"), { app.player.playList(songs, 0, title, shuffle = true) }, drawable("shuffle"), light = false)
                if (radio != null) PillButton(str("radio"), radio, drawable("radio"), light = false)
                SaveButton(item)
                extra()
            }
        }
    }
}

private fun LazyListScope.songList(songs: List<SongItem>, title: String, numbered: Boolean) {
    itemsIndexed(songs, key = { i, song -> "$i-${song.id}" }) { i, song ->
        val app = LocalApp.current
        SongRow(
            song, onClick = { app.player.playList(songs, i, title) },
            modifier = Modifier.padding(horizontal = 12.dp),
            number = if (numbered) i + 1 else null,
        )
    }
}

/** the heart that keeps an album, artist or playlist in the library */
@Composable
fun SaveButton(item: YTItem) {
    val app = LocalApp.current
    val saved = app.library.isSaved(item)
    IconAction(
        drawable(if (saved) "ic_heart" else "ic_heart_outline"), str(if (saved) "desktop_saved" else "save"),
        { app.library.toggleSaved(item) }, background = Color.White.copy(alpha = 0.12f), iconSize = 20.dp,
    )
}

@Composable
private fun AboutCard(title: String, text: String) {
    Column(Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Raised).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
    }
}

// ---- library (ui/screens/library) ----

enum class Shelf(val labelKey: String) { Liked("liked_songs"), History("history"), Albums("filter_albums"), Artists("filter_artists"), Playlists("playlists"), Downloads("desktop_downloads") }

@Composable
fun LibraryScreen() {
    val app = LocalApp.current
    var shelf by remember { mutableStateOf(Shelf.Liked) }
    // signed in, the history can be this computer's or the account's (what every device played)
    var fromAccount by remember { mutableStateOf(true) }
    val accountHistory = if (shelf == Shelf.History && fromAccount && app.account.session != null) {
        rememberLoad("yt-history") { YouTube.musicHistory() }
    } else null
    val data = app.library.data
    val list = rememberLazyListState()
    Column {
        PageTitle(str("desktop_library"))
        ChoiceChips(Shelf.entries, shelf, { str(it.labelKey) }) { shelf = it }
        if (app.account.session != null) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                PillButton(str(if (app.sync.syncing) "desktop_syncing" else "desktop_sync"), { app.sync.pull() }, drawable("sync"), light = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = ChromeClearance)) {
                if (shelf == Shelf.History && accountHistory != null) {
                        item { HistorySourceChips(fromAccount) { fromAccount = it } }
                        when (accountHistory) {
                            is Load.Ready -> {
                                val all = accountHistory.value.sections.orEmpty().flatMap { it.songs }
                                accountHistory.value.sections.orEmpty().forEach { group ->
                                    item { SectionHeader(group.title) }
                                    itemsIndexed(group.songs, key = { i, song -> "yt-${group.title}-$i-${song.id}" }) { _, song ->
                                        SongRow(song, onClick = { app.player.playList(all, all.indexOf(song).coerceAtLeast(0), str("history")) })
                                    }
                                }
                            }
                            is Load.Failed -> item { Text(str("desktop_cant_load"), Modifier.padding(24.dp), color = Dim) }
                            Load.Loading -> {}
                        }
                } else when (shelf) {
                    Shelf.Downloads -> {
                        val songs = data.downloads.map { it.song }
                        val busy = app.player.downloads.progress
                        if (busy.isNotEmpty()) item {
                            Text(str("desktop_downloading", busy.size), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Dim, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (songs.isEmpty() && busy.isEmpty()) item { Text(str("desktop_downloads_empty"), Modifier.padding(16.dp), color = Dim, style = MaterialTheme.typography.bodyLarge) }
                        else if (songs.isNotEmpty()) item {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton(str("play"), { app.player.playList(songs, 0, str("desktop_downloads")) }, drawable("transport_play"))
                                PillButton(str("shuffle"), { app.player.playList(songs, 0, str("desktop_downloads"), shuffle = true) }, drawable("shuffle"), light = false)
                            }
                        }
                        itemsIndexed(songs, key = { _, song -> "dl-${song.id}" }) { i, song ->
                            SongRow(song, onClick = { app.player.playList(songs, i, str("desktop_downloads")) })
                        }
                    }
                    Shelf.Liked, Shelf.History -> {
                        if (shelf == Shelf.History && app.account.session != null) item { HistorySourceChips(fromAccount) { fromAccount = it } }
                        val songs = if (shelf == Shelf.Liked) data.liked else data.history
                        val title = str(shelf.labelKey)
                        if (songs.isEmpty()) item { EmptyShelf() } else item {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton(str("play"), { app.player.playList(songs, 0, title) }, drawable("transport_play"))
                                PillButton(str("shuffle"), { app.player.playList(songs, 0, title, shuffle = true) }, drawable("shuffle"), light = false)
                                if (shelf == Shelf.History) PillButton(str("clear_listen_history"), app.library::clearHistory, light = false)
                            }
                        }
                        itemsIndexed(songs, key = { _, song -> song.id }) { i, song ->
                            SongRow(song, onClick = { app.player.playList(songs, i, title) })
                        }
                    }
                    else -> {
                        val items: List<YTItem> = when (shelf) {
                            Shelf.Albums -> data.albums
                            Shelf.Artists -> data.artists
                            else -> data.playlists
                        }
                        if (shelf == Shelf.Playlists) {
                            item {
                                Row(Modifier.padding(12.dp)) {
                                    PillButton(str("create_playlist"), {
                                        app.showSheet { NameSheet(str("create_playlist"), str("create")) { name -> createPlaylist(app, name) } }
                                    }, drawable("add"))
                                }
                            }
                            items(data.localPlaylists, key = { it.id }) { LocalPlaylistRow(it) }
                        }
                        if (items.isEmpty() && (shelf != Shelf.Playlists || data.localPlaylists.isEmpty())) item { EmptyShelf() }
                        items(items, key = { it.id }) { ItemRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf() {
    Text(str("desktop_library_empty"), Modifier.padding(16.dp), color = Dim, style = MaterialTheme.typography.bodyLarge)
}

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
private fun <T> ChoiceChipsInline(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
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

@Composable
private fun HistorySourceChips(fromAccount: Boolean, onPick: (Boolean) -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ChoiceChipsInline(listOf(false, true), fromAccount, { str(if (it) "desktop_history_account" else "desktop_history_here") }, onPick)
    }
}
