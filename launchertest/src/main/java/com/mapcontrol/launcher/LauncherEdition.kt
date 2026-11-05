package com.mapcontrol.launcher

import android.app.Activity
import android.content.Intent
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
 * beyaz listeden ([WidgetWritePolicy]) geçirir (sürüş kilidi yok), `WidgetWrite` etiketiyle günlüğe yazar.
 */
internal object LauncherEdition {

    @Volatile
    private var on = false

    /** true iken widget komutları araca gider (kapılar açık, beyaz liste şartıyla). */
    val WRITE_TEST: Boolean get() = on

    /** true: araç verisi yalnızca okunur, dokunuşlar komut göndermez. Test anahtarı açıkken false. */
    val READ_ONLY: Boolean get() = !on

    const val HAS_WRITE_TEST = true

    const val HAS_SETTINGS = false

    /** Bu derlemede ok resmi ayrı resmi FrostNav uygulamasına ait; denetlenemez, işlem yok. */
    @Suppress("UNUSED_PARAMETER")
    fun setHomeForeground(front: Boolean) = Unit

    /** Koltuk soğutma yazması araçta doğrulandı (sürücü, CS 93); yine de yazma yalnızca test anahtarı açıkken ([READ_ONLY]) gider. */
    const val SEAT_VENT_WRITE_VERIFIED = true

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

    fun writeTestHint(): String = "Her komut günlüğe yazılır."

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

    /** Ses alanı (arka hoparlör): anahtar kapalıysa hiçbir şey olmaz; açıkken beyaz listeden geçer. */
    fun sendSoundField(balance: Int, fade: Int) {
        if (!on) return
        WidgetWriter.sendSoundField(balance, fade)
    }

    fun setCentralLock(context: Context, locked: Boolean) {
        if (!on) return
        WidgetWriter.sendLock(context, locked)
    }

    @Suppress("UNUSED_PARAMETER")
    fun openSettings(activity: Activity) = Unit

    /** Bu derlemede FrostNav yok: sistemin Wi-Fi ayarları açılır. */
    fun openWifi(activity: Activity) {
        activity.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Bu derlemede ağ listesi/bağlanma yok: panel yalnızca aç/kapa ve geçerli ağı gösterir. */
    fun wifiBackend(context: Context): WifiBackend = TestWifiBackend(context.applicationContext)

    /** Bu derlemede FrostNav yok: web paneli, ekolayzer, otomasyon, bölünmüş ekran ve yansıtma widget'ları "yok" gösterir. */
    @Suppress("UNUSED_PARAMETER")
    fun frostNav(context: Context): FrostNavFeatures = FrostNavFeatures.NONE
}

@Suppress("DEPRECATION")
private class TestWifiBackend(context: Context) : WifiBackend {
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override val canList = false
    override fun isEnabled() = try { wifi?.isWifiEnabled == true } catch (e: RuntimeException) { false }

    override fun setEnabled(on: Boolean, done: () -> Unit) {
        Thread({
            try { wifi?.setWifiEnabled(on) } catch (e: RuntimeException) { }
            main.postDelayed(done, 800L)
        }, "wifi-radio").start()
    }

    override fun hasLocationPermission() = true
    override fun startScan() = false
    override fun scanResults(): List<WifiScan> = emptyList()

    override fun current(): Pair<String?, Int>? {
        val info = try { wifi?.connectionInfo } catch (e: RuntimeException) { null } ?: return null
        if (info.supplicantState != android.net.wifi.SupplicantState.COMPLETED) return null
        return WifiListShaper.cleanSsid(info.ssid) to android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, WifiStateMap.MAX_LEVEL + 1)
    }

    override fun isSaved(ssid: String) = false
    override fun connect(scan: WifiScan, password: String?) = false
    override fun disconnect() = try { wifi?.disconnect() == true } catch (e: RuntimeException) { false }
}
