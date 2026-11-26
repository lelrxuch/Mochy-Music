package com.mochyy.desktop.ui.screens

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.YouTube.SearchFilter
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.delay

enum class SearchKind(val labelKey: String, val filter: SearchFilter?) {
    All("desktop_all", null),
    Songs("filter_songs", SearchFilter.FILTER_SONG),
    Videos("filter_videos", SearchFilter.FILTER_VIDEO),
    Albums("filter_albums", SearchFilter.FILTER_ALBUM),
    Artists("filter_artists", SearchFilter.FILTER_ARTIST),
    Community("filter_community_playlists", SearchFilter.FILTER_COMMUNITY_PLAYLIST),
    Featured("filter_featured_playlists", SearchFilter.FILTER_FEATURED_PLAYLIST),
}

/** What's typed and what was searched, kept while you visit other tabs. */
class SearchModel {
    var query by mutableStateOf("")
    var submitted by mutableStateOf<String?>(null)
    var kind by mutableStateOf(SearchKind.All)
}

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val search = app.search
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        search.query = query
        search.submitted = query
        app.library.addSearch(query)
    }

    Column {
        PageTitle(str("search"))
        SearchField(search.query, { search.query = it; if (it.isBlank()) search.submitted = null }, ::submit, Modifier.focusRequester(focus))
        Spacer(Modifier.height(12.dp))
        val submitted = search.submitted
        when {
            submitted != null && submitted == search.query -> Results(submitted, search.kind) { search.kind = it }
            search.query.isNotBlank() -> Suggestions(search.query, ::submit)
            else -> SearchStart(::submit)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSubmit: (String) -> Unit, modifier: Modifier) {
    Row(
        Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(Raised).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(svg("search"), null, Modifier.size(18.dp), tint = Dim)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(str("search_yt_music"), color = Dim, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value, onChange,
                modifier.fillMaxWidth().onPreviewKeyEvent {
                    val enter = it.key == Key.Enter && it.type == KeyEventType.KeyDown
                    if (enter) onSubmit(value)
                    enter
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
            )
        }
        if (value.isNotEmpty()) IconAction(drawable("close"), null, { onChange("") }, size = 32.dp, iconSize = 18.dp, tint = Dim)
    }
}

/** before anything is typed: recent searches and new albums */
@Composable
private fun SearchStart(onSubmit: (String) -> Unit) {
    val app = LocalApp.current
    var attempt by remember { mutableIntStateOf(0) }
    val releases = rememberLoad("new-releases", attempt) { YouTube.newReleaseAlbums() }
    // what the app's explore page shows: the charts and the moods and genres
    val charts = rememberLoad("charts", attempt) { YouTube.getChartsPage() }
    val moods = rememberLoad("moods", attempt) { YouTube.moodAndGenres() }
    val list = rememberLazyListState()
    ScrollPage(list) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
            val searches = app.library.data.searches
            if (searches.isNotEmpty()) {
                item { SectionHeader(str("desktop_recent_searches")) }
                items(searches) { query ->
                    Row(
                        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSubmit(query) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(drawable("history"), null, Modifier.size(20.dp), tint = Dim)
                        Spacer(Modifier.width(14.dp))
                        Text(query, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        PillButton(str("clear_search_history"), app.library::clearSearches, light = false)
                    }
                }
            }
            (releases as? Load.Ready)?.value?.let { section(str("new_release_albums"), it) }
            (charts as? Load.Ready)?.value?.sections
                ?.filter { it.title != "Top music videos" && it.items.isNotEmpty() }
                ?.forEach { chart -> section(chart.title.ifEmpty { str("charts") }, chart.items) }
            (moods as? Load.Ready)?.value?.forEach { group -> item(key = "moods:${group.title}") { MoodSection(group) } }
        }
    }
}

/** while typing: what YouTube Music guesses you mean */
@Composable
private fun Suggestions(query: String, onSubmit: (String) -> Unit) {
    var found by remember { mutableStateOf<Pair<List<String>, List<YTItem>>?>(null) }
    LaunchedEffect(query) {
        delay(250) // wait for a pause in typing instead of asking on every key
        youtube { YouTube.searchSuggestions(query) }.onSuccess { found = it.queries to it.recommendedItems }
    }
    val list = rememberLazyListState()
    ScrollPage(list) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
            items(found?.first.orEmpty()) { text ->
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSubmit(text) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(svg("search"), null, Modifier.size(16.dp), tint = Dim)
                    Spacer(Modifier.width(14.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(found?.second.orEmpty()) { Box(Modifier.padding(horizontal = 12.dp)) { ItemRow(it) } }
        }
    }
}

@Composable
private fun Results(query: String, kind: SearchKind, onKind: (SearchKind) -> Unit) {
    val app = LocalApp.current
    var attempt by remember(query, kind) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    Column {
        ChoiceChips(SearchKind.entries, kind, { str(it.labelKey) }, onKind)
        if (kind.filter == null) {
            val load = rememberLoad("summary" to query, attempt) { YouTube.searchSummary(query) }
            LoadView(load, { attempt++ }, placeholder = { ListSkeleton() }) { page ->
                ScrollPage(list) {
                    LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
                        if (page.summaries.isEmpty()) item { NoResults() }
                        page.summaries.forEach { section(it.title, it.items) }
                    }
                }
            }
        } else {
            val load = rememberLoad(Triple("search", query, kind), attempt) { YouTube.search(query, kind.filter) }
            LoadView(load, { attempt++ }, placeholder = { ListSkeleton(Modifier.padding(top = 12.dp)) }) { result ->
                // more results come in as the end of the list gets close
                var found by remember(result) { mutableStateOf(result.items.distinctBy { it.id }) }
                var token by remember(result) { mutableStateOf(result.continuation) }
                LoadMoreAtEnd(list, result) {
                    val next = token ?: return@LoadMoreAtEnd false
                    youtube { YouTube.searchContinuation(next) }.onSuccess { page ->
                        val seen = found.mapTo(HashSet()) { it.id }
                        found = found + page.items.filter { seen.add(it.id) }
                        token = page.continuation
                    }
                    true
                }
                ScrollPage(list) {
                    LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = ChromeClearance)) {
                        if (found.isEmpty()) item { NoResults() }
                        val songs = found.filterIsInstance<SongItem>()
                        items(found, key = { it.id }) { item ->
                            if (item is SongItem) {
                                // a song from a song list plays that list, so next and previous follow the results
                                SongRow(item, onClick = { app.player.playList(songs, songs.indexOf(item), query) })
                            } else {
                                ItemRow(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoResults() {
    Text(str("no_results_found"), Modifier.padding(24.dp), color = Dim, style = MaterialTheme.typography.bodyLarge)
}

/** a mood, genre or chart page: its sections of songs, albums and playlists */
@Composable
fun BrowseScreen(route: Route.Browse) {
    var attempt by remember { mutableIntStateOf(0) }
    val load = rememberLoad(route, attempt) { YouTube.browse(route.id, route.params) }
    val list = rememberLazyListState()
    LoadView(load, { attempt++ }) { page ->
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = ChromeClearance)) {
                item { PageTitle(page.title ?: route.title) }
                page.items.filter { it.items.isNotEmpty() }.forEach { group -> section(group.title.orEmpty(), group.items) }
            }
        }
    }
}
