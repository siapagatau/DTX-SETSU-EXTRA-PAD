package com.kendang.realpads

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

// Normalisasi volume sample PCM 16-bit supaya semua pad (dari preset bawaan,
// import file manual, maupun import ZIP preset) punya level suara yang konsisten
// - gak ada lagi yang kedengeran lemah/kurang kenceng dibanding pad lain.
//
// Sengaja dibikin OBJECT TERPISAH & STATELESS (bukan bagian dari WavLoader atau
// CompressedSampleLoader) supaya bisa dipanggil dari KEDUA loader itu tanpa bikin
// mereka saling bergantung satu sama lain (lihat catatan soal duplikasi di
// WavLoader/CompressedSampleLoader - alasan yang sama, tapi di sini kita SHARE
// karena ini murni fungsi matematika tanpa dependensi lifecycle/thread apapun,
// jadi aman dipakai bersama).
//
// Dipanggil SEKALI SAJA pas sample di-load/di-import (sama seperti resample &
// estimateIsBass) - bukan tiap pad dipukul - jadi tidak menambah beban ke audio
// thread/real-time playback sama sekali.
//
// Metode: RMS normalize (menyamakan tingkat kekencangan RATA-RATA yang didengar
// telinga) dengan batas peak (supaya sample yang punya transient tajam/dinamik
// besar tidak jadi pecah/clipping walau RMS-nya disamakan). Ini metode yang sama
// yang dipakai buat "meratakan" kumpulan sample WAV secara manual, cuma di sini
// jalan otomatis di dalam app tiap kali sample masuk.
object VolumeNormalizer {
    // Target RMS & batas peak dalam dBFS (0 dB = amplitudo maksimum 16-bit).
    // -18 dB RMS itu level yang umum dipakai buat sample drum/perkusi supaya ada
    // headroom cukup pas beberapa pad dibunyikan bareng (di-mix), tapi tetap
    // kedengeran "kenceng"/jelas kupingnya.
    private const val TARGET_RMS_DB = -18.0
    private const val PEAK_CEILING_DB = -1.0
    private const val FULL_SCALE = 32768.0

    // Kalau sample nyaris hening (RMS di bawah ini), jangan disentuh - kalau
    // dipaksa gain-up bisa meledakkan noise lantai jadi kedengeran, dan secara
    // musikal sample "nyaris kosong" gak butuh diratakan.
    private const val SILENCE_RMS_FLOOR = 1.0

    // Kalau gain yang dihitung nyaris 1.0 (<~0.17 dB), skip - hemat kerjaan CPU
    // buat sample yang emang udah pas levelnya, tanpa mengubah bit-nya sama sekali.
    private const val GAIN_NEGLIGIBLE_LOW = 0.98
    private const val GAIN_NEGLIGIBLE_HIGH = 1.02

    fun normalize(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm

        var sumSquares = 0.0
        var peak = 0
        for (s in pcm) {
            val v = s.toInt()
            sumSquares += v.toDouble() * v.toDouble()
            val av = abs(v)
            if (av > peak) peak = av
        }
        if (peak == 0) return pcm // silence total (semua sample 0)

        val rms = sqrt(sumSquares / pcm.size)
        if (rms < SILENCE_RMS_FLOOR) return pcm

        val currentRmsDb = 20.0 * log10(rms / FULL_SCALE)
        var gainDb = TARGET_RMS_DB - currentRmsDb

        // Cek: kalau gain ini diterapkan, apakah peak-nya bakal kelewat batas
        // (clipping)? Kalau iya, gain diturunkan supaya peak pas di batas -
        // prioritas utama tetap "jangan sampai pecah", RMS jadi sedikit di bawah
        // target buat sample yang dinamikanya lebar (peak jauh di atas RMS).
        val currentPeakDb = 20.0 * log10(peak / FULL_SCALE)
        val resultingPeakDb = currentPeakDb + gainDb
        if (resultingPeakDb > PEAK_CEILING_DB) {
            gainDb = PEAK_CEILING_DB - currentPeakDb
        }

        val gainLinear = 10.0.pow(gainDb / 20.0)
        if (gainLinear in GAIN_NEGLIGIBLE_LOW..GAIN_NEGLIGIBLE_HIGH) return pcm

        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val scaled = pcm[i] * gainLinear
            out[i] = scaled.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }
}
