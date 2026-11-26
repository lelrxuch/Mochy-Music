package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The library on SQLite: what goes in comes back out, in order, after closing and opening again. */
class LibraryDbCheck {
    private fun song(n: Int) = SongItem(
        id = "song$n", title = "Song $n", artists = listOf(Artist("Artist ${n % 7}", "UC${n % 7}")),
        thumbnail = "https://i.ytimg.com/vi/$n/hqdefault.jpg", duration = 120 + n,
    )
    private fun album(n: Int) = AlbumItem(browseId = "MPRE$n", playlistId = "OLAK$n", title = "Album $n", artists = null, thumbnail = "t$n")
    private fun artist(n: Int) = ArtistItem(id = "UC$n", title = "Artist $n", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
    private fun playlist(n: Int) = PlaylistItem(
        id = "VLPL$n", title = "Playlist $n", author = null, songCountText = null, thumbnail = null,
        playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
    )

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mochy-library").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun reopen(store: LibraryStore, file: File): LibraryStore {
        store.close()
        return LibraryStore(file)
    }

    @Test
    fun aLibraryJsonFromBeforeIsMovedInWhole() = withDir { dir ->
        val before = LibraryData(
            liked = (1..5).map(::song), history = (6..9).map(::song), albums = listOf(album(1), album(2)),
            artists = listOf(artist(3)), playlists = listOf(playlist(4)), searches = listOf("daft punk", "justice"),
            volume = 37.5, highQuality = false, pending = listOf(PendingChange("song1", PendingChange.SONG, "song1", true)),
            localPlaylists = listOf(LocalPlaylist("local-a", "Road trip", (1..3).map(::song)), LocalPlaylist("local-b", "Empty")),
            reportPlays = false, normalize = false, downloads = listOf(DownloadedSong(song(2), -4.5)),
            audioLab = AudioLabSettings(eqEnabled = true, presetId = "rock", reverb = Reverb.Hall, speed = 1.25),
        )
        File(dir, "library.json").writeText(Json { encodeDefaults = true }.encodeToString(LibraryData.serializer(), before))
        val file = File(dir, "library.db")
        var store = LibraryStore(file)
        assertEquals(before, store.data)
        assertTrue(File(dir, "library.json.migrated").exists(), "the old file is kept, renamed")
        assertTrue(!File(dir, "library.json").exists())
        store = reopen(store, file)
        assertEquals(before, store.data, "read back from the database, not the json")
        store.close()
    }

    @Test
    fun everyChangeSurvivesClosingTheApp() = withDir { dir ->
        // a long random session of everything the app does to its library, closed and reopened every so often
        val file = File(dir, "library.db")
        var store = LibraryStore(file)
        val random = Random(20261125)
        val songs = (1..40).map(::song)
        repeat(3000) { step ->
            when (random.nextInt(16)) {
                0, 1 -> store.toggleLike(songs.random(random))
                2, 3 -> store.addHistory(songs.random(random))
                4 -> store.toggleSaved(listOf(album(random.nextInt(8)), artist(random.nextInt(8)), playlist(random.nextInt(8))).random(random))
                // what a sync pull does: whole shelves replaced, reordered, sometimes with a repeated song
                5 -> store.replaceSaved(
                    liked = songs.shuffled(random).take(random.nextInt(30)).let { if (it.isNotEmpty() && random.nextBoolean()) it + it.first() else it },
                    albums = (0..7).map(::album).shuffled(random).take(random.nextInt(8)),
                    artists = null,
                    playlists = (0..7).map(::playlist).shuffled(random).take(random.nextInt(8)),
                )
                6 -> store.createPlaylist("List $step", songs.shuffled(random).take(random.nextInt(6)))
                7 -> store.data.localPlaylists.randomOrNull(random)?.let { store.addToPlaylist(it.id, songs.shuffled(random).take(3)) }
                8 -> store.data.localPlaylists.randomOrNull(random)?.let { p ->
                    if (p.songs.isNotEmpty()) store.moveInPlaylist(p.id, random.nextInt(p.songs.size), random.nextInt(p.songs.size))
                }
                9 -> store.data.localPlaylists.randomOrNull(random)?.let { p -> if (p.songs.isNotEmpty()) store.removeFromPlaylist(p.id, random.nextInt(p.songs.size)) }
                10 -> store.data.localPlaylists.randomOrNull(random)?.let { p ->
                    if (random.nextInt(4) == 0) store.deletePlaylist(p.id) else store.renamePlaylist(p.id, "Renamed $step")
                }
                11 -> store.addSearch(listOf("a", "b", "c", "daft punk", "Daft Punk", "justice").random(random))
                12 -> if (random.nextBoolean()) store.addDownload(DownloadedSong(songs.random(random), random.nextDouble(-8.0, 2.0)))
                else store.data.downloads.randomOrNull(random)?.let { store.removeDownload(it.song.id) }
                13 -> store.setPending(songs.shuffled(random).take(random.nextInt(5)).map { PendingChange(it.id, PendingChange.SONG, it.id, random.nextBoolean()) })
                14 -> when (random.nextInt(4)) {
                    0 -> store.setVolume(random.nextDouble(0.0, 130.0))
                    1 -> store.setNormalize(random.nextBoolean())
                    2 -> store.setAudioLab(AudioLabSettings(eqEnabled = random.nextBoolean(), gains = List(EqFrequencies.size) { random.nextDouble(-12.0, 12.0) }))
                    else -> store.setHighQuality(random.nextBoolean())
                }
                else -> store.editSavedPlaylists { it.reversed() }
            }
            if (step % 250 == 249) {
                val expected = store.data
                store = reopen(store, file)
                assertEquals(expected, store.data, "after step $step")
            }
        }
        store.close()
    }

    @Test
    fun historyKeepsTheLast500InOrder() = withDir { dir ->
        val file = File(dir, "library.db")
        var store = LibraryStore(file)
        (1..600).forEach { store.addHistory(song(it)) }
        store.addHistory(song(550)) // played again: back to the top, not twice
        val expected = store.data.history
        assertEquals(500, expected.size)
        assertEquals("song550", expected.first().id)
        store = reopen(store, file)
        assertEquals(expected, store.data.history)
        store.close()
    }

    @Test
    fun aLikeIsOneRowEvenInABigLibrary() {
        val liked = (1..10_000).map(::song)
        val keys = LongArray(liked.size) { it * SPACING }
        val like = planShelf(liked, keys, listOf(song(0)) + liked)!!
        assertEquals(listOf(0), like.added)
        assertTrue(like.removed.isEmpty())
        assertTrue(like.keys[0] < like.keys[1])
        val unlike = planShelf(liked, keys, liked.filterNot { it.id == "song5000" })!!
        assertEquals(listOf(4999 * SPACING), unlike.removed)
        assertTrue(unlike.added.isEmpty())
        // played again: moved to the top, the rest keep their rows
        val again = planShelf(liked, keys, listOf(song(300)) + liked.filterNot { it.id == "song300" })!!
        assertEquals(1, again.removed.size)
        assertEquals(listOf(0), again.added)
    }

    @Test
    fun noRoomBetweenTwoRowsAsksForARewrite() {
        assertNull(planShelf(listOf("a", "b"), longArrayOf(0, 1), listOf("a", "x", "b")))
        val roomy = assertNotNull(planShelf(listOf("a", "b"), longArrayOf(0, 10), listOf("a", "x", "y", "b")))
        assertTrue(roomy.keys.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun bigLibraryStaysFast() = withDir { dir ->
        val file = File(dir, "library.db")
        var store = LibraryStore(file)
        store.replaceSaved(liked = (1..10_000).map(::song), albums = null, artists = null, playlists = null)
        store = reopen(store, file)
        val took = measureTimeMillis {
            (10_001..10_200).forEach { store.toggleLike(song(it)) }
            store = reopen(store, file)
        }
        println("200 likes in a 10 000 song library, written and read back: $took ms")
        assertEquals(10_200, store.data.liked.size)
        assertEquals("song10200", store.data.liked.first().id)
        assertTrue(took < 20_000, "took $took ms")
        store.close()
    }

    @Test
    fun aBrokenDatabaseIsKeptAside() = withDir { dir ->
        val file = File(dir, "library.db")
        file.writeText("this is not a database")
        val store = LibraryStore(file)
        assertEquals(LibraryData(), store.data)
        store.toggleLike(song(1))
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("library.db.broken-") })
        val reopened = reopen(store, file)
        assertEquals(listOf("song1"), reopened.data.liked.map { it.id })
        reopened.close()
    }

    @Test
    fun aBrokenLibraryJsonIsKeptAsideToo() = withDir { dir ->
        File(dir, "library.json").writeText("{ not json")
        val store = LibraryStore(File(dir, "library.db"))
        assertEquals(LibraryData(), store.data)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("library.json.broken-") })
        store.close()
    }
}
