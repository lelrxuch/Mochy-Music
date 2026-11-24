package com.mochyy.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/*
 * Playlists (ui/screens/playlist, ui/menu/AddToPlaylistDialog*.kt, CreatePlaylistDialog.kt) and the
 * dialogs they use. Signed in, your YouTube Music playlists are edited for real; signed out, the
 * playlists live on this computer only. Dialogs are drawn inside the window, on liquid glass.
 */

// ---- the dialog host ----

/** draws the open dialog (app.sheet) over everything, with a dim behind it; a click outside closes it */
@Composable
fun SheetHost() {
    val app = LocalApp.current
    val content = app.sheet
    // kept while it fades out, so the dialog doesn't vanish before its fade
    var shown by remember { mutableStateOf(content) }
    if (content != null) shown = content
    AnimatedVisibility(content != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { app.closeSheet() },
            contentAlignment = Alignment.Center,
        ) {
            // over the player the glass would show the page behind it, so it falls back to a plain tint there
            CompositionLocalProvider(LocalGlass provides if (app.nav.playerOpen) null else LocalGlass.current) {
                shown?.invoke()
            }
        }
    }
}

/** a dialog card: springs in, liquid glass, a title over [content] */
@Composable
fun Sheet(title: String, content: @Composable ColumnScope.() -> Unit) {
    val grow = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f)) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth()
            .graphicsLayer { scaleX = grow.value; scaleY = grow.value }
            .liquidGlass(shape, tint = 0.62f, elevation = 24.dp).clip(shape)
            // clicks inside the card stay inside it
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** a one-line text field in the app's pill style; Enter calls [onDone] */
@Composable
fun PillField(value: String, onChange: (String) -> Unit, placeholder: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Dim, style = MaterialTheme.typography.bodyLarge)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                val enter = it.key == Key.Enter && it.type == KeyEventType.KeyDown
                if (enter) onDone()
                enter
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
        )
    }
}

/** asks for a name; [initial] fills it in for a rename */
@Composable
fun NameSheet(title: String, confirm: String, initial: String = "", onConfirm: (String) -> Unit) {
    val app = LocalApp.current
    var name by remember { mutableStateOf(initial) }
    val done = { if (name.isNotBlank()) { app.closeSheet(); onConfirm(name.trim()) } }
    Sheet(title) {
        PillField(name, { name = it }, str("playlist_name"), done)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(str("cancel"), app::closeSheet, light = false)
            PillButton(confirm, done)
        }
    }
}

/** a yes/no for something that can't be undone */
@Composable
fun ConfirmSheet(title: String, body: String, confirm: String, onConfirm: () -> Unit) {
    val app = LocalApp.current
    Sheet(title) {
        Text(body, color = Dim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(str("cancel"), app::closeSheet, light = false)
            PillButton(confirm, { app.closeSheet(); onConfirm() })
        }
    }
}

// ---- adding to a playlist ----

/** a playlist songs can go into: one of yours on YouTube Music, or one on this computer */
sealed interface PlaylistTarget {
    val name: String
    val cover: String?

    data class Online(val playlist: PlaylistItem) : PlaylistTarget {
        override val name get() = playlist.title
        override val cover get() = playlist.thumbnail
    }

    data class Local(val playlist: LocalPlaylist) : PlaylistTarget {
        override val name get() = playlist.name
        override val cover get() = playlist.songs.firstOrNull()?.thumbnail
    }
}

fun playlistTargets(app: AppState): List<PlaylistTarget> {
    val online = if (app.account.session != null) app.library.data.playlists.filter { it.isEditable }.map(PlaylistTarget::Online) else emptyList()
    return online + app.library.data.localPlaylists.map(PlaylistTarget::Local)
}

/** the "add to playlist" dialog (AddToPlaylistDialog.kt): a new playlist, or one of yours */
@Composable
fun AddToPlaylistSheet(songs: List<SongItem>) {
    val app = LocalApp.current
    Sheet(str("add_to_playlist")) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            TargetRow(str("create_playlist"), null, icon = true) {
                app.showSheet { NameSheet(str("create_playlist"), str("create")) { name -> createPlaylist(app, name, songs) } }
            }
            playlistTargets(app).forEach { target ->
                TargetRow(target.name, target.cover) {
                    app.closeSheet()
                    addSongs(app, target, songs)
                }
            }
        }
    }
}

@Composable
private fun TargetRow(name: String, cover: String?, icon: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(drawable("add"), null, Modifier.size(22.dp), tint = Color.White)
            }
        } else {
            Artwork(cover, Modifier.size(44.dp), RoundedCornerShape(10.dp), size = 120)
        }
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun addSongs(app: AppState, target: PlaylistTarget, songs: List<SongItem>) {
    when (target) {
        is PlaylistTarget.Local -> {
            app.library.addToPlaylist(target.playlist.id, songs)
            app.player.message = str("desktop_added_to", target.name)
        }
        is PlaylistTarget.Online -> app.scope.launch {
            val id = target.playlist.id.removePrefix("VL")
            val sent = withContext(Dispatchers.IO) { signedIn { YouTube.addVideosToPlaylist(id, songs.map { it.id }) } }
            app.player.message = if (sent.isSuccess) str("desktop_added_to", target.name) else str("desktop_action_failed")
        }
    }
}

/** a new playlist with [songs] in it: on YouTube Music when signed in, on this computer otherwise */
fun createPlaylist(app: AppState, name: String, songs: List<SongItem> = emptyList()) {
    if (app.account.session == null) {
        app.library.createPlaylist(name, songs)
        app.player.message = str("desktop_created", name)
        return
    }
    app.scope.launch {
        val made = withContext(Dispatchers.IO) { signedIn { runCatching { YouTube.createPlaylist(name) } } }
        val id = made.getOrNull()
        if (id == null) {
            app.player.message = str("desktop_action_failed")
            return@launch
        }
        // on the shelf at once; YouTube's own list can take a while to show a new playlist
        val playlist = PlaylistItem(
            id = "VL$id", title = name, author = null, songCountText = null, thumbnail = songs.firstOrNull()?.thumbnail,
            playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null, isEditable = true,
        )
        app.sync.keepCreated(playlist.id)
        app.library.editSavedPlaylists { listOf(playlist) + it }
        val added = songs.isEmpty() || withContext(Dispatchers.IO) { signedIn { YouTube.addVideosToPlaylist(id, songs.map { it.id }) } }.isSuccess
        app.player.message = if (added) str("desktop_created", name) else str("desktop_action_failed")
        app.sync.pull()
    }
}

fun renameOnlinePlaylist(app: AppState, playlist: PlaylistItem, name: String, onDone: () -> Unit = {}) {
    app.scope.launch {
        val renamed = withContext(Dispatchers.IO) { signedIn { YouTube.renamePlaylist(playlist.id.removePrefix("VL"), name) } }
        app.player.message = if (renamed.isSuccess) null else str("desktop_action_failed")
        if (renamed.isSuccess) {
            onDone()
            app.library.editSavedPlaylists { list -> list.map { if (it.id == playlist.id) it.copy(title = name) else it } }
            app.sync.pull()
        }
    }
}

fun deleteOnlinePlaylist(app: AppState, playlist: PlaylistItem) {
    app.scope.launch {
        val deleted = withContext(Dispatchers.IO) { signedIn { YouTube.deletePlaylist(playlist.id.removePrefix("VL")) } }
        if (deleted.isSuccess) {
            app.nav.back()
            app.sync.forgetCreated(playlist.id)
            app.library.editSavedPlaylists { list -> list.filterNot { it.id == playlist.id } }
            app.sync.pull()
        } else {
            app.player.message = str("desktop_action_failed")
        }
    }
}

fun copyLink(app: AppState, link: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null) }
        .onSuccess { app.player.message = str("link_copied") }
}

/** the right-click menu of an album, artist or playlist (AlbumMenu.kt, ArtistMenu.kt, PlaylistMenu.kt) */
fun itemActions(app: AppState, item: YTItem): List<Pair<String, () -> Unit>> = buildList {
    when (item) {
        is SongItem -> return songActions(app, item)
        is AlbumItem -> add(str("play") to { app.player.playEndpoint(WatchEndpoint(playlistId = item.playlistId), item.title) })
        is PlaylistItem -> {
            add(str("play") to { app.player.playEndpoint(item.playEndpoint ?: WatchEndpoint(playlistId = item.id.removePrefix("VL")), item.title) })
            item.shuffleEndpoint?.let { add(str("shuffle") to { app.player.playEndpoint(it, item.title) }) }
            item.radioEndpoint?.let { add(str("start_radio") to { app.player.playEndpoint(it, item.title) }) }
        }
        is ArtistItem -> {
            item.shuffleEndpoint?.let { add(str("shuffle") to { app.player.playEndpoint(it, item.title) }) }
            item.radioEndpoint?.let { add(str("start_radio") to { app.player.playEndpoint(it, item.title) }) }
        }
    }
    val saved = app.library.isSaved(item)
    add(str(if (saved) "desktop_unsave" else "save") to { app.library.toggleSaved(item) })
    add(str("copy_link") to { copyLink(app, item.shareLink) })
}

// ---- a playlist on this computer ----

@Composable
fun LocalPlaylistScreen(id: String) {
    val app = LocalApp.current
    val playlist = app.library.localPlaylist(id)
    if (playlist == null) {
        // deleted while open
        LaunchedEffect(Unit) { app.nav.back() }
        return
    }
    val songs = playlist.songs
    val list = rememberLazyListState()
    ScrollPage(list) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = ChromeClearance)) {
            item(key = "header") {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Artwork(songs.firstOrNull()?.thumbnail, Modifier.size(240.dp), RoundedCornerShape(20.dp), size = 800)
                    Spacer(Modifier.width(28.dp))
                    Column {
                        Text(str("desktop_playlist"), color = Dim, style = MaterialTheme.typography.labelLarge)
                        Text(playlist.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(songsDetails(songs), color = Dim, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(str("play"), { app.player.playList(songs, 0, playlist.name) }, drawable("transport_play"))
                            PillButton(str("shuffle"), { app.player.playList(songs, 0, playlist.name, shuffle = true) }, drawable("shuffle"), light = false)
                            PillButton(str("desktop_rename"), {
                                app.showSheet { NameSheet(str("desktop_rename"), str("save"), playlist.name) { app.library.renamePlaylist(id, it) } }
                            }, light = false)
                            PillButton(str("delete"), {
                                app.showSheet {
                                    ConfirmSheet(str("desktop_delete_title", playlist.name), str("desktop_delete_body"), str("delete")) {
                                        app.library.deletePlaylist(id)
                                    }
                                }
                            }, light = false)
                        }
                    }
                }
            }
            if (songs.isEmpty()) item { Text(str("desktop_playlist_empty"), Modifier.padding(24.dp), color = Dim, style = MaterialTheme.typography.bodyLarge) }
            itemsIndexed(songs, key = { i, song -> "$i-${song.id}" }) { i, song ->
                SongRow(
                    song, onClick = { app.player.playList(songs, i, playlist.name) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    trailing = {
                        IconAction(drawable("close"), str("remove_from_playlist"), { app.library.removeFromPlaylist(id, i) }, size = 32.dp, iconSize = 16.dp, tint = Dim)
                    },
                )
            }
        }
    }
}

/** a playlist on this computer as a library row */
@Composable
fun LocalPlaylistRow(playlist: LocalPlaylist) {
    val app = LocalApp.current
    Row(
        Modifier.revealOnAppear("local:${playlist.id}").fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable { app.nav.open(Route.LocalPlaylist(playlist.id)) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.songs.firstOrNull()?.thumbnail, Modifier.size(56.dp), RoundedCornerShape(12.dp), size = 160)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(str("desktop_song_count", playlist.songs.size), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Icon(drawable("chevron_right_px"), null, Modifier.size(20.dp), tint = Dim)
    }
}
