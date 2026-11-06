package com.mapcontrol.launcher.cluster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.View
import com.mapcontrol.launcher.cluster.ClusterMapMath.Box
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_H
import com.mapcontrol.launcher.cluster.ClusterMapMath.DESIGN_W
import com.mapcontrol.launcher.cluster.ClusterMapMath.Frame

/**
 * Gösterge haritasının "tek katman"ı: haritanın üstünde, dokunulmaz, tek statik bitmap (boyut ya da stil değişince bir
 * kez hesaplanır; animasyon yok). Çizgi, kutu, ışık yok: yalnızca yumuşak geçişler. Pencere ([hole]) dışı opak zemin
 * (başlık çubuğu dahil), içeride harita kenarlardan zemine erir; gece oval vinyeti, sağ alt yazı köşesi ve üst QNX
 * sırasının altı hafifçe koyulaşır (hesap: [ClusterMapMath.veilCover]). [hole] null ise yalnızca zemin.
 * Geçişler yumuşak olduğundan örtme yarım çözünürlükte hesaplanır, süzgeçle büyütülür; 4x4 Bayer titreşimi bantlanmayı
 * önler.
 */
internal class ClusterFrameView(context: Context, frame: Frame, private val hole: Box?) : View(context) {

    private var frame: Frame = frame
    private var bitmap: Bitmap? = null
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

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
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, blit)
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
        bmp.setPixels(paintVeil(bw, bh, ClusterMapMath.veil(frame), hole), 0, bw, 0, 0, bw, bh)
        bitmap?.recycle()
        bitmap = bmp
        Log.i(ClusterMap.TAG, "tek katman çizildi: ${frame.key} ${w}x$h pencere=$hole (${(System.nanoTime() - started) / 1_000_000} ms)")
    }

    companion object {
        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f)

        /** [bw]x[bh] piksel (1920x720 tasarımına ölçekli) ARGB: zemin rengi, alfa = örtme. */
        fun paintVeil(bw: Int, bh: Int, veil: ClusterMapMath.Veil, hole: Box?): IntArray {
            val px = IntArray(bw * bh)
            val rgb = veil.base and 0x00FFFFFF
            val sx = DESIGN_W / bw.toFloat()
            val sy = DESIGN_H / bh.toFloat()
            for (j in 0 until bh) {
                val y = (j + 0.5f) * sy
                val row = j * bw
                for (i in 0 until bw) {
                    val cover = if (hole == null) 1f else ClusterMapMath.veilCover((i + 0.5f) * sx, y, hole, veil)
                    val a = if (cover >= 1f) 255 else (cover * 255f + BAYER[(j and 3) * 4 + (i and 3)] / 16f).toInt().coerceIn(0, 255)
                    px[row + i] = (a shl 24) or rgb
                }
            }
            return px
        }
    }
}
