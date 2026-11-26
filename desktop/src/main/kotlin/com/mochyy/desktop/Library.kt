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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    val reportPlays: Boolean = true,
    val normalize: Boolean = true,
    val downloads: List<DownloadedSong> = emptyList(),
    val audioLab: AudioLabSettings = AudioLabSettings(),
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
 * The library: what the Android app keeps in its database. The screens read [data], a plain value in
 * memory, so they never wait for the disk; every change is handed to one writer thread that puts
 * only what changed into ~/.local/share/mochy-desktop/library.db (see [LibraryDb]).
 * A library.json from before the database is read once, written in, and kept as library.json.migrated.
 */
class LibraryStore(private val file: File = defaultFile()) : AutoCloseable {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "library-writer").apply { isDaemon = true } }
    private val lock = Any()
    private val db: LibraryDb

    var data: LibraryData by mutableStateOf(LibraryData())
        private set

    init {
        val (opened, loaded) = openOrStartOver()
        db = opened
        data = loaded
    }

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

    /** changes the account's playlists shelf right away, before YouTube's copy catches up */
    fun editSavedPlaylists(change: (List<PlaylistItem>) -> List<PlaylistItem>) = update { copy(playlists = change(playlists)) }

    fun setPending(pending: List<PendingChange>) = update { copy(pending = pending.distinctBy { it.id }) }

    fun setVolume(volume: Double) = update { copy(volume = volume) }

    fun setHighQuality(high: Boolean) = update { copy(highQuality = high) }

    fun setReportPlays(report: Boolean) = update { copy(reportPlays = report) }

    fun setNormalize(normalize: Boolean) = update { copy(normalize = normalize) }

    fun setAudioLab(lab: AudioLabSettings) = update { copy(audioLab = lab) }

    fun addDownload(song: DownloadedSong) = update { copy(downloads = listOf(song) + downloads.filterNot { it.song.id == song.song.id }) }

    fun removeDownload(id: String) = update { copy(downloads = downloads.filterNot { it.song.id == id }) }

    private fun update(change: LibraryData.() -> LibraryData) {
        synchronized(lock) {
            val old = data
            val new = old.change()
            data = new
            // queued in the order the changes happened, so the disk always ends where memory is
            runCatching { writer.execute { persist(old, new) } }
                .onFailure { System.err.println("library: a change after closing wasn't saved") }
        }
    }

    private fun persist(old: LibraryData, new: LibraryData) {
        runCatching { db.write(old, new) }.onFailure {
            System.err.println("library: couldn't save a change, the next one rewrites everything: ${it.message}")
            db.markDirty()
        }
    }

    /** waits for the changes still being written and closes the database; call when the app quits */
    @Synchronized
    override fun close() {
        if (writer.isShutdown) return
        writer.shutdown()
        writer.awaitTermination(10, TimeUnit.SECONDS)
        runCatching { db.close() }
    }

    private fun openOrStartOver(): Pair<LibraryDb, LibraryData> {
        val legacy = File(file.parentFile, "library.json")
        val fresh = !file.exists()
        val db = runCatching { LibraryDb.open(file) }.getOrElse { problem ->
            // never overwrite a library we couldn't read: keep it aside so nothing is lost
            System.err.println("library: couldn't open ${file.name}, kept aside: ${problem.message}")
            keepAside(file)
            listOf("-wal", "-shm").forEach { keepAside(File(file.path + it)) }
            LibraryDb.open(file)
        }
        if (fresh && legacy.exists()) {
            val imported = readLegacy(legacy)
            if (imported != null) {
                db.markDirty() // the first write puts the whole library down
                db.write(LibraryData(), imported)
                legacy.renameTo(File(legacy.path + ".migrated"))
            }
        }
        return db to db.load()
    }

    private fun readLegacy(legacy: File): LibraryData? =
        runCatching { json.decodeFromString<LibraryData>(legacy.readText()) }.getOrElse {
            System.err.println("library: couldn't read ${legacy.name}, kept aside: ${it.message}")
            keepAside(legacy)
            null
        }

    private fun keepAside(broken: File) {
        if (broken.exists()) broken.renameTo(File(broken.path + ".broken-${System.currentTimeMillis()}"))
    }

    companion object {
        private const val HISTORY_SIZE = 500
        private const val SEARCHES_SIZE = 20
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun defaultFile(): File {
            val data = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
            return File(data, "mochy-desktop/library.db")
        }
    }
}
