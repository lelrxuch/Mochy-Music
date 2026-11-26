package com.mochyy.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

/** a cover at [size] pixels: YouTube serves tiny ones unless asked (the app's String.resize in ui/utils/YouTubeUtils.kt) */
fun String.artwork(size: Int): String = when {
    "i.ytimg.com" in this -> replace(Regex("(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), if (size >= 1200) "maxresdefault.jpg" else "hqdefault.jpg")
    "googleusercontent.com" in this && "=w" in this -> "${substringBefore("=w")}=w$size-h$size-p-l90-rj"
    "yt3.ggpht.com" in this -> "${substringBefore("=")}=s$size"
    else -> this
}

@Composable
fun Artwork(url: String?, modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp), size: Int = 300) {
    AsyncImage(
        model = url?.artwork(size),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).background(Raised),
    )
}

fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

/** the grey line under an item's title */
fun YTItem.subtitle(): String = when (this) {
    is SongItem -> listOfNotNull(artistNames(), album?.name).filter { it.isNotBlank() }.joinToString(" · ")
    is AlbumItem -> listOfNotNull(artists?.joinToString { it.name }, year?.toString()).joinToString(" · ")
    is PlaylistItem -> listOfNotNull(author?.name, songCountText).joinToString(" · ")
    is ArtistItem -> str("desktop_artist")
}

/** what a song's menu offers, both on right click and from its ⋯ button */
fun songActions(app: AppState, song: SongItem): List<Pair<String, () -> Unit>> = buildList {
    add(str("play_next") to { app.player.playNext(song) })
    add(str("add_to_queue") to { app.player.addToQueue(song) })
    add(str("add_to_playlist") to { app.showSheet { AddToPlaylistSheet(listOf(song)) } })
    add(str("desktop_select") to { app.selection.toggle(song) })
    add(str("start_radio") to { app.player.playSong(song) })
    add((if (app.library.isLiked(song.id)) str("desktop_unlike") else str("like")) to { app.library.toggleLike(song) })
    song.artists.firstOrNull { it.id != null }?.let { artist -> add(str("view_artist") to { app.nav.open(Route.Artist(artist.id!!)) }) }
    song.album?.let { album -> add(str("view_album") to { app.nav.open(Route.Album(album.id)) }) }
    if (app.player.downloads.isDownloaded(song.id)) add(str("remove_download") to { app.player.downloads.remove(song) })
    else add(str("desktop_download") to { app.player.downloads.download(listOf(song)) })
    add(str("copy_link") to { copyLink(app, song.shareLink) })
}

/** the ⋯ of a page full of songs (album, playlist): the same things a song offers, for all of them at once */
fun songListActions(app: AppState, songs: List<SongItem>): List<Pair<String, () -> Unit>> =
    if (songs.isEmpty()) emptyList() else listOf(
        str("play_next") to { app.player.playNext(songs) },
        str("add_to_queue") to { app.player.addToQueue(songs) },
        str("add_to_playlist") to { app.showSheet { AddToPlaylistSheet(songs) } },
        str("desktop_download_all") to { app.player.downloads.download(songs) },
    )

@Composable
fun SongRow(
    song: SongItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    // what this place adds to the song's menu, such as taking it out of a playlist
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    // shown before the menu button, never instead of it
    trailing: (@Composable () -> Unit)? = null,
) {
    val app = LocalApp.current
    val playing = app.player.current?.id == song.id
    val actions = { songActions(app, song) + extra }
    ContextMenuArea(items = { actions().map { (label, action) -> ContextMenuItem(label, action) } }) {
        val selected = app.selection.isSelected(song.id)
        Row(
            // while songs are being picked (or Ctrl/Shift is held) a click picks instead of playing
            modifier.revealOnAppear("song:${song.id}").fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable { if (app.selection.active || app.selection.extending) app.selection.toggle(song) else onClick() }
                .background(if (selected) Color.White.copy(alpha = 0.14f) else if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                Text("$number", Modifier.width(32.dp), color = Dim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Artwork(song.thumbnail, Modifier.size(48.dp), RoundedCornerShape(10.dp), size = 120)
                    if (selected) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Icon(drawable("check"), null, Modifier.size(22.dp), tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(song.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Dim)
            }
            song.duration?.let { Text(formatTime(it.toDouble()), Modifier.padding(horizontal = 12.dp), color = Dim, style = MaterialTheme.typography.bodySmall) }
            trailing?.invoke()
            MoreButton(actions())
        }
    }
}

/** the round ⋯ button and the menu it opens */
@Composable
fun MoreButton(actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(drawable("more_horiz"), str("desktop_more"), { open = true }, size = 32.dp, iconSize = 18.dp, background = Color.White.copy(alpha = 0.08f))
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Raised, shape = RoundedCornerShape(16.dp)) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

/** a square cover with two lines under it; artists get a round one */
@Composable
fun ItemCard(item: YTItem, width: Dp = 168.dp) {
    val app = LocalApp.current
    val round = item is ArtistItem
    val menu = itemActions(app, item)
    ContextMenuArea(items = { menu.map { (label, action) -> ContextMenuItem(label, action) } }) {
        Column(
            Modifier.revealOnAppear("card:${item.id}").press3D().width(width).clip(RoundedCornerShape(16.dp)).clickable { app.open(item) }.padding(6.dp),
            horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Artwork(item.thumbnail, Modifier.size(width - 12.dp), if (round) CircleShape else RoundedCornerShape(16.dp), size = 400)
            Spacer(Modifier.height(8.dp))
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Dim)
        }
    }
}

/** an album, artist or playlist as a list row */
@Composable
fun ItemRow(item: YTItem) {
    val app = LocalApp.current
    if (item is SongItem) return SongRow(item, onClick = { app.player.playSong(item) })
    ContextMenuArea(items = { itemActions(app, item).map { (label, action) -> ContextMenuItem(label, action) } }) { Row(
        Modifier.revealOnAppear("row:${item.id}").fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { app.open(item) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.thumbnail, Modifier.size(56.dp), if (item is ArtistItem) CircleShape else RoundedCornerShape(12.dp), size = 160)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Icon(drawable("chevron_right_px"), null, Modifier.size(20.dp), tint = Dim)
    } }
}

/** pills to pick one of a few views, like the library's All / Local / Cached */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val option = options[i]
            val on = option == selected
            Text(
                label(option),
                Modifier.clip(CircleShape).background(if (on) Color.White else Raised).clickable { onPick(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (on) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** "Recently played >" over a row of cards */
@Composable
fun SectionHeader(title: String, label: String? = null, onMore: (() -> Unit)? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 10.dp)) {
        if (label != null) Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dim)
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).let { if (onMore != null) it.clickable(onClick = onMore) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (onMore != null) Icon(drawable("chevron_right_px"), null, Modifier.size(22.dp), tint = Dim)
        }
    }
}

/** a section's items as a row of cards, or as song rows when it's all songs */
fun LazyListScope.section(title: String, items: List<YTItem>, label: String? = null, songsAsRows: Boolean = true) {
    if (items.isEmpty()) return
    item(key = "header-$title-${items.first().id}") { SectionHeader(title, label) }
    val songs = items.filterIsInstance<SongItem>()
    if (songsAsRows && songs.size == items.size) {
        item(key = "rows-$title-${items.first().id}") { SongColumns(songs) }
    } else {
        item(key = "cards-$title-${items.first().id}") {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items, key = { it.id }) { ItemCard(it) }
            }
        }
    }
}

/** songs in two columns on a wide window, like the app's quick picks grid */
@Composable
fun SongColumns(songs: List<SongItem>, columns: Int = 2) {
    val app = LocalApp.current
    val shown = songs.take(8)
    val groups = shown.chunked(((shown.size + columns - 1) / columns).coerceAtLeast(1))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(columns) { c ->
            Column(Modifier.weight(1f)) {
                groups.getOrNull(c)?.forEach { song -> SongRow(song, onClick = { app.player.playSong(song) }) }
            }
        }
    }
}

/** a page's loading spinner or its error with a retry button */
@Composable
fun <T> LoadView(load: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        is Load.Ready -> content(load.value)
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        }
        is Load.Failed -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(str("desktop_cant_load"), style = MaterialTheme.typography.titleMedium)
            load.reason?.let { Text(it.take(160), color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            PillButton(str("retry"), onRetry)
        }
    }
}

/** a scrolling page with a thin desktop scrollbar on its right edge */
@Composable
fun ScrollPage(state: LazyListState, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 2.dp))
    }
}

/** the room at a page's bottom so the floating mini player and nav bar never cover the last row */
val ChromeClearance = 168.dp

@Composable
fun PageTitle(text: String) {
    Text(text, Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
}
