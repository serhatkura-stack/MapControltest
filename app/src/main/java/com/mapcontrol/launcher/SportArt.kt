package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Spor modu (kronograf dili) renkleri ve çizim yardımcıları. Kırmızı-turuncu yalnız ışık olarak (ibre, kırmızı bölge,
 * dikiş, ilerleme halkası). Spor her temada koyudur (karbon); gündüz/gece ayrımı yok.
 *
 * Bütün durağan çizimler (karbon, kadran yüzleri, çerçeveler, dikişler, yazılar) ya bir kez önbellek bitmap'ine
 * ([SportArt.render]) ya da küçük karo gölgelendiricisiyle çizilir; kare başına bitmap üretimi, bulanıklık ya da gölge yok.
 */
internal object SportInk {
    const val RED = 0xFFEA580C.toInt()
    const val RED_HOT = 0xFFFF7A33.toInt()
    const val STITCH = 0x8CD9541E.toInt()
    const val INK = 0xFFF1F3F6.toInt()
    const val INK_2 = 0xFFC9CED6.toInt()
    const val INK_3 = 0xFF858B94.toInt()
    const val FACE = 0xFF0B0C0E.toInt()
    const val METAL_HI = 0xFFE9ECEF.toInt()
    const val METAL_MID = 0xFF9298A1.toInt()
    const val METAL_LO = 0xFF3B3F45.toInt()
    const val METAL_DEEP = 0xFF1C1E22.toInt()
    const val ICON = 0xFFD9DDE3.toInt()
    const val ICON_ACTIVE = RED_HOT
    const val CARBON_A = 0xFF0C0D0F.toInt()
    const val CARBON_B = 0xFF1B1D21.toInt()
}

internal object SportArt {

    /** Karbon örgü karosu (2 × 2 dimi örgü, [cellPx] hücre): bir kez üretilir, BitmapShader ile döşenir. */
    @Volatile private var tile: Bitmap? = null

    fun carbonTile(cellPx: Int): Bitmap {
        tile?.let { if (it.width == cellPx * 2) return it }
        val c = cellPx.coerceAtLeast(3)
        val bmp = Bitmap.createBitmap(c * 2, c * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0..1) for (j in 0..1) {
            val l = (i * c).toFloat()
            val t = (j * c).toFloat()
            val horizontal = (i + j) % 2 == 0
            p.shader = if (horizontal) LinearGradient(l, t, l, t + c, intArrayOf(SportInk.CARBON_B, SportInk.CARBON_A, 0xFF070809.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            else LinearGradient(l, t, l + c, t, intArrayOf(0xFF151719.toInt(), SportInk.CARBON_A, 0xFF060708.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, l + c, t + c, p)
        }
        tile = bmp
        return bmp
    }

    fun carbonPaint(context: Context, alpha: Int = 255): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(carbonTile(context.dp(5)), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setRotate(45f) })
        }
        this.alpha = alpha
    }

    // --- Kadran parçaları ---

    /** Fırçalanmış metal halka: [r] dış yarıçap, [bw] kalınlık; [bright] 0..1 genel parlaklık. */
    fun bezel(c: Canvas, cx: Float, cy: Float, r: Float, bw: Float, bright: Float = 1f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF040405.toInt()
        c.drawCircle(cx, cy, r, p)
        fun shade(col: Int): Int = scale(col, bright)
        val hi = shade(SportInk.METAL_HI)
        val mid = shade(SportInk.METAL_MID)
        val lo = shade(SportInk.METAL_LO)
        val deep = shade(SportInk.METAL_DEEP)
        p.style = Paint.Style.STROKE
        p.strokeWidth = bw
        p.shader = SweepGradient(cx, cy,
            intArrayOf(mid, hi, mid, lo, deep, lo, mid, hi, mid, lo, deep, lo, mid),
            floatArrayOf(0f, 0.07f, 0.15f, 0.24f, 0.32f, 0.41f, 0.5f, 0.57f, 0.65f, 0.74f, 0.82f, 0.91f, 1f))
        val rm = r - bw / 2f - 0.5f
        c.drawCircle(cx, cy, rm, p)
        p.shader = null
        // Fırça izleri: halka boyunca ince eşmerkezli çizgiler.
        p.strokeWidth = 0.7f
        for (i in 1..5) {
            p.color = if (i % 2 == 0) 0x1AFFFFFF else 0x1F000000
            c.drawCircle(cx, cy, r - 0.5f - bw * i / 6f, p)
        }
        // Dış kenar (koyu) ve iç pah (ince ışık + koyu çizgi).
        p.strokeWidth = 1.2f
        p.color = 0xFF020203.toInt()
        c.drawCircle(cx, cy, r - 0.6f, p)
        p.color = 0x59FFFFFF
        p.strokeWidth = 1f
        c.drawCircle(cx, cy, r - bw - 0.5f, p)
        p.color = 0xFF050506.toInt()
        p.strokeWidth = 1.6f
        c.drawCircle(cx, cy, r - bw - 1.8f, p)
        // Üstten düşen ışık: dış kenarın üst yarısında ince parıltı.
        p.strokeWidth = 1.2f
        p.shader = LinearGradient(0f, cy - r, 0f, cy, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - 1.4f, p)
        p.shader = null
    }

    /** Kadran yüzü: üstten hafif ışık, kenara doğru koyulaşan iç gölge (gradyan; bulanıklık yok). */
    fun face(c: Canvas, cx: Float, cy: Float, rf: Float, lift: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = blend(0xFF1A1C20.toInt(), 0xFF3A3E45.toInt(), lift)
        p.shader = RadialGradient(cx, cy - rf * 0.55f, rf * 1.45f, intArrayOf(top, SportInk.FACE, 0xFF050506.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rf, p)
        p.shader = RadialGradient(cx, cy, rf, intArrayOf(0x00000000, 0x00000000, 0xA6000000.toInt()), floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rf, p)
        p.shader = null
    }

    /** Yüzün kenarında ince kronograf bölüntüsü ([n] çizgi; her 5'te biri uzun). [red] açı aralıklarında kırmızı. */
    fun tickRing(c: Canvas, cx: Float, cy: Float, rOut: Float, n: Int, len: Float, alpha: Int, red: List<ClosedFloatingPointRange<Float>> = emptyList()) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until n) {
            val deg = 360f * i / n
            val major = i % 5 == 0
            val isRed = red.any { deg in it }
            p.color = if (isRed) SportInk.RED else (alpha shl 24) or 0xFFFFFF
            p.strokeWidth = if (major) 2f else 1.1f
            val l = if (major) len * 1.6f else len
            line(c, cx, cy, deg, rOut - l, rOut, p)
        }
    }

    fun line(c: Canvas, cx: Float, cy: Float, deg: Float, r0: Float, r1: Float, p: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, p)
    }

    fun textPaint(context: Context, sizePx: Float, color: Int, weight: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = sizePx
        this.color = color
        typeface = SahneFonts.get(context, weight)
        textAlign = Paint.Align.CENTER
    }

    /** Yazıyı (cx, cy) dikey ortasına yerleştirir. */
    fun centered(c: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    /** Kesik kesik kırmızı dikiş. */
    fun stitchPaint(width: Float, dash: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = SportInk.STITCH
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.75f), 0f)
    }

    fun scale(col: Int, k: Float): Int {
        val a = col ushr 24
        val r = (((col shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = (((col shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((col and 0xFF) * k).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - k)) + (((b shr s) and 0xFF) * k)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // --- Sahne bitmap'i (orta alan) ---

    /**
     * Orta alanın bütün durağan çizimi (saydam zemin; karbon kök zeminden gelir): dikişler, beş/yedi kadranın çerçeveleri,
     * yüzleri, bölüntüleri ve sabit yazıları. Arka planda çağrılabilir (yazı tipleri önceden yüklü olmalı).
     */
    fun render(context: Context, g: SportGeometry): Bitmap {
        val bmp = Bitmap.createBitmap(g.w.coerceAtLeast(1), g.h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        stitches(c, g)
        mediaDial(c, g.media)
        carDial(c, g.car, g)
        g.subs.forEach { subDial(context, c, it, g) }
        rpmDial(context, c, g)
        return bmp
    }

    private fun stitches(c: Canvas, g: SportGeometry) {
        val p = stitchPaint(g.r(2.2f), g.r(9f))
        // Orta küme çevresinde dikişli "kaput": kadranlar üstüne çizildiğinden yalnız aralarda görünür.
        val l = g.x(470f); val r = g.x(1402f); val t = g.y(10f); val b = g.y(478f)
        c.drawRoundRect(RectF(l, t, r, b), g.r(200f), g.r(200f), p)
        // Yan kadranların içe bakan yaylarında ikinci dikiş.
        val m = g.media
        c.drawArc(RectF(m.cx - m.r - g.r(14f), m.cy - m.r - g.r(14f), m.cx + m.r + g.r(14f), m.cy + m.r + g.r(14f)), -62f, 124f, false, p)
        val k = g.car
        c.drawArc(RectF(k.cx - k.r - g.r(14f), k.cy - k.r - g.r(14f), k.cx + k.r + g.r(14f), k.cy + k.r + g.r(14f)), 118f, 124f, false, p)
    }

    private fun mediaDial(c: Canvas, d: SportGeometry.Dial) {
        val bw = d.r * 0.075f
        bezel(c, d.cx, d.cy, d.r, bw)
        val rf = d.r - bw - 2f
        face(c, d.cx, d.cy, rf)
        tickRing(c, d.cx, d.cy, rf - 4f, 120, rf * 0.035f, 0x40, red = listOf(150f..210f, 330f..360f, 0f..30f))
        // İlerleme halkasının izi (dolgu canlı çizilir).
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d.r * SportGeometry.PROGRESS_W; color = 0x1FFFFFFF }
        c.drawCircle(d.cx, d.cy, d.r * SportGeometry.PROGRESS_R, p)
    }

    private fun carDial(c: Canvas, d: SportGeometry.Dial, g: SportGeometry) {
        val bw = d.r * 0.075f
        bezel(c, d.cx, d.cy, d.r, bw, bright = 1.06f)
        val rf = d.r - bw - 2f
        face(c, d.cx, d.cy, rf, lift = 0.55f)
        tickRing(c, d.cx, d.cy, rf - 4f, 120, rf * 0.035f, 0x48, red = listOf(150f..210f, 330f..360f, 0f..30f))
        val carLen = g.carLength()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Zemin gölgesi (aracın altı) ve stop lambası parıltısı (burun yukarı, kuyruk aşağıda).
        p.shader = RadialGradient(d.cx, d.cy, carLen * 0.62f, intArrayOf(0x99000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(d.cx, d.cy, carLen * 0.62f, p)
        val ty = d.cy + carLen * 0.5f
        p.shader = RadialGradient(d.cx, ty, carLen * 0.34f, intArrayOf(0x8CEA580C.toInt(), 0x33EA580C, 0x00EA580C), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1.5f, 0.75f, d.cx, ty)
        c.drawCircle(d.cx, ty, carLen * 0.34f, p)
        c.restore()
        p.shader = null
    }

    private fun subDial(context: Context, c: Canvas, d: SportGeometry.Dial, g: SportGeometry) {
        val bw = d.r * 0.09f
        bezel(c, d.cx, d.cy, d.r, bw, bright = 0.82f)
        val rf = d.r - bw - 2f
        face(c, d.cx, d.cy, rf)
        tickRing(c, d.cx, d.cy, rf - 3f, 60, rf * 0.05f, 0x30)
        val label = textPaint(context, d.r * 0.17f, SportInk.INK_3, 500)
        val unit = textPaint(context, d.r * 0.15f, SportInk.INK_3, 400)
        when (d.kind) {
            SportGeometry.Kind.G -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x33FFFFFF }
                val gr = d.r * SportGeometry.G_RANGE
                c.drawCircle(d.cx, d.cy, gr, p)
                c.drawCircle(d.cx, d.cy, gr / 2f, p)
                c.drawLine(d.cx - gr - 6f, d.cy, d.cx + gr + 6f, d.cy, p)
                c.drawLine(d.cx, d.cy - gr - 6f, d.cx, d.cy + gr + 6f, p)
                centered(c, "G", d.cx, d.cy - d.r * 0.70f, label)
                val small = textPaint(context, d.r * 0.12f, SportInk.INK_3, 400)
                centered(c, "1", d.cx + gr + d.r * 0.12f, d.cy - d.r * 0.10f, small)
                centered(c, "1", d.cx - gr - d.r * 0.12f, d.cy - d.r * 0.10f, small)
            }
            else -> {
                val k = d.kind ?: return
                centered(c, g.label(k), d.cx, d.cy - d.r * 0.44f, label)
                centered(c, g.unit(k), d.cx, d.cy + d.r * 0.42f, unit)
            }
        }
    }

    private fun rpmDial(context: Context, c: Canvas, g: SportGeometry) {
        val d = g.rpm
        val bw = d.r * 0.075f
        bezel(c, d.cx, d.cy, d.r, bw, bright = 1.08f)
        val rf = d.r - bw - 2f
        face(c, d.cx, d.cy, rf, lift = 0.15f)
        val rT = rf - rf * 0.045f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Kırmızı bölge: dış izde ince yay.
        p.style = Paint.Style.STROKE
        val red0 = SportMath.needleDeg(SportMath.RED_ZONE_RPM.toFloat())
        val red1 = SportMath.needleDeg(SportMath.RPM_MAX.toFloat())
        p.strokeWidth = rf * 0.03f
        p.color = SportInk.RED
        val rr = rT + rf * 0.012f
        c.drawArc(RectF(d.cx - rr, d.cy - rr, d.cx + rr, d.cy + rr), red0, red1 - red0, false, p)
        p.style = Paint.Style.FILL
        // Bölüntü: her 100 dev/dk ince, 500 orta, 1000 uzun.
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0..SportMath.RPM_MAX / 100) {
            val rpm = i * 100
            val deg = SportMath.needleDeg(rpm.toFloat())
            val major = rpm % 1000 == 0
            val half = !major && rpm % 500 == 0
            val red = rpm >= SportMath.RED_ZONE_RPM
            p.color = when {
                red -> SportInk.RED
                major -> SportInk.INK
                half -> 0xD9FFFFFF.toInt()
                else -> 0x8CFFFFFF.toInt()
            }
            p.strokeWidth = when { major -> rf * 0.016f; half -> rf * 0.009f; else -> rf * 0.0055f }
            val len = when { major -> rf * 0.11f; half -> rf * 0.065f; else -> rf * 0.038f }
            line(c, d.cx, d.cy, deg, rT - len, rT, p)
        }
        // Rakamlar 0..8 (ince, büyük).
        val num = textPaint(context, rf * 0.165f, SportInk.INK, 300)
        val rn = rf * 0.70f
        for (k in 0..8) {
            val a = Math.toRadians(SportMath.needleDeg(k * 1000f).toDouble())
            centered(c, k.toString(), d.cx + cos(a).toFloat() * rn, d.cy + sin(a).toFloat() * rn, num)
        }
        // Ortadaki etiketler ve iç halka.
        centered(c, "RPM", d.cx, d.cy - rf * 0.36f, textPaint(context, rf * 0.085f, SportInk.INK_2, 500))
        centered(c, "×1000", d.cx, d.cy - rf * 0.26f, textPaint(context, rf * 0.062f, SportInk.INK_3, 400))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x22FFFFFF
        c.drawCircle(d.cx, d.cy, rf * 0.52f, p)
        // Vites penceresi: metal çerçeveli koyu pencere.
        val win = g.gearWindow()
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = 0xFF050506.toInt()
        val wr = win.height() * 0.18f
        c.drawRoundRect(win, wr, wr, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = rf * 0.012f
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom, intArrayOf(SportInk.METAL_HI, SportInk.METAL_LO, SportInk.METAL_MID), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(win, wr, wr, p)
        p.shader = null
        if (!g.hasG) {
            // G-metre yoksa anlık tüketim devir kadranının altında (sabit etiket ve birim).
            val y = g.innerInstantY()
            centered(c, g.label(SportGeometry.Kind.INSTANT), d.cx - rf * 0.27f, y, textPaint(context, rf * 0.065f, SportInk.INK_3, 500))
            centered(c, g.unit(SportGeometry.Kind.INSTANT), d.cx + rf * 0.27f, y, textPaint(context, rf * 0.065f, SportInk.INK_3, 400))
        }
    }

    /** Geçişte kadran çerçevesi parıltısı (yalnızca halka; gradyanla yumuşak kenar). */
    fun glowRing(c: Canvas, cx: Float, cy: Float, r: Float, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val outer = r + width
        val inner = (r - width).coerceAtLeast(1f)
        val start = inner / outer
        val mid = r / outer
        p.shader = RadialGradient(cx, cy, outer, intArrayOf(0x00EA580C, 0x00EA580C, 0xCCFF7A33.toInt(), 0x00EA580C),
            floatArrayOf(0f, start, mid, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, outer, p)
    }

    /** Tırtıllı (knurl) kenar: dış halkaya kısa radyal çizgiler. */
    fun knurl(c: Canvas, cx: Float, cy: Float, r: Float, depth: Float, n: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        for (i in 0 until n) {
            val deg = 360f * i / n
            p.color = if (i % 2 == 0) 0x66000000 else 0x40FFFFFF
            line(c, cx, cy, deg, r - depth, r, p)
        }
    }
}

/** Spor orta alanının kadran yerleşimi (taslak 1872 × 488; ölçek ve ortalama gerçek boyuta göre). */
internal class SportGeometry(val w: Int, val h: Int, val hasG: Boolean) {
    enum class Kind { ZERO100, G, INSTANT, MAX }
    class Dial(val cx: Float, val cy: Float, val r: Float, val kind: Kind? = null)

    val s: Float = min(w / DW, h / DH).coerceAtLeast(0.1f)
    private val ox = (w - DW * s) / 2f
    private val oy = (h - DH * s) / 2f

    fun x(d: Float) = ox + d * s
    fun y(d: Float) = oy + d * s
    fun r(d: Float) = d * s

    val media = Dial(x(212f), y(244f), r(210f))
    val car = Dial(x(1660f), y(244f), r(210f))
    val rpm = Dial(x(936f), y(244f), r(236f))
    val subs: List<Dial> = if (hasG) listOf(
        Dial(x(590f), y(128f), r(104f), Kind.ZERO100),
        Dial(x(590f), y(360f), r(104f), Kind.G),
        Dial(x(1282f), y(128f), r(104f), Kind.INSTANT),
        Dial(x(1282f), y(360f), r(104f), Kind.MAX),
    ) else listOf(
        Dial(x(590f), y(244f), r(112f), Kind.ZERO100),
        Dial(x(1282f), y(244f), r(112f), Kind.MAX),
    )

    /** Kadranlar soldan sağa (geçişteki parıltı sırası). */
    val inOrder: List<Dial> get() = (listOf(media, rpm, car) + subs).sortedBy { it.cx }

    fun sub(kind: Kind): Dial? = subs.firstOrNull { it.kind == kind }

    /** Devir kadranı yüz yarıçapı. */
    val rpmFace: Float get() = rpm.r - rpm.r * 0.075f - 2f

    fun gearWindow(): RectF {
        val rf = rpmFace
        val cx = rpm.cx
        val cy = rpm.cy + rf * 0.42f
        val hw = rf * 0.23f
        val hh = rf * 0.15f
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    fun innerInstantY(): Float = rpm.cy + rpmFace * 0.74f

    /** Araç kadranında aracın gövde boyu (burun yukarı). */
    fun carLength(): Float = (car.r - car.r * 0.075f - 2f) * 1.52f

    fun label(kind: Kind): String = when (kind) {
        Kind.ZERO100 -> "0–100"
        Kind.G -> "G"
        Kind.INSTANT -> "Anlık"
        Kind.MAX -> "Maks"
    }

    fun unit(kind: Kind): String = when (kind) {
        Kind.ZERO100 -> "sn"
        Kind.G -> ""
        Kind.INSTANT -> "L/100"
        Kind.MAX -> "km/sa"
    }

    companion object {
        const val DW = 1872f
        const val DH = 488f
        /** Medya kadranında ilerleme halkası yarıçapı ve kalınlığı (dış yarıçapa oran). */
        const val PROGRESS_R = 0.82f
        const val PROGRESS_W = 0.016f
        /** G-metre: 1 g'nin kadrandaki yarıçapı (dış yarıçapa oran). */
        const val G_RANGE = 0.56f
    }
}

/**
 * Spor modunda kök zemin: döşenmiş karbon, kenarlara doğru koyulaşan. Karbon karosu ve vinyet tek gölgelendiricide
 * birleşir (ComposeShader, çarpma): tam ekran tek geçiş, büyük bitmap yok.
 */
internal class SportGround(context: Context) : Drawable() {
    private val carbon = BitmapShader(SportArt.carbonTile(context.dp(5)), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        setLocalMatrix(Matrix().apply { setRotate(45f) })
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(b: Rect) {
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = maxOf(b.width(), b.height()) * 0.62f
        val vignette = RadialGradient(cx, cy, r, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB3B3B3.toInt(), 0xFF333333.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        paint.shader = android.graphics.ComposeShader(carbon, vignette, android.graphics.PorterDuff.Mode.MULTIPLY)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Talaşlı metal düğme çerçevesi (yuvarlak): fırçalanmış halka, koyu iç yüz; [knurled] ise dış kenarda tırtıl.
 * Sınırların ortasına, kısa kenara sığacak çapta çizilir. Gölgelendiriciler [onBoundsChange]'de bir kez kurulur.
 */
internal class SportKnobDrawable(private val context: Context, private val knurled: Boolean = false, private val insetDp: Float = 2f) : Drawable() {
    private var cache: Bitmap? = null

    override fun onBoundsChange(b: Rect) {
        cache = null
    }

    private fun build(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - context.dpF(insetDp)
        val bw = r * if (knurled) 0.2f else 0.13f
        SportArt.bezel(c, cx, cy, r, bw, bright = 0.92f)
        if (knurled) SportArt.knurl(c, cx, cy, r - 1f, bw * 0.75f, 96)
        SportArt.face(c, cx, cy, r - bw - 1.5f, lift = 0.25f)
        return bmp
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = cache ?: build(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Metal çerçeveli hap / plaka (bağlantı ve hava kapsülü, saat plakası); iç yüz koyu, karbon görünmez. */
internal class SportPillDrawable(private val context: Context, private val radiusDp: Float = 999f, private val frameDp: Float = 2.5f) : Drawable() {
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF040405.toInt() }
    private val rect = RectF()
    private val inner = RectF()
    private var radius = 0f

    override fun onBoundsChange(b: Rect) {
        val fw = context.dpF(frameDp)
        frame.strokeWidth = fw
        rect.set(b.left + fw / 2f, b.top + fw / 2f, b.right - fw / 2f, b.bottom - fw / 2f)
        inner.set(rect.left + fw / 2f + 0.5f, rect.top + fw / 2f + 0.5f, rect.right - fw / 2f - 0.5f, rect.bottom - fw / 2f - 0.5f)
        radius = min(context.dpF(radiusDp), b.height() / 2f)
        frame.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(SportInk.METAL_HI, SportInk.METAL_MID, SportInk.METAL_LO, SportInk.METAL_MID), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), 0xFF1A1C20.toInt(), 0xFF08090A.toInt(), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, frame)
        val ir = (radius - frame.strokeWidth).coerceAtLeast(0f)
        canvas.drawRoundRect(inner, ir, ir, hair)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Spor alt çubuğu: tek karbon panel, fırçalanmış metal çerçeve ve üst kenarda ince kırmızı dikiş. Vida yok.
 * Bir kez önbellek bitmap'ine çizilir (çubuk yeniden çizilse de gradyan/dikiş yeniden hesaplanmaz).
 */
internal class SportPanelDrawable(private val context: Context, private val radiusDp: Float) : Drawable() {
    private var cache: Bitmap? = null

    override fun onBoundsChange(b: Rect) {
        cache = null
    }

    private fun build(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fw = context.dpF(3f)
        val r = min(context.dpF(radiusDp), h / 2f)
        val rect = RectF(fw / 2f, fw / 2f, w - fw / 2f, h - fw / 2f)
        val p = SportArt.carbonPaint(context)
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF08090A.toInt() })
        // Karbonu panelin içine kırp.
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawRect(rect, p)
        c.drawRect(rect, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x1FFFFFFF, 0x00000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        })
        c.restore()
        // Fırçalanmış metal çerçeve: yatay bantlarla.
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = fw
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(SportInk.METAL_LO, SportInk.METAL_HI, SportInk.METAL_MID, SportInk.METAL_LO, SportInk.METAL_MID, SportInk.METAL_HI, SportInk.METAL_LO),
                null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, r, r, frame)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF030304.toInt() }
        val ir = (r - fw).coerceAtLeast(0f)
        c.drawRoundRect(RectF(rect.left + fw / 2f + 0.5f, rect.top + fw / 2f + 0.5f, rect.right - fw / 2f - 0.5f, rect.bottom - fw / 2f - 0.5f), ir, ir, edge)
        // Üst kenarda dikiş.
        val sy = fw + context.dpF(7f)
        c.drawLine(r, sy, w - r, sy, SportArt.stitchPaint(context.dpF(1.6f), context.dpF(7f)))
        return bmp
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = cache ?: build(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Spor yazı tipi: ince kronograf rakamları. */
internal fun sportType(context: Context, weight: Int): Typeface = SahneFonts.get(context, weight)
