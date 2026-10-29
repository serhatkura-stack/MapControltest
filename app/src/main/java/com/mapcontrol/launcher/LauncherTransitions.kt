package com.mapcontrol.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.ArrayList

/**
 * Ana ekran açılırken ilk kare çizilene kadar ertelenebilir işler (hava durumu, yoklamalar).
 * İlk kare çizildikten sonraki ilk Choreographer karesinde çalışır; ondan sonra gelenler hemen (post ile) çalışır.
 */
object LauncherStartup {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Deferred>()
    private var firstFrameDone = false

    class Deferred internal constructor(private val block: () -> Unit) : Runnable {
        @Volatile private var cancelled = false
        fun cancel() { cancelled = true }
        override fun run() { if (!cancelled) block() }
    }

    /** Etkinlik her kurulduğunda çağrılır; ilk çizimden sonra bekleyenleri çalıştırır. */
    fun begin(decor: View) {
        pending.clear()
        firstFrameDone = false
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                Choreographer.getInstance().postFrameCallback {
                    firstFrameDone = true
                    val run = ArrayList(pending)
                    pending.clear()
                    run.forEach { it.run() }
                }
                return true
            }
        })
    }

    /** İlk kareden sonra çalıştırır; dönen nesne ile (örn. onHidden'da) iptal edilebilir. */
    fun later(block: () -> Unit): Deferred {
        val d = Deferred(block)
        if (firstFrameDone) main.post(d) else pending.add(d)
        return d
    }
}

/**
 * Tema değişiminde `recreate()` ağır bir kare üretir (yeni görünüm ağacı). Eski ekranın görüntüsünü tam ekran
 * kaplama olarak tutup yeni etkinlikte yumuşakça söndürerek takılma yerine kısa bir geçiş gösterilir.
 */
object ThemeTransition {
    private const val FADE_MS = 200L
    private var snapshot: Bitmap? = null

    /** Pencerenin görüntüsünü alıp [Activity.recreate] eder; yakalama başarısızsa doğrudan recreate. */
    fun recreate(activity: Activity) {
        val decor = activity.window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0 || !decor.isAttachedToWindow) {
            activity.recreate()
            return
        }
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            activity.recreate()
            return
        }
        try {
            PixelCopy.request(activity.window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) snapshot = bmp
                activity.recreate()
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            activity.recreate()
        }
    }

    /** Yeni etkinlikte: varsa eski görüntüyü kaplama yapar, ilk kare çizilince söndürür. */
    fun playIn(activity: Activity) {
        val bmp = snapshot ?: return
        snapshot = null
        val decor = activity.window.decorView as ViewGroup
        // Tek bitmap çizildiği için alfa doğrudan boyaya uygulanır; ara katman (saveLayer) gerekmez.
        val overlay = object : ImageView(activity) {
            override fun hasOverlappingRendering() = false
        }.apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = true
        }
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                Choreographer.getInstance().postFrameCallback {
                    overlay.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                        decor.removeView(overlay)
                        overlay.setImageDrawable(null)
                        bmp.recycle()
                    }.start()
                }
                return true
            }
        })
    }
}
