package com.mochyy.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.min

/** the springs the app's motion uses: quick and settled for presses, a little give for things that move */
val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
val MoveSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)

/**
 * Press3D.kt from the app: while held, the thing sinks a little and tilts toward where it was
 * pressed, then springs back. Watches the pointer without taking the click from what's inside.
 */
fun Modifier.press3D(
    maxTilt: Float = 7f,
    pressedScale: Float = 0.97f,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    var normalized by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: continue
                    val bounds = this.size
                    if (bounds.width > 0 && bounds.height > 0) {
                        normalized = Offset(
                            x = ((down.position.x / bounds.width) * 2f - 1f).coerceIn(-1f, 1f),
                            y = ((down.position.y / bounds.height) * 2f - 1f).coerceIn(-1f, 1f),
                        )
                        size = bounds
                    }
                    scope.launch { press.animateTo(1f, PressSpring) }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                    }
                    scope.launch { press.animateTo(0f, PressSpring) }
                }
            }
        }
        .graphicsLayer {
            val t = press.value
            if (t > 0f) {
                val s = 1f - (1f - pressedScale) * t
                scaleX = s
                scaleY = s
                rotationX = -normalized.y * maxTilt * t
                rotationY = normalized.x * maxTilt * t
                cameraDistance = (size.width.takeIf { it > 0 }?.toFloat() ?: 1000f) * 3f
                transformOrigin = origin
            }
        }
}

// ---- reveal on appear (ui/component/RevealOnAppear.kt) ----

private val RevealEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val RevealFadeInMs = 520
private val RevealSlideDistance = 20.dp
// reveals that start together (a page of rows landing at once) go one after another
private const val BurstWindowMs = 120L
private const val BurstStepMs = 60
private const val BurstMaxSteps = 6

/** which keys already played their reveal on a page, so scrolling back or returning doesn't replay them */
@Stable
class RevealSeenState(initial: Collection<String> = emptyList()) {
    private val seen = HashSet(initial)
    fun hasSeen(key: String) = key in seen
    fun markSeen(key: String) {
        seen.add(key)
    }

    private var burstStartedAt = 0L
    private var burstCount = 0

    internal fun nextBurstDelay(): Int {
        val now = System.nanoTime() / 1_000_000
        if (now - burstStartedAt > BurstWindowMs) {
            burstStartedAt = now
            burstCount = 0
        }
        return min(burstCount++, BurstMaxSteps) * BurstStepMs
    }

    companion object {
        val Saver: Saver<RevealSeenState, ArrayList<String>> = Saver(save = { ArrayList(it.seen) }, restore = { RevealSeenState(it) })
    }
}

@Composable
fun rememberRevealSeenState(): RevealSeenState = rememberSaveable(saver = RevealSeenState.Saver) { RevealSeenState() }

/** the page's reveal record; each page in the shell gets its own */
val LocalReveal = staticCompositionLocalOf<RevealSeenState?> { null }

/** fades and slides this up the first time [key] shows on the page; items landing together take turns */
@Composable
fun Modifier.revealOnAppear(key: String, cascade: Boolean = true): Modifier {
    val seenState = LocalReveal.current ?: return this
    val alreadySeen = remember(key) { seenState.hasSeen(key) }
    if (alreadySeen) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        // marked up front: a fast scroll that drops the row mid-animation shouldn't replay it later
        seenState.markSeen(key)
        val wait = if (cascade) seenState.nextBurstDelay() else 0
        progress.animateTo(1f, tween(RevealFadeInMs, delayMillis = wait, easing = RevealEasing))
    }
    val slidePx = with(LocalDensity.current) { RevealSlideDistance.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * slidePx
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}
