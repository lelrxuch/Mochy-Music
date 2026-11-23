package com.mochyy.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.utils.completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** what saving [this] means on YouTube Music: a liked song, an album or playlist in the library, a subscribed artist */
fun YTItem.toChange(on: Boolean): PendingChange? = when (this) {
    is SongItem -> PendingChange(id, PendingChange.SONG, id, on)
    is AlbumItem -> PendingChange(id, PendingChange.COLLECTION, playlistId, on)
    // your own playlists are always in your library, there's nothing to like or unlike
    is PlaylistItem -> if (isEditable) null else PendingChange(id, PendingChange.COLLECTION, id.removePrefix("VL"), on)
    is ArtistItem -> PendingChange(id, PendingChange.ARTIST, channelId ?: id, on)
}

/**
 * Keeps the local library and the account's in step while signed in, what SyncUtils does on Android:
 * a heart or save made here goes up right away; whatever fails is retried before the next pull, so a
 * change made here wins over YouTube's copy. After that YouTube Music's shelves are the library.
 */
class LibrarySync(private val scope: CoroutineScope, private val library: LibraryStore, private val account: AccountStore) {
    var syncing by mutableStateOf(false)
        private set

    init {
        library.onChange = ::push
        if (account.session != null) pull()
    }

    fun push(item: YTItem, on: Boolean) {
        if (account.session == null) return
        val change = item.toChange(on) ?: return
        scope.launch {
            if (!withContext(Dispatchers.IO) { send(change) }) library.setPending(library.data.pending.filterNot { it.id == change.id } + change)
        }
    }

    /** brings the account's library in; with [adopt] (just signed in) what was saved here before goes up too, instead of being dropped */
    fun pull(adopt: Boolean = false) {
        if (account.session == null || syncing) return
        syncing = true
        scope.launch {
            try {
                library.setPending(library.data.pending.filterNot { withContext(Dispatchers.IO) { send(it) } })
                val remote = withContext(Dispatchers.IO) { fetch() }
                val local = library.data
                if (adopt) {
                    val localOnly = listOf(local.liked to remote.liked, local.albums to remote.albums, local.artists to remote.artists, local.playlists to remote.playlists)
                        .flatMap { (mine, theirs) -> if (theirs == null) emptyList() else mine.filter { item -> theirs.none { it.id == item.id } } }
                    val failed = localOnly.mapNotNull { it.toChange(true) }.filterNot { withContext(Dispatchers.IO) { send(it) } }
                    library.setPending(library.data.pending + failed)
                }
                val pending = library.data.pending
                library.replaceSaved(
                    liked = merge(remote.liked, local.liked, pending, adopt),
                    albums = merge(remote.albums, local.albums, pending, adopt),
                    artists = merge(remote.artists, local.artists, pending, adopt),
                    playlists = merge(remote.playlists, local.playlists, pending, adopt),
                )
            } finally {
                syncing = false
            }
        }
    }

    private class Remote(val liked: List<SongItem>?, val albums: List<AlbumItem>?, val artists: List<ArtistItem>?, val playlists: List<PlaylistItem>?)

    // the same shelves the Android app reads; a shelf that fails comes back null and is left alone
    private suspend fun fetch() = Remote(
        liked = YouTube.playlist("LM").completed().getOrNull()?.songs,
        albums = YouTube.library("FEmusic_liked_albums").completed().getOrNull()?.items?.filterIsInstance<AlbumItem>(),
        artists = YouTube.library("FEmusic_library_corpus_artists").completed().getOrNull()?.items?.filterIsInstance<ArtistItem>(),
        playlists = YouTube.library("FEmusic_liked_playlists").completed().getOrNull()?.items?.filterIsInstance<PlaylistItem>()
            ?.filterNot { it.id.removePrefix("VL") in AUTO_PLAYLISTS },
    )

    private suspend fun send(change: PendingChange): Boolean = when (change.kind) {
        PendingChange.SONG -> YouTube.likeVideo(change.target, change.on).isSuccess
        PendingChange.COLLECTION -> YouTube.likePlaylist(change.target, change.on).isSuccess
        else -> YouTube.subscribeChannel(change.target, change.on).isSuccess
    }

    companion object {
        // "Liked music" is the liked songs shelf itself, "SE" is saved podcast episodes
        private val AUTO_PLAYLISTS = setOf("LM", "SE")
    }
}

/** YouTube's shelf, plus what's waiting to go up (or was saved here before signing in, with [adopt]), minus what's waiting to come off */
fun <T : YTItem> merge(remote: List<T>?, local: List<T>, pending: List<PendingChange>, adopt: Boolean): List<T>? {
    if (remote == null) return null
    val remoteIds = remote.mapTo(HashSet()) { it.id }
    val wanted = pending.associate { it.id to it.on }
    val kept = local.filter { it.id !in remoteIds && (adopt || wanted[it.id] == true) }
    return (kept + remote).filter { wanted[it.id] != false }
}
