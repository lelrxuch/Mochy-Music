// Mochy Desktop, a Linux port that started life as Mochy Music by realidkroo:
// https://github.com/realidkroo/mochy-music (Mochy itself is a fork of ViviMusic, built on Metrolist).
package com.mochyy.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.unit.IntOffset
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
    // the last changes reach the disk even when the app is stopped some other way (logout, kill)
    Runtime.getRuntime().addShutdownHook(Thread { library.close() })
    // the sign-in goes first, so the first home feed is already the account's own
    val account = AccountStore()
    account.keepFresh(scope)
    val app = AppState(Nav(), Player(scope, library), library, HomeModel(scope), SearchModel(), account, LibrarySync(scope, library, account), scope)
    val mpris = Mpris(scope, app.player)
    val icon = BitmapPainter(Image.makeFromEncoded(AppState::class.java.getResource("/icon.png")!!.readBytes()).toComposeImageBitmap())
    application {
        Window(
            onCloseRequest = { mpris.close(); app.player.close(); library.close(); exitApplication() },
            title = "Mochy",
            icon = icon,
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
            onKeyEvent = { shortcut(app, it) },
        ) {
            window.minimumSize = Dimension(760, 560)
            SingleInstance.window = window
            // Ctrl or Shift let go while the window was away would otherwise leave clicks picking songs
            DisposableEffect(window) {
                val focus = object : java.awt.event.WindowAdapter() {
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        app.selection.extending = false
                    }
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus) }
            }
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
    // Ctrl or Shift held turns a click on a song into picking it
    if (event.key in listOf(Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight)) {
        app.selection.extending = event.type == KeyEventType.KeyDown
        return false
    }
    if (event.type != KeyEventType.KeyDown) return false
    when {
        event.key == Key.Spacebar -> app.player.togglePause()
        event.key == Key.Escape -> when {
            app.sheet != null -> app.closeSheet()
            app.selection.active -> app.selection.clear()
            else -> app.nav.back()
        }
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun Shell() {
    val app = LocalApp.current
    val nav = app.nav
    val player = app.player
    // each page keeps its scroll position while another is on top of it
    val pages = rememberSaveableStateHolder()
    val backdrop = remember { GlassBackdrop() }
    SharedTransitionLayout { CompositionLocalProvider(LocalGlass provides backdrop, LocalSharedScope provides this) { Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Press) { if (it.button == PointerButton.Back) nav.back() },
    ) {
        val route = nav.top
        // what was picked on one page doesn't carry to the next
        LaunchedEffect(route, nav.tab) { app.selection.clear() }
        // the pages are what the floating glass shows, bent and blurred
        Box(Modifier.fillMaxSize().background(Color.Black).glassSource(backdrop)) { pages.SaveableStateProvider(route?.toString() ?: nav.tab.name) {
            // each page keeps its own record of what already played its entrance
            CompositionLocalProvider(LocalReveal provides rememberRevealSeenState()) { when (route) {
                null -> when (nav.tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Search -> SearchScreen()
                    Tab.Library -> LibraryScreen()
                    Tab.Settings -> SettingsScreen()
                }
                is Route.Artist -> ArtistScreen(route.id)
                is Route.Album -> AlbumScreen(route.id)
                is Route.Playlist -> PlaylistScreen(route.id)
                is Route.LocalPlaylist -> LocalPlaylistScreen(route.id)
                is Route.Browse -> BrowseScreen(route)
                Route.AudioLab -> AudioLabScreen()
            } }
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
            AnimatedVisibility(app.selection.active, enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), initialScale = 0.9f), exit = fadeOut(tween(120))) {
                SelectionBar(Modifier.fillMaxWidth())
            }
            // hidden while the player is open, so its cover can fly up into the player's and back
            AnimatedVisibility(!nav.playerOpen, enter = fadeIn(tween(220)), exit = fadeOut(tween(120))) {
                CompositionLocalProvider(LocalVisibilityScope provides this) {
                    MiniPlayer(player, onOpen = { nav.playerOpen = true }, Modifier.fillMaxWidth())
                }
            }
            NavBar(nav.tab, nav::select, Modifier.fillMaxWidth())
        }

        // the player grows up out of the mini player on a spring, and sinks back into it
        AnimatedVisibility(
            nav.playerOpen && player.current != null,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 320f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 3 } +
                scaleIn(spring(dampingRatio = 0.86f, stiffness = 320f), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(tween(180)),
            exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 3 } +
                scaleOut(spring(dampingRatio = 1f, stiffness = 420f), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(tween(160)),
        ) {
            CompositionLocalProvider(LocalVisibilityScope provides this) { NowPlaying(onClose = { nav.playerOpen = false }) }
        }
        SheetHost()
    } } }
}
