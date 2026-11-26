package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.awt.event.WindowEvent
import kotlin.math.abs

/**
 * Mochy on the desktop's media controls: GNOME's panel and lock screen, and the keyboard's media keys,
 * all driving Mochy's own queue. The D-Bus side is mpris/mochy-mpris.py, Python with GLib's Gio (part of
 * every GNOME install), run the way mpv is: a helper process spoken to in JSON lines. Without python3
 * or Gio there are simply no media controls.
 */
class Mpris(private val scope: CoroutineScope, private val player: Player) : AutoCloseable {
    private val process: Process? = runCatching {
        val script = Mpris::class.java.getResourceAsStream("/mpris/mochy-mpris.py")!!.use { it.readBytes().decodeToString() }
        ProcessBuilder("python3", "-c", script).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }.getOrNull()
    private val input = process?.outputStream?.bufferedWriter()

    init {
        val helper = process
        if (helper != null) {
            Thread({
                runCatching { helper.inputStream.bufferedReader().forEachLine { line -> scope.launch { handle(line) } } }
            }, "mpris").apply { isDaemon = true }.start()
            watch()
        }
    }

    override fun close() {
        runCatching { input?.close() }
        process?.destroy()
    }

    // twice a second: the state goes out when something changed, or when the position jumped (a seek)
    private fun watch() = scope.launch {
        var last: JsonObject? = null
        var lastPosition = 0.0
        var lastAt = System.nanoTime()
        var lastPlaying = false
        while (isActive) {
            val state = state()
            val position = player.mpv?.position?.value ?: 0.0
            val playing = state["status"]?.jsonPrimitive?.content == "Playing"
            val expected = lastPosition + if (lastPlaying) (System.nanoTime() - lastAt) / 1e9 else 0.0
            val seeked = state == last && abs(position - expected) > 1.5
            if (state != last || seeked) {
                send(JsonObject(state + mapOf("position" to JsonPrimitive(position), "seeked" to JsonPrimitive(seeked))))
                last = state
                lastPosition = position
                lastAt = System.nanoTime()
                lastPlaying = playing
            }
            delay(500)
        }
    }

    private fun state(): JsonObject {
        val song = player.current
        val mpv = player.mpv
        return buildJsonObject {
            put("status", if (song == null) "Stopped" else if (mpv?.paused?.value == true) "Paused" else "Playing")
            if (song != null) {
                put("id", song.id)
                put("title", song.title)
                putJsonArray("artists") { song.artists.forEach { add(JsonPrimitive(it.name)) } }
                song.album?.name?.let { put("album", it) }
                put("art", song.thumbnail.artwork(544))
                put("length", mpv?.duration?.value?.takeIf { it > 0 } ?: song.duration?.toDouble() ?: 0.0)
            }
            put("canNext", player.index + 1 < player.queue.size || player.repeat == Repeat.ALL)
            put("shuffle", player.shuffled)
            put("loop", when (player.repeat) { Repeat.OFF -> "None"; Repeat.ONE -> "Track"; Repeat.ALL -> "Playlist" })
            put("volume", (mpv?.volume?.value ?: 100.0) / 100)
        }
    }

    @Synchronized
    private fun send(state: JsonObject) {
        // a helper that died (no D-Bus session, say) just stops listening
        runCatching {
            input?.write(state.toString())
            input?.newLine()
            input?.flush()
        }
    }

    private fun handle(line: String) {
        val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val value = message["value"]?.jsonPrimitive
        val paused = player.mpv?.paused?.value ?: true
        when (message["cmd"]?.jsonPrimitive?.contentOrNull) {
            "PlayPause" -> player.togglePause()
            "Play" -> if (paused) player.togglePause()
            "Pause", "Stop" -> if (!paused) player.togglePause()
            "Next" -> player.next()
            "Previous" -> player.previous()
            "SetPosition" -> message["position"]?.jsonPrimitive?.doubleOrNull?.let { player.seek(it) }
            "Shuffle" -> value?.booleanOrNull?.let { if (it != player.shuffled) player.toggleShuffle() }
            "Loop" -> {
                val wanted = when (value?.contentOrNull) { "Track" -> Repeat.ONE; "Playlist" -> Repeat.ALL; else -> Repeat.OFF }
                repeat(Repeat.entries.size) { if (player.repeat != wanted) player.cycleRepeat() }
            }
            "Volume" -> value?.doubleOrNull?.let { player.setVolume(it * 100, save = true) }
            "Raise" -> SingleInstance.raise()
            "Quit" -> SingleInstance.window?.let { it.dispatchEvent(WindowEvent(it, WindowEvent.WINDOW_CLOSING)) }
        }
    }
}
