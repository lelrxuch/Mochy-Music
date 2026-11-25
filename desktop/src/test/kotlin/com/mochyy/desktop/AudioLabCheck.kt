package com.mochyy.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
