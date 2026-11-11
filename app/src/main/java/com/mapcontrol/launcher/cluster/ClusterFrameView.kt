package com.mapcontrol.launcher.cluster

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mapcontrol.launcher.cluster.ClusterMapMath.Box
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_H
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_W
import com.mapcontrol.launcher.cluster.ClusterMapMath.Frame

/**
 * Gösterge haritasının "tek katman"ı: haritanın üstünde, dokunulmaz, statik bitmap (boyut, stil ya da ambiyans rengi
 * değişince bir kez hesaplanır). QNX'in sökülemeyen oval vinyetini saklamak yerine onunla aynı dili konuşur:
 * pencere dışı opak zemin (başlık çubuğu dahil), harita kenarlardan zemine erir; üstte ufuk sisi, haritaya düşük alfalı
 * birleştirici ton, alt-ortada iç ışık, QNX üst sırasının altında ortası parlak tek ışık kılavuzu kıl çizgisi
 * (hesap: [ClusterMapMath.overlay], [ClusterMapMath.lineAlpha]).
 * Hareket yalnızca tek seferlik: açılışta siyahtan son hale ~600 ms (ease-out), stil değişiminde ~400 ms çapraz geçiş.
 * Örtme yarım çözünürlükte hesaplanır, süzgeçle büyütülür (4x4 Bayer titreşimi bantlanmayı önler); kıl çizgi tam
 * çözünürlükte vektör çizilir.
 */
internal class ClusterFrameView(context: Context, frame: Frame, private val hole: Box?) : View(context) {

    private var frame: Frame = frame
    private var accent: Int? = null
    private var bitmap: Bitmap? = null
    private var old: Bitmap? = null
    private var linePaint: Paint? = null
    private var oldLinePaint: Paint? = null
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = Color.BLACK }
    private val dst = Rect()

    /** Açılış: 0 = tamamen siyah, 1 = son katman. */
    private var open = 0f
    /** Stil geçişi: 0 = eski bitmap, 1 = yeni. */
    private var cross = 1f
    private var anim: ValueAnimator? = null
    private var opened = false

    fun setFrame(f: Frame) {
        if (f == frame) return
        frame = f
        restyle()
    }

    /** Ambiyans rengi (RGB, okunamıyorsa null): kıl çizgi ve iç ışık ona uyar. */
    fun setAccent(rgb: Int?) {
        if (rgb == accent) return
        accent = rgb
        restyle()
    }

    private fun restyle() {
        if (width <= 0 || height <= 0) return
        val keep = opened && bitmap != null
        if (keep) {
            old?.recycle()
            old = bitmap
            oldLinePaint = linePaint
            bitmap = null
        }
        rebuild(width, height)
        if (keep) fade(ClusterMapMath.STYLE_FADE_MS) { cross = it } else invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild(w, h)
        if (!opened) {
            opened = true
            fade(ClusterMapMath.OPEN_FADE_MS) { open = it }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel()
        anim = null
        bitmap?.recycle()
        bitmap = null
        old?.recycle()
        old = null
    }

    /** Tek seferlik 0→1 geçiş (ease-out); bitince eski bitmap bırakılır. Sürekli animasyon yok. */
    private fun fade(ms: Long, set: (Float) -> Unit) {
        // Önceki geçiş sonlandırılır ama bitiş işleyicisi çalışmaz (yeni "eski" bitmap'i bırakmasın); yarım kalan açılış
        // ya da geçiş son haline atlar.
        anim?.let { it.removeAllListeners(); it.cancel() }
        anim = null
        open = 1f
        cross = 1f
        set(0f)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    set(1f)
                    old?.recycle()
                    old = null
                    oldLinePaint = null
                    if (anim === a) anim = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        blit.alpha = 255
        canvas.drawBitmap(b, null, dst, blit)
        linePaint?.let { drawLine(canvas, it, 255) }
        val o = old
        if (o != null && cross < 1f) {
            val a = Math.round((1f - cross) * 255)
            blit.alpha = a
            canvas.drawBitmap(o, null, dst, blit)
            oldLinePaint?.let { drawLine(canvas, it, a) }
        }
        if (open < 1f) {
            shade.alpha = Math.round((1f - open) * 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        }
    }

    private fun drawLine(c: Canvas, p: Paint, alpha: Int) {
        val sx = width / DESIGN_W.toFloat()
        val sy = height / DESIGN_H.toFloat()
        p.alpha = alpha
        val y = ClusterMapMath.LINE_Y * sy
        c.drawLine((ClusterMapMath.LINE_CX - ClusterMapMath.LINE_HALF) * sx, y,
            (ClusterMapMath.LINE_CX + ClusterMapMath.LINE_HALF) * sx, y, p)
    }

    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val started = System.nanoTime()
        val bw = (w + 1) / 2
        val bh = (h + 1) / 2
        val bmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(ClusterMap.TAG, "katman bitmap'i ayrılamadı ${bw}x$bh")
            return
        }
        val veil = ClusterMapMath.veil(frame)
        bmp.setPixels(paintVeil(bw, bh, veil, hole, accent), 0, bw, 0, 0, bw, bh)
        if (bitmap !== old) bitmap?.recycle()
        bitmap = bmp
        linePaint = linePaint(w, veil, accent)
        Log.i(ClusterMap.TAG, "tek katman çizildi: ${frame.key} ${w}x$h pencere=$hole ambiyans=${accent?.let { Integer.toHexString(it) }} " +
            "(${(System.nanoTime() - started) / 1_000_000} ms)")
    }

    companion object {
        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f)
        private const val LINE_STOPS = 17

        /** Ortası parlak, uçlarda sönen kıl çizgi boyası ([ClusterMapMath.lineAlpha] örneklenir). */
        fun linePaint(w: Int, veil: ClusterMapMath.Veil, accent: Int?): Paint {
            val sx = w / DESIGN_W.toFloat()
            val rgb = accent ?: veil.line
            val x0 = ClusterMapMath.LINE_CX - ClusterMapMath.LINE_HALF
            val colors = IntArray(LINE_STOPS)
            val pos = FloatArray(LINE_STOPS)
            for (i in 0 until LINE_STOPS) {
                val t = i / (LINE_STOPS - 1f)
                val a = ClusterMapMath.lineAlpha(x0 + t * 2 * ClusterMapMath.LINE_HALF, veil)
                colors[i] = (Math.round(a * 255).coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
                pos[i] = t
            }
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = ClusterMapMath.LINE_WIDTH * sx.coerceAtLeast(0.5f)
                shader = LinearGradient(x0 * sx, 0f, (ClusterMapMath.LINE_CX + ClusterMapMath.LINE_HALF) * sx, 0f,
                    colors, pos, Shader.TileMode.CLAMP)
            }
        }

        /** [bw]x[bh] piksel (1920x720 tasarımına ölçekli), premultiplied olmayan ARGB. */
        fun paintVeil(bw: Int, bh: Int, veil: ClusterMapMath.Veil, hole: Box?, accent: Int?): IntArray {
            val px = IntArray(bw * bh)
            val sx = DESIGN_W / bw.toFloat()
            val sy = DESIGN_H / bh.toFloat()
            val m = ClusterMapMath.Mix()
            val opaque = veil.base or 0xFF000000.toInt()
            for (j in 0 until bh) {
                val y = (j + 0.5f) * sy
                val row = j * bw
                for (i in 0 until bw) {
                    if (hole == null) { px[row + i] = opaque; continue }
                    ClusterMapMath.overlay((i + 0.5f) * sx, y, hole, veil, accent, m)
                    if (m.a >= 1f) { px[row + i] = opaque; continue }
                    if (m.a <= 0f) { px[row + i] = 0; continue }
                    val a = (m.a * 255f + BAYER[(j and 3) * 4 + (i and 3)] / 16f).toInt().coerceIn(0, 255)
                    val r = (m.r / m.a * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (m.g / m.a * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (m.b / m.a * 255f + 0.5f).toInt().coerceIn(0, 255)
                    px[row + i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return px
        }
    }
}
