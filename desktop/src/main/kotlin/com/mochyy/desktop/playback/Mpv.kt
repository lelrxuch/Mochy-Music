package com.mochyy.desktop.playback

import com.mochyy.desktop.text.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.util.concurrent.TimeUnit

/**
 * mpv running hidden in the background. Mochy sends it commands over mpv's JSON IPC socket
 * (https://mpv.io/manual/stable/#json-ipc) and listens for position, pause and end-of-track.
 * [onFileEnd] gets mpv's reason: "eof" when a song finished, "error" when it couldn't play.
 */
// what mpv sends when no client asked for a particular one
private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"

class Mpv(private val extraArgs: List<String> = emptyList(), private val onFileEnd: (reason: String) -> Unit) : AutoCloseable {
    // mpv's socket accepts any command, even running programs, so it lives in the user's private runtime dir, not shared /tmp
    private val socket = File(
        System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"),
        "mochy-mpv-${ProcessHandle.current().pid()}.sock",
    )
    @Volatile private lateinit var process: Process
    @Volatile private lateinit var channel: SocketChannel
    @Volatile private var closed = false
    // which mpv this is: bumped by every start, so two parts that notice the same death start one mpv, not two
    @Volatile private var generation = 0

    // what was set on mpv, put back on a fresh one after a restart
    private var volumeSetting = 100.0
    private var filterSetting = ""
    private var speedSetting = 1.0
    private val restartTimes = ArrayDeque<Long>()

    /**
     * mpv died on its own (an audio device going away, a crash) and was started again: where the
     * song was, and whether it was playing, so the player can pick it back up.
     */
    val revived = MutableSharedFlow<Pair<Double, Boolean>>(extraBufferCapacity = 1)

    /** whether mpv is there to take commands */
    val isRunning get() = !closed && process.isAlive

    /** for the tests that kill mpv on purpose */
    internal val pid get() = process.pid()

    val position = MutableStateFlow(0.0)
    val duration = MutableStateFlow(0.0)
    val seekRevision = MutableStateFlow(0L)
    private var seekPending = false
    val paused = MutableStateFlow(false)
    val volume = MutableStateFlow(100.0)
    val codec = MutableStateFlow<String?>(null)
    val sampleRate = MutableStateFlow<Int?>(null)

    init {
        start()
    }

    /** starts mpv and its control socket, and gives it back the volume and the Audio Lab settings */
    private fun start() {
        generation++
        socket.delete()
        process = ProcessBuilder(
            // --load-scripts=no keeps mpv-mpris (and any personal mpv script) out: Mochy has its own media controls
            listOf("mpv", "--idle=yes", "--no-video", "--no-terminal", "--load-scripts=no", "--input-ipc-server=${socket.path}") + extraArgs,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val opened = connect()
        channel = opened
        val started = generation
        Thread({ readLoop(opened, started) }, "mpv-ipc").apply { isDaemon = true }.start()
        write("observe_property", 1, "time-pos")
        write("observe_property", 2, "duration")
        write("observe_property", 3, "pause")
        write("observe_property", 4, "volume")
        write("observe_property", 5, "audio-codec-name")
        write("observe_property", 6, "audio-params/samplerate")
        write("set_property", "volume", volumeSetting)
        if (filterSetting.isNotEmpty()) write("set_property", "af", filterSetting)
        if (speedSetting != 1.0) write("set_property", "speed", speedSetting)
    }

    /**
     * mpv number [failed] stopped answering: start a new one, at most 5 times a minute so a mpv that
     * can't run doesn't loop. Answers whether there's a running mpv again.
     */
    @Synchronized
    private fun revive(failed: Int): Boolean {
        if (closed) return false
        // another part already noticed and started a fresh one
        if (generation != failed) return isRunning
        val now = System.currentTimeMillis()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > 60_000) restartTimes.removeFirst()
        if (restartTimes.size >= 5) return false
        restartTimes.addLast(now)
        runCatching { channel.close() }
        // a mpv that stopped answering is no use even if it hasn't quite exited yet
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        return runCatching { start() }
            .onSuccess {
                // a fresh mpv has nothing loaded: it shows as paused (as at start-up) until the song is asked for again
                duration.value = 0.0
                write("set_property", "pause", true)
            }
            .onFailure { System.err.println("mpv: couldn't start it again: ${it.message}") }
            .isSuccess
    }

    fun play(url: String, title: String, userAgent: String?, cookies: File? = null, gainDb: Double = 0.0) {
        // evens out loud and quiet songs (the app's loudness enhancer); 0 when normalising is off
        send("set_property", "volume-gain", gainDb)
        send("set_property", "force-media-title", title)
        // googlevideo checks links against the client that asked for them
        send("set_property", "user-agent", userAgent ?: DEFAULT_USER_AGENT)
        // only used when the link is a watch page that mpv hands to yt-dlp
        send("set_property", "ytdl-format", "bestaudio/best")
        send("set_property", "ytdl-raw-options", cookies?.let { "cookies=${it.path}" } ?: "")
        send("loadfile", url)
        send("set_property", "pause", false)
    }

    fun togglePause() = send("cycle", "pause")

    /** ffmpeg filters for the sound (Audio Lab); "" takes them all away */
    fun setFilters(chain: String) {
        filterSetting = chain
        send("set_property", "af", chain)
    }

    fun setSpeed(speed: Double) {
        speedSetting = speed
        send("set_property", "speed", speed)
    }

    fun seek(seconds: Double) = send("seek", seconds, "absolute")

    fun setVolume(percent: Double) {
        volumeSetting = percent.coerceIn(0.0, 100.0)
        send("set_property", "volume", volumeSetting)
    }

    fun stop() = send("stop")

    override fun close() {
        // marked first, so the socket closing under the read loop isn't taken for a crash
        closed = true
        runCatching { write("quit") }
        runCatching { channel.close() }
        process.destroy()
        socket.delete()
    }

    /** a command to mpv; if mpv died since the last one, it's started again first. Never throws: a click mustn't crash the app */
    @Synchronized
    private fun send(vararg args: Any) {
        if (closed) return
        val seen = generation
        val sent = runCatching { write(*args) }.isSuccess || (revive(seen) && runCatching { write(*args) }.isSuccess)
        if (!sent) System.err.println("mpv: a command didn't reach mpv: ${args.firstOrNull()}")
    }

    @Synchronized
    private fun write(vararg args: Any) {
        if (!process.isAlive) throw IOException("mpv isn't running")
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
    private fun readLoop(from: SocketChannel, started: Int) {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = ByteArrayOutputStream()
        runCatching {
            while (from.read(buffer) >= 0) {
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
        }
        // the socket closes when the app quits, that ends this loop; any other time mpv died under us
        if (closed || started != generation) return
        val where = position.value to (!paused.value && duration.value > 0)
        if (revive(started)) revived.tryEmit(where)
    }

    private fun handle(raw: String) {
        val message = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (message["event"]?.jsonPrimitive?.content) {
            "seek" -> seekPending = true
            "playback-restart" -> if (seekPending) {
                seekPending = false
                seekRevision.value++
            }
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
