package com.kendang.realpads

import android.content.ContentResolver
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(val pcm: ShortArray, val channels: Int, val sampleRate: Int, val isBass: Boolean)

// Parser WAV PCM 16-bit sederhana. Cukup buat sample drum/kendang pada umumnya.
object WavLoader {
    // HARUS sama persis dengan sample rate stream Oboe yang di-set di PadEngine::openStream()
    // (lihat setSampleRate(48000) di PadEngine.cpp). Engine native muter tiap sample pad
    // dengan cara jalan 1 frame per frame output TANPA konversi rate apapun (lihat
    // onAudioReady() -> v.position++ langsung per frame) - dia asumsikan pcm yang masuk
    // sudah di sample rate ini. Kalau file WAV aslinya bukan 48kHz (paling umum: 44100,
    // sample rate default hampir semua rekaman/DAW) dan dikirim APA ADANYA ke native
    // tanpa resample dulu, hasilnya pitch-nya melenceng & suaranya kedengeran "jelek"/
    // pecah/kayak dikompres - PADAHAL sample aslinya bagus, cuma dimainkan di kecepatan
    // yang salah. Ini penyebab utama kualitas suara pad kedengeran buruk dibanding app
    // pad lain (FL Studio Mobile, Real Drum, dll yang otomatis resample tiap sample-nya).
    private const val ENGINE_SAMPLE_RATE = 48000
    // Dipakai waktu user milih file lewat SAF (content:// uri)
    fun load(resolver: ContentResolver, uri: Uri): WavData? {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return parse(bytes)
    }

    // Dipakai buat decode ulang dari bytes yang udah disimpan di internal storage,
    // atau dari entry zip preset yang baru diimport.
    fun parse(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return null

        var pos = 12
        var channels = 1
        var sampleRate = 44100
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(pos + 4)
            val chunkStart = pos + 8
            when (chunkId) {
                "fmt " -> {
                    channels = bb.getShort(chunkStart + 2).toInt()
                    sampleRate = bb.getInt(chunkStart + 4)
                    bitsPerSample = bb.getShort(chunkStart + 14).toInt()
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                }
            }
            pos = chunkStart + chunkSize + (chunkSize % 2)
        }

        if (dataOffset < 0 || bitsPerSample != 16) return null
        val numSamples = dataSize / 2
        val pcmRaw = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            pcmRaw[i] = bb.getShort(dataOffset + i * 2)
        }

        // isBass dihitung dari pcm ASLI (ZCR-nya gak berubah walau nanti di-resample,
        // jadi gak perlu nunggu resample kelar buat estimasi ini).
        val isBass = estimateIsBass(pcmRaw, channels, sampleRate)

        // Resample ke sample rate tetap yang dipakai native engine (lihat catatan di
        // ENGINE_SAMPLE_RATE) - WAJIB, supaya sample apapun sample rate aslinya (44100,
        // 22050, dll) diputar di PITCH & KECEPATAN yang benar, bukan cuma "dijejelin apa
        // adanya" ke stream 48kHz.
        val resampled = if (channels > 0 && sampleRate != ENGINE_SAMPLE_RATE && sampleRate > 0) {
            resampleLinear(pcmRaw, sampleRate, channels, ENGINE_SAMPLE_RATE)
        } else {
            pcmRaw
        }

        // Ratakan volume di sini, SEKALI pas load, sebelum pcm disimpan/dipakai
        // native engine - biar preset apapun yang di-load (bawaan, file manual,
        // ataupun dari ZIP import) otomatis kedengeran konsisten levelnya tanpa
        // butuh proses manual di luar app lagi. Lihat VolumeNormalizer buat detail
        // metodenya (RMS normalize + batas peak biar gak clipping).
        val pcm = VolumeNormalizer.normalize(resampled)

        return WavData(pcm, channels, ENGINE_SAMPLE_RATE, isBass)
    }

    // Resample linear sederhana (bukan sinc/polyphase, tapi cukup buat sample drum
    // durasi pendek - gak ada artefak yang kedengeran signifikan). Implementasi sama
    // persis konsepnya dengan MusicLoader.resampleLinear, sengaja diduplikasi di sini
    // (bukan di-share lewat 1 fungsi util) supaya WavLoader tetap berdiri sendiri tanpa
    // dependensi ke MusicLoader (beda lifecycle: WavLoader dipanggil sinkron pas assign
    // pad / load semua preset di startup, MusicLoader di background thread terpisah).
    private fun resampleLinear(pcm: ShortArray, srcRate: Int, channels: Int, dstRate: Int): ShortArray {
        if (srcRate <= 0 || channels <= 0) return pcm
        val srcFrames = pcm.size / channels
        if (srcFrames == 0) return pcm
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstFrames = ((srcFrames.toLong() * dstRate) / srcRate).toInt()
        val out = ShortArray(dstFrames * channels)

        for (i in 0 until dstFrames) {
            val srcPosF = i * ratio
            var srcIndex = srcPosF.toInt()
            if (srcIndex >= srcFrames - 1) srcIndex = srcFrames - 2
            if (srcIndex < 0) srcIndex = 0
            val frac = srcPosF - srcIndex
            for (ch in 0 until channels) {
                val i0 = srcIndex * channels + ch
                val i1 = (srcIndex + 1) * channels + ch
                val s0 = pcm[i0]
                val s1 = if (i1 < pcm.size) pcm[i1] else s0
                out[i * channels + ch] = (s0 + (s1 - s0) * frac).toInt().toShort()
            }
        }
        return out
    }

    // Estimasi kasar: apakah sample ini "nada rendah" (kick/bass tom, dll) atau bukan
    // (hihat/snare/clap yang lebih terang). Dihitung SEKALI di sini pas sample dimuat,
    // bukan tiap dipukul -> gak ada beban ke audio thread/real-time playback sama
    // sekali, jadi gak mungkin bikin suara lag/delay.
    //
    // Caranya pakai zero-crossing rate (ZCR): berapa kali sinyal ganti tanda (+/-)
    // per detik. Nada rendah alaminya jarang ganti tanda (gelombangnya lebar/lambat),
    // nada tinggi/berisik (hihat, clap) sering banget ganti tanda. Ini heuristik
    // sederhana (bukan analisis frekuensi penuh/FFT), cukup akurat buat bedain
    // kick/bass vs instrumen perkusi yang lebih terang, dan murah secara komputasi.
    private fun estimateIsBass(pcm: ShortArray, channels: Int, sampleRate: Int): Boolean {
        if (pcm.isEmpty() || sampleRate <= 0) return false
        val step = if (channels > 0) channels else 1
        if (pcm.size <= step) return false

        var crossings = 0
        var counted = 0
        var prev = pcm[0].toInt()
        var i = step
        while (i < pcm.size) {
            val cur = pcm[i].toInt()
            if ((prev >= 0) != (cur >= 0)) crossings++
            prev = cur
            counted++
            i += step
        }
        if (counted == 0) return false

        val durationSec = counted.toDouble() / sampleRate
        if (durationSec <= 0.0) return false
        val crossingsPerSecond = crossings / durationSec

        // ZCR kira-kira 2x frekuensi dominan. Ambang ini (400/detik ~ setara
        // frekuensi dominan di bawah ~200Hz) sengaja agak longgar biar kick yang
        // ada sedikit "klik" transient di awal tetap kehitung nada rendah, tapi
        // sample yang jelas terang (hihat/clap/snare) tetap gak kepilih.
        return crossingsPerSecond < 400.0
    }
}
