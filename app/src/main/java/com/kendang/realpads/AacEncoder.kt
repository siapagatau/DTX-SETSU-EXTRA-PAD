package com.kendang.realpads

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Ubah hasil rekaman (PCM stereo interleaved dari AudioEngine.nativeStopRecording())
// jadi file M4A/AAC beneran, pake MediaCodec bawaan Android (bukan library native
// pihak ketiga kayak TAndroidLame sebelumnya). Alasan ganti dari MP3 ke AAC:
// libandroidlame.so bawaan TAndroidLame dikompilasi pake NDK lama yang gak support
// 16 KB page size, dan library-nya sendiri udah gak di-maintain sejak lama jadi gak
// bisa di-upgrade -> Play Console nolak/ngasih warning upload. MediaCodec itu API
// sistem Android sendiri (bukan .so yang ikut ke-bundle di APK/AAB), jadi masalah
// 16 KB ini otomatis gak akan pernah muncul lagi.
//
// Selalu dipanggil dari background thread (Dispatchers.IO) sama kayak encoder lama,
// karena walau cepat, ini tetap kerja CPU yang gak boleh nge-block UI thread buat
// rekaman yang agak panjang.
object AacEncoder {
    private const val SAMPLE_RATE = 48000
    private const val CHANNELS = 2
    private const val BITRATE_BPS = 192_000
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L

    // outputFile: perlu File biasa (bukan langsung OutputStream dari SAF) karena
    // MediaMuxer butuh path/FileDescriptor buat nulis container MP4 (.m4a), gak bisa
    // nulis ke OutputStream sembarangan. Makanya dipanggil dengan file temp dulu di
    // MainActivity, baru hasilnya di-copy ke Uri SAF yang dipilih user.
    //
    // Catatan efisiensi: fungsi ini TIDAK bikin salinan penuh dari `pcm` di memori.
    // Konversi short->byte little-endian dilakukan langsung per-chunk ke input buffer
    // milik MediaCodec (lihat loop di bawah), jadi peak memory selama proses simpan
    // rekaman kira-kira cuma seukuran `pcm` itu sendiri, bukan 2x lipatnya.
    fun encodeToAac(pcm: ShortArray, outputFile: File) {
        if (pcm.isEmpty()) return

        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        // FIX efisiensi: dulu SELURUH ShortArray pcm diubah jadi satu ByteArray raksasa
        // (pcm.size * 2 byte) di awal, SEBELUM encoder mulai jalan sama sekali. Untuk
        // rekaman panjang itu artinya PCM ada 2x di memori sekaligus (ShortArray asli +
        // ByteArray duplikatnya) tepat pas encoder paling butuh headroom -> ini salah
        // satu penyebab utama "gagal menyimpan" (OOM) di rekaman yang agak panjang,
        // plus ada jeda di awal proses (loop konversi penuh) sebelum encoding kelihatan
        // mulai jalan. Sekarang konversi short->byte dilakukan LANGSUNG per-chunk, pas
        // ngisi tiap input buffer milik encoder saja - gak ada lagi salinan penuh kedua.
        var shortOffset = 0 // posisi baca di pcm (dalam satuan sample, bukan byte)
        var presentationTimeUs = 0L
        val bytesPerSampleFrame = 2 * CHANNELS // 16-bit x 2 channel

        try {
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        val remainingShorts = pcm.size - shortOffset
                        if (remainingShorts <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            // Isi input buffer encoder langsung lewat ShortBuffer little-endian
                            // (bukan nyalin byte manual ke ByteArray perantara) - satu bulk put()
                            // per chunk, tanpa alokasi tambahan sama sekali di loop ini.
                            val capacityShorts = inputBuffer.capacity() / 2
                            val chunkShorts = minOf(capacityShorts, remainingShorts)
                            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            inputBuffer.asShortBuffer().put(pcm, shortOffset, chunkShorts)
                            val chunkSize = chunkShorts * 2
                            codec.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                            shortOffset += chunkShorts
                            val framesThisChunk = chunkSize / bytesPerSampleFrame
                            presentationTimeUs += (framesThisChunk * 1_000_000L) / SAMPLE_RATE
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0 // config buffer gak ditulis langsung ke muxer
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        val encodedBuffer: ByteBuffer = codec.getOutputBuffer(outputIndex)!!
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                    }
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) {
                        outputDone = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Throwable) {}
            muxer.release()
        }
    }
}
