package com.kendang.realpads

import android.content.Context

private const val LOOP_PREFS = "realpads_pad_loop"

// Nyimpen setelan loop (true/false) per (preset, pad) di SharedPreferences, pola
// sama kayak PadChokeStorage - terpisah dari sample WAV-nya sendiri, jadi setelan
// yang di-set user lewat dialog "Pengaturan" tetep nempel walau apk ditutup total.
//
// Beda dari PadChokeStorage: default di sini FALSE (loop mati), sama kayak
// zero-init bawaan native PadEngine, jadi getBoolean() di bawah default-nya false
// dan loadAllIntoEngine() aman cuma push prefs yang memang ADA (pad yang belum
// pernah di-custom otomatis tetap false).
object PadLoopStorage {
    private fun key(preset: Int, pad: Int) = "loop_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PadChokeStorage.loadAllIntoEngine):
    // baca semua setelan loop custom yang kesimpen lalu push ke native engine.
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(LOOP_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadLoop(preset, pad, prefs.getBoolean(k, false))
                }
            }
        }
    }

    // Dipanggil tiap toggle loop di dialog "Pengaturan" ditekan: langsung update ke
    // native (biar kedengeran efeknya di ketukan berikutnya) SEKALIGUS ditulis ke disk.
    fun set(context: Context, preset: Int, pad: Int, enabled: Boolean) {
        AudioEngine.nativeSetPadLoop(preset, pad, enabled)
        context.getSharedPreferences(LOOP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(preset, pad), enabled)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine.
    fun get(preset: Int, pad: Int): Boolean = AudioEngine.nativeGetPadLoop(preset, pad)
}
