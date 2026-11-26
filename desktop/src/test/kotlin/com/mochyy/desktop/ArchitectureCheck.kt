package com.mochyy.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shape of the code, checked on every build so it doesn't drift as the app grows:
 *
 *   text, playback, library, account, integrations   the core: what the app does, no drawing
 *   ui, ui/screens                     how it looks, on top of the core
 *   (root)                             Main, navigation and AppState, which wire the two together
 *
 * The core never reaches up into the ui or into Compose's drawing libraries (Compose's runtime, for
 * state that the screens watch, is fine). That keeps the core testable without a window and lets a
 * screen be redone without touching how music plays or the library is kept.
 */
class ArchitectureCheck {
    private val root = File("src/main/kotlin/com/mochyy/desktop")
    private val core = listOf("text", "playback", "library", "account", "integrations")

    private val forbidden = listOf(
        Regex("""androidx\.compose\.(ui|foundation|material3|animation)\b""") to "Compose drawing",
        Regex("""com\.mochyy\.desktop\.ui\b""") to "the ui packages",
        Regex("""^import com\.mochyy\.desktop\.(\*|[A-Z]\w*)""", RegexOption.MULTILINE) to "the app's wiring (AppState, Route...)",
    )

    @Test
    fun theCoreDoesNotKnowAboutTheScreens() {
        val problems = core.flatMap { dir ->
            File(root, dir).walk().filter { it.extension == "kt" }.flatMap { file ->
                val text = file.readText()
                forbidden.filter { (pattern, _) -> pattern.containsMatchIn(text) }.map { (_, what) -> "${file.relativeTo(root)} uses $what" }
            }.toList()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyFileSitsInThePackageOfItsFolder() {
        val wrong = root.walk().filter { it.extension == "kt" }.mapNotNull { file ->
            val folder = file.parentFile.relativeTo(root).path.replace(File.separatorChar, '.')
            val expected = "com.mochyy.desktop" + if (folder.isEmpty()) "" else ".$folder"
            val declared = file.useLines { lines -> lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() }
            if (declared == expected) null else "${file.relativeTo(root)} says $declared, its folder says $expected"
        }.toList()
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
