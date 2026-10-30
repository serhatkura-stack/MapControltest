package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
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

    /** "Yandex -> HUD" köprüsü yalnızca launchertest derlemesindedir. */
    const val HAS_HUD_BRIDGE = false
    @Suppress("UNUSED_PARAMETER") fun hudBridgeEnabled(context: Context) = false
    @Suppress("UNUSED_PARAMETER") fun setHudBridgeEnabled(context: Context, on: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun hudBridgeServiceGranted(context: Context) = false
    @Suppress("UNUSED_PARAMETER") fun hudBridgeForceStraight(context: Context) = false
    @Suppress("UNUSED_PARAMETER") fun toggleHudBridgeForceStraight(context: Context) = false

    /**
     * Koltuk soğutma yazması araçta doğrulanana kadar kapalı (kodlama bilinmiyor; sinyal 93/94 hep [0] okunuyor).
     * Araç testinden sonra true yapılır.
     */
    const val SEAT_VENT_WRITE_VERIFIED = false

    /** Koltuk soğutma seviyesi (0..3). Doğrulanmadığı sürece hiçbir şey göndermez. Kodlama ısıtmayla aynı varsayılır (doğrulanmadı). */
    fun sendSeatVentLevel(proxy: CarInfoProxy, driver: Boolean, level: Int) {
        if (!SEAT_VENT_WRITE_VERIFIED) return
        val id = if (driver) CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION else CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION
        proxy.sendItemValue(VDEventCarInfo.MODULE_CAR_SETTING, id, level.coerceIn(0, 3))
    }

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
