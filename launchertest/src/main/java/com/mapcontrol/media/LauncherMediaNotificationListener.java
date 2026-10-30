package com.mapcontrol.media;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.mapcontrol.hud.YandexNotificationBridge;

/**
 * Test sürümünün bildirim dinleyicisi: medya oturumlarını okur ve (Yandex -> HUD köprüsü açıksa) Yandex Haritalar
 * navigasyon bildirimini köprüye verir. :app'teki sürümden farkı, Google Maps navigasyon bildirimini işlememesidir.
 */
public final class LauncherMediaNotificationListener extends NotificationListenerService {

    private static volatile Listener listener;

    interface Listener {
        void onListenerConnected();
    }

    static void setListener(Listener value) {
        listener = value;
    }

    @Override
    public void onListenerConnected() {
        YandexNotificationBridge.onConnected(this);
        Listener current = listener;
        if (current != null) {
            current.onListenerConnected();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        YandexNotificationBridge.onPosted(this, sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        YandexNotificationBridge.onRemoved(this, sbn);
    }
}
