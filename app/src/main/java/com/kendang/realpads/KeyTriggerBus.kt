package com.kendang.realpads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow

// Jembatan antara key event fisik yang ditangkep di MainActivity.dispatchKeyEvent
// (bukan Composable, jadi gak punya akses langsung ke onPadDown/state animasi
// pad yang hidup di dalam PadScreen) dengan logic pemukulan pad yang sebenarnya.
// Activity cukup emit ke sini tiap physical key yang match binding ditekan,
// PadScreen collect lewat LaunchedEffect lalu manggil onPadDown-nya sendiri -
// jadi trigger suara, kedip pad, efek GLER dsb tetep jalan SAMA PERSIS kayak
// dipukul pakai jari, gak ada jalur duplikat yang perlu dijaga sinkron manual.
object KeyTriggerBus {
    private val _triggers = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val triggers = _triggers

    // tryEmit (bukan suspend emit) sengaja dipilih karena dipanggil dari
    // dispatchKeyEvent - jalur callback Android biasa, bukan coroutine. Buffer 16
    // lebih dari cukup buat nampung ketukan cepat sebelum PadScreen sempet collect.
    fun emit(pad: Int) {
        _triggers.tryEmit(pad)
    }
}

// Status "lagi nunggu 1 tombol fisik buat direkam" yang dipicu dari tombol
// "Rekam" di dialog Pengaturan tiap pad.
//
// null = gak lagi nunggu apa-apa -> key event fisik jalan NORMAL, dipakai buat
// trigger pad sesuai binding yang udah tersimpan (lewat KeyTriggerBus di atas).
//
// Non-null (berisi nomor pad) = MainActivity.dispatchKeyEvent lagi dalam mode
// rekam: tombol fisik BERIKUTNYA yang ditekan (bukan tombol sistem terlarang,
// lihat isBindableKeyCode) ditangkep dan disimpan sebagai binding buat pad ini
// lewat PadKeyBindingStorage.set, lalu otomatis balik ke null. Selama mode ini
// aktif, key event TIDAK diteruskan sebagai trigger pad biasa.
object KeyBindRecorder {
    var recordingPad by mutableStateOf<Int?>(null)
}
