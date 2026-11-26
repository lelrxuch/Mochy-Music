package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioLabCheck {
    @Test
    fun nothingOnMeansNoFilters() {
        assertEquals("", audioFilters(AudioLabSettings()))
        // a flat curve switched on still adds nothing
        assertEquals("", audioFilters(AudioLabSettings(eqEnabled = true)))
    }

    @Test
    fun boostsArePaidForWithPreamp() {
        val rock = EqPresets.first { it.id == "rock" }
        val chain = audioFilters(AudioLabSettings(eqEnabled = true, gains = rock.gains))
        assertTrue(chain.startsWith("lavfi=[volume=-5.0dB,equalizer=f=31:t=q:w=1.41:g=5.0"), chain)
        assertTrue(chain.endsWith("equalizer=f=16000:t=q:w=1.41:g=4.5]"), chain)
    }

    @Test
    fun effectsJoinOneChain() {
        val chain = audioFilters(AudioLabSettings(reverb = Reverb.Room, eightD = true, mono = true))
        assertEquals(
            "lavfi=[aecho=0.8:0.9:23|37|53:0.3|0.22|0.15,pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,apulsator=hz=0.08:amount=0.85]",
            chain,
        )
    }

    @Test
    fun everyPresetHasTenBands() {
        assertTrue(EqPresets.all { it.gains.size == EqFrequencies.size })
    }

    @Test
    fun savingPicksAnIndependentCopyOfTheCurve() {
        val curve = EqPresets.first { it.id == "rock" }.gains.toMutableList()
        val saved = AudioLabSettings(gains = curve).savePreset("  Headphones  ", 1234)
        val preset = saved.userPresets.single()
        assertEquals("user_1234", preset.id)
        assertEquals(preset.id, saved.presetId)
        assertEquals("Headphones", preset.name)
        assertEquals(curve, preset.gains)
        curve[0] = -12.0
        assertEquals(5.0, preset.gains[0])
        assertEquals(preset.gains, saved.gains)
        assertEquals(false, saved.eqEnabled)
    }

    @Test
    fun bandEditsFollowTheSelectedPreset() {
        val rock = EqPresets.first { it.id == "rock" }
        val saved = AudioLabSettings().applyPreset(rock).savePreset("Speakers", 1)
        val edited = saved.editBand(0, -2.0).editBand(1, 6.0)
        assertEquals(saved.presetId, edited.presetId)
        assertEquals(listOf(-2.0, 6.0) + rock.gains.drop(2), edited.gains)
        assertEquals(edited.gains, edited.userPresets.single().gains)
        assertEquals(rock.gains, saved.userPresets.single().gains)

        val custom = edited.applyPreset(rock).editBand(2, -7.0)
        assertNull(custom.presetId)
        assertEquals(-7.0, custom.gains[2])
        assertEquals(3.0, rock.gains[2])
        assertEquals(edited, custom.copy(eqEnabled = false).applyPreset(custom.userPresets.single()))
    }

    @Test
    fun renamingAndDeletingKeepTheCurrentSound() {
        val saved = AudioLabSettings().editBand(4, 3.0).savePreset("First", 10).savePreset("Second", 10)
        val (first, second) = saved.userPresets
        assertTrue(first.id != second.id)
        val renamed = saved.renamePreset(first.id, "  New name  ")
        assertEquals(listOf(first.copy(name = "New name"), second), renamed.userPresets)
        assertEquals(second.id, renamed.presetId)

        val kept = renamed.deletePreset(first.id)
        assertEquals(saved.copy(userPresets = listOf(second)), kept)
        val custom = kept.deletePreset(second.id)
        assertEquals(kept.copy(presetId = null, userPresets = emptyList()), custom)
        assertEquals(audioFilters(saved), audioFilters(custom))
        val flat = saved.applyPreset(EqPresets.first())
        assertEquals(flat, flat.deletePreset("flat"))
    }

    @Test
    fun namesMustBePresentAndUniqueWhenSavingOrRenaming() {
        val saved = AudioLabSettings().savePreset("Speakers", 1).savePreset("Car", 2)
        for (name in listOf("", "  ", " sPeAkErS ", " fLaT ")) {
            assertTrue(saved.presetNameError(name, "user_2") != null)
            assertEquals(saved, saved.savePreset(name, 3))
            assertEquals(saved, saved.renamePreset("user_2", name))
        }
        assertNull(saved.presetNameError(" CAR ", "user_2"))
        assertEquals("CAR", saved.renamePreset("user_2", " CAR ").userPresets.last().name)
    }

    @Test
    fun settingsWrittenBeforeUserPresetsStillLoad() {
        val old = """{"eqEnabled":true,"gains":[-1,2,0,0,0,0,0,0,0,0],"presetId":null,"reverb":"Hall","eightD":true,"crossfeed":true,"mono":true,"speed":1.25}"""
        val expected = AudioLabSettings(
            eqEnabled = true, gains = listOf(-1.0, 2.0) + List(8) { 0.0 }, presetId = null,
            reverb = Reverb.Hall, eightD = true, crossfeed = true, mono = true, speed = 1.25,
        )
        assertEquals(expected, Json.decodeFromString<AudioLabSettings>(old))
        assertEquals(AudioLabSettings(), Json.decodeFromString<AudioLabSettings>("{}"))
    }

    @Test
    fun presetsSurviveClosingAndReopeningTheLibrary() {
        val dir = Files.createTempDirectory("mochy-eq").toFile()
        try {
            val file = dir.resolve("library.db")
            val saved = AudioLabSettings(reverb = Reverb.Room).editBand(5, 2.0).savePreset("Car", 1)
            val edited = saved.savePreset("Headphones", 2).editBand(0, -4.5).renamePreset("user_2", "Desk")
            LibraryStore(file).use { it.setAudioLab(saved); it.setAudioLab(edited) }
            LibraryStore(file).use { assertEquals(edited, it.data.audioLab) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
