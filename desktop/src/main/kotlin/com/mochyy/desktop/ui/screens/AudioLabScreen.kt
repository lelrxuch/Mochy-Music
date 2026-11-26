package com.mochyy.desktop.ui.screens

import com.mochyy.desktop.*
import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
                        update(app.library.data.audioLab.editBand(band, gain))
                    }
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EqPresets.forEach { preset ->
                            Chip(preset.name, lab.eqEnabled && lab.presetId == preset.id) {
                                update(lab.applyPreset(preset))
                            }
                        }
                        lab.userPresets.forEach { preset ->
                            ContextMenuArea(items = {
                                listOf(
                                    ContextMenuItem(str("desktop_rename")) { app.showSheet { PresetNameSheet(preset) } },
                                    ContextMenuItem(str("delete")) {
                                        app.showSheet {
                                            ConfirmSheet(str("desktop_delete_title", preset.name), str("desktop_delete_body"), str("delete")) {
                                                app.library.setAudioLab(app.library.data.audioLab.deletePreset(preset.id))
                                            }
                                        }
                                    },
                                )
                            }) {
                                Chip(preset.name, lab.eqEnabled && lab.presetId == preset.id) { update(lab.applyPreset(preset)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PillButton(str("desktop_save_as_preset"), { app.showSheet { PresetNameSheet() } }, light = false)
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
                            Chip("${oneDecimal(speed)}×", lab.speed == speed) { update(lab.copy(speed = speed)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetNameSheet(preset: EqPreset? = null) {
    val library = LocalApp.current.library
    NameSheet(
        title = if (preset == null) str("desktop_save_as_preset") else str("desktop_rename"),
        confirm = str("save"),
        initial = preset?.name.orEmpty(),
        placeholder = str("desktop_preset_name"),
        validate = { name -> library.data.audioLab.presetNameError(name, preset?.id)?.let { str(it) } },
    ) { name ->
        val lab = library.data.audioLab
        library.setAudioLab(if (preset == null) lab.savePreset(name, System.currentTimeMillis()) else lab.renamePreset(preset.id, name))
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
                Text((if (gain > 0) "+" else "") + oneDecimal(gain), color = Dim, style = MaterialTheme.typography.labelSmall)
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
