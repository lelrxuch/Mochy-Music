package com.mochyy.desktop.integrations

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordPresenceCheck {
    private val applicationId = "123"
    private val song = SongItem(
        id = "song", title = "Canción", artists = listOf(Artist("First", null), Artist("Second", null)),
        album = Album("Album", "album"), thumbnail = "https://example.com/cover.jpg",
    )
    private val playback = DiscordPlayback(song, 10.0, 120.0, 1_700_000_000_000L)

    @Test
    fun publishesSeeksClearsAndAnswersPing() = withRuntimeDir { dir ->
        val path = dir.resolve("app/com.discordapp.Discord/discord-ipc-4")
        Files.createDirectories(path.parent)
        FakeDiscord(path).use { server ->
            DiscordPresence(this, dir).use { presence ->
                presence.desired.value = DiscordPresenceState(applicationId, song.id, playback)
                server.accept().use { connection ->
                    connection.handshake(applicationId)
                    val first = connection.activity()
                    val firstAt = System.nanoTime()
                    assertSong(first.activity.jsonObject, playback)
                    assertEquals(applicationId, presence.connectedId.value)

                    presence.desired.value = presence.desired.value.copy(playback = playback.copy(position = 60.0))
                    val ping = "ping".encodeToByteArray()
                    connection.send(3, ping)
                    val (opcode, bytes) = connection.read()
                    assertEquals(4, opcode)
                    assertContentEquals(ping, bytes)

                    val seek = connection.activity()
                    assertTrue(System.nanoTime() - firstAt >= 950_000_000L, "seek update went out too soon")
                    assertNotEquals(first.nonce, seek.nonce)
                    assertSong(seek.activity.jsonObject, playback.copy(position = 60.0))

                    val shortSeek = playback.copy(position = 60.1, seekRevision = 1)
                    presence.desired.value = presence.desired.value.copy(playback = shortSeek)
                    assertSong(connection.activity().activity.jsonObject, shortSeek)

                    presence.desired.value = presence.desired.value.copy(playback = null)
                    assertEquals(JsonNull, connection.activity().activity)
                }
            }
        }
    }

    @Test
    fun reconnectsOnTheNextSongAndOnTheRetryTimer() = withRuntimeDir { dir ->
        val path = dir.resolve("snap.discord/discord-ipc-2")
        Files.createDirectories(path.parent)
        DiscordPresence(this, dir, retryMs = 2_000).use { presence ->
            presence.desired.value = DiscordPresenceState(applicationId, song.id, playback)
            FakeDiscord(path).use { server ->
                server.accept().use { connection ->
                    connection.handshake(applicationId)
                    assertSong(connection.activity().activity.jsonObject, playback)
                    connection.send(2, "{}".encodeToByteArray())
                }
            }
            withTimeout(2_000) { while (presence.connectedId.value != null) delay(20) }

            val next = playback.copy(song = song.copy(id = "next"))
            FakeDiscord(path).use { server ->
                presence.desired.value = DiscordPresenceState(applicationId, next.song.id, next)
                server.accept(timeout = 1_500).use { connection ->
                    connection.handshake(applicationId)
                    assertSong(connection.activity().activity.jsonObject, next)
                }
            }
            withTimeout(2_000) { while (presence.connectedId.value != null) delay(20) }

            FakeDiscord(path).use { server ->
                server.accept().use { connection ->
                    connection.handshake(applicationId)
                    assertSong(connection.activity().activity.jsonObject, next)
                    presence.desired.value = DiscordPresenceState()
                    assertEquals(JsonNull, connection.activity().activity)
                }
            }
        }
    }

    @Test
    fun noSocketIsQuiet() = withRuntimeDir { dir ->
        DiscordPresence(this, dir, retryMs = 100).use { presence ->
            presence.desired.value = DiscordPresenceState(applicationId, song.id, playback)
            delay(350)
            assertNull(presence.connectedId.value)
        }
    }

    private fun assertSong(activity: JsonObject, expected: DiscordPlayback) {
        assertEquals(2, activity.getValue("type").jsonPrimitive.int)
        assertEquals("Canción", activity.getValue("details").jsonPrimitive.content)
        assertEquals("First, Second", activity.getValue("state").jsonPrimitive.content)
        val timestamps = activity.getValue("timestamps").jsonObject
        assertEquals(expected.start / 1_000, timestamps.getValue("start").jsonPrimitive.long)
        assertEquals(expected.end / 1_000, timestamps.getValue("end").jsonPrimitive.long)
        val assets = activity.getValue("assets").jsonObject
        assertEquals(song.thumbnail, assets.getValue("large_image").jsonPrimitive.content)
        assertEquals("Album", assets.getValue("large_text").jsonPrimitive.content)
        val button = activity.getValue("buttons").jsonArray.single().jsonObject
        assertEquals("Listen on YouTube Music", button.getValue("label").jsonPrimitive.content)
        assertEquals("https://music.youtube.com/watch?v=${expected.song.id}", button.getValue("url").jsonPrimitive.content)
    }

    private fun withRuntimeDir(test: suspend kotlinx.coroutines.CoroutineScope.(Path) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("mochy-rpc")
        try {
            test(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class FakeDiscord(private val path: Path) : AutoCloseable {
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))

        suspend fun accept(timeout: Long = 5_000) = Connection(withTimeout(timeout) { runInterruptible { server.accept() } })

        override fun close() {
            server.close()
            Files.deleteIfExists(path)
        }
    }

    private data class Update(val nonce: UUID, val activity: kotlinx.serialization.json.JsonElement)

    private class Connection(private val socket: SocketChannel) : AutoCloseable {
        suspend fun send(opcode: Int, bytes: ByteArray) = withTimeout(5_000) {
            runInterruptible {
                val frame = ByteBuffer.allocate(8 + bytes.size).order(LITTLE_ENDIAN)
                frame.putInt(opcode).putInt(bytes.size).put(bytes).flip()
                while (frame.hasRemaining()) socket.write(frame)
            }
        }

        suspend fun read(): Pair<Int, ByteArray> = withTimeout(5_000) {
            runInterruptible {
                fun fill(buffer: ByteBuffer) {
                    while (buffer.hasRemaining()) check(socket.read(buffer) >= 0) { "Discord peer disconnected" }
                    buffer.flip()
                }
                val header = ByteBuffer.allocate(8).order(LITTLE_ENDIAN)
                fill(header)
                val opcode = header.int
                val body = ByteBuffer.allocate(header.int)
                fill(body)
                opcode to body.array()
            }
        }

        suspend fun handshake(applicationId: String) {
            val (opcode, bytes) = read()
            assertEquals(0, opcode)
            val frame = discordFrameJson(bytes)
            assertEquals(1, frame.getValue("v").jsonPrimitive.int)
            assertEquals(applicationId, frame.getValue("client_id").jsonPrimitive.content)
            send(1, """{"cmd":"DISPATCH","evt":"READY"}""".encodeToByteArray())
        }

        suspend fun activity(): Update {
            val (opcode, bytes) = read()
            assertEquals(1, opcode)
            val command = discordFrameJson(bytes)
            assertEquals("SET_ACTIVITY", command.getValue("cmd").jsonPrimitive.content)
            val args = command.getValue("args").jsonObject
            assertEquals(ProcessHandle.current().pid(), args.getValue("pid").jsonPrimitive.long)
            return Update(UUID.fromString(command.getValue("nonce").jsonPrimitive.content), args.getValue("activity"))
        }

        override fun close() = socket.close()
    }
}
