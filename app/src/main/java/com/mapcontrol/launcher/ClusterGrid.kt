package com.mapcontrol.launcher

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager

/** Izgara çizgi/etiket hesabı (saf, JVM'de test edilir). */
internal object ClusterGridMath {
    const val MINOR_STEP = 40
    const val MAJOR_STEP = 200

    /** 0..extent (dahil) her [MINOR_STEP] pikselde bir çizgi konumu. */
    fun lines(extent: Int): List<Int> = (0..extent step MINOR_STEP).toList()

    /** Kalın çizgi mi: [MAJOR_STEP] katı. */
    fun isMajor(pos: Int): Boolean = pos % MAJOR_STEP == 0

    /** Etiketlenecek konumlar: 0..extent arası her [MAJOR_STEP] pikselde bir. */
    fun labels(extent: Int): List<Int> = (0..extent step MAJOR_STEP).toList()
}

/**
 * YALNIZCA DEBUG: gösterge ekranını (Tiggo 7: Android ekran 2, 1920x720 @160dpi) ölçmek için kalibrasyon
 * ızgarası. QNX üstüne kendi katmanlarını/vinyetini çizer, ekran görüntüsü alınamaz; kullanıcı fotoğraflar.
 * Izgara bir kez çizilir (statik): siyah zemin, her 40 px ince gri, her 200 px parlak çizgi + piksel etiketi
 * (üst/sol kenarda ve ortada: x etiketleri y=360, y etiketleri x=960 boyunca), 4 renkli köşe karesi
 * (KS kırmızı, KÜ yeşil, SA mavi, SAĞ-ALT sarı; 40x40), 1 px beyaz dış çerçeve, orta artı, "1920x720 @160 · grid 40px".
 * Birimler gerçek ekran pikselidir (yoğunluk dönüşümü yok).
 *
 * Komutlar (paket com.mapcontrol veya com.mapcontrol.launchertest; debug yapıda, launcher açıkken de çalışır):
 *   adb shell am start -n com.mapcontrol/com.mapcontrol.launcher.HomeLauncherActivity --es launcher_cluster_grid on
 *   adb shell am start -n com.mapcontrol/com.mapcontrol.launcher.HomeLauncherActivity --es launcher_cluster_grid on --ei launcher_cluster_display 2
 *   adb shell am start -n com.mapcontrol/com.mapcontrol.launcher.HomeLauncherActivity --es launcher_cluster_grid off
 * Varsayılan ekran: 1920x720 olan ilk varsayılan-dışı ekran (varsa numara 2). Tüm ekranlar "ClusterGrid" etiketiyle
 * loga yazılır. Presentation reddedilirse FrostNav'ın ClusterDisplayManager/DisplayHelper yolu gibi o ekranın
 * bağlamında TYPE_APPLICATION_OVERLAY penceresi denenir. 10 dakika sonra kendiliğinden kapanır.
 * Emülatörde: adb shell settings put global overlay_display_devices 1920x720/160   (kaldır: ... "")
 */
internal object ClusterGrid {

    const val EXTRA = "launcher_cluster_grid"
    const val EXTRA_DISPLAY = "launcher_cluster_display"
    private const val TAG = "ClusterGrid"
    private const val AUTO_OFF_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var presentation: Presentation? = null
    private var overlayView: View? = null
    private var overlayWm: WindowManager? = null
    private val autoOff = Runnable { dismiss("10 dk doldu") }

    /** Debug kapısı LauncherProbe.applyFrom'da; burada yalnızca komutu işler. */
    fun handle(activity: Activity, mode: String, displayId: Int?) {
        main.post {
            when (mode) {
                "on" -> show(activity, displayId)
                "off" -> dismiss("komut")
                else -> Log.w(TAG, "geçersiz değer: $mode (on|off)")
            }
        }
    }

    private fun pickDisplay(context: Context, forced: Int?): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val all = dm.displays
        for (d in all) {
            val m = DisplayMetrics().also { d.getRealMetrics(it) }
            Log.i(TAG, "ekran id=${d.displayId} ad=${d.name} ${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} flags=0x${Integer.toHexString(d.flags)}")
        }
        if (forced != null) return all.firstOrNull { it.displayId == forced }
        fun is1920(d: Display) = DisplayMetrics().also { d.getRealMetrics(it) }.let { it.widthPixels == 1920 && it.heightPixels == 720 }
        return all.firstOrNull { it.displayId == 2 && it.displayId != Display.DEFAULT_DISPLAY }
            ?: all.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && is1920(it) }
    }

    private fun show(activity: Activity, forced: Int?) {
        dismiss("yeniden")
        val display = pickDisplay(activity, forced)
        if (display == null) {
            Log.w(TAG, "uygun ekran yok (istenen=$forced)")
            return
        }
        try {
            val p = Presentation(activity, display)
            p.setContentView(GridView(p.context))
            p.show()
            presentation = p
            Log.i(TAG, "Presentation gösterildi: ekran ${display.displayId}")
        } catch (e: Throwable) {
            Log.w(TAG, "Presentation reddedildi: ${e.javaClass.simpleName}: ${e.message}; overlay deneniyor")
            presentation = null
            showOverlay(activity, display)
        }
        main.postDelayed(autoOff, AUTO_OFF_MS)
    }

    private fun showOverlay(activity: Activity, display: Display) {
        try {
            val ctx = activity.applicationContext.createDisplayContext(display)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = GridView(ctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            )
            wm.addView(view, lp)
            overlayView = view
            overlayWm = wm
            Log.i(TAG, "Overlay gösterildi: ekran ${display.displayId}")
        } catch (e: Throwable) {
            Log.e(TAG, "Overlay da reddedildi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun dismiss(why: String) {
        main.removeCallbacks(autoOff)
        var did = false
        try {
            presentation?.dismiss(); did = presentation != null
        } catch (e: Throwable) {
            Log.w(TAG, "dismiss hata: ${e.message}")
        }
        presentation = null
        try {
            overlayView?.let { overlayWm?.removeView(it); did = true }
        } catch (e: Throwable) {
            Log.w(TAG, "overlay kaldırma hata: ${e.message}")
        }
        overlayView = null
        overlayWm = null
        if (did) Log.i(TAG, "kapatıldı ($why)")
    }

    private class GridView(context: Context) : View(context) {
        private val minor = Paint().apply { color = Color.rgb(190, 190, 190); strokeWidth = 1f; style = Paint.Style.STROKE }
        private val major = Paint().apply { color = Color.rgb(40, 40, 40); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f; isFakeBoldText = true }
        private val bg = Paint().apply { color = Color.WHITE }

        override fun onDraw(c: Canvas) {
            val w = width
            val h = height
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
            for (x in ClusterGridMath.lines(w)) {
                c.drawLine(x + 0.5f, 0f, x + 0.5f, h.toFloat(), if (ClusterGridMath.isMajor(x)) major else minor)
            }
            for (y in ClusterGridMath.lines(h)) {
                c.drawLine(0f, y + 0.5f, w.toFloat(), y + 0.5f, if (ClusterGridMath.isMajor(y)) major else minor)
            }
            val cx = w / 2f
            val cy = h / 2f
            for (x in ClusterGridMath.labels(w)) {
                val t = x.toString()
                c.drawText(t, x + 3f, 22f, text)
                c.drawText(t, x + 3f, cy - 4f, text)
            }
            for (y in ClusterGridMath.labels(h)) {
                if (y == 0) continue
                val t = y.toString()
                c.drawText(t, 3f, y - 4f, text)
                c.drawText(t, cx + 3f, y - 4f, text)
            }
            val cross = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            c.drawLine(cx - 30f, cy, cx + 30f, cy, cross)
            c.drawLine(cx, cy - 30f, cx, cy + 30f, cross)
            c.drawText("${w}x$h @160 · grid 40px", cx + 40f, cy + 50f, text)
            fun sq(x: Float, y: Float, col: Int) = c.drawRect(x, y, x + 40f, y + 40f, Paint().apply { color = col })
            sq(0f, 0f, Color.RED); sq(w - 40f, 0f, Color.GREEN)
            sq(0f, h - 40f, Color.BLUE); sq(w - 40f, h - 40f, Color.YELLOW)
            val border = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
            c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, border)
        }
    }
}
