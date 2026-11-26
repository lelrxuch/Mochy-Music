package com.mochyy.desktop.integrations

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.SocketChannel
import java.nio.file.Path

internal class DiscordIpc : AutoCloseable {
    private val socket = SocketChannel.open(StandardProtocolFamily.UNIX).apply { configureBlocking(false) }
    private val header = ByteBuffer.allocate(8).order(LITTLE_ENDIAN)
    private var body: ByteBuffer? = null

    suspend fun connect(path: Path) = withTimeout(1_000) {
        if (!socket.connect(UnixDomainSocketAddress.of(path))) {
            while (!socket.finishConnect()) delay(20)
        }
    }

    fun read(): Pair<Int, ByteArray>? {
        val payload = body ?: run {
            if (!fill(header)) return null
            val size = header.getInt(4)
            if (size !in 0..1_048_576) throw IOException("Invalid Discord frame length")
            ByteBuffer.allocate(size).also { body = it }
        }
        if (!fill(payload)) return null
        val opcode = header.getInt(0)
        header.clear()
        body = null
        return opcode to payload.array()
    }

    private fun fill(buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            when (socket.read(buffer)) {
                -1 -> throw EOFException()
                0 -> return false
            }
        }
        return true
    }

    suspend fun write(opcode: Int, payload: ByteArray) = withTimeout(2_000) {
        val frame = ByteBuffer.allocate(8 + payload.size).order(LITTLE_ENDIAN)
        frame.putInt(opcode).putInt(payload.size).put(payload).flip()
        while (frame.hasRemaining()) {
            if (socket.write(frame) == 0) delay(20)
        }
    }

    suspend fun write(opcode: Int, payload: JsonObject) = write(opcode, payload.toString().encodeToByteArray())

    override fun close() = socket.close()
}

internal fun discordFrameJson(payload: ByteArray): JsonObject =
    Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: throw IOException("Invalid Discord frame payload")
