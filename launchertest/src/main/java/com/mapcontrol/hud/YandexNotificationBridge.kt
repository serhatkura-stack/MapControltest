package com.mapcontrol.hud

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * LauncherMediaNotificationListener'ın çağırdığı ince katman: Yandex Haritalar navigasyon bildirimini ayrıştırıp
 * [HudBridgeController]'a verir. Tercih KAPALIYKEN hiçbir şey okunmaz. Her şey try/catch (dinleyiciyi düşürmez).
 */
object YandexNotificationBridge {

    private const val TAG = "HudBridge"
    private val parser = YandexNotificationParser()

    private fun isYandexNav(sbn: StatusBarNotification?): Boolean =
        sbn != null && sbn.packageName == YandexNotificationReader.PACKAGE &&
            sbn.notification?.category == Notification.CATEGORY_NAVIGATION

    @JvmStatic
    fun onConnected(listener: NotificationListenerService) {
        try {
            HudBridgeController.attach(listener)
            val active = listener.activeNotifications ?: return
            for (sbn in active) onPosted(listener, sbn)
        } catch (t: Throwable) {
            Log.w(TAG, "bildirim eşitleme hatası: $t")
        }
    }

    @JvmStatic
    fun onPosted(context: Context, sbn: StatusBarNotification?) {
        try {
            if (!isYandexNav(sbn)) return
            HudBridgeController.attach(context)
            if (!HudBridgeController.enabled()) return
            val n = sbn!!.notification
            val title = YandexNotificationReader.title(n)
            val text = YandexNotificationReader.text(n)
            val icons = try { YandexNotificationReader.iconEntryNames(context, sbn) } catch (_: Throwable) { emptyList() }
            val iconName = icons.firstOrNull { YandexNotificationParser.maneuverFromIconName(it) != null }
            val update = synchronized(parser) { parser.parse(title, text, iconName, SystemClock.elapsedRealtime()) }
            Log.i(TAG, "BILDIRIM title='$title' text='$text' icons=$icons -> ${update ?: "veri yok"}")
            if (update != null) HudBridgeController.onNotification(update)
        } catch (t: Throwable) {
            Log.w(TAG, "bildirim okunamadı: $t")
        }
    }

    @JvmStatic
    fun onRemoved(context: Context, sbn: StatusBarNotification?) {
        try {
            if (!isYandexNav(sbn)) return
            HudBridgeController.attach(context)
            synchronized(parser) { parser.reset() }
            Log.i(TAG, "BILDIRIM kaldırıldı")
            HudBridgeController.onNotificationGone()
        } catch (t: Throwable) {
            Log.w(TAG, "bildirim kaldırma hatası: $t")
        }
    }
}
