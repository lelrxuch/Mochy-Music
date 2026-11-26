package com.mochyy.desktop.ui.screens

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.innertube.YouTube
import com.music.innertube.models.YTItem
import com.music.innertube.pages.MoodAndGenres
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// the moods and genres of the search page as Android draws them (ui/screens/search/SearchDesign.kt MoodTile):
// a tile tinted with the mood's colour, with two tilted covers taken from that mood's own page

private val TileBase = Color(0xFF121214)
private val CoverBase = Color(0xFF1C1C1F)

/** the first two covers of each mood's page, kept while the app runs so the grid doesn't ask twice */
internal object MoodCovers {
    val covers = mutableStateMapOf<String, List<String>>()
    private val asked = HashSet<String>()
    private val gate = Semaphore(3) // a page of moods is ~60 pages to read: a few at a time

    fun key(item: MoodAndGenres.Item) = item.endpoint.browseId + "|" + item.endpoint.params

    fun request(app: AppState, item: MoodAndGenres.Item) {
        val key = key(item)
        if (!asked.add(key)) return
        app.scope.launch {
            gate.withPermit {
                youtube { YouTube.browse(item.endpoint.browseId, item.endpoint.params) }
                    .onSuccess { page ->
                        val urls = page.items.asSequence().flatMap { it.items.asSequence() }.mapNotNull(YTItem::thumbnail).distinct().take(2).toList()
                        if (urls.isNotEmpty()) covers[key] = urls
                    }
                    .onFailure { asked.remove(key) } // try again next time the grid shows
            }
        }
    }
}

/** one group of moods or genres: its title and its tiles, as many per row as the window fits */
@Composable
fun MoodSection(group: MoodAndGenres) {
    val app = LocalApp.current
    Column {
        SectionHeader(group.title)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            val columns = (maxWidth / 210.dp).toInt().coerceIn(2, 5)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.items.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { mood ->
                            LaunchedEffect(MoodCovers.key(mood)) { MoodCovers.request(app, mood) }
                            MoodTile(
                                mood.title, mood.stripeColor, MoodCovers.covers[MoodCovers.key(mood)].orEmpty(),
                                onClick = { app.nav.open(Route.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) },
                                modifier = Modifier.weight(1f).revealOnAppear("mood:${MoodCovers.key(mood)}"),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodTile(title: String, stripeColor: Long, covers: List<String>, onClick: () -> Unit, modifier: Modifier) {
    val brush = remember(stripeColor) {
        val tint = moodTint(stripeColor)
        Brush.linearGradient(listOf(blendOver(tint, 0.55f, TileBase), blendOver(tint, 0.16f, TileBase)))
    }
    var titleWraps by remember(title) { mutableStateOf(false) }
    Box(modifier.press3D().height(76.dp).clip(RoundedCornerShape(14.dp)).background(brush).clickable(onClick = onClick)) {
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).size(84.dp, 76.dp)
                // a two-line title runs under the covers, so they fade out towards it
                .graphicsLayer { compositingStrategy = if (titleWraps) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawWithContent {
                    drawContent()
                    if (titleWraps) {
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 0.42f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Black),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) {
            TiltedCover(covers.getOrNull(1), rotation = -24f, x = (-2).dp, y = 2.dp, size = 44.dp)
            TiltedCover(covers.getOrNull(0), rotation = -6f, x = 28.dp, y = 13.dp, size = 48.dp)
        }
        Text(
            title,
            Modifier.align(Alignment.CenterStart).padding(start = 14.dp, end = 70.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { val wraps = it.lineCount > 1; if (wraps != titleWraps) titleWraps = wraps },
        )
    }
}

@Composable
private fun BoxScope.TiltedCover(url: String?, rotation: Float, x: Dp, y: Dp, size: Dp) {
    Box(
        Modifier.align(Alignment.CenterStart).size(size)
            .graphicsLayer {
                rotationZ = rotation
                translationX = x.toPx()
                translationY = y.toPx()
                shape = RoundedCornerShape(8.dp)
                clip = true
            }
            .background(CoverBase),
    ) {
        if (url != null) Artwork(url, Modifier.size(size), RoundedCornerShape(0.dp), size = 128)
    }
}

/** the mood's colour lifted towards a mid grey, so dark stripes still read as a colour on the dark tile */
private fun moodTint(stripeColor: Long): Color {
    val argb = stripeColor.toInt()
    val r = argb shr 16 and 0xFF
    val g = argb shr 8 and 0xFF
    val b = argb and 0xFF
    if (r == 0 && g == 0 && b == 0) return Color(0xFF3A3A44)
    fun lift(c: Int) = (c + (170 - c) * 0.45f).toInt().coerceIn(40, 220)
    return Color(lift(r), lift(g), lift(b))
}

private fun blendOver(top: Color, alpha: Float, bottom: Color) = Color(
    red = top.red * alpha + bottom.red * (1 - alpha),
    green = top.green * alpha + bottom.green * (1 - alpha),
    blue = top.blue * alpha + bottom.blue * (1 - alpha),
)
