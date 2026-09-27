package com.kendang.realpads

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateMapOf

private const val KEYBIND_PREFS = "realpads_pad_keybind"

// Nyimpen binding tombol keyboard fisik (USB maupun Bluetooth/wireless yang
// dihubungin ke HP - keduanya sama-sama nongol ke Android sebagai KeyEvent
// biasa, gak perlu izin USB host khusus) ke tiap pad (P1..P12).
//
// Mapping-nya SENGAJA dibikin GLOBAL, terpisah dari preset yang lagi aktif -
// bukan per (preset, pad) kayak PadTuneStorage dkk. Alasannya: keyboard fisik
// itu barang yang nempel tetap (user pasang sekali, mainnya lama), sedangkan
// preset sering gonta-ganti pas lagi main - kalau mapping-nya ikut preset,
// user harus apal/rekam ulang tombol tiap pindah preset, padahal maksudnya
// justru supaya SATU tombol yang sama konsisten mukul PAD di posisi yang sama
// walau preset-nya lagi ganti-ganti.
object PadKeyBindingStorage {
    // pad -> Android KeyEvent.keyCode. Ini SUMBER KEBENARAN yang dibaca langsung
    // di MainActivity.dispatchKeyEvent tiap physical key ditekan SELAGI dialog
    // Pengaturan lagi GAK kebuka (dipakai buat trigger pad) - makanya harus
    // sudah ke-load di memori SEBELUM user mulai mukul-mukul keyboard (dipanggil
    // sekali pas app start, lihat pemanggilan load() di PadScreen), supaya gak
    // ada IO/SharedPreferences read di jalur real-time pemukulan pad yang bisa
    // nambah delay/lag kedengeran.
    val bindings = mutableStateMapOf<Int, Int>()

    private fun key(pad: Int) = "keycode_pad$pad"

    fun load(context: android.content.Context) {
        val prefs = context.getSharedPreferences(KEYBIND_PREFS, android.content.Context.MODE_PRIVATE)
        bindings.clear()
        for (pad in 1..PresetStorage.MAX_PAD) {
            val k = key(pad)
            if (prefs.contains(k)) {
                bindings[pad] = prefs.getInt(k, -1)
            }
        }
    }

    // Dipanggil begitu proses "rekam tombol" di dialog Pengaturan nangkep 1 physical
    // key. Kalau tombol yang sama kebetulan udah kepasang di pad lain, otomatis
    // dilepas dulu dari situ - biar 1 tombol fisik gak pernah nge-trigger 2 pad
    // sekaligus (bisa bikin bingung/bunyi dobel gak sengaja pas ditekan).
    fun set(context: android.content.Context, pad: Int, keyCode: Int) {
        val prefs = context.getSharedPreferences(KEYBIND_PREFS, android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val existingPad = bindings.entries.firstOrNull { it.value == keyCode && it.key != pad }?.key
        if (existingPad != null) {
            bindings.remove(existingPad)
            editor.remove(key(existingPad))
        }
        bindings[pad] = keyCode
        editor.putInt(key(pad), keyCode)
        editor.apply()
    }

    // Reset tombol 1 pad aja balik ke "belum diatur".
    fun reset(context: android.content.Context, pad: Int) {
        bindings.remove(pad)
        context.getSharedPreferences(KEYBIND_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(key(pad))
            .apply()
    }

    // Reset SEMUA binding tombol (dipanggil dari tombol "Reset Semua Tombol" di
    // dialog Pengaturan) - balikin seluruh pad ke "belum diatur" sekaligus.
    fun resetAll(context: android.content.Context) {
        bindings.clear()
        context.getSharedPreferences(KEYBIND_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // Dipanggil tiap physical key ditekan (MainActivity.dispatchKeyEvent) buat
    // nyari pad mana yang di-bind ke keyCode itu. Null kalau gak ada yang cocok.
    fun padForKeyCode(keyCode: Int): Int? =
        bindings.entries.firstOrNull { it.value == keyCode }?.key
}

// Tombol sistem yang SENGAJA gak boleh direbut buat jadi trigger pad, walau lagi
// dalam mode "rekam tombol" - kalau ini ikut kerekam, user bisa kejebak (mis.
// tombol Back gak lagi bisa keluar dialog/app) atau ganggu fungsi dasar HP
// (volume, home, power). Semua key fisik LAIN (huruf, angka, F1-F24, tombol
// numpad, tombol extra di keyboard gaming/DJ controller, dst) tetap bebas dipakai.
private val NON_BINDABLE_KEYCODES = setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_CAMERA,
    KeyEvent.KEYCODE_UNKNOWN
)

fun isBindableKeyCode(keyCode: Int): Boolean = keyCode !in NON_BINDABLE_KEYCODES

// "KEYCODE_A" -> "A", "KEYCODE_SPACE" -> "SPACE", dst - biar yang ditampilin ke
// user nama tombolnya aja, bukan konstanta Android mentah-mentah.
fun keyCodeDisplayName(keyCode: Int): String {
    val raw = KeyEvent.keyCodeToString(keyCode)
    return raw.removePrefix("KEYCODE_").replace("_", " ")
}
