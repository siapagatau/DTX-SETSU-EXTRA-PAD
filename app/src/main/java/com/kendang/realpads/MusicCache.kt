package com.kendang.realpads

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Cache hasil decode+resample lagu (PCM 16-bit 48kHz) ke penyimpanan internal app.
//
// Kenapa perlu: decode MP3/AAC -> PCM + resample itu yang bikin loading lama. Dulu
// hasilnya cuma hidup di memori (native engine), jadi begitu apk ditutup paksa /
// di-kill sistem / dibersihin dari recent apps, user harus pilih lagu & nunggu decode
// dari nol lagi. Sekarang begitu decode selesai, PCM-nya ditulis ke disk sekali, dan
// pas apk dibuka lagi tinggal dibaca balik mentah-mentah (tanpa decode, tanpa resample)
// -> jauh lebih cepat, dan gak tergantung izin/URI lagu aslinya lagi.
//
// Disimpan di filesDir (BUKAN cacheDir) karena cacheDir bisa dihapus sistem kapan aja
// pas storage sempit. Cuma 1 lagu yang disimpan (lagu terakhir yang berhasil dimuat);
// lagu baru otomatis menimpa yang lama, jadi pemakaian storage gak numpuk.
//
// Format file: header 20 byte little-endian
//   [magic][version][sampleRate][channels][totalShorts]
// lalu PCM short little-endian mentah sebanyak totalShorts.
object MusicCache {
    private const val FILE_NAME = "music_cache.pcm"
    private const val TMP_NAME = "music_cache.tmp"
    private const val MAGIC = 0x4B474D43 // "KGMC"
    private const val VERSION = 1
    private const val SAMPLE_RATE = 48000 // harus sama dengan ENGINE_SAMPLE_RATE di MusicLoader
    private const val HEADER_BYTES = 20
    private const val CHUNK_SHORTS = 64 * 1024 // 128KB per putaran baca/tulis

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun tmpFile(context: Context) = File(context.filesDir, TMP_NAME)

    // Dipanggil dari IO thread setelah lagu berhasil dimuat ke engine. Ditulis dulu ke
    // file sementara, baru di-rename ke nama final -> kalau apk keburu di-kill di
    // tengah nulis, file lama tetap utuh (gak pernah ada cache setengah jadi yang
    // dibaca sebagai lagu rusak). @Synchronized biar 2 penyimpanan beruntun (user
    // ganti lagu cepat-cepat) gak nulis ke file sementara yang sama barengan.
    @Synchronized
    fun save(context: Context, pcm: ShortArray, channels: Int): Boolean {
        if (pcm.isEmpty() || channels <= 0) return false
        val tmp = tmpFile(context)
        return try {
            FileOutputStream(tmp).use { fos ->
                val ch = fos.channel
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(MAGIC)
                header.putInt(VERSION)
                header.putInt(SAMPLE_RATE)
                header.putInt(channels)
                header.putInt(pcm.size)
                header.flip()
                while (header.hasRemaining()) ch.write(header)

                val buf = ByteBuffer.allocateDirect(CHUNK_SHORTS * 2).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos < pcm.size) {
                    val n = minOf(CHUNK_SHORTS, pcm.size - pos)
                    buf.clear()
                    buf.asShortBuffer().put(pcm, pos, n)
                    buf.limit(n * 2)
                    while (buf.hasRemaining()) ch.write(buf)
                    pos += n
                }
                fos.fd.sync() // pastiin beneran nyampe disk sebelum di-rename
            }
            val dst = file(context)
            if (!tmp.renameTo(dst)) {
                dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (e: Throwable) {
            tmp.delete()
            false
        }
    }

    // Dipanggil dari IO thread pas apk baru dibuka. Balikin null kalau gak ada cache,
    // cache-nya rusak/kepotong (file otomatis dihapus), atau memori gak cukup buat
    // nampung PCM-nya (file TIDAK dihapus, karena filenya sendiri sehat).
    @Synchronized
    fun load(context: Context): MusicLoader.LoadedMusic? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            FileInputStream(f).use { fis ->
                val ch = fis.channel
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                while (header.hasRemaining()) {
                    if (ch.read(header) < 0) break
                }
                if (header.hasRemaining()) {
                    f.delete()
                    return null
                }
                header.flip()
                val magic = header.getInt()
                val version = header.getInt()
                val sampleRate = header.getInt()
                val channels = header.getInt()
                val total = header.getInt()

                val valid = magic == MAGIC &&
                    version == VERSION &&
                    sampleRate == SAMPLE_RATE &&
                    channels in 1..8 &&
                    total > 0 &&
                    total % channels == 0 &&
                    f.length() == HEADER_BYTES + total.toLong() * 2L
                if (!valid) {
                    f.delete()
                    return null
                }

                val pcm = ShortArray(total)
                val buf = ByteBuffer.allocateDirect(CHUNK_SHORTS * 2).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos < total) {
                    val n = minOf(CHUNK_SHORTS, total - pos)
                    buf.clear()
                    buf.limit(n * 2)
                    while (buf.hasRemaining()) {
                        if (ch.read(buf) < 0) {
                            f.delete() // file kepotong di tengah
                            return null
                        }
                    }
                    buf.flip()
                    buf.asShortBuffer().get(pcm, pos, n)
                    pos += n
                }
                val durationSec = (total / channels).toDouble() / SAMPLE_RATE
                MusicLoader.LoadedMusic(pcm, channels, durationSec)
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Throwable) {
            null
        }
    }

    // Hapus cache lagu (belum dipanggil dari UI mana pun; disiapin kalau nanti mau
    // dikasih tombol "hapus cache lagu" di pengaturan).
    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        tmpFile(context).delete()
    }
}
