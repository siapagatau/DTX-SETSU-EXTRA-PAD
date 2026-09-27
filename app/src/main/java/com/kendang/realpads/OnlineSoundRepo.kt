package com.kendang.realpads

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sumber suara pad online: repo GitHub publik yang isinya folder KATEGORI, dan
// di dalam tiap folder ada file .wav (bisa juga folder di dalam folder, dibiarin
// bebas). BEDA dari OnlinePresetRepo (yang cuma nge-list file .zip di 1 level
// folder tetap) - repo ini butuh navigasi folder karena kategorinya sendiri
// dinamis: pemilik repo bisa nambah folder kategori baru kapan aja lewat GitHub
// tanpa app ini perlu di-update sama sekali, karena listContents() nanya ulang
// ke GitHub Contents API sesuai path yang lagi dibuka - bukan daftar kategori
// yang di-hardcode di kode (tapi ada CACHE sebentar di sisi app, lihat di
// bawah, biar gak sering-sering nabrak jatah request GitHub).
object OnlineSoundRepo {

    // Ganti 2 baris ini kalau suatu saat pindah owner/nama repo.
    private const val REPO_OWNER = "siapagatau"
    private const val REPO_NAME = "kumpulan-wav-dtx"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // GitHub Contents API buat request TANPA login cuma dijatah 60x per jam per
    // alamat IP - dan jatah itu KEBAGI RAME-RAME sama semua orang yang pakai IP
    // publik yang sama (kartu SIM/provider seluler biasanya numpuk banyak HP di
    // 1 IP lewat CGNAT), jadi kepencet limit bukan berarti app-nya nembak
    // request kebanyakan sendirian. Makanya isi 1 folder yang baru aja diambil
    // disimpan sebentar di memori (TTL di bawah) supaya buka-tutup dialog atau
    // bolak-balik folder yang sama TIDAK nge-hit API lagi selama masih fresh -
    // cuma tombol refresh (ikon muter) yang sengaja MELEWATI cache ini.
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 menit

    private data class CacheEntry(val entries: List<Entry>, val fetchedAtMs: Long)
    private val cache = HashMap<String, CacheEntry>()

    enum class EntryType { DIR, FILE }

    data class Entry(
        val name: String,        // nama file/folder apa adanya
        val path: String,        // path lengkap dari root repo, dipakai buat navigasi lanjut
        val type: EntryType,
        val downloadUrl: String?, // cuma ada buat FILE, siap di-GET/di-stream langsung
        val sizeBytes: Long
    )

    // Dilempar khusus pas kena rate limit GitHub (HTTP 403 + header
    // X-RateLimit-Remaining: 0) - dibedain dari error lain biar dialog bisa
    // nampilin pesan yang jelas & ngasih tau kira-kira kapan boleh coba lagi,
    // BUKAN nampahin mentah-mentah body JSON error dari GitHub ke user.
    class RateLimitException(val resetAtMs: Long) : Exception(
        "Batas request API GitHub abis sementara, coba lagi nanti"
    )

    // Ambil isi 1 folder (path = "" artinya root repo) lewat GitHub Contents API:
    // https://api.github.com/repos/{owner}/{repo}/contents/{path}
    // forceRefresh = true (dipakai tombol refresh) selalu nanya ulang ke GitHub
    // walau masih ada cache yang fresh; selain itu cache dipakai dulu kalau
    // umurnya belum lewat CACHE_TTL_MS, baru nanya ke GitHub kalau belum ada/
    // udah basi.
    //
    // Folder ditaruh duluan (lalu file .wav) dan masing-masing diurutkan A-Z,
    // biar navigasinya rapi & konsisten - gak ngikutin urutan commit GitHub yang
    // dari sudut pandang user kelihatan acak.
    fun listContents(path: String, forceRefresh: Boolean = false): List<Entry> {
        val cleanPath = path.trim('/')
        if (!forceRefresh) {
            val cached = cache[cleanPath]
            if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
                return cached.entries
            }
        }
        val apiUrl = if (cleanPath.isBlank()) {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents"
        } else {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$cleanPath"
        }
        val json = httpGetText(apiUrl)
        val arr = JSONArray(json)
        val dirs = mutableListOf<Entry>()
        val files = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name")
            val entryPath = obj.optString("path")
            when (obj.optString("type")) {
                "dir" -> dirs.add(Entry(name, entryPath, EntryType.DIR, null, 0L))
                "file" -> {
                    if (name.lowercase().endsWith(".wav")) {
                        val downloadUrl = obj.optString("download_url")
                        val size = obj.optLong("size", 0L)
                        if (downloadUrl.isNotBlank()) {
                            files.add(Entry(name, entryPath, EntryType.FILE, downloadUrl, size))
                        }
                    }
                }
            }
        }
        dirs.sortBy { it.name.lowercase() }
        files.sortBy { it.name.lowercase() }
        val result = dirs + files
        cache[cleanPath] = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    // Bikin pesan yang enak dibaca buat ditampilin di dialog pas kena rate
    // limit - jam lokal HP kapan boleh coba lagi + berapa menit lagi kira-kira,
    // dari header X-RateLimit-Reset (epoch detik) yang dikirim GitHub.
    fun formatRateLimitMessage(resetAtMs: Long): String {
        val minutesLeft = ((resetAtMs - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(resetAtMs))
        return "Batas request API GitHub (buat yang belum login) abis sementara, kena limit bareng pengguna lain yang IP-nya sama. Coba lagi sekitar $minutesLeft menit lagi (\u2248 $timeStr)."
    }

    // Download isi 1 file .wav yang dipilih, dipakai pas user beneran nekan
    // "SET" (bukan cuma preview) - hasilnya langsung dioper ke pipeline yang
    // sama persis dengan file lokal (WavLoader.parse -> PresetStorage.saveSample
    // -> AudioEngine.nativeLoadSample), jadi gak ada logic duplikat. Ini lewat
    // raw.githubusercontent.com (bukan api.github.com), yang jatah request-nya
    // TERPISAH dan jauh lebih longgar daripada Contents API di atas, jadi gak
    // ikut mentok pas listContents() lagi kena limit.
    fun downloadWav(downloadUrl: String): ByteArray = httpGetBytes(downloadUrl)

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
