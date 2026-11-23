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
)

/**
 * What the Android app keeps in its database, kept here in one JSON file:
 * ~/.local/share/mochy-desktop/library.json. Small enough to rewrite whole on every change.
 * ponytail: one file rewritten each time, move to SQLite (Room works on desktop) if libraries grow to thousands of songs
 */
class LibraryStore(private val file: File = defaultFile()) {
    var data by mutableStateOf(load())
        private set

    fun isLiked(id: String) = data.liked.any { it.id == id }

    fun toggleLike(song: SongItem) = update {
        copy(liked = if (isLiked(song.id)) liked.filterNot { it.id == song.id } else listOf(song) + liked)
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

    fun toggleSaved(item: YTItem) = update {
        val saved = isSaved(item)
        when (item) {
            is AlbumItem -> copy(albums = if (saved) albums.filterNot { it.id == item.id } else listOf(item) + albums)
            is ArtistItem -> copy(artists = if (saved) artists.filterNot { it.id == item.id } else listOf(item) + artists)
            is PlaylistItem -> copy(playlists = if (saved) playlists.filterNot { it.id == item.id } else listOf(item) + playlists)
            is SongItem -> copy(liked = if (saved) liked.filterNot { it.id == item.id } else listOf(item) + liked)
        }
    }

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
