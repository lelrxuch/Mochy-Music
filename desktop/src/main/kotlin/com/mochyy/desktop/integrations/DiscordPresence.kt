package com.mochyy.desktop.integrations

import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

internal data class DiscordPlayback(
    val song: SongItem,
    val position: Double,
    val duration: Double,
    val sampledAt: Long = System.currentTimeMillis(),
    val seekRevision: Long = 0,
) {
    val start = sampledAt - (position * 1_000).toLong()
    val end = start + (duration * 1_000).toLong()

    fun activity() = buildJsonObject {
        put("type", 2)
        put("details", song.title)
        put("state", song.artists.joinToString { it.name })
        putJsonObject("timestamps") {
            put("start", start / 1_000)
            put("end", end / 1_000)
        }
        putJsonObject("assets") {
            if (song.thumbnail.startsWith("https://")) put("large_image", song.thumbnail)
            put("large_text", song.album?.name ?: song.title)
        }
        putJsonArray("buttons") {
            add(buildJsonObject {
                put("label", "Listen on YouTube Music")
                put("url", song.shareLink)
            })
        }
    }
}

internal data class DiscordPresenceState(
    val applicationId: String = "",
    val songId: String? = null,
    val playback: DiscordPlayback? = null,
)

internal class DiscordPresence(
    scope: CoroutineScope,
    runtimeDir: Path = Path.of(System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: "/tmp"),
    private val retryMs: Long = 30_000,
) : AutoCloseable {
    private val paths = listOf(runtimeDir, runtimeDir.resolve("app/com.discordapp.Discord"), runtimeDir.resolve("snap.discord"))
        .flatMap { dir -> (0..9).map { dir.resolve("discord-ipc-$it") } }
    val desired = MutableStateFlow(DiscordPresenceState())
    private val ready = MutableStateFlow<String?>(null)
    val connectedId = ready.asStateFlow()
    private var lastUpdate = System.nanoTime() - 1_000_000_000L

    private val job = scope.launch(Dispatchers.IO) {
        var lastAttempt: Pair<String, String?>? = null
        var retryAt = 0L
        while (isActive) {
            val state = desired.value
            val id = state.applicationId
            val key = id to state.songId
            if (id.isEmpty()) {
                lastAttempt = null
            } else if (key != lastAttempt || System.nanoTime() >= retryAt) {
                val sessionSong = attempt { runSession(id) }
                lastAttempt = id to (sessionSong?.id ?: state.songId)
                retryAt = System.nanoTime() + retryMs * 1_000_000
            }
            delay(if (id.isEmpty()) 500 else 100)
        }
    }

    private suspend fun runSession(id: String): SessionSong? {
        for (path in paths) {
            val sessionSong = attempt {
                DiscordIpc().use { ipc ->
                    ipc.connect(path)
                    ipc.write(0, buildJsonObject { put("v", 1); put("client_id", id) })
                    withTimeout(2_000) {
                        while (event(ipc, ipc.read()) != "READY") delay(50)
                    }
                    ready.value = id
                    try {
                        publish(ipc, id)
                    } finally {
                        ready.value = null
                    }
                }
            }
            if (sessionSong != null) return sessionSong
            if (desired.value.applicationId != id) return null
        }
        return null
    }

    private data class SessionSong(val id: String?)

    private suspend fun publish(ipc: DiscordIpc, id: String): SessionSong {
        var sent = false
        var previous: DiscordPlayback? = null
        var songId = desired.value.songId
        attempt {
            while (true) {
                val state = desired.value
                songId = state.songId
                val leaving = state.applicationId != id
                val playing = state.playback.takeUnless { leaving }
                if (leaving && previous == null) break

                // Ignore normal position ticks; a seek changes the inferred start time.
                val old = previous
                val changed = !sent || if (old == null || playing == null) old != playing else
                    old.song != playing.song || old.duration != playing.duration ||
                        old.seekRevision != playing.seekRevision || abs(old.start - playing.start) >= 750
                if (changed && System.nanoTime() - lastUpdate >= 1_000_000_000L) {
                    setActivity(ipc, playing?.activity())
                    sent = true
                    previous = playing
                    if (leaving) break
                }

                event(ipc, ipc.read())
                delay(100)
            }
        }
        return SessionSong(songId)
    }

    private suspend fun setActivity(ipc: DiscordIpc, activity: JsonObject?) {
        try {
            ipc.write(1, buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                putJsonObject("args") {
                    put("pid", ProcessHandle.current().pid())
                    put("activity", activity ?: JsonNull)
                }
                put("nonce", UUID.randomUUID().toString())
            })
        } finally {
            lastUpdate = System.nanoTime()
        }
    }

    private suspend fun event(ipc: DiscordIpc, frame: Pair<Int, ByteArray>?): String? {
        val (opcode, payload) = frame ?: return null
        return when (opcode) {
            1 -> (discordFrameJson(payload)["evt"] as? JsonPrimitive)?.content
            2 -> throw IOException("Discord closed the connection")
            3 -> { ipc.write(4, payload); null }
            else -> null
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (_: TimeoutCancellationException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun close() = job.cancel()
}
