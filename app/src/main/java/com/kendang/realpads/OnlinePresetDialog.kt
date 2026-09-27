package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Dialog pertama begitu tombol "LOAD" ditekan: pilih mau load preset dari mana,
// dari penyimpanan HP (alur lama, lewat file picker) atau online dari repo
// GitHub bersama. Dipisah jadi dialog pilihan dulu (bukan langsung buka file
// picker kayak sebelumnya) karena sekarang ada 2 sumber.
@Composable
fun LoadSourceChooserDialog(
    preset: Int,
    onPickLocal: () -> Unit,
    onPickOnline: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Load Preset - P$preset", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Mau load suara pad dari mana?",
                    color = TextDim,
                    fontSize = 12.sp
                )
                SourceOptionRow(
                    icon = Icons.Filled.Smartphone,
                    title = "Dari HP (lokal)",
                    subtitle = "Pilih file .zip dari penyimpanan HP",
                    onClick = onPickLocal
                )
                SourceOptionRow(
                    icon = Icons.Filled.CloudDownload,
                    title = "Online dari GitHub",
                    subtitle = "Pilih preset dari repo bersama, butuh internet",
                    onClick = onPickOnline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("BATAL", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun SourceOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ThemeState.accentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = TextDim, fontSize = 11.sp)
        }
    }
}

// Status list preset online - dipakai OnlinePresetListDialog buat nentuin apa
// yang ditampilkan (loading / error / hasil).
sealed class OnlinePresetsUiState {
    data object Loading : OnlinePresetsUiState()
    data class Error(val message: String, val isRateLimit: Boolean = false) : OnlinePresetsUiState()
    data class Loaded(val presets: List<OnlinePresetRepo.OnlinePreset>) : OnlinePresetsUiState()
}

// Dialog kedua: daftar semua .zip yang ada di repo GitHub (hasil
// OnlinePresetRepo.listPresets(), pakai cache singkat 5 menit - lihat
// OnlinePresetRepo - jadi buka/tutup dialog ini gak nembak API GitHub
// berkali-kali; kalau ada yang baru aja upload preset baru ke repo dan mau
// lihat langsung, tinggal pencet ikon refresh yang MELEWATI cache). Tap 1
// item -> download lalu langsung diimport ke preset yang lagi aktif.
@Composable
fun OnlinePresetListDialog(
    preset: Int,
    state: OnlinePresetsUiState,
    downloadingName: String?,
    onRetry: () -> Unit,
    onPick: (OnlinePresetRepo.OnlinePreset) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Preset Online - P$preset",
                    color = PadTextColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Muat ulang daftar",
                        tint = TextDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .heightIn(min = 120.dp, max = 420.dp)
            ) {
                when (state) {
                    is OnlinePresetsUiState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = ThemeState.accentColor)
                            Spacer(Modifier.height(10.dp))
                            Text("Mengambil daftar preset...", color = TextDim, fontSize = 12.sp)
                        }
                    }
                    is OnlinePresetsUiState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(
                                if (state.isRateLimit) "Kena limit request GitHub." else "Gagal mengambil daftar preset online.",
                                color = PadTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                // Pesan rate limit dari formatRateLimitMessage() udah
                                // lengkap & enak dibaca sendiri (ada perkiraan jam boleh
                                // coba lagi), jadi ditampilin apa adanya - beda dengan
                                // error lain yang masih perlu dibungkus kalimat umum.
                                if (state.isRateLimit) state.message
                                else "Cek koneksi internet, atau coba lagi (${state.message})",
                                color = TextDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                    is OnlinePresetsUiState.Loaded -> {
                        if (state.presets.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = TextDim,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Belum ada file .zip preset di repo ini.",
                                    color = TextDim,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.presets) { p ->
                                    OnlinePresetRow(
                                        preset = p,
                                        isDownloading = downloadingName == p.name,
                                        downloadDisabled = downloadingName != null,
                                        onClick = { onPick(p) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun OnlinePresetRow(
    preset: OnlinePresetRepo.OnlinePreset,
    isDownloading: Boolean,
    downloadDisabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = !downloadDisabled, onClick = onClick)
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Buang ekstensi .zip biar nama presetnya keliatan bersih di daftar.
                preset.name.removeSuffix(".zip").removeSuffix(".ZIP"),
                color = if (downloadDisabled && !isDownloading) TextDim else PadTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(
                formatFileSize(preset.sizeBytes),
                color = TextDim,
                fontSize = 10.sp
            )
        }
        if (isDownloading) {
            CircularProgressIndicator(
                color = ThemeState.accentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = "Download & load ${preset.name}",
                tint = if (downloadDisabled) TextDim else ThemeState.accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    return if (kb < 1024) {
        "${kb.roundToInt()} KB"
    } else {
        val mb = kb / 1024.0
        "%.1f MB".format(mb)
    }
}
