package com.mapcontrol.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Yandex -> HUD köprüsünün TEK motor sahibi. Bildirim kaynağı (ana) ve erişilebilirlik (isteğe bağlı) buraya yazar.
 * Motor ([HudBridgeEngine]) ve birleştirici tek bir arka plan iş parçacığında çalışır; çağıranlar herhangi bir
 * iş parçacığından olabilir. Tercih KAPALIYKEN olaylar hemen atılır. 200 ms'de bir tik: bekleyen gönderimler,
 * 5 sn veri gelmezse HUD temizleme (Yandex önde iken bildirim boşalır).
 */
internal object HudBridgeController {

    private const val TAG = "HudBridge"
    private const val TICK_MS = 200L

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hud-bridge").apply { isDaemon = true } }
    private val merger = HudNavMerger()

    @Volatile private var appContext: Context? = null
    private var engine: HudBridgeEngine? = null
    private var wasEnabled = false

    /** Yalnızca yürütücüde okunur/yazılır. */
    @Synchronized
    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val sender = HudBridgeSender(app)
        engine = HudBridgeEngine(
            send = { sender.send(it) },
            iconSource = { merger.icon() ?: if (HudBridgeSettings.forceStraight(app)) HudBridgeEngine.ICON_STRAIGHT else null },
            log = { Log.i(TAG, it) },
        )
        exec.scheduleWithFixedDelay({ safe { tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun enabled(): Boolean {
        val c = appContext ?: return false
        return HudBridgeSettings.enabled(c)
    }

    /** Bildirimden yeni durum. */
    fun onNotification(update: NavUpdate) = post { e, now ->
        merger.onNotification(update, now)
        publish(e, now)
    }

    /** Yandex bildirimi kalktı (rota bitti ya da uygulama kapandı). */
    fun onNotificationGone() = post { e, now ->
        merger.clearNotification()
        publish(e, now, endedIfEmpty = true)
    }

    /** Erişilebilirlik okuması (toplam mesafe, yedek mesafe/sokak); null: rehberlik yok. */
    fun onAccessibility(state: HudState?) = post { e, now ->
        merger.onAccessibility(state, now)
        if (state != null) publish(e, now)
    }

    private fun publish(e: HudBridgeEngine, now: Long, endedIfEmpty: Boolean = false) {
        val st = merger.state(now)
        when {
            st != null -> e.onState(st, now)
            endedIfEmpty -> e.onGuidanceEnded(now)
            else -> e.tick(now)
        }
    }

    private fun post(block: (HudBridgeEngine, Long) -> Unit) {
        if (!enabled()) return
        exec.execute { safe { gate()?.let { now -> engine?.let { block(it, now) } } } }
    }

    /** Açık ise şimdiki zaman; kapalıysa (ve az önce açıktıysa) ekranı temizleyip null. */
    private fun gate(): Long? {
        val e = engine ?: return null
        val now = SystemClock.elapsedRealtime()
        if (!enabled()) {
            if (wasEnabled) {
                wasEnabled = false
                merger.reset()
                e.onGuidanceEnded(now)
                Log.i(TAG, "köprü kapatıldı")
            }
            return null
        }
        wasEnabled = true
        return now
    }

    private fun tick() {
        val now = gate()
        val e = engine ?: return
        // Kapalıyken de bekleyen "temizle" denemesi sürsün.
        e.tick(now ?: SystemClock.elapsedRealtime())
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "denetleyici hatası: $t") }
    }
}
