package com.mapcontrol.launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import kotlin.math.abs

/**
 * Serbest pencere başlığı (Android 11 `DecorCaptionView`: küçült/kapat simgeli ~42 px şerit) için kendi etkinliklerimizde yedek.
 *
 * Bölünmüş ekran açılırken ([com.mapcontrol.util.SplitScreenLauncher]) üst yarılar başlık kadar yukarıdan başlatılır, başlık durum
 * çubuğunun altında kalır. Sistem bu sınırları kırpıp pencereyi yine durum çubuğunun hemen altına koyarsa başlık görünür kalır;
 * o durumda başlık bu etkinlikte gizlenir, içerik pencerenin tamamını alır. Pencere kenarından boyut değiştirme (sistem işi)
 * etkilenmez; yalnızca başlıktan sürükleyip taşıma ve iki simge gider (bölmeyi FrostNav/Mini gösterge düğmeleri yönetir).
 */
internal object FreeformCaption {

    private const val CAPTION_VIEW = "DecorCaptionView"
    /** Pencere üstünün durum çubuğu altına "denk" sayıldığı pay (px). */
    private const val TOLERANCE_PX = 2

    /**
     * Saf kural (birim testlenir): pencerenin üstü kullanılabilir alanın üstüne (durum çubuğunun altı) denk geliyorsa başlık ekranda
     * görünüyordur ve gizlenir. Daha yukarıdaysa başlık zaten durum çubuğunun altındadır (gizlenirse içerik oraya kayardı); daha
     * aşağıdaysa pencere elle konmuştur, taşıma tutamağı kalsın.
     */
    fun shouldHide(windowTop: Int, usableTop: Int): Boolean = usableTop >= 0 && abs(windowTop - usableTop) <= TOLERANCE_PX

    /** Yerleşimden sonra ve yapılandırma değişince çağrılır; serbest pencere değilse (başlık görünümü yoksa) bir şey yapmaz. */
    fun apply(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val holder = (0 until decor.childCount).map(decor::getChildAt)
            .firstOrNull { it.javaClass.name.endsWith(CAPTION_VIEW) } as? ViewGroup ?: return
        // DecorCaptionView'un ilk çocuğu başlık şeridi (id/caption), ikincisi içerik.
        val caption = holder.getChildAt(0) ?: return
        if (caption.visibility == View.GONE) return
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        if (shouldHide(loc[1], usableTop(activity))) caption.visibility = View.GONE
    }

    /** Durum çubuğunun altı (px); bölünmüş ekranın kullandığı ölçüyle aynı (en büyük pencere ölçüsünün üst inset'i). */
    private fun usableTop(activity: Activity): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return activity.windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                ).top
            } catch (_: Throwable) {
            }
        }
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) activity.resources.getDimensionPixelSize(id) else -1
    }
}
