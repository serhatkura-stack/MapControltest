package com.mapcontrol.service;

import android.content.Context;

/** Test derlemesinin boş yedeği: :app'teki service/BootReceiver.java; açılış servisi yok, manifest girdisi gerekmez. */
public final class BootReceiver {
    private BootReceiver() {}

    public static void startServiceIfAutoStartEnabled(Context context) {}
}
