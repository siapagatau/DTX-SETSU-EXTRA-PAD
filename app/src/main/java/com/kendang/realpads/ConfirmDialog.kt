package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Dialog konfirmasi Ya/Tidak generik - dipakai di titik-titik yang butuh
// "jeda sebentar" sebelum aksi yang gak bisa langsung dibatalin (nyimpen
// rekaman ke file, export preset ke file), biar gak berasa tiba-tiba/gak
// sengaja kepencet. Gak dibikin spesifik per fitur, satu composable dipakai
// ulang di semua tempat yang butuh pola ini.
//
// FIX WARNA: dulu background dialog ini pakai CtrlPanelColor (0xFF1C1C1E) -
// hampir sama dengan BgColor app (0xFF111113) di belakangnya, jadi dialognya
// kelihatan "nyatu" sama layar dan susah dibedain batasnya. Sekarang pakai
// PanelColor (0xFF3C3C3F), PERSIS warna container dialog lain di app ini
// (MusicLibraryDialog, OnlinePresetDialog, ColorPickerDialog, PadVolumeDialog
// - semuanya AlertDialog(containerColor = PanelColor)), dan tombol kedua
// (BUANG/TIDAK/BATAL) pakai CtrlPanelColor + PadTextColor persis kayak tombol
// "BATAL" di dialog-dialog itu. Tombol aksi utama (SIMPAN/YA/EXPORT) tetap
// warna aksen tema user (ThemeState.accentColor), sama kayak elemen "aktif"
// lain di app.
//
// Lebar dibatasi (gak lebih dari 340dp) biar gak kepanjangan buat pesan pendek.
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "YA",
    dismissLabel: String = "TIDAK",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        // Dialog ini MEMBLOKIR: tap di luar dialog & tombol back sengaja diabaikan
        // (bukan lagi sama kayak pilih dismissLabel). Kalau tidak, satu tap nyasar di
        // luar dialog langsung memicu BUANG dan rekaman yang barusan jadi hilang.
        // Sekarang user WAJIB milih tombol secara eksplisit (SIMPAN/BUANG, dst).
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor)
                .padding(20.dp)
        ) {
            Text(
                title,
                color = PadTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CtrlPanelColor,
                        contentColor = PadTextColor
                    )
                ) {
                    Text(dismissLabel, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThemeState.accentColor,
                        contentColor = ThemeState.accentTextColor
                    )
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
