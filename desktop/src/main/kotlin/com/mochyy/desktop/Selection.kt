package com.mochyy.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.music.innertube.models.SongItem
import kotlin.math.roundToInt

/*
 * Picking several songs at once (ui/menu/SelectionSongsMenu.kt) and dragging songs into a new
 * order in your playlists (the app uses a reorderable list for this).
 */

/** the songs picked on the page; Ctrl or Shift held makes a click pick instead of play */
@Stable
class Selection {
    val songs = mutableStateListOf<SongItem>()
    var extending by mutableStateOf(false)

    val active get() = songs.isNotEmpty()

    fun isSelected(id: String) = songs.any { it.id == id }

    fun toggle(song: SongItem) {
        val at = songs.indexOfFirst { it.id == song.id }
        if (at >= 0) songs.removeAt(at) else songs.add(song)
    }

    fun clear() = songs.clear()
}

/** the floating glass bar with what can be done to the picked songs */
@Composable
fun SelectionBar(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val picked = app.selection.songs.toList()
    Row(
        modifier.height(56.dp).chromeGlass().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(drawable("close"), str("cancel"), app.selection::clear, size = 40.dp, iconSize = 18.dp)
        Text(
            str("desktop_selected", picked.size), Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        )
        IconAction(drawable("playlist_add"), str("add_to_playlist"), {
            app.showSheet { AddToPlaylistSheet(picked) }
            app.selection.clear()
        }, size = 40.dp, iconSize = 20.dp)
        IconAction(drawable("queue_music"), str("add_to_queue"), {
            // with nothing playing, the picked songs become the queue, in the order they were picked
            if (app.player.current == null) app.player.playList(picked) else picked.forEach(app.player::addToQueue)
            app.selection.clear()
        }, size = 40.dp, iconSize = 20.dp)
        IconAction(drawable("favorite_border"), str("desktop_like_all"), {
            picked.filterNot { app.library.isLiked(it.id) }.forEach(app.library::toggleLike)
            app.selection.clear()
        }, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(4.dp))
        PillButton(str("play_next"), {
            // in reverse, so they play in the order they were picked; with nothing playing they just play
            if (app.player.current == null) app.player.playList(picked) else picked.asReversed().forEach(app.player::playNext)
            app.selection.clear()
        }, light = true)
    }
}

// ---- dragging rows into a new order ----

/** which row is being dragged in a list, how far, and how tall a row is */
@Stable
class DragReorder {
    var dragging by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    var rowHeight by mutableFloatStateOf(1f)

    /** where the dragged row would land if let go now */
    fun target(count: Int) = if (dragging < 0 || count <= 0) -1 else (dragging + (offset / rowHeight).roundToInt()).coerceIn(0, count - 1)
}

/**
 * For a row of a reorderable list: the dragged row follows the pointer above the others, and
 * the rows it passes step aside on a spring to show where it will land.
 */
@Composable
fun Modifier.reorderRow(state: DragReorder, index: Int, count: Int): Modifier {
    val target = state.target(count)
    val shift = when {
        state.dragging < 0 || index == state.dragging -> 0f
        index in (state.dragging + 1)..target -> -state.rowHeight
        index in target until state.dragging -> state.rowHeight
        else -> 0f
    }
    val stepAside by animateFloatAsState(shift, spring(dampingRatio = 0.8f, stiffness = 500f))
    val lifted = index == state.dragging
    return this
        .onSizeChanged { if (it.height > 0) state.rowHeight = it.height.toFloat() }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer { translationY = if (lifted) state.offset else stepAside }
        .then(if (lifted) Modifier.shadow(16.dp, RoundedCornerShape(14.dp)) else Modifier)
}

/** the grip that starts a drag; [onMove] gets the row's old and new place when let go */
fun Modifier.dragHandle(state: DragReorder, index: Int, count: Int, onMove: (from: Int, to: Int) -> Unit): Modifier =
    pointerInput(index, count) {
        detectDragGestures(
            onDragStart = {
                state.dragging = index
                state.offset = 0f
            },
            onDragEnd = {
                val to = state.target(count)
                state.dragging = -1
                state.offset = 0f
                if (to >= 0 && to != index) onMove(index, to)
            },
            onDragCancel = {
                state.dragging = -1
                state.offset = 0f
            },
        ) { change, amount ->
            change.consume()
            state.offset += amount.y
        }
    }
