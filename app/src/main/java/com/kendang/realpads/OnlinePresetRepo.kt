package com.kendang.realpads

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sumber preset online: repo GitHub publik yang isinya file .zip preset kendang
// (format .zip-nya sama persis kayak hasil tombol EXPORT di app ini: entry
// pad1.wav, pad2.wav, dst - atau pad1.mp3/m4a/dll, karena PresetStorage.importPreset
// udah bisa handle keduanya). Sengaja TIDAK butuh index.html/file manifest apapun
// di sisi repo - GitHub sendiri sudah nyediain REST API publik yang otomatis
// nge-list SEMUA file di sebuah folder repo dalam format JSON, jadi begitu ada
// yang upload .zip baru ke repo, app ini langsung "lihat" tanpa perlu update
// apa-apa lagi di sisi repo maupun di sisi app.
object OnlinePresetRepo {

    // Ganti 2 baris ini kalau suatu saat pindah owner/nama repo atau mau nunjuk
    // ke folder tertentu di dalam repo (bukan root).
    private const val REPO_OWNER = "siapagatau"
    private const val REPO_NAME = "preset-kendang-dtx-android"
    private const val REPO_PATH = "" // kosong = root repo

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // Sama kayak OnlineSoundRepo: GitHub Contents API buat request TANPA login
    // cuma dijatah 60x per jam per alamat IP - dan jatah itu KEBAGI RAME-RAME
    // sama semua orang yang pakai IP publik yang sama (CGNAT operator seluler
    // numpuk banyak HP di 1 IP). Dulu tiap dialog "Online dari GitHub" dibuka
    // langsung nembak API baru tanpa cache sama sekali, jadi user yang
    // gampang-gampang buka/tutup dialog ini bisa ngabisin jatah sendirian.
    // Sekarang hasilnya disimpan sebentar di memori (TTL di bawah) - cuma
    // tombol refresh (ikon muter) yang sengaja MELEWATI cache ini.
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 menit

    private data class CacheEntry(val presets: List<OnlinePreset>, val fetchedAtMs: Long)
    @Volatile private var cache: CacheEntry? = null

    data class OnlinePreset(
        val name: String,        // nama file apa adanya, mis. "Kendang Sunda Kasar.zip"
        val downloadUrl: String, // URL langsung ke raw file, siap di-GET
        val sizeBytes: Long
    )

    // Dilempar khusus pas kena rate limit GitHub (HTTP 403 + header
    // X-RateLimit-Remaining: 0) - dibedain dari error lain biar dialog bisa
    // nampilin pesan yang jelas & ngasih tau kira-kira kapan boleh coba lagi,
    // BUKAN nampahin mentah-mentah body JSON error dari GitHub ke user.
    class RateLimitException(val resetAtMs: Long) : Exception(
        "Batas request API GitHub abis sementara, coba lagi nanti"
    )

    // Ambil daftar semua file .zip yang ada di repo lewat GitHub Contents API:
    // https://api.github.com/repos/{owner}/{repo}/contents/{path}
    // Endpoint ini publik (gak butuh token) buat repo publik. forceRefresh =
    // true (dipakai tombol refresh) selalu nanya ulang ke GitHub walau masih
    // ada cache yang fresh; selain itu cache dipakai dulu kalau umurnya belum
    // lewat CACHE_TTL_MS, baru nanya ke GitHub kalau belum ada/udah basi.
    // Lempar Exception kalau gagal (network mati, repo gak ketemu, dll) - biar
    // pemanggil (UI) yang nentuin pesan errornya sendiri lewat try/catch.
    fun listPresets(forceRefresh: Boolean = false): List<OnlinePreset> {
        if (!forceRefresh) {
            val cached = cache
            if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
                return cached.presets
            }
        }
        val path = if (REPO_PATH.isBlank()) "" else "/$REPO_PATH"
        val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents$path"
        val json = httpGetText(apiUrl)
        val arr = JSONArray(json)
        val result = mutableListOf<OnlinePreset>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type")
            val name = obj.optString("name")
            if (type == "file" && name.lowercase().endsWith(".zip")) {
                val downloadUrl = obj.optString("download_url")
                val size = obj.optLong("size", 0L)
                if (downloadUrl.isNotBlank()) {
                    result.add(OnlinePreset(name, downloadUrl, size))
                }
            }
        }
        // Urutkan A-Z biar daftarnya rapi & konsisten tiap dibuka, gak ngikutin
        // urutan upload commit di GitHub yang random dari sudut pandang user.
        val sorted = result.sortedBy { it.name.lowercase() }
        cache = CacheEntry(sorted, System.currentTimeMillis())
        return sorted
    }

    // Bikin pesan yang enak dibaca buat ditampilin di dialog pas kena rate
    // limit - jam lokal HP kapan boleh coba lagi + berapa menit lagi kira-kira,
    // dari header X-RateLimit-Reset (epoch detik) yang dikirim GitHub. Sama
    // persis dengan OnlineSoundRepo.formatRateLimitMessage().
    fun formatRateLimitMessage(resetAtMs: Long): String {
        val minutesLeft = ((resetAtMs - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(resetAtMs))
        return "Batas request API GitHub (buat yang belum login) abis sementara, kena limit bareng pengguna lain yang IP-nya sama. Coba lagi sekitar $minutesLeft menit lagi (\u2248 $timeStr)."
    }

    // Download isi file .zip preset yang dipilih, langsung sebagai ByteArray -
    // dipanggil dari IO thread lalu byte-nya dioper ke
    // PresetStorage.importPreset(..., ByteArrayInputStream(bytes)) persis kayak
    // alur import file lokal biasa, jadi gak ada logic duplikat sama sekali.
    fun downloadZip(downloadUrl: String): ByteArray = httpGetBytes(downloadUrl)

    private fun httpGetText(urlStr: String): String = String(httpGetBytes(urlStr, acceptJson = true), Charsets.UTF_8)

    private fun httpGetBytes(urlStr: String, acceptJson: Boolean = false): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            // User-Agent WAJIB diisi buat GitHub API - request tanpa User-Agent
            // ditolak (403) oleh server GitHub.
            conn.setRequestProperty("User-Agent", "DTX-Android-App")
            if (acceptJson) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Rate limit GitHub selalu balas 403 + header X-RateLimit-Remaining
                // "0" (beda dari 403 biasa yang gak ada header ini) - dipisah dulu
                // di sini sebelum dibaca sebagai error umum, biar pesannya ke user
                // bisa jauh lebih jelas daripada nampilin body JSON mentah.
                if (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0") {
                    val resetSec = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val resetAtMs = if (resetSec != null) resetSec * 1000L else System.currentTimeMillis() + 60_000L
                    throw RateLimitException(resetAtMs)
                }
                val errBody = try {
                    conn.errorStream?.readBytes()?.let { String(it, Charsets.UTF_8) }
                } catch (e: Exception) { null }
                throw Exception("HTTP $code${if (errBody != null) ": $errBody" else ""}")
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
