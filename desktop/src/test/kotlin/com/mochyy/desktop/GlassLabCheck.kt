package com.mochyy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * The glass alone, over a busy backdrop (stripes of colour, big type, covers), so the bend, the
 * colour split, the blur and the rim light can be judged in a picture. Writes $MOCHY_SHOTS/0-glass.png.
 */
class GlassLabCheck {
    @Test
    fun glass() {
        val dir = System.getenv("MOCHY_SHOTS")?.let(::File) ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(1280, 840, Density(1f)) {
            MochyTheme {
                val backdrop = remember { GlassBackdrop() }
                CompositionLocalProvider(LocalGlass provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().glassSource(backdrop)) {
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.linearGradient(listOf(Color(0xFF1B1F6B), Color(0xFFB0306E), Color(0xFFF2A541), Color(0xFF1C7C6C))),
                                ),
                            )
                            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) {
                                    Text("Mochy liquid glass", color = Color.White, fontSize = 92.sp, fontWeight = FontWeight.Black)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    listOf("FGBhQbmPwH8", "5NV6Rdv1a3I", "gAjR4_CbPpQ", "a5uQMwRMHcs", "K0HSD_i2DvA", "yydNF8tuVmU").forEach {
                                        Artwork("https://i.ytimg.com/vi/$it/hqdefault.jpg", Modifier.size(180.dp))
                                    }
                                }
                            }
                            // thin stripes show the bend best
                            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                                repeat(12) { i -> Box(Modifier.fillMaxWidth().height(4.dp).background(if (i % 2 == 0) Color.White else Color.Black)) }
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).width(560.dp).height(64.dp).liquidGlass(CircleShape))
                        Box(Modifier.align(Alignment.Center).size(200.dp).liquidGlass(CircleShape))
                        Box(Modifier.align(Alignment.TopEnd).offset((-40).dp, 60.dp).width(360.dp).height(140.dp).liquidGlass(RoundedCornerShape(28.dp)))
                        Box(Modifier.align(Alignment.CenterStart).offset(60.dp, 0.dp).size(120.dp, 220.dp).liquidGlass(RoundedCornerShape(40.dp)))
                    }
                }
            }
        }
        var time = 0L
        var image = scene.render(time)
        repeat(30) {
            Thread.sleep(200)
            time += 200_000_000
            image = scene.render(time)
        }
        File(dir, "0-glass.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
