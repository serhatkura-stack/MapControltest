package com.mapcontrol.media;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.mapcontrol.launcher.NavigationSource;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Test sürümünün bildirim dinleyicisi: medya oturumlarını okumak için bildirim erişimini sağlar ve yalnızca Yandex
 * Haritalar / Navigator rehberlik bildirimini Sahne Navigasyon paneline ({@link NavigationSource}) iletir (salt okuma;
 * diğer paketlerin bildirim içeriği okunmaz). :app'teki sürümden farkı, Google Maps navigasyon bildirimini işlememesidir.
 */
public final class LauncherMediaNotificationListener extends NotificationListenerService {

    /** Her medya denetleyicisi kendi dinleyicisini ekler; biri durunca ötekiler silinmez. */
    private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    interface Listener {
        void onListenerConnected();
    }

    static void addListener(Listener value) {
        if (value != null) {
            listeners.add(value);
        }
    }

    static void removeListener(Listener value) {
        listeners.remove(value);
    }

    @Override
    public void onListenerConnected() {
        for (Listener current : listeners) {
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
