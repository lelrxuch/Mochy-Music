package com.mochyy.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.ceil

/*
 * Liquid glass for the floating chrome: the nav bar, the mini player, notices, the back button.
 *
 * Android's GlassKit records the app into a RenderNode and blurs it under the bars. Here the page
 * area is recorded into a GraphicsLayer (glassSource) and each piece of glass (liquidGlass) draws
 * its own patch of that recording through a Skia shader: blurred, bent at the rim like the edge of
 * a thick lens, split a touch into colours right at the edge, and lit along the top. Over that go
 * the Figma's #131313 (thinner than Android's 78%, so the glass reads as glass), a faint grain so a
 * dark blur doesn't band, and a hairline that's brighter on top.
 */

/** the page area as the glass sees it */
@Stable
class GlassBackdrop {
    internal var layer: GraphicsLayer? = null
    internal var origin by mutableStateOf(Offset.Zero)

    /** bumped each time the page draws, so the glass over it redraws too */
    internal var frame by mutableIntStateOf(0)
}

val LocalGlass = staticCompositionLocalOf<GlassBackdrop?> { null }

/** records what this draws so the glass above it can show it */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = composed {
    val layer = rememberGraphicsLayer()
    backdrop.layer = layer
    this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            backdrop.frame++
        }
}

private val GlassTint = Color(0xFF131313)

/**
 * A piece of liquid glass in [shape]. [tint] is how much #131313 covers the bent, blurred page;
 * [depth] is how far in from the rim the lens bends; [blur] how soft the page gets.
 * Without a [glassSource] above it (or where the shader can't run) it falls back to a flat tint.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Float = 0.22f,
    depth: Dp = 16.dp,
    blur: Dp = 7.dp,
    elevation: Dp = 12.dp,
): Modifier = composed {
    val backdrop = LocalGlass.current
    var position by remember { mutableStateOf(Offset.Zero) }
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val corner = cornerRadius(outline, size)
            val blurPx = blur.toPx()
            // the patch reaches past the glass, so the blur and the bent rim have real page to read there
            val margin = ceil(blurPx * 1.5f + depth.toPx())
            val patch = obtainGraphicsLayer()
            val effect = glassEffect(size, margin, corner, depth.toPx(), blurPx)
            val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.5f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.03f))
            val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.05f), 0.45f to Color.Transparent)
            val rimStroke = Stroke(1.dp.toPx())
            onDrawBehind {
                val source = backdrop?.layer
                if (backdrop != null && source != null && effect != null) {
                    // read so that a page redraw redraws the glass
                    backdrop.frame
                    patch.renderEffect = effect
                    patch.record(IntSize((size.width + 2 * margin).toInt(), (size.height + 2 * margin).toInt())) {
                        translate(backdrop.origin.x - position.x + margin, backdrop.origin.y - position.y + margin) {
                            drawLayer(source)
                        }
                    }
                    clipPath(path) {
                        translate(-margin, -margin) { drawLayer(patch) }
                    }
                    drawPath(path, GlassTint.copy(alpha = tint))
                } else {
                    drawPath(path, GlassTint.copy(alpha = 0.92f))
                }
                clipPath(path) { drawRect(GrainBrush, alpha = 0.04f) }
                drawPath(path, sheen)
                drawPath(path, rim, style = rimStroke)
            }
        }
}

private fun cornerRadius(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    is Outline.Generic -> minOf(size.width, size.height) / 2
}

/**
 * The lens, in SkSL. Coordinates are the patch's: the glass sits [margin] in from its corner.
 * The rounded rectangle's distance field gives, for each pixel, how near the rim it is and which
 * way the rim faces; near the rim the pixel is read from further in (the bend), with red read a
 * little further and blue a little nearer (the colour split), and the top edge catches light.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float margin;
uniform float radius;
uniform float depth;

half4 main(float2 coord) {
    float2 p = coord - float2(margin);
    float2 hs = size * 0.5;
    float2 q = p - hs;
    float r = min(radius, min(hs.x, hs.y));
    float2 k = abs(q) - (hs - float2(r));
    float d = length(max(k, float2(0.0))) + min(max(k.x, k.y), 0.0) - r;
    float2 s = float2(q.x < 0.0 ? -1.0 : 1.0, q.y < 0.0 ? -1.0 : 1.0);
    float2 n;
    if (k.x > 0.0 && k.y > 0.0) {
        n = normalize(k) * s;
    } else if (k.x > k.y) {
        n = float2(s.x, 0.0);
    } else {
        n = float2(0.0, s.y);
    }
    // 0 deep inside, 1 on the rim; the bend grows fast toward the rim, like the curved edge of a lens
    float edge = clamp(1.0 + d / depth, 0.0, 1.0);
    float2 bend = -n * edge * edge * edge * depth * 2.2;
    half4 c = content.eval(coord + bend);
    c.r = content.eval(coord + bend * 1.18).r;
    c.b = content.eval(coord + bend * 0.82).b;
    // glass livens colour a little rather than greying it
    half l = dot(c.rgb, half3(0.299, 0.587, 0.114));
    c.rgb = mix(half3(l), c.rgb, 1.25);
    // light from above catches the top rim, a weaker bounce lights the bottom one
    float rim = pow(edge, 6.0);
    float lit = rim * (0.55 * clamp(dot(n, normalize(float2(-0.35, -1.0))), 0.0, 1.0)
                     + 0.22 * clamp(dot(n, normalize(float2(0.35, 1.0))), 0.0, 1.0));
    c.rgb = c.rgb + half3(lit);
    c.a = 1.0;
    return c;
}
"""

private val lensEffect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(LENS) }.getOrNull() }

private fun glassEffect(size: Size, margin: Float, corner: Float, depth: Float, blur: Float): RenderEffect? {
    val effect = lensEffect ?: return null
    if (size.width <= 0f || size.height <= 0f) return null
    return runCatching {
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("size", size.width, size.height)
        builder.uniform("margin", margin)
        builder.uniform("radius", corner)
        builder.uniform("depth", depth.coerceAtLeast(1f))
        val soft = ImageFilter.makeBlur(blur / 2, blur / 2, FilterTileMode.CLAMP, null, null)
        ImageFilter.makeRuntimeShader(builder, "content", soft).asComposeRenderEffect()
    }.getOrNull()
}

// a small tile of grain, half lighter and half darker pixels (FrostedGlass.kt's FrostGrainBrush)
private val GrainBrush: ShaderBrush by lazy {
    val tile = 64
    val random = java.util.Random(0x6A55)
    val pixels = ByteArray(tile * tile * 4)
    for (i in 0 until tile * tile) {
        val light = random.nextBoolean()
        val alpha = random.nextInt(256)
        // premultiplied BGRA
        val value = if (light) alpha else 0
        pixels[i * 4] = value.toByte()
        pixels[i * 4 + 1] = value.toByte()
        pixels[i * 4 + 2] = value.toByte()
        pixels[i * 4 + 3] = alpha.toByte()
    }
    val image: ImageBitmap = Image.makeRaster(ImageInfo.makeN32Premul(tile, tile), pixels, tile * 4).toComposeImageBitmap()
    ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}
