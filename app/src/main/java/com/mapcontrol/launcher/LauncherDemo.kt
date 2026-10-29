package com.mapcontrol.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.mapcontrol.media.LauncherMediaController.MediaState
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

/**
 * Sunum ve ekran görüntüsü için sahte araç verisi (emülatörde her şey "—" görünmesin diye).
 *
 * Yalnızca debug (debuggable) derlemede ve başlatma isteğinde açıkça istenirse açılır:
 *   adb shell am start -n <paket>/com.mapcontrol.launcher.HomeLauncherActivity --ez launcher_demo true
 * Açıkken widget'lar aracı değil buradaki sabit değerleri gösterir; komut gönderen yollar yine
 * LauncherEdition üzerinden geçer, demo onlara dokunmaz.
 */
internal object LauncherDemo {

    const val EXTRA = "launcher_demo"

    @Volatile
    var enabled = false
        private set

    /** Etkinlik her açıldığında çağrılır; istek demo içermiyorsa önceki durum korunur (tema değişimi). */
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        enabled = debuggable && intent.getBooleanExtra(EXTRA, false)
    }

    /** Park halinde, kapılar kapalı, Normal mod, tavan kapalı bir Tiggo 7. */
    fun vehicle(): VehicleMetricsSnapshot = VehicleMetricsSnapshot(
        true,   // connected
        0, 0,   // hız
        18_420, // km
        620,    // yakıt, onda bir (%62,0) — araçtaki gibi
        540,    // menzil km
        0, 2, 0,
        0, 0, 0, 0, 0, // kapılar ve bagaj kapalı
        2, 1, 1,       // tavan: kapalı
        0, 0, 0,
        1,             // sürüş modu: Normal
    )

    const val LOCKED = true
    const val TIRE_WARNING = false

    const val DRIVER_SEAT_LEVEL = 2
    const val PASSENGER_SEAT_LEVEL = 0
    const val DRIVER_VENT_LEVEL = 1
    const val PASSENGER_VENT_LEVEL = 0

    fun trip(): TripData = TripData(
        connected = true, moving = true, instantL100 = 6.8, driveAvgL100 = 7.4, tripKm = 42.6,
        avgL100 = 7.9, avgSpeedKmh = 46, batteryV = 14.1, coolantC = 89, lowFuel = false,
        serviceKm = 3978, serviceDays = 155, pm25In = 1, pm25Out = 4,
    )

    /** Ses çalıyormuş gibi; konum gerçek zamanla ilerlemesin diye her çağrıda aynı başlangıç. */
    fun media(): MediaState = MediaState(
        true, true, true,
        "Yolun Sonu", "Demo Sanatçı", "com.google.android.apps.youtube.music",
        72_000L, 215_000L, null,
    )
}
