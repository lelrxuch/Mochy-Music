package com.mochyy.desktop.ui

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.screens.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// ---- colours: the Android app's dark scheme (ui/theme/Theme.kt), mochy is dark only ----

val MochyColors = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF333333),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    surfaceTint = Color.Black,
)

/** the grey of second lines (artists, counts) under white titles */
val Dim = Color.White.copy(alpha = 0.6f)

/** cards and fields a step above the black page */
val Raised = Color(0xFF1A1A1A)

// ---- type: Inter everywhere, semibold and a touch tight, like ui/theme/Type.kt ----

private fun font(file: String, weight: FontWeight) = Font("res/font/$file.ttf", weight)

val Inter = FontFamily(
    font("inter_thin", FontWeight.Thin),
    font("inter_extralight", FontWeight.ExtraLight),
    font("inter_light", FontWeight.Light),
    font("inter_regular", FontWeight.Normal),
    font("inter_medium", FontWeight.Medium),
    font("inter_semibold", FontWeight.SemiBold),
    font("inter_bold", FontWeight.Bold),
    font("inter_extrabold", FontWeight.ExtraBold),
)

/** the tall condensed face of the artist page's name */
val Bartle = FontFamily(font("bbh_bartle_regular", FontWeight.Normal))

private fun TextStyle.mochy() = copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.05).em)

val MochyType = Typography().run {
    Typography(
        displayLarge.mochy(), displayMedium.mochy(), displaySmall.mochy(),
        headlineLarge.mochy(), headlineMedium.mochy(), headlineSmall.mochy(),
        titleLarge.mochy(), titleMedium.mochy(), titleSmall.mochy(),
        bodyLarge.mochy(), bodyMedium.mochy(), bodySmall.mochy(),
        labelLarge.mochy(), labelMedium.mochy(), labelSmall.mochy(),
    )
}

@Composable
fun MochyTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = MochyColors, typography = MochyType) {
        // text and icons are white on mochy's black unless they say otherwise
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }

private object Resources

// ---- icons: the Android app's own vector drawables and svgs ----

private val vectors = HashMap<String, ImageVector>()

/** an icon from the app's res/drawable, by file name */
@Composable
fun drawable(name: String): Painter = rememberVectorPainter(vector(name))

fun vector(name: String): ImageVector = vectors.getOrPut(name) {
    val stream = Resources::class.java.getResourceAsStream("/res/drawable/$name.xml") ?: error("no drawable $name")
    val xml = stream.use { it.readBytes().decodeToString() }
    runCatching { loadXmlImageVector(InputSource(withPlainValues(xml).reader()), Density(1f)) }.getOrElse {
        // a blank icon beats the whole window closing over one drawable the desktop reader can't follow
        System.err.println("Mochy: can't read drawable $name: ${it.message}")
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).build()
    }
}

// the desktop reader only knows plain values, not android's @color, @dimen and ?attr references; icons get tinted anyway
private val COLOR_REF = Regex("""(android:(?:fillColor|strokeColor|color))="[@?][^"]*"""")
private val SIZE_REF = Regex("""(android:(?:width|height))="[@?][^"]*"""")

private fun withPlainValues(xml: String) = xml
    .replace("\"@android:color/transparent\"", "\"#00000000\"")
    .replace(Regex("""\s+android:tint="[^"]*""""), "")
    .replace(COLOR_REF, "$1=\"#FFFFFFFF\"")
    .replace(SIZE_REF, "$1=\"24dp\"")
    .replace(Regex("""(\d)dip""""), "$1dp\"") // the old spelling of dp

/** an icon from the app's res/raw svgs, by file name */
@Composable
fun svg(name: String): Painter {
    val density = LocalDensity.current
    return remember(name, density) {
        Resources::class.java.getResourceAsStream("/res/raw/$name.svg")!!.use { loadSvgPainter(it, density) }
    }
}
