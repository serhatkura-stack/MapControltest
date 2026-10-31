package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Random
import kotlin.math.min

/**
 * Taş-lav (ısıtma) ya da taş-buz (soğutma) plakası: her durum tek bir sabit görüntüdür. (seviye, boyut, tema) için bir kez
 * yazılım Canvas'ında Bitmap'e çizilir ve yalnızca bu anahtar değişince yeniden üretilir; animasyon ve kare başı iş yok.
 * Yalnızca güncel durum tutulur (kutucuk başına tek Bitmap).
 */
internal class SeatStoneView(context: Context, private val vent: Boolean, private val driver: Boolean) : View(context) {

    private var level = 0
    private var bitmap: Bitmap? = null
    private var key = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setLevel(l: Int) {
        if (l == level) return
        level = l
        invalidate()
    }

    /** Tema değişti: bir sonraki çizimde yeni renklerle üretilir. */
    fun themeChanged() = invalidate()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle(); bitmap = null; key = 0
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val k = ((level * 2L + (if (LauncherTheme.night) 1 else 0)) shl 40) or (width.toLong() shl 20) or height.toLong()
        if (bitmap == null || k != key) {
            bitmap?.recycle()
            bitmap = render(width, height, level, LauncherTheme.night, vent, driver, context.dpF(LauncherTheme.CARD_RADIUS_DP), context.dpF(1f))
            key = k
        }
        canvas.drawBitmap(bitmap!!, 0f, 0f, paint)
    }

    companion object {
        /** Taşın açık/koyu olması etiket renginin seçimini belirler (açık taşta koyu yazı). */
        fun lightStone(vent: Boolean, night: Boolean): Boolean = vent || !night

        private fun stoneTop(vent: Boolean, night: Boolean): Int = when {
            vent && night -> 0xFFBCC9D0.toInt()
            vent -> 0xFFE3EBEF.toInt()
            night -> 0xFF262A31.toInt()
            else -> 0xFFDDD5C9.toInt()
        }

        private fun stoneBottom(vent: Boolean, night: Boolean): Int = when {
            vent && night -> 0xFFA5B5BE.toInt()
            vent -> 0xFFD2DDE3.toInt()
            night -> 0xFF15171B.toInt()
            else -> 0xFFC3B9AB.toInt()
        }

        /** Lav/buz renk eğrisi: [t] 0 (kenar) .. 1 (çekirdek). */
        private fun ramp(vent: Boolean, t: Float): Int {
            val stops = if (vent) intArrayOf(0xFF4FA3C7.toInt(), 0xFF7FD6EE.toInt(), 0xFFC9F4FF.toInt(), 0xFFFFFFFF.toInt())
            else intArrayOf(0xFF6E0C0C.toInt(), 0xFFC5260B.toInt(), 0xFFFF7A1A.toInt(), 0xFFFFE066.toInt())
            val f = t.coerceIn(0f, 1f) * (stops.size - 1)
            val i = min(f.toInt(), stops.size - 2)
            return lerp(stops[i], stops[i + 1], f - i)
        }

        private fun lerp(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun render(w: Int, h: Int, level: Int, night: Boolean, vent: Boolean, driver: Boolean, radius: Float, px: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val wf = w.toFloat(); val hf = h.toFloat()
            val seed = SeatStone.seed(vent, driver)
            val rnd = Random(seed + 5)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val clip = Path().apply { addRoundRect(RectF(0f, 0f, wf, hf), radius, radius, Path.Direction.CW) }
            c.clipPath(clip)

            // Taş: dikey ton geçişi ve ince lekeler.
            val top = stoneTop(vent, night); val bottom = stoneBottom(vent, night)
            p.shader = android.graphics.LinearGradient(0f, 0f, wf * 0.3f, hf, top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, wf, hf, p)
            p.shader = null
            val dark = !lightStone(vent, night)
            repeat((w * h / 70).coerceAtMost(4000)) {
                p.color = if (rnd.nextBoolean() == dark) 0x12FFFFFF else 0x16000000
                c.drawCircle(rnd.nextFloat() * wf, rnd.nextFloat() * hf, px * (0.6f + rnd.nextFloat() * 1.3f), p)
            }

            val cracks = SeatStone.cracks(seed, wf, hf)
            val unit = min(wf, hf)
            val baseW = unit * 0.018f * SeatStone.crackWidthScale(level)
            val grooveColor = if (vent) (if (night) 0xFF36424B.toInt() else 0xFF55656F.toInt()) else 0xFF08090B.toInt()
            val glowColor = if (vent) 0xFF5CE1FF.toInt() else if (level >= 2) 0xFFFFA42B.toInt() else 0xFFFF7A1A.toInt()
            fun strokeAll(paint: Paint, scale: Float) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                for (cr in cracks) {
                    val path = Path()
                    cr.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
                    paint.strokeWidth = (baseW * (0.35f + 0.65f * cr.width) * scale).coerceAtLeast(px)
                    c.drawPath(path, paint)
                }
            }
            // Oluk, sonra içindeki ışık (halo + çizgi).
            p.shader = null
            p.color = (grooveColor and 0x00FFFFFF) or (0xD0 shl 24)
            strokeAll(p, 1.5f)
            val glowA = SeatStone.crackGlowAlpha(level)
            if (level > 0) {
                p.color = (glowColor and 0x00FFFFFF) or ((0x70 * glowA).toInt() shl 24)
                p.maskFilter = BlurMaskFilter(unit * 0.04f, BlurMaskFilter.Blur.NORMAL)
                strokeAll(p, 2.4f)
                p.maskFilter = null
            }
            p.color = (glowColor and 0x00FFFFFF) or ((255 * glowA).toInt() shl 24)
            strokeAll(p, if (level == 0) 0.45f else 0.8f)

            // Lav/buz havuzları: toplamsal yarıçap alanı, eşikten sonra renk eğrisi.
            val blobs = SeatStone.blobs(level, cracks, seed, wf, hf)
            if (blobs.isNotEmpty()) pours(c, blobs, w, h, vent, rnd, px)

            // Köşe perdeleri: etiket (sol üst) ve nokta göstergesi (sağ alt) taş üstünde okunsun.
            scrim(c, 0.16f * wf, 0.2f * hf, 0.34f * wf, top)
            scrim(c, 0.9f * wf, 0.86f * hf, 0.2f * wf, bottom)

            // İnce kenar çizgisi (gündüz beyaz kartlar arasında kutu seçilsin).
            p.shader = null; p.maskFilter = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = px
            p.color = if (night) 0x22FFFFFF else 0x30000000
            c.drawRoundRect(RectF(px / 2, px / 2, wf - px / 2, hf - px / 2), radius, radius, p)
            return bmp
        }

        private fun scrim(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            val rgb = color and 0x00FFFFFF
            sp.shader = RadialGradient(cx, cy, r, intArrayOf(rgb or (0xC0 shl 24), rgb or (0x70 shl 24), rgb), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, sp)
        }

        private fun pours(c: Canvas, blobs: List<SeatStone.Blob>, w: Int, h: Int, vent: Boolean, rnd: Random, px: Float) {
            val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val fc = Canvas(field)
            val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            fp.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            for (b in blobs) {
                val big = b.r / 0.55f
                fp.shader = RadialGradient(b.x, b.y, big, intArrayOf(0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                fc.drawCircle(b.x, b.y, big, fp)
            }
            val px4 = IntArray(w * h)
            field.getPixels(px4, 0, w, 0, 0, w, h)
            field.recycle()
            val t0 = 110; val band = 14
            for (i in px4.indices) {
                var a = px4[i] ushr 24
                if (vent && a > 0) a += rnd.nextInt(25) - 12 // buzda tanecikli kenar
                if (a < t0 - band) { px4[i] = 0; continue }
                val depth = ((a - t0).coerceAtLeast(0) / (255f - t0)).coerceIn(0f, 1f)
                val col = ramp(vent, Math.sqrt(depth.toDouble()).toFloat())
                val alpha = ((a - (t0 - band)) / band.toFloat()).coerceIn(0f, 1f)
                px4[i] = (col and 0x00FFFFFF) or ((255 * alpha).toInt() shl 24)
            }
            val lava = Bitmap.createBitmap(px4, w, h, Bitmap.Config.ARGB_8888)
            val lp = Paint(Paint.FILTER_BITMAP_FLAG)
            // Dış parlama (yumuşak), sonra keskin kütle.
            lp.maskFilter = BlurMaskFilter(min(w, h) * 0.05f, BlurMaskFilter.Blur.NORMAL)
            lp.alpha = if (vent) 120 else 170
            c.drawBitmap(lava, 0f, 0f, lp)
            lp.maskFilter = null; lp.alpha = 255
            c.drawBitmap(lava, 0f, 0f, lp)
            lava.recycle()
        }
    }
}
