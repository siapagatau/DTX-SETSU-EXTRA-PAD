package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val GLER_PREFS = "realpads_gler"
private const val KEY_GLER_ENABLED = "gler_enabled"

// Nyimpen on/off efek GLER (layar bergetar/gemeretak pas mukul pad nada rendah/kick)
// dan bikin dia tetep nempel walau apk ditutup total, karena disimpen di
// SharedPreferences - sama persis polanya kayak ThemeState buat warna tema.
// Default-nya OFF (mati) pas pertama kali install / belum pernah diset,
// biar user baru mulai dengan efek gler nonaktif kecuali mereka sendiri
// yang nyalain lewat dialog Pengaturan.
object GlerEffectState {
    var enabled by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(GLER_PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_GLER_ENABLED, false)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(GLER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLER_ENABLED, value)
            .apply()
    }
}
