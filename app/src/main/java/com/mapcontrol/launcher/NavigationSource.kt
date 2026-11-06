package com.mapcontrol.launcher

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mapcontrol.BuildConfig
import com.mapcontrol.media.LauncherMediaController
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Navigasyon durumunun tek kaynağı (gözlenebilir, yoklama yok). Uygulamanın mevcut bildirim dinleyicisi
 * (`LauncherMediaNotificationListener`) Yandex Haritalar / Yandex Navigator bildirimlerini buraya iletir; her bildirim
 * güncellemesinde [NavParse] ile yeni bir [NavigationState] üretilir ve dinleyicilere ana iş parçacığında verilir.
 * Rehberlik bildirimi kalkınca durum [NavigationState.NONE] olur.
 *
 * İş parçacığı: bildirim okuma ve ayrıştırma (yansıma ile RemoteViews gezintisi) tek bir arka plan iş parçacığında
 * ("NavParse") yapılır; 6 Kasım araç ANR'leri bu işin ana iş parçacığında olmasından geliyordu. İçeriği değişmeyen
 * yeniden yayımlar [NavParseGate] ile atlanır; dinleyiciler yalnız durum gerçekten değişince (ana iş parçacığında) çağrılır.
 *
 * Bilinen sınır (araştırma, araçta doğrulanmadı): Yandex zengin rehberlik bildirimini yalnızca kendisi arka plandayken
 * üretir; ana ekran öndeyken (Sahne görünürken) tam bu durumdur. Yandex öndeyken yalnızca "Navigator başlatıldı" yer
 * tutucusu kalır ve bu rota yok sayılır.
 *
 * Kullanım (Sahne paneli, mini gösterge):
 * ```
 * val l: (NavigationState) -> Unit = { s -> bind(s) }
 * NavigationSource.addListener(l)   // hemen son durumla da çağrılır
 * NavigationSource.removeListener(l)
 * ```
 */
object NavigationSource {
    const val YANDEX_MAPS = "ru.yandex.yandexmaps"
    const val YANDEX_NAVI = "ru.yandex.yandexnavi"
    @JvmField val PACKAGES: Set<String> = setOf(YANDEX_MAPS, YANDEX_NAVI)

    private const val TAG = "NavSource"

    /** Bildirim kalkınca (aynı anahtarla yeniden yazma arasında) kısa bekleme; panel boşa düşüp geri gelmesin. */
    private const val REMOVE_HOLD_MS = 800L

    /** Debug: tek ayrıştırma bundan uzun sürerse uyarı. */
    private const val SLOW_PARSE_MS = 50L

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy {
        val t = HandlerThread("NavParse", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        Handler(t.looper)
    }
    private val listeners = CopyOnWriteArraySet<(NavigationState) -> Unit>()

    /** Bildirimden okunan gerçek durum. */
    @Volatile private var real: NavigationState = NavigationState.NONE
    @Volatile private var demo = false
    @Volatile private var serviceRef: WeakReference<NotificationListenerService>? = null

    // Yalnız worker iş parçacığında erişilir.
    private var activeKey: String? = null
    private val gate = NavParseGate()

    /** Son durum (demo açıksa demo rota). */
    @JvmStatic
    val state: NavigationState get() = if (demo) NavigationState.DEMO else real

    /** Dinleyici ekler ve hemen son durumla çağırır (çağıranın iş parçacığında). Sonraki çağrılar ana iş parçacığında. */
    @JvmStatic
    fun addListener(listener: (NavigationState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    @JvmStatic
    fun removeListener(listener: (NavigationState) -> Unit) {
        listeners.remove(listener)
    }

    /** Bildirim dinleyicisi sisteme bağlı mı (erişim verildi ve servis çalışıyor). */
    @JvmStatic
    val isListenerBound: Boolean get() = serviceRef?.get() != null

    /** Kullanıcı bildirim erişimini Ayarlar'da açmış mı. Kodla verilmez; yalnızca okunur. */
    @JvmStatic
    fun hasAccess(context: Context): Boolean = LauncherMediaController.isNotificationAccessEnabled(context)

    /** Demo rota (`--ez launcher_demo_nav true`, yalnızca debug demo açıkken). */
    @JvmStatic
    fun setDemo(on: Boolean) {
        if (demo == on) return
        demo = on
        publish()
    }

    // --- Bildirim dinleyicisinden (ana iş parçacığı); iş worker'a aktarılır ---

    @JvmStatic
    fun onListenerConnected(service: NotificationListenerService) {
        serviceRef = WeakReference(service)
        worker.post {
            PACKAGES.forEach { NavNotificationReader.forget(it) }
            gate.clear()
            resync()
            // Erişim yeni verildiyse panel izin iletisinden çıksın: durum aynı olsa da dinleyiciler uyarılır.
            publish()
        }
    }

    @JvmStatic
    fun onListenerDisconnected() {
        serviceRef = null
        worker.post {
            worker.removeCallbacks(confirmRemoved)
            activeKey = null
            gate.clear()
            real = NavigationState.NONE
            publish()
        }
    }

    @JvmStatic
    fun onNotificationPosted(service: NotificationListenerService, sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES) return
        serviceRef = WeakReference(service)
        worker.post { handlePosted(service, sbn) }
    }

    @JvmStatic
    fun onNotificationRemoved(service: NotificationListenerService, sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES) return
        serviceRef = WeakReference(service)
        worker.post {
            gate.forget(sbn.key)
            if (sbn.key != activeKey) return@post
            activeKey = null
            worker.removeCallbacks(confirmRemoved)
            worker.postDelayed(confirmRemoved, REMOVE_HOLD_MS)
        }
    }

    // --- worker iş parçacığı ---

    private fun handlePosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        val key = sbn.key
        val result = parseGated(service, sbn) ?: return // içerik aynı: durum değişmez
        val parsed = result.state
        if (parsed != null) {
            worker.removeCallbacks(confirmRemoved)
            activeKey = key
            setReal(parsed)
        } else if (key == activeKey) {
            // Aynı bildirim yer tutucuya döndü (Yandex öne geldi ya da rota bitti).
            activeKey = null
            setReal(NavigationState.NONE)
        }
    }

    private val confirmRemoved = Runnable { if (activeKey == null && !resync()) setReal(NavigationState.NONE) }

    /** Etkin bildirimleri yeniden okur; rehberlik bulunduysa true. */
    private fun resync(): Boolean {
        val service = serviceRef?.get() ?: return false
        val active = try {
            service.activeNotifications
        } catch (e: Exception) {
            null
        } ?: return false
        for (sbn in active) {
            val pkg = sbn?.packageName ?: continue
            if (pkg !in PACKAGES) continue
            // Yeniden eşitlemede kapı kullanılmaz: kayıtlı içerik aynı olsa bile durum yeniden kurulmalı.
            val parsed = parse(service, sbn) ?: continue
            gate.remember(sbn.key, sbn.postTime, signature(sbn))
            activeKey = sbn.key
            setReal(parsed)
            return true
        }
        if (activeKey == null) setReal(NavigationState.NONE)
        return false
    }

    /** Ayrıştırma sonucu; null = kapı atlattı (içerik değişmedi). */
    private class Parsed(val state: NavigationState?)

    private fun parseGated(context: Context, sbn: StatusBarNotification): Parsed? {
        var sig: Int? = null
        val sigOf = { sig ?: signature(sbn).also { sig = it } }
        if (!gate.shouldParse(sbn.key, sbn.postTime, sigOf)) return null
        val state = parse(context, sbn)
        gate.remember(sbn.key, sbn.postTime, sigOf())
        return Parsed(state)
    }

    private fun signature(sbn: StatusBarNotification): Int = try {
        NavNotificationReader.contentSignature(sbn)
    } catch (e: Exception) {
        sbn.postTime.hashCode()
    }

    private fun parse(context: Context, sbn: StatusBarNotification): NavigationState? {
        val t0 = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        val result = try {
            NavParse.parse(NavNotificationReader.read(context, sbn), System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
        if (BuildConfig.DEBUG) {
            val dt = SystemClock.elapsedRealtime() - t0
            if (dt > SLOW_PARSE_MS) Log.w(TAG, "slow parse ${dt} ms pkg=${sbn.packageName}")
        }
        return result
    }

    private fun setReal(next: NavigationState) {
        if (next.copy(updatedAtMs = 0) == real.copy(updatedAtMs = 0)) return
        real = next
        if (!demo) publish()
    }

    private fun publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val s = state
            listeners.forEach { it(s) }
        } else {
            main.post { val s = state; listeners.forEach { it(s) } }
        }
    }
}
