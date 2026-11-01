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
        if (enabled && intent.hasExtra(EXTRA_WIFI)) wifi = intent.getBooleanExtra(EXTRA_WIFI, false)
        if (enabled && intent.hasExtra(EXTRA_REAR_OFF)) rearOff = intent.getBooleanExtra(EXTRA_REAR_OFF, false)
    }

    /** Wi-Fi paneli önizlemesi: `--ez launcher_demo_wifi true` listeye örnek ağlar ekler (emülatörde tek sanal ağ var). */
    const val EXTRA_WIFI = "launcher_demo_wifi"

    @Volatile
    private var wifi = false

    internal fun wifiScans(): List<WifiScan> = if (!enabled || !wifi) emptyList() else listOf(
        WifiScan("Ev_5G", 4, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("Kafe Misafir", 3, "[ESS]"),
        WifiScan("TurkNet_A1B2", 3, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("Ofis", 2, "[WPA3-SAE][ESS]"),
        WifiScan("iPhone (Serhat)", 2, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("Komşu_2.4", 1, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("DIRECT-roku", 1, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("Kitap Cafe", 1, "[ESS]"),
        WifiScan("Ev_5G", 2, "[WPA2-PSK-CCMP][ESS]"),
        WifiScan("", 3, "[ESS]"),
    )

    /** Demo arka hoparlör durumu (`--ez launcher_demo_rear_off true`): fader ön uçta, arka kapalı. Verilmezse açık. */
    const val EXTRA_REAR_OFF = "launcher_demo_rear_off"

    @Volatile
    var rearOff = false
        private set

    /** İstek sürüş modu içeriyorsa (yalnızca demo açıkken) alır; uygulanacak mod döner, yoksa null. */
    fun applyDriveMode(intent: Intent?): Int? {
        if (!enabled || intent?.hasExtra(EXTRA_DRIVE_MODE) != true) return null
        driveMode = intent.getIntExtra(EXTRA_DRIVE_MODE, 1).coerceIn(0, 6)
        return driveMode
    }

    /**
     * Gövde animasyonu önizlemesi: `--es launcher_demo_open "lf,rf,lr,rr,trunk,sunroof,hood"` (virgülle; boş = hepsi kapalı).
     * Çalışırken yeni istekle de değiştirilebilir; araç kartı anında yeniden çizer.
     */
    const val EXTRA_OPEN = "launcher_demo_open"

    @Volatile
    var openParts: Set<String> = emptySet()
        private set

    /** Yandan "kil beyazı" araç çiziminin önizlemesi: `--ez launcher_demo_side true` (varsayılan değil, yalnızca demo). */
    const val EXTRA_SIDE = "launcher_demo_side"

    @Volatile
    var sidePreview = false
        private set

    /** Koltuk seviyesi önizlemesi: `--ei launcher_demo_seat N` (0..3) tüm ısıtma/soğutma koltuklarını N yapar. */
    const val EXTRA_SEAT = "launcher_demo_seat"

    /** Araç sağlığı önizlemesi: `--es launcher_demo_health battery|hot|service|overdue|multi`; verilmezse her şey normal. */
    const val EXTRA_HEALTH = "launcher_demo_health"

    @Volatile
    var healthProblem: String? = null
        private set

    @Volatile
    var seatOverride: Int? = null
        private set

    /** Araç kartı bunu atar; demo isteği değişince ana iş parçacığında çağrılır. */
    @Volatile
    var onBodyChanged: Runnable? = null

    fun applyPreview(intent: Intent?) {
        if (!enabled || intent == null) return
        if (intent.hasExtra(EXTRA_OPEN)) {
            openParts = intent.getStringExtra(EXTRA_OPEN).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            onBodyChanged?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
        if (intent.hasExtra(EXTRA_CLIMATE)) climate = intent.getStringExtra(EXTRA_CLIMATE).orEmpty().split(',').map { it.trim().toDoubleOrNull() }
        if (intent.hasExtra(EXTRA_SIDE)) sidePreview = intent.getBooleanExtra(EXTRA_SIDE, false)
        if (intent.hasExtra(EXTRA_HEALTH)) healthProblem = intent.getStringExtra(EXTRA_HEALTH)
        if (intent.hasExtra(EXTRA_SEAT)) seatOverride = intent.getIntExtra(EXTRA_SEAT, 0).coerceIn(0, 3)
    }

    private fun open(key: String) = if (key in openParts) 1 else 0

    /** Park halinde, kapılar kapalı, Normal mod, tavan kapalı bir Tiggo 7. */
    fun vehicle(): VehicleMetricsSnapshot = VehicleMetricsSnapshot(
        true,   // connected
        0, 0,   // hız
        18_420, // km
        620,    // yakıt, onda bir (%62,0) — araçtaki gibi
        540,    // menzil km
        0, 2, 0,
        open("lf"), open("rf"), open("lr"), open("rr"), open("trunk"), // kapılar ve bagaj (varsayılan kapalı)
        if ("sunroof" in openParts) 3 else 2, if ("sunroof" in openParts) 5 else 1, 1,       // tavan (varsayılan kapalı)
        0, 0, 0,
        driveMode,     // sürüş modu (varsayılan Normal)
    )

    const val LOCKED = true
    const val TIRE_WARNING = false

    val DRIVER_SEAT_LEVEL get() = seatOverride ?: 2
    val PASSENGER_SEAT_LEVEL get() = seatOverride ?: 0
    /** Klima önizlemesi: `--es launcher_demo_climate "17.5,27.5,7"` sürücü °C, yolcu °C, fan kademesi (boş alan varsayılan). */
    const val EXTRA_CLIMATE = "launcher_demo_climate"

    @Volatile
    private var climate: List<Double?> = emptyList()

    val DRIVER_TEMP: Double get() = climate.getOrNull(0) ?: 22.0
    val PASSENGER_TEMP: Double get() = climate.getOrNull(1) ?: 24.5
    val FAN_LEVEL: Int get() = climate.getOrNull(2)?.toInt() ?: 3
    val DRIVER_VENT_LEVEL get() = seatOverride ?: 1
    val PASSENGER_VENT_LEVEL get() = seatOverride ?: 2

    /** Konfor kutucukları: perde açık, arka cam ısıtma açık, ön cam kapalı. */
    fun comfort(): ComfortReading = ComfortReading(
        connected = true, sunshade = SunshadeState.OPEN, frontDefrost = false, rearDefrost = true,
        trunk = TrunkReading(position = 0, latchOpen = 0, motion = TrunkMath.MOTION_STOPPED),
        keys = ClimateKeysReading(ac = true, auto = true, recirc = false, power = true),
        heat = HeatReading(steering = false, windshield = false),
        sound = SoundFieldReading(balance = SoundFieldMath.CENTER, fade = if (rearOff) SoundFieldMath.FRONT_EXTREME else SoundFieldMath.CENTER),
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
        val base = tripBase()
        val sick = when (healthProblem) {
            "battery" -> base.copy(batteryV = 11.4)
            "hot" -> base.copy(coolantC = 118)
            "service" -> base.copy(serviceKm = 640, serviceDays = 22)
            "overdue" -> base.copy(serviceKm = 0, serviceDays = 0)
            "multi" -> base.copy(batteryV = 11.4, coolantC = 118, serviceKm = 640)
            else -> base
        }
        return sick.copy(moving = isMoving, speedKmh = speed, instantL100 = instant)
    }

    private fun tripBase(): TripData = TripData(
        connected = true, moving = true, instantL100 = 6.8, driveAvgL100 = 7.4, tripKm = 53.8,
        avgL100 = 7.9, avgSpeedKmh = 45, batteryV = 14.1, coolantC = 89, lowFuel = false,
        serviceKm = 3978, serviceDays = 155, pm25In = 1, pm25Out = 4,
        totalKm = 18_420, totalFuelL = 1_284.6, drivingRunMin = 47, drivingResetMin = 3_552,
    )

    /** Demo kapak: ekran görüntülerinde boş yer tutucu yerine gerçekçi bir albüm resmi (bir kez çizilir). */
    private val artwork: android.graphics.Bitmap by lazy {
        val size = 240
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF3B1E6E.toInt(), 0xFFFF7A3D.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0x33FFFFFF
        c.drawCircle(size * 0.64f, size * 0.42f, size * 0.30f, p)
        p.color = 0x55000000
        c.drawCircle(size * 0.30f, size * 0.78f, size * 0.22f, p)
        bmp
    }

    /** Ses çalıyormuş gibi; konum gerçek zamanla ilerlemesin diye her çağrıda aynı başlangıç. */
    fun media(): MediaState = MediaState(
        true, true, true,
        "Yolun Sonu", "Demo Sanatçı", "com.google.android.apps.youtube.music",
        72_000L, 215_000L, artwork,
    )
}
