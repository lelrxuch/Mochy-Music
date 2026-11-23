package com.mochyy.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * mpv running hidden in the background. Mochy sends it commands over mpv's JSON IPC socket
 * (https://mpv.io/manual/stable/#json-ipc) and listens for position, pause and end-of-track.
 * [onFileEnd] gets mpv's reason: "eof" when a song finished, "error" when it couldn't play.
 */
class Mpv(extraArgs: List<String> = emptyList(), private val onFileEnd: (reason: String) -> Unit) : AutoCloseable {
    // mpv's socket accepts any command, even running programs, so it lives in the user's private runtime dir, not shared /tmp
    private val socket = File(
        System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"),
        "mochy-mpv-${ProcessHandle.current().pid()}.sock",
    )
    private val process = ProcessBuilder(
        listOf("mpv", "--idle=yes", "--no-video", "--no-terminal", "--input-ipc-server=${socket.path}") + extraArgs,
    ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val channel = connect()

    val position = MutableStateFlow(0.0)
    val duration = MutableStateFlow(0.0)
    val paused = MutableStateFlow(false)
    val volume = MutableStateFlow(100.0)
    val codec = MutableStateFlow<String?>(null)
    val sampleRate = MutableStateFlow<Int?>(null)

    init {
        Thread(::readLoop, "mpv-ipc").apply { isDaemon = true }.start()
        send("observe_property", 1, "time-pos")
        send("observe_property", 2, "duration")
        send("observe_property", 3, "pause")
        send("observe_property", 4, "volume")
        send("observe_property", 5, "audio-codec-name")
        send("observe_property", 6, "audio-params/samplerate")
    }

    fun play(url: String, title: String, userAgent: String) {
        // the title is what GNOME's media controls show when mpv-mpris is installed
        send("set_property", "force-media-title", title)
        // googlevideo checks links against the client that asked for them
        send("set_property", "user-agent", userAgent)
        send("loadfile", url)
        send("set_property", "pause", false)
    }

    fun togglePause() = send("cycle", "pause")

    fun seek(seconds: Double) = send("seek", seconds, "absolute")

    fun setVolume(percent: Double) = send("set_property", "volume", percent.coerceIn(0.0, 100.0))

    fun stop() = send("stop")

    override fun close() {
        runCatching { send("quit") }
        runCatching { channel.close() }
        process.destroy()
        socket.delete()
    }

    @Synchronized
    private fun send(vararg args: Any) {
        val command = buildJsonObject {
            put("command", JsonArray(args.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
        }
        val buffer = ByteBuffer.wrap("$command\n".toByteArray())
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun connect(): SocketChannel {
        repeat(50) {
            if (socket.exists()) runCatching { return SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())) }
            check(process.isAlive) { "mpv closed right after starting" }
            Thread.sleep(100)
        }
        process.destroy()
        error("mpv never opened its control socket")
    }

    // mpv answers with one JSON object per line
    private fun readLoop() {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = ByteArrayOutputStream()
        runCatching {
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val byte = buffer.get()
                    if (byte == '\n'.code.toByte()) {
                        handle(line.toString(Charsets.UTF_8))
                        line.reset()
                    } else {
                        line.write(byte.toInt())
                    }
                }
                buffer.clear()
            }
        } // the socket closes when the app quits, that ends this loop
    }

    private fun handle(raw: String) {
        val message = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (message["event"]?.jsonPrimitive?.content) {
            "property-change" -> {
                val data = message["data"] as? JsonPrimitive
                when (message["name"]?.jsonPrimitive?.content) {
                    "time-pos" -> position.value = data?.doubleOrNull ?: 0.0
                    "duration" -> duration.value = data?.doubleOrNull ?: 0.0
                    "pause" -> paused.value = data?.booleanOrNull ?: false
                    "volume" -> volume.value = data?.doubleOrNull ?: volume.value
                    "audio-codec-name" -> codec.value = data?.contentOrNull
                    "audio-params/samplerate" -> sampleRate.value = data?.intOrNull
                }
            }
            // "stop" and "redirect" also end a file when we load the next one, those aren't news
            "end-file" -> message["reason"]?.jsonPrimitive?.content
                ?.takeIf { it == "eof" || it == "error" }
                ?.let(onFileEnd)
        }
    }
}
