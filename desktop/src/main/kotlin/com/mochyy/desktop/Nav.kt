package com.mochyy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A page past the four tabs. */
sealed interface Route {
    data class Artist(val id: String) : Route
    data class Album(val id: String) : Route
    data class Playlist(val id: String) : Route
}

/** The tab on show and the pages opened on top of it, newest last. */
class Nav {
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()
    var playerOpen by mutableStateOf(false)

    val top: Route? get() = stack.lastOrNull()

    fun select(tab: Tab) {
        this.tab = tab
        stack.clear()
        playerOpen = false
    }

    fun open(route: Route) {
        stack.add(route)
        playerOpen = false
    }

    /** closes the player first, then the newest page; false when there was nothing to go back from */
    fun back(): Boolean = when {
        playerOpen -> { playerOpen = false; true }
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        else -> false
    }
}

/** Everything a screen needs, handed down once instead of through every function. */
class AppState(
    val nav: Nav,
    val player: Player,
    val library: LibraryStore,
    val home: HomeModel,
    val search: SearchModel,
    val account: AccountStore = AccountStore(),
) {
    /** what a click on any item does: songs play, the rest open their page */
    fun open(item: YTItem) {
        when (item) {
            is SongItem -> player.playSong(item)
            is AlbumItem -> nav.open(Route.Album(item.browseId))
            is ArtistItem -> nav.open(Route.Artist(item.id))
            is PlaylistItem -> nav.open(Route.Playlist(item.id))
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppState> { error("no app state") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val reason: String?) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** pages already fetched, so going back to one shows it at once instead of loading it again */
private val pages = HashMap<Any, Any?>()

/**
 * Fetches [key]'s page once and keeps it. Bump [attempt] to fetch again after a failure.
 * ponytail: the cache never forgets during a run, add a size limit if memory ever matters
 */
@Composable
fun <T> rememberLoad(key: Any, attempt: Int = 0, fetch: suspend () -> Result<T>): Load<T> {
    @Suppress("UNCHECKED_CAST")
    val cached = pages[key]?.let { Load.Ready(it as T) }
    return produceState(cached ?: Load.Loading, key, attempt) {
        if (cached != null && attempt == 0) return@produceState
        value = Load.Loading
        value = withContext(Dispatchers.IO) { fetch() }.fold(
            onSuccess = { pages[key] = it; Load.Ready(it) },
            onFailure = { Load.Failed(it.message) },
        )
    }.value
}
