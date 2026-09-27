package com.kendang.realpads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Palet warna sama persis dengan versi web (flat, no green)
val BgColor = Color(0xFF111113)
val PanelColor = Color(0xFF3C3C3F)
val PadColor = Color(0xFF4D4D50)
val PadLineColor = Color(0xFF232325)
val PadTextColor = Color(0xFFA9A9AD)
val CtrlPanelColor = Color(0xFF1C1C1E)
val TextDim = Color(0xFF8C8C90)
// Warna aksen (oren) sekarang custom & persist -> lihat ThemeState.kt

// Ukuran teks seragam buat hampir semua label di UI (tombol, angka pad, dsb) biar
// tampilan lebih rapi. Satu-satunya pengecualian: teks posisi/durasi lagu yang
// sedang diputar (tetap dibiarin kecil apa adanya, lihat MusicSidePanel).
val AppTextSize = 12.sp

// Padding "grup" dipakai SERAGAM buat 3 strip kontrol utama di sekeliling pad -
// panel preset kiri, panel musik kanan, dan strip tombol aksi bawah - biar jarak
// dari tepi grup ke konten & jarak antar-elemen di dalamnya sama persis di
// ketiganya (dulu beda-beda: kiri/kanan 10dp/7dp tapi bawah cuma 6dp vertical
// tanpa horizontal sama sekali, jadi kelihatan gak seragam).
val GroupOuterPaddingV = 8.dp
val GroupOuterPaddingH = 8.dp
val GroupItemSpacing = 6.dp

// Ukuran ikon buat tombol switch/record/play (pengganti emoji & label teks).
// Sengaja jauh lebih gede dari AppTextSize (12sp) supaya kebaca jelas sebagai
// ikon, bukan mepet/samar setara ukuran teks label lain.
val ControlIconSize = 24.dp

// --- Versi FREE: preset P3-P8 & tombol TEMA dikunci (ikon gembok), tap
// salah satunya ngarahin ke halaman upgrade Pro, bukan ke fiturnya. Cuma
// P1 & P2 yang kebuka bebas dipakai di versi ini. Versi PREMIUM (dibedain
// lewat BuildConfig.IS_PREMIUM, di-set per productFlavor di app/build.gradle.kts)
// semua presetnya kebuka & TEMA gak dikunci sama sekali - lihat pemakaian
// FREE_UNLOCKED_PRESETS & BuildConfig.IS_PREMIUM di bawah.
val FREE_UNLOCKED_PRESETS = if (BuildConfig.IS_PREMIUM) PresetStorage.MAX_PRESET else 2
const val PRO_UPGRADE_URL = "https://siapagatau.github.io/APK-DTX-PRO/"

fun openProUpgradeLink(context: android.content.Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRO_UPGRADE_URL)))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fullscreen immersive: sembunyiin status bar & nav bar biar pad benar-benar full-screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        AudioEngine.nativeInit()
        setContent {
            MaterialTheme {
                PadScreen()
            }
        }
    }

    // Lepas jalur audio exclusive begitu app ini gak lagi di depan (mis. user pindah
    // ke app kendang satunya, atau ke app lain apapun) -> supaya app itu bisa dapet
    // jalur exclusive juga, bukan kepaksa jatuh ke mode Shared yang kerasa lebih
    // keras & delay gara-gara app ini masih "nyekek" hardware audio di background.
    override fun onPause() {
        super.onPause()
        // Simpan posisi lagu terakhir (dibaca langsung dari native, paling akurat) -
        // onPause selalu kepanggil sebelum apk bisa ditutup/di-kill dari recent apps.
        val dur = AudioEngine.nativeGetMusicDuration()
        if (dur > 0.0) {
            SessionStorage.saveMusicPosition(this, AudioEngine.nativeGetMusicPosition(), dur)
        }
        AudioEngine.nativePause()
    }

    // Ambil lagi jalur exclusive begitu balik ke depan. Aman dipanggil walau
    // onCreate baru aja jalan (start() di sisi native udah dibikin idempoten),
    // jadi gak bakal buka stream dobel pas pertama kali app dibuka.
    override fun onResume() {
        super.onResume()
        AudioEngine.nativeResume()
    }

    override fun onDestroy() {
        // Kalau activity kehancur selagi rekam layar masih jalan, minta service berhenti
        // DULU sebelum engine native dibongkar - thread audio service masih narik PCM
        // dari engine itu (nativeDrainRecording) dan crash native kalau engine-nya
        // keburu hilang.
        if (ScreenRecordService.isSessionActive) {
            runCatching {
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    }
                )
            }
        }
        AudioEngine.nativeShutdown()
        super.onDestroy()
    }

    // Nangkep physical key event di level Activity buat trigger pad SELAGI dialog
    // Pengaturan LAGI GAK kebuka - keyboard USB maupun Bluetooth/wireless yang
    // dihubungin ke HP sama-sama nongol lewat jalur ini sebagai KeyEvent biasa.
    // CATATAN: begitu dialog Pengaturan (AlertDialog) kebuka, dia jadi window
    // Android TERPISAH yang ngerebut fokus input - key event fisik SETELAH itu
    // gak lagi nyampe ke sini, melainkan ketangkep di compose tree dialog itu
    // sendiri (lihat Modifier.onKeyEvent di PadVolumeDialog.kt, dipakai buat
    // proses "rekam tombol").
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // repeatCount == 0 -> cuma tepi awal penekanan yang dihitung sebagai 1
            // pukulan. Kalau tombolnya ditahan lama, auto-repeat OS (yang ngirim
            // event ACTION_DOWN berulang dengan repeatCount naik) SENGAJA diabaikan -
            // drum pad fisik ngirim 1 hit per tekan, bukan nyerocos selama ditahan.
            if (event.repeatCount == 0) {
                val pad = PadKeyBindingStorage.padForKeyCode(event.keyCode)
                if (pad != null) {
                    KeyTriggerBus.emit(pad)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun PadScreen() {
    var currentPreset by remember { mutableIntStateOf(1) }
    // Preset sekarang ada 8 total, dibagi 2 "bank" isi 4 biar tetep muat di strip bawah
    // tanpa nge-geser tombol lain: bank 0 nampilin P1-P4, bank 1 nampilin P5-P8. Pindah
    // bank cukup dengan nge-tap tombol switch di sebelah REC - tiap tap gantian
    // 1-4 <-> 5-8, gak perlu buka menu apa-apa (lihat tombol switch di strip bawah).
    var presetBank by remember { mutableIntStateOf(0) }
    var selectedPad by remember { mutableStateOf<Int?>(null) }
    // Kedipan pad SEKARANG digambar dari WAKTU asli (System.nanoTime), bukan dari
    // flag boolean yang di-set true lalu di-false-kan lewat coroutine delay(90).
    //
    // Kenapa diganti: pendekatan boolean+delay lama rawan 2 masalah persis yang
    // dikeluhkan user -
    //  1) "gak ada proses 90ms" -> boolean cuma ON/OFF instan, jadi kedipnya kayak
    //     lampu di-klik, bukan animasi yang keliatan berjalan selama 90ms.
    //  2) "kelipan kelewat pas ketuk cepat" -> kalau pad yang sama kena tap 2x dalam
    //     1 frame yang sama (misal ON->OFF->ON terjadi sebelum Compose sempet gambar
    //     ulang), Compose cuma lihat NILAI TERAKHIR pas recompose - jadi transisi di
    //     tengah bisa "collapse"/ketelen dan gak pernah kegambar sama sekali.
    //
    // Fix-nya: tiap pad nyimpen KAPAN terakhir dipukul (nanotime), lalu tiap FRAME
    // (lewat withFrameNanos di bawah) intensitas warnanya dihitung ulang dari selisih
    // waktu asli sekarang vs waktu pukulan itu. Karena dihitung dari waktu asli setiap
    // frame (bukan dari flag yang bisa numpuk/ke-overwrite), setiap pukulan PASTI
    // menghasilkan minimal 1 frame kegambar di intensitas penuh (1f) sebelum meluruh
    // ke 0 selama 90ms - gak mungkin "kelewat" walau pad yang sama dipukul beruntun
    // secepat apapun, karena tiap pukulan nulis ulang start time-nya sendiri.
    val padHitTimeNanos = remember { mutableStateMapOf<Int, Long>() }
    // Intensitas render tiap pad (1f = paling terang/baru dipukul, meluruh ke 0f
    // selama FLASH_DURATION_NANOS). Ini yang dibaca langsung oleh Pad() buat nge-blend
    // warnanya - dipisah dari padHitTimeNanos supaya Pad() gak perlu ngitung ulang tiap
    // baca (udah dihitung sekali per frame di loop LaunchedEffect di bawah).
    val flashIntensity = remember { mutableStateMapOf<Int, Float>() }
    var showColorPicker by remember { mutableStateOf(false) }
    // Tombol "LOAD" sekarang buka dialog pilihan sumber dulu (lokal vs online)
    // alih-alih langsung buka file picker - lihat LoadSourceChooserDialog &
    // OnlinePresetListDialog di OnlinePresetDialog.kt, dan OnlinePresetRepo.kt
    // buat logic ambil-daftar/download dari repo GitHub.
    var showLoadSourceChooser by remember { mutableStateOf(false) }
    var showOnlinePresetList by remember { mutableStateOf(false) }
    var onlinePresetsState by remember {
        mutableStateOf<OnlinePresetsUiState>(OnlinePresetsUiState.Loading)
    }
    // Nama file yang lagi didownload sekarang (null = gak ada yang lagi proses) -
    // dipakai buat nampilin spinner di baris yang bersangkutan & nge-disable baris
    // lain sementara biar gak ke-tap dobel.
    var downloadingPresetName by remember { mutableStateOf<String?>(null) }

    // Tombol "SET SUARA" sekarang juga buka dialog pilihan sumber dulu (lokal vs
    // online), pola persis sama kayak LoadSourceChooserDialog di atas - lihat
    // SetSoundSourceChooserDialog & OnlineSoundBrowserDialog di
    // OnlineSoundDialog.kt, dan OnlineSoundRepo.kt buat logic jelajah-folder/
    // download dari repo GitHub kumpulan-wav-dtx. Beda dari preset online:
    // di sini ada tahap PREVIEW (dengerin dulu WAV-nya sebelum beneran di-set)
    // dan navigasi FOLDER (kategori = folder, bisa nambah kategori baru cuma
    // dengan nambah folder di repo, gak perlu update apk).
    var showSetSoundSourceChooser by remember { mutableStateOf(false) }
    var showOnlineSoundBrowser by remember { mutableStateOf(false) }
    // Path folder yang lagi dibuka ("" = root repo) + stack path folder induk
    // (buat tombol kembali/breadcrumb) - direset ke root tiap dialog dibuka dari
    // awal (tombol SET SUARA ditekan lagi dari nol).
    var onlineSoundPath by remember { mutableStateOf("") }
    val onlineSoundPathStack = remember { mutableStateListOf<String>() }
    var onlineSoundState by remember {
        mutableStateOf<OnlineSoundUiState>(OnlineSoundUiState.Loading)
    }
    // Path file WAV yang lagi di-preview (null = gak ada) & apakah masih
    // nunggu MediaPlayer siap (buffering) buat file itu - dipakai nampilin
    // spinner sebentar di antara tap tombol play & suara beneran mulai.
    var previewingSoundPath by remember { mutableStateOf<String?>(null) }
    var isPreviewBuffering by remember { mutableStateOf(false) }
    // MediaPlayer buat preview streaming langsung dari download_url GitHub -
    // TIDAK pernah dipakai buat suara pad beneran (itu tetap lewat native
    // engine/Oboe seperti biasa), cuma buat didengerin dulu di dalam dialog.
    var previewPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    // Path file WAV yang lagi didownload+diset ke pad (null = gak ada yang lagi
    // proses) - dipakai nampilin spinner di baris itu & nge-disable aksi lain
    // sementara biar gak ke-tap dobel.
    var settingSoundPath by remember { mutableStateOf<String?>(null) }

    // Tutorial pengenalan (cuma tampil sekali seumur app, lihat TutorialPrefs di
    // TutorialOverlay.kt) yang nunjuk tombol LOAD biar user baru langsung ngerti
    // alur "LOAD -> Online dari GitHub" dari awal, bukan nyoba-nyoba sendiri. Posisi
    // asli tombol LOAD (loadButtonBoundsInRoot) diukur lewat onGloballyPositioned
    // di tombolnya sendiri di bawah, dipakai TutorialOverlay buat nge-highlight +
    // nunjuk panah ke situ persis.
    var showTutorial by remember { mutableStateOf(false) }
    var loadButtonBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    // Dialog "Pengaturan" (volume/tune/reverb per pad): showPadVolumeSettings ngontrol
    // tampil/enggaknya, padVolumes/padTunes/padReverbs nyimpen nilai 12 pad DI PRESET
    // YANG LAGI AKTIF - di-refresh dari native tiap dialog dibuka (lihat tombol
    // Pengaturan di bawah). padVolumes: 0f..1.5f, padTunes: -12f..12f (semitone),
    // padReverbs: 0f..1f (send ke bus reverb bersama).
    var showPadVolumeSettings by remember { mutableStateOf(false) }
    val padVolumes = remember { mutableStateMapOf<Int, Float>() }
    val padTunes = remember { mutableStateMapOf<Int, Float>() }
    val padReverbs = remember { mutableStateMapOf<Int, Float>() }
    // Choke per pad (true = default, pukulan baru motong pukulan lama di pad yang
    // sama). Lihat PadChokeStorage/PadEngine.setPadChoke buat detail kenapa ini
    // bisa dimatiin per pad (biar gak kedengeran "tet" di sample yang sengaja mau
    // dibiarin overlap/nyambung natural).
    val padChokes = remember { mutableStateMapOf<Int, Boolean>() }
    // Loop per pad (default false) - gaya DTX M12: kalau aktif, sekali ketuk pad-nya
    // muter sample-nya berulang tanpa jeda sampai diketuk lagi buat berhenti. Lihat
    // PadLoopStorage/PadEngine.setPadLoop buat detail "ketuk buat stop"-nya.
    val padLoops = remember { mutableStateMapOf<Int, Boolean>() }
    // "Stop" - choke ANTAR pad (fitur baru, beda dari padChokes di atas yang
    // choke SESAMA pad): kalau aktif buat sebuah pad, pad itu bakal berhenti
    // otomatis kalau salah satu pad lain yang dicentang di daftarnya (mask,
    // bitmask 12-bit) dipukul. Lihat PadCrossChokeStorage/PadEngine.
    // setPadCrossChokeMask buat detail lengkapnya.
    val padCrossChokeEnabled = remember { mutableStateMapOf<Int, Boolean>() }
    val padCrossChokeMask = remember { mutableStateMapOf<Int, Int>() }
    // Pad-pad (di preset yang lagi AKTIF) yang SAAT INI kedengeran lagi loop -
    // dipoll berkala dari native (lihat LaunchedEffect di bawah, AudioEngine.
    // nativeIsPadLooping) tiap ~90ms, BUKAN dihitung dari state lokal manapun,
    // karena loop bisa juga di-stop dari sumber lain (mis. keyboard fisik) yang
    // gak lewat jalur onPadDown yang sama. Dipakai PadGrid/Pad buat nentuin pad
    // mana yang harus nampilin slider tempo langsung di kotaknya sendiri.
    var loopingPads by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Nilai tempo LIVE yang lagi ditampilin per pad (0.5f..2f, 1f = normal) - state
    // UI-nya doang, sumber kebenaran aslinya ada di native (real-time) DAN di disk
    // lewat PadLoopTempoStorage (persist antar sesi loop maupun antar buka-tutup
    // apk). Disinkronin ke nilai TERKINI begitu sebuah pad BARU AJA masuk status
    // loopingPads (lihat LaunchedEffect polling di bawah) - BUKAN direset ke 1f
    // lagi seperti sebelumnya, karena tempo custom user sekarang sengaja diingat.
    val padLoopTempos = remember { mutableStateMapOf<Int, Float>() }
    // True kalau strip tombol bawah + panel musik lagi disembunyiin (gestur cubit),
    // biar area pad meluas penuh dan gak ada tombol yang ketutupan jari pas maen.
    var controlsHidden by remember { mutableStateOf(false) }

    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var pendingRecordingPcm by remember { mutableStateOf<ShortArray?>(null) }
    // Nama file yang disiapin pas rekaman baru selesai ditangkap (sebelum user
    // konfirmasi) - dipisah dari pendingRecordingPcm biar timestamp-nya pas
    // waktu berhenti rekam, bukan waktu tombol "YA" di dialog konfirmasi ditekan.
    var pendingRecordingFileName by remember { mutableStateOf("") }
    // True begitu hasil rekaman udah ada (gak kosong) dan nunggu user milih
    // "Simpan" atau "Buang" lewat dialog konfirmasi - dipisah dari langsung
    // buka file picker (recordSaveLauncher) biar user gak kaget tiba-tiba
    // disodorin dialog simpan file begitu tap STOP.
    var showRecordSaveConfirmDialog by remember { mutableStateOf(false) }
    // True selagi hasil rekaman lagi di-encode & ditulis ke file (proses ini bisa makan
    // waktu buat rekaman panjang) -> dipakai buat nampilin "MENYIMPAN..." di tombol REC
    // dan nyegah user nge-tap ulang di tengah proses simpan.
    var isSavingRecording by remember { mutableStateOf(false) }
    // True SEBENTAR banget pas tombol STOP baru dipencet: nativeStopRecording() harus
    // dipanggil di background thread (bukan lagi di UI thread kayak sebelumnya) karena
    // dia menyalin SELURUH buffer rekaman dari native ke Java sekali jalan - untuk
    // rekaman panjang itu bisa freeze UI thread beberapa detik (kerasa kayak macet)
    // dan di HP RAM kecil malah bisa OOM/ANR pas lagi nge-freeze. State ini cuma
    // buat kasih feedback visual ("MEMPROSES") selama jeda background itu.
    var isStoppingRecording by remember { mutableStateOf(false) }
    // Sama polanya kayak showRecordSaveConfirmDialog di atas, tapi buat tombol EXPORT
    // preset - dulu langsung buka file picker begitu ditekan, sekarang mampir dulu ke
    // dialog konfirmasi biar gak berasa tiba-tiba (misal ke-tap gak sengaja).
    var showExportConfirmDialog by remember { mutableStateOf(false) }

    // ---- REC LAYAR (video+audio, lihat ScreenRecordService.kt) ----
    // Beda dari isRecording (REC audio-only) di atas: ini video+audio -> hasilnya
    // .mp4, bukan .m4a, dan prosesnya jalan di ScreenRecordService (foreground
    // service), bukan langsung di sini. State di bawah cuma "cermin" UI dari
    // status service itu (via callback statik ScreenRecordService.onTick/
    // onFinished/onError, lihat DisposableEffect di bawah).
    var isScreenRecording by remember { mutableStateOf(false) }
    var screenRecordSeconds by remember { mutableIntStateOf(0) }
    // True sesaat pas tombol STOP rekam layar baru ditekan / MediaProjection
    // dicabut dari notifikasi sistem - encoder & muxer masih beres-beres di
    // background (join thread, muxer.stop(), dst) sebelum onFinished/onError
    // benar-benar dipanggil.
    var isStoppingScreenRecording by remember { mutableStateOf(false) }
    var pendingScreenRecordFile by remember { mutableStateOf<File?>(null) }
    var pendingScreenRecordFileName by remember { mutableStateOf("") }
    var showScreenRecordSaveConfirmDialog by remember { mutableStateOf(false) }
    var isSavingScreenRecord by remember { mutableStateOf(false) }

    // (preset, pad) -> apakah pad itu kedengeran nada rendah/kick, dipakai buat micu
    // efek getar layar (GLER). Dihitung otomatis dari sample WAV-nya sendiri, gak
    // perlu di-set manual oleh user. Lihat WavLoader.estimateIsBass().
    val bassPads = remember { mutableStateMapOf<Pair<Int, Int>, Boolean>() }
    // Counter yang di-increment tiap kali pad nada rendah dipukul -> LaunchedEffect
    // di bawah dengerin perubahan ini buat mulai animasi getar. Pakai counter
    // (bukan Boolean on/off) biar pukulan beruntun (roll di kick) bisa langsung
    // restart animasinya dari awal tanpa nyangkut/numpuk state lama.
    var shakeGen by remember { mutableIntStateOf(0) }

    // Fitur play lagu (musik latar): posisi/duration di-poll dari native tiap 200ms
    // pas lagi play (lihat LaunchedEffect di bawah), bukan dihitung sendiri di
    // Kotlin, biar UI selalu sinkron ke posisi asli yang lagi dibaca audio thread.
    var songLoaded by remember { mutableStateOf(false) }
    var isLoadingMusic by remember { mutableStateOf(false) }
    // Penanda "load lagu ke berapa" - di-increment tiap ada proses load baru (pilih
    // lagu / pemulihan otomatis dari cache). Dipakai supaya pemulihan lagu terakhir di
    // awal app gak nimpa lagu yang keburu dipilih user selagi pemulihan masih jalan.
    var musicLoadGen by remember { mutableIntStateOf(0) }
    // Dialog "Pilih Lagu" - gantiin file picker langsung di tombol LAGU/GANTI.
    // deviceSongs diisi dari MusicLibraryRepo.scanDeviceSongs (index musik yang
    // sudah kedeteksi di HP, gak ada proses decode/tunggu apa-apa buat nampilin
    // daftar ini - beda sama proses decode+resample yang cuma jalan SETELAH
    // user beneran pilih 1 lagu). isScanningDeviceSongs cuma buat spinner
    // sebentar pas query MediaStore-nya jalan.
    var showMusicLibraryDialog by remember { mutableStateOf(false) }
    var isScanningDeviceSongs by remember { mutableStateOf(false) }
    var deviceSongs by remember { mutableStateOf<List<MusicLibraryRepo.DeviceSong>>(emptyList()) }
    // False sampai SEMUA state pad (sample WAV, flag loop per pad, tempo loop
    // tersimpan, volume/tune/reverb/choke, dst - lihat LaunchedEffect(Unit) di
    // bawah) selesai dimuat dari disk ke native. DITAMBAHIN karena sebelumnya
    // onPadDown() gak nunggu ini sama sekali - pad grid udah bisa dipencet
    // begitu layar tampil, padahal loading-nya sendiri jalan di background
    // (Dispatchers.IO) dan makan waktu (apalagi kalau sample-nya banyak/preset
    // gede). Kalau pas jendela itu user langsung mukul pad yang harusnya loop,
    // native belum tau pad itu "mode loop" (flag-nya belum ke-push dari
    // PadLoopStorage) -> yang kejadian cuma bunyi sekali kayak pad biasa, chip
    // tempo gak pernah muncul karena secara teknis memang gak lagi ada loop
    // yang jalan. Itu akar dari "loop gak muncul pas baru buka app terus
    // langsung pencet pad".
    var isEngineReady by remember { mutableStateOf(false) }
    // True setelah "keadaan terakhir dipakai" (preset aktif, volume, dst - lihat
    // SessionStorage) selesai dibaca dari disk. Efek penyimpan di bawah nunggu ini
    // dulu biar nilai default awal gak sempat menimpa nilai yang tersimpan.
    var sessionLoaded by remember { mutableStateOf(false) }
    var isMusicPlaying by remember { mutableStateOf(false) }
    var musicPositionSec by remember { mutableStateOf(0.0) }
    var musicDurationSec by remember { mutableStateOf(0.0) }
    var kendangVolume by remember { mutableFloatStateOf(1f) }
    var musicVolume by remember { mutableFloatStateOf(1f) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Ambil ulang daftar preset online dari GitHub - dipanggil pas dialog online
    // pertama kali dibuka & tiap tombol refresh/coba-lagi ditekan. forceRefresh
    // = false (dipakai pas dialog baru dibuka) coba pakai cache singkat dulu
    // di OnlinePresetRepo sebelum nembak API lagi - forceRefresh = true
    // (khusus tombol refresh) selalu nanya ulang ke GitHub walau cache-nya
    // masih fresh. Sama polanya dengan refreshOnlineSound() di bawah.
    fun refreshOnlinePresets(forceRefresh: Boolean = false) {
        onlinePresetsState = OnlinePresetsUiState.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OnlinePresetRepo.listPresets(forceRefresh) }
            }
            onlinePresetsState = result.fold(
                onSuccess = { OnlinePresetsUiState.Loaded(it) },
                onFailure = { e ->
                    // Kena limit request GitHub dibedain dari error lain - pesannya
                    // udah jadi kalimat lengkap & enak dibaca dari
                    // formatRateLimitMessage() (ada perkiraan jam boleh coba lagi),
                    // sama pola dengan refreshOnlineSound() di bawah.
                    if (e is OnlinePresetRepo.RateLimitException) {
                        OnlinePresetsUiState.Error(
                            OnlinePresetRepo.formatRateLimitMessage(e.resetAtMs),
                            isRateLimit = true
                        )
                    } else {
                        OnlinePresetsUiState.Error(e.message ?: "tidak diketahui")
                    }
                }
            )
        }
    }

    // Download 1 preset online lalu langsung import ke preset yang lagi aktif -
    // persis alur importLauncher, cuma sumber byte-nya dari internet, bukan
    // content:// URI lokal.
    fun downloadAndLoadOnlinePreset(item: OnlinePresetRepo.OnlinePreset) {
        downloadingPresetName = item.name
        scope.launch {
            val loadedBass = mutableListOf<Pair<Int, Boolean>>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = OnlinePresetRepo.downloadZip(item.downloadUrl)
                    // Download-nya berhasil dulu baru preset lama dikosongin - biar
                    // kalau internet putus di tengah download, preset yang lagi
                    // aktif TIDAK ikut kehapus/kosong sia-sia.
                    PresetStorage.clearPreset(context, currentPreset)
                    java.io.ByteArrayInputStream(bytes).use { input ->
                        PresetStorage.importPreset(context, currentPreset, input) { pad, isBass ->
                            loadedBass.add(pad to isBass)
                        }
                    }
                }
            }
            downloadingPresetName = null
            result.fold(
                onSuccess = { count ->
                    // Reset dulu bass state SEMUA pad preset ini (bukan cuma yang
                    // baru ke-load) - konsisten sama clearPreset di atas, biar efek
                    // GLER pad lama yang gak ada di preset online ini ikut ilang.
                    for (pad in 1..PresetStorage.MAX_PAD) bassPads.remove(currentPreset to pad)
                    loadedBass.forEach { (pad, isBass) -> bassPads[currentPreset to pad] = isBass }
                    val msg = if (count > 0) {
                        showOnlinePresetList = false
                        "Preset P$currentPreset di-load dari online ($count pad)"
                    } else {
                        "Preset P$currentPreset dikosongkan (gak ada pad valid di file ini)"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        context,
                        "Gagal download preset (${e.message ?: "cek koneksi internet"})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    // Hentikan + lepas MediaPlayer preview yang lagi jalan (kalau ada) - dipanggil
    // sebelum mulai preview file lain, pas dialog online ditutup, dan pas
    // composable ini di-dispose (lihat DisposableEffect di bawah), biar gak ada
    // MediaPlayer nyangkut/bocor muter di background sesudah dialognya ilang.
    fun stopSoundPreview() {
        previewPlayer?.let {
            try { it.stop() } catch (e: Exception) { /* abaikan, mungkin belum sempat prepared */ }
            it.release()
        }
        previewPlayer = null
        previewingSoundPath = null
        isPreviewBuffering = false
    }

    // Ambil ulang isi 1 folder (kategori) di repo kumpulan-wav-dtx dari GitHub -
    // dipanggil pas dialog browser online pertama kali dibuka, tiap pindah
    // folder, dan tiap tombol refresh ditekan. forceRefresh = false (dipakai
    // navigasi folder biasa) coba pakai cache singkat dulu di OnlineSoundRepo
    // sebelum nembak API lagi - forceRefresh = true (khusus tombol refresh)
    // selalu nanya ulang ke GitHub walau cache-nya masih fresh.
    fun refreshOnlineSound(path: String, forceRefresh: Boolean = false) {
        onlineSoundState = OnlineSoundUiState.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OnlineSoundRepo.listContents(path, forceRefresh) }
            }
            onlineSoundState = result.fold(
                onSuccess = { OnlineSoundUiState.Loaded(it) },
                onFailure = { e ->
                    // Kena limit request GitHub dibedain dari error lain - pesannya
                    // udah jadi kalimat lengkap & enak dibaca dari
                    // formatRateLimitMessage() (ada perkiraan jam boleh coba lagi),
                    // beda dari error umum yang masih perlu dibungkus kalimat lain
                    // di OnlineSoundBrowserDialog.
                    if (e is OnlineSoundRepo.RateLimitException) {
                        OnlineSoundUiState.Error(
                            OnlineSoundRepo.formatRateLimitMessage(e.resetAtMs),
                            isRateLimit = true
                        )
                    } else {
                        OnlineSoundUiState.Error(e.message ?: "tidak diketahui")
                    }
                }
            )
        }
    }

    // Masuk ke 1 folder (kategori) - simpan path sekarang ke stack biar tombol
    // kembali tahu harus balik ke mana, lalu pindah & refresh ke folder baru.
    // Preview yang lagi jalan (kalau ada, dari folder sebelumnya) dihentikan
    // dulu biar gak nyangkut muter pas user udah pindah folder.
    fun openOnlineSoundFolder(entry: OnlineSoundRepo.Entry) {
        stopSoundPreview()
        onlineSoundPathStack.add(onlineSoundPath)
        onlineSoundPath = entry.path
        refreshOnlineSound(entry.path)
    }

    // Mundur 1 folder (pop dari stack) - dipanggil dari tombol panah-kembali di
    // judul dialog, cuma muncul kalau stack-nya gak kosong (belum di root).
    fun goBackOnlineSoundFolder() {
        stopSoundPreview()
        val parent = onlineSoundPathStack.removeLastOrNull() ?: ""
        onlineSoundPath = parent
        refreshOnlineSound(parent)
    }

    // Play/stop preview 1 file WAV - streaming langsung dari download_url GitHub
    // (MediaPlayer Android bisa nerima URL https:// langsung tanpa perlu download
    // manual dulu), jadi user bisa dengerin dulu SEBELUM beneran di-set ke pad.
    // Tap file yang lagi di-preview lagi = stop. Tap file lain = stop yang lama,
    // mulai yang baru.
    fun toggleSoundPreview(entry: OnlineSoundRepo.Entry) {
        if (previewingSoundPath == entry.path) {
            stopSoundPreview()
            return
        }
        stopSoundPreview()
        val url = entry.downloadUrl ?: return
        previewingSoundPath = entry.path
        isPreviewBuffering = true
        try {
            val player = android.media.MediaPlayer()
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(url)
            player.setOnPreparedListener {
                // Bisa jadi user udah keburu tap stop/file lain SEBELUM proses
                // prepare async ini kelar - kalau begitu, player ini udah bukan
                // previewPlayer yang aktif lagi, jadi jangan mulai muter (cegah
                // 2 suara preview nyampur/nyangkut).
                if (previewPlayer === it) {
                    isPreviewBuffering = false
                    it.start()
                }
            }
            player.setOnCompletionListener {
                if (previewPlayer === it) stopSoundPreview()
            }
            player.setOnErrorListener { mp, _, _ ->
                if (previewPlayer === mp) {
                    stopSoundPreview()
                    Toast.makeText(context, "Gagal memutar preview", Toast.LENGTH_SHORT).show()
                }
                true
            }
            previewPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            stopSoundPreview()
            Toast.makeText(context, "Gagal memutar preview (${e.message})", Toast.LENGTH_SHORT).show()
        }
    }

    // Download 1 file WAV online lalu langsung assign ke pad yang lagi dipilih -
    // pipeline PERSIS SAMA dengan filePicker (file lokal) di bawah: parse WAV ->
    // simpan ke disk sebagai sample kanonik preset aktif -> load ke native
    // engine -> update flag bass buat efek GLER. Preview yang lagi jalan
    // dihentikan dulu (kalau file yang lagi di-set sama yang lagi di-preview)
    // biar gak ada 2 sumber suara bentrok.
    fun setSoundFromOnline(entry: OnlineSoundRepo.Entry, pad: Int) {
        val url = entry.downloadUrl ?: return
        if (previewingSoundPath == entry.path) stopSoundPreview()
        settingSoundPath = entry.path
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = OnlineSoundRepo.downloadWav(url)
                    val wav = WavLoader.parse(bytes)
                        ?: throw Exception("File bukan WAV yang valid")
                    PresetStorage.saveSample(context, currentPreset, pad, bytes)
                    PresetStorage.setBassFlag(context, currentPreset, pad, wav.isBass)
                    wav
                }
            }
            settingSoundPath = null
            result.fold(
                onSuccess = { wav ->
                    AudioEngine.nativeLoadSample(currentPreset, pad, wav.pcm, wav.channels)
                    bassPads[currentPreset to pad] = wav.isBass
                    showOnlineSoundBrowser = false
                    Toast.makeText(context, "Suara pad $pad di-set dari online", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        context,
                        "Gagal set suara (${e.message ?: "cek koneksi internet"})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    // Pastikan MediaPlayer preview gak pernah nyangkut muter di background
    // begitu PadScreen di-dispose (mis. Activity ditutup/dirotasi total).
    DisposableEffect(Unit) {
        onDispose { stopSoundPreview() }
    }

    // Sekali pas layar pertama kali muncul: balikin warna tema custom + semua suara
    // yang udah pernah di-set sebelumnya, biar gak ilang walau apk sempet ditutup total.
    LaunchedEffect(Unit) {
        // Cek paling awal (ringan, cuma baca SharedPreferences boolean) - gak
        // perlu nunggu proses IO berat di bawah (load semua sample dkk) dulu,
        // biar tutorial bisa langsung nongol pas layar pertama kali kelihatan.
        showTutorial = !TutorialPrefs.hasSeenIntro(context)
        ThemeState.load(context)
        GlerEffectState.load(context)
        PadSlideRollState.load(context)
        ExtraPadState.load(context)
        // Balikin keadaan terakhir dipakai: preset aktif (P1-P8), volume kendang &
        // musik, status UI disembunyiin. Nilai volume didorong ke native tepat
        // sebelum isEngineReady di bawah.
        val session = SessionStorage.load(context)
        currentPreset = session.preset
        presetBank = session.bank
        controlsHidden = session.controlsHidden
        kendangVolume = session.kendangVolume
        musicVolume = session.musicVolume
        sessionLoaded = true
        val loadedBass = mutableListOf<Triple<Int, Int, Boolean>>()
        withContext(Dispatchers.IO) {
            PresetStorage.loadAllIntoEngine(context) { preset, pad, isBass ->
                loadedBass.add(Triple(preset, pad, isBass))
            }
            PadVolumeStorage.loadAllIntoEngine(context)
            PadTuneStorage.loadAllIntoEngine(context)
            PadReverbStorage.loadAllIntoEngine(context)
            PadChokeStorage.loadAllIntoEngine(context)
            PadLoopStorage.loadAllIntoEngine(context)
            PadCrossChokeStorage.loadAllIntoEngine(context)
            PadLoopTempoStorage.loadAllIntoEngine(context)
            // Load binding tombol keyboard fisik ke memori SEBELUM user sempat
            // mukul-mukul keyboard - dispatchKeyEvent di Activity baca langsung
            // dari PadKeyBindingStorage.bindings, jadi harus udah keisi duluan.
            PadKeyBindingStorage.load(context)
        }
        // Nulis ke state Compose di sini (balik ke Main, di luar withContext(IO)),
        // bukan dari dalam callback yang jalan di IO thread -> biar konsisten sama
        // pola state-writing lain di file ini, aman dari race/tearing.
        loadedBass.forEach { (preset, pad, isBass) -> bassPads[preset to pad] = isBass }
        // decode bitmap-nya (berat) di IO, baru tulis ke state Compose di main thread.
        val loadedBg = withContext(Dispatchers.IO) { BackgroundState.loadFromDisk(context) }
        BackgroundState.applyLoaded(loadedBg)
        // SEMUA state pad (sample, flag loop, tempo loop, dll di atas) udah
        // selesai dipush ke native di titik ini -> baru sekarang aman nerima
        // ketukan pad. Lihat catatan panjang di deklarasi isEngineReady.
        AudioEngine.nativeSetKendangVolume(kendangVolume)
        AudioEngine.nativeSetMusicVolume(musicVolume)
        isEngineReady = true
    }

    // Simpan otomatis tiap preset aktif / status sembunyiin UI berubah (dari cara
    // apapun: tap preset, tombol bank, cubit, dst) - satu tempat, gak perlu diselipin
    // di tiap handler.
    LaunchedEffect(currentPreset, sessionLoaded) {
        if (sessionLoaded) SessionStorage.savePreset(context, currentPreset)
    }
    LaunchedEffect(controlsHidden, sessionLoaded) {
        if (sessionLoaded) SessionStorage.saveControlsHidden(context, controlsHidden)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedPad != null) {
            val pad = selectedPad!!
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    Toast.makeText(context, "Gagal baca file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Coba parse sebagai WAV dulu (cepat, gak perlu decode) - kalau file
                // yang dipilih memang WAV, ini langsung berhasil tanpa ada proses
                // decode tambahan sama sekali. Kalau BUKAN WAV (mp3/aac/m4a/ogg/dll),
                // baru fallback ke CompressedSampleLoader yang decode lewat
                // MediaCodec. Proses decode ini di IO thread & CUMA SEKALI pas file
                // di-assign ke pad - hasil PCM-nya lalu disimpan ulang sebagai WAV
                // kanonik, jadi playback pad setelahnya persis kayak WAV biasa,
                // TIDAK ADA delay tambahan pas pad dipukul.
                val result = withContext(Dispatchers.IO) {
                    val directWav = WavLoader.parse(bytes)
                    if (directWav != null) {
                        directWav to bytes
                    } else {
                        val decoded = CompressedSampleLoader.decode(context, bytes)
                        if (decoded != null) {
                            val wavBytes = CompressedSampleLoader.encodeToWavBytes(
                                decoded.pcm, decoded.channels, decoded.sampleRate
                            )
                            decoded to wavBytes
                        } else null
                    }
                }
                if (result != null) {
                    val (wav, wavBytes) = result
                    // simpen dulu ke disk (selalu sebagai .wav kanonik) supaya persist,
                    // baru load ke engine
                    PresetStorage.saveSample(context, currentPreset, pad, wavBytes)
                    PresetStorage.setBassFlag(context, currentPreset, pad, wav.isBass)
                    AudioEngine.nativeLoadSample(currentPreset, pad, wav.pcm, wav.channels)
                    bassPads[currentPreset to pad] = wav.isBass
                } else {
                    Toast.makeText(context, "File suara tidak valid atau formatnya tidak didukung", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val count = context.contentResolver.openOutputStream(uri)?.use { out ->
                PresetStorage.exportPreset(context, currentPreset, out)
            } ?: 0
            val msg = if (count > 0) "Preset P$currentPreset di-export ($count pad)" else "Preset P$currentPreset masih kosong"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val loadedBass = mutableListOf<Pair<Int, Boolean>>()
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Kosongin dulu preset ini SEBELUM nulis pad yang baru - biar
                        // pad yang gak ada di file zip ini (preset barunya gak isi
                        // penuh 12 pad) ikut kosong juga, gak nyisain suara dari
                        // preset sebelumnya yang mungkin lebih lengkap.
                        PresetStorage.clearPreset(context, currentPreset)
                        PresetStorage.importPreset(context, currentPreset, input) { pad, isBass ->
                            loadedBass.add(pad to isBass)
                        }
                    } ?: 0
                }
                // Reset state bass SEMUA pad preset ini dulu (bukan cuma yang baru
                // ke-load) - biar efek GLER pad lama yang gak ada di preset baru ikut
                // ilang, sinkron sama clearPreset di atas.
                for (pad in 1..PresetStorage.MAX_PAD) bassPads.remove(currentPreset to pad)
                loadedBass.forEach { (pad, isBass) -> bassPads[currentPreset to pad] = isBass }
                val msg = if (count > 0) "Preset P$currentPreset di-load ($count pad)" else "Preset P$currentPreset dikosongkan (gak ada pad valid di file ini)"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Pilih gambar background buat area pad - dibaca & disalin ke internal storage
    // LANGSUNG di sini (bukan disimpen URI-nya doang), lihat catatan di BackgroundState
    // kenapa: izin akses content:// URI galeri gak dijamin nempel terus setelah app
    // ditutup/restart.
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                val decoded = bytes?.let { b ->
                    withContext(Dispatchers.IO) { BackgroundState.decodeAndSave(context, b) }
                }
                if (decoded != null) {
                    BackgroundState.applyImage(context, decoded)
                } else {
                    Toast.makeText(context, "Gagal muat gambar background", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Proses inti load musik dari sebuah Uri (baik dari hasil tap di dialog
    // "Pilih Lagu" yang nunjuk ke lagu yang udah kedeteksi di HP, maupun dari
    // file picker manual/SAF) -> decode+resample di IO thread (bisa makan waktu
    // buat file panjang), baru dikirim PCM-nya ke native lewat nativeLoadMusic.
    // Dipisah jadi fungsi sendiri (bukan cuma inline di dalam musicPicker kayak
    // sebelumnya) supaya jalur "pilih dari daftar terdeteksi" gak perlu nyalin
    // ulang logic try/catch + toast yang sama.
    fun loadMusicFromUri(uri: Uri) {
        musicLoadGen++
        isLoadingMusic = true
        scope.launch {
            // Try/catch tambahan di sini (selain yang udah ada di MusicLoader) sebagai
            // jaring pengaman terakhir: apapun yang meleset pas decode gak boleh sampe
            // nutup paksa apk-nya, paling banter cuma toast gagal load.
            val loaded = try {
                withContext(Dispatchers.IO) {
                    MusicLoader.decodeAndResample(context, uri)
                }
            } catch (e: Throwable) {
                null
            }
            isLoadingMusic = false
            if (loaded != null) {
                val ok = try {
                    AudioEngine.nativeLoadMusic(loaded.pcm, loaded.channels)
                } catch (e: Throwable) {
                    false
                }
                if (ok) {
                    musicDurationSec = loaded.durationSec
                    musicPositionSec = 0.0
                    isMusicPlaying = false
                    songLoaded = true
                    SessionStorage.saveMusicPosition(context, 0.0, loaded.durationSec)
                    // Simpan hasil decode ke disk di background (gak nahan UI) supaya
                    // kalau apk ditutup paksa / di-kill sistem / dibuang dari recent
                    // apps, lagu ini bisa langsung dipulihin pas apk dibuka lagi tanpa
                    // pilih lagu & decode ulang. Lihat MusicCache + efek pemulihan di
                    // bawah (LaunchedEffect(isEngineReady)).
                    scope.launch(Dispatchers.IO) {
                        MusicCache.save(context, loaded.pcm, loaded.channels)
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Gagal muat lagu (kemungkinan file terlalu besar/panjang buat memori device ini)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    "Gagal load lagu (format gak didukung atau file kegedean buat memori device ini)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // File picker manual (SAF) - dipakai sebagai fallback dari dialog "Pilih
    // Lagu" (tombol "CARI FILE MANUAL"), buat lagu yang gak nongol di daftar
    // musik terdeteksi (baru disalin & belum ke-index sistem, atau di lokasi
    // yang gak ke-cover MediaStore).
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadMusicFromUri(uri)
    }

    // Pulihin lagu terakhir dari cache disk begitu engine siap (lihat MusicCache).
    // Jalan sekali per pembukaan apk: baca PCM mentah dari disk (tanpa decode &
    // resample lagi, jadi jauh lebih cepat dari pilih lagu ulang) lalu dorong ke
    // native. Posisi play mulai dari awal & lagu tidak otomatis diputar.
    LaunchedEffect(isEngineReady) {
        if (!isEngineReady) return@LaunchedEffect
        if (songLoaded || isLoadingMusic) return@LaunchedEffect
        // Kalau native ternyata masih megang lagu (mis. activity dibuat ulang tapi
        // engine belum dibongkar), cukup sinkronin state UI - gak perlu baca disk.
        val existingDuration = try { AudioEngine.nativeGetMusicDuration() } catch (e: Throwable) { 0.0 }
        if (existingDuration > 0.0) {
            musicDurationSec = existingDuration
            musicPositionSec = try { AudioEngine.nativeGetMusicPosition() } catch (e: Throwable) { 0.0 }
            songLoaded = true
            return@LaunchedEffect
        }
        val myGen = ++musicLoadGen
        isLoadingMusic = true
        val cached = try {
            withContext(Dispatchers.IO) { MusicCache.load(context) }
        } catch (e: Throwable) {
            null
        }
        // User keburu milih lagu lain selagi pemulihan jalan -> biarin proses itu yang
        // ngurus state-nya (isLoadingMusic dsb), pemulihan ini mundur diam-diam.
        if (musicLoadGen != myGen) return@LaunchedEffect
        isLoadingMusic = false
        if (cached != null) {
            val ok = try {
                AudioEngine.nativeLoadMusic(cached.pcm, cached.channels)
            } catch (e: Throwable) {
                false
            }
            if (ok) {
                musicDurationSec = cached.durationSec
                // Lanjut dari posisi terakhir (kalau cocok dgn lagu ini), tetap dalam
                // keadaan berhenti - lagu gak otomatis bunyi pas apk baru dibuka.
                val savedPos = SessionStorage.loadMusicPosition(context, cached.durationSec)
                if (savedPos > 0.0) {
                    try { AudioEngine.nativeSeekMusic(savedPos) } catch (e: Throwable) { }
                }
                musicPositionSec = savedPos
                isMusicPlaying = false
                songLoaded = true
            }
        }
    }

    // Izin baca musik di HP (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE tergantung
    // versi Android) - dibutuhin buat query MediaStore di MusicLibraryRepo.
    // Ditolak pun gapapa: dialog tetap kebuka, cuma daftar musik terdeteksinya
    // kosong, dan opsi "Cari File Manual" (gak butuh izin ini) tetap jalan normal.
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun refreshDeviceSongs() {
        scope.launch {
            isScanningDeviceSongs = true
            deviceSongs = try {
                withContext(Dispatchers.IO) { MusicLibraryRepo.scanDeviceSongs(context) }
            } catch (e: Throwable) {
                emptyList()
            }
            isScanningDeviceSongs = false
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Discan ulang aja terlepas dari izinnya dikasih atau enggak - kalau
        // ditolak, scanDeviceSongs akan gagal/balikin kosong dan dialog otomatis
        // nampilin state "gak ada musik kedeteksi" + tombol cari manual tetap ada.
        refreshDeviceSongs()
    }

    // Dipanggil dari tombol LAGU/GANTI: buka dialog "Pilih Lagu" langsung
    // (tanpa nunggu apa-apa - daftarnya nyusul kalau belum ada / lagi discan)
    // sambil minta izin & mulai scan MediaStore di background.
    fun openMusicLibrary() {
        showMusicLibraryDialog = true
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            refreshDeviceSongs()
        } else {
            audioPermissionLauncher.launch(audioPermission)
        }
    }

    // Setelah rekaman di-stop, user pilih lokasi simpen lewat SAF -> baru di-encode ke
    // AAC/M4A di background thread dan ditulis ke situ (persis pola exportLauncher di atas).
    val recordSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val pcm = pendingRecordingPcm
        pendingRecordingPcm = null
        if (uri != null && pcm != null && pcm.isNotEmpty()) {
            isSavingRecording = true
            scope.launch {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        // MediaMuxer butuh path File asli (gak bisa nulis langsung ke
                        // OutputStream dari SAF), jadi di-encode ke file temp dulu di
                        // cache dir, baru hasilnya disalin ke Uri yang dipilih user.
                        val tempFile = File.createTempFile("rec_", ".m4a", context.cacheDir)
                        try {
                            AacEncoder.encodeToAac(pcm, tempFile)
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                tempFile.inputStream().use { it.copyTo(out) }
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                    true
                } catch (e: Throwable) {
                    // Throwable (bukan cuma Exception) sengaja dipakai di sini: rekaman yang
                    // panjang bisa bikin encoder kehabisan memori (OutOfMemoryError adalah
                    // Error, bukan Exception), dan itu sebelumnya nembus ke atas lalu nutup
                    // paksa aplikasi. Sekarang ketangkep juga, jadi paling banter cuma gagal
                    // simpan dengan pesan yang jelas.
                    false
                }
                isSavingRecording = false
                val secs = pcm.size / 2 / 48000
                val msg = if (ok) "Rekaman disimpan (${secs}s)" else "Gagal menyimpan rekaman (memori tidak cukup atau penyimpanan bermasalah)"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Timer buat label tombol record ("0:12" dst) selama lagi rekam. Gak ada batas
    // durasi lagi -> jalan terus sampe user tap stop sendiri.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (true) {
                delay(1000)
                recordSeconds++
            }
        }
    }

    fun onRecordToggle() {
        if (isSavingRecording || isStoppingRecording) return // lagi proses simpan/berhenti -> jangan bisa mulai/stop rekaman baru dulu
        if (isRecording) {
            isRecording = false
            isStoppingRecording = true
            // FIX: nativeStopRecording() DIPINDAH ke background thread (dulu dipanggil
            // langsung di UI thread). Fungsi ini menyalin SELURUH buffer rekaman dari
            // native ke array Java dalam satu panggilan sinkron - untuk rekaman yang
            // agak panjang ini bisa makan waktu ratusan ms sampai beberapa detik, dan
            // kalau itu terjadi di UI thread, seluruh UI freeze/nge-lag ("loading
            // lama" yang dikeluhkan), bahkan bisa ke-ANR di HP yang lemot. Dipindah ke
            // Dispatchers.Default (bukan IO, karena ini kerja CPU/memcpy lewat JNI,
            // bukan I/O) supaya UI tetap responsif dan cuma nampilin ikon "MEMPROSES"
            // sebentar sampai PCM-nya beres diambil.
            scope.launch {
                val pcm = try {
                    withContext(Dispatchers.Default) {
                        AudioEngine.nativeStopRecording()
                    }
                } catch (e: Throwable) {
                    // Rekaman yang sangat panjang bisa bikin alokasi array hasil rekaman
                    // gagal (OOM) di sisi JNI/native -> ditangkep di sini biar gak nutup
                    // paksa aplikasi, cuma gagal dengan pesan yang jelas.
                    null
                }
                isStoppingRecording = false
                if (pcm == null || pcm.isEmpty()) {
                    Toast.makeText(context, "Gak ada suara yang kerekam", Toast.LENGTH_SHORT).show()
                } else {
                    // FIX: dulu langsung recordSaveLauncher.launch(...) di sini - dialog
                    // simpan file (SAF) tau-tau nongol begitu tap STOP, kerasa tiba-tiba.
                    // Sekarang mampir dulu ke dialog konfirmasi in-app ("Simpan rekaman
                    // ini?"); file picker baru dibuka kalau user beneran pilih "YA" (lihat
                    // showRecordSaveConfirmDialog di bawah).
                    pendingRecordingPcm = pcm
                    pendingRecordingFileName = SimpleDateFormat("'kendang_'yyyyMMdd_HHmmss'.m4a'", Locale.US).format(Date())
                    showRecordSaveConfirmDialog = true
                }
            }
        } else {
            AudioEngine.nativeStartRecording()
            isRecording = true
        }
    }

    // ---- REC LAYAR ----

    // Nyambungin callback statik ScreenRecordService ke state Compose di sini.
    // DisposableEffect(Unit) -> daftar sekali pas composable ini pertama kali
    // masuk komposisi, dan WAJIB dilepas lagi (set balik ke null) pas keluar,
    // biar service gak nyimpen referensi ke composable/activity yang udah gak
    // ada lagi (kebocoran memori kalau activity di-recreate, mis. rotasi).
    DisposableEffect(Unit) {
        ScreenRecordService.onTick = { elapsedMs ->
            screenRecordSeconds = (elapsedMs / 1000L).toInt()
        }
        ScreenRecordService.onFinished = { file, durationMs ->
            isScreenRecording = false
            isStoppingScreenRecording = false
            pendingScreenRecordFile = file
            pendingScreenRecordFileName = SimpleDateFormat(
                "'kendang_layar_'yyyyMMdd_HHmmss'.mp4'", Locale.US
            ).format(Date())
            showScreenRecordSaveConfirmDialog = true
        }
        ScreenRecordService.onError = { message ->
            isScreenRecording = false
            isStoppingScreenRecording = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        onDispose {
            ScreenRecordService.onTick = null
            ScreenRecordService.onFinished = null
            ScreenRecordService.onError = null
        }
    }

    val mediaProjectionManager = remember {
        context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
    }

    // Notifikasi foreground service butuh izin ini dari Android 13 (API 33) ke
    // atas biar KELIHATAN (service-nya sendiri tetap jalan walau ditolak - lihat
    // catatan di ScreenRecordService.startForegroundNotification). Diminta pas
    // user pertama kali nyoba REC LAYAR, sama polanya kayak audioPermissionLauncher
    // di atas buat "Pilih Lagu".
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* diabaikan kalau ditolak - cuma notifikasi yang gak muncul, fitur tetap jalan */ }

    // Hasil dialog izin capture layar bawaan Android (createScreenCaptureIntent) ->
    // kalau user pilih "Start now"/"Izinkan", langsung start ScreenRecordService
    // bawa resultCode+data-nya (WAJIB dipakai persis dalam beberapa saat, gak bisa
    // disimpan lama-lama buat dipakai nanti).
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val metrics = context.resources.displayMetrics
            // Ukuran video SELALU landscape (lebar >= tinggi), sesuai tampilan app yang
            // dikunci landscape. Dipakai max/min biar konsisten apa pun pembacaan metrics-nya.
            val recWidth = maxOf(metrics.widthPixels, metrics.heightPixels)
            val recHeight = minOf(metrics.widthPixels, metrics.heightPixels)
            val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenRecordService.EXTRA_WIDTH, recWidth)
                putExtra(ScreenRecordService.EXTRA_HEIGHT, recHeight)
                putExtra(ScreenRecordService.EXTRA_DENSITY, metrics.densityDpi)
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                isScreenRecording = true
                screenRecordSeconds = 0
            } catch (t: Throwable) {
                    Toast.makeText(context, "Gagal memulai rekam layar: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // resultCode di sini murni ditentukan dialog sistem Android
            // (MediaProjectionPermissionActivity) - user memilih "Batal"/menolak.
            Toast.makeText(
                context,
                "Izin rekam layar tidak diberikan",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Setelah rekam layar di-stop & file .mp4 sementara (di cache) sudah siap, user
    // pilih lokasi simpen lewat SAF -> disalin dari cache ke situ (sama pola dengan
    // recordSaveLauncher, tapi tinggal copy byte apa adanya - gak perlu encode lagi
    // karena ScreenRecordService sudah nulis MP4 final langsung).
    val screenRecordSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val file = pendingScreenRecordFile
        pendingScreenRecordFile = null
        if (uri != null && file != null) {
            isSavingScreenRecord = true
            scope.launch {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                    true
                } catch (e: Throwable) {
                    false
                } finally {
                    file.delete()
                }
                isSavingScreenRecord = false
                Toast.makeText(
                    context,
                    if (ok) "Rekaman layar disimpan" else "Gagal menyimpan rekaman layar",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            file?.delete()
        }
    }

    // Timer label tombol REC LAYAR ("0:12" dst) - polanya sama kayak LaunchedEffect
    // isRecording di atas, tapi angkanya sendiri di-drive dari onTick service (lihat
    // DisposableEffect di atas), bukan dihitung ulang di sini, jadi tetap akurat
    // walau composable ini sempat di-recompose/activity dirotasi selagi rekam jalan.

    fun onScreenRecordToggle() {
        if (isSavingScreenRecord || isStoppingScreenRecording) return
        if (isScreenRecording) {
            isStoppingScreenRecording = true
            val stopIntent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            try {
                context.startService(stopIntent)
            } catch (t: Throwable) {
                // Gagal ngirim perintah stop ke service - kembalikan tombol supaya gak
                // nyangkut di "MEMPROSES" selamanya.
                isStoppingScreenRecording = false
                Toast.makeText(context, "Gagal menghentikan rekaman layar", Toast.LENGTH_SHORT).show()
            }
        } else {
            // FIX SCOPE: REC audio-only (tombol REC biasa) dan REC LAYAR sama-sama
            // makai flag recording_ tunggal di native (lewat nativeStartRecording/
            // nativeStopRecording - lihat catatan di ScreenRecordService & PadEngine::
            // drainRecordBuffer), jadi TIDAK BOLEH jalan bersamaan. Tombolnya sendiri
            // sudah saling di-nonaktifin (lihat enabled = ... di kedua Button di bawah),
            // tapi dicek ulang di sini juga buat jaga-jaga (mis. tombol sempat ke-tap
            // pas transisi state).
            if (isRecording || isSavingRecording || isStoppingRecording) {
                Toast.makeText(context, "Selesaikan dulu rekaman audio (REC) yang lagi jalan", Toast.LENGTH_SHORT).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 14+ (API 34): dialog izin defaultnya nawarin "Satu aplikasi" atau
            // "Seluruh layar". Pada mode "Satu aplikasi", user TIDAK dibalikin ke app ini
            // (yang dibuka malah app yang mau direkam), jadi callback hasil izin di
            // launcher bisa gak pernah jalan & rekaman gak mulai. App ini merekam layarnya
            // sendiri + audio internal, jadi cukup "Seluruh layar": pilihan "Satu aplikasi"
            // dimatikan lewat createConfigForDefaultDisplay() (API 34+). Di bawah Android
            // 14 dialognya memang gak punya opsi itu, jadi pakai intent biasa.
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjectionManager?.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mediaProjectionManager?.createScreenCaptureIntent()
            }
            if (captureIntent != null) {
                screenCaptureLauncher.launch(captureIntent)
            } else {
                Toast.makeText(context, "Rekam layar tidak didukung di perangkat ini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onMusicToggle() {
        if (!songLoaded) {
            openMusicLibrary()
            return
        }
        if (isMusicPlaying) {
            AudioEngine.nativeStopMusic() // "stop" = pause, posisi kesimpen buat di-lanjut lagi
            isMusicPlaying = false
            SessionStorage.saveMusicPosition(context, AudioEngine.nativeGetMusicPosition(), musicDurationSec)
        } else {
            // Kalau lagu udah abis (posisi nempel di ujung durasi), reset optimis
            // balik ke 0 di sini juga - bukan nunggu poll 200ms berikutnya - biar
            // slider seek & label posisi langsung keliatan balik ke awal instan pas
            // user klik play, senada sama fix di sisi native (PadEngine::playMusic)
            // yang beneran mindahin posisi baca lagunya balik ke 0.
            if (musicDurationSec > 0.0 && musicPositionSec >= musicDurationSec) {
                musicPositionSec = 0.0
            }
            AudioEngine.nativePlayMusic()
            isMusicPlaying = true
        }
    }

    fun onSeekMusic(seconds: Double) {
        musicPositionSec = seconds // update UI langsung biar responsif, gak nunggu poll berikutnya
        AudioEngine.nativeSeekMusic(seconds)
        SessionStorage.saveMusicPosition(context, seconds, musicDurationSec)
    }

    fun onKendangVolumeChange(v: Float) {
        kendangVolume = v
        AudioEngine.nativeSetKendangVolume(v)
        SessionStorage.saveKendangVolume(context, v)
    }

    fun onMusicVolumeChange(v: Float) {
        musicVolume = v
        AudioEngine.nativeSetMusicVolume(v)
        SessionStorage.saveMusicVolume(context, v)
    }

    // Poll posisi & status play lagu dari native tiap 200ms selama ada lagu ke-load.
    // Sumber kebenarannya di audio thread (native), bukan dihitung sendiri di Kotlin,
    // biar slider seek selalu nunjukin posisi asli walau ada jitter/underrun dsb.
    LaunchedEffect(songLoaded) {
        var ticks = 0
        while (songLoaded) {
            delay(200)
            val playingNow = AudioEngine.nativeIsMusicPlaying()
            if (playingNow != isMusicPlaying) isMusicPlaying = playingNow
            if (playingNow) {
                musicPositionSec = AudioEngine.nativeGetMusicPosition()
                // Simpan posisi tiap ~2 detik selama play, jaga-jaga kalau apk
                // ke-kill mendadak sebelum sempat lewat onPause.
                if (++ticks % 10 == 0) {
                    SessionStorage.saveMusicPosition(context, musicPositionSec, musicDurationSec)
                }
            }
        }
    }

    // Durasi kedip pad, dalam nanodetik (satuan yang sama kayak System.nanoTime())
    // biar gak perlu konversi bolak-balik tiap frame. 90ms sesuai yang diminta.
    val flashDurationNanos = 90_000_000L

    // Loop animasi kedip pad: jalan tiap frame (disinkronkan ke vsync lewat
    // withFrameNanos, bukan lewat delay() yang timing-nya bisa meleset), dan buat
    // SETIAP pad yang lagi kena hit, hitung ulang intensitasnya dari selisih waktu
    // asli sekarang vs waktu terakhir pad itu dipukul (padHitTimeNanos). Ini ganti
    // total mekanisme delayed-off yang lama:
    //  - Gak ada lagi flag boolean yang di-flip lewat coroutine terpisah -> gak ada
    //    lagi transisi yang bisa "collapse"/kelewat kalau 2 tap jatuh di frame yang
    //    sama, karena di sini nilainya SELALU dihitung ulang dari waktu asli tiap
    //    frame, bukan disimpan sebagai hasil efek samping yang bisa numpuk.
    //  - Peluruhannya juga jadi animasi beneran (1f -> 0f linear selama 90ms), bukan
    //    lompatan ON/OFF sesaat, jadi "proses 90ms"-nya keliatan kegambar.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (padHitTimeNanos.isNotEmpty()) {
                    var doneIds: MutableList<Int>? = null
                    for ((id, startTime) in padHitTimeNanos) {
                        val elapsed = now - startTime
                        val progress = (elapsed.toFloat() / flashDurationNanos).coerceIn(0f, 1f)
                        flashIntensity[id] = 1f - progress
                        if (progress >= 1f) {
                            (doneIds ?: mutableListOf<Int>().also { doneIds = it }).add(id)
                        }
                    }
                    // Beres-beres entry yang udah tuntas meluruh, biar dua map ini gak
                    // numpuk terus isinya selama app hidup (padHitTimeNanos & isEmpty()
                    // check di atas juga jadi cara loop ini "istirahat" pas gak ada pad
                    // yang lagi kedip, gak ngitung apa-apa tiap frame kalau lagi diem).
                    doneIds?.forEach { id ->
                        padHitTimeNanos.remove(id)
                        flashIntensity.remove(id)
                    }
                }
            }
        }
    }

    fun onPadDown(id: Int) {
        // Tolak ketukan yang kejadian SEBELUM loading awal selesai (lihat
        // isEngineReady) - tanpa ini, pad yang harusnya loop bisa cuma bunyi
        // sekali doang (flag loop-nya di native belum ke-set) kalau user
        // sempet mukul pas app baru kebuka. Toast dikasih supaya user ngerti
        // itu bukan pad-nya rusak, cuma perlu nunggu sebentar.
        if (!isEngineReady) {
            Toast.makeText(context, "Tunggu sebentar, lagi dimuat..", Toast.LENGTH_SHORT).show()
            return
        }
        // Trigger duluan sebelum apa-apa lagi -> ini yang bikin gak ada delay terasa
        AudioEngine.nativeTrigger(currentPreset, id)
        selectedPad = id
        // Catet momen pukulan ini (nanotime) & langsung set terang penuh, SINKRON di
        // baris yang sama dengan trigger suara -> nempel pas sama momen bunyi, gak
        // nunggu giliran frame/efek berikutnya dulu buat nyala.
        //
        // Ditulis ulang PENUH tiap kali dipukul (walau pad-nya lagi nyala/lagi meluruh)
        // supaya pukulan beruntun/roll di pad yang sama SELALU restart dari terang
        // penuh - gak ada lagi ketergantungan ke "generasi" coroutine buat nyegah
        // delayed-off yang lama motong kedipan yang baru, karena sekarang gak ada
        // delayed-off sama sekali: peluruhannya dihitung ulang tiap frame dari waktu
        // ini, jadi otomatis benar walau di-overwrite berkali-kali secepat apapun.
        padHitTimeNanos[id] = System.nanoTime()
        flashIntensity[id] = 1f
        // GLER: kalau pad ini kedengeran nada rendah/kick, micu getar layar. Ditulis
        // sinkron juga di sini (bareng suara & kedip pad), bukan lewat efek terpisah,
        // biar getarnya nempel pas di momen dipukul. Cuma jalan kalau user belum
        // matiin efeknya lewat toggle di dialog Pengaturan (GlerEffectState.enabled).
        if (GlerEffectState.enabled && bassPads[currentPreset to id] == true) {
            shakeGen++
        }
    }

    // Dengerin trigger yang datang dari physical key (keyboard USB/wireless),
    // lihat MainActivity.dispatchKeyEvent + KeyTriggerBus. Dipanggil lewat
    // onPadDown yang SAMA PERSIS dengan yang dipakai sentuhan jari di PadGrid -
    // jadi suara, kedip pad, dan efek GLER semuanya konsisten, gak ada jalur
    // terpisah yang bisa keluar sinkron.
    LaunchedEffect(Unit) {
        KeyTriggerBus.triggers.collect { pad -> onPadDown(pad) }
    }

    // Poll native tiap ~90ms buat tau pad mana (di preset yang lagi aktif SEKARANG,
    // dibaca ulang tiap iterasi - jadi otomatis ngikutin kalau user pindah preset)
    // yang lagi bunyi loop, dipakai buat munculin/nyembunyiin slider tempo langsung
    // di pad-nya (lihat PadGrid/Pad). Bukan lewat withFrameNanos (60x/detik) kayak
    // flashIntensity di atas - status loop gak butuh presisi seketat itu, dan manggil
    // JNI 12x tiap frame di 60fps cuma buang-buang kerjaan sia-sia.
    LaunchedEffect(Unit) {
        while (true) {
            val preset = currentPreset
            var next: MutableSet<Int>? = null
            for (pad in 1..PresetStorage.MAX_PAD) {
                if (AudioEngine.nativeIsPadLooping(preset, pad)) {
                    (next ?: mutableSetOf<Int>().also { next = it }).add(pad)
                    // Loop yang BARU AJA kedeteksi (belum ada di set sebelumnya) ->
                    // sinkronin tampilan tempo-nya ke nilai TERKINI di native (bukan
                    // dipaksa 1x lagi - tempo sekarang persist per pad, lihat
                    // PadLoopTempoStorage, jadi loop yang baru mulai bisa aja emang
                    // udah "diingat" pada tempo custom dari sesi sebelumnya).
                    if (pad !in loopingPads) padLoopTempos[pad] = PadLoopTempoStorage.get(preset, pad)
                }
            }
            loopingPads = next ?: emptySet()
            delay(90)
        }
    }

    // === Efek GLER: layar bergetar sebentar pas mukul pad nada rendah/kick ===
    // shakeOffset ini nilai geser horizontal (px) yang dipasang ke seluruh layar
    // lewat graphicsLayer di Box paling luar. graphicsLayer murni operasi compositing
    // (GPU), gak micu re-layout/re-measure sama sekali -> jadi ringan & gak mungkin
    // bikin audio/UI lain lag walau di-trigger sesering apapun.
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeGen) {
        if (shakeGen == 0) return@LaunchedEffect
        // Selalu di-reset dulu ke 0 sebelum mulai animasi baru -> kalau pad kick
        // dipukul beruntun cepat (roll), animasi lama otomatis kebatalin (LaunchedEffect
        // dengan key baru otomatis cancel coroutine sebelumnya) dan yang baru mulai
        // dari titik bersih, jadi gak ada resiko geser numpuk/nyangkut di posisi miring.
        shakeOffset.stop()
        shakeOffset.snapTo(0f)
        shakeOffset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 180
                0f at 0
                24f at 30
                -20f at 60
                14f at 90
                -9f at 120
                4f at 150
                0f at 180
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            // Jaga-jaga kalau immersive hide gagal (banyak kejadian di Android lama /
            // custom ROM tertentu yang gak nurut BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            // atau pas user swipe buat munculin bar sementara): begitu status bar /
            // nav bar kebuka, konten otomatis digeser biar gak ketiban/ketutupan.
            // Kalau bar-nya beneran hilang (immersive sukses), inset ini bernilai 0
            // jadi gak makan tempat sama sekali -> tampilan tetap full-screen.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .graphicsLayer { translationX = shakeOffset.value }
            // Dulu di sini ada gestur "cubit 2 jari" buat sembunyi/tampilin strip aksi
            // bawah + panel musik kanan. DIHAPUS TOTAL, diganti tab kecil permanen di
            // tepi kanan layar (lihat HideToggleTab di bawah, dipasang sebagai kolom
            // paling kanan di Row).
            //
            // Alasannya: gestur berbasis "jarak antar 2 jari" itu SECARA DEFINISI gak
            // bisa dibedain dari gerakan tangan normal pas main pad drum - app ini
            // emang lazim dimainkan pakai 2 tangan/banyak jari sekaligus (roll, dua pad
            // ditekan bareng, dst), dan jarak antar jari pasti berubah-ubah terus
            // selama main. Seketat apapun threshold-nya diatur (udah dicoba dinaikin +
            // dilacak per pointer ID biar gak ketuker pasangan jari), tetap ada
            // kemungkinan 2 jari yang lagi mukul pad kebetulan jaraknya berubah cukup
            // jauh dalam waktu singkat -> kepicu gak sengaja, dan itu sangat ganggu pas
            // lagi asik main. Tab fisik yang HARUS di-tap di titik tetap yang jelas,
            // di LUAR area hit-test PadGrid sama sekali, gak punya masalah itu -
            // seberapapun rame/cepat permainannya, gak mungkin ke-trigger gak sengaja.
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
        // Panel preset vertikal di sisi kiri, mirror dari MusicSidePanel di kanan -
        // P1-P4 (atau P5-P8) ditumpuk ke bawah plus tombol ganti bank di atasnya,
        // bukan dipepetin jadi satu baris horizontal sempit kayak sebelumnya.
        if (!controlsHidden) {
        PresetSidePanel(
            bank = presetBank,
            current = currentPreset,
            onSelect = { currentPreset = it },
            onLockedPresetTap = { openProUpgradeLink(context) },
            onBankToggle = {
                val newBank = 1 - presetBank
                if (newBank == 1 && !BuildConfig.IS_PREMIUM) {
                    // Bank P5-P8 seluruhnya terkunci di versi FREE - jangan pindah
                    // bank beneran, arahin ke halaman upgrade aja. Versi PREMIUM
                    // (BuildConfig.IS_PREMIUM) boleh pindah bank bebas, sama kayak
                    // preset P3-P8 yang juga udah kebuka semua (lihat FREE_UNLOCKED_PRESETS).
                    openProUpgradeLink(context)
                } else {
                    presetBank = newBank
                    currentPreset = newBank * 4 + 1
                }
            },
            onOpenSettings = {
                // Refresh dari native tiap dialog dibuka - preset yang lagi aktif bisa
                // aja beda dari terakhir kali dialog ini dibuka, jadi gak boleh pakai
                // nilai basi yang nempel dari preset sebelumnya.
                for (pad in 1..PresetStorage.MAX_PAD) {
                    padVolumes[pad] = PadVolumeStorage.get(currentPreset, pad)
                    padTunes[pad] = PadTuneStorage.get(currentPreset, pad)
                    padReverbs[pad] = PadReverbStorage.get(currentPreset, pad)
                    padChokes[pad] = PadChokeStorage.get(currentPreset, pad)
                    padLoops[pad] = PadLoopStorage.get(currentPreset, pad)
                    padCrossChokeEnabled[pad] = PadCrossChokeStorage.getEnabled(currentPreset, pad)
                    padCrossChokeMask[pad] = PadCrossChokeStorage.getMask(currentPreset, pad)
                }
                showPadVolumeSettings = true
            },
            modifier = Modifier
                .width(76.dp)
                .fillMaxHeight()
        )
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Box biar background gambar (kalau ada) bisa digambar DI BELAKANG PadGrid,
            // memenuhi area yang sama persis (weight(1f).fillMaxWidth()).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val bgImage = BackgroundState.imageBitmap
                if (bgImage != null) {
                    // Cuma keliatan lewat celah 3dp antar pad & garis tepi grid - pad-nya
                    // sendiri TETAP solid/opaque (gak ikut ditembus gambar) biar nomor pad
                    // & efek kedip pas dipukul tetap jelas kebaca, gak numpuk sama gambar.
                    Image(
                        bitmap = bgImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = BackgroundState.opacity,
                        modifier = Modifier.matchParentSize()
                    )
                }
                PadGrid(
                    currentPreset = currentPreset,
                    flashIntensity = flashIntensity,
                    selectedPad = selectedPad,
                    onPadDown = { onPadDown(it) },
                    hasBackgroundImage = bgImage != null,
                    padOpacity = if (bgImage != null) BackgroundState.padOpacity else 1f,
                    // Baca ulang tiap recompose (bukan di-snapshot sekali) - biar
                    // begitu user nyalain/matiin "Pad Tambahan" di dialog Pengaturan,
                    // ATAU pindah ke preset lain yang scope-nya beda (mode "Preset
                    // Tertentu"), grid-nya langsung nambah/ngilangin baris ke-5
                    // (pad13-16) tanpa perlu apa-apa lagi dari user.
                    extraPadsActive = ExtraPadState.isActiveFor(currentPreset),
                    loopingPads = loopingPads,
                    padLoopTempos = padLoopTempos,
                    onLoopTempoChange = { pad, rate ->
                        padLoopTempos[pad] = rate
                        // set() (bukan nativeSetPadLoopTempo langsung) - update ke
                        // native REAL-TIME sekaligus nulis ke disk, jadi tempo ini
                        // masih kepakai lagi walau loop di-restart ATAU apk ditutup
                        // total lalu dibuka lagi (lihat PadLoopTempoStorage).
                        PadLoopTempoStorage.set(context, currentPreset, pad, rate)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // strip kontrol tipis di bawah, gak makan banyak ruang dari pad.
            // Semua tombol aksi dirapikan seragam (padding, shape, font size sama),
            // biar tetap muat 1 baris walau nambah tombol REC.
            // Disembunyiin total (bukan cuma dibikin transparan) pas controlsHidden,
            // supaya tingginya ke-reclaim dan PadGrid (weight 1f di Column yang sama)
            // otomatis melebar ngisi ruang yang kebebas, gak perlu logic ukuran manual.
            if (!controlsHidden) {
            val actionButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            val actionButtonShape = RoundedCornerShape(4.dp)
            val actionButtonFontSize = AppTextSize

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CtrlPanelColor)
                    // Padding grup (vertical & horizontal) sekarang SAMA PERSIS dengan
                    // panel kiri (PresetSidePanel) & panel kanan (MusicSidePanel) -
                    // GroupOuterPaddingV/H - biar ketiga strip kontrol kebaca sebagai
                    // satu keluarga UI yang konsisten, bukan 3 gaya beda-beda.
                    .padding(vertical = GroupOuterPaddingV, horizontal = GroupOuterPaddingH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GroupItemSpacing)
            ) {
                // P1-P4 dan dropdown bank preset udah dipindah ke PresetSidePanel
                // vertikal di sisi kiri layar (lihat pemanggilannya di atas), jadi
                // strip bawah ini sekarang cuma isi tombol aksi: REC, SET SUARA,
                // WARNA, EXPORT, LOAD.
                Button(
                    onClick = { onRecordToggle() },
                    // + !isScreenRecording && !isStoppingScreenRecording: REC audio-only
                    // gak boleh dipencet selagi REC LAYAR jalan (keduanya rebutan flag
                    // recording_ yang sama di native - lihat catatan di onScreenRecordToggle).
                    enabled = !isSavingRecording && !isStoppingRecording &&
                        !isScreenRecording && !isStoppingScreenRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFD32F2F) else PanelColor,
                        contentColor = if (isRecording) Color.White else PadTextColor,
                        disabledContainerColor = PanelColor,
                        disabledContentColor = PadTextColor
                    ),
                    shape = actionButtonShape,
                    contentPadding = actionButtonPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    // Ikon + teks berdampingan (bukan ikon doang di atas teks) - biar
                    // statusnya kebaca jelas walau icon dan tulisan cuma sekilas dilirik.
                    // Gak ada lagi keterangan menit/detik. Ukuran teksnya SAMA PERSIS
                    // dengan teks tombol lain (actionButtonFontSize), gak dibedain.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isSavingRecording || isStoppingRecording -> Icons.Filled.HourglassEmpty
                                isRecording -> Icons.Filled.Stop
                                else -> Icons.Filled.FiberManualRecord
                            },
                            contentDescription = when {
                                isSavingRecording -> "Menyimpan rekaman"
                                isStoppingRecording -> "Memproses hasil rekaman"
                                isRecording -> "Berhenti merekam"
                                else -> "Mulai merekam"
                            },
                            modifier = Modifier.size(ControlIconSize)
                        )
                        Text(
                            text = when {
                                isSavingRecording -> "MENYIMPAN"
                                isStoppingRecording -> "MEMPROSES"
                                isRecording -> "STOP"
                                else -> "REC"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = actionButtonFontSize,
                            maxLines = 1
                        )
                    }
                }

                // REC LAYAR: video+audio (lihat ScreenRecordService.kt) - beda dari REC
                // di atas yang audio-only. Dikasih weight lebih kecil (0.9f) daripada
                // REC/TEMA/EXPORT/LOAD (1f) karena baris ini udah lumayan padat -
                // labelnya sengaja pendek ("LAYAR"/"STOP") biar tetap muat 1 baris.
                Button(
                    onClick = { onScreenRecordToggle() },
                    enabled = !isSavingScreenRecord && !isStoppingScreenRecording &&
                        !isRecording && !isSavingRecording && !isStoppingRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScreenRecording) Color(0xFFD32F2F) else PanelColor,
                        contentColor = if (isScreenRecording) Color.White else PadTextColor,
                        disabledContainerColor = PanelColor,
                        disabledContentColor = PadTextColor
                    ),
                    shape = actionButtonShape,
                    contentPadding = actionButtonPadding,
                    modifier = Modifier.weight(0.9f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isSavingScreenRecord || isStoppingScreenRecording -> Icons.Filled.HourglassEmpty
                                isScreenRecording -> Icons.Filled.Stop
                                else -> Icons.Filled.Videocam
                            },
                            contentDescription = when {
                                isSavingScreenRecord -> "Menyimpan rekaman layar"
                                isStoppingScreenRecording -> "Memproses hasil rekaman layar"
                                isScreenRecording -> "Berhenti merekam layar"
                                else -> "Mulai merekam layar"
                            },
                            modifier = Modifier.size(ControlIconSize)
                        )
                        Text(
                            text = when {
                                isSavingScreenRecord -> "MENYIMPAN"
                                isStoppingScreenRecording -> "MEMPROSES"
                                isScreenRecording -> "STOP"
                                else -> "LAYAR"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = actionButtonFontSize,
                            maxLines = 1
                        )
                    }
                }

                Button(
                    onClick = {
                        if (selectedPad != null) showSetSoundSourceChooser = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeState.accentColor, contentColor = ThemeState.accentTextColor),
                    shape = actionButtonShape,
                    contentPadding = actionButtonPadding,
                    modifier = Modifier.weight(1.8f)
                ) {
                    Text(
                        if (selectedPad != null) "SET SUARA - PAD $selectedPad" else "PILIH PAD DULU",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = actionButtonFontSize,
                        maxLines = 1
                    )
                }

                // REC / TEMA / EXPORT / LOAD: 4 tombol aksi dikasih weight SAMA PERSIS
                // (1f) biar lebar & ukurannya seragam & sejajar rapi - dulu REC beda
                // sendiri (0.75f) dari WARNA/EXPORT/LOAD (0.9f), sekarang keempatnya
                // selaras. SET SUARA tetap lebih lebar (1.8f) karena teksnya jauh lebih
                // panjang ("SET SUARA - PAD X" / "PILIH PAD DULU"). Label "WARNA" diganti
                // "TEMA" karena dialognya sekarang bukan cuma ngatur warna aksen doang,
                // tapi juga background gambar pad + transparansinya.
                // TEMA dikunci di versi FREE - tap-nya ngarahin ke halaman upgrade
                // Pro, bukan buka dialog ganti warna/background. Di versi PREMIUM
                // (BuildConfig.IS_PREMIUM) tombolnya balik normal: buka
                // ColorPickerDialog kayak biasa, gak ada ikon gembok.
                if (BuildConfig.IS_PREMIUM) {
                    Button(
                        onClick = { showColorPicker = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = PadTextColor),
                        shape = actionButtonShape,
                        contentPadding = actionButtonPadding,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("TEMA", fontWeight = FontWeight.SemiBold, fontSize = actionButtonFontSize, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = { openProUpgradeLink(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeState.accentColor, contentColor = ThemeState.accentTextColor),
                        shape = actionButtonShape,
                        contentPadding = actionButtonPadding,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Tema terkunci - upgrade ke Pro",
                                modifier = Modifier.size(14.dp)
                            )
                            Text("TEMA", fontWeight = FontWeight.SemiBold, fontSize = actionButtonFontSize, maxLines = 1)
                        }
                    }
                }

                Button(
                    onClick = { showExportConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = PadTextColor),
                    shape = actionButtonShape,
                    contentPadding = actionButtonPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("EXPORT", fontWeight = FontWeight.SemiBold, fontSize = actionButtonFontSize, maxLines = 1)
                }

                Button(
                    onClick = { showLoadSourceChooser = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = PadTextColor),
                    shape = actionButtonShape,
                    contentPadding = actionButtonPadding,
                    modifier = Modifier
                        .weight(1f)
                        // Diukur terus (bukan cuma sekali) karena posisinya bisa
                        // geser antar recomposition/rotasi/tampil-sembunyi strip
                        // kontrol - TutorialOverlay butuh koordinat yang selalu
                        // akurat buat nge-highlight tombol ini persis.
                        .onGloballyPositioned { coords ->
                            loadButtonBoundsInRoot = coords.boundsInRoot()
                        }
                ) {
                    Text("LOAD", fontWeight = FontWeight.SemiBold, fontSize = actionButtonFontSize, maxLines = 1)
                }
            }
            } // end if (!controlsHidden) - strip tombol bawah
        }

        // Garis strip kontrol lagu, sama konsepnya kayak strip di bawah tapi vertikal
        // di sisi kanan: play/stop, seek ke posisi tertentu, volume kendang & musik.
        // Disembunyiin juga pas controlsHidden -> Column pad di sebelahnya (weight 1f,
        // satu-satunya elemen weighted yang tersisa di Row) otomatis melebar ngisi
        // seluruh lebar layar, area pad jadi maksimal buat main.
        if (!controlsHidden) {
        MusicSidePanel(
            songLoaded = songLoaded,
            isLoadingMusic = isLoadingMusic,
            isMusicPlaying = isMusicPlaying,
            positionSec = musicPositionSec,
            durationSec = musicDurationSec,
            kendangVolume = kendangVolume,
            musicVolume = musicVolume,
            onPickSong = { openMusicLibrary() },
            onToggle = { onMusicToggle() },
            onSeek = { onSeekMusic(it) },
            onKendangVolumeChange = { onKendangVolumeChange(it) },
            onMusicVolumeChange = { onMusicVolumeChange(it) },
            modifier = Modifier
                .width(76.dp)
                .fillMaxHeight()
        )
        }

        // Tab permanen buat sembunyi/tampilin strip aksi bawah + panel musik (gantiin
        // gestur cubit yang lama - lihat catatan panjang di Box paling luar). SELALU
        // ada (gak ikut disembunyiin oleh controlsHidden, karena ini justru
        // tombolnya sendiri), lebarnya sengaja tipis (22dp) supaya gak makan banyak
        // ruang dari pad, dan posisinya di LUAR PadGrid (kolom Row tersendiri) jadi
        // area sentuhnya sama sekali gak overlap/rebutan sama hit-test pad manapun -
        // tap di sini gak mungkin kepicu gak sengaja walau lagi mukul pad serame apapun.
        HideToggleTab(
            hidden = controlsHidden,
            onToggle = { controlsHidden = !controlsHidden },
            modifier = Modifier
                .width(22.dp)
                .fillMaxHeight()
        )
        }

        if (showColorPicker) {
            ColorPickerDialog(
                initial = ThemeState.accentColor,
                backgroundImage = BackgroundState.imageBitmap,
                backgroundOpacity = BackgroundState.opacity,
                padOpacity = BackgroundState.padOpacity,
                onDismiss = { showColorPicker = false },
                onConfirm = { color ->
                    ThemeState.set(context, color)
                    showColorPicker = false
                },
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onClearBackground = { BackgroundState.clearImage(context) },
                onOpacityChange = { BackgroundState.setOpacity(context, it) },
                onPadOpacityChange = { BackgroundState.setPadOpacity(context, it) }
            )
        }

        if (showLoadSourceChooser) {
            LoadSourceChooserDialog(
                preset = currentPreset,
                onPickLocal = {
                    showLoadSourceChooser = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                onPickOnline = {
                    showLoadSourceChooser = false
                    showOnlinePresetList = true
                    refreshOnlinePresets()
                },
                onDismiss = { showLoadSourceChooser = false }
            )
        }

        if (showSetSoundSourceChooser) {
            SetSoundSourceChooserDialog(
                pad = selectedPad ?: 0,
                onPickLocal = {
                    showSetSoundSourceChooser = false
                    filePicker.launch(arrayOf("audio/*"))
                },
                onPickOnline = {
                    showSetSoundSourceChooser = false
                    // Selalu mulai dari root repo tiap dialog dibuka dari nol -
                    // biar user gak nyangkut di folder terakhir kali dia buka
                    // dialog ini buat pad/sesi lain.
                    onlineSoundPathStack.clear()
                    onlineSoundPath = ""
                    showOnlineSoundBrowser = true
                    refreshOnlineSound("")
                },
                onDismiss = { showSetSoundSourceChooser = false }
            )
        }

        if (showOnlineSoundBrowser) {
            OnlineSoundBrowserDialog(
                pad = selectedPad ?: 0,
                currentPath = onlineSoundPath,
                canGoBack = onlineSoundPathStack.isNotEmpty(),
                state = onlineSoundState,
                previewingPath = previewingSoundPath,
                isPreviewBuffering = isPreviewBuffering,
                settingPath = settingSoundPath,
                onOpenFolder = { openOnlineSoundFolder(it) },
                onBack = { goBackOnlineSoundFolder() },
                onRetry = { refreshOnlineSound(onlineSoundPath, forceRefresh = true) },
                onTogglePreview = { toggleSoundPreview(it) },
                onSet = { entry -> selectedPad?.let { setSoundFromOnline(entry, it) } },
                onDismiss = {
                    // Gak boleh nutup dialog di tengah-tengah download+set lagi
                    // jalan - biar user gak ninggalin proses yang belum kelar
                    // tanpa sadar (sama pola dengan showOnlinePresetList di atas).
                    if (settingSoundPath == null) {
                        stopSoundPreview()
                        showOnlineSoundBrowser = false
                    }
                }
            )
        }

        // Konfirmasi sebelum benar-benar buka file picker buat nyimpen rekaman -
        // muncul begitu nativeStopRecording() beres di background (lihat onRecordToggle).
        if (showRecordSaveConfirmDialog) {
            val secs = (pendingRecordingPcm?.size ?: 0) / 2 / 48000
            ConfirmDialog(
                title = "Simpan Rekaman?",
                message = "Rekaman kendang kamu (${secs}s) udah siap. Simpan ke file, atau buang aja?",
                confirmLabel = "SIMPAN",
                dismissLabel = "BUANG",
                onConfirm = {
                    showRecordSaveConfirmDialog = false
                    recordSaveLauncher.launch(pendingRecordingFileName)
                },
                onDismiss = {
                    showRecordSaveConfirmDialog = false
                    pendingRecordingPcm = null
                    Toast.makeText(context, "Rekaman dibuang", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Sama pola dengan showRecordSaveConfirmDialog di atas, tapi buat hasil REC
        // LAYAR (.mp4) - muncul begitu ScreenRecordService.onFinished dipanggil
        // (lihat DisposableEffect di dekat onScreenRecordToggle).
        if (showScreenRecordSaveConfirmDialog) {
            ConfirmDialog(
                title = "Simpan Rekaman Layar?",
                message = "Rekaman layar (${screenRecordSeconds}s) udah siap. Simpan ke file, atau buang aja?",
                confirmLabel = "SIMPAN",
                dismissLabel = "BUANG",
                onConfirm = {
                    showScreenRecordSaveConfirmDialog = false
                    screenRecordSaveLauncher.launch(pendingScreenRecordFileName)
                },
                onDismiss = {
                    showScreenRecordSaveConfirmDialog = false
                    pendingScreenRecordFile?.delete()
                    pendingScreenRecordFile = null
                    Toast.makeText(context, "Rekaman layar dibuang", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Konfirmasi sebelum buka file picker export preset - dulu tombol EXPORT
        // langsung nge-launch file picker begitu ditekan.
        if (showExportConfirmDialog) {
            ConfirmDialog(
                title = "Export Preset?",
                message = "Export semua suara pad di preset P$currentPreset ke file .zip?",
                confirmLabel = "EXPORT",
                dismissLabel = "BATAL",
                onConfirm = {
                    showExportConfirmDialog = false
                    exportLauncher.launch("preset_p$currentPreset.zip")
                },
                onDismiss = { showExportConfirmDialog = false }
            )
        }

        if (showOnlinePresetList) {
            OnlinePresetListDialog(
                preset = currentPreset,
                state = onlinePresetsState,
                downloadingName = downloadingPresetName,
                onRetry = { refreshOnlinePresets(forceRefresh = true) },
                onPick = { downloadAndLoadOnlinePreset(it) },
                onDismiss = {
                    // Gak boleh nutup dialog di tengah-tengah download lagi jalan -
                    // biar user gak ninggalin proses yang belum kelar tanpa sadar.
                    if (downloadingPresetName == null) showOnlinePresetList = false
                }
            )
        }

        if (showMusicLibraryDialog) {
            MusicLibraryDialog(
                songs = deviceSongs,
                isScanning = isScanningDeviceSongs,
                onPick = { song ->
                    showMusicLibraryDialog = false
                    loadMusicFromUri(song.uri)
                },
                onBrowseManual = {
                    showMusicLibraryDialog = false
                    musicPicker.launch(arrayOf("audio/*"))
                },
                onDismiss = { showMusicLibraryDialog = false }
            )
        }

        if (showPadVolumeSettings) {
            PadVolumeDialog(
                preset = currentPreset,
                padVolumes = padVolumes,
                padTunes = padTunes,
                padReverbs = padReverbs,
                padChokes = padChokes,
                padLoops = padLoops,
                padCrossChokeEnabled = padCrossChokeEnabled,
                padCrossChokeMask = padCrossChokeMask,
                glerEnabled = GlerEffectState.enabled,
                onGlerEnabledChange = { GlerEffectState.set(context, it) },
                slideRollEnabled = PadSlideRollState.enabled,
                onSlideRollEnabledChange = { PadSlideRollState.set(context, it) },
                // "Pad Tambahan": switch utama on/off, pilihan scope (semua preset
                // P1-P8 ATAU cuma preset tertentu yang dicentang) - lihat catatan
                // panjang di ExtraPadState.kt. extraPadsActiveNow dipakai dialog
                // buat tau apakah preset yang LAGI DIBUKA ini sekarang nampilin
                // pad13-16 atau enggak, biar baris PAD 13..16 di bawah cuma
                // muncul kalau memang lagi keliatan di grid.
                extraPadEnabled = ExtraPadState.enabled,
                onExtraPadEnabledChange = { ExtraPadState.setEnabled(context, it) },
                extraPadApplyToAll = ExtraPadState.applyToAll,
                onExtraPadApplyToAllChange = { ExtraPadState.setApplyToAll(context, it) },
                isExtraPadPresetSelected = { p -> ExtraPadState.isPresetSelected(p) },
                onExtraPadPresetToggle = { p, checked -> ExtraPadState.setPresetSelected(context, p, checked) },
                extraPadsActiveNow = ExtraPadState.isActiveFor(currentPreset),
                onVolumeChange = { pad, volume ->
                    padVolumes[pad] = volume
                    PadVolumeStorage.set(context, currentPreset, pad, volume)
                },
                onTuneChange = { pad, semitones ->
                    padTunes[pad] = semitones
                    PadTuneStorage.set(context, currentPreset, pad, semitones)
                },
                onReverbChange = { pad, amount ->
                    padReverbs[pad] = amount
                    PadReverbStorage.set(context, currentPreset, pad, amount)
                },
                onChokeChange = { pad, enabled ->
                    padChokes[pad] = enabled
                    PadChokeStorage.set(context, currentPreset, pad, enabled)
                },
                onLoopChange = { pad, enabled ->
                    padLoops[pad] = enabled
                    PadLoopStorage.set(context, currentPreset, pad, enabled)
                },
                onCrossChokeEnabledChange = { pad, enabled ->
                    padCrossChokeEnabled[pad] = enabled
                    PadCrossChokeStorage.setEnabled(context, currentPreset, pad, enabled)
                },
                onCrossChokeMaskChange = { pad, mask ->
                    padCrossChokeMask[pad] = mask
                    PadCrossChokeStorage.setMask(context, currentPreset, pad, mask)
                },
                // Binding tombol keyboard fisik GLOBAL (bukan per preset), lihat
                // catatan di PadKeyBindingStorage - jadi dibaca langsung dari
                // singleton-nya, gak perlu di-refresh manual tiap dialog dibuka
                // kayak padVolumes/padTunes/padReverbs.
                keyBindings = PadKeyBindingStorage.bindings,
                recordingPad = KeyBindRecorder.recordingPad,
                onStartKeyRecording = { pad -> KeyBindRecorder.recordingPad = pad },
                onCancelKeyRecording = { KeyBindRecorder.recordingPad = null },
                // Dipanggil dari Modifier.onKeyEvent DI DALAM compose tree dialog
                // (lihat PadVolumeDialog.kt) begitu 1 tombol fisik ketangkep selagi
                // mode rekam aktif - bukan dari dispatchKeyEvent Activity lagi,
                // karena dialog ini window terpisah yang gak kelewatan situ.
                onKeyCaptured = { pad, keyCode ->
                    PadKeyBindingStorage.set(context, pad, keyCode)
                    KeyBindRecorder.recordingPad = null
                },
                onResetKeyBinding = { pad ->
                    PadKeyBindingStorage.reset(context, pad)
                    if (KeyBindRecorder.recordingPad == pad) KeyBindRecorder.recordingPad = null
                },
                onResetAllKeyBindings = {
                    PadKeyBindingStorage.resetAll(context)
                    KeyBindRecorder.recordingPad = null
                },
                onDismiss = {
                    showPadVolumeSettings = false
                    // Batalin mode rekam kalau dialog ditutup sebelum sempet nekan
                    // tombol apa-apa - biar gak "nyangkut" nunggu key event padahal
                    // dialognya udah gak keliatan lagi.
                    KeyBindRecorder.recordingPad = null
                }
            )
        }

        // Tutorial pengenalan buat user baru - SENGAJA ditaruh PALING TERAKHIR
        // di dalam Box ini (bukan di atas), karena urutan children di Box
        // menentukan urutan gambar (yang belakangan digambar PALING ATAS) -
        // jadi overlay ini pasti nutupin semua dialog/strip kontrol lain di
        // atasnya, termasuk nge-highlight tombol LOAD walau lagi ada dialog
        // lain yang somehow kebuka barengan.
        if (showTutorial) {
            TutorialOverlay(
                targetBoundsInRoot = loadButtonBoundsInRoot,
                onConfirm = {
                    showTutorial = false
                    TutorialPrefs.markIntroSeen(context)
                }
            )
        }
    }
}

@Composable
fun PadGrid(
    currentPreset: Int,
    flashIntensity: Map<Int, Float>,
    selectedPad: Int?,
    onPadDown: (Int) -> Unit,
    hasBackgroundImage: Boolean = false,
    padOpacity: Float = 1f,
    // True kalau preset yang lagi aktif SEKARANG harus nampilin 4 "Pad Tambahan"
    // (pad13-16, lihat ExtraPadState) sebagai kolom ke-4 di SETIAP baris,
    // DI SAMPING 3 pad bawaan baris itu (pad13 di baris 1, pad14 di baris 2,
    // dst) - bukan baris ke-5 terpisah di bawah. Karena tiap pad tambahan
    // masuk Row yang sama dengan pad bawaan dan sama-sama pakai weight(1f),
    // bentuknya (lebar & tinggi) otomatis ngikutin pad lain di baris tsb.
    // False (default) -> grid balik ke 12 pad seperti semula, kolom ke-4 gak
    // digambar sama sekali (bukan cuma disembunyikan/alpha 0 - benar-benar gak
    // ada Pad-nya, jadi gak makan lebar layar sama sekali).
    extraPadsActive: Boolean = false,
    // Pad-pad yang SAAT INI kedengeran lagi loop (lihat catatan panjang di
    // PadScreen) - dipakai Pad() buat nampilin slider tempo langsung di kotaknya.
    loopingPads: Set<Int> = emptySet(),
    padLoopTempos: Map<Int, Float> = emptyMap(),
    onLoopTempoChange: (pad: Int, rate: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // 3 kolom x 4 baris: pipih, besar, besar, pipih -> sama seperti layout DTX-Multi 12
    // full-bleed: gak ada margin luar, cuma garis tipis (seam) antar pad.
    //
    // Tiap entry = (weight tinggi baris) ke (jumlah kolom di baris itu). 4 "Pad
    // Tambahan" (pad13-16, cuma muncul kalau extraPadsActive) DITEMPEL sebagai
    // kolom ke-4 di TIAP baris yang sudah ada (bukan baris ke-5 sendiri di
    // bawah) - satu pad tambahan per baris, jadi dia nampil DI SAMPING 3 pad
    // bawaan baris itu. Karena kolomnya sama-sama pakai weight(1f) di dalam
    // Row yang sama (lihat repeat(columns) di bawah), lebar & tinggi pad
    // tambahan otomatis ngikut persis pad lain di baris itu juga - gak perlu
    // weight/ukuran sendiri kayak baris ke-5 versi lama.
    val rowSpecs = listOf(0.85f, 2f, 2f, 0.85f)
    // Satu pad tambahan per baris (13 di baris 1, 14 di baris 2, dst) - urutan
    // ini yang bikin pad13-16 nempel sebagai kolom terakhir baris masing-masing.
    val extraPadIdByRow = listOf(13, 14, 15, 16)

    // Posisi & ukuran tiap pad (koordinat window), dicatat lewat onGloballyPositioned di
    // masing-masing Pad. Dipakai buat hit-testing manual di pointerInput level GRID di
    // bawah -> ini yang bikin "geser dari 1 pad ke pad lain" kebaca dan ikut bunyi.
    //
    // Kenapa gak cukup pointerInput per-Pad (versi sebelumnya): begitu Compose nentuin
    // suatu pointer "dimiliki" oleh composable tempat dia pertama kali ACTION_DOWN,
    // event MOVE pointer itu SETERUSNYA cuma dikirim ke composable itu-itu aja, walau
    // posisi jarinya udah keluar dari area pad tsb dan masuk ke pad lain. Makanya dulu
    // geser jari dari pad A ke pad B gak micu bunyi pad B sama sekali - yang kepanggil
    // cuma pad A (tempat jari mulai nempel).
    //
    // Fix-nya: satu pointerInput di level GRID (bukan per-pad) yang, di SETIAP event
    // (down maupun move), hitung sendiri pad mana yang lagi ketiban posisi jari (pakai
    // koordinat window, biar gak kebingungan gara-gara padding/weight/gap tiap pad),
    // lalu panggil onDown() begitu jari itu masuk ke pad yang BEDA dari pad sebelumnya
    // (termasuk pas down pertama kali). Efeknya: geser jari melintasi beberapa pad tanpa
    // ngangkat, tiap pad yang dilewati ikut bunyi satu-satu - persis teknik "glide/roll"
    // di drum pad fisik atau app pad lain (FL Studio Mobile dsb), gak perlu ketuk² lagi.
    //
    // Fitur "geser ikut mindah/bunyiin pad lain" di atas sekarang BISA
    // dimatikan lewat switch "Geser Pindah Pad" di dialog Pengaturan (lihat
    // PadSlideRollState, DEFAULT NONAKTIF) - tap pertama tiap sentuhan tetap
    // SELALU bunyi seperti biasa; yang dikontrol switch itu cuma efek geser
    // SETELAH jari nempel (lihat percabangan previousPad/hitPad di bawah).
    val padCoords = remember { mutableMapOf<Int, LayoutCoordinates>() }
    // Posisi & ukuran chip tempo (tombol −/+) yang SAAT INI lagi kegambar (cuma ada
    // entry buat pad yang lagi loop - lihat Pad()), dicatat lewat onGloballyPositioned
    // persis kayak padCoords di atas. Dipakai di pointerInput bawah buat SKIP hit-test
    // "ketuk pad" kalau sentuhan jatuh di area chip kecil ini - tanpa ini, nge-tap
    // tombol −/+ bakal ke-anggap "ketuk pad" juga (loop Initial-pass grid ini jalan
    // duluan sebelum tombolnya sempat proses tap-nya sendiri lewat Main pass), yang
    // bikin loop pad itu malah ke-STOP tiap kali user coba pencet tombol tempo-nya.
    val tempoSliderCoords = remember { mutableMapOf<Int, LayoutCoordinates>() }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // pointerId -> pad yang lagi "ditempatin" jari itu sekarang, biar onDown() cuma
    // dipanggil pas jari BERPINDAH ke pad baru (bukan tiap event move di pad yang sama).
    val pointerPad = remember { mutableMapOf<Long, Int>() }

    Column(
        modifier = modifier
            .onGloballyPositioned { gridCoords = it }
            // Transparan kalau ada background gambar (BackgroundState) - biar gambarnya
            // keliatan lewat celah 3dp antar pad & garis tepi grid, bukan ketutup warna
            // solid PadLineColor. Pad-nya sendiri (lihat Pad() di bawah) TETAP solid.
            .background(if (hasBackgroundImage) Color.Transparent else PadLineColor)
            .padding(3.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Toleransi snap ke pad terdekat kalau sentuhan jatuh di celah antar pad
                    // (bukan pas di dalam pad manapun). 12dp cukup buat nutup celah 3dp + sedikit
                    // ekstra buat jitter sensor sentuh, tapi masih jauh lebih kecil dari ukuran
                    // pad itu sendiri jadi gak bakal salah pilih pad yang jauh.
                    val gapSnapThresholdPx = 12.dp.toPx()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val gc = gridCoords
                        event.changes.forEach { change ->
                            if (!change.pressed) {
                                // jari terangkat -> lupain posisi pad terakhirnya
                                pointerPad.remove(change.id.value)
                                return@forEach
                            }
                            if (gc == null) return@forEach
                            // Cukup evaluasi kalau ini down baru ATAU posisinya emang gerak,
                            // biar gak buang kerjaan ngecek hit-test tiap frame pas jari diem.
                            if (!change.changedToDown() && !change.positionChanged()) return@forEach

                            val windowPos = gc.localToWindow(change.position)

                            // Kalau titik sentuh ini jatuh di area CHIP TEMPO (tombol
                            // −/+) pad yang lagi loop, biarin event ini lolos apa
                            // adanya ke tombolnya (lewat Main pass normal Compose) -
                            // JANGAN dianggap sebagai pukulan pad sama sekali (lihat
                            // catatan panjang di deklarasi tempoSliderCoords di atas).
                            var insideTempoSlider = false
                            for ((_, sc) in tempoSliderCoords) {
                                if (!sc.isAttached) continue
                                val localS = sc.windowToLocal(windowPos)
                                val sizeS = sc.size
                                if (localS.x >= 0f && localS.y >= 0f &&
                                    localS.x <= sizeS.width.toFloat() && localS.y <= sizeS.height.toFloat()
                                ) {
                                    insideTempoSlider = true
                                    break
                                }
                            }
                            if (insideTempoSlider) return@forEach

                            var hitPad = -1
                            // Jarak (kuadrat) terkecil ke tepi pad, dipakai sebagai fallback kalau
                            // titik sentuh gak pas kena kotak pad manapun (misalnya jatuh persis di
                            // celah/seam 3dp antar pad). Tanpa fallback ini, sentuhan yang jatuh di
                            // celah dibuang diam-diam -> gak nyala, gak bunyi, padahal jari udah
                            // "kena" pad secara kasat mata. Karena celahnya cuma beberapa dp, pad
                            // TERDEKAT hampir pasti pad yang emang dituju, jadi aman selalu dipilih.
                            var bestDist = Float.MAX_VALUE
                            var nearestPad = -1
                            for ((padId, coords) in padCoords) {
                                if (!coords.isAttached) continue
                                val local = coords.windowToLocal(windowPos)
                                val size = coords.size
                                if (local.x >= 0f && local.y >= 0f &&
                                    local.x <= size.width.toFloat() && local.y <= size.height.toFloat()
                                ) {
                                    hitPad = padId
                                    break
                                }
                                // Jarak dari titik ke kotak pad ini (0 kalau di dalam, >0 kalau di luar)
                                val dx = when {
                                    local.x < 0f -> -local.x
                                    local.x > size.width -> local.x - size.width
                                    else -> 0f
                                }
                                val dy = when {
                                    local.y < 0f -> -local.y
                                    local.y > size.height -> local.y - size.height
                                    else -> 0f
                                }
                                val dist = dx * dx + dy * dy
                                if (dist < bestDist) {
                                    bestDist = dist
                                    nearestPad = padId
                                }
                            }
                            // Fallback cuma dipakai kalau memang gak ada exact-hit, dan cuma kalau
                            // titik-nya deket banget (<= 12dp dari tepi pad) -> supaya sentuhan yang
                            // beneran di luar area grid (kalau ada) tetap gak dipaksa masuk ke pad.
                            if (hitPad < 0 && nearestPad >= 0 && bestDist <= gapSnapThresholdPx * gapSnapThresholdPx) {
                                hitPad = nearestPad
                            }
                            if (hitPad < 0) return@forEach
                            val pointerId = change.id.value
                            val previousPad = pointerPad[pointerId]
                            if (previousPad == null) {
                                // Jari BARU nempel (belum punya pad tercatat) -> selalu
                                // dianggap pukulan, terlepas dari switch "Geser Pindah
                                // Pad" - switch itu cuma ngatur geser SETELAH jari nempel
                                // (lihat cabang di bawah), bukan tap pertamanya.
                                pointerPad[pointerId] = hitPad
                                onPadDown(hitPad)
                            } else if (previousPad != hitPad && PadSlideRollState.enabled) {
                                // Jari udah nempel di suatu pad terus GESER ke pad lain -
                                // ini yang dikontrol switch "Geser Pindah Pad" (default
                                // nonaktif, lihat PadSlideRollState). Nonaktif -> geser
                                // gak ikut mindah/bunyiin pad lain, mesti angkat jari dulu
                                // baru ketuk pad berikutnya (perilaku ketuk biasa).
                                pointerPad[pointerId] = hitPad
                                onPadDown(hitPad)
                            }
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        var padId = 1
        rowSpecs.forEachIndexed { rowIndex, weight ->
            // Kolom baris ini = 3 pad bawaan, ditambah 1 pad tambahan di ujung
            // (kolom ke-4) kalau extraPadsActive - bukan baris terpisah, jadi
            // pad tambahan kegambar DI SAMPING pad bawaan baris yang sama.
            val idsInRow = buildList {
                repeat(3) { add(padId++) }
                if (extraPadsActive) add(extraPadIdByRow[rowIndex])
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                idsInRow.forEach { id ->
                    Pad(
                        id = id,
                        flashIntensity = flashIntensity[id] ?: 0f,
                        selected = selectedPad == id,
                        opacity = padOpacity,
                        isLooping = id in loopingPads,
                        loopTempo = padLoopTempos[id] ?: 1f,
                        onLoopTempoChange = { rate -> onLoopTempoChange(id, rate) },
                        onSliderBoundsChanged = { coords ->
                            if (coords != null) tempoSliderCoords[id] = coords else tempoSliderCoords.remove(id)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { coords -> padCoords[id] = coords }
                    )
                }
            }
        }
    }
}

@Composable
fun Pad(
    id: Int,
    flashIntensity: Float,
    selected: Boolean,
    opacity: Float = 1f,
    // True kalau pad ini SAAT INI kedengeran lagi loop (gaya DTX M12) - lihat
    // PadScreen.loopingPads. Cuma pas ini true chip tempo di bawah kegambar.
    isLooping: Boolean = false,
    // Nilai tempo yang lagi ditampilin (0.5f..2f, 1f = normal speed).
    loopTempo: Float = 1f,
    onLoopTempoChange: (Float) -> Unit = {},
    // Dipanggil dengan posisi/ukuran chip tempo tiap kali dia digambar ulang, dan
    // dengan null begitu chip-nya dilepas dari layar (loop berhenti/pad diganti) -
    // lihat catatan panjang di tempoSliderCoords pada PadGrid buat kenapa ini perlu.
    onSliderBoundsChanged: (LayoutCoordinates?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // flashIntensity (0f..1f) di-drive tiap frame dari PadScreen (lihat loop
    // withFrameNanos di sana), bukan flag on/off lagi -> Pad di sini murni nge-blend
    // warna sesuai intensitas yang dikasih, gak perlu tau apa-apa soal timing.
    //
    // Deteksi sentuh/geser ditangani terpusat di PadGrid (lihat komentar di sana) -
    // Pad di sini murni tampilan, gak punya pointerInput sendiri, biar gak ada 2 sistem
    // deteksi sentuh yang saling rebutan pointer. Chip tempo di bawah (2 tombol +/-)
    // TETAP pakai gesture tap-nya sendiri (detectTapGestures) - aman karena PadGrid
    // udah sengaja nge-skip hit-test "ketuk pad" kalau sentuhan jatuh di area chip ini.
    val t = flashIntensity.coerceIn(0f, 1f)
    val bg = if (t <= 0f) PadColor else lerp(PadColor, ThemeState.accentColor, t)
    val fg = if (t <= 0f) PadTextColor else lerp(PadTextColor, ThemeState.accentTextColor, t)

    // Begitu pad ini BERHENTI loop (isLooping balik ke false, mis. dipukul lagi buat
    // stop, atau pindah preset), bilangin ke PadGrid chip-nya udah gak ada lagi -
    // kalau enggak, entry basi di tempoSliderCoords bisa nyangkut nutupin area pad
    // yang udah gak nampilin chip apa-apa (bug "gak bisa mukul pad di titik itu").
    DisposableEffect(isLooping) {
        onDispose { if (isLooping) onSliderBoundsChanged(null) }
    }

    Box(
        modifier = modifier
            .background(bg.copy(alpha = bg.alpha * opacity.coerceIn(0f, 1f)), RoundedCornerShape(3.dp))
            .then(
                if (selected) Modifier.border(2.dp, ThemeState.accentColor, RoundedCornerShape(3.dp)) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "$id", color = fg, fontWeight = FontWeight.SemiBold, fontSize = AppTextSize)

        if (isLooping) {
            // GANTI dari slider geser (versi awal) ke chip 2-tombol (−/+) yang kecil &
            // padat, nempel di POJOK KANAN BAWAH pad, BUKAN melebar hampir sepenuh
            // lebar pad kayak sebelumnya. Alasan gantinya:
            //  1) Slider geser butuh area sentuh lebar buat presisi drag-nya, dan di
            //     pad kecil area itu "makan" hampir seluruh zona yang biasa dipakai
            //     buat ketuk-stop -> jari yang niatnya mukul pad buat STOP malah
            //     sering kena slider (loop-nya gak berhenti, malah tempo-nya kegeser).
            //  2) Chip 2-tombol ini ukurannya TETAP KECIL (bukan fillMaxWidth), jadi
            //     sisa badan pad (termasuk nomor pad di tengah) tetap luas & aman
            //     buat ketuk-stop seperti biasa - cuma sudut kanan-bawah yang "reserved"
            //     buat kontrol tempo, gak "maksa" nutup sebagian besar pad kayak
            //     sebelumnya, dan teksnya cuma 1 baris (gak numpuk 2 elemen vertikal
            //     kayak label+slider dulu) jadi gak nabrak nomor pad di tengah lagi.
            //  3) Tap diskrit (per tombol) juga jauh lebih gampang kena presisinya
            //     dibanding narik thumb slider yang tipis, apalagi di pad kecil.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(CtrlPanelColor.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
                    .onGloballyPositioned { coords -> onSliderBoundsChanged(coords) }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            ) {
                TempoStepButton(label = "−", onTap = {
                    onLoopTempoChange((loopTempo - TEMPO_STEP).coerceIn(0.5f, 2f))
                })
                Text(
                    // Math.round, BUKAN .toInt() - .toInt() motong ke bawah, dan
                    // loopTempo hasil penjumlahan berulang TEMPO_STEP (Float) suka
                    // punya sisa imprecision biner (mis. 1.05f kadang kesimpen
                    // sebagai 1.0499999...f, bukan pas 1.05f). Dikali 100 jadi
                    // 104.99999...%, dan kalau dipotong hasilnya 104% - keliatan
                    // kayak lompat ganjil (100 -> 104) padahal step-nya tetap 5%
                    // persis, cuma salah dibulatinnya doang.
                    text = "${Math.round(loopTempo * 100)}%",
                    color = Color(0xFFF2EDE9),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        // Dobel-tap di angka persen = reset cepat ke 100% (kecepatan
                        // normal) - tanpa ini user yang kepencet kejauhan dari 100%
                        // harus mepet-tap −/+ belasan kali buat balik pas, padahal
                        // "balik ke normal" itu aksi yang paling sering dibutuhin.
                        // Pakai gesture terpisah dari TempoStepButton (bukan nambah
                        // fungsi baru ke tombolnya) biar −/+ tetap murni step biasa.
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onLoopTempoChange(1f) })
                        }
                )
                TempoStepButton(label = "+", onTap = {
                    onLoopTempoChange((loopTempo + TEMPO_STEP).coerceIn(0.5f, 2f))
                })
            }
        }
    }
}

// Besar langkah tiap tap tombol +/- tempo loop (5%) - lihat TempoStepButton & Pad().
private const val TEMPO_STEP = 0.05f

// Jeda (ms) sebelum tombol tempo yang ditahan mulai "nge-repeat" sendiri, dan
// jarak antar repeat setelah itu - pola standar tombol hold-to-repeat (mis.
// tombol volume fisik), lihat TempoStepButton.
private const val TEMPO_REPEAT_INITIAL_DELAY_MS = 350L
private const val TEMPO_REPEAT_INTERVAL_MS = 70L

// 1 tombol (−/+) buat chip tempo loop di Pad(). Ukuran dinaikkan dari versi
// sebelumnya (16x14dp -> 22x20dp) supaya jempol gak gampang meleset ke tombol
// sebelahnya atau ke luar chip pas main cepat - sengaja masih BUKAN pakai
// IconButton material (defaultnya minimal 48dp touch target, kegedean buat muat
// berdampingan di sudut pad sekecil ini, malah bikin chip-nya balik lagi makan
// banyak ruang & nutupin area ketuk-stop pad, lihat catatan panjang di Pad()).
//
// DITAMBAHIN hold-to-repeat: sebelumnya user yang mau geser tempo jauh (mis. dari
// 100% ke 70%) harus tap tombol yang sama 6x berturut-turut, presisi & kelamaan.
// Sekarang cukup TAHAN tombolnya - begitu ditekan langsung jalan sekali, lalu
// (setelah jeda awal biar tap sekali biasa gak ke-double-count) terus nge-ulang
// sendiri selama masih ditahan, berhenti begitu jari dilepas.
@Composable
private fun TempoStepButton(label: String, onTap: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onTap()
            delay(TEMPO_REPEAT_INITIAL_DELAY_MS)
            while (isPressed) {
                onTap()
                delay(TEMPO_REPEAT_INTERVAL_MS)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(width = 22.dp, height = 20.dp)
            // Sedikit lebih terang selagi ditekan/ditahan - umpan balik visual biar
            // user yakin tahanannya beneran kedetect (penting justru karena sekarang
            // ada mode "tahan" yang gak jelas keliatan cuma dari angka persen yang
            // jalan pelan-pelan).
            .background(
                Color.Black.copy(alpha = if (isPressed) 0.45f else 0.28f),
                RoundedCornerShape(3.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    // Nunggu jari diangkat ATAU gesture dibatalin (mis. jari geser
                    // keluar area tombol) - kedua kasus itu WAJIB matiin isPressed,
                    // kalau enggak repeat-nya bisa nyangkut jalan terus walau jari
                    // udah gak di tombol itu lagi.
                    waitForUpOrCancellation()
                    isPressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color(0xFFF2EDE9), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

// Tab tipis permanen buat sembunyi/tampilin strip aksi bawah + panel musik. Pengganti
// gestur "cubit 2 jari" yang lama - lihat catatan panjang di pemanggilnya (PadScreen).
// Sengaja composable TERPISAH dari PadGrid/Pad, dan dipasang sebagai kolom sendiri di
// Row (bukan overlay di atas pad) -> area sentuhnya gak pernah numpuk sama hit-test
// pad manapun, jadi gak mungkin ke-trigger gak sengaja pas main serame/secepat apapun.
@Composable
fun HideToggleTab(hidden: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(CtrlPanelColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggle() })
            },
        contentAlignment = Alignment.Center
    ) {
        // "‹" pas panel lagi kebuka (tap buat nutup/perbesar area pad), "›" pas panel
        // lagi disembunyiin (tap buat balikin). Kecil tapi jelas fungsinya di sentuh.
        Text(
            text = if (hidden) "‹" else "›",
            color = PadTextColor,
            fontWeight = FontWeight.Bold,
            fontSize = AppTextSize
        )
    }
}

// Panel preset vertikal di sisi kiri: tombol ganti bank (ikon putar), 4 tombol
// preset (P1-P4 atau P5-P8), dan tombol Pengaturan (ikon gear, buka dialog volume
// per pad) - SEMUA dijadiin 1 grup weight(1f) yang sama rata dalam 1 Column, dengan
// jarak antar tombol yang sama persis (GroupItemSpacing, seragam dengan panel kanan
// & strip bawah) - dulu tombol switch beda sendiri (tinggi fix + jarak 7dp ke
// bawah), sekarang keenam tombol identik tinggi & spacing-nya.
@Composable
fun PresetSidePanel(
    bank: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    onLockedPresetTap: () -> Unit,
    onBankToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(CtrlPanelColor)
            // Sama persis dengan padding grup kanan (MusicSidePanel) & bawah (strip
            // tombol aksi) - GroupOuterPaddingV/H & GroupItemSpacing - biar konsisten.
            .padding(vertical = GroupOuterPaddingV, horizontal = GroupOuterPaddingH),
        verticalArrangement = Arrangement.spacedBy(GroupItemSpacing)
    ) {
        // Ikon vektor (bukan emoji 🔄 lagi - emoji tampilannya bisa beda-beda
        // tergantung vendor device), tanpa teks angka bank - lebih ringkas
        // dibanding nulis "1-4"/"5-8". Ukuran ControlIconSize biar jelas & gak
        // kekecilan kayak kalau disamain sama ukuran teks label lain.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onBankToggle() })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = "Ganti bank preset",
                tint = PadTextColor,
                modifier = Modifier.size(ControlIconSize)
            )
        }

        ((bank * 4 + 1)..(bank * 4 + 4)).forEach { p ->
            val active = current == p
            val locked = p > FREE_UNLOCKED_PRESETS
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (active) ThemeState.accentColor else PanelColor, RoundedCornerShape(4.dp))
                    .pointerInput(p, locked) {
                        detectTapGestures(onPress = { if (locked) onLockedPresetTap() else onSelect(p) })
                    },
                contentAlignment = Alignment.Center
            ) {
                // Preset terkunci (versi FREE): gembok doang, gak ada label P-nya -
                // biar jelas beda dari yang kebuka, dan tap-nya ngarahin ke upgrade.
                if (locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "P$p terkunci - upgrade ke Pro",
                        tint = TextDim,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        "P$p",
                        color = if (active) ThemeState.accentTextColor else TextDim,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = AppTextSize
                    )
                }
            }
        }

        // Tombol Pengaturan: buka dialog slider volume per pad (P1..P12) buat preset
        // yang lagi aktif. Weight(1f) & shape/warna PanelColor SAMA PERSIS kayak
        // tombol switch bank di atas, biar keliatan satu keluarga tombol, bukan
        // tempelan yang beda gaya.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpenSettings() })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Pengaturan volume pad",
                tint = PadTextColor,
                modifier = Modifier.size(ControlIconSize)
            )
        }
    }
}

// Strip kontrol lagu, vertikal, nempel di sisi kanan layar (mirror dari strip
// horizontal di bawah pad). Isinya: pilih/ganti lagu, play/stop, seek ke posisi
// tertentu, dan 2 slider volume terpisah (kendang vs musik).
@Composable
fun MusicSidePanel(
    songLoaded: Boolean,
    isLoadingMusic: Boolean,
    isMusicPlaying: Boolean,
    positionSec: Double,
    durationSec: Double,
    kendangVolume: Float,
    musicVolume: Float,
    onPickSong: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onKendangVolumeChange: (Float) -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // Garis pemisah tipis antar-grup kontrol, biar tiap bagian (transport / seek /
    // mixer) kebaca sebagai unit yang beda, bukan tumpukan elemen yang dipaksa muat.
    @Composable
    fun GroupDivider() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }

    Column(
        modifier = modifier
            .background(CtrlPanelColor)
            // Sama persis dengan padding grup kiri (PresetSidePanel) & bawah (strip
            // tombol aksi) - GroupOuterPaddingV/H & GroupItemSpacing - biar konsisten.
            .padding(vertical = GroupOuterPaddingV, horizontal = GroupOuterPaddingH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GroupItemSpacing)
    ) {
        // --- Grup 1: transport (pilih lagu, play/stop) ---
        // LAGU & PLAY sengaja dikasih padding vertical yang SAMA PERSIS (9dp) -
        // dulu beda (8dp vs 10dp) jadi tingginya kelihatan beda tipis & aneh.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThemeState.accentColor, RoundedCornerShape(5.dp))
                .pointerInput(Unit) { detectTapGestures(onPress = { onPickSong() }) }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isLoadingMusic -> "MUAT.."
                    songLoaded -> "GANTI"
                    else -> "LAGU"
                },
                color = ThemeState.accentTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = AppTextSize,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isMusicPlaying) Color(0xFFD32F2F) else PanelColor, RoundedCornerShape(5.dp))
                .pointerInput(songLoaded) {
                    detectTapGestures(onPress = { onToggle() })
                }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ikon aja (bukan teks "PLAY"/"STOP") - segitiga play pas berhenti, kotak
            // stop pas lagi muter, ukuran ControlIconSize biar jelas kebaca dari jauh.
            Icon(
                imageVector = if (isMusicPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isMusicPlaying) "Stop musik" else "Putar musik",
                tint = if (isMusicPlaying) Color.White else PadTextColor,
                modifier = Modifier.size(ControlIconSize)
            )
        }

        GroupDivider()

        // --- Grup 2: seek posisi lagu ---
        // Seeker vertikal -> geser buat pindah posisi lagu. Atas = akhir lagu,
        // bawah = awal, biar konsisten sama volume (atas = lebih tinggi/jauh).
        VerticalSlider(
            value = positionSec.toFloat().coerceIn(0f, durationSec.toFloat().coerceAtLeast(0.01f)),
            onValueChange = { onSeek(it.toDouble()) },
            valueRange = 0f..durationSec.toFloat().coerceAtLeast(0.01f),
            modifier = Modifier
                .weight(1.5f)
                .fillMaxWidth()
        )

        // Posisi & durasi digabung 1 baris (bukan 2 baris terpisah) biar gak makan
        // ruang vertikal ekstra yang bikin sisa slider di bawahnya kesempitan.
        Text(
            "${fmt(positionSec)}/${fmt(durationSec)}",
            color = TextDim,
            fontSize = 9.sp,
            maxLines = 1
        )

        GroupDivider()

        // --- Grup 3: mixer volume (pad & musik) ---
        Text("PAD", color = TextDim, fontSize = AppTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        VerticalSlider(
            value = kendangVolume,
            onValueChange = onKendangVolumeChange,
            valueRange = 0f..1.5f,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        Text("MUSIK", color = TextDim, fontSize = AppTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        VerticalSlider(
            value = musicVolume,
            onValueChange = onMusicVolumeChange,
            valueRange = 0f..1.5f,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

// Compose gak nyediain Slider vertikal bawaan -> ini trik umum: ukur si Slider
// biasa (horizontal) dengan constraint width/height ketuker, terus diputer 90°.
// Hasilnya slider yang keliatan & bisa disentuh secara vertikal, tanpa nulis
// custom drag-detector sendiri (behavior/animasi thumb dsb tetap bawaan Slider).
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = ThemeState.accentColor,
            activeTrackColor = ThemeState.accentColor,
            inactiveTrackColor = PanelColor
        ),
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width / 2 - placeable.height / 2),
                        y = -(placeable.height / 2 - placeable.width / 2)
                    )
                }
            }
            // -90 derajat: ujung "atas" slider = value max, ujung "bawah" = value min,
            // konsisten sama ekspektasi umum kontrol volume (geser ke atas = lebih besar).
            .rotate(-90f)
    )
}
