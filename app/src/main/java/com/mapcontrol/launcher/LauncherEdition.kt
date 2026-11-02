package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.ui.activity.MainActivity
import com.mapcontrol.util.WifiController
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

    /** Widget'lardan araca yazma testi anahtarı yalnızca test sürümünde var; bu derlemede hep kapalı. */
    const val WRITE_TEST = false
    const val HAS_WRITE_TEST = false

    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setWriteTest(context: Context, on: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun writeTestTitle(on: Boolean): String = ""

    fun writeTestHint(): String = ""

    /** Ayarlar (MapControl ana ekranı) bu derlemede var mı. */
    const val HAS_SETTINGS = true

    /**
     * Koltuk soğutma yazması araçta doğrulanana kadar kapalı (kodlama bilinmiyor; sinyal 93/94 hep [0] okunuyor).
     * Araç testinden sonra true yapılır.
     */
    const val SEAT_VENT_WRITE_VERIFIED = false

    /** Koltuk soğutma seviyesi (0..3). Doğrulanmadığı sürece hiçbir şey göndermez. Kodlama ısıtmayla aynı varsayılır (doğrulanmadı). */
    fun sendSeatVentLevel(proxy: CarInfoProxy, driver: Boolean, level: Int) {
        if (!SEAT_VENT_WRITE_VERIFIED) return
        val id = if (driver) CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION else CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION
        // Kodlama ısıtmayla aynı (SeatHeatModel.encode: 0 kapalı → 1, 1..3 → 2..4), ClimateAutomationEngine.wireSeatLevel gibi.
        proxy.sendItemValue(VDEventCarInfo.MODULE_CAR_SETTING, id, SeatHeatModel.encode(level))
    }

    fun sendItemValue(proxy: CarInfoProxy, module: Int, id: Int, value: Int) {
        proxy.sendItemValue(module, id, value)
    }

    /** Ses alanı (arka hoparlör): [balance] sol-sağ, [fade] ön-arka, 0..20. Çağıran kapıyı ([ComfortWriter.rearSpeakerEnabled]) zaten denetledi. */
    fun sendSoundField(balance: Int, fade: Int) {
        SoundFieldIo.write(balance, fade)
    }

    fun setCentralLock(context: Context, locked: Boolean) {
        VehicleQuickControls.getInstance(context).setCentralLock(locked)
    }

    /**
     * FrostNav'ı doğrudan "Ana ekran" sekmesinde açar. Launcher kendi görev ilişkisinde (taskAffinity) çalışır;
     * CLEAR_TOP|SINGLE_TOP FrostNav'ı açıksa öne getirip onNewIntent ile sekmeyi iletir.
     */
    fun openSettings(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_HOME_SCREEN),
        )
    }

    /** FrostNav'ı doğrudan "Wi-Fi" sekmesinde açar (yakındaki ağları tarar, listeler ve bağlanır). */
    fun openWifi(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_WIFI),
        )
    }

    /** Wi-Fi paneli arka ucu: FrostNav'ın Wi-Fi sekmesiyle paylaşılan [WifiController] (tara, bağlan, kes, aç/kapa). */
    fun wifiBackend(context: Context): WifiBackend = AppWifiBackend(context.applicationContext)

    /** FrostNav widget'larının arka ucu: FrostNav'ın kendi yöneticileri, tercihleri ve servisi ([AppFrostNavFeatures]). */
    fun frostNav(context: Context): FrostNavFeatures = AppFrostNavFeatures(context.applicationContext)
}

private class AppWifiBackend(private val context: Context) : WifiBackend {
    private val controller = WifiController(context)
    private val main = Handler(Looper.getMainLooper())

    override val canList = true

    override fun isEnabled() = controller.isEnabled()

    override fun setEnabled(on: Boolean, done: () -> Unit) {
        Thread({
            try {
                controller.setEnabledBlocking(on) { Log.d(TAG, it) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "setEnabled: ${e.message}")
            }
            main.postDelayed(done, SETTLE_MS)
        }, "wifi-radio").start()
    }

    override fun hasLocationPermission() =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun startScan() = try { controller.startScan() } catch (e: RuntimeException) { false }

    override fun scanResults(): List<WifiScan> = controller.scanResults().map {
        WifiScan(it.SSID.orEmpty(), WifiManager.calculateSignalLevel(it.level, WifiStateMap.MAX_LEVEL + 1), it.capabilities)
    }

    override fun current(): Pair<String?, Int>? {
        val info = controller.connectionInfo() ?: return null
        if (info.supplicantState != SupplicantState.COMPLETED) return null
        return WifiListShaper.cleanSsid(info.ssid) to WifiManager.calculateSignalLevel(info.rssi, WifiStateMap.MAX_LEVEL + 1)
    }

    override fun isSaved(ssid: String) = controller.savedNetworkId(ssid) != -1

    override fun connect(scan: WifiScan, password: String?): Boolean {
        val out = when {
            controller.savedNetworkId(scan.ssid) != -1 && password == null -> controller.connectSaved(scan.ssid)
            !scan.secured -> controller.connectOpen(scan.ssid)
            password != null -> controller.connectSecure(scan.ssid, scan.capabilities.orEmpty(), password)
            else -> return false
        }
        return out.result == WifiController.Result.STARTED
    }

    override fun disconnect() = controller.disconnect().result == WifiController.Result.DISCONNECTED

    private companion object {
        const val TAG = "WifiPanel"
        const val SETTLE_MS = 800L
    }
}
