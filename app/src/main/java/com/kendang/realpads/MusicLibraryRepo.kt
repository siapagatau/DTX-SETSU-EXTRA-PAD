package com.kendang.realpads

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

// Sumber daftar musik buat dialog "LAGU": scan MediaStore.Audio.Media, yaitu
// index musik yang SUDAH dibuatin sama Android sendiri (media scanner bawaan
// sistem) - jadi kita gak perlu jalan-jalan buka folder satu-satu, tinggal
// query database media yang udah ada. Ini yang dimaksud "musik yang sudah
// terdeteksi di HP" - kalau lagunya baru aja disalin & belum sempet di-index
// sistem, dia emang belum bakal muncul di sini (itu gunanya opsi "Cari File
// Manual" di dialog, tetep bisa ambil file appapun lewat SAF picker biasa).
object MusicLibraryRepo {

    data class DeviceSong(
        val uri: Uri,
        val title: String,
        val durationMs: Long,
        val sizeBytes: Long
    )

    // Dipanggil dari IO thread. Query MediaStore biasanya cepat (baca index,
    // bukan baca file asli satu-satu), tapi tetep bukan kerjaan main thread.
    // Lempar exception ke atas (mis. SecurityException kalau izin ditolak) -
    // biar pemanggil (UI) yang nentuin gimana nampilinnya, sama pola kayak
    // OnlinePresetRepo.listPresets().
    fun scanDeviceSongs(context: Context): List<DeviceSong> {
        val songs = mutableListOf<DeviceSong>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        // IS_MUSIC != 0 -> filter standar biar nada dering/notifikasi/alarm/
        // rekaman panggilan bawaan sistem gak ikut nongol di daftar lagu.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol)
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                    ?: displayName?.takeIf { it.isNotBlank() }
                    ?: "Tanpa judul"
                val duration = cursor.getLong(durationCol)
                val size = cursor.getLong(sizeCol)
                val uri = Uri.withAppendedPath(collection, id.toString())
                songs.add(DeviceSong(uri, title, duration, size))
            }
        }
        return songs
    }
}
