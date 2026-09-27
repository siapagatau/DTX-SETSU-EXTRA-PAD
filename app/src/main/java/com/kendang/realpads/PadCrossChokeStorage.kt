package com.kendang.realpads

import android.content.Context

private const val CROSS_CHOKE_PREFS = "realpads_pad_cross_choke"

// Nyimpen setelan "Stop" (choke ANTAR pad, fitur baru - beda dari PadChokeStorage
// yang choke SESAMA pad) per (preset, pad) di SharedPreferences, pola sama kayak
// storage pad lain: enabled (switch on/off) + mask (bitmask 12-bit, bit
// (targetPad-1) = 1 berarti pad ini bakal berhenti kalau targetPad itu dipukul).
//
// Default keduanya FALSE/0, sama kayak zero-init bawaan native PadEngine, jadi
// (pola sama kayak PadLoopStorage) loadAllIntoEngine() aman cuma push prefs yang
// memang ADA - pad yang belum pernah di-custom otomatis tetap nonaktif/kosong.
object PadCrossChokeStorage {
    private fun enabledKey(preset: Int, pad: Int) = "cc_enabled_p${preset}_pad$pad"
    private fun maskKey(preset: Int, pad: Int) = "cc_mask_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PadChokeStorage/PadLoopStorage.
    // loadAllIntoEngine): baca semua setelan custom yang kesimpen lalu push ke
    // native engine.
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(CROSS_CHOKE_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val ek = enabledKey(preset, pad)
                val mk = maskKey(preset, pad)
                if (prefs.contains(ek)) {
                    AudioEngine.nativeSetPadCrossChokeEnabled(preset, pad, prefs.getBoolean(ek, false))
                }
                if (prefs.contains(mk)) {
                    AudioEngine.nativeSetPadCrossChokeMask(preset, pad, prefs.getInt(mk, 0))
                }
            }
        }
    }

    // Dipanggil tiap toggle switch "Stop" di dialog "Pengaturan" ditekan: langsung
    // update ke native (kedengeran efeknya di ketukan berikutnya) SEKALIGUS ditulis
    // ke disk. Mask-nya SENGAJA gak ikut dihapus pas switch dimatiin - biar
    // checklist yang udah disusun user gak ilang kalau cuma mau dimatiin sementara.
    fun setEnabled(context: Context, preset: Int, pad: Int, enabled: Boolean) {
        AudioEngine.nativeSetPadCrossChokeEnabled(preset, pad, enabled)
        context.getSharedPreferences(CROSS_CHOKE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(enabledKey(preset, pad), enabled)
            .apply()
    }

    // Dipanggil tiap centang/hapus centang 1 pad target di checklist.
    fun setMask(context: Context, preset: Int, pad: Int, mask: Int) {
        AudioEngine.nativeSetPadCrossChokeMask(preset, pad, mask)
        context.getSharedPreferences(CROSS_CHOKE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(maskKey(preset, pad), mask)
            .apply()
    }

    // Dipanggil pas dialog "Pengaturan" dibuka: ambil nilai TERKINI dari native engine.
    fun getEnabled(preset: Int, pad: Int): Boolean = AudioEngine.nativeGetPadCrossChokeEnabled(preset, pad)
    fun getMask(preset: Int, pad: Int): Int = AudioEngine.nativeGetPadCrossChokeMask(preset, pad)
}
