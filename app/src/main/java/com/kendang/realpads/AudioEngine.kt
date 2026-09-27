package com.kendang.realpads

// Jembatan tipis ke native engine (C++/Oboe). Semua playback beneran
// terjadi di audio thread native, bukan di JVM/UI thread.
object AudioEngine {
    init {
        System.loadLibrary("padengine")
    }

    external fun nativeInit()
    external fun nativeLoadSample(preset: Int, pad: Int, pcm: ShortArray, channels: Int)
    // Kosongin sample yang lagi kepasang di 1 pad (preset+pad), tanpa nge-load
    // apa-apa buat gantiin - dipakai PresetStorage.clearPreset pas load preset
    // baru yang gak isi semua 12 pad, biar pad yang gak keisi gak nyisain suara
    // dari preset sebelumnya.
    external fun nativeClearPad(preset: Int, pad: Int)
    external fun nativeTrigger(preset: Int, pad: Int)
    external fun nativeShutdown()

    // Dipanggil dari onPause/onResume Activity (bukan onCreate/onDestroy) - buat
    // ngelepas jalur audio exclusive begitu app ke-background, dan ambil lagi begitu
    // balik ke depan. Ini yang bikin app FREE & Premium yang diinstall bareng gak
    // rebutan jalur exclusive kalau dibuka gantian: sample yang udah di-load ke pad
    // TETEP ada (gak kayak nativeShutdown yang bongkar semuanya), cuma jalur audio
    // hardware-nya aja yang dilepas-sambung.
    external fun nativePause()
    external fun nativeResume()

    // Rekam mixdown pad (stereo, 48kHz, interleaved) -> dipakai fitur record.
    external fun nativeStartRecording()
    // FIX: tipe balik dibikin nullable ("ShortArray?"). Implementasi native (lihat
    // native-lib.cpp) sengaja "return nullptr;" kalau alokasi array hasil rekaman
    // gagal (OOM, rekaman kepanjangan). Dulu dideklarasikan non-null ("ShortArray")
    // di sini - begitu native beneran balikin null, Kotlin langsung nge-throw NPE
    // TEPAT di titik pemanggilan (bukan di titik dipakai), jadi kalaupun pemanggilnya
    // niat nangkep exception belakangan, urutan evaluasinya tetap gak aman. Sekarang
    // tipe & perilakunya cocok, null ditangani eksplisit di semua pemanggil.
    external fun nativeStopRecording(): ShortArray?

    // Nyicil ambil PCM yang udah kerekam sejauh ini TANPA menghentikan recording
    // (beda dari nativeStopRecording() di atas) - dipakai fitur rekam layar
    // (ScreenRecordService) buat narik audio tiap ~100-200ms selagi VirtualDisplay
    // + video encoder jalan bareng, biar audio langsung ke-encode & ke-mux
    // sepanjang recording, bukan cuma di akhir. Sumbernya sama persis dengan
    // nativeStopRecording() (mixdown pad+musik dari native, bukan capture
    // eksternal), jadi TIDAK pernah menyentuh/mengganggu jalur audio exclusive.
    external fun nativeDrainRecording(): ShortArray

    // Player lagu latar. PCM harus udah 48kHz (di-resample di MusicLoader.kt
    // sebelum dikirim ke sini) -> ikut kemix & kerekam bareng suara kendang.
    // Return false kalau gagal dimuat ke native engine (mis. device gak punya cukup
    // memori buat lagu yang panjang/berat) -> dipakai buat kasih tau user dengan toast,
    // bukan biarin app crash.
    external fun nativeLoadMusic(pcm: ShortArray, channels: Int): Boolean
    external fun nativePlayMusic()
    external fun nativeStopMusic()
    external fun nativeSeekMusic(seconds: Double)
    external fun nativeGetMusicPosition(): Double
    external fun nativeGetMusicDuration(): Double
    external fun nativeIsMusicPlaying(): Boolean

    // Volume terpisah: kendang (semua pad) vs musik latar.
    external fun nativeSetKendangVolume(volume: Float)
    external fun nativeSetMusicVolume(volume: Float)

    // Volume PER PAD (per preset+pad) - buat nyeimbangin tingkatan suara antar pad,
    // independen dari nativeSetKendangVolume (yang ngalikan SEMUA pad sekaligus).
    external fun nativeSetPadVolume(preset: Int, pad: Int, volume: Float)
    external fun nativeGetPadVolume(preset: Int, pad: Int): Float

    // Tune PER PAD (preset+pad) dalam semitone, -12f..12f, 0f = pitch asli sample.
    // Diimplementasi di native lewat resampling kecepatan baca voice (lihat catatan
    // di PadEngine.h/setPadTune) - jadi pitch naik/turun juga sedikit ngubah durasi,
    // konsisten sama karakter sampler/drum machine murah pada umumnya.
    external fun nativeSetPadTune(preset: Int, pad: Int, semitones: Float)
    external fun nativeGetPadTune(preset: Int, pad: Int): Float

    // Reverb PER PAD (preset+pad), 0f..1f: seberapa besar porsi suara pad itu yang
    // dikirim ke SATU bus reverb bersama (dipakai semua pad, hemat CPU). 0 = kering
    // total, 1 = kirim penuh.
    external fun nativeSetPadReverb(preset: Int, pad: Int, amount: Float)
    external fun nativeGetPadReverb(preset: Int, pad: Int): Float

    // Choke PER PAD (preset+pad), default true: kalau aktif, pukulan baru di pad yang
    // sama motong (restart) pukulan sebelumnya yang masih bunyi - berguna buat
    // kebanyakan pad (bikin roll kedengeran rapi, gak numpuk), tapi bisa kedengeran
    // "tet" (klik) di sample yang panjang. Matiin per pad kalau mau suara pad itu
    // dibiarkan overlap/nyelesain sendiri tanpa dipotong.
    external fun nativeSetPadChoke(preset: Int, pad: Int, enabled: Boolean)
    external fun nativeGetPadChoke(preset: Int, pad: Int): Boolean

    // Choke ANTAR PAD (fitur baru, beda dari nativeSetPadChoke di atas yang cuma
    // motong DI PAD YANG SAMA): pad ini bisa disetel berhenti otomatis kalau
    // salah satu pad lain yang dicentang di daftarnya (mask) dipukul. enabled =
    // switch master per pad, mask = bitmask 12-bit (bit (targetPad-1) = pad
    // target yang tercentang). Satu arah & independen per pad - lihat catatan
    // panjang di PadEngine.h/PadEngine::setPadCrossChokeMask.
    external fun nativeSetPadCrossChokeEnabled(preset: Int, pad: Int, enabled: Boolean)
    external fun nativeGetPadCrossChokeEnabled(preset: Int, pad: Int): Boolean
    external fun nativeSetPadCrossChokeMask(preset: Int, pad: Int, mask: Int)
    external fun nativeGetPadCrossChokeMask(preset: Int, pad: Int): Int

    // Loop PER PAD (preset+pad), default false - gaya DTX M12: kalau aktif, sekali
    // ketuk pad-nya muter sample itu berulang tanpa jeda sampai diketuk lagi buat
    // berhenti. Lihat catatan panjang di PadEngine.setPadLoop buat detail gimana
    // "ketuk buat stop"-nya diimplementasi di native.
    external fun nativeSetPadLoop(preset: Int, pad: Int, enabled: Boolean)
    external fun nativeGetPadLoop(preset: Int, pad: Int): Boolean

    // Tempo LIVE loop PER PAD (preset+pad), 0.5f..2.0f, default 1.0f - gaya knob
    // turntable/tempo DTX M12 asli: BEDA dari nativeSetPadTune (yang cuma kepakai di
    // ketukan BERIKUTNYA), ini kepakai LANGSUNG di tengah loop yang lagi muter.
    // Persist ke disk lewat PadLoopTempoStorage (dipanggil dari situ, bukan
    // langsung dari sini) - jadi tempo yang di-set user NEMPEL walau loop-nya
    // dihentikan/dimulai lagi, atau apk ditutup total lalu dibuka lagi.
    external fun nativeSetPadLoopTempo(preset: Int, pad: Int, rate: Float)
    external fun nativeGetPadLoopTempo(preset: Int, pad: Int): Float

    // True kalau pad ini SAAT INI punya suara yang lagi aktif loop - dipoll berkala
    // dari MainActivity buat nentuin pad mana yang harus nampilin slider tempo
    // langsung di kotak pad-nya sendiri (lihat PadGrid/Pad).
    external fun nativeIsPadLooping(preset: Int, pad: Int): Boolean
}
