#include "PadEngine.h"
#include <algorithm>
#include <cmath>
#include <chrono>

PadEngine::PadEngine() {
    // std::atomic gak bisa di-brace-init ke nilai tertentu langsung di dalam
    // std::array (cuma zero-init) -> set manual ke 1.0f (volume penuh, gak ngubah
    // apa-apa) di sini, sekali pas engine dibikin.
    for (auto &v : padVolume_) v.store(1.0f);
    // padTune_ (0 semitone) dan padReverb_ (0 = kering) sudah bener lewat zero-init
    // bawaan std::array<std::atomic<float>>, jadi gak perlu loop init di sini.

    // padChoke_ defaultnya AKTIF (true) buat semua pad - beda dari padTune_/padReverb_
    // di atas, di sini zero-init (false) itu JUSTRU KEBALIKAN dari default yang kita
    // mau, jadi wajib di-set manual satu-satu di sini.
    for (auto &c : padChoke_) c.store(true);

    // padLoopTempo_ defaultnya 1.0 (kecepatan/pitch asli, gak ngubah apa-apa) buat
    // semua pad - sama alasan kenapa padChoke_ di atas harus di-set manual: zero-init
    // bawaan std::atomic array (0.0) JUSTRU nilai yang salah/berbahaya di sini (lihat
    // catatan di deklarasi padLoopTempo_ pada PadEngine.h).
    for (auto &t : padLoopTempo_) t.store(1.0f);

    reverb_.prepare(48000); // sample rate stream selalu 48000, lihat openStream()
}

void SimpleReverb::prepare(int sampleRate) {
    // Panjang delay comb/allpass klasik ala Freeverb (Jezar at Dreampoint, public
    // domain), di-tune awalnya buat 44100Hz -> diskalakan ke sampleRate aktual biar
    // karakter reverbnya konsisten walau suatu saat sample rate stream berubah.
    // Nilai comb kanal R sengaja digeser dikit (+23 sample) dari kanal L (skema umum
    // Freeverb "stereo spread") biar hasilnya gak mono-terdengar/lebih lebar.
    const float scale = (float)sampleRate / 44100.0f;
    const int combTuningL[kNumCombs] = {1116, 1188, 1277, 1356};
    const int combTuningR[kNumCombs] = {1116 + 23, 1188 + 23, 1277 + 23, 1356 + 23};
    const int allpassTuningL[kNumAllpasses] = {556, 441};
    const int allpassTuningR[kNumAllpasses] = {556 + 23, 441 + 23};

    for (int i = 0; i < kNumCombs; i++) {
        combsL_[i].setSize(std::max(1, (int)(combTuningL[i] * scale)));
        combsR_[i].setSize(std::max(1, (int)(combTuningR[i] * scale)));
        combsL_[i].feedback = combsR_[i].feedback = 0.5f;
        combsL_[i].damp1 = combsR_[i].damp1 = 0.2f;
        combsL_[i].damp2 = combsR_[i].damp2 = 0.8f;
    }
    for (int i = 0; i < kNumAllpasses; i++) {
        allpassL_[i].setSize(std::max(1, (int)(allpassTuningL[i] * scale)));
        allpassR_[i].setSize(std::max(1, (int)(allpassTuningR[i] * scale)));
        allpassL_[i].feedback = allpassR_[i].feedback = 0.5f;
    }
}

void SimpleReverb::process(float input, float &outL, float &outR) {
    // Gain kecil sebelum masuk comb (sama spirit-nya kayak "fixedgain" di Freeverb
    // asli) - tanpa ini, 4 comb dengan feedback 0.5 yang dijumlahin bisa jauh lebih
    // keras dari sinyal aslinya (tiap comb kira-kira menggandakan energi input lewat
    // feedback loop-nya), jadi hasil akhirnya kedengeran meledak/pecah alih-alih
    // ekor reverb yang halus.
    const float in = input * 0.15f;
    float sumL = 0.0f, sumR = 0.0f;
    for (int i = 0; i < kNumCombs; i++) {
        sumL += combsL_[i].process(in);
        sumR += combsR_[i].process(in);
    }
    for (int i = 0; i < kNumAllpasses; i++) {
        sumL = allpassL_[i].process(sumL);
        sumR = allpassR_[i].process(sumR);
    }
    outL = sumL;
    outR = sumR;
}

bool PadEngine::loadMusic(const int16_t* pcm, int numFrames, int channels) {
    if (pcm == nullptr || numFrames <= 0) return false;

    // Lagu panjang/berat butuh alokasi vector yang gede (lihat catatan di MusicTrack
    // pada PadEngine.h). Kalau device gak punya memori cukup, std::vector::resize()
    // ngelempar std::bad_alloc/std::length_error -> kalau gak ditangkep di sini,
    // exception itu bakal nembus JNI boundary dan bikin seluruh aplikasi force close.
    // Makanya di-try/catch: gagal alokasi cuma bikin fungsi ini return false (lagu
    // lama yang lagi keputer, kalau ada, dibiarin gak keganggu), bukan crash total.
    try {
        MusicTrack track;
        track.totalFrames = numFrames;
        track.data.resize((size_t)numFrames * 2); // selalu disimpen stereo, mono di-duplikat ke L/R
        for (int i = 0; i < numFrames; i++) {
            int16_t l, r;
            if (channels <= 1) {
                l = r = pcm[i];
            } else {
                l = pcm[(size_t)i * channels + 0];
                r = pcm[(size_t)i * channels + 1];
            }
            track.data[(size_t)i * 2 + 0] = l;
            track.data[(size_t)i * 2 + 1] = r;
        }

        std::lock_guard<std::mutex> lock(musicMutex_);
        music_ = std::move(track);
        musicTotalFrames_.store(music_.totalFrames);
        musicPosition_.store(0);
        musicPlaying_.store(false);
        return true;
    } catch (const std::exception&) {
        return false;
    } catch (...) {
        return false;
    }
}

void PadEngine::playMusic() {
    // Kalau lagu udah abis keputer sampe habis (render loop di bawah auto-stop
    // begitu posisi kepentok totalFrames, tapi posisinya sendiri gak direset),
    // posisi bacanya masih nyangkut persis di ujung akhir. Tanpa reset ini, klik
    // "play" lagi cuma nyalain flag musicPlaying_ doang -> frame render PERTAMA
    // langsung ketemu pos >= totalFrames lagi dan auto-stop instan, jadi keliatan
    // kayak macet/gak mau muter sama sekali. Fix-nya: begitu play dipanggil pas
    // posisi udah di ujung (atau lewat, buat jaga-jaga), balikin ke 0 dulu -> lagu
    // otomatis keputer ulang dari awal, konsisten sama ekspektasi tombol play.
    int64_t total = musicTotalFrames_.load();
    if (total > 0 && musicPosition_.load() >= total) {
        musicPosition_.store(0);
    }
    musicPlaying_.store(true);
}

void PadEngine::stopMusic() {
    musicPlaying_.store(false);
}

void PadEngine::seekMusic(double seconds) {
    int64_t frame = (int64_t)(seconds * kMusicSampleRate);
    int64_t total = musicTotalFrames_.load();
    if (frame < 0) frame = 0;
    if (frame > total) frame = total;
    musicPosition_.store(frame);
}

double PadEngine::getMusicPositionSeconds() const {
    return (double)musicPosition_.load() / kMusicSampleRate;
}

double PadEngine::getMusicDurationSeconds() const {
    return (double)musicTotalFrames_.load() / kMusicSampleRate;
}

bool PadEngine::isMusicPlaying() const {
    return musicPlaying_.load();
}

void PadEngine::setKendangVolume(float v) {
    kendangVolume_.store(v);
}

void PadEngine::setMusicVolume(float v) {
    musicVolume_.store(v);
}

void PadEngine::setPadVolume(int preset, int pad, float v) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.5f) v = 1.5f; // konsisten sama batas atas kendangVolume_/musicVolume_
    padVolume_[idx].store(v);
}

float PadEngine::getPadVolume(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return 1.0f;
    return padVolume_[idx].load();
}

void PadEngine::setPadTune(int preset, int pad, float semitones) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    if (semitones < -12.0f) semitones = -12.0f;
    if (semitones > 12.0f) semitones = 12.0f;
    padTune_[idx].store(semitones);
}

float PadEngine::getPadTune(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return 0.0f;
    return padTune_[idx].load();
}

void PadEngine::setPadReverb(int preset, int pad, float amount) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    if (amount < 0.0f) amount = 0.0f;
    if (amount > 1.0f) amount = 1.0f;
    padReverb_[idx].store(amount);
}

float PadEngine::getPadReverb(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return 0.0f;
    return padReverb_[idx].load();
}

void PadEngine::setPadChoke(int preset, int pad, bool enabled) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    padChoke_[idx].store(enabled);
}

bool PadEngine::getPadChoke(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return true; // default aktif kalau idx invalid
    return padChoke_[idx].load();
}

void PadEngine::setPadCrossChokeEnabled(int preset, int pad, bool enabled) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    crossChokeEnabled_[idx].store(enabled);
}

bool PadEngine::getPadCrossChokeEnabled(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return false;
    return crossChokeEnabled_[idx].load();
}

void PadEngine::setPadCrossChokeMask(int preset, int pad, int mask) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    crossChokeMask_[idx].store((uint16_t)(mask & 0xFFFF));
}

int PadEngine::getPadCrossChokeMask(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return 0;
    return (int)crossChokeMask_[idx].load();
}

void PadEngine::setPadLoop(int preset, int pad, bool enabled) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    padLoop_[idx].store(enabled);
    // SENGAJA gak nyentuh voice yang lagi aktif looping di sini walau enabled
    // di-set false: kalau lagi muter, ketukan berikutnya di pad itu tetap yang
    // bakal menghentikannya (lihat pengecekan voice.loop di onAudioReady, yang
    // gak bergantung ke padLoop_ terkini) - biar user yang matiin toggle ini
    // pas lagi muter gak kaget suaranya keputus mendadak tanpa diketuk.
}

bool PadEngine::getPadLoop(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return false;
    return padLoop_[idx].load();
}

void PadEngine::setPadLoopTempo(int preset, int pad, float rate) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    if (rate < 0.5f) rate = 0.5f;
    if (rate > 2.0f) rate = 2.0f;
    padLoopTempo_[idx].store(rate);
}

float PadEngine::getPadLoopTempo(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return 1.0f;
    return padLoopTempo_[idx].load();
}

bool PadEngine::isPadLooping(int preset, int pad) const {
    int idx = indexOf(preset, pad);
    if (idx < 0) return false;
    // v.loop & v.buffer BUKAN atomic (lihat catatan di Voice pada PadEngine.h - cuma
    // dijamin aman kalau satu-satunya pembaca/penulis adalah audio thread). Fungsi ini
    // dipanggil dari thread LAIN (loop polling UI di Kotlin), jadi WAJIB ambil
    // samplesMutex_ dulu - mutex yang sama persis yang dipegang audio thread selama
    // SELURUH onAudioReady (termasuk pas nulis v.loop/v.buffer pas assign voice DAN
    // pas baca v.loop di loop mixing) - biar baca di sini gak pernah nyilang sama
    // tulisan audio thread di tengah jalan. Dipanggil cuma tiap ~100ms dari UI (bukan
    // per-frame), jadi lock_guard biasa (nunggu, bukan try_lock) aman - nunggunya
    // paling lama seukuran 1 callback audio (mikrodetik), gak kerasa dari UI thread.
    std::lock_guard<std::mutex> lock(samplesMutex_);
    for (const auto &v : voices_) {
        if (v.active.load() && v.loop && v.buffer == &samples_[idx]) return true;
    }
    return false;
}

void PadEngine::startRecording() {
    std::lock_guard<std::mutex> lock(recordMutex_);
    recordBuffer_.clear();
    recording_.store(true);
}

std::vector<int16_t> PadEngine::stopRecording() {
    recording_.store(false);
    // FIX "tet tet tet": dulu di sini di-copy LANGSUNG dari recordBuffer_ ke
    // vector di dalam lock. std::deque::swap() itu O(1) (cuma tuker pointer
    // internal), sedangkan copy-ke-vector O(n) - buat rekaman panjang beda
    // jauh berapa lama recordMutex_ dipegang. Sekarang critical section cuma
    // swap; copy ke vector dikerjain DI LUAR lock, jadi onAudioReady (real-time
    // thread, lihat di bawah) gak pernah nunggu lama buat dapet lock ini.
    std::deque<int16_t> local;
    {
        std::lock_guard<std::mutex> lock(recordMutex_);
        std::swap(local, recordBuffer_);
    }
    std::vector<int16_t> result(local.begin(), local.end());
    return result;
}

std::vector<int16_t> PadEngine::drainRecordBuffer() {
    // Sengaja TIDAK menyentuh recording_ di sini (beda dari stopRecording()) -
    // onAudioReady harus tetap terus nulis ke recordBuffer_ setelah fungsi ini
    // selesai, karena recording (screen record) masih berlangsung.
    //
    // FIX "tet tet tet" (suara rekaman screen record kedengeran kretek-kretek
    // putus-putus): sama kayak stopRecording() di atas, swap() O(1) dulu di
    // dalam lock, baru convert ke vector DI LUAR lock - lihat juga perubahan
    // di onAudioReady yang sekarang gak lagi try_lock+skip.
    std::deque<int16_t> local;
    {
        std::lock_guard<std::mutex> lock(recordMutex_);
        std::swap(local, recordBuffer_);
    }
    std::vector<int16_t> result(local.begin(), local.end());
    return result;
}

int PadEngine::indexOf(int preset, int pad) const {
    if (preset < 1 || preset > kMaxPresets || pad < 1 || pad > kMaxPads) return -1;
    return (preset - 1) * kMaxPads + (pad - 1);
}

bool PadEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(48000)
           ->setCallback(this);

    // Coba sekali lagi (dengan jeda singkat) sebelum nyerah ke mode Shared.
    // Kenapa: kalau app baru aja ditutup terus dibuka lagi cepet, jalur audio
    // Exclusive dari sesi sebelumnya kadang belum sempurna dilepas sama sistem
    // Android (butuh puluhan ms). Percobaan PERTAMA bisa gagal gara-gara itu,
    // padahal beberapa saat kemudian jalurnya udah kosong lagi. Tanpa retry ini,
    // kegagalan sesaat itu langsung bikin turun ke mode Shared (lewat mixer
    // software biasa) -> itu sebabnya sebelumnya kerasa beda tiap buka ulang:
    // volume dikit lebih keras + delay dikit lebih kerasa, padahal cuma
    // beda jalur audio doang, bukan beda volume yang di-set aplikasi.
    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK) {
        // fallback kalau device gak support exclusive low-latency stream
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
        if (result != oboe::Result::OK) return false;
    }
    stream_->requestStart();
    return true;
}

bool PadEngine::start() {
    if (stream_) return true; // stream udah kebuka -> gak perlu buka lagi (idempoten,
                               // aman dipanggil berkali-kali dari onResume tanpa efek samping)
    return openStream();
}

void PadEngine::stop() {
    // Kalau ada proses reconnect yang lagi jalan, tunggu beres dulu biar
    // gak balapan sama stream_ yang lagi kita reset di sini.
    if (restartThread_.joinable()) {
        restartThread_.join();
    }
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

void PadEngine::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) {
    // Ini yang biasa kepanggil pas mulai/berhenti screen recording: sistem
    // narik paksa stream low-latency eksklusif buat nge-route audio ke
    // capture pipeline si recorder. Gak perlu ngapa-ngapain di sini selain
    // logging kalau perlu, actual recovery-nya ada di onErrorAfterClose.
    (void)stream;
    (void)error;
}

void PadEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    (void)stream;
    (void)error;

    // Hindari numpuk banyak thread restart kalau errornya beruntun.
    bool expected = false;
    if (!restarting_.compare_exchange_strong(expected, true)) return;

    // Buka stream baru di thread terpisah (bukan di audio callback thread)
    // biar gak nge-block dan recovery-nya cepet -- ini yang bikin delay
    // ilang begitu sistem selesai reroute audio buat screen recording.
    if (restartThread_.joinable()) restartThread_.join();
    restartThread_ = std::thread([this]() {
        // stream_ lama udah invalid & closed oleh Oboe sebelum callback ini,
        // jadi aman langsung buka yang baru menggantikannya.
        openStream();
        restarting_.store(false);
    });
}

void PadEngine::loadSample(int preset, int pad, const int16_t* pcm, int numFrames, int channels) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;

    SampleBuffer buf;
    buf.channels = channels;
    buf.data.resize((size_t)numFrames * channels);
    for (size_t i = 0; i < buf.data.size(); i++) {
        buf.data[i] = pcm[i] / 32768.0f;
    }

    // Cari titik loncat-balik loop: scan MUNDUR dari ekor sample nyari frame
    // TERAKHIR yang beneran ada bunyinya (di atas ambang hening), lalu kasih
    // sedikit padding (8ms) biar ekor natural decay/reverb transient terakhir
    // gak kepotong mepet pas loop lompat balik. Sample DATA-nya (buf.data di
    // atas) TIDAK diubah/dipotong sama sekali - ini murni nentuin di frame
    // keberapa WRAP loop terjadi (lihat pemakaiannya di onAudioReady), jadi
    // mode sekali-ketuk (non-loop) tetap muter penuh sample aslinya sampai
    // benar-benar habis, ori 100%, gak kepengaruh field ini sama sekali.
    const float kSilenceThreshold = 0.012f; // ~= 400/32768 di skala int16 lama
    const int kEdgePaddingFrames = (int)(48000 * 0.008); // sample udah 48kHz dari Kotlin
    int lastLoud = -1;
    for (int f = numFrames - 1; f >= 0; f--) {
        bool loud = false;
        size_t base = (size_t)f * channels;
        for (int ch = 0; ch < channels; ch++) {
            if (std::abs(buf.data[base + ch]) > kSilenceThreshold) { loud = true; break; }
        }
        if (loud) { lastLoud = f; break; }
    }
    // Semua frame di bawah ambang (sample hening total / rusak) -> jangan
    // diapa-apain, biarin wrap di panjang penuh seperti biasa.
    buf.loopEndFrame = (lastLoud < 0) ? numFrames : std::min(numFrames, lastLoud + 1 + kEdgePaddingFrames);

    std::lock_guard<std::mutex> lock(samplesMutex_);
    samples_[idx] = std::move(buf);
    // release: begitu ini kebaca true (acquire) di trigger()/audio thread, semua
    // tulisan ke samples_[idx] di atas dijamin udah kelihatan.
    sampleLoaded_[idx].store(true, std::memory_order_release);
}

void PadEngine::clearPad(int preset, int pad) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;

    // Matiin flag DULUAN (release) sebelum bongkar buffer-nya - trigger() baca
    // flag ini lock-free (acquire) tanpa samplesMutex_, jadi urutan ini yang
    // nyegah audio thread sempat kepilih trigger pad ini pas buffer-nya lagi
    // ditengah-tengah dikosongin di bawah.
    sampleLoaded_[idx].store(false, std::memory_order_release);

    std::lock_guard<std::mutex> lock(samplesMutex_);
    samples_[idx] = SampleBuffer(); // lepas memori PCM lama, balik ke kosong
}

void PadEngine::trigger(int preset, int pad) {
    int idx = indexOf(preset, pad);
    if (idx < 0) return;
    // Tolak cepet kalau emang belum ada sample di pad ini -> gak perlu nulis apa-apa
    // ke antrean. Baca lock-free, gak nunggu samplesMutex_ sama sekali.
    if (!sampleLoaded_[idx].load(std::memory_order_acquire)) return;

    // Wait-free: cuma nulis idx ke antrean, gak pernah nge-block nunggu lock apapun,
    // jadi ketukan cepet & berulang di UI thread gak akan pernah kesendat/telat nunggu
    // audio thread lagi sibuk nge-mix. Voice-nya sendiri baru diklaim belakangan oleh
    // audio thread pas narik antrean ini (lihat onAudioReady).
    //
    // PENTING soal urutan 2 baris di bawah (bug yang barusan diperbaiki): trigger()
    // cuma dipanggil dari 1 thread (UI thread Compose), jadi antrean ini single-writer
    // -> aman baca triggerWriteIdx_ dengan relaxed dulu (gak ada writer lain yang
    // bisa nyelip). Tapi PUBLISH-nya (nulis ulang triggerWriteIdx_ yang baru) HARUS
    // paling akhir, pakai release, dan HARUS setelah data pad-nya kelar ditulis ke
    // slot. Sebelumnya urutannya kebalik (index dinaikin duluan lewat fetch_add,
    // baru nulis data ke slot) -> di CPU ARM (semua HP Android), 2 store atomic ke
    // alamat BEDA kayak gini boleh "kelihatan" thread lain dalam urutan yang beda
    // dari urutan kode aslinya. Akibatnya audio thread kadang keburu liat index-nya
    // udah naik (ada ketukan baru) TAPI data pad di slot itu belom sempet ke-tulis,
    // jadi yang kebaca isi lama di slot itu (index pad lain / gak valid) -> ketukan
    // itu didiskip diam-diam. Itu sebabnya kedengeran kayak salah satu ketukan
    // "ketelan" pas mukul pad yang sama cepet berturut-turut (dobel/triple). Dengan
    // urutan yang benar (data ditulis dulu, index dipublish belakangan pakai
    // release, dibaca pake acquire di onAudioReady), audio thread dijamin selalu
    // liat data slot yang udah lengkap begitu dia liat index-nya udah naik.
    int writeIdx = triggerWriteIdx_.load(std::memory_order_relaxed);
    int slot = writeIdx % kTriggerQueueSize;
    triggerQueue_[slot].store(idx, std::memory_order_relaxed);
    triggerWriteIdx_.store(writeIdx + 1, std::memory_order_release);
}

oboe::DataCallbackResult PadEngine::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    float *out = static_cast<float*>(audioData);
    int outChannels = stream->getChannelCount();
    std::fill(out, out + (size_t)numFrames * outChannels, 0.0f);

    // try_lock, bukan lock_guard biasa: audio thread ini gak boleh sampe
    // keblokir nunggu loadSample() di thread lain. Kalau lagi kebentur (jarang -
    // cuma pas user lagi ganti sample), mending skip 1 buffer (senyap sekejap)
    // daripada nge-stall dan bikin delay ke seluruh playback (termasuk yang
    // kerekam layar). trigger() dari UI thread SUDAH GAK ikut lock ini lagi
    // (lihat trigger() - lock-free), jadi kontensi di sini sekarang cuma
    // kejadian pas loadSample(), bukan pas maen/mukul pad cepet-cepet.
    std::unique_lock<std::mutex> lock(samplesMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return oboe::DataCallbackResult::Continue;

    // Panjang fade-out (dalam frame) buat voice yang dipotong - dihitung dari sample
    // rate stream yang beneran kepakai (normalnya 48000, lihat openStream()), bukan
    // di-hardcode, biar durasinya tetap kChokeFadeMs walau device maksa rate lain.
    const int sampleRate = stream->getSampleRate() > 0 ? stream->getSampleRate() : 48000;
    const int fadeFrames = std::max(1, (int)(sampleRate * (kChokeFadeMs / 1000.0f)));

    // Tarik semua pemicu pad yang numpuk di antrean sejak buffer sebelumnya, lalu
    // klaim voice buat masing-masing di sini (di audio thread, sambil udah pegang
    // samplesMutex_ buat baca samples_ dengan aman). Logic milih voice sama persis
    // kayak sebelumnya (voice nganggur diprioritaskan, round-robin cuma fallback).
    {
        int writeSnapshot = triggerWriteIdx_.load(std::memory_order_acquire);
        int readIdx = triggerReadIdx_.load(std::memory_order_relaxed);
        // Kalau antrean sampai kepenuhan (ketukan numpuk lebih dari kTriggerQueueSize
        // dalam 1 buffer callback - praktis mustahil, tapi jaga-jaga), cuma proses
        // kTriggerQueueSize entri paling baru biar gak muter selamanya di sini.
        if (writeSnapshot - readIdx > kTriggerQueueSize) {
            readIdx = writeSnapshot - kTriggerQueueSize;
        }

        // BUG YANG BARU DIPERBAIKI: sebelum ini, kalau pad yang SAMA di-trigger 2x/3x
        // dalam SATU batch drain (dua ketukan cepat yang kebetulan jatuh dalam 1
        // periode buffer audio - beberapa milidetik), kode langsung reuse voice yang
        // SAMA berkali-kali (choke) di loop ini, SEBELUM loop mixing di bawah sempat
        // jalan sama sekali. Akibatnya voice itu di-reset position=0 lagi oleh
        // ketukan ke-2 sebelum ketukan pertama sempat ngeluarin satu frame pun ->
        // ketukan pertama itu 0% terdengar (bukan cuma kepotong/lemah, tapi benar-benar
        // hilang total), padahal choke-nya sendiri dimaksudkan buat ketukan beruntun
        // yang lebih renggang (antar callback berbeda), bukan dua ketukan yang jatuh
        // di batch yang sama persis. Ini yang bikin kedengeran "kadang ketukan sekian
        // nggak kepencet" pas dipukul dobel/triple SANGAT cepat di pad yang sama.
        //
        // Perbaikannya: choke/reuse voice HANYA kalau voice itu sudah aktif SEBELUM
        // batch drain ini dimulai (udah sempat render minimal 1 buffer sebelumnya,
        // aman dipotong). Voice yang baru saja di-assign DI DALAM batch drain yang
        // sama tidak boleh dipakai ulang lagi -> ketukan berikutnya dalam batch yang
        // sama wajib dapat voice lain (nganggur/round-robin), jadi tiap ketukan -
        // sedekat apapun jaraknya - dijamin dapat minimal satu buffer render penuh
        // sebelum (kalau perlu) dipotong oleh ketukan sesudahnya.
        std::array<bool, kMaxVoices> assignedThisBatch{};
        assignedThisBatch.fill(false);

        while (readIdx != writeSnapshot) {
            int slot = ((readIdx % kTriggerQueueSize) + kTriggerQueueSize) % kTriggerQueueSize;
            int idx = triggerQueue_[slot].load(std::memory_order_acquire);
            readIdx++;

            if (idx < 0 || (size_t)idx >= samples_.size() || samples_[idx].data.empty()) continue;

            // --- CHOKE ANTAR PAD (fitur baru, beda dari choke sesama pad di bawah) ---
            // Pad idx ini baru aja dipukul (hit). Sebelum lanjut proses pukulan idx
            // sendiri, cek SEMUA pad lain di preset yang sama: kalau pad itu punya
            // crossChokeEnabled_ aktif DAN pad idx ini ADA di checklist mask-nya
            // (crossChokeMask_), voice pad itu yang lagi aktif langsung distop di
            // sini - persis efek "pad 1 lagi bunyi, dipukul pad lain (yang ada di
            // daftar pad 1), pad 1 langsung berhenti". Arahnya SATU ARAH & per pad
            // (lihat catatan panjang di PadEngine::setPadCrossChokeMask), jadi beda
            // dari padChoke_ di bawah yang cuma nyetop DIRI SENDIRI, bukan pad lain.
            {
                int hitPreset = idx / kMaxPads + 1;
                int hitPad = idx % kMaxPads + 1;
                int presetBase = (hitPreset - 1) * kMaxPads;
                for (int p = 0; p < kMaxPads; p++) {
                    int ownerIdx = presetBase + p;
                    if (ownerIdx == idx) continue; // pad gak nyetop dirinya sendiri lewat jalur ini
                    if (!crossChokeEnabled_[ownerIdx].load()) continue;
                    uint16_t mask = crossChokeMask_[ownerIdx].load();
                    if ((mask & (uint16_t)(1u << (hitPad - 1))) == 0) continue;
                    for (int i = 0; i < kMaxVoices; i++) {
                        if (voices_[i].active.load() && voices_[i].buffer == &samples_[ownerIdx]) {
                            // FADE-OUT (bukan active=false langsung) biar gak "tet".
                            beginFadeOut(voices_[i], fadeFrames);
                        }
                    }
                }
            }

            // LOOP (gaya DTX M12): kalau pad ini SAAT INI punya voice yang lagi
            // looping (voices_[i].loop == true), ketukan ini fungsinya STOP loop
            // itu, BUKAN mulai bunyi baru. Ini dicek berdasarkan status voice YANG
            // BENERAN LAGI JALAN (voice.loop), bukan padLoop_[idx] (setelan toggle
            // di dialog Pengaturan) - jadi kalau user matiin togglenya pas lagi
            // muter, ketukan berikutnya di pad itu tetap menghentikan loop yang
            // udah kepalang jalan (lihat catatan di setPadLoop). Voice yang
            // assignedThisBatch dilewatin sama seperti choke di bawah, biar gak
            // "makan" loop yang baru aja dimulai DALAM batch drain yang sama.
            int existingLoop = -1;
            for (int i = 0; i < kMaxVoices; i++) {
                if (!assignedThisBatch[i] && voices_[i].active.load() && voices_[i].loop &&
                    voices_[i].buffer == &samples_[idx]) {
                    existingLoop = i;
                    break;
                }
            }
            if (existingLoop >= 0) {
                // FADE-OUT (bukan active=false langsung) biar stop loop gak "tet".
                beginFadeOut(voices_[existingLoop], fadeFrames);
                assignedThisBatch[existingLoop] = true;
                continue; // stop-toggle - ketukan ini gak mulai voice baru sama sekali
            }

            // Kalau pad yang SAMA masih ada voice yang lagi bunyi DARI SEBELUM batch
            // ini, voice itu di-FADE-OUT (choke) -> roll yang agak renggang (antar
            // callback) kedengeran bersih satu-satu, gak numpuk/phase-cancel.
            // Voice yang assignedThisBatch (baru aja dikasih ke ketukan lain barusan,
            // DALAM callback yang sama) sengaja DILEWATIN walau buffer-nya sama, biar
            // gak "makan" ketukan yang belum sempat bunyi sama sekali (lihat catatan
            // di atas).
            //
            // Choke ini SENGAJA bisa dimatiin per pad (padChoke_[idx]) - motong voice
            // lama secara paksa di tengah jalan sering kedengeran "tet" (klik/pop
            // gara-gara sinyal keputus mendadak, gak nol dulu). Buat pad yang gak mau
            // efek itu (mis. ingin overlap natural), kalau padChoke_[idx] == false,
            // loop pencarian voice lama ini SKIP total -> ketukan baru wajib dapat
            // voice lain (nganggur/round-robin di bawah), voice lama dibiarin nyelesain
            // sendiri tanpa dipotong.
            //
            // FIX "tet" (choke sesama pad): DULU voice lama langsung di-REUSE buat
            // pukulan baru (positionF = 0 + buffer diganti di frame yang sama) -> sinyal
            // lama yang lagi di tengah gelombang loncat mendadak ke awal sample baru =
            // klik/"tet". SEKARANG voice lama TIDAK disentuh posisinya - cuma
            // ditandai fade-out (beginFadeOut) lalu mati mulus dalam kChokeFadeMs,
            // sementara pukulan baru dapat voice BARU (nganggur/round-robin di bawah)
            // dan langsung bunyi. Hasilnya crossfade singkat: ekor lama turun halus,
            // pukulan baru naik tanpa lubang. Voice yang assignedThisBatch tetap
            // dilewatin (aturan lama: tiap ketukan dijamin dapat render minimal 1
            // buffer sebelum boleh dipotong). Voice yang SUDAH fading dibiarin lanjut
            // (beginFadeOut gak me-restart) - dan semua voice pad ini yang masih
            // bunyi ikut di-fade, bukan cuma yang pertama ketemu, biar gak ada sisa
            // ekor lama yang numpuk.
            if (padChoke_[idx].load()) {
                for (int i = 0; i < kMaxVoices; i++) {
                    if (!assignedThisBatch[i] && voices_[i].active.load() && voices_[i].buffer == &samples_[idx]) {
                        beginFadeOut(voices_[i], fadeFrames);
                    }
                }
            }
            int v = -1;
            {
                for (int i = 0; i < kMaxVoices; i++) {
                    if (!assignedThisBatch[i] && !voices_[i].active.load()) {
                        v = i;
                        break;
                    }
                }
            }
            if (v < 0) {
                // Semua slot kepake. Kalau ada voice yang LAGI fade-out, curi yang
                // paling hampir selesai (progress terbesar = paling pelan/hampir
                // senyap) - jauh lebih halus daripada nyuri voice yang lagi keras
                // bunyinya (itu yang bakal kepotong mendadak = "tet").
                float bestProgress = -1.0f;
                for (int i = 0; i < kMaxVoices; i++) {
                    if (!assignedThisBatch[i] && voices_[i].active.load() && voices_[i].fading &&
                        voices_[i].fadeProgress > bestProgress) {
                        bestProgress = voices_[i].fadeProgress;
                        v = i;
                    }
                }
            }
            if (v < 0) {
                // Fallback round-robin: cari slot yang belum kepake batch ini juga,
                // biar 2 ketukan (pad sama atau beda) dalam batch yang sama gak saling timpa.
                for (int tries = 0; tries < kMaxVoices; tries++) {
                    int cand = nextVoice_.fetch_add(1) % kMaxVoices;
                    if (!assignedThisBatch[cand]) { v = cand; break; }
                }
                if (v < 0) v = nextVoice_.fetch_add(1) % kMaxVoices; // semua slot kepake -> curi salah satu
            }

            voices_[v].buffer = &samples_[idx];
            voices_[v].positionF = 0.0;
            voices_[v].volume = padVolume_[idx].load();
            // Mulai loop baru kalau setelan pad ini AKTIF (padLoop_[idx]) - voice
            // ini bakal wrap balik ke frame 0 pas nyampe ujung sample TANPA di-
            // nonaktifkan (lihat pengecekan v.loop di loop mixing di bawah), jadi
            // muter terus tanpa jeda sampai ketukan berikutnya di pad ini nge-stop
            // dia lewat pengecekan existingLoop di atas. HARUS di-reset eksplisit
            // di sini (bukan cuma di-default false di struct) karena slot voice ini
            // bisa aja bekas dipakai pad LAIN yang loop-nya beda status sebelumnya.
            voices_[v].loop = padLoop_[idx].load();
            // Voice ini "milik" slot (preset,pad) idx selama dia aktif - dipakai buat
            // nyari tempo LIVE loop-nya (padLoopTempo_[idx]) tiap callback di loop
            // mixing bawah, TANPA harus nyimpen preset/pad terpisah di Voice.
            voices_[v].padIndex = idx;
            // CATATAN (diubah atas permintaan user): loop yang BARU AJA dimulai
            // dulu SELALU dipaksa balik ke tempo normal (1.0x) di sini. Sekarang
            // SENGAJA dibiarkan apa adanya - padLoopTempo_[idx] udah nyimpen tempo
            // TERAKHIR yang di-set user buat pad ini (lewat setPadLoopTempo, dipicu
            // dari chip −/+ di UI, dan dipulihkan dari disk tiap app start lewat
            // PadLoopTempoStorage.loadAllIntoEngine) - biar tempo itu TETAP NEMPEL
            // walau loop-nya dihentikan lalu dimulai lagi, konsisten sama ekspektasi
            // "kecepatan yang gue atur harusnya kepake terus sampe gue ubah lagi".
            // 2^(semitone/12): rasio kecepatan baca sample buat tune. >1 = pitch naik
            // & sample kebaca lebih cepat (durasi lebih pendek), <1 = pitch turun &
            // sample kebaca lebih lambat (durasi lebih panjang) - lihat catatan di
            // setPadTune() kenapa ini dianggap wajar buat sampler kendang.
            voices_[v].pitchRatio = std::pow(2.0f, padTune_[idx].load() / 12.0f);
            voices_[v].reverbSend = padReverb_[idx].load();
            // Reset state fade-out: slot ini bisa aja bekas voice yang lagi/udah fading
            // (mis. hasil curian di atas) - ketukan baru harus mulai dari gain penuh.
            voices_[v].fading = false;
            voices_[v].fadeProgress = 0.0f;
            voices_[v].fadeInc = 0.0f;
            voices_[v].active.store(true);
            assignedThisBatch[v] = true;
        }
        triggerReadIdx_.store(readIdx, std::memory_order_relaxed);
    }

    // Bus reverb bareng: di-reset ke 0 buat numFrames callback ini (di-clamp ke
    // kapasitas tetap reverbBus_ - lihat catatan di PadEngine.h soal kenapa gak
    // pakai std::vector di sini), lalu tiap voice numpahin porsi "send"-nya (lihat
    // v.reverbSend) ke sini SEBELUM diproses lewat reverb_ di bawah.
    int reverbFrames = std::min(numFrames, kMaxReverbBusFrames);
    std::fill(reverbBus_.begin(), reverbBus_.begin() + reverbFrames, 0.0f);

    for (auto &v : voices_) {
        if (!v.active.load() || v.buffer == nullptr) continue;
        int srcChannels = v.buffer->channels;
        int totalFrames = (int)(v.buffer->data.size() / srcChannels);
        if (totalFrames <= 0) { v.active.store(false); continue; }
        // kendangVol = mixer global (ngalikan SEMUA pad), v.volume = level pad ITU
        // SENDIRI (di-set lewat dialog Pengaturan) - dua-duanya digabung di sini,
        // jadi user bisa nurunin/naikin 1 pad doang tanpa ganggu pad lain, DAN
        // tetep punya kontrol volume kendang keseluruhan seperti biasa.
        float kendangVol = kendangVolume_.load() * v.volume;

        // Tempo LIVE loop (gaya turntable DTX M12): CUMA berlaku buat voice yang lagi
        // loop (v.loop true) - voice biasa (sekali ketuk) gak kepengaruh sama sekali,
        // tetap pakai pitchRatio apa adanya. Dihitung SEKALI per voice per callback
        // (bukan tiap frame di dalam) - cukup buat kedengeran berubah "langsung" tanpa
        // nge-load atomic berkali-kali sia-sia dalam 1 buffer yang sama.
        float loopTempo = (v.loop && v.padIndex >= 0) ? padLoopTempo_[v.padIndex].load() : 1.0f;

        // Titik wrap loop: PAKAI loopEndFrame (bukan totalFrames) buat voice yang
        // lagi loop - itu udah dihitung SEKALI pas loadSample() (nyari frame
        // terakhir yang beneran ada bunyinya + padding kecil), jadi loop lompat
        // balik ke awal PERSIS pas levelnya mulai turun ke hening, bukan nunggu
        // beneran abis nyampe akhir data (yang bisa aja masih nyisa hening ekor
        // dari file WAV aslinya). totalFrames (panjang PENUH) tetap dipakai apa
        // adanya buat voice yang BUKAN loop (sekali ketuk) - mode itu tetap muter
        // sample ori sampai bener-bener habis, sama sekali gak kepengaruh.
        int wrapFrames = totalFrames;
        if (v.loop && v.buffer->loopEndFrame > 0 && v.buffer->loopEndFrame <= totalFrames) {
            wrapFrames = v.buffer->loopEndFrame;
        }

        for (int frame = 0; frame < numFrames; frame++) {
            // positionF pecahan (bukan int) karena pitchRatio biasanya bukan 1.0 kalau
            // pad di-tune - baca 2 frame source terdekat (i0/i1) & interpolasi linear,
            // ini yang bikin hasil pitch-shift-nya gak berisik/aliasing kasar walau
            // resampling-nya sederhana (linear, bukan sinc).
            if (v.positionF >= (double)wrapFrames) {
                if (v.loop) {
                    // Wrap balik ke awal sample TANPA JEDA - dilakukan di tengah
                    // frame loop yang sama (bukan nunggu callback berikutnya), jadi
                    // gak ada satu buffer pun yang kelewat senyap. fmod (bukan cuma
                    // "- wrapFrames") biar tetap benar walau pitchRatio besar bikin
                    // positionF sempat lompat lebih dari 1 panjang sample sekaligus.
                    v.positionF = std::fmod(v.positionF, (double)wrapFrames);
                } else {
                    v.active.store(false);
                    break;
                }
            }
            // Gain fade-out (1.0 kalau voice gak lagi dipotong). Kurva smoothstep
            // (1 - p^2*(3-2p)) bukan garis lurus: turunnya landai di awal DAN akhir,
            // jadi gak ada "patahan" gelombang di titik mulai/selesai fade (patahan
            // itu sendiri bisa kedengeran sebagai klik halus). Kalau progress udah
            // penuh, voice dimatiin di sini - frame ini gak dirender (gain-nya 0).
            float fadeGain = 1.0f;
            if (v.fading) {
                float p = v.fadeProgress;
                if (p >= 1.0f) {
                    v.active.store(false);
                    v.fading = false;
                    break;
                }
                fadeGain = 1.0f - p * p * (3.0f - 2.0f * p);
                v.fadeProgress = p + v.fadeInc;
            }

            int i0 = (int)v.positionF;
            // i1 di-clamp ke frame terakhir (bukan lanjut ke luar array) - pas posisi
            // udah di frame paling akhir, ini efektif "nahan" sample terakhir buat
            // interpolasi alih-alih baca di luar batas buffer.
            int i1 = std::min(i0 + 1, totalFrames - 1);
            float frac = (float)(v.positionF - (double)i0);

            float monoForReverb = 0.0f;
            for (int ch = 0; ch < outChannels; ch++) {
                int srcCh = (srcChannels == 1) ? 0 : (ch % srcChannels);
                float s0 = v.buffer->data[(size_t)i0 * srcChannels + srcCh];
                float s1 = v.buffer->data[(size_t)i1 * srcChannels + srcCh];
                float sample = s0 + (s1 - s0) * frac;
                float mixed = sample * kendangVol * fadeGain;
                out[frame * outChannels + ch] += mixed;
                monoForReverb += mixed;
            }
            if (v.reverbSend > 0.0f && frame < reverbFrames) {
                // rata-rata kanal (bukan jumlah) biar send-nya gak dobel lebih keras
                // cuma gara-gara sample-nya stereo dibanding mono.
                reverbBus_[frame] += (monoForReverb / (float)std::max(1, outChannels)) * v.reverbSend;
            }

            // loopTempo cuma pernah != 1.0 kalau v.loop true (lihat perhitungan di
            // atas), jadi voice non-loop tetap maju persis sebesar pitchRatio-nya
            // seperti sebelumnya - baris ini gak ngubah perilaku pad yang gak loop.
            v.positionF += (double)(v.pitchRatio * loopTempo);
        }
    }

    // Proses bus reverb bareng lalu jumlahin hasilnya (wet stereo) balik ke output.
    // Selalu dijalankan (bukan cuma pas ada pad yang reverb-nya > 0) - delay line-nya
    // udah kepenuhan nol kalau gak ada input, jadi CPU cost-nya konstan & kecil, dan
    // ini menghindari "klik" dari nyala/matiin proses reverb di tengah-tengah.
    for (int frame = 0; frame < reverbFrames; frame++) {
        float wetL, wetR;
        reverb_.process(reverbBus_[frame], wetL, wetR);
        if (outChannels >= 2) {
            out[frame * outChannels + 0] += wetL;
            out[frame * outChannels + 1] += wetR;
        } else if (outChannels == 1) {
            out[frame] += (wetL + wetR) * 0.5f;
        }
    }

    // Mix track musik (kalau lagi play) tepat di sini -> sebelum clamp & sebelum
    // capture rekaman, jadi lagu otomatis ikut ke-record bareng pukulan kendang,
    // persis kayak yang kedengeran. try_lock juga di sini biar loadMusic() (bisa
    // makan waktu, filenya bisa gede) gak pernah nge-block audio thread.
    if (musicPlaying_.load()) {
        std::unique_lock<std::mutex> mlock(musicMutex_, std::try_to_lock);
        if (mlock.owns_lock() && !music_.data.empty()) {
            float musicVol = musicVolume_.load();
            int64_t pos = musicPosition_.load();
            int64_t totalFrames = music_.totalFrames;
            for (int frame = 0; frame < numFrames; frame++) {
                if (pos >= totalFrames) {
                    musicPlaying_.store(false); // lagu abis -> auto-stop
                    break;
                }
                for (int ch = 0; ch < outChannels; ch++) {
                    int srcCh = ch % 2; // music_ selalu stereo
                    float sample = music_.data[(size_t)pos * 2 + srcCh] / 32768.0f;
                    out[frame * outChannels + ch] += sample * musicVol;
                }
                pos++;
            }
            musicPosition_.store(pos);
        }
    }

    // soft clamp biar gak crackling kalau beberapa pad numpuk
    for (int i = 0; i < numFrames * outChannels; i++) {
        out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    }

    // Kalau lagi rekam: tangkep persis buffer yang barusan di-mix di atas (post-clamp),
    // jadi hasil rekaman = persis apa yang kedengeran.
    //
    // FIX "tet tet tet" (suara REKAM LAYAR kedengeran kretek/putus-putus tiap
    // sebentar): sebelumnya di sini pakai try_lock, dan kalau lagi kebentur sama
    // drainRecordBuffer()/stopRecording() (dipanggil berkali-kali per detik dari
    // thread encoder audio rekam layar), SELURUH buffer callback ini (bisa
    // beberapa milidetik audio) langsung DIBUANG diam-diam dari recordBuffer_ -
    // padahal suara yang didengar user real-time TETAP normal (buang-nya cuma
    // di jalur rekaman). Hasilnya: di FILE hasil rekaman ada bolong kecil
    // berulang di semua titik yang kebentur lock itu, dan tiap bolong kedengeran
    // sebagai klik/"tet" karena gelombang audio jadi gak nyambung mulus.
    // Sekarang pakai lock_guard biasa (nunggu, gak pernah skip data) - AMAN buat
    // audio thread real-time karena drainRecordBuffer()/stopRecording() sudah
    // diubah supaya cuma pegang recordMutex_ untuk satu std::deque::swap() O(1)
    // (lihat komentar di sana), jadi paling lama nunggu cuma beberapa instruksi,
    // bukan durasi copy seluruh buffer rekaman.
    if (recording_.load()) {
        std::lock_guard<std::mutex> recLock(recordMutex_);
        // push_back ke deque, BUKAN resize+index ke vector: deque nambah data
        // per-chunk dan gak pernah realloc+copy seluruh histori rekaman, jadi
        // biaya per buffer tetep konstan walau rekamannya udah jalan lama.
        size_t samplesThisBuffer = (size_t)numFrames * outChannels;
        for (size_t i = 0; i < samplesThisBuffer; i++) {
            recordBuffer_.push_back((int16_t)std::lround(out[i] * 32767.0f));
        }
    }

    return oboe::DataCallbackResult::Continue;
}
