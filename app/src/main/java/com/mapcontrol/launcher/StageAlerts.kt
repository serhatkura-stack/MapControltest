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

/**
 * Sahne aracının yeri ve boyu (saf, JVM'de sınanır). Araç, üstteki engellerin (menzil satırları, kilit hapı) altında ve alttaki
 * bildirim şeridi payının üstünde kalan en büyük genişliği alır ([maxW]'den [minW]'ye); o genişlikte yatayda ortaya en yakın
 * sığan konum seçilir, dikeyde kalan boşluğun ortasına oturur. Köşe notları engel sayılmaz (yalnızca sorun varken çıkarlar,
 * cam zeminle aracın üstünde dururlar); böylece araç not çıkınca zıplamaz.
 */
internal object StageFit {

    /** Sahne pikseli dikdörtgen. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * [areaLeft]..[areaRight]: aracın yatay sınırı; [minTop]..[maxBottom]: dikey sınır; [above]: üstteki engeller (araç bunlarla
     * yatayda çakışıyorsa üstü engelin altına [gap] kadar iner); [aspect] = yükseklik / genişlik. Hiçbir genişlik sığmazsa
     * [minW] ortada, [minTop]'ta döner (çağıran yine çizer; taşma en küçük boyda olur).
     */
    fun car(
        areaLeft: Float, areaRight: Float, minTop: Float, maxBottom: Float,
        maxW: Float, minW: Float, aspect: Float, gap: Float, above: List<Box>, step: Float = 2f,
    ): Box {
        fun topFor(x: Float, w: Float): Float =
            above.filter { it.left < x + w + gap && it.right > x - gap }.maxOfOrNull { it.bottom + gap }?.coerceAtLeast(minTop) ?: minTop
        var w = minOf(maxW, areaRight - areaLeft)
        while (w >= minW) {
            val h = w * aspect
            val center = (areaLeft + areaRight) / 2f - w / 2f
            var d = 0f
            val span = areaRight - areaLeft - w
            while (d <= span) {
                for (x in if (d == 0f) listOf(center) else listOf(center + d, center - d)) {
                    if (x < areaLeft - 0.5f || x + w > areaRight + 0.5f) continue
                    val top = topFor(x, w)
                    if (top + h <= maxBottom) {
                        val y = top + (maxBottom - top - h) / 2f
                        return Box(x, y, x + w, y + h)
                    }
                }
                d += step
            }
            w -= step
        }
        val x = (areaLeft + areaRight) / 2f - minW / 2f
        return Box(x, minTop, x + minW, minTop + minW * aspect)
    }
}
