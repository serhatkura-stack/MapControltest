package com.mapcontrol.launcher.cluster

import android.util.Log
import android.view.View
import android.view.WindowManager
import com.mapcontrol.launcher.LLog

/**
 * Ek ekranlara (gösterge, display 2) eklenen overlay pencerelerinin tek kaydı.
 *
 * Resmi FrostNav 1.4.6, "Göstergeye yansıt" tekrarlandıkça display 2'de 13–25 APPLICATION_OVERLAY penceresi biriktirdi
 * (6 Kasım bugreport; hepsi GPU ile birleştirildi, surfaceflinger %31–35). Kural: amaç başına tek pencere.
 *  - [add]: aynı amaçla kayıtlı başka bir görünüm hâlâ ekliyse önce o kaldırılır (uyarı yazılır), sonra yenisi eklenir;
 *    aynı görünüm yeniden eklenirse (z-sırası yükseltme) kayıt korunur.
 *  - Kayıttan düşme, görünüm pencereden ayrılınca kendiliğinden olur (her kaldırma yolu kapsanır).
 *  - Her değişimde canlı sayı günlüğe yazılır (debug); [MAX_EXPECTED]'i aşarsa her derlemede uyarı.
 * Ana iş parçacığından kullanılır.
 */
object OverlayWindows {
    const val TAG = "ClusterWindows"

    /** Aynı anda beklenen en çok pencere (splash/hazırlanıyor + kart + çerçeve + görsel). */
    const val MAX_EXPECTED = 4

    private class Entry(val view: View, val wm: WindowManager, val listener: View.OnAttachStateChangeListener)

    private val live = LinkedHashMap<String, Entry>()

    /** Canlı (kayıtlı) pencere sayısı. */
    @JvmStatic
    val liveCount: Int get() { prune(); return live.size }

    @JvmStatic
    fun isLive(purpose: String): Boolean = live.containsKey(purpose)

    /** [wm].addView yerine; hata olursa fırlatır (çağıran eski yolundaki gibi yakalar). */
    @JvmStatic
    fun add(purpose: String, wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        prune()
        val old = live[purpose]
        if (old != null && old.view !== view) {
            Log.w(TAG, "$purpose: eski pencere hâlâ ekliydi, kaldırılıyor (sızıntı önlendi)")
            drop(purpose, old)
            try {
                old.wm.removeViewImmediate(old.view)
            } catch (e: Throwable) {
                // zaten ayrılmış
            }
        }
        wm.addView(view, lp)
        if (live[purpose]?.view === view) {
            changed("+$purpose (yeniden)")
            return
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // Anahtar değişmiş olabilir (retire): görünüm kimliğiyle aranır.
                val key = live.entries.firstOrNull { it.value.view === v }?.key ?: return
                drop(key, live.getValue(key))
                changed("-$key")
            }
        }
        view.addOnAttachStateChangeListener(listener)
        live[purpose] = Entry(view, wm, listener)
        changed("+$purpose")
    }

    private var retireSeq = 0

    /**
     * Solarak çıkan pencere: amaç anahtarı boşaltılır (aynı amaçla yeni pencere uyarısız eklenebilir), görünüm
     * ayrılana kadar "amaç~çıkıyor" adıyla sayılmaya devam eder.
     */
    @JvmStatic
    fun retire(purpose: String, view: View) {
        val e = live[purpose] ?: return
        if (e.view !== view) return
        live.remove(purpose)
        live["$purpose~cikiyor#${++retireSeq}"] = e
    }

    /** Amaçtaki pencereyi hemen kaldırır (varsa). */
    @JvmStatic
    fun remove(purpose: String) {
        val e = live[purpose] ?: return
        drop(purpose, e)
        try {
            e.wm.removeViewImmediate(e.view)
        } catch (t: Throwable) {
        }
        changed("-$purpose")
    }

    private fun drop(purpose: String, e: Entry) {
        live.remove(purpose)
        e.view.removeOnAttachStateChangeListener(e.listener)
    }

    /** Hiç bağlanmadan kaldırılan görünümler ayrılma geri çağrısı almaz; ebeveyni (ViewRootImpl) yoksa kayıttan düşer. */
    private fun prune() {
        val gone = live.entries.filter { it.value.view.parent == null && !it.value.view.isAttachedToWindow }
        gone.forEach { drop(it.key, it.value) }
    }

    private fun changed(what: String) {
        prune()
        val n = live.size
        if (n > MAX_EXPECTED) Log.w(TAG, "$what canlı=$n > $MAX_EXPECTED: ${live.keys}")
        else LLog.i(TAG, "$what canlı=$n ${live.keys}")
    }
}
