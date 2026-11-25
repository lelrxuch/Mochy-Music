package com.mochyy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

class EqPreset(val id: String, val name: String, val gains: List<Double>)

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
)

/**
 * The settings as one mpv audio filter: ffmpeg filters in a lavfi chain. Boosts are paid for with a
 * preamp of minus the biggest one (the app's auto preamp), so a boosted band can't clip. Empty when
 * everything is off, which takes every filter away.
 */
fun audioFilters(lab: AudioLabSettings): String {
    val chain = buildList {
        if (lab.eqEnabled && lab.gains.any { it != 0.0 }) {
            val headroom = lab.gains.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
            if (headroom > 0) add("volume=${num(-headroom)}dB")
            lab.gains.forEachIndexed { i, gain ->
                if (gain != 0.0) add("equalizer=f=${EqFrequencies[i]}:t=q:w=$OctaveQ:g=${num(gain)}")
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

private fun num(value: Double) = String.format(Locale.US, "%.1f", value)

// ---- the page ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioLabScreen() {
    val app = LocalApp.current
    val lab = app.library.data.audioLab
    val update: (AudioLabSettings) -> Unit = { app.library.setAudioLab(it); app.player.applyAudioLab() }
    val list = rememberLazyListState()
    ScrollPage(list) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = 56.dp, bottom = ChromeClearance)) {
            item { PageTitle("Audio Lab") }
            item {
                LabCard(str("desktop_equalizer")) {
                    OnOff(lab.eqEnabled) { update(lab.copy(eqEnabled = it)) }
                    Spacer(Modifier.height(16.dp))
                    EqBands(lab.gains, enabled = lab.eqEnabled) { band, gain ->
                        update(lab.copy(eqEnabled = true, presetId = null, gains = lab.gains.toMutableList().also { it[band] = gain }))
                    }
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EqPresets.forEach { preset ->
                            Chip(preset.name, lab.eqEnabled && lab.presetId == preset.id) {
                                update(lab.copy(eqEnabled = true, presetId = preset.id, gains = preset.gains))
                            }
                        }
                    }
                }
            }
            item {
                LabCard(str("desktop_reverb")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Reverb.entries.forEach { room -> Chip(str(room.labelKey), lab.reverb == room) { update(lab.copy(reverb = room)) } }
                    }
                }
            }
            item {
                LabCard(str("desktop_effects")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(str("desktop_8d"), lab.eightD) { update(lab.copy(eightD = !lab.eightD)) }
                        Chip(str("desktop_crossfeed"), lab.crossfeed) { update(lab.copy(crossfeed = !lab.crossfeed)) }
                        Chip(str("desktop_mono"), lab.mono) { update(lab.copy(mono = !lab.mono)) }
                    }
                }
            }
            item {
                LabCard(str("desktop_speed")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75, 0.9, 1.0, 1.1, 1.25, 1.5).forEach { speed ->
                            Chip("${num(speed)}×", lab.speed == speed) { update(lab.copy(speed = speed)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Raised).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(str("desktop_on"), on) { onChange(true) }
        Chip(str("desktop_off"), !on) { onChange(false) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.press3D(maxTilt = 0f, pressedScale = 0.94f).clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (on) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** the ten bands as vertical sliders, from -12 to +12 dB; drag or click to set */
@Composable
private fun EqBands(gains: List<Double>, enabled: Boolean, onChange: (Int, Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        gains.forEachIndexed { band, gain ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((if (gain > 0) "+" else "") + num(gain), color = Dim, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                BandSlider(gain, enabled) { onChange(band, it) }
                Spacer(Modifier.height(6.dp))
                val hz = EqFrequencies[band]
                Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", color = Dim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BandSlider(gain: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    fun gainAt(y: Float, height: Float) = (((1f - (y / height).coerceIn(0f, 1f)) * 2 - 1) * EqMaxDb).let { Math.round(it * 2) / 2.0 }
    Box(
        Modifier.width(36.dp).height(180.dp)
            .pointerInput(Unit) { detectTapGestures { onChange(gainAt(it.y, size.height.toFloat())) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); onChange(gainAt(change.position.y, size.height.toFloat())) } }
            .drawBehind {
                val track = 6.dp.toPx()
                val x = size.width / 2 - track / 2
                drawRoundRect(Color.White.copy(alpha = 0.1f), Offset(x, 0f), Size(track, size.height), CornerRadius(track / 2))
                val mid = size.height / 2
                val y = mid - (gain / EqMaxDb).toFloat() * mid
                val fill = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
                drawRoundRect(fill, Offset(x, minOf(y, mid)), Size(track, kotlin.math.abs(mid - y)), CornerRadius(track / 2))
                drawCircle(fill, radius = 9.dp.toPx(), center = Offset(size.width / 2, y))
            },
    )
}
