package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mapcontrol.audio.AudioEqualizerController
import com.mapcontrol.manager.WebServerManager
import com.mapcontrol.service.DmsFaceRecognitionManager
import com.mapcontrol.service.MapControlService
import com.mapcontrol.ui.activity.MainActivity
import com.mapcontrol.util.ClusterNavigationState
import com.mapcontrol.util.SplitScreenLauncher
import com.mapcontrol.util.SplitScreenMessages
import com.mapcontrol.util.SplitScreenStore
import com.mapcontrol.util.TargetPackageStore
import com.mapcontrol.vehicle.ClimateAutomationEngine
import com.mapcontrol.vehicle.ClimateAutomationStore
import com.mapcontrol.vehicle.SunroofAutomationEngine
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * [FrostNavFeatures]'ın gerçek sürümü: her çağrı FrostNav'ın kendi ayar sekmesinin kullandığı yoldan geçer. Yalnızca :app'te
 * derlenir (`launchertest` bu dosyayı almaz, [FrostNavFeatures.NONE] kullanır).
 *
 * Web sunucusu: [WebServerManager] örneği MainActivity'nin kendisinindir (dinleyicisi APK kurma, uygulama açma gibi işleri
 * etkinlik üzerinden yapar); launcher'dan güvenle başlatılıp durdurulamaz. Bu yüzden durum, sunucunun sabit portuna yerel bir
 * TCP bağlantı denemesiyle okunur, adres FrostNav'ın kendi adres seçiminden ([WebServerManager.currentHost]) gelir;
 * başlat/durdur için FrostNav "Aktar" (web yönetimi) sekmesi açılır.
 */
internal class AppFrostNavFeatures(context: Context) : FrostNavFeatures {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val available = true

    // --- Web paneli ---

    override fun readWebPanel(): WebPanelState {
        val running = portOpen(FrostNavWidgetMath.WEB_PORT)
        if (!running) return WebPanelState(running = false, url = null, reachable = false)
        // Yalnızca adres okunur: örnek başlatılmaz, soket açmaz (FrostNav'ın gösterdiği adresle aynı seçim).
        val host = try { WebServerManager(app).currentHost() } catch (e: RuntimeException) { null }
        return WebPanelState(true, FrostNavWidgetMath.webUrl(host), !FrostNavWidgetMath.isLoopback(host))
    }

    /** Sunucu dinliyor mu: bağlanıp hemen kapatır (FrostNav başlık okuyamayınca bağlantıyı sessizce kapatır). */
    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS); true }
    } catch (e: IOException) {
        false
    }

    override fun qr(text: String): QrMatrix? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0,
        )
        // 0×0 istenince zxing modül çözünürlüğünde (1 piksel = 1 modül) üretir; çizim launcher'da ölçeklenir.
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = minOf(m.width, m.height)
        QrMatrix(size, BooleanArray(size * size) { m.get(it % size, it / size) })
    } catch (e: WriterException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // --- Ekolayzer (EqualizerTabBuilder.onPresetSelected ile aynı yol) ---

    override fun readEqualizer(): EqState {
        val s = AudioEqualizerController.read(app)
        return EqState(s.supported, s.enabled, s.preset, PRESETS)
    }

    override fun setEqualizer(enabled: Boolean, preset: String) {
        val s = AudioEqualizerController.read(app)
        val bands = if (preset == AudioEqualizerController.PRESET_CUSTOM) s.bands
        else AudioEqualizerController.bandsForPreset(preset, s.centerHz, s.minLevelMb, s.maxLevelMb)
        AudioEqualizerController.update(app, enabled, s.gainMb, bands, preset)
    }

    // --- Otomasyon (ClimateAutomationTabBuilder / SunroofAutomationTabBuilder ana anahtarlarıyla aynı yol) ---

    private fun climatePrefs() = app.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)

    override fun readAutomations(): List<AutomationItem> = listOf(
        AutomationItem(AutomationId.CLIMATE, ClimateAutomationStore.isMasterEnabled(climatePrefs()), toggleable = true),
        AutomationItem(AutomationId.SUNROOF,
            SunroofAutomationEngine.getInstance(app).prefs.getBoolean(SunroofAutomationEngine.KEY_ENABLED, false), toggleable = true),
        // Yüz tanımayı açmak kamera izni ve kayıt akışı ister (DmsFaceRecognitionTabBuilder): yalnızca durumu gösterilir.
        AutomationItem(AutomationId.FACE, DmsFaceRecognitionManager.isEnabled(app), toggleable = false),
    )

    override fun setAutomation(id: AutomationId, on: Boolean) {
        when (id) {
            AutomationId.CLIMATE -> {
                ClimateAutomationStore.setMasterEnabled(climatePrefs(), on)
                val engine = ClimateAutomationEngine.getInstance(app)
                if (on) engine.notifyRulesChanged() else engine.disableAllRules()
            }
            AutomationId.SUNROOF -> SunroofAutomationEngine.getInstance(app).prefs.edit()
                .putBoolean(SunroofAutomationEngine.KEY_ENABLED, on).apply()
            AutomationId.FACE -> Unit
        }
        Log.i(TAG, "automation $id -> $on")
    }

    // --- Bölünmüş ekran (SplitScreenTabBuilder.onStartClicked ile aynı yol) ---

    override fun readSplitPair() = SplitPair(SplitScreenStore.getPkg1(app), SplitScreenStore.getPkg2(app))

    override fun startSplit(done: (String?) -> Unit) {
        val pair = readSplitPair()
        if (!FrostNavWidgetMath.splitReady(pair)) {
            main.post { done(app.getString(com.mapcontrol.R.string.split_select_both)) }
            return
        }
        SplitScreenLauncher.start(app, pair.first!!, pair.second!!, SplitScreenStore.getRatio(app)) { result ->
            Log.i(TAG, "split -> $result")
            done(if (result == SplitScreenLauncher.Result.STARTED) null else SplitScreenMessages.resultText(app, result))
        }
    }

    // --- Göstergeye yansıt (MapControlService aç/kapa eylemleri → ClusterDisplayManager, Yansıtma sekmesiyle aynı) ---

    override fun readCluster() = ClusterState(ClusterNavigationState.getLastKnownOpen(), TargetPackageStore.read(app).ifEmpty { null })

    override fun setCluster(open: Boolean) {
        val intent = Intent(app, MapControlService::class.java)
            .setAction(if (open) MapControlService.ACTION_BENCH_OPEN_CLUSTER else MapControlService.ACTION_BENCH_CLOSE_CLUSTER)
            // Yansıtma sekmesindeki gibi true: hedef uygulama ana ekrana taşınınca HOME ile launcher yeniden öne gelir.
            .putExtra(MapControlService.EXTRA_CLUSTER_CLOSE_SEND_BACKGROUND, true)
        try {
            app.startService(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cluster $open: ${e.message}")
        }
    }

    override fun openTab(activity: Activity, tab: FrostNavTab) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, tabIndex(tab)),
        )
    }

    private fun tabIndex(tab: FrostNavTab): Int = when (tab) {
        // Web yönetimi ("Aktar"), Yansıtma ve Ses ayarları için MainActivity'de herkese açık sabit yok (tabTitle: 1, 3, 11).
        FrostNavTab.WEB -> TAB_WEB
        FrostNavTab.PROJECTION -> TAB_PROJECTION
        FrostNavTab.EQUALIZER -> TAB_EQUALIZER
        FrostNavTab.CLIMATE_AUTOMATION -> MainActivity.TAB_CLIMATE_AUTOMATION
        FrostNavTab.SUNROOF_AUTOMATION -> MainActivity.TAB_SUNROOF_AUTOMATION
        FrostNavTab.FACE_RECOGNITION -> MainActivity.TAB_FACE_RECOGNITION
        FrostNavTab.SPLIT_SCREEN -> MainActivity.TAB_SPLIT_SCREEN
    }

    private companion object {
        const val TAG = "FrostNavWidgets"
        const val PREFS_MAIN = "MapControlPrefs"
        const val PROBE_TIMEOUT_MS = 300
        const val TAB_WEB = 1
        const val TAB_PROJECTION = 3
        const val TAB_EQUALIZER = 11

        /** EqualizerTabBuilder'daki hazır ayar sırası. */
        val PRESETS = listOf(
            AudioEqualizerController.PRESET_FLAT,
            AudioEqualizerController.PRESET_BASS,
            AudioEqualizerController.PRESET_VOCAL,
            AudioEqualizerController.PRESET_TREBLE,
        )
    }
}
