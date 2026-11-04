package com.mapcontrol.media;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.mapcontrol.launcher.NavigationSource;

/**
 * Test sürümünün bildirim dinleyicisi: medya oturumlarını okumak için bildirim erişimini sağlar ve yalnızca Yandex
 * Haritalar / Navigator rehberlik bildirimini Sahne Navigasyon paneline ({@link NavigationSource}) iletir (salt okuma;
 * diğer paketlerin bildirim içeriği okunmaz). :app'teki sürümden farkı, Google Maps navigasyon bildirimini işlememesidir.
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
        Listener current = listener;
        if (current != null) {
            current.onListenerConnected();
        }
        NavigationSource.onListenerConnected(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NavigationSource.onNotificationPosted(this, sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NavigationSource.onNotificationRemoved(this, sbn);
    }

    @Override
    public void onListenerDisconnected() {
        NavigationSource.onListenerDisconnected();
    }
}
