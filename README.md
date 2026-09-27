# Real Pads Kendang (Native Android)

Versi native dari app pad kendang, pakai Kotlin + Jetpack Compose buat UI dan
C++ (Oboe / AAudio LowLatency + Exclusive mode) buat audio engine-nya —
jalur yang sama yang dipakai app-app drum pad native (kayak Real Pads) buat
dapetin latency serendah mungkin. Ini BUKAN web app lagi, jadi gak lewat
Web Audio API/browser sama sekali.

## Versi FREE vs PREMIUM

Project ini sekarang punya 2 **product flavor** Gradle (`free` & `premium`),
dibangun dari source code yang SAMA PERSIS (app/src/main) tapi hasilnya 2 APK
terpisah dengan applicationId beda - jadi bisa diinstall BERBARENGAN di HP
yang sama tanpa saling timpa:

| Flavor    | Nama aplikasi     | applicationId                        | Ikon  | Preset & Tema                    |
|-----------|--------------------|---------------------------------------|-------|-----------------------------------|
| `free`    | APK DTX FREE       | `com.kendang.realpads.gler.free`      | FREE  | Cuma P1-P2 kebuka, P3-P8 & TEMA dikunci (gembok, tap -> link upgrade) |
| `premium` | APK DTX PREMIUM    | `com.kendang.realpads.gler.premium`   | DTX   | Semua preset (P1-P8) & TEMA kebuka bebas |

Yang nentuin kunci/enggaknya fitur di kode Kotlin (`MainActivity.kt`) adalah
1 flag `BuildConfig.IS_PREMIUM`, yang otomatis ke-set oleh Gradle sesuai
flavor yang lagi di-build (`false` buat `free`, `true` buat `premium`) -
lihat `productFlavors` di `app/build.gradle.kts`.

## Cara build lewat GitHub (gak perlu Android Studio)

1. Buat repo baru di GitHub (bisa private atau public).
2. Push semua file di folder ini ke repo tersebut:
   ```
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO.git
   git push -u origin main
   ```
3. Buka tab **Actions** di repo GitHub kamu. Workflow "Build Debug APK" bakal
   otomatis jalan.
4. Setelah selesai (~5-10 menit build pertama kali), buka run yang barusan,
   scroll ke bagian **Artifacts**.
5. **PENTING (sejak ada flavor free/premium):** karena sekarang ada 2 flavor,
   `app-debug.apk` yang lama gak ada lagi - hasil build-nya jadi 2 file
   terpisah, `app-free-debug.apk` (di folder
   `app/build/outputs/apk/free/debug/`) dan `app-premium-debug.apk` (di
   `app/build/outputs/apk/premium/debug/`). Kalau workflow `build-apk.yml` di
   repo kamu masih nunjuk ke path/nama file lama (`app-debug.apk` doang),
   update dulu langkah upload-artifact-nya biar ke-includ dua-duanya (atau
   pakai pola `app/build/outputs/apk/**/*.apk` biar otomatis ke-ambil semua
   flavor tanpa perlu diubah lagi kalau nambah flavor baru).
6. Extract zip artifact-nya, install `app-free-debug.apk` buat versi FREE
   dan/atau `app-premium-debug.apk` buat versi PREMIUM. Kirim ke HP (lewat
   Drive/Telegram/
   kabel), lalu install (perlu izinin "Install dari sumber tidak dikenal").

## Cara pakai app-nya

- Tap pad = langsung mainin suara (trigger di ACTION_DOWN, bukan nunggu lepas jari).
- Tap pad lagi (bukan tap-tap cepat) buat pilih pad itu jadi target isi suara.
- Tombol preset **P1-P4** di bawah buat ganti bank suara.
- Tombol **SET SUARA** buka file picker (WAV 16-bit) buat pad yang lagi dipilih.
- Tombol **● REC** buat mulai rekam permainan pad (mixdown-nya, bukan lewat mic).
  Tap lagi buat stop (tombol berubah merah & nunjukin durasi berjalan), lalu
  pilih lokasi simpen -> otomatis di-encode ke file **.mp3**.

## Batasan versi ini

- Untuk **load sample ke pad**, cuma dukung file **.wav PCM 16-bit** (format
  paling umum buat sample drum). MP3 belum didukung buat sample masuk.
- **Rekaman** (fitur REC) di-export sebagai **.mp3** (pakai LAME encoder lewat
  library `TAndroidLame`). Gak ada batas durasi - jalan terus sampe di-stop
  manual. Makin lama direkam, makin banyak RAM yang dipakai selama sesi rekam
  (~11.5 MB/menit, stereo 48kHz 16-bit), jadi tetep disarankan stop kalau udah
  gak dipakai.
- Sample yang di-load disimpan di RAM aja, ilang kalau app di-kill (belum ada
  penyimpanan permanen). Bisa ditambahin nanti kalau perlu.
- Polyphony dibatasi 16 voice bersamaan (lebih dari cukup buat main kendang).

## Kalau build gagal di Actions

Versi NDK/CMake/Compose compiler di file `app/build.gradle.kts` di-pin ke
versi tertentu (NDK 26.1.10909125, AGP 8.5.0, Kotlin 1.9.24). Kalau GitHub
Actions error karena versi Android SDK/build-tools berubah, biasanya cukup
naikin versi yang direferensikan di `build-apk.yml` dan `build.gradle.kts`
ke versi terbaru yang tersedia.
