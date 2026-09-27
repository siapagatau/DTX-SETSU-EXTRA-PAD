package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val EXTRA_PAD_PREFS = "realpads_extra_pad"
private const val KEY_EXTRA_PAD_ENABLED = "extra_pad_enabled"
private const val KEY_EXTRA_PAD_APPLY_ALL = "extra_pad_apply_all"
private const val KEY_EXTRA_PAD_PRESET_MASK = "extra_pad_preset_mask"

// Fitur "Pad Tambahan": nambahin 4 pad ekstra (pad13..pad16) ke PadGrid, DI SAMPING
// 12 pad bawaan (satu pad tambahan per baris, jadi kolom ke-4 tiap baris).
// Lihat PresetStorage.BASE_PAD/MAX_PAD - MAX_PAD dinaikin dari 12
// ke 16 justru buat nampung slot storage/native 4 pad ini, baik di disk maupun
// di PadEngine C++, sehingga otomatis ke-cover juga sama semua jalur yang sudah
// ada - load/save sample, volume/tune/reverb/choke/loop/cross-choke per pad,
// SERTA export/import preset (PresetStorage.exportPreset/importPreset), TANPA
// perlu nulis ulang kode-kode itu - semuanya udah loop "1..MAX_PAD".
//
// Beda dari 12 pad bawaan yang SELALU ada: 4 pad tambahan ini punya switch
// on/off sendiri (lihat `enabled` di bawah) - dimatikan berarti cuma
// DISEMBUNYIKAN dari PadGrid (lihat PadGrid.extraPadsActive di MainActivity),
// data yang sudah kesimpen di pad 13-16 (sample, volume, dst) TETAP UTUH di
// disk, gak dihapus - begitu dinyalain lagi semuanya balik keliatan persis
// seperti terakhir kali di-set.
//
// Scope aktifnya bisa diatur 2 cara (mirror pilihan user di dialog Pengaturan):
//  - applyToAll = true  -> aktif di SEMUA preset (P1-P8) sekaligus.
//  - applyToAll = false -> cuma aktif di preset yang dicentang user sendiri
//    lewat presetMask (bitmask 8-bit, bit (preset-1) = preset itu tercentang).
object ExtraPadState {
    var enabled by mutableStateOf(false)
        private set
    var applyToAll by mutableStateOf(true)
        private set
    var presetMask by mutableStateOf(0)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(EXTRA_PAD_PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_EXTRA_PAD_ENABLED, false)
        applyToAll = prefs.getBoolean(KEY_EXTRA_PAD_APPLY_ALL, true)
        presetMask = prefs.getInt(KEY_EXTRA_PAD_PRESET_MASK, 0)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(EXTRA_PAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXTRA_PAD_ENABLED, value)
            .apply()
    }

    fun setApplyToAll(context: Context, value: Boolean) {
        applyToAll = value
        context.getSharedPreferences(EXTRA_PAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXTRA_PAD_APPLY_ALL, value)
            .apply()
    }

    // Centang/hapus centang 1 preset (1..8) di daftar "preset tertentu" - cuma
    // kepakai kalau applyToAll == false (lihat isActiveFor di bawah).
    fun setPresetSelected(context: Context, preset: Int, selected: Boolean) {
        val bit = 1 shl (preset - 1)
        presetMask = if (selected) presetMask or bit else presetMask and bit.inv()
        context.getSharedPreferences(EXTRA_PAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_EXTRA_PAD_PRESET_MASK, presetMask)
            .apply()
    }

    fun isPresetSelected(preset: Int): Boolean = (presetMask and (1 shl (preset - 1))) != 0

    // Dipanggil PadGrid (lewat PadScreen) buat nentuin apakah PRESET INI, SAAT
    // INI, harus nampilin 4 pad tambahan atau enggak - satu-satunya titik
    // keputusan dipakai di seluruh app, biar PadGrid & PadVolumeDialog selalu
    // sepakat pad mana aja yang lagi "keliatan".
    fun isActiveFor(preset: Int): Boolean = enabled && (applyToAll || isPresetSelected(preset))
}
