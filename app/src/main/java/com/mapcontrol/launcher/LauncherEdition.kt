package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.mapcontrol.ui.activity.MainActivity
import com.mapcontrol.vehicle.VehicleQuickControls

/**
 * Launcher'ın araca komut gönderen ve MapControl'e bağlanan tek noktası.
 *
 * Salt-okur test uygulaması (`launchertest` modülü) bu dosyanın kendi sürümünü kullanır: orada
 * komutlar hiçbir şey yapmaz ve Ayarlar girişi yoktur. Launcher'ın geri kalanı iki derlemede aynıdır.
 */
internal object LauncherEdition {

    /** true: araç verisi yalnızca okunur, dokunuşlar komut göndermez. */
    const val READ_ONLY = false

    /** Ayarlar (MapControl ana ekranı) bu derlemede var mı. */
    const val HAS_SETTINGS = true

    fun sendItemValue(proxy: CarInfoProxy, module: Int, id: Int, value: Int) {
        proxy.sendItemValue(module, id, value)
    }

    fun setCentralLock(context: Context, locked: Boolean) {
        VehicleQuickControls.getInstance(context).setCentralLock(locked)
    }

    fun openSettings(activity: Activity) {
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
