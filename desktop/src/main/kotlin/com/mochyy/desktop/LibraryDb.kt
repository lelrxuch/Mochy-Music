package com.mochyy.desktop

import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The library on disk: ~/.local/share/mochy-desktop/library.db, a SQLite database.
 *
 * Every list of the library (liked songs, history, saved albums, each playlist of this computer...)
 * is a "shelf": one row per item, in the order of a sort key. A change writes only the rows that
 * changed, so liking a song in a library of 10 000 is one row, not the whole library again.
 *
 * Each item is kept as its JSON: YouTube's models gain and lose fields often, and a JSON column reads
 * old rows with new code without a migration for every field. What needs its own column (the id,
 * the order) has one.
 *
 * Not thread-safe: [LibraryStore] calls it from its one writer thread.
 */
internal class LibraryDb private constructor(private val connection: Connection) : AutoCloseable {

    /** the sort keys of each shelf as they are on disk, in the shelf's order */
    private val keys = HashMap<String, LongArray>()

    /** set when a write failed: the next one rewrites everything from memory instead of patching */
    private var dirty = false

    /** reads the whole library, and remembers where each row sits for the writes that follow */
    fun load(): LibraryData {
        keys.clear()
        val settings = HashMap<String, String>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT key, value FROM settings").use { while (it.next()) settings[it.getString(1)] = it.getString(2) }
        }
        val defaults = LibraryData()
        val headers = read(LOCAL_PLAYLISTS, PlaylistHeader.serializer())
        return LibraryData(
            liked = read(LIKED, SongItem.serializer()),
            history = read(HISTORY, SongItem.serializer()),
            albums = read(ALBUMS, AlbumItem.serializer()),
            artists = read(ARTISTS, ArtistItem.serializer()),
            playlists = read(PLAYLISTS, PlaylistItem.serializer()),
            searches = read(SEARCHES, String.serializer()),
            pending = read(PENDING, PendingChange.serializer()),
            downloads = read(DOWNLOADS, DownloadedSong.serializer()),
            localPlaylists = headers.map { LocalPlaylist(it.id, it.name, read(songsOf(it.id), SongItem.serializer())) },
            volume = settings["volume"]?.toDoubleOrNull() ?: defaults.volume,
            highQuality = settings["highQuality"]?.toBooleanStrictOrNull() ?: defaults.highQuality,
            reportPlays = settings["reportPlays"]?.toBooleanStrictOrNull() ?: defaults.reportPlays,
            normalize = settings["normalize"]?.toBooleanStrictOrNull() ?: defaults.normalize,
            audioLab = settings["audioLab"]?.let { runCatching { json.decodeFromString(AudioLabSettings.serializer(), it) }.getOrNull() }
                ?: defaults.audioLab,
        )
    }

    private fun <T> read(shelf: String, serializer: KSerializer<T>): List<T> {
        val items = ArrayList<T>()
        val found = ArrayList<Long>()
        connection.prepareStatement("SELECT sort, json FROM items WHERE shelf = ? ORDER BY sort").use { q ->
            q.setString(1, shelf)
            q.executeQuery().use { rows ->
                while (rows.next()) {
                    // a row this version can't read is left out; the next write rewrites the shelf without it
                    val item = runCatching { json.decodeFromString(serializer, rows.getString(2)) }
                        .onFailure { System.err.println("library: skipped an unreadable row of $shelf: ${it.message}"); dirty = true }
                        .getOrNull() ?: continue
                    items += item
                    found += rows.getLong(1)
                }
            }
        }
        keys[shelf] = found.toLongArray()
        return items
    }

    /** writes what changed from [old] to [new], all of it or none of it */
    fun write(old: LibraryData, new: LibraryData) {
        if (old === new) return
        val full = dirty
        transaction {
            if (full) connection.createStatement().use { it.executeUpdate("DELETE FROM items"); it.executeUpdate("DELETE FROM settings") }
            val before = if (full) LibraryData(localPlaylists = emptyList()) else old
            if (full) keys.clear()
            shelf(LIKED, before.liked, new.liked, SongItem.serializer(), full) { it.id }
            shelf(HISTORY, before.history, new.history, SongItem.serializer(), full) { it.id }
            shelf(ALBUMS, before.albums, new.albums, AlbumItem.serializer(), full) { it.id }
            shelf(ARTISTS, before.artists, new.artists, ArtistItem.serializer(), full) { it.id }
            shelf(PLAYLISTS, before.playlists, new.playlists, PlaylistItem.serializer(), full) { it.id }
            shelf(SEARCHES, before.searches, new.searches, String.serializer(), full) { it }
            shelf(PENDING, before.pending, new.pending, PendingChange.serializer(), full) { it.id }
            shelf(DOWNLOADS, before.downloads, new.downloads, DownloadedSong.serializer(), full) { it.song.id }
            localPlaylists(before.localPlaylists, new.localPlaylists, full)
            setting("volume", before.volume, new.volume, full) { it.toString() }
            setting("highQuality", before.highQuality, new.highQuality, full) { it.toString() }
            setting("reportPlays", before.reportPlays, new.reportPlays, full) { it.toString() }
            setting("normalize", before.normalize, new.normalize, full) { it.toString() }
            setting("audioLab", before.audioLab, new.audioLab, full) { json.encodeToString(AudioLabSettings.serializer(), it) }
        }
        dirty = false
    }

    /** the next write puts everything down again (after a failed write, the rows on disk can't be trusted to match) */
    fun markDirty() {
        dirty = true
    }

    private fun localPlaylists(old: List<LocalPlaylist>, new: List<LocalPlaylist>, full: Boolean) {
        if (old === new && !full) return
        shelf(LOCAL_PLAYLISTS, old.map { PlaylistHeader(it.id, it.name) }, new.map { PlaylistHeader(it.id, it.name) }, PlaylistHeader.serializer(), full) { it.id }
        val before = old.associateBy { it.id }
        new.forEach { playlist -> shelf(songsOf(playlist.id), before[playlist.id]?.songs.orEmpty(), playlist.songs, SongItem.serializer(), full) { it.id } }
        val kept = new.mapTo(HashSet()) { it.id }
        old.filter { it.id !in kept }.forEach { gone ->
            connection.prepareStatement("DELETE FROM items WHERE shelf = ?").use { it.setString(1, songsOf(gone.id)); it.executeUpdate() }
            keys.remove(songsOf(gone.id))
        }
    }

    private fun <T> setting(key: String, old: T, new: T, full: Boolean, text: (T) -> String) {
        if (!full && old == new) return
        connection.prepareStatement("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use {
            it.setString(1, key)
            it.setString(2, text(new))
            it.executeUpdate()
        }
    }

    private fun <T> shelf(name: String, old: List<T>, new: List<T>, serializer: KSerializer<T>, full: Boolean, id: (T) -> String) {
        if (old === new && !full) return
        val before = if (full) LongArray(0) else keys[name] ?: LongArray(0)
        val plan = if (full || before.size != old.size) null else planShelf(old, before, new)
        if (plan == null) {
            // no room between two rows, or nothing to patch from: the shelf is written again, evenly spaced
            connection.prepareStatement("DELETE FROM items WHERE shelf = ?").use { it.setString(1, name); it.executeUpdate() }
            val fresh = LongArray(new.size) { it * SPACING }
            insert(name, new.indices, fresh, new, serializer, id)
            keys[name] = fresh
            return
        }
        if (plan.removed.isNotEmpty()) {
            connection.prepareStatement("DELETE FROM items WHERE shelf = ? AND sort = ?").use { delete ->
                plan.removed.forEach { key -> delete.setString(1, name); delete.setLong(2, key); delete.addBatch() }
                delete.executeBatch()
            }
        }
        insert(name, plan.added, plan.keys, new, serializer, id)
        keys[name] = plan.keys
    }

    private fun <T> insert(name: String, which: Iterable<Int>, sort: LongArray, items: List<T>, serializer: KSerializer<T>, id: (T) -> String) {
        connection.prepareStatement("INSERT INTO items(shelf, sort, id, json) VALUES(?, ?, ?, ?)").use { insert ->
            which.forEach { i ->
                insert.setString(1, name)
                insert.setLong(2, sort[i])
                insert.setString(3, id(items[i]))
                insert.setString(4, json.encodeToString(serializer, items[i]))
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private inline fun transaction(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    @Serializable
    private data class PlaylistHeader(val id: String, val name: String)

    companion object {
        private const val SCHEMA = 1

        // shelves
        private const val LIKED = "liked"
        private const val HISTORY = "history"
        private const val ALBUMS = "albums"
        private const val ARTISTS = "artists"
        private const val PLAYLISTS = "playlists"
        private const val SEARCHES = "searches"
        private const val PENDING = "pending"
        private const val DOWNLOADS = "downloads"
        private const val LOCAL_PLAYLISTS = "local_playlists"
        private fun songsOf(playlist: String) = "local:$playlist"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Opens (or makes) the database at [file]. Throws if the file isn't a database we can read;
         * [LibraryStore] then keeps it aside and starts over.
         */
        fun open(file: File): LibraryDb {
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
            try {
                connection.createStatement().use { s ->
                    // WAL: a write doesn't wait for readers and survives a crash half-way
                    s.execute("PRAGMA journal_mode = WAL")
                    s.execute("PRAGMA synchronous = NORMAL")
                    val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                    if (version > SCHEMA) error("library.db is from a newer Mochy (schema $version)")
                    if (version < 1) {
                        s.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS items(shelf TEXT NOT NULL, sort INTEGER NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, " +
                                "PRIMARY KEY(shelf, sort)) WITHOUT ROWID",
                        )
                        s.executeUpdate("CREATE INDEX IF NOT EXISTS items_by_id ON items(shelf, id)")
                        s.executeUpdate("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    }
                    // later schemas: `if (version < 2) { ... }` here, in order
                    s.execute("PRAGMA user_version = $SCHEMA")
                }
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
            return LibraryDb(connection)
        }
    }
}

/** sort keys leave this much room between neighbours, so most inserts fit between two rows */
internal const val SPACING = 1L shl 20

/** which rows a shelf loses ([removed], by sort key), which items of the new list are written ([added]) and every item's key */
internal class ShelfPlan(val removed: List<Long>, val added: List<Int>, val keys: LongArray)

/**
 * Plans the fewest writes that turn [old] (on disk with sort keys [oldKeys]) into [new]: the items
 * that stay in the same relative order keep their rows (the longest run of them, found as a longest
 * increasing subsequence), everything else is deleted or inserted. Null when an insert finds no free
 * key between two neighbours; the caller then rewrites the shelf.
 */
internal fun <T> planShelf(old: List<T>, oldKeys: LongArray, new: List<T>): ShelfPlan? {
    // each new item takes the first unused equal item of the old list
    val positions = HashMap<T, ArrayDeque<Int>>(old.size * 2)
    old.forEachIndexed { i, item -> positions.getOrPut(item) { ArrayDeque() }.addLast(i) }
    val match = IntArray(new.size) { positions[new[it]]?.removeFirstOrNull() ?: -1 }

    // longest increasing subsequence of the matched old positions: those rows stay
    val tails = IntArray(new.size) // tails[k]: index in new of the last item of the best run of length k+1
    val previous = IntArray(new.size) { -1 }
    var length = 0
    for (i in new.indices) {
        val at = match[i]
        if (at < 0) continue
        var lo = 0
        var hi = length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (match[tails[mid]] < at) lo = mid + 1 else hi = mid
        }
        if (lo > 0) previous[i] = tails[lo - 1]
        tails[lo] = i
        if (lo == length) length++
    }
    val stays = BooleanArray(new.size)
    var walk = if (length > 0) tails[length - 1] else -1
    while (walk >= 0) {
        stays[walk] = true
        walk = previous[walk]
    }

    val keptOld = BooleanArray(old.size)
    val keys = LongArray(new.size)
    for (i in new.indices) if (stays[i]) {
        keys[i] = oldKeys[match[i]]
        keptOld[match[i]] = true
    }
    val removed = old.indices.filter { !keptOld[it] }.map { oldKeys[it] }

    // new keys for every run of inserted items, between the rows that stay around it
    val added = ArrayList<Int>()
    var i = 0
    while (i < new.size) {
        if (stays[i]) { i++; continue }
        val start = i
        while (i < new.size && !stays[i]) i++
        val count = i - start
        val low = if (start > 0) keys[start - 1] else null
        val high = if (i < new.size) keys[i] else null
        for (j in 0 until count) {
            keys[start + j] = when {
                low == null && high == null -> j * SPACING
                low == null -> high!! - (count - j) * SPACING
                high == null -> low + (j + 1) * SPACING
                else -> {
                    if (high - low <= count) return null
                    low + (high - low) / (count + 1) * (j + 1)
                }
            }
            added += start + j
        }
    }
    return ShelfPlan(removed, added, keys)
}
