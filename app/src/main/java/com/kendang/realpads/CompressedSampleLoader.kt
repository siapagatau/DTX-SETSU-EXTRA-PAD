package com.kendang.realpads

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// Decoder buat sample PAD yang formatnya BUKAN WAV (mp3/aac/m4a/ogg/flac/dll -
// apapun yang didukung MediaCodec bawaan device), dipakai sebagai fallback kalau
// WavLoader.parse() gagal (artinya file yang dipilih user bukan WAV mentah).
//
// Sengaja dibikin OBJECT TERPISAH dari MusicLoader (bukan reuse langsung) karena
// input di sini berupa ByteArray (dari SAF InputStream atau entry ZIP import
// preset), bukan Uri seperti MusicLoader - beda kebutuhan, jadi ditulis mandiri
// (sama prinsipnya dengan kenapa WavLoader & MusicLoader masing-masing punya
// resampleLinear sendiri, lihat catatan di WavLoader).
//
// PENTING soal "delay": decode di sini SELALU dijalankan SEKALI SAJA pas user
// milih/assign file ke pad (proses import, di background thread/IO dispatcher -
// lihat pemanggilnya di MainActivity & PresetStorage). Hasilnya berupa PCM biasa
// yang lalu disimpan ulang sebagai WAV kanonik (lihat encodeToWavBytes) dan dari
// situ dan seterusnya dimainkan lewat jalur SAMA PERSIS kayak sample WAV asli -
// tidak ada decoding MP3 tiap kali pad dipukul, jadi tidak menambah latency
// realtime sama sekali.
object CompressedSampleLoader {
    private const val ENGINE_SAMPLE_RATE = 48000

    private data class RawDecoded(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

    // Entry point utama: coba decode bytes apapun formatnya (selain WAV, yang
    // harusnya udah ditangani WavLoader.parse duluan oleh pemanggil) jadi WavData
    // siap-pakai (PCM sudah di-resample ke ENGINE_SAMPLE_RATE + isBass sudah dihitung).
    fun decode(context: Context, bytes: ByteArray): WavData? {
        return try {
            val raw = decodeRaw(context, bytes) ?: return null
            val resampled = if (raw.sampleRate != ENGINE_SAMPLE_RATE && raw.sampleRate > 0) {
                resampleLinear(raw.pcm, raw.sampleRate, raw.channels, ENGINE_SAMPLE_RATE)
            } else {
                raw.pcm
            }
            // Sama seperti WavLoader.parse - ratakan volume sekali di sini pas
            // decode, biar sample dari MP3/AAC/dll juga konsisten levelnya dengan
            // sample WAV asli, tanpa perlu proses manual di luar app.
            val pcm = VolumeNormalizer.normalize(resampled)
            val isBass = estimateIsBass(pcm, raw.channels, ENGINE_SAMPLE_RATE)
            WavData(pcm, raw.channels, ENGINE_SAMPLE_RATE, isBass)
        } catch (e: OutOfMemoryError) {
            // Sample yang aneh/rusak dalam teori gak akan sebesar lagu, tapi tetap
            // dijaga - gagal decode paling banter cuma toast, bukan nutup paksa apk.
            null
        } catch (e: Throwable) {
            null
        }
    }

    // MediaExtractor butuh path file/Uri, gak bisa langsung dari ByteArray -> tulis
    // dulu ke cache internal (private ke app, otomatis kebersihin lewat finally di
    // bawah) baru dibaca dari situ.
    private fun decodeRaw(context: Context, bytes: ByteArray): RawDecoded? {
        val tempFile = File(context.cacheDir, "pad_import_${UUID.randomUUID()}.tmp")
        try {
            tempFile.writeBytes(bytes)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tempFile.absolutePath)

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                // Sample drum/kendang biasanya pendek (beda sama lagu di MusicLoader),
                // jadi gak perlu prealokasi kapasitas seagresif MusicLoader - default
                // growable ByteArrayOutputStream cukup.
                val output = ByteArrayOutputStream()
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                try {
                    while (!sawOutputEOS) {
                        if (!sawInputEOS) {
                            val inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inIndex)
                                val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (outIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                if (outBuffer != null) {
                                    val chunk = ByteArray(bufferInfo.size)
                                    outBuffer.position(bufferInfo.offset)
                                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    outBuffer.get(chunk)
                                    output.write(chunk)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                        }
                    }
                } finally {
                    codec.stop()
                    codec.release()
                }

                val outBytes = output.toByteArray()
                val shortBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcm = ShortArray(shortBuffer.remaining())
                shortBuffer.get(pcm)

                return RawDecoded(pcm, sampleRate, channels)
            } catch (e: Exception) {
                return null
            } finally {
                extractor.release()
            }
        } finally {
            tempFile.delete()
        }
    }

    // Sengaja diduplikasi (bukan share dari WavLoader/MusicLoader) - lihat alasan
    // duplikasi di WavLoader.
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

    // Sama persis logikanya dengan WavLoader.estimateIsBass (lihat komentar di sana
    // buat penjelasan lengkap ZCR-nya) - diduplikasi di sini dengan alasan yang sama.
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

        return crossingsPerSecond < 400.0
    }

    // Bungkus PCM 16-bit hasil decode jadi file WAV kanonik (header RIFF standar),
    // biar bisa disimpan & diperlakukan lewat pipeline PresetStorage yang SUDAH ADA
    // (saveSample/loadAllIntoEngine/export semuanya cuma ngerti .wav) TANPA perlu
    // ubah format penyimpanan sama sekali. Sample dari MP3/dll jadi tersimpan di
    // disk sebagai .wav biasa (persis kayak native WAV), dan pas dibaca lagi waktu
    // app dibuka ulang, WavLoader.parse bakal baca ini seperti WAV asli manapun.
    fun encodeToWavBytes(pcm: ShortArray, channels: Int, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))

        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16) // PCM fmt chunk size
        bb.putShort(1) // audio format = PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        val byteRate = sampleRate * channels * 2
        bb.putInt(byteRate)
        val blockAlign = channels * 2
        bb.putShort(blockAlign.toShort())
        bb.putShort(16) // bits per sample

        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        for (s in pcm) bb.putShort(s)

        return bb.array()
    }
}
