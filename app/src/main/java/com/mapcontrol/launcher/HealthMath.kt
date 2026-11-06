package com.mapcontrol.launcher

/**
 * Araç sağlığı özeti (saf mantık, JVM'de sınanır). Akü, motor suyu, yakıt uyarısı ve bakım aralığını tek karara
 * indirir: sorun yoksa sakin bir "Her şey normal"; sorun varsa en önemlisi başta olacak şekilde sorun listesi.
 * Yalnızca eldeki veriyi değerlendirir; okunamayan değer sorun sayılmaz.
 */
internal object HealthMath {

    /** Sorunlar önem sırasıyla (küçük sıra = daha önemli). */
    enum class Issue { ENGINE_HOT, BATTERY_LOW, BATTERY_HIGH, SERVICE_OVERDUE, LOW_FUEL, SERVICE_SOON }

    const val BATTERY_MIN_V = 12.0
    /** Motor çalışırken 14,5 V normal (araçta ölçüldü); aşırı gerilim ancak bunun üstünde ve [OverVoltageGate] süresince. */
    const val BATTERY_MAX_V = 15.3
    /** Aşırı gerilim uyarısı bu kadar sürmeden gösterilmez (şarj dalgalanması kısa sıçrar). */
    const val BATTERY_HIGH_HOLD_MS = 30_000L
    const val COOLANT_MAX_C = 110
    const val SOON_KM = 1_000
    const val SOON_DAYS = 30

    /** Hiçbir değer okunamıyorsa özet de yoktur (kutucuk "veri bekleniyor" gösterir). */
    fun hasData(batteryV: Double?, coolantC: Int?, lowFuel: Boolean?, serviceKm: Int?, serviceDays: Int?): Boolean =
        batteryV != null || coolantC != null || lowFuel != null || serviceKm != null || serviceDays != null

    /**
     * [batteryHighHeld]: aşırı gerilim [BATTERY_HIGH_HOLD_MS] boyunca sürdü mü ([OverVoltageGate], [TripData.batteryHighHeld]);
     * false iken eşik üstü gerilim de sorun sayılmaz. Düşük gerilim beklemeden sayılır.
     */
    fun issues(batteryV: Double?, coolantC: Int?, lowFuel: Boolean?, serviceKm: Int?, serviceDays: Int?, batteryHighHeld: Boolean): List<Issue> = buildList {
        if (coolantC != null && coolantC > COOLANT_MAX_C) add(Issue.ENGINE_HOT)
        if (batteryV != null && batteryV < BATTERY_MIN_V) add(Issue.BATTERY_LOW)
        if (batteryHighHeld && batteryV != null && batteryV > BATTERY_MAX_V) add(Issue.BATTERY_HIGH)
        if (serviceOverdue(serviceKm, serviceDays)) add(Issue.SERVICE_OVERDUE)
        if (lowFuel == true) add(Issue.LOW_FUEL)
        if (serviceSoon(serviceKm, serviceDays) && !serviceOverdue(serviceKm, serviceDays)) add(Issue.SERVICE_SOON)
    }

    /** Bakım zamanı geçti: kalan km ya da gün 0'a indi. */
    fun serviceOverdue(km: Int?, days: Int?): Boolean = (km != null && km <= 0) || (days != null && days <= 0)

    /** Bakım yaklaşıyor: <= 1000 km ya da <= 30 gün kaldı. */
    fun serviceSoon(km: Int?, days: Int?): Boolean = (km != null && km <= SOON_KM) || (days != null && days <= SOON_DAYS)

    /**
     * Eski kaydedilmiş düzenlerde kaldırılan widget anahtarları ve yeni karşılıkları: Akü ve Bakım "Araç sağlığı"na,
     * Toplam "Yolculuk"a katıldı.
     */
    val REMOVED_KEYS: Map<String, String> = mapOf(
        "battery" to "health",
        "service" to "health",
        "trip_totals" to "trip",
    )
}

/**
 * Aşırı gerilim süzgeci (saf, JVM'de sınanır): gerilim [HealthMath.BATTERY_MAX_V]'un üstüne çıktığı andan itibaren [holdMs]
 * kesintisiz üstte kalırsa true döner; eşik altına inen ya da okunamayan (null) değer sayacı sıfırlar.
 */
internal class OverVoltageGate(private val holdMs: Long = HealthMath.BATTERY_HIGH_HOLD_MS) {
    private var since = -1L

    fun update(volts: Double?, nowMs: Long): Boolean {
        if (volts == null || volts <= HealthMath.BATTERY_MAX_V) {
            since = -1L
            return false
        }
        if (since < 0L) since = nowMs
        return nowMs - since >= holdMs
    }
}
