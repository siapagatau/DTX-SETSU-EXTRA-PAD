package com.kendang.realpads

import android.content.Context

private const val VOLUME_PREFS = "realpads_pad_volume"

// Nyimpen volume per (preset, pad) di SharedPreferences, terpisah dari sample WAV-nya
// sendiri (PresetStorage) - biar tingkatan suara yang di-set user lewat dialog
// "Pengaturan" tetep nempel walau apk ditutup total, sama kayak ThemeState buat warna.
object PadVolumeStorage {
    private fun key(preset: Int, pad: Int) = "vol_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PresetStorage.loadAllIntoEngine): baca
    // semua volume custom yang kesimpen lalu push ke native engine. Pad yang belum
    // pernah di-custom otomatis tetap 1.0 (default di native, gak perlu ditulis di sini).
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadVolume(preset, pad, prefs.getFloat(k, 1f))
                }
            }
        }
    }

    // Dipanggil tiap slider di dialog "Pengaturan" digeser: langsung update ke native
    // (biar kedengeran efeknya real-time) SEKALIGUS ditulis ke disk (biar nempel).
    fun set(context: Context, preset: Int, pad: Int, volume: Float) {
        AudioEngine.nativeSetPadVolume(preset, pad, volume)
        context.getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), volume)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine
    // (bukan dari prefs) - lebih akurat kalau suatu saat ada cara lain buat ngubah
    // volume pad selain lewat dialog ini.
    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadVolume(preset, pad)
}
