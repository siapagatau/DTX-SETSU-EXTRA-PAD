package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// Warna aksen default: putih, dipakai kalau user belum pernah custom warna sendiri.
val DefaultAccentColor = Color(0xFFFFFFFF)

private const val THEME_PREFS = "realpads_theme"
private const val KEY_ACCENT_ARGB = "accent_argb"

// Nyimpen warna tema custom user (bebas, bukan dari daftar pilihan) dan bikin dia
// tetep nempel walau apk ditutup total, karena disimpen di SharedPreferences.
object ThemeState {
    var accentColor by mutableStateOf(DefaultAccentColor)
        private set

    // Warna teks/icon di atas accentColor otomatis nyesuain (gelap kalau warnanya terang,
    // terang kalau warnanya gelap) biar tetap kebaca walau usernya pilih warna lembut/pastel.
    val accentTextColor: Color
        get() = bestTextColorFor(accentColor)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ACCENT_ARGB)) {
            val argb = prefs.getInt(KEY_ACCENT_ARGB, DefaultAccentColor.toArgb())
            accentColor = Color(argb)
        }
    }

    fun set(context: Context, color: Color) {
        accentColor = color
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCENT_ARGB, color.toArgb())
            .apply()
    }
}

// Pilih warna teks kontras (hitam-kehangatan atau putih-krem) berdasar luminance background.
fun bestTextColorFor(bg: Color): Color {
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (luminance > 0.6f) Color(0xFF201512) else Color(0xFFF2EDE9)
}

// "C97A5F" atau "#C97A5F" -> Color. Return null kalau formatnya gak valid.
fun parseHexColor(input: String): Color? {
    val clean = input.trim().removePrefix("#")
    if (clean.length != 6) return null
    return try {
        val r = clean.substring(0, 2).toInt(16) / 255f
        val g = clean.substring(2, 4).toInt(16) / 255f
        val b = clean.substring(4, 6).toInt(16) / 255f
        Color(r, g, b)
    } catch (e: NumberFormatException) {
        null
    }
}

fun colorToHex(c: Color): String {
    val r = (c.red * 255).toInt().coerceIn(0, 255)
    val g = (c.green * 255).toInt().coerceIn(0, 255)
    val b = (c.blue * 255).toInt().coerceIn(0, 255)
    return String.format("%02X%02X%02X", r, g, b)
}
