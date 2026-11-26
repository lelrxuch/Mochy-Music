package com.mochyy.desktop.text

import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// ---- texts: the Android app's strings.xml in the system's language, plus the few the desktop adds ----

private object Strings {
    val table: Map<String, String> by lazy {
        val locale = Locale.getDefault()
        // later folders win: english, then the language, then its region
        val folders = listOf("values", "values-${locale.language}", "values-${locale.language}-r${locale.country}")
        val files = listOf("strings.xml", "mochy_strings.xml", "desktop_strings.xml")
        buildMap { for (folder in folders) for (file in files) read("/res/$folder/$file")?.let(::putAll) }
    }

    private fun read(path: String): Map<String, String>? = Strings::class.java.getResourceAsStream(path)?.use { stream ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    // android's own escaping in strings.xml
    private fun unescape(raw: String) = raw.trim().removeSurrounding("\"")
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}

/** the text for [key] in the system language; [args] fill its %1\$s style blanks */
fun str(key: String, vararg args: Any): String {
    val text = Strings.table[key] ?: key
    return if (args.isEmpty()) text else runCatching { text.format(*args) }.getOrDefault(text)
}
