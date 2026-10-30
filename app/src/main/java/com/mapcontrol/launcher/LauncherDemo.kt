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

    /** Demo sürüş modu (ham değer 0..6); `--ei launcher_demo_drive_mode N` ile verilir, yoksa Normal. */
    const val EXTRA_DRIVE_MODE = "launcher_demo_drive_mode"

    @Volatile
    var driveMode = 1
        private set

    /** Demo hareket durumu (`--ez launcher_demo_moving true|false`); verilmediyse null. Yalnızca demo açıkken. */
    const val EXTRA_MOVING = "launcher_demo_moving"

    @Volatile
    var moving: Boolean? = null
        private set

    fun applyMoving(intent: Intent?) {
        if (!enabled || intent?.hasExtra(EXTRA_MOVING) != true) return
        moving = intent.getBooleanExtra(EXTRA_MOVING, false)
    }

    /** Etkinlik her açıldığında çağrılır; istek demo içermiyorsa önceki durum korunur (tema değişimi). */
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        enabled = debuggable && intent.getBooleanExtra(EXTRA, false)
    }

    /** İstek sürüş modu içeriyorsa (yalnızca demo açıkken) alır; uygulanacak mod döner, yoksa null. */
    fun applyDriveMode(intent: Intent?): Int? {
        if (!enabled || intent?.hasExtra(EXTRA_DRIVE_MODE) != true) return null
        driveMode = intent.getIntExtra(EXTRA_DRIVE_MODE, 1).coerceIn(0, 6)
        return driveMode
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
        driveMode,     // sürüş modu (varsayılan Normal)
    )

    const val LOCKED = true
    const val TIRE_WARNING = false

    const val DRIVER_SEAT_LEVEL = 2
    const val PASSENGER_SEAT_LEVEL = 0
    const val DRIVER_TEMP = 22.0
    const val PASSENGER_TEMP = 24.5
    const val FAN_LEVEL = 3
    const val DRIVER_VENT_LEVEL = 1
    const val PASSENGER_VENT_LEVEL = 0

    /** Konfor kutucukları: perde açık, arka cam ısıtma açık, ön cam ve direksiyon ısıtma kapalı. */
    fun comfort(): ComfortReading = ComfortReading(
        connected = true, sunshade = SunshadeState.OPEN, frontDefrost = false, rearDefrost = true, steerHeat = false,
    )

    /**
     * Anlık tüketim için 60 sn'lik döngü: duruş, kalkış (düşük hızda absürt değerler), seyir (gürültülü ~7), kısa
     * ani yükseliş (eşik üstü 30+ sınırı) ve tekrar duruş. `launcher_demo_moving false` hep duruyor gösterir.
     */
    private fun tripLive(): Triple<Boolean, Int, Double?> {
        if (moving == false) return Triple(false, 0, null)
        val t = (System.currentTimeMillis() / 1000 % 60).toInt()
        return when {
            t < 6 -> Triple(false, 0, null)
            t < 18 -> Triple(true, 8 + (t - 6), listOf(95.4, 45.0, 30.8, 21.0, 17.5)[t % 5])
            t < 48 -> Triple(true, 52 + t % 7, 6.5 + (t % 4) * 0.4 + if (t in 36..37) 25.0 else 0.0)
            t < 54 -> Triple(true, 25, 12.0)
            else -> Triple(false, 0, null)
        }
    }

    fun trip(): TripData {
        val (isMoving, speed, instant) = tripLive()
        return tripBase().copy(moving = isMoving, speedKmh = speed, instantL100 = instant)
    }

    private fun tripBase(): TripData = TripData(
        connected = true, moving = true, instantL100 = 6.8, driveAvgL100 = 7.4, tripKm = 53.8,
        avgL100 = 7.9, avgSpeedKmh = 45, batteryV = 14.1, coolantC = 89, lowFuel = false,
        serviceKm = 3978, serviceDays = 155, pm25In = 1, pm25Out = 4,
    )

    /** Ses çalıyormuş gibi; konum gerçek zamanla ilerlemesin diye her çağrıda aynı başlangıç. */
    fun media(): MediaState = MediaState(
        true, true, true,
        "Yolun Sonu", "Demo Sanatçı", "com.google.android.apps.youtube.music",
        72_000L, 215_000L, null,
    )
}
