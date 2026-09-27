package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Dialog pertama begitu tombol "SET SUARA" ditekan: pilih mau ambil suara pad
// dari mana, dari penyimpanan HP (alur lama, lewat file picker) atau online
// dari repo kumpulan WAV bersama di GitHub. Polanya sengaja disamakan persis
// sama LoadSourceChooserDialog di OnlinePresetDialog.kt biar konsisten.
@Composable
fun SetSoundSourceChooserDialog(
    pad: Int,
    onPickLocal: () -> Unit,
    onPickOnline: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Set Suara - PAD $pad", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Mau ambil suara pad dari mana?",
                    color = TextDim,
                    fontSize = 12.sp
                )
                SoundSourceOptionRow(
                    icon = Icons.Filled.Smartphone,
                    title = "Dari HP (lokal)",
                    subtitle = "Pilih file suara dari penyimpanan HP",
                    onClick = onPickLocal
                )
                SoundSourceOptionRow(
                    icon = Icons.Filled.CloudDownload,
                    title = "Online dari GitHub",
                    subtitle = "Jelajahi kategori suara dari repo bersama, bisa didengar dulu, butuh internet",
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
private fun SoundSourceOptionRow(
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

// Status daftar isi folder online - dipakai OnlineSoundBrowserDialog buat
// nentuin apa yang ditampilkan (loading / error / hasil).
sealed class OnlineSoundUiState {
    data object Loading : OnlineSoundUiState()
    data class Error(val message: String, val isRateLimit: Boolean = false) : OnlineSoundUiState()
    data class Loaded(val entries: List<OnlineSoundRepo.Entry>) : OnlineSoundUiState()
}

// Dialog kedua: file manager sederhana buat jelajahin repo kumpulan-wav-dtx -
// folder = kategori (bisa bersarang bebas, tinggal tap buat masuk), file .wav
// bisa di-preview (tombol play/stop) SEBELUM beneran di-set ke pad yang
// dipilih (tombol cloud-download di baris yang sama). Path yang lagi dibuka
// ditampilin sebagai breadcrumb di judul dialog, dan tombol panah-kembali
// muncul begitu udah masuk minimal 1 folder.
@Composable
fun OnlineSoundBrowserDialog(
    pad: Int,
    currentPath: String,
    canGoBack: Boolean,
    state: OnlineSoundUiState,
    previewingPath: String?,
    isPreviewBuffering: Boolean,
    settingPath: String?,
    onOpenFolder: (OnlineSoundRepo.Entry) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTogglePreview: (OnlineSoundRepo.Entry) -> Unit,
    onSet: (OnlineSoundRepo.Entry) -> Unit,
    onDismiss: () -> Unit
) {
    val busy = settingPath != null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = PanelColor,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canGoBack) {
                        IconButton(onClick = onBack, modifier = Modifier.size(28.dp), enabled = !busy) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Kembali",
                                tint = TextDim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "Suara Online - PAD $pad",
                        color = PadTextColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRetry, modifier = Modifier.size(28.dp), enabled = !busy) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Muat ulang daftar",
                            tint = TextDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                // Breadcrumb path folder yang lagi dibuka - cuma ditampilin begitu
                // udah masuk ke minimal 1 folder. Di root sengaja DIKOSONGIN (bukan
                // ditulis "/") - dulu selalu nampilin garis miring sendirian di root
                // yang kelihatan berantakan/gak rapi padahal gak ngasih info apa-apa
                // (toh tombol kembali juga baru muncul begitu udah masuk folder).
                if (currentPath.isNotBlank()) {
                    Text(
                        text = "/$currentPath",
                        color = TextDim,
                        fontSize = 10.sp,
                        maxLines = 1
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
                    is OnlineSoundUiState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = ThemeState.accentColor)
                            Spacer(Modifier.height(10.dp))
                            Text("Mengambil daftar...", color = TextDim, fontSize = 12.sp)
                        }
                    }
                    is OnlineSoundUiState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(
                                if (state.isRateLimit) "Kena limit request GitHub." else "Gagal mengambil daftar dari repo.",
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
                    is OnlineSoundUiState.Loaded -> {
                        if (state.entries.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = TextDim,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Folder ini masih kosong.",
                                    color = TextDim,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.entries, key = { it.path }) { entry ->
                                    when (entry.type) {
                                        OnlineSoundRepo.EntryType.DIR -> SoundFolderRow(
                                            entry = entry,
                                            enabled = !busy,
                                            onClick = { onOpenFolder(entry) }
                                        )
                                        OnlineSoundRepo.EntryType.FILE -> SoundFileRow(
                                            entry = entry,
                                            isPreviewing = previewingPath == entry.path,
                                            isPreviewBuffering = isPreviewBuffering && previewingPath == entry.path,
                                            isSetting = settingPath == entry.path,
                                            setDisabled = busy,
                                            onTogglePreview = { onTogglePreview(entry) },
                                            onSet = { onSet(entry) }
                                        )
                                    }
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
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun SoundFolderRow(
    entry: OnlineSoundRepo.Entry,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = ThemeState.accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.name,
            color = if (enabled) PadTextColor else TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SoundFileRow(
    entry: OnlineSoundRepo.Entry,
    isPreviewing: Boolean,
    isPreviewBuffering: Boolean,
    isSetting: Boolean,
    setDisabled: Boolean,
    onTogglePreview: () -> Unit,
    onSet: () -> Unit
) {
    // Seluruh baris sekarang bisa langsung di-tap buat SET (persis pola
    // OnlinePresetRow di dialog LOAD - tap body = langsung unduh & pakai),
    // BUKAN cuma ikon cloud-download di ujung kanan seperti sebelumnya.
    // Tombol play/stop preview di kiri tetap punya area tap sendiri (nested
    // clickable IconButton) - Compose kasih prioritas ke situ dulu, jadi
    // nge-tap tombol play TETAP cuma preview, gak ikut ke-trigger SET.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = !setDisabled, onClick = onSet)
            .padding(10.dp)
    ) {
        // Tombol play/stop preview - dengerin dulu sebelum diset, streaming
        // langsung dari download_url GitHub tanpa nyimpen apapun ke disk.
        IconButton(
            onClick = onTogglePreview,
            enabled = !setDisabled || isPreviewing,
            modifier = Modifier.size(32.dp)
        ) {
            if (isPreviewBuffering) {
                CircularProgressIndicator(
                    color = ThemeState.accentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = if (isPreviewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isPreviewing) "Stop preview" else "Dengarkan dulu",
                    tint = ThemeState.accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name.removeSuffix(".wav").removeSuffix(".WAV"),
                color = if (setDisabled && !isSetting) TextDim else PadTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(formatFileSize(entry.sizeBytes), color = TextDim, fontSize = 10.sp)
        }
        // Ikon di ujung kanan sekarang CUMA penanda visual (persis kayak
        // OnlinePresetRow), BUKAN tombol terpisah lagi - jadi gak ada lagi
        // IconButton dengan target sentuh 48dp bawaan yang bikin spinner
        // "MENYIMPAN..." kelihatan geser/gak center pas ganti dari ikon ke
        // spinner. Dibungkus Box ukuran tetap (18dp) biar posisi ikon &
        // spinner persis di titik yang sama, gak ada pergeseran sama sekali.
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (isSetting) {
                CircularProgressIndicator(
                    color = ThemeState.accentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = "Set suara ini ke pad",
                    tint = if (setDisabled) TextDim else ThemeState.accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
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
