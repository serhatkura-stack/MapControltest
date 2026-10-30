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
 * yaklaşık 105 px yüksekliğindedir. Araç fotoğrafı (2026-10-30): launcher penceresi bu çubuğun ÜSTÜNDE
 * bitiyor; Android pencereyi kendisi kısaltıyor/inset bildiriyor, bizim alt çubuğumuz OEM çubuğunun hemen
 * üstüne oturuyor. Bu yüzden gerçek cihazda ek bir yedek boşluk UYGULANMAZ (yalnızca sistemin bildirdiği inset).
 *
 * Emülatörde aynı düzeni denemek için yalnızca debug yapıda `--ei launcher_oem_inset <px>` verilir: bu hem
 * alt bandı ayırır, alt çubuğumuzu kaldırır hem de OEM çubuğunun görsel taklidini ([OemBarMock]) çizer.
 */
object LauncherSafeArea {

    private const val DESAY_PACKAGE = "com.desaysv.vehiclesetting"
    private const val EXTRA_OEM_INSET = "launcher_oem_inset"

    /** Yalnızca debug yapıda, emülatörde OEM çubuğunu taklit etmek için (`--ei launcher_oem_inset 120`). */
    private var debugOverridePx: Int? = null

    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA_OEM_INSET) != true) return
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        debugOverridePx = if (debuggable) intent.getIntExtra(EXTRA_OEM_INSET, 0).coerceAtLeast(0) else null
    }

    /** Debug yapıda `launcher_oem_inset` ekstrasıyla taklit edilen çubuk mu (gerçek Desay tespitinde false). */
    fun isMocked(): Boolean = (debugOverridePx ?: 0) > 0

    /** Mock çubuğu çizilecek mi: tam ekranda gizlenir (emülatör gerçek aracı taklit eder). */
    fun showsMockBar(context: Context): Boolean = isMocked() && !LauncherFullscreen.enabled(context)

    /** Desay ünitesi mi: ucuz ve sağlam bir paket varlığı kontrolü (yalnızca bilgi; yerleşimi değiştirmez). */
    fun isDesayUnit(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DESAY_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * OEM alt çubuğu olan cihaz: gerçek Desay ünitesi ya da debug simülasyonu. Bizim alt çubuğumuz kaldırılır
     * (uygulamalar birleşik 1×1 kutucuk, koltuk ısıtma kutucuk) ve Desay varsayılan düzeni kullanılır.
     */
    fun hasOemBar(context: Context): Boolean = (debugOverridePx ?: 0) > 0 || isDesayUnit(context)

    /** Debug simülasyonunun ayırdığı OEM alt bandı (px); gerçek cihazda her zaman 0. */
    @Suppress("UNUSED_PARAMETER")
    fun oemInsetPx(context: Context): Int = if (LauncherFullscreen.enabled(context)) 0 else debugOverridePx ?: 0

    /** Alt güvenli alan = max(sistem gezinme çubuğu inset'i, debug OEM simülasyonu). */
    fun bottomInsetPx(context: Context, insets: WindowInsets?): Int {
        val system = when {
            // Tam ekran: gezinme çubuğu gizli; geçici gösterim içeriği itmesin, ızgara 720 px'in tamamını alır.
            LauncherFullscreen.enabled(context) -> 0
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
