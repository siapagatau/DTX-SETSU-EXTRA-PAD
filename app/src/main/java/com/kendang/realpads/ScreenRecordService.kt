package com.kendang.realpads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.DisplayMetrics
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

// Rekam layar SENDIRI di dalam app, BUKAN lewat screen recorder pihak ketiga:
//
//  - VIDEO: MediaProjection -> VirtualDisplay -> Surface -> MediaCodec (H.264)
//    encoder. Ini alur video standar screen recorder Android pada umumnya.
//
//  - AUDIO: SENGAJA BUKAN AudioPlaybackCapture (API yang "nyadap" audio app
//    dari mixer sistem, kayak screen recorder umum). Di sini audio diambil
//    LANGSUNG dari buffer PCM internal yang sama persis dipakai fitur REC
//    audio-only (lihat AudioEngine.nativeDrainRecording() / nativeStopRecording()
//    -> PadEngine::recordBuffer_, diisi di onAudioReady dari mixdown pad+musik).
//    Karena sumbernya persis output mixer sebelum dikirim ke hardware, hasilnya
//    gak kena potongan/reroute/resampling tambahan apa pun dari sistem - lebih
//    akurat daripada "nyadap dari luar".
//
//  - Video+audio ini dimux jadi SATU file .mp4 (2 track) lewat MediaMuxer,
//    dengan timestamp video mengikuti clock video encoder sendiri (didorong
//    VirtualDisplay secara real-time) dan timestamp audio dihitung dari jumlah
//    sample yang sudah di-encode (sample-accurate, bukan wall-clock) - sehingga
//    drift antara video & audio dalam rekaman panjang tetap minimal.
//
// PENTING soal exclusive mode: satu-satunya titik singgung service ini ke audio
// native adalah nativeStartRecording()/nativeDrainRecording()/nativeStopRecording(),
// yang SEMUANYA cuma MEMBACA (dan mengosongkan) recordBuffer_ - tidak pernah
// membuka stream baru, tidak reconfigure Oboe, tidak sentuh AudioStream apa pun.
// Jadi jalur audio exclusive yang sedang berjalan (via AudioEngine.nativeResume(),
// dll) TIDAK PERNAH diganggu oleh fitur ini - feel main kendang tetap 100% sama.
//
// TRADE-OFF (lihat juga catatan di MainActivity tempat fitur ini dipanggil):
// - REC audio-only (tombol REC biasa) dan REC LAYAR sama-sama makai flag
//   recording_ tunggal di native (lewat nativeStartRecording/StopRecording),
//   jadi TIDAK BISA dipakai bersamaan - MainActivity yang menjaga ini dengan
//   saling menonaktifkan kedua tombol selama salah satunya aktif.
// - Kalau user mencabut izin dari notifikasi sistem "casting/recording" bawaan
//   Android, MediaProjection.Callback.onStop() di bawah akan langsung
//   menghentikan & menyimpan rekaman yang sudah berjalan (bukan membuang).
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.kendang.realpads.action.START_SCREEN_RECORD"
        const val ACTION_STOP = "com.kendang.realpads.action.STOP_SCREEN_RECORD"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"

        private const val NOTIF_CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 4242

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val TIMEOUT_US = 10_000L
        // Jeda kalau belum ada PCM baru yang bisa di-drain, biar audio thread gak
        // busy-loop makan CPU pas gak ada suara yang lagi kepencet.
        private const val AUDIO_IDLE_SLEEP_MS = 30L

        // Callback ke MainActivity (app single-activity, jadi cukup dipegang statik
        // sederhana - bukan event bus penuh). SELALU dipanggil di main thread -
        // termasuk yang dipicu dari thread encoder (lihat postMain/reportError di bawah).
        var onFinished: ((file: File, durationMs: Long) -> Unit)? = null
        var onError: ((message: String) -> Unit)? = null
        var onTick: ((elapsedMs: Long) -> Unit)? = null

        // Dicek dari MainActivity buat saling menonaktifkan tombol REC audio-only
        // vs REC LAYAR selama salah satunya jalan (lihat catatan trade-off di atas).
        // Bukan `private set` yang "dipaksa" Kotlin - cuma disiplin, cuma diubah
        // dari dalam service ini sendiri.
        @Volatile
        var isSessionActive: Boolean = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    @Volatile private var muxerStarted = false
    // True kalau waitForMuxerReady() sudah pernah nyerah nunggu (muxer gak kunjung start).
    // Setelah itu gak nunggu lagi tiap sample - dulu tiap sample nunggu 3 detik penuh,
    // bikin thread video macet & rekaman gagal kesimpen.
    @Volatile private var muxerWaitGaveUp = false
    // Titik nol timestamp video (mikrodetik) - lihat normalizeVideoPts().
    private var videoPtsBaseUs = Long.MIN_VALUE
    // Timestamp sample terakhir yang ditulis per track (mikrodetik) - cuma buat log
    // diagnosa durasi video vs audio di stopRecordingInternal.
    @Volatile private var lastVideoPtsUs = 0L
    @Volatile private var lastAudioPtsUs = 0L
    // videoThread & audioThread sama-sama bisa nulis ke muxer (2 track independen),
    // jadi akses ke muxer + flag muxerStarted + kedua trackIndex WAJIB disatuin di
    // belakang satu lock ini.
    private val muxerLock = java.lang.Object()

    private val running = AtomicBoolean(false)
    private var startTimeNs = 0L
    // Dipakai buat menghitung presentation timestamp audio dari JUMLAH SAMPLE yang
    // sudah di-encode (sample-accurate), BUKAN dari System.nanoTime() - supaya audio
    // gak "ngambang" dari video walau ada jeda kecil antar-drain PCM.
    private var totalAudioFramesEncoded = 0L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private lateinit var outputFile: File

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    // FIX FORCE CLOSE: callback onError dulu dipanggil LANGSUNG dari thread encoder
    // (ScreenRec-Video / ScreenRec-Audio) - padahal isinya di MainActivity itu
    // Toast.makeText(), yang WAJIB di main thread. Kalau ada error encoder (mis.
    // pas STOP), Toast dari thread tanpa Looper langsung throw RuntimeException di
    // dalam blok catch, tanpa ada yang nangkep -> app force close. Sekarang semua
    // callback ke UI dilewatkan lewat sini biar pasti jalan di main thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun reportError(message: String) {
        postMain { onError?.invoke(message) }
    }

    // Error dari thread encoder: kalau rekaman masih jalan, hentikan bersih (sekali
    // saja) + kabari UI. Kalau memang lagi proses berhenti, cukup dicatat di log -
    // file yang sempat ke-mux tetap dipakai, bukan dibuang.
    private fun handleEncoderFailure(label: String, t: Throwable) {
        Log.e("ScreenRecordService", "$label gagal", t)
        if (running.get()) {
            stopRecordingInternal(errorMessage = "$label error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // Timestamp frame video dari VirtualDisplay itu clock sistem (uptime), BUKAN mulai
    // dari 0, sedangkan timestamp audio dihitung dari 0 (jumlah sample). Kalau
    // dua-duanya ditulis apa adanya ke MediaMuxer, video jadi tertunda jam-jaman /
    // file rusak. Jadi video dinormalisasi ke titik nol yang sama dengan audio:
    // pakai waktu start service kalau clock-nya sejalan (selisih < 5 detik), kalau
    // enggak pakai frame pertama sebagai nol.
    private fun normalizeVideoPts(rawUs: Long): Long {
        if (videoPtsBaseUs == Long.MIN_VALUE) {
            val startUs = startTimeNs / 1000L
            videoPtsBaseUs = if (kotlin.math.abs(rawUs - startUs) < 5_000_000L) startUs else rawUs
        }
        return (rawUs - videoPtsBaseUs).coerceAtLeast(0L)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User cabut izin lewat notifikasi sistem "layar sedang direkam/di-cast"
            // -> WAJIB berhenti bersih SEKARANG (bukan nunggu tombol STOP di app),
            // dan tetap simpan apa yang sempat kerekam sejauh ini.
            stopRecordingInternal(errorMessage = null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running.get()) return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val density = intent.getIntExtra(EXTRA_DENSITY, DisplayMetrics.DENSITY_DEFAULT)
                if (resultData == null || width <= 0 || height <= 0) {
                    reportError("Izin rekam layar tidak lengkap")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                startRecording(resultCode, resultData, width, height, density)
            }
            ACTION_STOP -> stopRecordingInternal(errorMessage = null)
            else -> if (!running.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // SETUP
    // ---------------------------------------------------------------------

    private fun startRecording(resultCode: Int, resultData: Intent, width: Int, height: Int, densityDpi: Int) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                reportError("Gagal membuat MediaProjection")
                stopSelf()
                return
            }
            mediaProjection = projection
            projection.registerCallback(projectionCallback, null)

            outputFile = File(cacheDir, "screenrec_${System.currentTimeMillis()}.mp4")

            // Lebar/tinggi digenapkan ke kelipatan 2 - syarat encoder H.264 di
            // hampir semua device (odd dimension bisa gagal configure()/crash).
            val evenWidth = width - (width % 2)
            val evenHeight = height - (height % 2)

            // ---- VIDEO ENCODER (H.264, input via Surface) ----
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, evenWidth, evenHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // ~5.5 bit per pixel per detik, dibulatkan wajar buat rekaman UI/gameplay
                // (bukan video sinematik) - cukup tajam tapi ukuran file gak bengkak.
                setInteger(MediaFormat.KEY_BIT_RATE, (evenWidth.toLong() * evenHeight * 4).toInt().coerceAtLeast(1_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // FIX VIDEO "HABIS" DI UJUNG (audio masih bunyi): VirtualDisplay cuma
                // ngirim frame kalau isi layar BERUBAH. Kalau di bagian akhir layar diam
                // (mis. berhenti mukul pad), frame video terakhir jauh lebih awal dari
                // akhir rekaman, sehingga track video di MP4 lebih pendek daripada
                // audio -> di pemutar bagian akhirnya kosong/durasi video "hilang".
                // Opsi ini nyuruh encoder mengulang frame sebelumnya kalau gak ada frame
                // baru dalam waktu segini (mikrodetik), jadi video terus ada frame
                // (~30 fps) sampai STOP walau layarnya diam.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_333L)
            }
            val vEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vEncoder.createInputSurface()
            vEncoder.start()
            videoEncoder = vEncoder
            inputSurface = surface

            // ---- VIRTUAL DISPLAY (dituju Surface video encoder di atas) ----
            virtualDisplay = projection.createVirtualDisplay(
                "KendangScreenRecord",
                evenWidth, evenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            // ---- AUDIO ENCODER (AAC, input via ByteBuffer manual) ----
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            }
            val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEncoder.start()
            audioEncoder = aEncoder

            // ---- MUXER (video+audio, ditulis dari 2 thread encoder di bawah) ----
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            muxerWaitGaveUp = false
            videoPtsBaseUs = Long.MIN_VALUE
            lastVideoPtsUs = 0L
            lastAudioPtsUs = 0L
            videoTrackIndex = -1
            audioTrackIndex = -1

            // Sumber PCM: SAMA seperti tombol REC audio-only (lihat catatan panjang
            // di kelas ini & di PadEngine::drainRecordBuffer) - mulai "recording_"
            // native SEKARANG, sebelum thread audio mulai narik drain pertamanya.
            AudioEngine.nativeStartRecording()

            running.set(true)
            isSessionActive = true
            startTimeNs = System.nanoTime()
            totalAudioFramesEncoded = 0L

            videoThread = Thread({ runVideoEncoderLoop() }, "ScreenRec-Video").also { it.start() }
            audioThread = Thread({ runAudioEncoderLoop() }, "ScreenRec-Audio").also { it.start() }

            tickerJob = serviceScope.launch {
                while (running.get()) {
                    delay(500)
                    val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L
                    postMain { onTick?.invoke(elapsedMs) }
                }
            }
        } catch (t: Throwable) {
            Log.e("ScreenRecordService", "startRecording gagal", t)
            reportError(t.message ?: "Gagal memulai rekam layar")
            runCatching { AudioEngine.nativeStopRecording() }
            releaseAllQuietly()
            isSessionActive = false
            running.set(false)
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------
    // VIDEO LOOP - dequeue output encoder video, tulis ke muxer, sampai EOS
    // ---------------------------------------------------------------------

    private fun runVideoEncoderLoop() {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var eos = false
        try {
            while (!eos) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                        maybeStartMuxerLocked()
                    }
                } else if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        waitForMuxerReady()
                        val data = encoder.getOutputBuffer(outIndex)
                        if (data != null) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = normalizeVideoPts(bufferInfo.presentationTimeUs)
                            lastVideoPtsUs = bufferInfo.presentationTimeUs
                            synchronized(muxerLock) {
                                if (muxerStarted) muxer?.writeSampleData(videoTrackIndex, data, bufferInfo)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
                // INFO_TRY_AGAIN_LATER -> loop lagi; dequeueOutputBuffer sendiri sudah
                // menunggu sampai TIMEOUT_US, jadi ini gak busy-loop kencang.
            }
        } catch (t: Throwable) {
            handleEncoderFailure("Video encoder", t)
        }
    }

    // ---------------------------------------------------------------------
    // AUDIO LOOP - narik PCM dari native tiap iterasi (nativeDrainRecording),
    // feed ke encoder AAC, dequeue output-nya, tulis ke muxer, sampai EOS.
    // ---------------------------------------------------------------------

    private fun runAudioEncoderLoop() {
        val encoder = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var eos = false
        var inputEosSent = false

        // Sisa PCM dari drain sebelumnya yang belum sempat kekirim penuh ke
        // encoder (kapasitas 1 input buffer encoder terbatas per panggilan).
        var pending: ShortArray? = null
        var pendingOffset = 0

        fun nextChunk(): ShortArray? {
            val cur = pending
            if (cur != null && pendingOffset < cur.size) return cur
            val fresh = AudioEngine.nativeDrainRecording()
            return if (fresh.isNotEmpty()) {
                pending = fresh
                pendingOffset = 0
                fresh
            } else {
                null
            }
        }

        fun ptsForFrames(frames: Long): Long = (frames * 1_000_000L) / SAMPLE_RATE

        try {
            while (!eos) {
                // ---- FEED INPUT ----
                if (!inputEosSent) {
                    val chunk = nextChunk()
                    if (chunk == null) {
                        if (!running.get()) {
                            // Recording sudah diminta berhenti & sudah gak ada PCM
                            // tersisa sama sekali -> ini giliran ngirim EOS ke encoder.
                            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                encoder.queueInputBuffer(
                                    inIndex, 0, 0,
                                    ptsForFrames(totalAudioFramesEncoded),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEosSent = true
                            }
                        } else {
                            // Belum ada suara baru yang kepencet - gak perlu ngapa-ngapain
                            // ke encoder sama sekali (drain buffer masih kosong itu wajar,
                            // bukan berarti recording bermasalah).
                            Thread.sleep(AUDIO_IDLE_SLEEP_MS)
                        }
                    } else {
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        // FIX force close: getInputBuffer() BISA balikin null kalau encoder
                        // barusan di-stop()/release() dari thread lain (mis. stopRecordingInternal
                        // yang nyerah nunggu thread ini join() lalu lanjut release codec-nya -
                        // lihat guard di stopRecordingInternal). Dulu di-force-unwrap pakai "!!",
                        // jadi NPE persis di titik ini -> force close app pas STOP. Sekarang kalau
                        // null, anggap encoder sudah gak bisa dipakai lagi & keluar dari loop
                        // dengan aman (data yang sempat ke-mux sebelumnya tetap tersimpan).
                        val buf = if (inIndex >= 0) encoder.getInputBuffer(inIndex) else null
                        if (inIndex >= 0 && buf == null) {
                            eos = true
                        } else if (buf != null) {
                            buf.clear()
                            val capacityShorts = buf.capacity() / 2
                            val remaining = chunk.size - pendingOffset
                            val take = minOf(capacityShorts, remaining)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.asShortBuffer().put(chunk, pendingOffset, take)
                            val sizeBytes = take * 2
                            val pts = ptsForFrames(totalAudioFramesEncoded)
                            encoder.queueInputBuffer(inIndex, 0, sizeBytes, pts, 0)
                            pendingOffset += take
                            totalAudioFramesEncoded += (take / CHANNELS).toLong()
                            if (pendingOffset >= chunk.size) pending = null
                        }
                        // inIndex < 0 (encoder input lagi penuh) -> chunk yang sama
                        // dicoba lagi di iterasi berikutnya, gak ada PCM yang kebuang.
                    }
                }

                // ---- DRAIN OUTPUT ----
                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        waitForMuxerReady()
                        val data = encoder.getOutputBuffer(outIndex)
                        if (data != null) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            synchronized(muxerLock) {
                                if (muxerStarted) muxer?.writeSampleData(audioTrackIndex, data, bufferInfo)
                                lastAudioPtsUs = bufferInfo.presentationTimeUs
                            }
                        }
                    }
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (isEos) {
                        eos = true
                        break
                    }
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                        maybeStartMuxerLocked()
                    }
                }
            }
        } catch (t: Throwable) {
            handleEncoderFailure("Audio encoder", t)
        }
    }

    // Muxer baru boleh di-start() sekali KEDUA track (video & audio) udah ke-add -
    // dipanggil dari kedua thread encoder, makanya harus di bawah muxerLock.
    private fun maybeStartMuxerLocked() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer?.start()
            muxerStarted = true
            muxerLock.notifyAll()
        }
    }

    // Dipanggil TANPA lock (dari luar synchronized block) sebelum sebuah thread
    // encoder nulis sample pertamanya - nunggu sebentar (maks ~3 detik) kalau
    // muxer belum start karena track satunya belum sempat nambahin format-nya.
    // Dalam praktiknya INFO_OUTPUT_FORMAT_CHANGED selalu jadi event PERTAMA dari
    // kedua encoder (sebelum ada data beneran), jadi ini nyaris gak pernah nunggu.
    private fun waitForMuxerReady() {
        if (muxerStarted || muxerWaitGaveUp) return
        synchronized(muxerLock) {
            var waited = 0L
            while (!muxerStarted && waited < 3000L) {
                try {
                    muxerLock.wait(50)
                } catch (_: InterruptedException) {
                }
                waited += 50
            }
            if (!muxerStarted) muxerWaitGaveUp = true
        }
    }

    // ---------------------------------------------------------------------
    // STOP
    // ---------------------------------------------------------------------

    private fun stopRecordingInternal(errorMessage: String?) {
        if (!running.getAndSet(false)) {
            // Sudah dihentikan / lagi proses berhenti - cegah double-stop (bisa
            // kepicu bareng dari tombol STOP di app + MediaProjection.Callback.onStop()
            // yang keduanya bisa datang hampir bersamaan).
            return
        }
        isSessionActive = false
        tickerJob?.cancel()

        val vThread = videoThread
        val aThread = audioThread

        serviceScope.launch {
            // FIX "video kayak terjeda tapi enggak, suaranya masih ada" di ujung
            // rekaman: dulu videoEncoder.signalEndOfInputStream() ditembak di SINI,
            // LANGSUNG pas tombol STOP dipencet - padahal audioThread biasanya
            // masih punya backlog PCM yang belum sempat di-drain/di-encode (drain
            // jalan sedikit demi sedikit tiap iterasi, bukan sekali borong) dan
            // baru berhenti setelah backlog itu benar-benar habis. Karena video
            // sudah di-EOS-kan duluan, video BERHENTI DAPET FRAME BARU (termasuk
            // repeat-frame dari KEY_REPEAT_PREVIOUS_FRAME_AFTER, yang cuma jalan
            // selama encoder belum EOS) padahal audio MASIH nulis sample untuk
            // durasi backlog itu ke muxer -> track video jadi lebih pendek dari
            // track audio, sehingga pas diputar, gambar freeze di frame terakhir
            // sementara suara tetap lanjut (persis kayak "video macet tapi
            // enggak macet, suara masih ada").
            //
            // Fix-nya: tunggu audioThread BENERAN selesai (drain habis + EOS
            // internalnya sendiri sudah dikirim & ditulis ke muxer) DULU, baru
            // sinyal EOS video. Selama menunggu itu, video terus dapet frame
            // (repeat-frame kalau layar diam) sehingga durasinya otomatis ikut
            // menutupi seluruh durasi audio - video & audio berhenti bareng,
            // gak ada lagi bagian akhir yang video-nya "kehabisan" duluan.
            aThread?.join(5000)

            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (_: Throwable) {
            }

            // Video: sinyal EOS lewat input Surface -> videoThread otomatis berhenti
            // sendiri begitu ketemu BUFFER_FLAG_END_OF_STREAM di output-nya (lihat
            // runVideoEncoderLoop).
            //
            // WAJIB tunggu thread encoder video benar-benar selesai nulis semua
            // sample (termasuk tail frame) sebelum muxer di-stop() - kalau
            // muxer.stop() dipanggil duluan, writeSampleData yang masih diproses
            // thread lain bisa exception atau bikin file jadi kepotong/corrupt.
            vThread?.join(5000)

            // Aman sekarang: drain terakhir PCM sudah dipakai habis oleh
            // audioThread lewat nativeDrainRecording() sebelum thread itu keluar.
            // nativeStopRecording() di sini cuma mastiin recording_ native mati &
            // buffer bersih - hasil ShortArray-nya sendiri sudah gak dipakai lagi.
            runCatching { AudioEngine.nativeStopRecording() }

            val muxerWasStarted = muxerStarted
            try {
                synchronized(muxerLock) {
                    if (muxerStarted) muxer?.stop()
                }
            } catch (_: Throwable) {
                // Kalau salah satu track sama sekali belum kebagian sample apa pun
                // (mis. rekaman di-stop SANGAT cepat), muxer.stop() bisa exception -
                // filenya tetap dianggap gagal lewat pengecekan panjang file di bawah.
            }
            releaseAllQuietly()

            val durationMs = (System.nanoTime() - startTimeNs) / 1_000_000L
            Log.i(
                "ScreenRecordService",
                "durasi akhir: total=${durationMs}ms video=${lastVideoPtsUs / 1000}ms audio=${lastAudioPtsUs / 1000}ms"
            )

            withContext(Dispatchers.Main) {
                when {
                    errorMessage != null -> {
                        onError?.invoke(errorMessage)
                        outputFile.delete()
                    }
                    !muxerWasStarted || !outputFile.exists() || outputFile.length() <= 0L -> {
                        onError?.invoke("Rekaman layar gagal disimpan (file kosong)")
                        runCatching { outputFile.delete() }
                    }
                    else -> onFinished?.invoke(outputFile, durationMs)
                }
                runCatching { stopForegroundCompat() }
                stopSelf()
            }
        }
    }

    private fun releaseAllQuietly() {
        runCatching { muxer?.release() }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
    }

    override fun onDestroy() {
        // Jaring pengaman kalau service dimatikan sistem di luar alur stop normal
        // (mis. dibunuh paksa) - pastikan recording_ native gak nyangkut true dan
        // semua resource dilepas, biar gak ganggu fitur REC audio-only berikutnya.
        if (running.getAndSet(false)) {
            isSessionActive = false
            runCatching { AudioEngine.nativeStopRecording() }
            releaseAllQuietly()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // NOTIFIKASI (wajib buat foreground service tipe mediaProjection)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "Rekam Layar", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi selagi rekam layar+audio kendang berjalan"
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Merekam layar")
            .setContentText("RealPads Kendang sedang merekam layar & audio")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
