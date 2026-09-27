#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <vector>
#include <deque>
#include <mutex>
#include <atomic>
#include <thread>
#include <cstdint>

struct SampleBuffer {
    std::vector<float> data; // interleaved, normalized -1..1
    int channels = 1;
    // Frame TERAKHIR (dari akhir) yang dianggap masih "ada bunyinya" + sedikit
    // padding - dipakai SEBAGAI TITIK LONCAT BALIK loop (bukan buat motong data
    // sample-nya sendiri, `data` di atas TETAP UTUH APA ADANYA/ori). Kalau sample
    // WAV-nya masih nyisa hening beberapa puluh/ratus ms di ekor (umum dari hasil
    // rekam/export DAW), loop yang nunggu sampe BENERAN abis (akhir `data`)
    // kedengeran "diem duluan" sesaat sebelum lompat balik ke awal - kedengeran
    // kayak jeda walau secara sample-accurate gak ada 1 frame pun yang beneran
    // dilewatin senyap. Dengan field ini, loop lompat balik LEBIH AWAL, persis
    // pas levelnya udah mulai turun ke hening, tanpa nyentuh data aslinya sama
    // sekali - jadi mode non-loop (sekali ketuk) tetap muter sample APA ADANYA
    // sampai bener-bener habis, gak kepengaruh sama sekali. -1 = belum dihitung/
    // sample kosong -> fallback ke panjang penuh `data` (lihat loadSample()).
    int loopEndFrame = -1;
};

// Reverb ringan bergaya Freeverb (comb + allpass). SATU instance dipakai bareng
// oleh SEMUA pad (bukan bikin reverb per-voice) supaya CPU-nya murah - tiap pad
// cuma ngirim porsi "wet send"-nya sendiri (lihat padReverb_) ke bus mono ini,
// hasilnya (stereo) dijumlahin balik ke output di onAudioReady. Delay line-nya
// di-alokasi sekali lewat prepare() (dipanggil dari constructor engine), BUKAN
// di audio callback, biar gak ada alokasi memori di real-time thread.
class SimpleReverb {
public:
    void prepare(int sampleRate);
    // input = sinyal mono gabungan dari semua "send" pad di 1 frame,
    // outL/outR = hasil wet stereo buat frame itu.
    void process(float input, float &outL, float &outR);

private:
    struct Comb {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        float damp1 = 0.2f;
        float damp2 = 0.8f;
        float filterStore = 0.0f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
            filterStore = 0.0f;
        }
        float process(float in) {
            float output = buffer[index];
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[index] = in + (filterStore * feedback);
            if (++index >= (int)buffer.size()) index = 0;
            return output;
        }
    };
    struct Allpass {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
        }
        float process(float in) {
            float bufOut = buffer[index];
            float output = -in + bufOut;
            buffer[index] = in + (bufOut * feedback);
            if (++index >= (int)buffer.size()) index = 0;
            return output;
        }
    };

    static constexpr int kNumCombs = 4;
    static constexpr int kNumAllpasses = 2;
    std::array<Comb, kNumCombs> combsL_;
    std::array<Comb, kNumCombs> combsR_;
    std::array<Allpass, kNumAllpasses> allpassL_;
    std::array<Allpass, kNumAllpasses> allpassR_;
};

// Mixer polyphonic (24 voice) yang jalan langsung di audio callback, jadi ketukan
// pad langsung "nyalain" voice tanpa lewat scheduling tambahan. Voice yang lagi
// nganggur diprioritaskan, jadi sample panjang yang masih jalan gak gampang kepotong.
class PadEngine : public oboe::AudioStreamCallback {
public:
    PadEngine();

    bool start();
    void stop();
    // Dipakai buat cek dari luar (native-lib) sebelum app dipause/di-background:
    // kalau lagi rekam, jangan tutup stream-nya (lebih penting jaga rekaman gak
    // keputus daripada ngalah ke app lain yang minta jalur audio exclusive).
    bool isRecording() const { return recording_.load(); }

    void loadSample(int preset, int pad, const int16_t* pcm, int numFrames, int channels);
    // Buang sample yang lagi kepasang di 1 pad (preset+pad) - dipakai pas load
    // preset baru yang gak isi penuh 12 pad, biar pad yang gak ada di preset baru
    // gak nyisain suara dari preset SEBELUMNYA (lihat PresetStorage.clearPreset).
    void clearPad(int preset, int pad);
    void trigger(int preset, int pad);

    // Player lagu (musik latar terpisah dari pad, satu track mono/stereo yang
    // dimix bareng suara pad). PCM yang masuk ke sini diasumsikan udah 48kHz
    // (resample-nya dilakuin di sisi Kotlin, sama kayak sample pad).
    // Return false kalau gagal (mis. alokasi memori gagal buat lagu yang kepanjangan/berat) -
    // di kasus itu track lama (kalau ada) dibiarin apa adanya, BUKAN crash aplikasi.
    bool loadMusic(const int16_t* pcm, int numFrames, int channels);
    void playMusic();
    void stopMusic(); // "stop" di sini = pause -> posisi kesimpen, bukan balik ke 0
    void seekMusic(double seconds);
    double getMusicPositionSeconds() const;
    double getMusicDurationSeconds() const;
    bool isMusicPlaying() const;

    // Volume global: kendangVolume buat semua voice pad, musicVolume buat track lagu.
    // Dipisah biar user bisa nurunin lagu kalau kekerasan tanpa ngurangin pukulan kendang, atau sebaliknya.
    void setKendangVolume(float v);
    void setMusicVolume(float v);

    // Volume PER PAD (per preset+pad, bukan global) - buat nyeimbangin tingkatan
    // suara antar pad (mis. pad snare kerekam lebih keras dari pad kick), independen
    // dari kendangVolume_ (yang ngalikan SEMUA pad sekaligus). Rentang sama kayak
    // kendangVolume_/musicVolume_ (0..1.5, boleh boost dikit di atas 100%).
    void setPadVolume(int preset, int pad, float v);
    float getPadVolume(int preset, int pad) const;

    // Tune PER PAD, dalam semitone (-12..+12, 0 = pitch asli sample). Diimplementasi
    // lewat resampling playback rate voice-nya (bukan pitch-shift "asli" yang
    // mempertahankan durasi) - jadi pad yang di-tune naik juga kedengeran sedikit
    // lebih pendek/cepat, dan yang di-tune turun sedikit lebih panjang/lambat -
    // ini persis kayak gimana sampler/drum machine murah (termasuk unit Kendang
    // fisik) biasa nge-tune sample, jadi karakternya familiar buat kuping kendang.
    void setPadTune(int preset, int pad, float semitones);
    float getPadTune(int preset, int pad) const;

    // Reverb PER PAD: seberapa besar porsi suara pad itu yang dikirim ke bus reverb
    // bersama (0 = kering total/no reverb, 1 = kirim penuh). Semua pad berbagi SATU
    // reverb (SimpleReverb reverb_ di bawah) buat hemat CPU - knob ini cuma ngatur
    // "send level" masing-masing pad ke bus itu, bukan bikin reverb terpisah per pad.
    void setPadReverb(int preset, int pad, float amount);
    float getPadReverb(int preset, int pad) const;

    // Choke PER PAD: kalau true (default), pukulan baru di pad yang sama sementara
    // pukulan sebelumnya masih bunyi bakal "motong" (reuse voice yang sama, restart
    // dari 0) - ini yang bikin kedengeran "tet" kalau sample-nya panjang/masih
    // nyisa ekor pas dipotong paksa. Sebagian pad (mis. cymbal panjang/ekor open
    // hihat) justru gak mau dipotong biar lebih natural, jadi ini bisa dimatiin
    // per pad - kalau false, pukulan baru cari voice lain (nganggur/round-robin)
    // dan pukulan lama dibiarin nyelesain sendiri (numpuk/overlap, gak ada "tet").
    void setPadChoke(int preset, int pad, bool enabled);
    bool getPadChoke(int preset, int pad) const;

    // Choke ANTAR PAD ("choke group" fleksibel, beda dari setPadChoke di atas yang
    // motong pukulan lama-baru DI PAD YANG SAMA): pad ini bisa diatur supaya
    // OTOMATIS BERHENTI kalau salah satu pad LAIN yang dicentang user (lewat
    // dialog "Pengaturan") dipukul - dipakai buat simulasi hi-hat closed/open,
    // atau kombinasi kendang manapun yang gak boleh numpuk bunyi bareng.
    // crossChokeEnabled_: switch master on/off per (preset,pad) - kalau false,
    // mask-nya diabaikan (tapi tetep kesimpen, gak ilang checklist-nya).
    // crossChokeMask_: bitmask 12-bit, bit (targetPad-1) di-set berarti pad INI
    // (pemilik) akan berhenti kalau targetPad tersebut dipukul. Beda dari
    // setPadChoke: pengaturan ini SEPENUHNYA SATU ARAH & independen per pad -
    // pad A bisa disetel berhenti kalau B dipukul TANPA otomatis bikin B
    // berhenti kalau A dipukul, jadi lebih fleksibel dari sekadar grup simetris.
    void setPadCrossChokeEnabled(int preset, int pad, bool enabled);
    bool getPadCrossChokeEnabled(int preset, int pad) const;
    void setPadCrossChokeMask(int preset, int pad, int mask);
    int getPadCrossChokeMask(int preset, int pad) const;

    // Loop PER PAD (gaya DTX M12 asli): kalau true, sekali ketuk pad-nya bakal
    // muter sample itu berulang TANPA JEDA (posisi baca wrap balik ke 0 pas
    // sampai ujung, di FRAME YANG SAMA - lihat onAudioReady) sampai pad yang
    // sama diketuk lagi buat berhenti. Beda dari choke (yang motong pukulan
    // LAMA pas ada pukulan BARU) - loop bikin SATU ketukan terus muter sendiri,
    // dan ketukan berikutnya di pad itu fungsinya STOP, bukan retrigger.
    // Default false (perilaku lama, sekali ketuk sekali bunyi lalu abis).
    void setPadLoop(int preset, int pad, bool enabled);
    bool getPadLoop(int preset, int pad) const;

    // Tempo/kecepatan LIVE loop PER PAD (preset+pad), rentang 0.5..2.0, default 1.0
    // (kecepatan/pitch asli sample). BEDA PENTING dari setPadTune(): Tune itu setelan
    // yang di-SNAPSHOT ke voice cuma pas ketukan BARU dimulai (lihat catatan di
    // Voice::pitchRatio) - biar geser slider Tune di dialog Pengaturan gak bikin
    // suara yang LAGI JALAN "melompat" nilainya. Tempo loop di sini justru
    // SEBALIKNYA - sengaja dibaca ULANG tiap callback oleh voice yang lagi loop
    // (lihat onAudioReady), jadi geser slidernya kedengeran berubah LANGSUNG di
    // tengah loop yang lagi muter (gaya turntable/pitch-bend DTX M12 asli), bukan
    // nunggu ketukan berikutnya. Otomatis di-reset balik ke 1.0 tiap kali sebuah
    // loop BARU dimulai (lihat onAudioReady) - jadi ini kontrol "performa langsung"
    // buat loop yang SEDANG jalan, bukan setelan permanen yang nempel di pad kayak
    // Tune/Volume/Reverb (makanya sengaja TIDAK ada storage/persist-nya di Kotlin).
    void setPadLoopTempo(int preset, int pad, float rate);
    float getPadLoopTempo(int preset, int pad) const;

    // True kalau pad ini SAAT INI punya voice yang lagi aktif dalam mode loop -
    // dipakai UI (lewat polling berkala dari Kotlin) buat tau kapan harus nampilin
    // slider tempo LANGSUNG di pad-nya sendiri (lihat Pad()/PadGrid di MainActivity)
    // dan kapan harus disembunyikan lagi (begitu loop-nya berhenti/di-stop).
    bool isPadLooping(int preset, int pad) const;

    // Rekam mixdown pad (bukan mic!) -> nangkep persis apa yang keluar dari
    // onAudioReady setiap kali sebuah pad dibunyiin, jadi hasil rekaman sama
    // persis kayak yang kedengeran di speaker/headphone.
    void startRecording();
    std::vector<int16_t> stopRecording(); // stop + ambil semua sample yang kerekam (interleaved stereo)

    // Ambil (dan kosongkan) PCM yang kerekam SEJAUH INI, TANPA menghentikan
    // recording_ (beda dari stopRecording() di atas, yang matiin recording_ juga).
    // Dipakai fitur rekam layar (ScreenRecordService) buat nyicil narik PCM tiap
    // ~100-200ms selagi video encoder & VirtualDisplay jalan bareng di background,
    // biar audio bisa langsung di-encode & di-mux ke MP4 sepanjang recording
    // berjalan - bukan nunggu recording kelar dulu (itu cara kerja tombol REC
    // audio-only yang lama, tetap dipertahankan apa adanya lewat stopRecording()).
    // Sumbernya SAMA PERSIS kayak stopRecording(): mixdown pad+musik dari
    // onAudioReady, bukan capture eksternal apa pun - jadi jalur audio exclusive
    // (Oboe stream) tidak pernah disentuh/diganggu oleh fitur ini sama sekali.
    // Aman dipanggil berkali-kali dari thread biasa (bukan audio callback) kapan
    // saja selama recording_ true; kalau dipanggil pas recording_ sudah false
    // (mis. race tipis pas stop), cuma balikin vector kosong.
    std::vector<int16_t> drainRecordBuffer();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;

    // Dipanggil Oboe di audio thread pas stream mau ketutup gara-gara error
    // (mis. didisconnect paksa sama sistem karena ada yang mulai nge-capture
    // audio internal app, seperti screen recorder yang include suara app).
    void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;

    // Dipanggil Oboe setelah stream lama beneran ketutup. Di sinilah kita
    // reopen stream baru di thread terpisah biar audio thread asli gak
    // keblokir dan playback bisa pulih secepat mungkin tanpa lag menumpuk.
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    // Dinaikin dari 24 -> 32: pas banyak pad ramai ditekan BARENGAN (bukan cuma 1
    // pad di-roll), 24 slot lebih gampang penuh -> trigger baru kepaksa "nyuri"
    // slot yang lagi kepake pad lain (voices_ round-robin di onAudioReady). Nyuri
    // sendiri gak bikin bisu (assignedThisBatch di onAudioReady udah jamin tiap
    // trigger tetap dapet slot), tapi makin sering kejadian, makin sering juga
    // pad LAIN yang kepotong duluan gara-gara slotnya direbut -> kedengeran makin
    // "rame tapi berantakan". Kasih headroom lebih (32) biar situasi "semua pad
    // ramai dipukul" jarang sampe harus nyuri slot pad lain sama sekali.
    static constexpr int kMaxVoices = 32;
    static constexpr int kMaxPresets = 8;
    // Dinaikin dari 12 -> 16 buat nampung fitur "Pad Tambahan" (4 pad ekstra,
    // lihat ExtraPadState.kt & PresetStorage.MAX_PAD di sisi Kotlin) - semua
    // array di bawah ini ukurannya kMaxPresets*kMaxPads, dan indexOf() bounds-
    // check-nya juga pakai konstanta ini, jadi cukup naikin di satu tempat ini
    // buat nambah slot pad, gak ada array/loop lain di file ini yang perlu
    // disentuh manual. crossChokeMask_ (di bawah, std::atomic<uint16_t>) juga
    // masih aman: bit tertinggi yang dipakai cuma bit 15 (pad ke-16), pas persis
    // muat di 16 bit uint16_t.
    static constexpr int kMaxPads = 16;

    struct Voice {
        const SampleBuffer* buffer = nullptr;
        // Posisi baca dalam FRAME, pecahan (bukan int) - dibutuhkan karena tune/pitch
        // diimplementasi lewat resampling (increment per frame = pitchRatio, bukan
        // selalu 1.0), jadi posisinya jarang pas jatuh di frame bulat. Nilai antar dua
        // frame terdekat di-interpolasi linear di onAudioReady.
        double positionF = 0.0;
        std::atomic<bool> active{false};
        // Volume/pitchRatio/reverbSend pad ini di-SNAPSHOT persis pas voice-nya
        // di-assign (lihat onAudioReady) - bukan dibaca ulang dari padVolume_/
        // padTune_/padReverb_ tiap frame - biar geser slider pas suara lagi jalan
        // gak bikin nilainya "melompat" di tengah pukulan, cuma ngaruh ke ketukan
        // BERIKUTNYA.
        float volume = 1.0f;
        float pitchRatio = 1.0f; // 1.0 = pitch asli; dari semitone lewat 2^(semitone/12)
        float reverbSend = 0.0f; // 0..1, porsi sinyal voice ini yang dikirim ke reverb_
        // True kalau voice ini lagi dalam mode loop (lihat setPadLoop) - dibaca &
        // ditulis CUMA di audio thread (di dalam onAudioReady), jadi sengaja bukan
        // std::atomic: satu-satunya "writer" & "reader"-nya sama-sama audio thread,
        // gak ada thread lain yang pernah nyentuh field ini langsung (UI thread cuma
        // ngubah padLoop_[idx] di bawah, yang DIBACA lagi oleh audio thread pas
        // narik antrean trigger, bukan nulis ke voice manapun).
        bool loop = false;
        // Index (preset,pad) pemilik voice ini SELAMA voice ini aktif - dipakai buat
        // nyari tempo LIVE loop-nya (padLoopTempo_[padIndex]) tiap callback di
        // onAudioReady. Sama pola kayak field `loop` di atas: CUMA pernah ditulis &
        // dibaca oleh audio thread (di-set pas voice di-assign, dibaca pas mixing di
        // callback yang sama/berikutnya), jadi sengaja bukan std::atomic. -1 = belum
        // pernah dipakai/gak valid.
        int padIndex = -1;

        // --- FADE-OUT PAS DIPOTONG (anti "tet") ---
        // Kalau voice ini lagi "dipotong" (choke sesama pad, choke antar pad, atau
        // stop loop), JANGAN langsung dimatiin (active=false) - itu bikin gelombang
        // suara loncat mendadak ke nol di tengah jalan dan kedengeran "tet"/klik.
        // Sebagai gantinya voice ditandai fading=true: dia TETAP dirender, tapi
        // gain-nya diturunin mulus dari 1 -> 0 selama kChokeFadeMs, baru setelah itu
        // active=false. fadeProgress 0..1 (naik sebesar fadeInc per frame). Sama pola
        // kayak loop/padIndex di atas: CUMA dibaca & ditulis di audio thread, jadi
        // sengaja bukan std::atomic.
        bool fading = false;
        float fadeProgress = 0.0f;
        float fadeInc = 0.0f;
    };

    // Lama fade-out (milidetik) pas sebuah voice dipotong. Terlalu pendek (<3ms) =
    // masih kedengeran "tet" (apalagi buat suara kendang yang bass-nya periodenya
    // ~10ms); terlalu panjang (>40ms) = potongannya kerasa "mendem/kelamaan".
    // 12ms itu titik aman: gak kedengeran klik, tapi tetap terasa "kepotong tegas".
    // Naikin kalau masih kedengeran "tet" di sample bass; turunin kalau kerasa
    // kurang tegas.
    static constexpr float kChokeFadeMs = 12.0f;

    // Mulai fade-out di voice v (aman dipanggil berkali-kali: kalau udah fading
    // dibiarin lanjut dari progress-nya, gak di-restart biar gak "nyendat"). loop
    // dimatiin biar voice yang lagi loop gak nge-wrap terus & gak ke-hitung lagi
    // sebagai "lagi looping" oleh isPadLooping()/pengecekan stop-toggle.
    static void beginFadeOut(Voice &v, int fadeFrames) {
        if (v.fading) return;
        v.fading = true;
        v.fadeProgress = 0.0f;
        v.fadeInc = 1.0f / (float)(fadeFrames > 0 ? fadeFrames : 1);
        v.loop = false;
    }

    std::array<Voice, kMaxVoices> voices_;
    std::array<SampleBuffer, kMaxPresets * kMaxPads> samples_;
    // mutable: isPadLooping() (const method, dipanggil dari thread polling UI di
    // Kotlin - lihat catatan panjang di definisinya pada PadEngine.cpp) perlu ngunci
    // mutex ini juga walau statusnya cuma "baca", biar sinkron sama tulisan audio
    // thread ke voices_[].loop/.buffer yang BUKAN atomic.
    mutable std::mutex samplesMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int> nextVoice_{0};

    // Flag lock-free per slot sample, di-set oleh loadSample() (release) dan dibaca
    // oleh trigger() (acquire) TANPA samplesMutex_ -> trigger() jadi bisa cepet nolak
    // pad yang belum ada suaranya tanpa perlu ngantri lock sama sekali.
    std::array<std::atomic<bool>, kMaxPresets * kMaxPads> sampleLoaded_{};

    // Antrean pemicu pad, lock-free (ring buffer pakai atomic index). trigger() DULU
    // langsung ambil samplesMutex_ buat klaim voice -> pas dipukul super cepat & rame
    // (banyak pad + 1 pad diketuk berulang), lock itu sering bentrok pas persis audio
    // callback lagi try_lock buat nge-mix, dan begitu bentrok audio callback-nya
    // nyerah & skip 1 buffer (senyap sekejap) -> itu sebabnya kedengeran kayak
    // pukulan "ketelan"/tersendat padahal ketukannya udah bener. Sekarang trigger()
    // cuma nulis ke antrean ini (gak pernah nunggu lock sama sekali, wait-free), dan
    // audio thread sendiri yang narik + klaim voice-nya pas udah pegang samplesMutex_
    // buat mixing -> gak ada lagi kontensi antara UI thread yang mukul pad cepet-cepet
    // sama audio thread real-time.
    static constexpr int kTriggerQueueSize = 256;
    std::array<std::atomic<int>, kTriggerQueueSize> triggerQueue_{};
    std::atomic<int> triggerWriteIdx_{0};
    std::atomic<int> triggerReadIdx_{0};
    std::atomic<bool> restarting_{false};
    std::thread restartThread_;
    std::atomic<float> kendangVolume_{1.0f};

    // Volume per (preset, pad) - indexOf() yang sama kayak samples_/sampleLoaded_,
    // jadi tiap pad di tiap preset punya level suaranya sendiri-sendiri. Di-init ke
    // 1.0 (100%, gak ngubah apa-apa) buat semua slot lewat konstruktor, BUKAN default
    // member init, karena std::atomic array gak auto-init nilainya ke 1.0.
    std::array<std::atomic<float>, kMaxPresets * kMaxPads> padVolume_;

    // Tune per (preset, pad) dalam semitone, sama pola indexOf() kayak padVolume_.
    // Di-init ke 0.0f (pitch asli, gak ngubah apa-apa) di konstruktor - std::atomic
    // array juga gak auto-init ke nilai tertentu selain zero, tapi kebetulan 0.0f
    // memang default yang kita mau di sini jadi gak perlu loop init eksplisit.
    std::array<std::atomic<float>, kMaxPresets * kMaxPads> padTune_{};

    // Reverb send per (preset, pad), 0..1. Default 0 (kering/no reverb) - juga
    // kebetulan sama kayak zero-init bawaan std::atomic array, jadi aman tanpa
    // loop init eksplisit di konstruktor.
    std::array<std::atomic<float>, kMaxPresets * kMaxPads> padReverb_{};

    // Choke per (preset, pad) - true = pukulan baru motong pukulan lama di pad yang
    // sama (lihat setPadChoke di atas). Default TRUE (perilaku lama/profesional buat
    // kebanyakan pad), tapi std::atomic<bool> array cuma zero-init (false) secara
    // bawaan, jadi HARUS di-set manual ke true di konstruktor (sama pola kayak
    // padVolume_).
    std::array<std::atomic<bool>, kMaxPresets * kMaxPads> padChoke_;

    // Choke ANTAR PAD - lihat setPadCrossChokeEnabled/setPadCrossChokeMask di atas.
    // Default FALSE/0 buat keduanya, sama kayak zero-init bawaan std::atomic array,
    // jadi (beda dari padChoke_ di atas) TIDAK perlu loop init manual di konstruktor.
    std::array<std::atomic<bool>, kMaxPresets * kMaxPads> crossChokeEnabled_{};
    std::array<std::atomic<uint16_t>, kMaxPresets * kMaxPads> crossChokeMask_{};

    // Loop per (preset, pad) - lihat setPadLoop di atas. Default FALSE, dan itu
    // kebetulan sama kayak zero-init bawaan std::atomic<bool> array, jadi gak
    // perlu loop init manual di konstruktor (beda dari padChoke_ di atas).
    std::array<std::atomic<bool>, kMaxPresets * kMaxPads> padLoop_{};

    // Tempo live loop per (preset, pad), 0.5..2.0, default 1.0 - lihat setPadLoopTempo
    // di atas. Di-init MANUAL ke 1.0f di konstruktor (bukan cuma andelin zero-init
    // bawaan std::atomic array), karena 0.0 di sini bakal bikin loop yang lagi jalan
    // macet total (posisi baca gak maju sama sekali) kalau ada query sebelum loop
    // manapun pernah dimulai - beda dari padTune_/padReverb_ yang kebetulan 0.0
    // memang default yang dimau.
    std::array<std::atomic<float>, kMaxPresets * kMaxPads> padLoopTempo_;

    // Reverb bersama (SATU instance dipakai semua pad, lihat SimpleReverb di atas)
    // + bus mono tempat semua voice numpahin porsi "send"-nya sebelum diproses.
    // Dialokasi sekali di konstruktor (prepare()), buffer bus-nya juga ukuran
    // TETAP (bukan std::vector yang di-resize tiap callback) biar onAudioReady
    // gak pernah alokasi memori di real-time thread. 8192 frame jauh lebih dari
    // cukup buat ukuran buffer callback Oboe yang biasa (umumnya < 1024 frame).
    static constexpr int kMaxReverbBusFrames = 8192;
    SimpleReverb reverb_;
    std::array<float, kMaxReverbBusFrames> reverbBus_{};

    // Track musik: selalu disimpen interleaved STEREO (mono di-duplikat pas load)
    // biar mixing di audio callback gampang & gak perlu cek channel count tiap frame.
    struct MusicTrack {
        // int16 (bukan float): separo lebih hemat memori buat lagu yang panjang
        // (lagu 5 menit stereo ~ 57MB sbg int16, vs ~115MB kalau float). Ini yang
        // bikin loadMusic() gampang OOM di device dgn heap kecil kalau filenya besar.
        std::vector<int16_t> data; // interleaved stereo
        int totalFrames = 0;
    };
    static constexpr int kMusicSampleRate = 48000;
    MusicTrack music_;
    std::mutex musicMutex_;             // dipisah dari samplesMutex_ biar load lagu (bisa gede & lama) gak nge-block trigger pad
    std::atomic<int> musicTotalFrames_{0}; // duplikat lock-free dari music_.totalFrames, buat getDuration() tanpa perlu lock
    std::atomic<bool> musicPlaying_{false};
    std::atomic<int64_t> musicPosition_{0}; // posisi baca, dalam frame
    std::atomic<float> musicVolume_{1.0f};

    // Buffer rekaman: diisi di audio thread (onAudioReady), dibaca/direset dari
    // thread lain lewat startRecording()/stopRecording(). Gak ada batas durasi -
    // rekaman jalan terus sampe user tap stop (RAM kepake ~11.5MB per menit,
    // stereo 48kHz 16-bit -> jadi tanggung jawab user buat gak lupa stop).
    //
    // Sengaja deque, BUKAN vector: vector kalau kapasitasnya abis harus realloc +
    // copy SEMUA data lama ke memori baru. Buat rekaman panjang itu bisa jadi copy
    // puluhan MB, dan ini kejadian di dalam onAudioReady (real-time thread) -> bikin
    // callback telat, audio driver underrun, dan yang kedengeran adalah "buffer
    // terakhir keulang cepet" (persis kaya pad nempel/ngeloop). Deque nambah data
    // per-chunk tanpa pernah mindahin data lama, jadi push_back-nya konsisten murah.
    std::atomic<bool> recording_{false};
    std::mutex recordMutex_;
    std::deque<int16_t> recordBuffer_;

    int indexOf(int preset, int pad) const;
    bool openStream(); // logic buka stream, dipisah dari start() biar bisa dipanggil ulang
};
