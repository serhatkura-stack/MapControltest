package com.mapcontrol.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mapcontrol.media.LauncherMediaController
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Navigasyon durumunun tek kaynağı (gözlenebilir, yoklama yok). Uygulamanın mevcut bildirim dinleyicisi
 * (`LauncherMediaNotificationListener`) Yandex Haritalar / Yandex Navigator bildirimlerini buraya iletir; her bildirim
 * güncellemesinde [NavParse] ile yeni bir [NavigationState] üretilir ve dinleyicilere ana iş parçacığında verilir.
 * Rehberlik bildirimi kalkınca durum [NavigationState.NONE] olur.
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

    /** Bildirim kalkınca (aynı anahtarla yeniden yazma arasında) kısa bekleme; panel boşa düşüp geri gelmesin. */
    private const val REMOVE_HOLD_MS = 800L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(NavigationState) -> Unit>()

    /** Bildirimden okunan gerçek durum. */
    @Volatile private var real: NavigationState = NavigationState.NONE
    @Volatile private var demo = false
    private var activeKey: String? = null
    private var serviceRef: WeakReference<NotificationListenerService>? = null

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

    // --- Bildirim dinleyicisinden (ana iş parçacığı) ---

    @JvmStatic
    fun onListenerConnected(service: NotificationListenerService) {
        serviceRef = WeakReference(service)
        PACKAGES.forEach { NavNotificationReader.forget(it) }
        resync()
        // Erişim yeni verildiyse panel izin iletisinden çıksın: durum aynı olsa da dinleyiciler uyarılır.
        publish()
    }

    @JvmStatic
    fun onListenerDisconnected() {
        serviceRef = null
        main.removeCallbacks(confirmRemoved)
        activeKey = null
        real = NavigationState.NONE
        publish()
    }

    @JvmStatic
    fun onNotificationPosted(service: NotificationListenerService, sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES) return
        serviceRef = WeakReference(service)
        val parsed = parse(service, sbn)
        if (parsed != null) {
            main.removeCallbacks(confirmRemoved)
            activeKey = sbn.key
            setReal(parsed)
        } else if (sbn.key == activeKey) {
            // Aynı bildirim yer tutucuya döndü (Yandex öne geldi ya da rota bitti).
            activeKey = null
            setReal(NavigationState.NONE)
        }
    }

    @JvmStatic
    fun onNotificationRemoved(service: NotificationListenerService, sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES || sbn.key != activeKey) return
        serviceRef = WeakReference(service)
        activeKey = null
        main.removeCallbacks(confirmRemoved)
        main.postDelayed(confirmRemoved, REMOVE_HOLD_MS)
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
            val parsed = parse(service, sbn) ?: continue
            activeKey = sbn.key
            setReal(parsed)
            return true
        }
        if (activeKey == null) setReal(NavigationState.NONE)
        return false
    }

    private fun parse(context: Context, sbn: StatusBarNotification): NavigationState? = try {
        NavParse.parse(NavNotificationReader.read(context, sbn), System.currentTimeMillis())
    } catch (e: Exception) {
        null
    }

    private fun setReal(next: NavigationState) {
        if (next.copy(updatedAtMs = 0) == real.copy(updatedAtMs = 0)) return
        real = next
        if (!demo) publish()
    }

    private fun publish() {
        val s = state
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it(s) }
        else main.post { listeners.forEach { it(state) } }
    }
}
