package com.kendang.realpads

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

// Decoder buat file lagu (mp3/aac/wav/ogg/dll, apapun yang didukung MediaCodec
// bawaan device) -> PCM 16-bit siap dipake. Beda sama WavLoader yang cuma parse
// WAV manual, di sini kita lewat MediaExtractor+MediaCodec bawaan Android biar
// gak perlu nulis decoder sendiri per-format.
object MusicLoader {
    data class LoadedMusic(val pcm: ShortArray, val channels: Int, val durationSec: Double)

    private const val ENGINE_SAMPLE_RATE = 48000

    // Dipanggil dari background thread (IO dispatcher) -> proses decode + resample
    // bisa makan waktu beberapa detik buat lagu panjang.
    //
    // PENTING: OutOfMemoryError adalah Error, BUKAN Exception -> "catch (e: Exception)"
    // gak bakal nangkep itu. Lagu yang kepanjangan/berat dulu bisa bikin OOM pas decode
    // lolos gak ketangkep sampe ke atas dan nutup paksa seluruh apknya. Makanya di sini
    // & di decode() sengaja nangkep Throwable/OutOfMemoryError juga, biar paling parah
    // cuma gagal load (toast), bukan apk keluar sendiri.
    fun decodeAndResample(context: Context, uri: Uri): LoadedMusic? {
        return try {
            val decoded = decode(context, uri) ?: return null
            val resampled = if (decoded.sampleRate == ENGINE_SAMPLE_RATE) {
                decoded.pcm
            } else {
                resampleLinear(decoded.pcm, decoded.sampleRate, decoded.channels, ENGINE_SAMPLE_RATE)
            }
            val frames = resampled.size / decoded.channels
            val durationSec = frames.toDouble() / ENGINE_SAMPLE_RATE
            LoadedMusic(resampled, decoded.channels, durationSec)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Throwable) {
            null
        }
    }

    private data class RawDecoded(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

    private fun decode(context: Context, uri: Uri): RawDecoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

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

            // FIX efisiensi besar: implementasi lama nampung hasil decode di
            // ByteArrayOutputStream (byte-per-byte lewat ByteArray `chunk` perantara
            // tiap iterasi), lalu di akhir masih ada 2 salinan penuh lagi:
            // output.toByteArray() (copy #1) dan ByteBuffer.wrap(...).asShortBuffer()
            // .get(pcm) (copy #2). Buat lagu 5 menit stereo 48kHz (~57MB PCM), itu
            // artinya sampai 3-4 salinan penuh buffer yang bertumpuk sesaat di memori
            // -> ini penyebab utama "loading lama" dan "sering gagal load" (OOM) yang
            // dikeluhkan. Sekarang decode nulis LANGSUNG ke satu ShortArray tunggal
            // yang di-preallocate dari estimasi durasi (dan digrow dgn doubling kalau
            // meleset, sama seperti strategi ArrayList/ByteArrayOutputStream tapi buat
            // short langsung, tanpa transit lewat byte) - dari ~4 salinan jadi ~1.
            val estimatedFrames = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                val durationSecEst = durationUs / 1_000_000.0
                (durationSecEst * sampleRate).toLong().coerceIn(0L, 100_000_000L)
            } else 0L
            var pcmOut = ShortArray(maxOf(1, (estimatedFrames * channels).toInt()))
            var pcmLen = 0

            fun ensureCapacity(additionalShorts: Int) {
                val needed = pcmLen + additionalShorts
                if (needed > pcmOut.size) {
                    // Doubling (amortized O(1) per append) - teknik yang sama dipakai
                    // ArrayList/StringBuilder/ByteArrayOutputStream, jadi jarang banget
                    // butuh realloc ulang walau estimasi durasi di atas meleset.
                    var newSize = maxOf(pcmOut.size, 1)
                    while (newSize < needed) newSize *= 2
                    pcmOut = pcmOut.copyOf(newSize)
                }
            }

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
                                // Baca langsung sebagai short little-endian dari output buffer
                                // milik codec, bulk get() satu kali per chunk - gak ada lagi
                                // ByteArray `chunk` perantara maupun ByteArrayOutputStream.
                                outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunkShorts = bufferInfo.size / 2
                                ensureCapacity(chunkShorts)
                                outBuffer.asShortBuffer().get(pcmOut, pcmLen, chunkShorts)
                                pcmLen += chunkShorts
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

            // Satu-satunya salinan penuh yang masih ada: pas trim ke ukuran pas
            // (pcmOut biasanya sedikit lebih besar dari pcmLen karena doubling).
            val pcm = if (pcmLen == pcmOut.size) pcmOut else pcmOut.copyOf(pcmLen)

            return RawDecoded(pcm, sampleRate, channels)
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    // Resample linear sederhana (bukan sinc/polyphase) -> cukup buat playback lagu,
    // gak butuh presisi studio. channels dipertahankan apa adanya (mono tetap mono,
    // stereo tetap stereo); duplikasi ke stereo buat mixing dilakuin di native side.
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
}
