package com.mochyy.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class LibraryData(
    val liked: List<SongItem> = emptyList(),
    val history: List<SongItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val playlists: List<PlaylistItem> = emptyList(),
    val searches: List<String> = emptyList(),
    val volume: Double = 100.0,
    val highQuality: Boolean = true,
    val pending: List<PendingChange> = emptyList(),
    val localPlaylists: List<LocalPlaylist> = emptyList(),
)

/** a playlist kept on this computer only, for when there's no YouTube account (the app's local playlists) */
@Serializable
data class LocalPlaylist(val id: String, val name: String, val songs: List<SongItem> = emptyList())

/** a save made here that YouTube Music hasn't taken yet; [target] is the id its API wants, [id] the item's own */
@Serializable
data class PendingChange(val id: String, val kind: String, val target: String, val on: Boolean) {
    companion object {
        const val SONG = "song"
        const val COLLECTION = "collection"
        const val ARTIST = "artist"
    }
}

/**
 * What the Android app keeps in its database, kept here in one JSON file:
 * ~/.local/share/mochy-desktop/library.json. Small enough to rewrite whole on every change.
 * ponytail: one file rewritten each time, move to SQLite (Room works on desktop) if libraries grow to thousands of songs
 */
class LibraryStore(private val file: File = defaultFile()) {
    var data by mutableStateOf(load())
        private set

    /** told about every heart and save the person taps, with the new state; the sync sends them to YouTube Music */
    var onChange: ((YTItem, Boolean) -> Unit)? = null

    fun isLiked(id: String) = data.liked.any { it.id == id }

    fun toggleLike(song: SongItem) {
        val liked = !isLiked(song.id)
        update { copy(liked = if (liked) listOf(song) + this.liked else this.liked.filterNot { it.id == song.id }) }
        onChange?.invoke(song, liked)
    }

    fun addHistory(song: SongItem) = update {
        copy(history = (listOf(song) + history.filterNot { it.id == song.id }).take(HISTORY_SIZE))
    }

    fun clearHistory() = update { copy(history = emptyList()) }

    fun addSearch(query: String) = update {
        copy(searches = (listOf(query) + searches.filterNot { it.equals(query, ignoreCase = true) }).take(SEARCHES_SIZE))
    }

    fun clearSearches() = update { copy(searches = emptyList()) }

    fun isSaved(item: YTItem) = when (item) {
        is AlbumItem -> data.albums.any { it.id == item.id }
        is ArtistItem -> data.artists.any { it.id == item.id }
        is PlaylistItem -> data.playlists.any { it.id == item.id }
        is SongItem -> isLiked(item.id)
    }

    fun toggleSaved(item: YTItem) {
        val saved = isSaved(item)
        update { savedToggled(item, saved) }
        onChange?.invoke(item, !saved)
    }

    private fun LibraryData.savedToggled(item: YTItem, saved: Boolean) = when (item) {
        is AlbumItem -> copy(albums = if (saved) albums.filterNot { it.id == item.id } else listOf(item) + albums)
        is ArtistItem -> copy(artists = if (saved) artists.filterNot { it.id == item.id } else listOf(item) + artists)
        is PlaylistItem -> copy(playlists = if (saved) playlists.filterNot { it.id == item.id } else listOf(item) + playlists)
        is SongItem -> copy(liked = if (saved) liked.filterNot { it.id == item.id } else listOf(item) + liked)
    }

    /** the shelves as YouTube Music has them; a null shelf didn't load and stays as it was */
    fun replaceSaved(liked: List<SongItem>?, albums: List<AlbumItem>?, artists: List<ArtistItem>?, playlists: List<PlaylistItem>?) = update {
        copy(
            liked = liked ?: this.liked,
            albums = albums ?: this.albums,
            artists = artists ?: this.artists,
            playlists = playlists ?: this.playlists,
        )
    }

    fun localPlaylist(id: String) = data.localPlaylists.firstOrNull { it.id == id }

    fun createPlaylist(name: String, songs: List<SongItem> = emptyList()): LocalPlaylist {
        val playlist = LocalPlaylist("local-" + java.util.UUID.randomUUID(), name.trim(), songs.distinctBy { it.id })
        update { copy(localPlaylists = listOf(playlist) + localPlaylists) }
        return playlist
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { copy(name = name.trim()) }

    fun deletePlaylist(id: String) = update { copy(localPlaylists = localPlaylists.filterNot { it.id == id }) }

    /** adds what isn't in it yet, at the end; the number added */
    fun addToPlaylist(id: String, songs: List<SongItem>): Int {
        val playlist = localPlaylist(id) ?: return 0
        val new = songs.distinctBy { it.id }.filter { song -> playlist.songs.none { it.id == song.id } }
        if (new.isNotEmpty()) editPlaylist(id) { copy(songs = this.songs + new) }
        return new.size
    }

    fun removeFromPlaylist(id: String, index: Int) = editPlaylist(id) {
        copy(songs = songs.filterIndexed { i, _ -> i != index })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = editPlaylist(id) {
        if (from !in songs.indices || to !in songs.indices) this
        else copy(songs = songs.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun editPlaylist(id: String, change: LocalPlaylist.() -> LocalPlaylist) = update {
        copy(localPlaylists = localPlaylists.map { if (it.id == id) it.change() else it })
    }

    fun setPending(pending: List<PendingChange>) = update { copy(pending = pending.distinctBy { it.id }) }

    fun setVolume(volume: Double) = update { copy(volume = volume) }

    fun setHighQuality(high: Boolean) = update { copy(highQuality = high) }

    private fun update(change: LibraryData.() -> LibraryData) {
        data = data.change()
        save()
    }

    private fun load(): LibraryData {
        if (!file.exists()) return LibraryData()
        return runCatching { json.decodeFromString<LibraryData>(file.readText()) }.getOrElse {
            // never overwrite a library we couldn't read: keep it aside so nothing is lost
            file.renameTo(File(file.path + ".broken-${System.currentTimeMillis()}"))
            LibraryData()
        }
    }

    // written to a temp file first and then swapped in, so a crash mid-write never leaves half a library
    private fun save() {
        file.parentFile.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(LibraryData.serializer(), data))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val HISTORY_SIZE = 500
        private const val SEARCHES_SIZE = 20
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun defaultFile(): File {
            val data = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
            return File(data, "mochy-desktop/library.json")
        }
    }
}
