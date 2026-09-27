package com.kendang.realpads

import android.content.Context

private const val CHOKE_PREFS = "realpads_pad_choke"

// Nyimpen setelan choke (true/false) per (preset, pad) di SharedPreferences, pola
// sama kayak PadReverbStorage/PadVolumeStorage/PadTuneStorage - terpisah dari
// sample WAV-nya sendiri, jadi setelan yang di-set user lewat dialog "Pengaturan"
// tetep nempel walau apk ditutup total.
//
// BEDA PENTING dari storage pad lain: default-nya di sini TRUE (choke aktif), bukan
// 0/kosong. Makanya getBoolean() di bawah selalu dikasih default true secara
// eksplisit, dan loadAllIntoEngine() cuma push ke native kalau prefs-nya memang
// ADA (pad yang belum pernah di-custom otomatis tetap true, sama kayak default
// bawaan di native PadEngine).
object PadChokeStorage {
    private fun key(preset: Int, pad: Int) = "choke_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PresetStorage.loadAllIntoEngine): baca
    // semua setelan choke custom yang kesimpen lalu push ke native engine.
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(CHOKE_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadChoke(preset, pad, prefs.getBoolean(k, true))
                }
            }
        }
    }

    // Dipanggil tiap toggle choke di dialog "Pengaturan" ditekan: langsung update ke
    // native (biar kedengeran efeknya di ketukan berikutnya) SEKALIGUS ditulis ke disk.
    fun set(context: Context, preset: Int, pad: Int, enabled: Boolean) {
        AudioEngine.nativeSetPadChoke(preset, pad, enabled)
        context.getSharedPreferences(CHOKE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(preset, pad), enabled)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine.
    fun get(preset: Int, pad: Int): Boolean = AudioEngine.nativeGetPadChoke(preset, pad)
}
