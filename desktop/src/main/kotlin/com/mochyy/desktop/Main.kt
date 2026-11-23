// Mochy Desktop, a Linux port that started life as Mochy Music by realidkroo:
// https://github.com/realidkroo/mochy-music (Mochy itself is a fork of ViviMusic, built on Metrolist).
package com.mochyy.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import java.awt.Dimension

fun main() {
    if (SingleInstance.handOff()) return
    SingleInstance.listen()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val library = LibraryStore()
    // the sign-in goes first, so the first home feed is already the account's own
    val account = AccountStore()
    account.keepFresh(scope)
    val app = AppState(Nav(), Player(scope, library), library, HomeModel(scope), SearchModel(), account, LibrarySync(scope, library, account))
    val mpris = Mpris(scope, app.player)
    val icon = BitmapPainter(Image.makeFromEncoded(AppState::class.java.getResource("/icon.png")!!.readBytes()).toComposeImageBitmap())
    application {
        Window(
            onCloseRequest = { mpris.close(); app.player.close(); exitApplication() },
            title = "Mochy",
            icon = icon,
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
            onKeyEvent = { shortcut(app, it) },
        ) {
            window.minimumSize = Dimension(760, 560)
            SingleInstance.window = window
            window.background = java.awt.Color.BLACK
            MochyTheme {
                CompositionLocalProvider(
                    LocalApp provides app,
                    LocalContextMenuRepresentation provides DarkDefaultContextMenuRepresentation,
                    LocalScrollbarStyle provides ScrollbarStyle(
                        minimalHeight = 32.dp, thickness = 6.dp, shape = RoundedCornerShape(3.dp), hoverDurationMillis = 200,
                        unhoverColor = Color.White.copy(alpha = 0.12f), hoverColor = Color.White.copy(alpha = 0.35f),
                    ),
                ) { Shell() }
            }
        }
    }
}

/** keys that work anywhere a text field isn't taking them: space plays and pauses, Esc and Alt+← go back */
private fun shortcut(app: AppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when {
        event.key == Key.Spacebar -> app.player.togglePause()
        event.key == Key.Escape -> app.nav.back()
        event.isAltPressed && event.key == Key.DirectionLeft -> app.nav.back()
        event.isCtrlPressed && event.key == Key.DirectionRight -> app.player.next()
        event.isCtrlPressed && event.key == Key.DirectionLeft -> app.player.previous()
        else -> return false
    }
    return true
}

/** the whole app without its window, for the screenshots CI takes */
@Composable
internal fun ShellForTest() = Shell()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shell() {
    val app = LocalApp.current
    val nav = app.nav
    val player = app.player
    // each page keeps its scroll position while another is on top of it
    val pages = rememberSaveableStateHolder()
    val backdrop = remember { GlassBackdrop() }
    CompositionLocalProvider(LocalGlass provides backdrop) { Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Press) { if (it.button == PointerButton.Back) nav.back() },
    ) {
        val route = nav.top
        // the pages are what the floating glass shows, bent and blurred
        Box(Modifier.fillMaxSize().background(Color.Black).glassSource(backdrop)) { pages.SaveableStateProvider(route?.toString() ?: nav.tab.name) {
            when (route) {
                null -> when (nav.tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Search -> SearchScreen()
                    Tab.Library -> LibraryScreen()
                    Tab.Settings -> SettingsScreen()
                }
                is Route.Artist -> ArtistScreen(route.id)
                is Route.Album -> AlbumScreen(route.id)
                is Route.Playlist -> PlaylistScreen(route.id)
            }
        } }
        if (route != null) {
            Box(Modifier.padding(16.dp).liquidGlass(CircleShape, elevation = 6.dp)) {
                IconAction(drawable("arrow_back"), str("desktop_go_back"), { nav.back() }, size = 44.dp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            player.message?.let { text ->
                LaunchedEffect(text) {
                    delay(5_000)
                    if (player.message == text) player.message = null
                }
                Text(
                    text,
                    Modifier.fillMaxWidth().chromeGlass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MiniPlayer(player, onOpen = { nav.playerOpen = true }, Modifier.fillMaxWidth())
            NavBar(nav.tab, nav::select, Modifier.fillMaxWidth())
        }

        AnimatedVisibility(
            nav.playerOpen && player.current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            NowPlaying(onClose = { nav.playerOpen = false })
        }
    } }
}
