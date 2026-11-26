package com.mochyy.desktop.ui

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

// ---- what a page shows while it loads, and when it couldn't ----

/** a page that loads: its skeleton while waiting, a plain-words error with retry when it fails */
@Composable
fun <T> LoadView(
    load: Load<T>,
    onRetry: () -> Unit,
    placeholder: @Composable () -> Unit = { ListSkeleton(Modifier.padding(top = 56.dp)) },
    content: @Composable (T) -> Unit,
) {
    when (load) {
        is Load.Ready -> content(load.value)
        Load.Loading -> placeholder()
        is Load.Failed -> ErrorView(load.error, onRetry)
    }
}

/** why something failed, in words a person can act on */
enum class Problem { Offline, Slow, YouTube, Other }

fun problemOf(error: Throwable): Problem {
    for (cause in generateSequence(error) { it.cause }.take(8)) {
        when {
            cause is UnknownHostException || cause is ConnectException || cause is NoRouteToHostException || cause is UnresolvedAddressException -> return Problem.Offline
            cause is SocketTimeoutException || cause.javaClass.simpleName.contains("Timeout") -> return Problem.Slow
            cause is ResponseException -> return Problem.YouTube
        }
    }
    return Problem.Other
}

/** the title and the line under it for [error] */
fun describe(error: Throwable): Pair<String, String> = when (problemOf(error)) {
    Problem.Offline -> str("error_no_internet") to str("desktop_error_offline_body")
    Problem.Slow -> str("error_timeout") to str("desktop_error_timeout_body")
    Problem.YouTube -> str("desktop_error_youtube_title") to
        str("desktop_error_youtube_body", generateSequence(error) { it.cause }.filterIsInstance<ResponseException>().first().response.status.value)
    Problem.Other -> str("desktop_cant_load") to str("desktop_error_other_body")
}

@Composable
fun ErrorView(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val app = LocalApp.current
    val (title, body) = describe(error)
    // without a connection it tries again by itself, so coming back online is enough
    if (problemOf(error) == Problem.Offline) LaunchedEffect(error) { delay(8_000); onRetry() }
    Column(modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, Modifier.widthIn(max = 420.dp).padding(top = 6.dp, bottom = 18.dp), color = Dim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(str("retry"), onRetry)
            PillButton(str("desktop_copy_details"), { copyToClipboard(app, error.stackTraceToString().lineSequence().take(25).joinToString("\n")) }, light = false)
        }
    }
}

fun copyToClipboard(app: AppState, text: String, done: String = str("copied")) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        .onSuccess { app.player.message = done }
}

// ---- skeletons: grey shapes where the content will be, with a light passing over (the app's ShimmerHost) ----

/** a light that sweeps across its content's shapes, and fades the bottom out like Android's ShimmerHost */
fun Modifier.shimmer(): Modifier = composed {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1050, delayMillis = 250, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = size.width * 0.35f
        val x = -band + (size.width + band * 2) * sweep
        drawRect(
            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), Offset(x - band, 0f), Offset(x + band, size.height * 0.15f)),
            blendMode = BlendMode.SrcAtop,
        )
        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
    }
}

private val Placeholder = Color.White.copy(alpha = 0.07f)

@Composable
private fun Block(width: Dp, height: Dp, round: Dp = 6.dp) {
    Spacer(Modifier.size(width, height).clip(RoundedCornerShape(round)).background(Placeholder))
}

@Composable
private fun RowSkeleton() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Block(48.dp, 48.dp, 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Block(220.dp, 12.dp)
            Block(130.dp, 10.dp)
        }
    }
}

/** a list of songs or items on its way */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier, rows: Int = 10) {
    Column(modifier.fillMaxSize().shimmer()) { repeat(rows) { RowSkeleton() } }
}

/** an album, playlist or artist page on its way: the cover and titles, then the songs */
@Composable
fun CollectionSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = 56.dp).shimmer()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
            Block(240.dp, 240.dp, 20.dp)
            Spacer(Modifier.width(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Block(70.dp, 12.dp)
                Block(320.dp, 34.dp, 8.dp)
                Block(160.dp, 16.dp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Block(96.dp, 40.dp, 20.dp); Block(110.dp, 40.dp, 20.dp) }
            }
        }
        repeat(8) { RowSkeleton() }
    }
}

/** the home feed on its way: a few shelves of covers */
@Composable
fun ShelvesSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().shimmer()) {
        repeat(3) {
            Box(Modifier.padding(start = 24.dp, top = 28.dp, bottom = 14.dp)) { Block(200.dp, 22.dp) }
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(7) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Block(160.dp, 160.dp, 14.dp)
                        Block(120.dp, 12.dp)
                        Block(80.dp, 10.dp)
                    }
                }
            }
        }
    }
}

// ---- long lists that come in pages ----

/**
 * Asks for the next page when [list] gets within a few rows of its end. [more] fetches and appends
 * it, and answers whether there may be another one after it.
 */
@Composable
fun LoadMoreAtEnd(list: LazyListState, key: Any?, more: suspend () -> Boolean) {
    LaunchedEffect(list, key) {
        var done = false
        snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (!done && total > 0 && last >= total - 5) done = !more() }
    }
}
