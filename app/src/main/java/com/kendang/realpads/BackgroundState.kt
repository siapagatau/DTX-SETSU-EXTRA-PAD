package com.kendang.realpads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

private const val BG_PREFS = "realpads_background"
private const val KEY_OPACITY = "opacity"
private const val KEY_PAD_OPACITY = "pad_opacity"
private const val BG_FILENAME = "pad_background.img"
private const val DEFAULT_OPACITY = 0.35f
// Pad tetap solid (1f) secara default biar tampilan lama gak berubah buat user yang
// belum pernah pasang background - transparansi pad cuma kepake begitu user sendiri
// yang menggeser slidernya (biasanya abis pasang background gambar).
private const val DEFAULT_PAD_OPACITY = 1f

// Nilai yang dipakai KHUSUS begitu user BARU AJA pasang gambar background (lihat
// applyImage di bawah) - beda dari DEFAULT_OPACITY/DEFAULT_PAD_OPACITY di atas yang
// cuma fallback buat state yang di-load dari disk pas app pertama kali dibuka (belum
// pernah ada background sama sekali). Begitu ada background BARU dipasang, gambarnya
// langsung kelihatan penuh (opacity 100%) dan pad-nya otomatis dibikin separuh
// tembus pandang (50%) biar background-nya ikut kelihatan dari balik pad tanpa
// user harus geser slider manual dulu.
private const val NEW_BACKGROUND_OPACITY = 1f
private const val NEW_BACKGROUND_PAD_OPACITY = 0.5f

// Background gambar buat area pad + opacity-nya. Gambarnya DISALIN ke internal storage
// (bukan cuma nyimpen content:// URI-nya) karena izin akses ke URI galeri bisa dicabut
// sewaktu-waktu sama sistem begitu app ditutup/di-restart - kalau cuma nyimpen URI,
// background bisa gagal muncul lagi padahal user udah milih. Pola persist-nya sama
// kayak ThemeState buat warna aksen (SharedPreferences + state Compose).
//
// Kerja file/decode BITMAP (berat, gak boleh ngeblok UI thread) dipisah TEGAS dari
// penulisan state Compose (imageBitmap/opacity, harus dari main thread): fungsi yang
// namanya diawali "load"/"decode" aman dipanggil dari Dispatchers.IO, sedangkan
// "apply*" HARUS dipanggil di main thread buat nulis ke state.
object BackgroundState {
    // null = belum ada background custom (area pad polos kayak biasa)
    var imageBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var opacity by mutableStateOf(DEFAULT_OPACITY)
        private set
    // Transparansi PAD itu sendiri (bukan gambar background) - biar area pad gak
    // "nutup" background gambar walau opacity gambarnya udah dinaikin. 1f = pad solid
    // penuh kayak sebelum fitur ini ada, makin kecil makin tembus pandang.
    var padOpacity by mutableStateOf(DEFAULT_PAD_OPACITY)
        private set

    data class Loaded(val image: ImageBitmap?, val opacity: Float, val padOpacity: Float)

    private fun file(context: Context) = File(context.filesDir, BG_FILENAME)

    // Baca prefs + decode bitmap dari disk - IO-heavy, panggil dari Dispatchers.IO,
    // gak nyentuh state Compose sama sekali. Hasilnya baru ditulis ke state lewat
    // applyLoaded() di main thread.
    fun loadFromDisk(context: Context): Loaded {
        val prefs = context.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE)
        val op = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY)
        val padOp = prefs.getFloat(KEY_PAD_OPACITY, DEFAULT_PAD_OPACITY)
        val f = file(context)
        val img = if (f.exists()) decodeDownsampled(f)?.asImageBitmap() else null
        return Loaded(img, op, padOp)
    }

    fun applyLoaded(loaded: Loaded) {
        imageBitmap = loaded.image
        opacity = loaded.opacity
        padOpacity = loaded.padOpacity
    }

    // Foto dari galeri HP modern gampang belasan-puluhan megapixel - didownsample dulu
    // (target sisi terpanjang ~1600px, masih lebih dari cukup tajam buat background di
    // belakang pad) biar gak berat di memori/decode dibanding ukuran aslinya.
    private fun decodeDownsampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val maxSide = 1600
            while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Throwable) {
            null
        }
    }

    // bytes = isi file gambar yang dipilih user (dibaca sebelum dipanggil, lewat
    // contentResolver.openInputStream). IO-heavy (tulis file + decode) - panggil dari
    // Dispatchers.IO, hasilnya (ImageBitmap? / null kalau gagal) baru ditulis ke state
    // lewat applyImage() di main thread.
    fun decodeAndSave(context: Context, bytes: ByteArray): ImageBitmap? {
        return try {
            file(context).writeBytes(bytes)
            decodeDownsampled(file(context))?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    // Dipanggil begitu user SELESAI milih gambar background baru dari galeri.
    // Sengaja SEKALIAN nge-reset opacity gambar & opacity pad ke default
    // "background baru" (100% / 50%, lihat NEW_BACKGROUND_OPACITY di atas) dan
    // langsung dipersist ke SharedPreferences lewat setOpacity/setPadOpacity -
    // bukan cuma ganti bitmap-nya doang - biar tiap kali pasang background baru
    // hasilnya konsisten kelihatan bagus dari awal (background penuh + pad
    // separuh transparan) tanpa user harus utak-atik slider transparansi
    // manual dulu tiap ganti gambar.
    fun applyImage(context: Context, image: ImageBitmap) {
        imageBitmap = image
        setOpacity(context, NEW_BACKGROUND_OPACITY)
        setPadOpacity(context, NEW_BACKGROUND_PAD_OPACITY)
    }

    fun clearImage(context: Context) {
        file(context).delete()
        imageBitmap = null
    }

    fun setOpacity(context: Context, value: Float) {
        opacity = value.coerceIn(0f, 1f)
        context.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_OPACITY, opacity)
            .apply()
    }

    fun setPadOpacity(context: Context, value: Float) {
        padOpacity = value.coerceIn(0f, 1f)
        context.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAD_OPACITY, padOpacity)
            .apply()
    }
}
