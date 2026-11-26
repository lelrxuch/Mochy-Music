package com.mochyy.desktop.ui

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.screens.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

// ---- the frosted chrome of the nav bar and mini player (ui/component/FrostedGlass.kt, GlassKit.kt) ----

val ChromeHighlightColor = Color.White.copy(alpha = 0.1f)

/** the floating bars' glass (Glass.kt), clipped so what's inside stays in the shape */
fun Modifier.chromeGlass(shape: Shape = CircleShape) = this.liquidGlass(shape).clip(shape)

/** the bar and the mini player are this tall (NavBarHeight in AppNavigation.kt) */
val ChromeHeight = 64.dp

// ---- nav bar (ui/component/AppNavigation.kt) ----

enum class Tab(val titleKey: String, val unlit: @Composable () -> Painter, val lit: @Composable () -> Painter, val iconSize: Dp) {
    Home("home", { svg("home") }, { svg("home_filled") }, 22.dp),
    Search("search", { svg("search") }, { svg("search_filled") }, 22.dp),
    Library("desktop_library", { svg("lib") }, { svg("lib_fill") }, 24.dp),
    Settings("settings", { drawable("nav_mochy_outline") }, { drawable("nav_mochy_filled") }, 24.dp),
}

@Composable
fun NavBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    // where the lit pill is, in tabs: it slides between them on a spring instead of jumping
    val position by animateFloatAsState(Tab.entries.indexOf(selected).toFloat(), spring(dampingRatio = 0.72f, stiffness = 380f))
    Box(modifier.height(ChromeHeight).chromeGlass().padding(horizontal = 6.dp)) {
        Spacer(
            Modifier.fillMaxSize().padding(vertical = 8.dp).drawBehind {
                val slot = size.width / Tab.entries.size
                val inset = 2.dp.toPx()
                // halfway between two tabs the pill stretches, like a drop being pulled, then settles
                val travel = 1f - abs((position - floor(position)) * 2f - 1f)
                val width = (slot - 2 * inset) * (1f + 0.3f * travel)
                val left = slot * position + slot / 2 - width / 2
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.1f), Offset(left, 0f), Size(width, size.height), radius)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f))),
                    Offset(left, 0f), Size(width, size.height), radius, style = Stroke(1.dp.toPx()),
                )
            },
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { Tab.entries.forEachIndexed { i, tab ->
            val lit = (1f - abs(position - i)).coerceIn(0f, 1f)
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp, horizontal = 2.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(tab) }
                    .press3D(maxTilt = 0f, pressedScale = 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                // the outline fades out as the filled icon fades in, like the app's tabs
                Icon(tab.unlit(), str(tab.titleKey), Modifier.size(tab.iconSize).alpha(0.7f * (1f - lit)), tint = Color.White)
                Icon(tab.lit(), null, Modifier.size(tab.iconSize).alpha(lit), tint = Color.White)
            }
        } }
    }
}

// ---- mini player (MiniSongInfo / MiniPlayerButton in ui/player/MorphingCover.kt) ----

@Composable
fun MiniPlayer(player: Player, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val song = player.current ?: return
    val mpv = player.mpv
    val paused = mpv?.paused?.collectAsState()?.value ?: true
    val scope = rememberCoroutineScope()
    val skip = remember { SkipCycleState() }
    val app = LocalApp.current
    ContextMenuArea(items = { songActions(app, song).map { (label, action) -> ContextMenuItem(label, action) } }) { Row(
        modifier.height(ChromeHeight).chromeGlass().clickable(onClick = onOpen).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnail, Modifier.sharedPlayerElement("cover").size(48.dp), RoundedCornerShape(12.dp), size = 120)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(song.artistNames(), song.album?.name).filter { it.isNotBlank() }.joinToString(" — ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(onClick = player::togglePause, size = 44.dp) {
            AnimatedPlayGlyph(playing = !paused, color = Color.White, height = 19.dp)
        }
        RoundButton(onClick = { scope.launch { skip.step() }; player.next() }, size = 44.dp) {
            AnimatedSkipGlyph(skip, backwards = false, color = Color.White, width = 19.dp)
        }
    } }
}

/** a round button with the nav bar's highlight under the pointer */
@Composable
fun RoundButton(onClick: () -> Unit, size: Dp, modifier: Modifier = Modifier, background: Color = Color.Transparent, content: @Composable () -> Unit) {
    Box(
        modifier.press3D(maxTilt = 0f, pressedScale = 0.88f).size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** an icon in a round button */
@Composable
fun IconAction(icon: Painter, label: String?, onClick: () -> Unit, size: Dp = 40.dp, iconSize: Dp = 22.dp, tint: Color = Color.White, background: Color = Color.Transparent) {
    RoundButton(onClick, size, background = background) { Icon(icon, label, Modifier.size(iconSize), tint = tint) }
}

/** the white or dark stadium buttons of the home hero and the album and artist pages */
@Composable
fun PillButton(text: String, onClick: () -> Unit, icon: Painter? = null, light: Boolean = true, modifier: Modifier = Modifier) {
    val content = if (light) Color.Black else Color.White
    Row(
        modifier.press3D(maxTilt = 4f, pressedScale = 0.95f).height(40.dp).clip(CircleShape)
            .background(if (light) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = content)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

// ---- seek and volume bars: the player's thin rounded track ----

/** a thin bar from 0 to 1 that follows a drag or a click; [onChange] while moving, [onDone] when let go */
@Composable
fun ThinBar(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    // the gestures live as long as the bar, so they read the newest callbacks through these
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val width = remember { floatArrayOf(1f) }
    val last = remember { floatArrayOf(value) }
    fun at(x: Float) = (x / width[0]).coerceIn(0f, 1f)
    Box(
        modifier.height(24.dp)
            .onSizeChanged { width[0] = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { last[0] = at(it.x); done(last[0]) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { last[0] = at(it.x); change(last[0]) },
                    onDragEnd = { done(last[0]) },
                    onDragCancel = { done(last[0]) },
                ) { pointer, _ -> last[0] = at(pointer.position.x); change(last[0]) }
            }
            .drawBehind {
                val h = height.toPx()
                val top = (size.height - h) / 2f
                val radius = CornerRadius(h / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, h), radius)
                drawRoundRect(Color.White, Offset(0f, top), Size(size.width * value.coerceIn(0f, 1f), h), radius)
            },
    )
}

// ---- the flagship player's play, pause and skip glyphs (ui/player/flagship/TransportIcons.kt) ----

// how small play and pause get before they swap and spring back
private const val SquashLow = 0.5f

private class TransportShapes(
    val play: Path,
    val playSize: Size,
    val skip: Path,
    val skipSize: Size,
    val skipStep: Float,
    val pairWidth: Float,
    val playShift: Float,
)

private val transportShapes by lazy {
    val play = vector("transport_play")
    val skip = vector("transport_skip")
    TransportShapes(
        play = play.toFlatPath(),
        playSize = Size(play.viewportWidth, play.viewportHeight),
        skip = skip.toFlatPath(),
        skipSize = Size(skip.viewportWidth, skip.viewportHeight),
        skipStep = 61f,
        pairWidth = 157.32f,
        playShift = 12.88f,
    )
}

// every path of the vector in one, with its groups' moves applied
private fun ImageVector.toFlatPath(): Path {
    val out = Path()
    fun walk(group: VectorGroup, dx: Float, dy: Float) {
        for (i in 0 until group.size) {
            when (val node = group[i]) {
                is VectorGroup -> walk(node, dx + node.translationX, dy + node.translationY)
                is VectorPath -> out.addPath(node.pathData.toPath(), Offset(dx, dy))
            }
        }
    }
    walk(root, 0f, 0f)
    return out
}

// play and pause swapping: the glyph squeezes small, swaps while it's tiny, then bounces back past full size
@Stable
private class PlayGlyphState(playing: Boolean) {
    val squash = Animatable(1f)
    var showsPause by mutableStateOf(playing)
        private set

    suspend fun swapTo(playing: Boolean) {
        if (playing == showsPause && squash.value == 1f) return
        squash.animateTo(SquashLow, tween(durationMillis = 110, easing = FastOutLinearInEasing))
        showsPause = playing
        squash.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = 420f))
    }
}

private fun DrawScope.drawPlayGlyph(shapes: TransportShapes, glyph: PlayGlyphState, unit: Float, color: Color) {
    val s = glyph.squash.value
    val cx = size.width / 2f
    val cy = size.height / 2f
    withTransform({ scale(s, s, Offset(cx, cy)) }) {
        if (glyph.showsPause) {
            // two thick bars with fully round ends and a narrow gap
            val playHeight = shapes.playSize.height * unit
            val barW = playHeight * 0.29f
            val barH = playHeight * 0.86f
            val gap = playHeight * 0.145f
            val radius = CornerRadius(barW / 2f)
            drawRoundRect(color, Offset(cx - gap / 2f - barW, cy - barH / 2f), Size(barW, barH), radius)
            drawRoundRect(color, Offset(cx + gap / 2f, cy - barH / 2f), Size(barW, barH), radius)
        } else {
            val w = shapes.playSize.width * unit
            val h = shapes.playSize.height * unit
            translate(cx + shapes.playShift * unit - w / 2f, cy - h / 2f) {
                scale(unit, unit, Offset.Zero) { drawPath(shapes.play, color) }
            }
        }
    }
}

/** a skip button's cycle: each press moves it one step on, a spring carrying it so quick presses run together */
@Stable
class SkipCycleState {
    val position = Animatable(0f)
    var target by mutableIntStateOf(0)
        private set

    suspend fun step() {
        target += 1
        position.animateTo(target.toFloat(), spring(dampingRatio = 0.6f, stiffness = 320f))
    }

    fun phase(): Float {
        val f = position.value - (target - 1)
        return if (f >= 0f) f else position.value - kotlin.math.floor(position.value)
    }
}

// the two triangles at phase f: the front one runs off and shrinks away, the back one takes its place, a new one grows in behind
private fun DrawScope.drawSkipGlyph(shapes: TransportShapes, phase: Float, unit: Float, color: Color, backwards: Boolean) {
    val w = shapes.skipSize.width * unit
    val h = shapes.skipSize.height * unit
    val step = shapes.skipStep * unit
    val left = size.width / 2f - shapes.pairWidth * unit / 2f
    val top = size.height / 2f - h / 2f
    val f = phase.coerceAtLeast(0f)
    val gone = f.coerceAtMost(1f)
    withTransform({ if (backwards) scale(-1f, 1f, Offset(size.width / 2f, size.height / 2f)) }) {
        skipTriangle(shapes, left, top, w, h, unit, scale = f, alpha = (f * 2.5f).coerceIn(0f, 1f), color = color)
        skipTriangle(shapes, left + step * f, top, w, h, unit, scale = 1f, alpha = 1f, color = color)
        if (gone < 1f) {
            skipTriangle(shapes, left + step + step * 0.8f * gone, top, w, h, unit, scale = 1f - gone, alpha = 1f - gone, color = color)
        }
    }
}

private fun DrawScope.skipTriangle(
    shapes: TransportShapes, x: Float, y: Float, w: Float, h: Float, unit: Float, scale: Float, alpha: Float, color: Color,
) {
    if (scale <= 0.001f || alpha <= 0.001f) return
    withTransform({
        scale(scale, scale, Offset(x + w / 2f, y + h / 2f))
        translate(x, y)
        scale(unit, unit, Offset.Zero)
    }) {
        drawPath(shapes.skip, color, alpha = alpha)
    }
}

/** play or pause, [height] tall, squashing as it swaps */
@Composable
fun AnimatedPlayGlyph(playing: Boolean, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val glyph = remember { PlayGlyphState(playing) }
    LaunchedEffect(playing) { glyph.swapTo(playing) }
    Spacer(
        modifier.size(height).drawBehind {
            drawPlayGlyph(transportShapes, glyph, size.height / transportShapes.playSize.height, color)
        },
    )
}

/** the two skip triangles, [width] across, running a step each time [cycle] steps */
@Composable
fun AnimatedSkipGlyph(cycle: SkipCycleState, backwards: Boolean, color: Color, width: Dp, modifier: Modifier = Modifier) {
    val shapes = transportShapes
    Spacer(
        modifier.size(width, width * (shapes.skipSize.height / shapes.pairWidth)).drawBehind {
            drawSkipGlyph(shapes, cycle.phase(), size.width / shapes.pairWidth, color, backwards)
        },
    )
}

/** a little outlined label like the player's "OPUS · 48 kHz" */
@Composable
fun Badge(text: String) {
    Text(
        text,
        Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.8f),
    )
}
