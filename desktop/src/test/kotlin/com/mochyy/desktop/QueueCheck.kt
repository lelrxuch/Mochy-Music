package com.mochyy.desktop

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

class QueueCheck {
    @Test
    fun theQueueComesBackAfterARestart() {
        val dir = Files.createTempDirectory("mochy-queue").toFile()
        try {
            val file = File(dir, "queue.json")
            val songs = listOf("one", "two", "three").map {
                SongItem(id = it, title = it, artists = listOf(Artist(name = "a", id = null)), thumbnail = "")
            }
            val list = songs.joinToString(",") { Json.encodeToString(SongItem.serializer(), it) }
            file.writeText("""{"songs":[$list],"index":1,"position":42.0,"title":"Road trip"}""")
            val player = Player(CoroutineScope(SupervisorJob() + Dispatchers.Default), LibraryStore(File(dir, "library.json")), file)
            assertEquals("two", player.current?.id)
            assertEquals("Road trip", player.queueTitle)
            player.close()
            // closing writes it back, still on the same song
            assertEquals(true, file.readText().contains("\\"index\\":1"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
