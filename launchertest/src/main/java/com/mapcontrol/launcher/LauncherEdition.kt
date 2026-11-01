package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo

/**
 * Launcher test sürümü. Varsayılan: salt-okur, araca hiçbir komut gönderilmez, MapControl Ayarlar'ı yoktur.
 * Bu dosya :app'teki LauncherEdition.kt'nin yerini alır; imzalar aynıdır.
 *
 * Ayarlar'daki "Widget'lardan araca yaz (test)" anahtarı AÇIKKEN (varsayılan KAPALI, tercihlerde saklanır) bu sürüm gerçek
 * sürüm gibi davranır: tüm özellik kapıları açılır ve komutlar [WidgetWriter] üzerinden gider. [WidgetWriter] her komutu
 * park kapısından ve beyaz listeden ([WidgetWritePolicy]) geçirir, `WidgetWrite` etiketiyle günlüğe yazar.
 */
internal object LauncherEdition {

    @Volatile
    private var on = false

    /** true iken widget komutları araca gider (kapılar açık, park + beyaz liste şartıyla). */
    val WRITE_TEST: Boolean get() = on

    /** true: araç verisi yalnızca okunur, dokunuşlar komut göndermez. Test anahtarı açıkken false. */
    val READ_ONLY: Boolean get() = !on

    const val HAS_WRITE_TEST = true

    const val HAS_SETTINGS = false

    /** Koltuk soğutma yazması yalnızca test anahtarı açıkken denenir (kodlama doğrulanmadı). */
    val SEAT_VENT_WRITE_VERIFIED: Boolean get() = on

    /**
     * Etkinlik açılırken çağrılır. Anahtar bilerek KAYDEDİLMEZ: yalnızca bu süreç boyunca açık kalır; uygulama ya da
     * kontak yeniden başlayınca hep kapalı açılır (güvenlik).
     */
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setWriteTest(context: Context, value: Boolean) {
        on = value
    }

    fun writeTestTitle(value: Boolean): String = "Widget'lardan araca yaz (test): " + if (value) "Açık" else "Kapalı"

    fun writeTestHint(): String = "Yalnızca Park'ta. Her komut günlüğe yazılır."

    /** Kodlama ısıtmayla aynı (SeatHeatModel.encode). Anahtar kapalıysa hiçbir şey olmaz. */
    fun sendSeatVentLevel(proxy: CarInfoProxy, driver: Boolean, level: Int) {
        if (!on) return
        val id = if (driver) CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION else CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION
        WidgetWriter.send(proxy, VDEventCarInfo.MODULE_CAR_SETTING, id, SeatHeatModel.encode(level))
    }

    fun sendItemValue(proxy: CarInfoProxy, module: Int, id: Int, value: Int) {
        if (!on) return
        WidgetWriter.send(proxy, module, id, value)
    }

    fun setCentralLock(context: Context, locked: Boolean) {
        if (!on) return
        WidgetWriter.sendLock(context, locked)
    }

    @Suppress("UNUSED_PARAMETER")
    fun openSettings(activity: Activity) = Unit
}
