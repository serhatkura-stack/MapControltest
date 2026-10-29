package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.WindowInsets
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

/**
 * Chery Tiggo 7 Desay ünitesinde OEM alt çubuğu (ana ekran/uygulamalar/araç/sıcaklık/fan/ayar/telefon)
 * uygulamaların ÜSTÜNE çizilen bir katmandır; Android bunun için inset bildirmez. Bu yüzden launcher'ın
 * alt çubuğu ve ızgarası bu yüksekliğin üstünde kalmalıdır.
 *
 * Kesin yükseklik henüz ölçülmedi: araç testi tur 2'de `dumpsys window` ile ölçülüp [OEM_BAR_FALLBACK_PX]
 * güncellenecek. Şimdilik 120 px (gözlenen ~100-120 px aralığının üst ucu).
 */
object LauncherSafeArea {

    const val OEM_BAR_FALLBACK_PX = 120
    private const val DESAY_PACKAGE = "com.desaysv.vehiclesetting"
    private const val EXTRA_OEM_INSET = "launcher_oem_inset"

    /** Yalnızca debug yapıda, emülatörde OEM çubuğunu taklit etmek için (`--ei launcher_oem_inset 120`). */
    private var debugOverridePx: Int? = null

    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA_OEM_INSET) != true) return
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        debugOverridePx = if (debuggable) intent.getIntExtra(EXTRA_OEM_INSET, 0).coerceAtLeast(0) else null
    }

    /** Desay ünitesi mi: ucuz ve sağlam bir paket varlığı kontrolü. */
    fun isDesayUnit(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DESAY_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** OEM katmanı için ayrılacak alt boşluk (px); Desay dışında 0. */
    fun oemInsetPx(context: Context): Int =
        debugOverridePx ?: if (isDesayUnit(context)) OEM_BAR_FALLBACK_PX else 0

    /** Alt güvenli alan = max(sistem gezinme çubuğu inset'i, OEM yedeği). */
    fun bottomInsetPx(context: Context, insets: WindowInsets?): Int {
        val system = when {
            insets == null -> 0
            Build.VERSION.SDK_INT >= 30 -> insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else -> @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        return maxOf(system, oemInsetPx(context))
    }

    /**
     * Sürüş kilidi: hız > 0 ise hareket var. Araç servisine bağlıyken hız henüz okunmadıysa da
     * kilitli sayılır; bağlı değilse (emülatör, servis yok) park kabul edilir. Demo her zaman park.
     */
    fun isMoving(snapshot: VehicleMetricsSnapshot, threshold: Int = 0): Boolean {
        if (LauncherDemo.enabled) return false
        if (snapshot.preferredSpeed() > threshold) return true
        val unread = !VehicleMetricsSnapshot.isValid(snapshot.displaySpeed) && !VehicleMetricsSnapshot.isValid(snapshot.actualSpeed)
        return snapshot.connected && unread && threshold == 0
    }
}
