package com.kendang.realpads

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Nyimpen sample WAV mentah (raw bytes) di internal storage app, per preset & per pad,
// biar begitu apk dibuka lagi semua suara yang udah di-set gak ilang.
// Struktur: filesDir/samples/p{preset}/pad{pad}.wav
object PresetStorage {
    const val MAX_PRESET = 8

    // Jumlah pad BAWAAN (selalu ada, gak bisa disembunyikan) - dipakai UI buat
    // tau batas 12 pad "asli" sebelum 4 pad tambahan (lihat ExtraPadState).
    const val BASE_PAD = 12

    // Total slot pad yang disediakan storage/native engine per preset. Dinaikin
    // dari 12 -> 16 buat nampung 4 "Pad Tambahan" (lihat ExtraPadState) - pad
    // 13..16 cuma beda di SEMBUNYI/TAMPIL-nya di PadGrid (kontrol ExtraPadState),
    // tapi datanya (sample, volume/tune/reverb/choke/loop, key binding) tetap
    // lewat jalur storage yang SAMA PERSIS kayak pad 1-12, termasuk lewat
    // exportPreset/importPreset di bawah - jadi otomatis ikut ke-export/ke-load
    // begitu MAX_PAD-nya dinaikin, tanpa perlu nulis ulang logic apa pun di sini.
    const val MAX_PAD = 16

    private fun presetDir(context: Context, preset: Int): File =
        File(File(context.filesDir, "samples"), "p$preset")

    private fun padFile(context: Context, preset: Int, pad: Int): File =
        File(presetDir(context, preset), "pad$pad.wav")

    // File penanda kosong (isinya cuma "1") buat nandain pad ini kedengeran nada
    // rendah/kick -> dipakai buat micu efek getar layar (GLER). Disimpen terpisah
    // dari file .wav-nya biar gampang, gak perlu format file WAV/JSON tambahan.
    private fun bassFile(context: Context, preset: Int, pad: Int): File =
        File(presetDir(context, preset), "pad$pad.bass")

    fun setBassFlag(context: Context, preset: Int, pad: Int, isBass: Boolean) {
        val f = bassFile(context, preset, pad)
        if (isBass) {
            f.parentFile?.mkdirs()
            f.writeText("1")
        } else if (f.exists()) {
            f.delete()
        }
    }

    fun isBassPad(context: Context, preset: Int, pad: Int): Boolean = bassFile(context, preset, pad).exists()

    // Simpen raw bytes WAV ke disk begitu user assign suara ke sebuah pad.
    fun saveSample(context: Context, preset: Int, pad: Int, bytes: ByteArray) {
        val file = padFile(context, preset, pad)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    // Dipanggil sekali pas app start: baca semua file yang ada di disk lalu
    // langsung load ke native engine, jadi semua preset balik seperti sebelum apk ditutup.
    // onPadLoaded dipanggil buat tiap pad yang berhasil dimuat, ngasih tau juga apakah
    // pad itu nada rendah/kick (dibaca dari file penanda .bass, bukan dihitung ulang -
    // sudah dihitung sekali pas pertama kali sample-nya di-assign).
    fun loadAllIntoEngine(context: Context, onPadLoaded: (preset: Int, pad: Int, isBass: Boolean) -> Unit = { _, _, _ -> }) {
        for (preset in 1..MAX_PRESET) {
            for (pad in 1..MAX_PAD) {
                val file = padFile(context, preset, pad)
                if (!file.exists()) continue
                val wav = WavLoader.parse(file.readBytes()) ?: continue
                AudioEngine.nativeLoadSample(preset, pad, wav.pcm, wav.channels)
                onPadLoaded(preset, pad, isBassPad(context, preset, pad))
            }
        }
    }

    // Cek apakah sebuah preset punya minimal satu pad yang udah diisi suara.
    fun presetHasAnySample(context: Context, preset: Int): Boolean {
        val dir = presetDir(context, preset)
        val files = dir.listFiles() ?: return false
        return files.any { it.isFile }
    }

    // Hapus SEMUA sample tersimpan di 1 preset - baik dari disk (file .wav + flag
    // .bass tiap pad) MAUPUN dari native engine (lewat AudioEngine.nativeClearPad) -
    // tanpa perlu tau pad mana aja yang sebelumnya keisi.
    //
    // WAJIB dipanggil SEBELUM importPreset kalau maksudnya "GANTI TOTAL isi preset
    // ini persis kayak isi file zip yang baru mau di-load". Alasannya: importPreset
    // cuma NULIS pad yang memang ada entry-nya di dalam zip - kalau dipanggil
    // sendirian tanpa clearPreset dulu, pad yang gak ada di zip baru (misal preset
    // lama full 12 pad, tapi zip yang baru di-load cuma isi 6 pad) bakal tetap
    // nyisain sample dari preset SEBELUMNYA - hasil akhirnya jadi "campuran" dua
    // preset yang beda, bukan isi preset baru yang bersih & sesuai file aslinya.
    fun clearPreset(context: Context, preset: Int) {
        for (pad in 1..MAX_PAD) {
            val file = padFile(context, preset, pad)
            if (file.exists()) file.delete()
            val bass = bassFile(context, preset, pad)
            if (bass.exists()) bass.delete()
            AudioEngine.nativeClearPad(preset, pad)
        }
    }

    // Export semua pad dari 1 preset jadi 1 file .zip (entry: pad1.wav, pad2.wav, dst).
    // Return jumlah pad yang berhasil di-export.
    fun exportPreset(context: Context, preset: Int, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            for (pad in 1..MAX_PAD) {
                val file = padFile(context, preset, pad)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("pad$pad.wav"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    // Import file .zip hasil export ke sebuah preset: nulis tiap entry ke disk
    // lalu langsung load-in ke native engine. Return jumlah pad yang berhasil dimuat.
    // onPadLoaded sama fungsinya kayak di loadAllIntoEngine, dipanggil per pad yang
    // berhasil di-import.
    fun importPreset(context: Context, preset: Int, input: InputStream, onPadLoaded: (pad: Int, isBass: Boolean) -> Unit = { _, _ -> }): Int {
        var count = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                // Entry .wav ditangani lewat parser cepat (WavLoader). Entry format lain
                // (mp3/aac/m4a/ogg/flac - misal preset hasil zip manual, bukan hasil
                // exportPreset di app ini yang selalu nulis .wav) ditangani lewat
                // CompressedSampleLoader (decode MediaCodec, cuma sekali pas import ini,
                // BUKAN tiap pad dipukul - jadi gak nambah delay realtime).
                val wavMatch = Regex("""pad(\d+)\.wav$""", RegexOption.IGNORE_CASE).find(name)
                val otherMatch = Regex("""pad(\d+)\.(mp3|m4a|aac|ogg|flac)$""", RegexOption.IGNORE_CASE).find(name)
                val pad = (wavMatch ?: otherMatch)?.groupValues?.get(1)?.toIntOrNull()
                if (pad != null && pad in 1..MAX_PAD) {
                    val rawBytes = zip.readBytes()
                    val result = if (wavMatch != null) {
                        WavLoader.parse(rawBytes)?.let { it to rawBytes }
                    } else {
                        CompressedSampleLoader.decode(context, rawBytes)?.let { decoded ->
                            val wavBytes = CompressedSampleLoader.encodeToWavBytes(
                                decoded.pcm, decoded.channels, decoded.sampleRate
                            )
                            decoded to wavBytes
                        }
                    }
                    if (result != null) {
                        val (wav, wavBytes) = result
                        // Selalu disimpan sebagai .wav kanonik di disk, apapun format
                        // aslinya di dalam zip - biar loadAllIntoEngine & exportPreset
                        // gak perlu tau/peduli soal mp3 sama sekali.
                        saveSample(context, preset, pad, wavBytes)
                        setBassFlag(context, preset, pad, wav.isBass)
                        AudioEngine.nativeLoadSample(preset, pad, wav.pcm, wav.channels)
                        onPadLoaded(pad, wav.isBass)
                        count++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return count
    }
}
