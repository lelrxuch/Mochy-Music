package com.mochyy.desktop.ui.screens

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
        // the cover's colours drifting under everything (the flagship's AmbientBackdrop), darkened toward the bottom for the controls
        AmbientBackdrop(rememberAmbientColors(song.thumbnail), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.6f))))

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
            Artwork(song.thumbnail, Modifier.sharedPlayerElement("cover").size(cover).shadow(24.dp, RoundedCornerShape(24.dp)), RoundedCornerShape(24.dp), size = 1200)
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
                LikeButton(app.library.isLiked(song.id)) { app.library.toggleLike(song) }
                Spacer(Modifier.width(8.dp))
                // everything a song row's menu offers, here too
                MoreButton(songActions(app, song))
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

/** the heart: it springs up when liked and throws a ring of sparks (a compact LikeBurst.kt) */
@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var before by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !before) {
            launch {
                pop.snapTo(0.6f)
                pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
            }
            launch {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            }
        }
        before = liked
    }
    Box(
        Modifier.drawBehind {
            val p = burst.value
            if (p >= 1f) return@drawBehind
            val reach = size.minDimension / 2 * (0.7f + 0.9f * p)
            repeat(8) { k ->
                val angle = k / 8f * 2f * PI.toFloat() - PI.toFloat() / 2f
                drawCircle(
                    Color.White.copy(alpha = 1f - p), radius = 3.dp.toPx() * (1f - p * 0.5f),
                    center = center + Offset(cos(angle) * reach, sin(angle) * reach),
                )
            }
            drawCircle(Color.White.copy(alpha = 0.35f * (1f - p)), radius = reach, style = Stroke(2.dp.toPx() * (1f - p)))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            IconAction(
                drawable(if (liked) "ic_heart" else "ic_heart_outline"), str("like"), onClick,
                background = Color.White.copy(alpha = 0.12f), iconSize = 20.dp,
            )
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
        // the sleep timer, lit while one is set
        val app = LocalApp.current
        val sleeping = app.player.sleepAt != null || app.player.sleepAtSongEnd
        IconAction(drawable("bedtime"), str("sleep_timer"), { app.showSheet { SleepTimerSheet() } }, tint = if (sleeping) Color.White else Dim)
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
    val stagger = remember(lines) { LyricsStagger(lines.size + 1) }
    LaunchedEffect(stagger) { stagger.run() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(current) {
            if (current < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
            // far off screen (a seek): just go there
            if (item == null) return@LaunchedEffect list.animateScrollToItem(current)
            stagger.anchorRow = current
            list.scroll {
                var previous = 0f
                animate(0f, item.offset.toFloat(), animationSpec = tween(620, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { value, _ ->
                    val consumed = scrollBy(value - previous)
                    previous += consumed
                    // rows below the sung line hold back by what just scrolled, then catch up on their own
                    if (consumed != 0f) {
                        for (info in list.layoutInfo.visibleItemsInfo) if (info.index > current) stagger.holdBack(info.index, consumed)
                    }
                }
            }
        }
        LazyColumn(
            state = list,
            // the sung line sits a third of the way down, with room to scroll past the last one
            contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight * 2 / 3),
        ) {
            itemsIndexed(lines) { i, line ->
                val distance = if (current < 0) 6 else minOf(abs(i - current), 6)
                val lit by animateFloatAsState(if (i == current) 1f else 0f, tween(500, easing = FastOutSlowInEasing))
                // the sung line and its neighbours stay sharp, the rest soften a little
                val soft by animateFloatAsState(((distance - 1).coerceAtLeast(0) / 5f), tween(400))
                Text(
                    line.text.ifBlank { "♪" },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.seek(line.timeMs / 1000.0) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .graphicsLayer {
                            // trails the scroll for a moment (the stagger), and the sung line stands a size up
                            translationY = stagger.lagOf(i)
                            val scale = 0.92f + 0.08f * lit
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .blur((1.4f * soft).dp),
                    color = Color.White.copy(alpha = 0.32f + 0.68f * lit),
                    style = MaterialTheme.typography.bodyLarge,
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
                    val removable = i != player.index
                    SongRow(
                        song, onClick = { player.jump(i) },
                        extra = if (removable) listOf(str("remove_from_queue") to { player.removeAt(i) }) else emptyList(),
                        trailing = if (!removable) null else {
                            { IconAction(drawable("close"), str("remove_from_queue"), { player.removeAt(i) }, size = 32.dp, iconSize = 16.dp, tint = Dim) }
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

/** the sleep timer's choices (the app's sleep timer sheet, its quick presets) */
@Composable
private fun SleepTimerSheet() {
    val app = LocalApp.current
    val player = app.player
    Sheet(str("sleep_timer")) {
        val at = player.sleepAt
        if (player.sleepAtSongEnd || at != null) {
            val text = if (player.sleepAtSongEnd) str("sleep_timer_until_song_ends")
            else str("desktop_sleep_until", DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(at!!).atZone(ZoneId.systemDefault())))
            Text(text, color = Dim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45).forEach { minutes ->
                PillButton(str("desktop_minutes", minutes), { player.sleepIn(minutes); app.closeSheet() }, light = false)
            }
            PillButton(str("sleep_timer_preset_hour"), { player.sleepIn(60); app.closeSheet() }, light = false)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(str("end_of_song"), { player.sleepAfterThisSong(); app.closeSheet() }, light = false)
            if (player.sleepAtSongEnd || at != null) PillButton(str("cancel"), { player.cancelSleep(); app.closeSheet() })
        }
    }
}
