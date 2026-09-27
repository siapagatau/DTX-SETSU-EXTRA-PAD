package com.kendang.realpads

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SV_WIDTH = 260.dp
private val SV_HEIGHT = 170.dp
private val HUE_WIDTH = 260.dp
private val HUE_HEIGHT = 28.dp

// Dialog "Set Tema": custom warna tema (tap/geser di kotak buat saturasi & terang -
// termasuk gampang dapetin warna lembut/pastel di kiri atas, dan di bar pelangi buat
// pilih warna dasarnya/hue; kode hex tetep ada buat yang mau input manual/presisi) DAN
// background gambar buat area pad (pilih dari galeri + atur transparansinya). Warna
// tetap lewat tombol PAKAI/BATAL seperti biasa, tapi background gambar & transparansi
// langsung diterapkan begitu diubah (gak nunggu PAKAI) - konsisten sama slider volume
// di tempat lain yang juga live, dan biar user langsung liat hasilnya di belakang
// dialog ini.
@Composable
fun ColorPickerDialog(
    initial: Color,
    backgroundImage: ImageBitmap?,
    backgroundOpacity: Float,
    padOpacity: Float,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onPadOpacityChange: (Float) -> Unit
) {
    val initialHsv = remember {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), hsv)
        hsv
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }       // 0f..360f
    var sat by remember { mutableStateOf(initialHsv[1]) }       // 0f..1f
    var brightness by remember { mutableStateOf(initialHsv[2]) } // 0f..1f
    var hexText by remember { mutableStateOf(colorToHex(initial)) }

    fun current(): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, brightness)))

    fun syncHexFromHsv() {
        hexText = colorToHex(current())
    }

    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Warna Tema", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp)
            ) {

                // Kotak saturasi x kecerahan. Kiri = pucat/lembut, kanan = pekat.
                // Atas = terang, bawah = gelap.
                Box(
                    modifier = Modifier
                        .size(SV_WIDTH, SV_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .background(hueColor)
                        .pointerInput(hue) {
                            fun updateFromOffset(offset: Offset) {
                                sat = (offset.x / size.width).coerceIn(0f, 1f)
                                brightness = (1f - offset.y / size.height).coerceIn(0f, 1f)
                                syncHexFromHsv()
                            }
                            detectTapGestures(onPress = { updateFromOffset(it) })
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                brightness = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                syncHexFromHsv()
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    )
                    // indikator posisi
                    Box(
                        modifier = Modifier
                            .offset(x = SV_WIDTH * sat - 9.dp, y = SV_HEIGHT * (1f - brightness) - 9.dp)
                            .size(18.dp)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Bar pelangi buat pilih hue
                Box(
                    modifier = Modifier
                        .size(HUE_WIDTH, HUE_HEIGHT)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                (0..360 step 60).map { h -> Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f))) }
                            )
                        )
                        .pointerInput(Unit) {
                            fun updateHue(offset: Offset) {
                                hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                                syncHexFromHsv()
                            }
                            detectTapGestures(onPress = { updateHue(it) })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                                syncHexFromHsv()
                            }
                        }
                ) {
                    // indikator posisi hue
                    Box(
                        modifier = Modifier
                            .offset(x = HUE_WIDTH * (hue / 360f) - 3.dp)
                            .width(6.dp)
                            .fillMaxHeight()
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .border(1.dp, Color.Black, RoundedCornerShape(3.dp))
                    )
                }

                Spacer(Modifier.height(14.dp))

                // preview + hex, buat yang mau input manual/presisi
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(SV_WIDTH)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(current())
                            .border(1.dp, PadLineColor, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { input ->
                            hexText = input
                            val parsed = parseHexColor(input)
                            if (parsed != null) {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed.toArgb(), hsv)
                                hue = hsv[0]; sat = hsv[1]; brightness = hsv[2]
                            }
                        },
                        label = { Text("mis. C97A5F", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = PadLineColor, modifier = Modifier.width(SV_WIDTH))
                Spacer(Modifier.height(14.dp))

                // Background gambar buat area pad, terpisah dari warna tema di atas.
                Text(
                    "Background Pad",
                    color = PadTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.width(SV_WIDTH)
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(SV_WIDTH)) {
                    // Thumbnail preview - nunjukin gambar yang lagi kepasang, atau ikon
                    // placeholder kalau belum ada background custom.
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CtrlPanelColor)
                            .border(1.dp, PadLineColor, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (backgroundImage != null) {
                            Image(
                                bitmap = backgroundImage,
                                contentDescription = "Preview background pad",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onPickBackground,
                        colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
                    ) {
                        Text(
                            if (backgroundImage != null) "GANTI" else "PILIH GAMBAR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (backgroundImage != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = onClearBackground) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Hapus background pad",
                                tint = TextDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Slider transparansi cuma muncul kalau backgroundnya ada - gak ada
                // gunanya diatur transparansinya kalau belum ada gambar apa-apa.
                if (backgroundImage != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(SV_WIDTH)) {
                        Text("Transparansi", color = TextDim, fontSize = 11.sp, modifier = Modifier.width(84.dp))
                        Slider(
                            value = backgroundOpacity,
                            onValueChange = onOpacityChange,
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = current(),
                                activeTrackColor = current(),
                                inactiveTrackColor = CtrlPanelColor
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(backgroundOpacity * 100).toInt()}%",
                            color = TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    // Transparansi PAD-nya sendiri - ini yang bikin gambar background
                    // keliatan TEMBUS di area pad, bukan cuma di celah antar pad.
                    // Cuma relevan kalau ada background gambar, sama kayak slider di atas.
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(SV_WIDTH)) {
                        Text("Transparansi Pad", color = TextDim, fontSize = 11.sp, modifier = Modifier.width(84.dp))
                        Slider(
                            value = padOpacity,
                            onValueChange = onPadOpacityChange,
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = current(),
                                activeTrackColor = current(),
                                inactiveTrackColor = CtrlPanelColor
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(padOpacity * 100).toInt()}%",
                            color = TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(current()) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = current(),
                    contentColor = bestTextColorFor(current())
                )
            ) {
                Text("PAKAI", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("BATAL", fontSize = 12.sp)
            }
        }
    )
}
