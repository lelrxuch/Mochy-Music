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

// the album, artist and playlist pages and the pieces they share

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
                                MoreButton(itemActions(app, artist))
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
                        youtube { YouTube.playlistContinuation(next) }.onSuccess {
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
                    val remove = { removeFromOnlinePlaylist(app, playlist.id, song) { songs = songs.filterNot { it.setVideoId == song.setVideoId } } }
                    SongRow(
                        song, onClick = { app.player.playList(songs, i, title) },
                        modifier = Modifier.reorderRow(drag, i, songs.size).padding(horizontal = 12.dp),
                        extra = listOf(str("remove_from_playlist") to remove),
                        trailing = { EditRowControls(drag, i, songs.size, remove) { from, to ->
                            songs = songs.toMutableList().apply { add(to, removeAt(from)) }
                            moveInOnlinePlaylist(app, playlist.id, songs[to], songs.getOrNull(to + 1))
                        } },
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
                MoreButton(songListActions(app, songs) + listOf(str("copy_link") to { copyLink(app, item.shareLink) }))
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
