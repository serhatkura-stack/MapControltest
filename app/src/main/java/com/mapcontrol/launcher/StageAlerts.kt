package com.mapcontrol.launcher

/**
 * Sahne köşe notlarının görünürlük kuralları (saf, JVM'de sınanır). İlke (4 Kasım kararı): her şey yolundaysa sakin ekran;
 * araç sağlığı ve hava kalitesi köşeleri normalde gizli, yalnızca bir şey olunca çıkar.
 */
internal object StageCorners {

    /** Bakım "yaklaştı" sayılan kalan mesafe (km); geçmiş bakım (0 ve altı) her zaman sayılır. */
    const val SERVICE_NEAR_KM = HealthMath.SOON_KM

    /** Kötü hava eşiği: kabin içi PM2.5 (µg/m³) bu değer ve üstündeyse hava köşesi çıkar. */
    const val AIR_BAD_PM25 = 55

    /** Köşeyi açan sağlık uyarıları. Düşük yakıt yok (menzil bloğunda ve bildirim kapsülünde); bakım ayrıca [serviceNear]. */
    private val WARNINGS = setOf(HealthMath.Issue.ENGINE_HOT, HealthMath.Issue.BATTERY_LOW, HealthMath.Issue.BATTERY_HIGH)

    /** Bakım yakın: kalan <= [SERVICE_NEAR_KM] km ya da bakım zamanı geçmiş (km ya da gün 0'a inmiş). */
    fun serviceNear(serviceKm: Int?, serviceDays: Int?): Boolean =
        (serviceKm != null && serviceKm <= SERVICE_NEAR_KM) || HealthMath.serviceOverdue(serviceKm, serviceDays)

    /** Akü / motor suyu uyarısı var mı (okunamayan değer uyarı sayılmaz). */
    fun healthWarning(batteryV: Double?, coolantC: Int?): Boolean =
        HealthMath.issues(batteryV, coolantC, null, null, null).any { it in WARNINGS }

    /** Sağlık köşesi: bakım yakın, sağlık uyarısı ya da lastik uyarısı varsa. */
    fun healthShown(batteryV: Double?, coolantC: Int?, serviceKm: Int?, serviceDays: Int?, tireAlert: Boolean): Boolean =
        serviceNear(serviceKm, serviceDays) || healthWarning(batteryV, coolantC) || tireAlert

    /** Hava köşesi: yalnızca kabin içi PM2.5 >= [AIR_BAD_PM25] iken. */
    fun airShown(pm25In: Int?): Boolean = pm25In != null && pm25In >= AIR_BAD_PM25
}
