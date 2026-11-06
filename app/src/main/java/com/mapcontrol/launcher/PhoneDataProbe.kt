package com.mapcontrol.launcher

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Debug sondası: VDBus rehber/geçmiş alışverişinin her adımı `PhoneSource` etiketiyle günlüğe yazılır ve bir okuma
 * hemen başlatılır. YALNIZCA SAYILAR (olay kimliği, parça sırası, toplam, adet, süre); isim/numara/adres asla.
 * Yalnızca debuggable yapıda:
 *
 *   adb shell am start -n <paket>/com.mapcontrol.launcher.HomeLauncherActivity --ez launcher_phone_data_probe true
 *   adb logcat -s PhoneSource
 *
 * `false` sondayı kapatır. Süreç ölünce kapanır (diske yazılmaz).
 */
internal object PhoneDataProbe {
    const val EXTRA = "launcher_phone_data_probe"
    private const val TAG = "PhoneSource"

    @Volatile
    var enabled: Boolean = false
        private set

    /** Etkinlik açılışında ve onNewIntent'te (ana iş parçacığı). */
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        val on = LauncherCrashGuard.isDebuggable(activity) && intent.getBooleanExtra(EXTRA, false)
        // Aynı istek tekrar işlenmesin (tema geçişinde yeniden kurulum).
        intent.removeExtra(EXTRA)
        enabled = on
        Log.i(TAG, "veri sondası ${if (on) "açık" else "kapalı"}")
        if (on) PhoneSource.probeVdbusData(activity)
    }

    fun log(msg: String) {
        if (enabled) Log.i(TAG, "sonda: $msg")
    }
}
