package com.mapcontrol.trip;

import android.content.Context;

/**
 * Launcher test sürümünde yolculuk kaydı ve bulut yüklemesi yoktur. Bu sınıf :app'teki TripRecorder'ın yerini alır;
 * VehicleMetricsRepository'nin çağırdığı imza aynıdır, hiçbir şey yapmaz.
 */
public final class TripRecorder {
    private static final TripRecorder INSTANCE = new TripRecorder();

    private TripRecorder() {}

    public static TripRecorder get(Context context) {
        return INSTANCE;
    }

    public void onPowerSample(int powerMode, long elapsedMs) {
    }
}
