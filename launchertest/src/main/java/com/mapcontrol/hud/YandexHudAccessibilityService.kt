package com.mapcontrol.hud

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mapcontrol.nav.YandexNavScraper
import com.mapcontrol.nav.YandexNavSnapshot
import com.mapcontrol.nav.YandexNavStability
import java.util.concurrent.Executors

/**
 * Yandex Haritalar rehberliğini erişilebilirlikle okuyup HUD'a aktaran hizmet (launchertest, isteğe bağlı).
 * Tercih KAPALIYKEN olaylar hemen atılır; hiçbir şey okunmaz ya da gönderilmez.
 * Okuma (ağaç gezme, binder beklemesi) ve gönderim tek bir arka plan iş parçacığında; ana iş parçacığı serbest.
 * Okuma en çok 2 Hz (500 ms); ayrıca olay gelmese de 500 ms'de bir yoklanır (arka plan penceresi).
 */
class YandexHudAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "hud-bridge").apply { isDaemon = true } }
    private val stability = YandexNavStability()
    private var sender: HudBridgeSender? = null
    private var engine: HudBridgeEngine? = null
    private var lastScrapeMs = 0L
    private var lastLogged: YandexNavSnapshot? = null
    @Volatile private var scrapeQueued = false
    @Volatile private var wasEnabled = false

    private val poll = object : Runnable {
        override fun run() {
            if (!connected) return
            schedule()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "erişilebilirlik hizmeti bağlandı")
        val s = HudBridgeSender(applicationContext)
        sender = s
        engine = HudBridgeEngine(
            send = { s.send(it) },
            iconSource = { iconSource.currentIcon() ?: if (HudBridgeSettings.forceStraight(this)) HudBridgeEngine.ICON_STRAIGHT else null },
            log = { Log.i(TAG, it) },
        )
        main.postDelayed(poll, POLL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.contentEquals(YandexNavScraper.PACKAGE_YANDEX_MAPS) != true) return
        if (!HudBridgeSettings.enabled(this)) return
        schedule()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        main.removeCallbacks(poll)
        // Hizmet kapanırken ekranda kalmasın.
        val e = engine
        if (e != null && wasEnabled) exec.execute { e.onGuidanceEnded(SystemClock.elapsedRealtime()) }
        return super.onUnbind(intent)
    }

    /** Okumayı kuyruğa alır (zaten kuyrukta ise yeni iş eklemez). */
    private fun schedule() {
        if (scrapeQueued) return
        scrapeQueued = true
        exec.execute { try { work() } finally { scrapeQueued = false } }
    }

    private fun work() {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        val enabled = HudBridgeSettings.enabled(this)
        if (!enabled) {
            if (wasEnabled) {
                // Kapatıldı: ekranı temizle ve durumu sıfırla.
                e.onGuidanceEnded(now)
                stability.clear()
                wasEnabled = false
                Log.i(TAG, "köprü kapatıldı")
            }
            return
        }
        wasEnabled = true
        if (now - lastScrapeMs < SCRAPE_GAP_MS) {
            e.tick(now)
            return
        }
        lastScrapeMs = now
        val read = readYandex()
        if (read.found != null) {
            val stable = stability.accept(read.found, now)
            logIfChanged(stable)
            if (!stable.guidanceActive) {
                e.onGuidanceEnded(now)
            } else {
                val st = HudStateMapper.map(stable.maneuverDistance, stable.maneuverUnit, stable.nextStreet, stable.etaDistance)
                if (st != null) e.onState(st, now) else e.tick(now)
            }
        } else {
            e.tick(now)
        }
    }

    private fun logIfChanged(s: YandexNavSnapshot) {
        if (s.contentEquals(lastLogged)) return
        lastLogged = s
        Log.i(TAG, "SNAPSHOT active=${s.guidanceActive} man='${s.maneuverDistance}' unit='${s.maneuverUnit}' " +
            "street='${s.nextStreet}' eta='${s.etaDistance}' status='${s.statusPanel}'")
    }

    private class Read(val found: YandexNavSnapshot?)

    /** Yandex pencerelerini (tüm ekranlar) tarar; bulunan alanları birleştirir. Pencere yoksa found=null. */
    private fun readYandex(): Read {
        var best: YandexNavSnapshot? = null
        fun offer(root: AccessibilityNodeInfo?) {
            if (root == null) return
            try {
                if (root.packageName?.contentEquals(YandexNavScraper.PACKAGE_YANDEX_MAPS) == true) {
                    val snap = YandexNavScraper.scrape(root)
                    best = best?.let { b ->
                        if (snap.populatedFieldCount() > b.populatedFieldCount()) snap.withFilledGaps(b) else b.withFilledGaps(snap)
                    } ?: snap
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        }
        try { offer(rootInActiveWindow) } catch (_: Exception) { }
        try {
            val lists = ArrayList<List<AccessibilityWindowInfo>>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val all = windowsOnAllDisplays
                for (i in 0 until all.size()) lists.add(all.valueAt(i))
            } else {
                lists.add(windows)
            }
            for (wins in lists) for (w in wins) {
                try { offer(w.root) } catch (_: Exception) { } finally { try { w.recycle() } catch (_: Exception) { } }
            }
        } catch (_: Exception) { }
        return Read(best)
    }

    companion object {
        const val TAG = "HudBridge"
        private const val POLL_MS = 500L
        private const val SCRAPE_GAP_MS = 500L

        /** Hizmet bağlı mı (ayarlar satırı için). */
        @Volatile var connected = false

        /** Dönüş tipini tanıyan kaynak. Şimdilik hiçbiri: ikon 0. */
        @Volatile var iconSource: ManeuverIconSource = NoManeuverIcon
    }
}
