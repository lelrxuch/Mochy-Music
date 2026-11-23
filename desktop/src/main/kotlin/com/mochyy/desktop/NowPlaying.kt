package com.mochyy.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class Panel { Lyrics, Queue }

@Composable
fun NowPlaying(onClose: () -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val song = player.current ?: return
    var panel by remember { mutableStateOf<Panel?>(Panel.Lyrics) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // clicks on the player stay on the player instead of reaching the page under it
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // the cover, blurred and dimmed, as the whole background
        Artwork(song.thumbnail, Modifier.fillMaxSize().blur(90.dp).alpha(0.6f), RectangleShape, size = 600)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black.copy(alpha = 0.85f))))

        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(drawable("expand_more"), str("desktop_close_player"), onClose, background = Color.White.copy(alpha = 0.1f))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(str("now_playing"), style = MaterialTheme.typography.labelMedium, color = Dim)
                    player.queueTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(40.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > 960.dp
                Row(Modifier.fillMaxSize()) {
                    // on a narrow window the lyrics or queue take the cover's place
                    if (wide || panel == null) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            CoverAndControls(panel, onPanel = { panel = if (panel == it) null else it })
                        }
                    }
                    panel?.let { shown ->
                        Box(Modifier.weight(1f).fillMaxHeight().padding(start = if (wide) 32.dp else 0.dp)) {
                            when (shown) {
                                Panel.Lyrics -> LyricsPanel()
                                Panel.Queue -> QueuePanel()
                            }
                            if (!wide) {
                                Box(Modifier.align(Alignment.BottomCenter)) { PanelToggles(panel) { panel = if (panel == it) null else it } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverAndControls(panel: Panel?, onPanel: (Panel) -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val song = player.current ?: return
    val mpv = player.mpv
    val position = mpv?.position?.collectAsState()?.value ?: 0.0
    val duration = mpv?.duration?.collectAsState()?.value ?: 0.0
    val paused = mpv?.paused?.collectAsState()?.value ?: true
    val volume = mpv?.volume?.collectAsState()?.value ?: 100.0
    val codec = mpv?.codec?.collectAsState()?.value
    val rate = mpv?.sampleRate?.collectAsState()?.value
    var scrub by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val back = remember { SkipCycleState() }
    val forward = remember { SkipCycleState() }

    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val cover = min(min(maxWidth * 0.85f, maxHeight - 340.dp), 520.dp).coerceAtLeast(160.dp)
        Column(Modifier.width(cover)) {
            Artwork(song.thumbnail, Modifier.size(cover).shadow(24.dp, RoundedCornerShape(24.dp)), RoundedCornerShape(24.dp), size = 1200)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val artist = song.artists.firstOrNull { it.id != null }
                    Text(
                        song.artistNames(),
                        Modifier.clip(RoundedCornerShape(6.dp)).let { m ->
                            if (artist != null) m.clickable { app.nav.open(Route.Artist(artist.id!!)) } else m
                        },
                        color = Dim, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                formatBadge(codec, rate)?.let { Badge(it); Spacer(Modifier.width(10.dp)) }
                val liked = app.library.isLiked(song.id)
                IconAction(
                    drawable(if (liked) "ic_heart" else "ic_heart_outline"), str("like"), { app.library.toggleLike(song) },
                    background = Color.White.copy(alpha = 0.12f), iconSize = 20.dp,
                )
            }
            Spacer(Modifier.height(16.dp))
            val shown = scrub ?: if (duration > 0) (position / duration).toFloat() else 0f
            ThinBar(shown, onChange = { scrub = it }, onDone = { player.seek(it * duration); scrub = null }, Modifier.fillMaxWidth())
            Row {
                Text(formatTime(scrub?.let { it * duration } ?: position), style = MaterialTheme.typography.labelMedium, color = Dim)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = Dim)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton({ scope.launch { back.step() }; player.previous() }, 72.dp) {
                    AnimatedSkipGlyph(back, backwards = true, color = Color.White, width = 44.dp)
                }
                RoundButton(player::togglePause, 84.dp) {
                    AnimatedPlayGlyph(playing = !paused, color = Color.White, height = 40.dp)
                }
                RoundButton({ scope.launch { forward.step() }; player.next() }, 72.dp) {
                    AnimatedSkipGlyph(forward, backwards = false, color = Color.White, width = 44.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            var dragVolume by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(drawable("volume_down"), null, { player.setVolume(volume - 10, save = true) }, size = 32.dp, iconSize = 18.dp, tint = Dim)
                ThinBar(
                    dragVolume ?: (volume / 100).toFloat(),
                    onChange = { dragVolume = it; player.setVolume(it * 100.0, save = false) },
                    onDone = { player.setVolume(it * 100.0, save = true); dragVolume = null },
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    height = 4.dp,
                )
                IconAction(drawable("volume_up"), null, { player.setVolume(volume + 10, save = true) }, size = 32.dp, iconSize = 18.dp, tint = Dim)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(drawable(if (player.shuffled) "shuffle_on" else "shuffle"), str("shuffle"), player::toggleShuffle, tint = if (player.shuffled) Color.White else Dim)
                val repeatIcon = when (player.repeat) {
                    Repeat.OFF -> "repeat"
                    Repeat.ALL -> "repeat_on"
                    Repeat.ONE -> "repeat_one_on"
                }
                IconAction(drawable(repeatIcon), null, player::cycleRepeat, tint = if (player.repeat == Repeat.OFF) Dim else Color.White)
                Spacer(Modifier.weight(1f))
                PanelToggles(panel, onPanel)
            }
        }
    }
}

/** the lyrics and queue buttons in the player's corner, lit while their panel shows */
@Composable
private fun PanelToggles(panel: Panel?, onPanel: (Panel) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 4.dp)) {
        listOf(Panel.Lyrics to "lyrics", Panel.Queue to "queue_music").forEach { (kind, icon) ->
            IconAction(drawable(icon), str(if (kind == Panel.Lyrics) "lyrics" else "queue"), { onPanel(kind) }, tint = if (panel == kind) Color.White else Dim)
        }
    }
}

@Composable
private fun LyricsPanel() {
    val player = LocalApp.current.player
    when (val lyrics = player.lyrics) {
        Lyrics.Loading -> Notice(str("desktop_loading_lyrics"))
        Lyrics.Missing -> Notice(str("lyrics_not_found"))
        is Lyrics.Plain -> Text(
            lyrics.text,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White.copy(alpha = 0.85f),
        )
        is Lyrics.Synced -> SyncedLyrics(lyrics.lines)
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>) {
    val player = LocalApp.current.player
    val position = player.mpv?.position?.collectAsState()?.value ?: 0.0
    // a hair early, so the line lights as it's sung rather than just after
    val current = lines.indexAt((position * 1000).toLong() + 200)
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem(current) }
        LazyColumn(
            state = list,
            // the sung line sits a third of the way down, with room to scroll past the last one
            contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight * 2 / 3),
        ) {
            itemsIndexed(lines) { i, line ->
                val color by animateColorAsState(if (i == current) Color.White else Color.White.copy(alpha = 0.3f))
                Text(
                    line.text.ifBlank { "♪" },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.seek(line.timeMs / 1000.0) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = color,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QueuePanel() {
    val player = LocalApp.current.player
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (player.index > 0) list.scrollToItem(player.index) }
    Column(Modifier.fillMaxSize()) {
        Text(str("desktop_up_next"), Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
        if (player.queue.isEmpty()) Notice(str("desktop_queue_empty"))
        ScrollPage(list) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 72.dp)) {
                itemsIndexed(player.queue, key = { i, song -> "$i-${song.id}" }) { i, song ->
                    SongRow(
                        song, onClick = { player.jump(i) },
                        trailing = {
                            if (i != player.index) {
                                IconAction(drawable("close"), str("remove_from_queue"), { player.removeAt(i) }, size = 32.dp, iconSize = 16.dp, tint = Dim)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Dim, style = MaterialTheme.typography.titleMedium)
    }
}
