package com.mapcontrol.launcher.cluster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.view.View
import com.mapcontrol.launcher.cluster.ClusterMapMath.Box
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_H
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_W
import com.mapcontrol.launcher.cluster.ClusterMapMath.Frame
import com.mapcontrol.launcher.cluster.ClusterMapMath.Palette

/**
 * Gösterge haritasının çerçevesi: tek, statik bitmap (boyut ya da stil değişince bir kez çizilir; animasyon yok).
 * Pencere ([hole]) şeffaf delik olarak bırakılır: altındaki harita uygulaması görünür; deliğin kenarına iç gölge,
 * yuvarlak köşe ve ince kenar çizgisi çizilir, harita çerçeveye "erir". [hole] null ise yalnızca zemin (görsel modu yok).
 * Çizim 1920x720 tasarım koordinatında; ekran farklıysa ölçeklenir.
 */
internal class ClusterFrameView(context: Context, frame: Frame, private val hole: Box?) : View(context) {

    private var frame: Frame = frame
    private var bitmap: Bitmap? = null
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setFrame(f: Frame) {
        if (f == frame) return
        frame = f
        rebuild(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, blit) }
    }

    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val started = System.nanoTime()
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(ClusterMap.TAG, "çerçeve bitmap'i ayrılamadı ${w}x$h")
            return
        }
        val c = Canvas(bmp)
        c.scale(w / DESIGN_W.toFloat(), h / DESIGN_H.toFloat())
        paintFrame(c, ClusterMapMath.palette(frame), hole)
        bitmap?.recycle()
        bitmap = bmp
        Log.i(ClusterMap.TAG, "çerçeve çizildi: ${frame.key} ${w}x$h pencere=$hole (${(System.nanoTime() - started) / 1_000_000} ms)")
    }

    companion object {
        private val W = DESIGN_W.toFloat()
        private val H = DESIGN_H.toFloat()

        /** Başlık bandının alt kenarı (ortada y=112, uçlarda 92'ye kalkar) ve alt plakanın üst kenarı (ortada 616). */
        private fun headerEdge(p: Path = Path()) = p.apply { moveTo(0f, 92f); quadTo(W / 2f, 132f, W, 92f) }
        private fun plateEdge(p: Path = Path()) = p.apply { moveTo(0f, 628f); quadTo(W / 2f, 604f, W, 628f) }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        /** Ortası parlak, uçlarına doğru sönen yatay çizgi boyası (kenar ışıklı kıl çizgi). */
        private fun hairlinePaint(color: Int, peakAlpha: Int, x0: Float, x1: Float, width: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                shader = LinearGradient(x0, 0f, x1, 0f,
                    intArrayOf(withAlpha(color, 0), withAlpha(color, peakAlpha / 3), withAlpha(color, peakAlpha),
                        withAlpha(color, peakAlpha / 3), withAlpha(color, 0)),
                    floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
            }

        /** Basık (yatay elips) yumuşak ışık lekesi. */
        private fun drawEllipseGlow(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val g = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha / 4), withAlpha(color, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            g.setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) })
            p.shader = g
            c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, p)
        }

        fun paintFrame(c: Canvas, pal: Palette, hole: Box?) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)

            // 1) Zemin + pencere çevresinde füme cam derinliği; kenarlar siyaha düşer (vinyet).
            c.drawColor(pal.base)
            val cx = hole?.let { (it.left + it.right) / 2f } ?: W / 2f
            val cy = hole?.let { (it.top + it.bottom) / 2f } ?: H / 2f
            fill.shader = RadialGradient(cx, cy, 980f, intArrayOf(pal.aura, pal.base, Color.BLACK),
                floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill)
            fill.shader = LinearGradient(0f, 0f, 300f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 300f, H, fill)
            fill.shader = LinearGradient(W, 0f, W - 300f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(W - 300f, 0f, W, H, fill)

            // 2) Üst band: QNX saat/vites/sıcaklık simgelerinin yuvası (sakin grafit), alt kenarında ışık çizgisi.
            val header = Path().apply { moveTo(0f, 0f); lineTo(W, 0f); lineTo(W, 92f); quadTo(W / 2f, 132f, 0f, 92f); close() }
            fill.shader = LinearGradient(0f, 0f, 0f, 112f, pal.headerTop, pal.headerBottom, Shader.TileMode.CLAMP)
            c.drawPath(header, fill)
            fill.shader = LinearGradient(0f, 0f, 0f, 40f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, 40f, fill)
            drawEllipseGlow(c, W / 2f, 114f, 520f, 34f, pal.hairline, 0x16)
            c.drawPath(headerEdge(), hairlinePaint(pal.hairline, 0x9A, 120f, W - 120f, 1.4f))

            // 3) Alt plaka: menzil/sürüş modu/km şeridinin yuvası; üst kenarında ışık çizgisi ve tek sıcak vurgu.
            val plate = Path().apply { moveTo(0f, H); lineTo(0f, 628f); quadTo(W / 2f, 604f, W, 628f); lineTo(W, H); close() }
            fill.shader = LinearGradient(0f, 610f, 0f, H, pal.plateTop, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawPath(plate, fill)
            c.drawPath(plateEdge(), hairlinePaint(pal.hairline, 0x7A, 60f, W - 60f, 1.2f))
            drawEllipseGlow(c, W / 2f, 618f, 230f, 22f, pal.accent, 0x50)
            c.drawPath(plateEdge(), hairlinePaint(pal.accent, 0xF0, W / 2f - 170f, W / 2f + 170f, 2f))

            // 4) Sol hız bloğu (x 200–490, y 440–620) için füme cam yuva: çok hafif dolgu, üstü ışıklı kıl çizgi.
            val pod = Path().apply {
                moveTo(110f, 622f)
                cubicTo(165f, 622f, 170f, 452f, 252f, 448f)
                lineTo(404f, 448f)
                cubicTo(458f, 452f, 452f, 600f, 492f, 616f)
            }
            fill.shader = LinearGradient(0f, 448f, 0f, 622f, withAlpha(pal.hairline, 0x10), withAlpha(pal.hairline, 0x02), Shader.TileMode.CLAMP)
            c.drawPath(Path(pod).apply { close() }, fill)
            c.drawPath(pod, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                shader = LinearGradient(0f, 448f, 0f, 622f, withAlpha(pal.hairline, 0x70), withAlpha(pal.hairline, 0x00), Shader.TileMode.CLAMP)
            })

            if (hole == null) return
            val r = ClusterMapMath.CORNER_PX
            val rect = RectF(hole.left.toFloat(), hole.top.toFloat(), hole.right.toFloat(), hole.bottom.toFloat())

            // 5) Pencere: dış yumuşak ışık, sonra delik (şeffaf: harita görünür), iç gölge, kenar kıl çizgisi.
            c.drawRoundRect(RectF(rect).apply { inset(-3f, -3f) }, r + 3, r + 3, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                color = pal.glow
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            })
            c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
            for (i in 0 until ClusterMapMath.INNER_SHADOW_PX) {
                val a = ClusterMapMath.innerShadowAlpha(pal.innerShadowAlpha, i)
                if (a <= 0) continue
                ring.color = Color.argb(a, 0, 0, 0)
                val inset = i + 0.5f
                c.drawRoundRect(RectF(rect).apply { inset(inset, inset) }, (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), ring)
            }
            c.drawRoundRect(RectF(rect).apply { inset(-0.75f, -0.75f) }, r + 0.75f, r + 0.75f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                shader = LinearGradient(0f, rect.top, 0f, rect.bottom, pal.bezelTop, pal.bezelBottom, Shader.TileMode.CLAMP)
            })
        }
    }
}
