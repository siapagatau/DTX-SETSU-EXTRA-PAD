package com.kendang.realpads

import android.content.Context

private const val TUNE_PREFS = "realpads_pad_tune"

// Nyimpen tune (semitone, -12f..12f) per (preset, pad) di SharedPreferences,
// persis pola PadVolumeStorage - terpisah dari sample WAV-nya sendiri, jadi
// setelan tune yang di-set user lewat dialog "Pengaturan" tetep nempel walau
// apk ditutup total.
object PadTuneStorage {
    private fun key(preset: Int, pad: Int) = "tune_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PresetStorage.loadAllIntoEngine): baca
    // semua tune custom yang kesimpen lalu push ke native engine. Pad yang belum
    // pernah di-custom otomatis tetap 0 semitone (default di native).
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(TUNE_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadTune(preset, pad, prefs.getFloat(k, 0f))
                }
            }
        }
    }

    // Dipanggil tiap slider tune di dialog "Pengaturan" digeser: langsung update ke
    // native (biar kedengeran efeknya real-time) SEKALIGUS ditulis ke disk.
    fun set(context: Context, preset: Int, pad: Int, semitones: Float) {
        AudioEngine.nativeSetPadTune(preset, pad, semitones)
        context.getSharedPreferences(TUNE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), semitones)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine.
    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadTune(preset, pad)
}
