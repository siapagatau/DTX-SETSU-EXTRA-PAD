package com.kendang.realpads

import android.content.Context

private const val SESSION_PREFS = "realpads_session"
private const val KEY_PRESET = "current_preset"
private const val KEY_KENDANG_VOLUME = "kendang_volume"
private const val KEY_MUSIC_VOLUME = "music_volume"
private const val KEY_CONTROLS_HIDDEN = "controls_hidden"
private const val KEY_MUSIC_POS = "music_pos_sec"
private const val KEY_MUSIC_DUR = "music_dur_sec"

// Nyimpen "keadaan terakhir dipakai" yang sebelumnya selalu balik ke awal tiap apk
// dibuka: preset aktif (P1-P8), volume kendang & volume musik, status UI disembunyiin
// (cubit), dan posisi terakhir lagu. Pola sama kayak ThemeState / PadVolumeStorage:
// SharedPreferences, ditulis pakai apply() (async, gak ngeblok UI).
//
// Setelan per-pad (volume, tune, reverb, choke, loop, tempo loop, key binding), tema,
// GLER, background, dan lagu sendiri sudah punya penyimpanan masing-masing.
object SessionStorage {
    data class Saved(
        val preset: Int,
        val bank: Int,
        val kendangVolume: Float,
        val musicVolume: Float,
        val controlsHidden: Boolean
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Saved {
        val p = prefs(context)
        var preset = p.getInt(KEY_PRESET, 1)
        // Preset yang kesimpen harus masih valid & kebuka di versi ini (mis. versi FREE
        // cuma buka P1-P2) - kalau enggak, balik ke P1 biar gak nyangkut di preset
        // terkunci.
        if (preset < 1 || preset > PresetStorage.MAX_PRESET || preset > FREE_UNLOCKED_PRESETS) {
            preset = 1
        }
        // Bank tampilan: P1-P4 = bank 0, P5-P8 = bank 1 (sama dgn logika tombol bank).
        val bank = if (preset > 4) 1 else 0
        return Saved(
            preset = preset,
            bank = bank,
            kendangVolume = p.getFloat(KEY_KENDANG_VOLUME, 1f).coerceIn(0f, 1.5f),
            musicVolume = p.getFloat(KEY_MUSIC_VOLUME, 1f).coerceIn(0f, 1.5f),
            controlsHidden = p.getBoolean(KEY_CONTROLS_HIDDEN, false)
        )
    }

    fun savePreset(context: Context, preset: Int) {
        prefs(context).edit().putInt(KEY_PRESET, preset).apply()
    }

    fun saveKendangVolume(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_KENDANG_VOLUME, v).apply()
    }

    fun saveMusicVolume(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_MUSIC_VOLUME, v).apply()
    }

    fun saveControlsHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTROLS_HIDDEN, hidden).apply()
    }

    // Posisi disimpan bareng durasi lagunya, dipakai buat mastiin posisi ini memang
    // milik lagu yang sekarang ada di cache (lihat loadMusicPosition).
    fun saveMusicPosition(context: Context, positionSec: Double, durationSec: Double) {
        if (durationSec <= 0.0) return
        prefs(context).edit()
            .putFloat(KEY_MUSIC_POS, positionSec.toFloat())
            .putFloat(KEY_MUSIC_DUR, durationSec.toFloat())
            .apply()
    }

    // Balikin posisi terakhir kalau cocok dgn lagu yang baru dipulihin (durasi selisih
    // < 1 detik), dan belum mentok di ujung lagu. Selain itu 0.0 (mulai dari awal).
    fun loadMusicPosition(context: Context, durationSec: Double): Double {
        val p = prefs(context)
        val pos = p.getFloat(KEY_MUSIC_POS, 0f).toDouble()
        val dur = p.getFloat(KEY_MUSIC_DUR, 0f).toDouble()
        if (dur <= 0.0 || Math.abs(dur - durationSec) > 1.0) return 0.0
        if (pos <= 0.0 || pos >= durationSec - 1.0) return 0.0
        return pos
    }
}
