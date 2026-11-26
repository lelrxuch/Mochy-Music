package com.mochyy.desktop.playback

import com.mochyy.desktop.text.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import java.util.Locale

/*
 * Audio Lab (eq/ and ui/screens/audiolab in the app): the ten-band equaliser with the app's own
 * presets, reverb, 8D audio, crossfeed, mono and speed. Android runs its own audio processors;
 * here mpv runs ffmpeg's filters, built into one chain from these settings.
 */

/** the app's ten bands, an octave apart, each a peaking filter of Q 1.41 (EqPresets in AudioLabStore.kt) */
val EqFrequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
private const val OctaveQ = 1.41
const val EqMaxDb = 12.0

@Serializable
data class EqPreset(val id: String, val name: String, val gains: List<Double>)

/** the app's built-in presets, the same numbers */
val EqPresets = listOf(
    EqPreset("flat", "Flat", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqPreset("acoustic", "Acoustic", listOf(4.5, 4.5, 3.5, 1.0, 1.5, 1.5, 3.0, 3.5, 3.0, 1.5)),
    EqPreset("bass_boost", "Bass Booster", listOf(5.5, 4.5, 3.5, 2.5, 1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqPreset("bass_cut", "Bass Reducer", listOf(-5.5, -4.5, -3.5, -2.5, -1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqPreset("classical", "Classical", listOf(4.5, 3.5, 3.0, 2.5, -1.5, -1.5, 0.0, 2.0, 3.0, 3.5)),
    EqPreset("dance", "Dance", listOf(3.5, 6.5, 5.0, 0.0, 2.0, 3.5, 5.0, 4.5, 3.5, 0.0)),
    EqPreset("deep", "Deep", listOf(5.0, 3.5, 1.5, 1.0, 3.0, 2.5, 1.5, -2.0, -3.5, -4.5)),
    EqPreset("electronic", "Electronic", listOf(4.5, 4.0, 1.5, 0.0, -2.0, 2.0, 1.0, 1.5, 4.5, 5.0)),
    EqPreset("hiphop", "Hip-Hop", listOf(5.0, 4.0, 1.5, 3.0, -1.0, -1.0, 1.5, -0.5, 2.0, 3.0)),
    EqPreset("jazz", "Jazz", listOf(4.0, 3.0, 1.5, 2.0, -1.5, -1.5, 0.0, 1.5, 3.0, 3.5)),
    EqPreset("loudness", "Loudness", listOf(6.0, 4.0, 0.0, 0.0, -2.0, 0.0, -1.0, -5.0, 5.0, 1.0)),
    EqPreset("lounge", "Lounge", listOf(-3.0, -1.5, -0.5, 1.5, 4.0, 2.5, 0.0, -1.5, 2.0, 1.0)),
    EqPreset("piano", "Piano", listOf(3.0, 2.0, 0.0, 2.5, 3.0, 1.5, 3.5, 4.5, 3.0, 3.5)),
    EqPreset("pop", "Pop", listOf(-1.5, -1.0, 0.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.5)),
    EqPreset("rnb", "R&B", listOf(2.5, 7.0, 5.5, 1.5, -2.5, -1.5, 2.5, 3.0, 3.0, 3.5)),
    EqPreset("rock", "Rock", listOf(5.0, 4.0, 3.0, 1.5, -0.5, -1.0, 0.5, 2.5, 3.5, 4.5)),
    EqPreset("small_speakers", "Small Speakers", listOf(5.5, 4.0, 3.5, 2.5, 1.0, 0.0, -1.0, -2.0, -3.5, -4.0)),
    EqPreset("spoken", "Spoken Word", listOf(-3.5, -0.5, 0.0, 0.5, 3.5, 4.5, 5.0, 4.0, 2.5, 0.0)),
    EqPreset("treble_boost", "Treble Booster", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 3.5, 4.5, 5.5)),
    EqPreset("treble_cut", "Treble Reducer", listOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.5, -3.5, -4.5, -5.5)),
    EqPreset("vocal", "Vocal Booster", listOf(-1.5, -3.0, -3.0, 1.5, 3.5, 3.5, 3.0, 1.5, 0.0, -1.5)),
)

/** reverb rooms, as ffmpeg echoes: delays in ms and how much of each comes back */
enum class Reverb(val labelKey: String, val delays: List<Int>, val decays: List<Double>, val wet: Double) {
    Off("desktop_off", emptyList(), emptyList(), 0.0),
    Room("desktop_reverb_room", listOf(23, 37, 53), listOf(0.30, 0.22, 0.15), 0.8),
    Hall("desktop_reverb_hall", listOf(60, 95, 140, 190), listOf(0.35, 0.28, 0.20, 0.13), 0.75),
    Cathedral("desktop_reverb_cathedral", listOf(110, 180, 260, 350, 460), listOf(0.40, 0.33, 0.26, 0.19, 0.13), 0.7),
}

@Serializable
data class AudioLabSettings(
    val eqEnabled: Boolean = false,
    val gains: List<Double> = List(EqFrequencies.size) { 0.0 },
    val presetId: String? = "flat",
    val reverb: Reverb = Reverb.Off,
    val eightD: Boolean = false,
    val crossfeed: Boolean = false,
    val mono: Boolean = false,
    val speed: Double = 1.0,
    val userPresets: List<EqPreset> = emptyList(),
) {
    fun presetNameError(name: String, exceptId: String? = null): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "desktop_preset_name_empty"
        val taken = EqPresets.any { it.name.equals(trimmed, ignoreCase = true) } ||
            userPresets.any { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }
        return if (taken) "desktop_preset_name_taken" else null
    }

    fun savePreset(name: String, epochMillis: Long): AudioLabSettings {
        if (presetNameError(name) != null) return this
        var time = epochMillis
        while (userPresets.any { it.id == "user_$time" }) time++
        val preset = EqPreset("user_$time", name.trim(), gains.toList())
        return copy(presetId = preset.id, gains = preset.gains, userPresets = userPresets + preset)
    }

    fun renamePreset(id: String, name: String): AudioLabSettings {
        if (presetNameError(name, id) != null) return this
        return copy(userPresets = userPresets.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun deletePreset(id: String): AudioLabSettings {
        if (userPresets.none { it.id == id }) return this
        return copy(presetId = presetId?.takeUnless { it == id }, userPresets = userPresets.filterNot { it.id == id })
    }

    fun applyPreset(preset: EqPreset) = copy(eqEnabled = true, presetId = preset.id, gains = preset.gains)

    fun editBand(band: Int, gain: Double): AudioLabSettings {
        if (eqEnabled && gains[band] == gain) return this
        val curve = gains.toMutableList().also { it[band] = gain }
        val picked = userPresets.firstOrNull { it.id == presetId }
        return copy(
            eqEnabled = true, gains = curve, presetId = picked?.id,
            userPresets = if (picked == null) userPresets else userPresets.map { if (it.id == picked.id) it.copy(gains = curve) else it },
        )
    }
}

/**
 * The settings as one mpv audio filter: ffmpeg filters in a lavfi chain. Boosts are paid for with a
 * preamp of minus the biggest one (the app's auto preamp), so a boosted band can't clip. Empty when
 * everything is off, which takes every filter away.
 */
fun audioFilters(lab: AudioLabSettings): String {
    val chain = buildList {
        if (lab.eqEnabled && lab.gains.any { it != 0.0 }) {
            val headroom = lab.gains.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
            if (headroom > 0) add("volume=${oneDecimal(-headroom)}dB")
            lab.gains.forEachIndexed { i, gain ->
                if (gain != 0.0) add("equalizer=f=${EqFrequencies[i]}:t=q:w=$OctaveQ:g=${oneDecimal(gain)}")
            }
        }
        if (lab.reverb != Reverb.Off) {
            add("aecho=${lab.reverb.wet}:0.9:${lab.reverb.delays.joinToString("|")}:${lab.reverb.decays.joinToString("|")}")
        }
        // headphone crossfeed: a little of each side bleeds to the other, like speakers in a room
        if (lab.crossfeed) add("crossfeed=strength=0.25")
        if (lab.mono) add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1")
        // 8D: the sound circles the head, a slow auto-pan
        if (lab.eightD) add("apulsator=hz=0.08:amount=0.85")
    }
    return if (chain.isEmpty()) "" else "lavfi=[${chain.joinToString(",")}]"
}

fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)
