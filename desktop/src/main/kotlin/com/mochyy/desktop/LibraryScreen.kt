package com.mochyy.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.models.YTItem

// the library: liked songs, history, saved albums, artists and playlists, downloads

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

@Composable
private fun HistorySourceChips(fromAccount: Boolean, onPick: (Boolean) -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ChoiceChipsInline(listOf(false, true), fromAccount, { str(if (it) "desktop_history_account" else "desktop_history_here") }, onPick)
    }
}
