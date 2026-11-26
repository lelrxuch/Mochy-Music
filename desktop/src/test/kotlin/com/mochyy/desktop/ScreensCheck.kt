package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.pages.MoodAndGenres
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Renders the app's screens to PNGs in $MOCHY_SHOTS, so changes to the look can be checked without
 * opening the app. Does nothing when MOCHY_SHOTS isn't set. Needs internet for real content.
 */
class ScreensCheck {
    private companion object {
        // public music videos, only for their thumbnails
        val SAMPLE_VIDEOS = listOf(
            "FGBhQbmPwH8", "5NV6Rdv1a3I", "gAjR4_CbPpQ", "a5uQMwRMHcs", "K0HSD_i2DvA", "yydNF8tuVmU",
            "IluRBvnYMoY", "h5EofwRzit0", "NF-kLy44Hls", "sFrNsSnk8GM", "LKYPYj2XX80", "dQw4w9WgXcQ",
        )
    }

    @Test
    fun screens() = runBlocking {
        val dir = System.getenv("MOCHY_SHOTS")?.let(::File) ?: return@runBlocking
        dir.mkdirs()
        val work = Files.createTempDirectory("mochy-shots").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = LibraryStore(File(work, "library.db"))
        val account = AccountStore(File(work, "account.json"))
        val app = AppState(Nav(), Player(scope, library, File(work, "queue.json")), library, HomeModel(scope), SearchModel(), account, LibrarySync(scope, library, account))

        fun shot(name: String, seconds: Int = 6, content: @Composable () -> Unit = { ShellForTest() }) {
            val scene = ImageComposeScene(1280, 840, Density(1f)) {
                MochyTheme { CompositionLocalProvider(LocalApp provides app) { content() } }
            }
            var time = 0L
            var image = scene.render(time)
            repeat(seconds * 5) {
                Thread.sleep(200)
                time += 200_000_000
                image = scene.render(time)
            }
            File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }

        ensureVisitorData()
        shot("1-home", 10)
        app.search.query = "daft punk"
        app.search.submitted = "daft punk"
        app.nav.select(Tab.Search)
        shot("2-search")
        val found = YouTube.searchSummary("daft punk").getOrNull()?.summaries?.flatMap { it.items }.orEmpty()
        found.filterIsInstance<ArtistItem>().firstOrNull()?.let { app.nav.open(Route.Artist(it.id)); shot("3-artist") }
        found.filterIsInstance<AlbumItem>().firstOrNull()?.let { app.nav.open(Route.Album(it.browseId)); shot("4-album") }
        found.filterIsInstance<SongItem>().firstOrNull()?.let { song ->
            library.toggleLike(song)
            app.player.playSong(song)
            app.nav.playerOpen = true
            shot("5-player", 8)
            app.nav.playerOpen = false
        }
        // a library with covers, so the glass has something to bend even where YouTube refuses CI
        SAMPLE_VIDEOS.forEachIndexed { i, id ->
            library.toggleLike(
                SongItem(
                    id = id, title = "Sample song ${i + 1}",
                    artists = listOf(com.music.innertube.models.Artist(name = "Sample artist", id = null)),
                    thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                ),
            )
        }
        app.nav.select(Tab.Library)
        shot("6-library", 6)
        // a dialog on glass, over the library: add to playlist, with one playlist of this computer's
        library.createPlaylist("Road trip", library.data.liked.take(3))
        library.data.liked.firstOrNull()?.let { song -> app.showSheet { AddToPlaylistSheet(listOf(song)) } }
        shot("6b-add-to-playlist", 3)
        app.closeSheet()
        // the player with a sample song: its stream won't load on CI, but the page and its backdrop do
        library.data.liked.firstOrNull()?.let { song ->
            app.player.playSong(song)
            app.nav.playerOpen = true
            shot("5b-player-sample", 6)
            app.nav.playerOpen = false
        }
        // the moods grid of the search page, with sample colours and covers since CI can't load YouTube's
        val colors = listOf(0xFFE53935, 0xFF8E24AA, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF000000, 0xFFFB8C00, 0xFF00ACC1, 0xFF6D4C41, 0xFFD81B60)
        val names = listOf("Chill", "Commute", "Energy boosters", "Feel good", "Focus", "Gaming", "Party", "Romance", "Sleep", "Workout & running")
        val moods = MoodAndGenres("Moods & moments", names.mapIndexed { i, title ->
            MoodAndGenres.Item(title, colors[i], BrowseEndpoint("FEmusic_moods_and_genres_category", "sample$i"))
        })
        moods.items.forEachIndexed { i, mood ->
            MoodCovers.covers[MoodCovers.key(mood)] = listOf(SAMPLE_VIDEOS[i % 12], SAMPLE_VIDEOS[(i + 5) % 12]).map { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        }
        shot("2b-moods", 4) { Box(Modifier.fillMaxSize().background(Color.Black)) { MoodSection(moods) } }
        app.nav.select(Tab.Settings)
        shot("7-settings", 3)
        library.setAudioLab(AudioLabSettings(eqEnabled = true, presetId = "rock", gains = EqPresets.first { it.id == "rock" }.gains, reverb = Reverb.Hall))
        app.nav.open(Route.AudioLab)
        shot("8-audiolab", 3)
        app.player.close()
        work.deleteRecursively()
    }
}
