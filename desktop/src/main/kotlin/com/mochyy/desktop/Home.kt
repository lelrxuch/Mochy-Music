package com.mochyy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.pages.HomePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home feed, kept for the whole run so switching tabs doesn't reload it. */
class HomeModel(private val scope: CoroutineScope) {
    var chips by mutableStateOf(emptyList<HomePage.Chip>())
        private set
    var chip by mutableStateOf<HomePage.Chip?>(null)
        private set
    var sections by mutableStateOf(emptyList<HomePage.Section>())
        private set
    var state by mutableStateOf<Load<Unit>>(Load.Loading)
        private set
    private var continuation: String? = null
    private var job: Job? = null
    private var moreJob: Job? = null

    init {
        refresh()
    }

    /** a mood chip, or the same one again to go back to the plain feed */
    fun select(picked: HomePage.Chip) {
        chip = if (picked == chip) null else picked
        refresh()
    }

    fun refresh() {
        job?.cancel()
        moreJob?.cancel()
        state = Load.Loading
        job = scope.launch {
            withContext(Dispatchers.IO) { YouTube.home(params = chip?.endpoint?.params) }
                .onSuccess { page ->
                    if (chips.isEmpty()) chips = page.chips.orEmpty()
                    sections = page.sections.filter { it.items.isNotEmpty() }
                    continuation = page.continuation
                    state = Load.Ready(Unit)
                }
                .onFailure { state = Load.Failed(it.message) }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (moreJob?.isActive == true) return
        moreJob = scope.launch {
            withContext(Dispatchers.IO) { YouTube.home(continuation = token) }.onSuccess { page ->
                sections = sections + page.sections.filter { it.items.isNotEmpty() }
                continuation = page.continuation
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val home = app.home
    val list = rememberLazyListState()
    // the next page of the feed comes in as the end gets close
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && last >= total - 3) home.loadMore() }
    }
    LoadView(home.state, home::refresh) {
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = ChromeClearance)) {
                item(key = "hero") { HomeHero(home.sections) }
                if (home.chips.isNotEmpty()) {
                    item(key = "chips") {
                        ChoiceChips(home.chips, home.chip, { it.title }, home::select)
                    }
                }
                home.sections.forEach { section(it.title, it.items, it.label) }
            }
        }
    }
}

/** the big cover at the top: a pick from the feed, "Because you listen to…" style */
@Composable
private fun HomeHero(sections: List<HomePage.Section>) {
    val app = LocalApp.current
    val section = sections.firstOrNull { it.label != null } ?: sections.firstOrNull() ?: return
    val item = section.items.firstOrNull { it.thumbnail != null } ?: return
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        Artwork(item.thumbnail, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, size = 1200)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to Color.Black),
            ),
        )
        Icon(drawable("nav_mochy_filled"), "Mochy", Modifier.padding(24.dp).size(32.dp), tint = Color.White)
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(section.label ?: section.title, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.75f))
            Text(
                item.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(str("desktop_view") + "  →", { app.open(item) })
        }
    }
}
