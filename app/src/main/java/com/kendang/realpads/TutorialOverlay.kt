package com.kendang.realpads

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TUTORIAL_PREFS = "realpads_tutorial"
private const val KEY_SEEN_INTRO = "seen_intro_v1"

// Nyimpen status "udah pernah lihat tutorial pengenalan" - simpel, cuma 1 flag
// boolean di SharedPreferences (pola sama kayak state persist lain di app ini),
// biar tutorialnya cuma nongol SEKALI aja pas pertama kali user buka app (sampai
// user clear data/uninstall-reinstall), gak ganggu tiap kali app dibuka ulang.
object TutorialPrefs {
    fun hasSeenIntro(context: Context): Boolean =
        context.getSharedPreferences(TUTORIAL_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEN_INTRO, false)

    fun markIntroSeen(context: Context) {
        context.getSharedPreferences(TUTORIAL_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN_INTRO, true)
            .apply()
    }
}

// Overlay tutorial pengenalan pas pertama kali masuk app: nge-"spotlight" tombol
// LOAD (bikin lubang terang persis di kotak tombolnya di tengah scrim gelap),
// dikasih border oren yang "napas" + panah mantul nunjuk ke situ, plus kartu
// penjelasan singkat kenapa harus mulai dari situ (pencet LOAD -> pilih
// "Online dari GitHub") dan tombol "OK, MENGERTI" buat nutup + nandain
// tutorial udah dilihat.
//
// targetBoundsInRoot = posisi tombol LOAD yang asli (diukur MainActivity lewat
// onGloballyPositioned di tombolnya sendiri, dalam koordinat ROOT compose tree -
// bukan koordinat lokal Box manapun, jadi konsisten dipakai di sini walau
// overlay ini nested beberapa level di dalam Box lain). Kalau null (misal belum
// sempet ke-measure di frame pertama), overlay tetap tampil tapi tanpa
// spotlight/panah - cuma kartu penjelasan di tengah layar - biar tutorialnya
// gak pernah "kosong tanpa isi" walau timing pengukuran meleset.
@Composable
fun TutorialOverlay(
    targetBoundsInRoot: Rect?,
    onConfirm: () -> Unit
) {
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    val infinite = rememberInfiniteTransition(label = "tutorial_infinite")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tutorial_pulse_alpha"
    )
    val bounce by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tutorial_bounce"
    )

    val accent = ThemeState.accentColor

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            // Telen semua tap di overlay ini (jangan tembus ke tombol di
            // baliknya) - user WAJIB nekan "OK, MENGERTI" buat lanjut, biar
            // gak ada yang nutup tutorial gak sengaja pas asal nge-tap layar.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // Konversi posisi tombol LOAD dari koordinat root ke koordinat LOKAL
        // Box overlay ini sendiri (overlayOrigin), biar semua penggambaran di
        // bawah (Canvas, offset arrow/card) bisa pakai angka yang langsung
        // relevan sama isi Box ini.
        val localTarget = targetBoundsInRoot?.let { r ->
            Rect(
                left = r.left - overlayOrigin.x,
                top = r.top - overlayOrigin.y,
                right = r.right - overlayOrigin.x,
                bottom = r.bottom - overlayOrigin.y
            )
        }
        val padPx = with(density) { 10.dp.toPx() }
        val cornerPx = with(density) { 16.dp.toPx() }

        // Layer 1: scrim gelap penuh layar + "lubang" terang persis di kotak
        // tombol LOAD (di-inflate dikit pakai padPx biar gak mepet banget).
        // graphicsLayer + CompositingStrategy.Offscreen WAJIB ada di sini -
        // tanpa itu, BlendMode.Clear bakal nge-clear ke transparan-nya LAYAR
        // ASLI di belakang Canvas (bukan cuma nge-clear rect gelap yang baru
        // digambar di ATASNYA), yang bikin lubangnya malah nembus ke hitam.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.72f))
            if (localTarget != null) {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(localTarget.left - padPx, localTarget.top - padPx),
                    size = Size(localTarget.width + padPx * 2, localTarget.height + padPx * 2),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    blendMode = BlendMode.Clear
                )
            }
        }

        if (localTarget != null) {
            // Layer 2: border aksen "napas" ngelilingin lubang spotlight - biar
            // makin ketara tombol mana yang dimaksud, gak cuma ngandelin lubang
            // terangnya doang.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = accent.copy(alpha = pulseAlpha),
                    topLeft = Offset(localTarget.left - padPx, localTarget.top - padPx),
                    size = Size(localTarget.width + padPx * 2, localTarget.height + padPx * 2),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = with(density) { 3.dp.toPx() })
                )
            }

            // Panah kecil yang mantul (naik-turun tipis) tepat di atas tombol,
            // nunjuk ke bawah ke arah lubang spotlight.
            val arrowSize = 28.dp
            val arrowGap = 8.dp
            val arrowLeftDp = with(density) { (localTarget.left + localTarget.width / 2f).toDp() } - arrowSize / 2
            val arrowTopDp = with(density) { localTarget.top.toDp() } - arrowGap - arrowSize - bounce.dp
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Tunjuk ke tombol LOAD",
                tint = accent,
                modifier = Modifier
                    .size(arrowSize)
                    .offset(x = arrowLeftDp, y = arrowTopDp)
            )

            // Kartu penjelasan: lebarnya DIBATASI (cardMaxWidth) supaya teksnya
            // pecah jadi beberapa baris dan kartunya kelihatan seperti tooltip
            // yang pas ukurannya - bukan melebar penuh 1 layar cuma gara-gara
            // 1 kalimat panjang gak ke-wrap. Sisi horizontalnya juga NEMPEL ke
            // sisi tombol LOAD (kanan/kiri, tergantung tombolnya ada di
            // separuh layar mana) alih-alih dipaksa ke tengah layar, biar
            // jelas keliatan "milik" tombol yang di-highlight, bukan
            // melayang gak jelas. Posisi vertikalnya (align BottomStart/
            // BottomEnd + padding bawah) sama seperti sebelumnya: dihitung dari
            // jarak "atas tombol ke bawah layar" biar otomatis pas nempel di
            // atas panah berapapun tinggi isi kartunya sendiri.
            val cardMaxWidth = 320.dp
            val cardBottomPadding = maxHeight -
                with(density) { localTarget.top.toDp() } +
                arrowGap + arrowSize + 6.dp
            val targetCenterXDp = with(density) { (localTarget.left + localTarget.width / 2f).toDp() }
            val cardSideMargin = 16.dp
            if (targetCenterXDp > maxWidth / 2) {
                // Tombol ada di separuh KANAN layar -> kartu nempel rata kanan,
                // SEJAJAR PERSIS ke tepi luar cincin spotlight-nya (target.right +
                // padPx, bukan cuma tepi tombol mentahnya) - biar keliatan
                // menempel pas ke border, bukan ada celah kosong di antaranya.
                val endPadding = (maxWidth - with(density) { (localTarget.right + padPx).toDp() })
                    .coerceAtLeast(4.dp)
                TutorialCard(
                    accent = accent,
                    onConfirm = onConfirm,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .widthIn(max = cardMaxWidth)
                        .padding(bottom = cardBottomPadding, end = endPadding, start = cardSideMargin)
                )
            } else {
                // Tombol ada di separuh KIRI layar -> kartu nempel rata kiri,
                // sejajar tepi luar cincin spotlight-nya juga (target.left - padPx).
                val startPadding = with(density) { (localTarget.left - padPx).toDp() }
                    .coerceAtLeast(4.dp)
                TutorialCard(
                    accent = accent,
                    onConfirm = onConfirm,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = cardMaxWidth)
                        .padding(bottom = cardBottomPadding, start = startPadding, end = cardSideMargin)
                )
            }
        } else {
            // Fallback: posisi tombol LOAD belum sempet ke-measure (jarang,
            // cuma mungkin di frame paling pertama) - tampilin kartu di tengah
            // layar tanpa spotlight/panah, biar tutorial tetap ada isinya dan
            // tetap bisa ditutup lewat tombol OK.
            TutorialCard(
                accent = accent,
                onConfirm = onConfirm,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun TutorialCard(
    accent: Color,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp), clip = false)
            .background(PanelColor, RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.TouchApp,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "MULAI DARI SINI",
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tekan tombol LOAD, lalu pilih \"Online dari GitHub\" untuk memuat preset yang sudah tersedia.",
            color = PadTextColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = bestTextColorFor(accent)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OK, MENGERTI", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
        }
    }
}
