package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val SLIDE_ROLL_PREFS = "realpads_slide_roll"
private const val KEY_SLIDE_ROLL_ENABLED = "slide_roll_enabled"

// Nyimpen on/off fitur "geser pindah pad" (glide/roll - lihat catatan panjang di
// PadGrid soal gimana geser jari lintas pad bikin tiap pad yang dilewati ikut
// bunyi, kayak fitur roll di drum pad fisik). Pola persist-nya sama kayak
// GlerEffectState - SharedPreferences + state Compose, nempel walau apk ditutup
// total.
//
// Default-nya OFF (mati): dulu perilaku ini SELALU aktif tanpa bisa dimatikan -
// enak buat sebagian orang yang mau main roll, tapi bikin sebagian user lain gak
// sengaja mindah pad cuma gara-gara jarinya sedikit geser pas mukul (apalagi di
// pad yang berdempetan). Sekarang jadi pilihan lewat switch di dialog Pengaturan,
// dan biar perilaku app gak berubah tiba-tiba buat user lama, defaultnya nonaktif
// sampai user sendiri yang menyalakan.
object PadSlideRollState {
    var enabled by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(SLIDE_ROLL_PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_SLIDE_ROLL_ENABLED, false)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(SLIDE_ROLL_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SLIDE_ROLL_ENABLED, value)
            .apply()
    }
}
