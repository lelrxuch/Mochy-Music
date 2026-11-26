package com.mochyy.desktop

import com.music.innertube.models.ArtistItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncCheck {
    private fun artist(id: String) = ArtistItem(id = id, title = id, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
    private fun ids(list: List<ArtistItem>?) = list?.map { it.id }

    @Test
    fun youtubeWinsExceptForWhatsWaitingToGoUp() {
        val remote = listOf(artist("a"), artist("b"))
        val local = listOf(artist("c"), artist("d"), artist("a"))
        val pending = listOf(PendingChange("c", PendingChange.ARTIST, "c", on = true), PendingChange("b", PendingChange.ARTIST, "b", on = false))
        // c is waiting to be saved, b to be removed, d was only here and isn't adopted
        assertEquals(listOf("c", "a"), ids(merge(remote, local, pending, adopt = false)))
        // right after signing in, d (saved before) is kept too
        assertEquals(listOf("c", "d", "a"), ids(merge(remote, local, pending, adopt = true)))
        // a shelf that didn't load leaves the local one alone
        assertEquals(null, merge(null, local, pending, adopt = false))
    }

    @Test
    fun aJustCreatedPlaylistStaysUntilYouTubeListsIt() {
        val remote = listOf(artist("a"))
        val local = listOf(artist("new"), artist("a"))
        assertEquals(listOf("a"), ids(merge(remote, local, emptyList(), adopt = false)))
        assertEquals(listOf("new", "a"), ids(merge(remote, local, emptyList(), adopt = false, keep = setOf("new"))))
    }

    @Test
    fun artistsSubscribeByChannel() {
        val change = artist("UCabc").copy(channelId = "UCxyz").toChange(on = true)!!
        assertEquals(PendingChange("UCabc", PendingChange.ARTIST, "UCxyz", true), change)
    }

    // Needs internet and a browser signed in to YouTube. Only reads: an empty local library sends nothing up.
    @Test
    fun theAccountsLibraryComesIn() = runBlocking {
        val login = BrowserCookies.find().firstOrNull() ?: return@runBlocking println("no browser signed in to YouTube, skipped")
        val dir = File.createTempFile("mochy-sync", "").apply { delete(); mkdirs() }
        try {
            val account = AccountStore(File(dir, "account.json"))
            account.signIn(login.cookie).getOrThrow()
            val library = LibraryStore(File(dir, "library.db"))
            val sync = LibrarySync(this, library, account)
            sync.pull()
            while (sync.syncing) delay(100)
            with(library.data) { println("synced: ${liked.size} liked, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists, ${pending.size} pending") }
        } finally {
            dir.deleteRecursively()
        }
    }
}
