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
    const val BATTERY_MAX_V = 15.0
    const val COOLANT_MAX_C = 110
    const val SOON_KM = 1_000
    const val SOON_DAYS = 30

    /** Hiçbir değer okunamıyorsa özet de yoktur (kutucuk "veri bekleniyor" gösterir). */
    fun hasData(batteryV: Double?, coolantC: Int?, lowFuel: Boolean?, serviceKm: Int?, serviceDays: Int?): Boolean =
        batteryV != null || coolantC != null || lowFuel != null || serviceKm != null || serviceDays != null

    fun issues(batteryV: Double?, coolantC: Int?, lowFuel: Boolean?, serviceKm: Int?, serviceDays: Int?): List<Issue> = buildList {
        if (coolantC != null && coolantC > COOLANT_MAX_C) add(Issue.ENGINE_HOT)
        if (batteryV != null && batteryV < BATTERY_MIN_V) add(Issue.BATTERY_LOW)
        if (batteryV != null && batteryV > BATTERY_MAX_V) add(Issue.BATTERY_HIGH)
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
