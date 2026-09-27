package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dialog "LAGU"/"GANTI": pengganti file picker langsung. Sekarang begitu
// tombolnya ditekan, yang muncul duluan adalah daftar musik yang SUDAH
// kedeteksi di HP (dari MusicLibraryRepo, gak perlu proses apa-apa dulu buat
// nampilinnya - cuma baca index media yang udah ada) + kotak pencarian buat
// nyaring cepat by judul. Di samping kotak pencarian ada tombol ikon folder
// buat buka file picker (SAF) manual - buat lagu yang gak nongol di daftar
// (baru disalin & belum ke-index, atau gak ada izin akses media) - jadi gak
// ada fitur lama yang hilang, cuma ditaruh jadi jalan pintas ikon di sebelah
// pencarian (bukan tombol lebar terpisah lagi).
@Composable
fun MusicLibraryDialog(
    songs: List<MusicLibraryRepo.DeviceSong>,
    isScanning: Boolean,
    onPick: (MusicLibraryRepo.DeviceSong) -> Unit,
    onBrowseManual: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter { it.title.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Pilih Lagu", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Kotak pencarian - warna teks/placeholder/ikon/border DIKASIH
                    // EKSPLISIT (bukan default Material3) karena default-nya kepilih
                    // warna gelap yang nyatu sama background gelap dialog ini,
                    // bikin gak kebaca sama sekali pas diketik.
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Cari", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = PadTextColor),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Hapus pencarian",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PadTextColor,
                            unfocusedTextColor = PadTextColor,
                            focusedContainerColor = CtrlPanelColor,
                            unfocusedContainerColor = CtrlPanelColor,
                            focusedBorderColor = ThemeState.accentColor,
                            unfocusedBorderColor = TextDim,
                            cursorColor = ThemeState.accentColor,
                            focusedPlaceholderColor = TextDim,
                            unfocusedPlaceholderColor = TextDim,
                            focusedLeadingIconColor = TextDim,
                            unfocusedLeadingIconColor = TextDim,
                            focusedTrailingIconColor = TextDim,
                            unfocusedTrailingIconColor = TextDim
                        )
                    )

                    // Tombol ikon "browse manual" - persis di samping kotak
                    // pencarian, biar keliatan sebagai jalan pintas terkait yang
                    // sama-sama soal "nyari lagu", bukan aksi terpisah di bawah.
                    IconButton(
                        onClick = onBrowseManual,
                        modifier = Modifier
                            .size(48.dp)
                            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "Cari file musik manual",
                            tint = ThemeState.accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(modifier = Modifier.heightIn(min = 100.dp, max = 340.dp)) {
                    when {
                        isScanning -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = ThemeState.accentColor)
                                Spacer(Modifier.height(10.dp))
                                Text("Memindai musik di HP...", color = TextDim, fontSize = 12.sp)
                            }
                        }
                        filtered.isEmpty() -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = TextDim,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (songs.isEmpty())
                                        "Gak ada musik yang kedeteksi di HP. Coba tombol folder di sebelah pencarian."
                                    else
                                        "Gak ketemu musik dengan nama itu.",
                                    color = TextDim,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filtered, key = { it.uri.toString() }) { song ->
                                    DeviceSongRow(song = song, onClick = { onPick(song) })
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
                Text("BATAL", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun DeviceSongRow(
    song: MusicLibraryRepo.DeviceSong,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = ThemeState.accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                color = PadTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(formatSongDuration(song.durationMs), color = TextDim, fontSize = 10.sp)
        }
    }
}

private fun formatSongDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
