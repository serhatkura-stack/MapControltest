package com.mapcontrol.media;

import android.service.notification.NotificationListenerService;

/**
 * Test sürümünün bildirim dinleyicisi: yalnızca medya oturumlarını okumaya izin verir.
 * :app'teki sürümden farkı, Google Maps navigasyon bildirimini işlememesidir.
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
    }
}
