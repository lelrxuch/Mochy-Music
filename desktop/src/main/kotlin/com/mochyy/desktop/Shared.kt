package com.mochyy.desktop

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/*
 * The mini player's cover flies up to become the player's cover, and back down when it closes
 * (ui/player/MorphingCover.kt does this on Android). Compose's shared elements do the flying; these
 * locals carry the two scopes it needs to wherever the cover is drawn.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** the AnimatedVisibility the mini player or the player is shown in */
val LocalVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** marks this as the [key] element shared between the mini player and the player; nothing outside them */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPlayerElement(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val visibility = LocalVisibilityScope.current ?: return this
    return with(shared) { this@sharedPlayerElement.sharedElement(rememberSharedContentState(key), visibility) }
}
