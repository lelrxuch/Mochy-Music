package com.mochyy.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import java.net.URI

/*
 * The flagship player's backdrop (ui/player/flagship/AmbientBackdrop.kt and AmbientColors.kt):
 * the cover cut into a 4x4 grid of colours, each a soft pool where it sits on the cover, the whole
 * picture slowly turning and flowing. Same shader as the app's AGSL one, which Skia runs as SkSL.
 */

private const val GridSide = 4
private const val FadeMs = 1_400
private val Start = Color(0xFF2B2A2D)

/** the backdrop's colours, easing from one cover's to the next */
@Stable
class AmbientColors {
    var grid by mutableStateOf(List(GridSide * GridSide) { Start })
        private set

    internal suspend fun easeTo(next: List<Color>) {
        val from = grid
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(FadeMs, easing = FastOutSlowInEasing)) {
            grid = List(next.size) { lerp(from[it], next[it], value) }
        }
    }
}

private val coverCache = HashMap<String, List<Color>>()

/** the cover's grid of colours, read once per cover off the UI thread */
private suspend fun coverGrid(url: String): List<Color>? {
    coverCache[url]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(URI(url).toURL().readBytes()))
            val w = bitmap.width
            val h = bitmap.height
            List(GridSide * GridSide) { cell ->
                val x0 = (cell % GridSide) * w / GridSide
                val y0 = (cell / GridSide) * h / GridSide
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                var y = y0
                while (y < y0 + h / GridSide) {
                    var x = x0
                    while (x < x0 + w / GridSide) {
                        val c = bitmap.getColor(x, y)
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        x += 2
                    }
                    y += 2
                }
                if (n == 0) Start else Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
        }.getOrNull()
    }?.also { coverCache[url] = it }
}

@Composable
fun rememberAmbientColors(cover: String?): AmbientColors {
    val colors = remember { AmbientColors() }
    LaunchedEffect(cover) {
        val grid = coverGrid(cover?.artwork(120) ?: return@LaunchedEffect) ?: return@LaunchedEffect
        colors.easeTo(grid)
    }
    return colors
}

// the app's shader, word for word: the grid fills the screen, turns and breathes about its middle,
// and a slow liquid warp lets the colours flow past each other; looped every 100 seconds
private const val AMBIENT = """
uniform float2 resolution;
uniform float time;
uniform float4 grid[16];

const float TAU = 6.2831853;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float t = time * TAU / 100.0;
    float2 c = uv - 0.5;
    c.x *= resolution.x / max(resolution.y, 1.0);
    float a = 0.32 * sin(t) + 0.12 * sin(t * 3.0 + 1.1);
    float s = 1.0 + 0.07 * sin(t * 2.0 + 0.6);
    c = float2(c.x * cos(a) - c.y * sin(a), c.x * sin(a) + c.y * cos(a)) / s;
    c += 0.06 * float2(sin(c.y * 4.5 + t * 2.0), cos(c.x * 4.0 - t * 3.0));
    c += 0.035 * float2(sin((c.x + c.y) * 7.0 - t * 4.0), cos((c.x - c.y) * 6.0 + t * 5.0));
    float2 g = (c + 0.5) * 4.0 - 0.5;
    float3 col = float3(0.0);
    float sum = 0.0001;
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float row = floor(fi / 4.0);
        float2 d = g - float2(fi - row * 4.0, row);
        float w = exp(-dot(d, d) * 1.25);
        col += grid[i].rgb * w;
        sum += w;
    }
    col /= sum;
    col *= 1.0 - 0.16 * smoothstep(0.6, 1.0, uv.y);
    float grain = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);
    col += (grain - 0.5) / 255.0;
    return half4(half3(col), 1.0);
}
"""

private val ambientEffect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(AMBIENT) }.getOrNull() }

/** the drifting colours of [colors]; a flat deep tone where the shader can't run */
@Composable
fun AmbientBackdrop(colors: AmbientColors, modifier: Modifier = Modifier) {
    // 30 steps a second: the drift is slow, so it looks as smooth as more for a quarter of the redraws
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (isActive) {
            time = ((withFrameMillis { it } - start) % 100_000L) / 1000f
            delay(33)
        }
    }
    val values = FloatArray(GridSide * GridSide * 4)
    Spacer(
        modifier.drawWithCache {
            val effect = ambientEffect
            onDrawBehind {
                if (effect == null) {
                    drawRect(colors.grid[GridSide + 1])
                    return@onDrawBehind
                }
                colors.grid.forEachIndexed { i, c ->
                    values[i * 4] = c.red
                    values[i * 4 + 1] = c.green
                    values[i * 4 + 2] = c.blue
                    values[i * 4 + 3] = 1f
                }
                val builder = RuntimeShaderBuilder(effect)
                builder.uniform("resolution", size.width, size.height)
                builder.uniform("time", time)
                builder.uniform("grid", values)
                val paint = Paint().apply { shader = builder.makeShader() }
                drawIntoCanvas { it.nativeCanvas.drawRect(Rect.makeWH(size.width, size.height), paint) }
            }
        },
    )
}
