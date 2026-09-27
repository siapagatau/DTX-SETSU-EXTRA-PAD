package com.kendang.realpads

import android.content.Context

private const val REVERB_PREFS = "realpads_pad_reverb"

// Nyimpen reverb send (0f..1f) per (preset, pad) di SharedPreferences, persis pola
// PadVolumeStorage/PadTuneStorage - terpisah dari sample WAV-nya sendiri, jadi
// setelan reverb yang di-set user lewat dialog "Pengaturan" tetep nempel walau
// apk ditutup total.
object PadReverbStorage {
    private fun key(preset: Int, pad: Int) = "reverb_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PresetStorage.loadAllIntoEngine): baca
    // semua reverb custom yang kesimpen lalu push ke native engine. Pad yang belum
    // pernah di-custom otomatis tetap 0 (kering, default di native).
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(REVERB_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadReverb(preset, pad, prefs.getFloat(k, 0f))
                }
            }
        }
    }

    // Dipanggil tiap slider reverb di dialog "Pengaturan" digeser: langsung update ke
    // native (biar kedengeran efeknya real-time) SEKALIGUS ditulis ke disk.
    fun set(context: Context, preset: Int, pad: Int, amount: Float) {
        AudioEngine.nativeSetPadReverb(preset, pad, amount)
        context.getSharedPreferences(REVERB_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), amount)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine.
    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadReverb(preset, pad)
}
