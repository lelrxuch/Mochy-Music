package com.mochyy.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A playlist you edit isn't shown from the copy kept before the edit. */
class PageCacheCheck {
    @Test
    fun anEditedPlaylistIsLoadedAgain() {
        // YouTube names the same playlist with and without "VL" depending on where it comes from
        pages[Route.Playlist("VLPLabc")] = "before"
        pages[Route.Playlist("PLabc")] = "before"
        pages[Route.Playlist("VLPLother")] = "kept"
        forgetPlaylist("PLabc")
        assertFalse(Route.Playlist("VLPLabc") in pages)
        assertFalse(Route.Playlist("PLabc") in pages)
        assertTrue(Route.Playlist("VLPLother") in pages, "other playlists keep their copy")
    }

    @Test
    fun theCacheKeepsOnlyTheLatestPages() {
        repeat(100) { pages[Route.Album("album$it")] = it }
        assertTrue(pages.size <= 60)
        assertTrue(Route.Album("album99") in pages)
        assertFalse(Route.Album("album0") in pages)
    }
}
