package com.kendang.realpads

import android.content.Context

private const val LOOP_TEMPO_PREFS = "realpads_pad_loop_tempo"

// Nyimpen tempo LIVE loop (0.5f..2f, 1f = normal) per (preset, pad) di
// SharedPreferences - pola sama persis dengan PadTuneStorage/PadVolumeStorage.
//
// Sebelumnya tempo ini SENGAJA gak disimpan sama sekali: tiap kali sebuah loop
// dihentikan lalu dimulai ulang, native (lihat PadEngine.cpp - blok reset di
// onAudioReady pas voice baru mulai loop) maupun UI (lihat LaunchedEffect polling
// loopingPads di MainActivity) sama-sama otomatis membalikkan tempo ke 1.0.
// Sekarang perilakunya diubah atas permintaan user: tempo yang di-set lewat chip
// −/+ harus TETAP NEMPEL walau loop-nya dihentikan & dimulai lagi, BAHKAN setelah
// apk ditutup total dan dibuka lagi - makanya butuh persist ke disk di sini, bukan
// cuma disimpan di native (yang notabene hilang tiap proses app di-kill).
object PadLoopTempoStorage {
    private fun key(preset: Int, pad: Int) = "looptempo_p${preset}_pad$pad"

    // Dipanggil sekali pas app start (bareng PresetStorage.loadAllIntoEngine &
    // storage per-pad lainnya): baca semua tempo custom yang kesimpen lalu push ke
    // native engine. Pad yang belum pernah di-custom otomatis tetap 1.0 (default
    // di native, lihat PadEngine constructor).
    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(LOOP_TEMPO_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadLoopTempo(preset, pad, prefs.getFloat(k, 1f))
                }
            }
        }
    }

    // Dipanggil tiap tombol −/+ (atau dobel-tap reset) di chip tempo dipencet:
    // langsung update ke native (biar efeknya real-time ke loop yang lagi bunyi)
    // SEKALIGUS ditulis ke disk, jadi kepakai lagi walau loop di-restart atau apk
    // ditutup-buka.
    fun set(context: Context, preset: Int, pad: Int, rate: Float) {
        AudioEngine.nativeSetPadLoopTempo(preset, pad, rate)
        context.getSharedPreferences(LOOP_TEMPO_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), rate)
            .apply()
    }

    // Dipakai buat nyinkronin tampilan (padLoopTempos di MainActivity) ke nilai
    // TERKINI di native pas sebuah loop baru kedeteksi mulai bunyi.
    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadLoopTempo(preset, pad)
}
