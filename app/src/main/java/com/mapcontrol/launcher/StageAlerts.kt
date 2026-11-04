package com.mapcontrol.launcher

/**
 * Sahne köşe notlarının görünürlük kuralları (saf, JVM'de sınanır). İlke (4 Kasım kararı): her şey yolundaysa sakin ekran;
 * araç sağlığı ve hava kalitesi köşeleri normalde gizli, yalnızca bir şey olunca çıkar.
 */
internal object StageCorners {

    /** Bakım "yaklaştı" sayılan kalan mesafe (km); geçmiş bakım (0 ve altı) her zaman sayılır. */
    const val SERVICE_NEAR_KM = HealthMath.SOON_KM

    /**
     * Kötü hava eşiği: kabin içi PM2.5 (µg/m³) bu değer ve üstündeyse hava köşesi çıkar. Hava kutucuğunun "Kötü" derecesiyle
     * aynı ([AirGrade.POOR]): uyarı renginde çıkan not "Orta" yazmasın.
     */
    const val AIR_BAD_PM25 = AirGrade.MODERATE_MAX + 1

    /** Köşeyi açan sağlık uyarıları. Düşük yakıt yok (menzil bloğunda ve bildirim kapsülünde); bakım ayrıca [serviceNear]. */
    private val WARNINGS = setOf(HealthMath.Issue.ENGINE_HOT, HealthMath.Issue.BATTERY_LOW, HealthMath.Issue.BATTERY_HIGH)

    /** Bakım yakın: kalan <= [SERVICE_NEAR_KM] km ya da bakım zamanı geçmiş (km ya da gün 0'a inmiş). */
    fun serviceNear(serviceKm: Int?, serviceDays: Int?): Boolean =
        (serviceKm != null && serviceKm <= SERVICE_NEAR_KM) || HealthMath.serviceOverdue(serviceKm, serviceDays)

    /** Akü / motor suyu uyarısı var mı (okunamayan değer uyarı sayılmaz). */
    fun healthWarning(batteryV: Double?, coolantC: Int?): Boolean =
        HealthMath.issues(batteryV, coolantC, null, null, null).any { it in WARNINGS }

    /** Sağlık notunda adlandırılan sorunlar, önem sırasıyla (ilki başlık olur). Düşük yakıt yok (kendi kapsülü var). */
    enum class HealthNote { ENGINE_HOT, BATTERY_LOW, BATTERY_HIGH, TIRE, SERVICE_OVERDUE, SERVICE_NEAR }

    fun healthNotes(batteryV: Double?, coolantC: Int?, serviceKm: Int?, serviceDays: Int?, tireAlert: Boolean): List<HealthNote> = buildList {
        val issues = HealthMath.issues(batteryV, coolantC, null, null, null)
        if (HealthMath.Issue.ENGINE_HOT in issues) add(HealthNote.ENGINE_HOT)
        if (HealthMath.Issue.BATTERY_LOW in issues) add(HealthNote.BATTERY_LOW)
        if (HealthMath.Issue.BATTERY_HIGH in issues) add(HealthNote.BATTERY_HIGH)
        if (tireAlert) add(HealthNote.TIRE)
        when {
            HealthMath.serviceOverdue(serviceKm, serviceDays) -> add(HealthNote.SERVICE_OVERDUE)
            serviceNear(serviceKm, serviceDays) -> add(HealthNote.SERVICE_NEAR)
        }
    }

    /** Sağlık köşesi: bakım yakın, sağlık uyarısı ya da lastik uyarısı varsa. */
    fun healthShown(batteryV: Double?, coolantC: Int?, serviceKm: Int?, serviceDays: Int?, tireAlert: Boolean): Boolean =
        healthNotes(batteryV, coolantC, serviceKm, serviceDays, tireAlert).isNotEmpty()

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

/** Sahne bildirim kapsülünün türü; sıra önem sırasıdır (küçük = daha önemli). */
internal enum class NoticeKind { DOOR, HOOD, TRUNK, TIRE, WINDOW, SUNROOF, FUEL_CAP, FUEL_LOW, SERVICE }

/**
 * Bir bildirim kapsülü. [part]: kapı / cam için hangi parça; [strong]: belirgin (dolu) ton — araç parktayken açık bagaj, cam
 * ya da cam tavan (inip gitmeden önce fark edilsin, 4 Kasım kararı §7.6).
 */
internal data class Notice(val kind: NoticeKind, val part: BodyPart? = null, val strong: Boolean = false)

/**
 * Aracın altındaki bildirim şeridinin saf kuralları (JVM'de sınanır): hangi kapsüller, hangi sırada, kaçı görünür.
 * Kaynaklar: açık kapılar (her biri ayrı), kaput, bagaj, lastik uyarısı, açık camlar (her biri ayrı), cam tavan, yakıt kapağı,
 * düşük yakıt ve yaklaşan bakım. Sıra: belirgin olanlar önce, sonra [NoticeKind] önem sırası, aynı türde [BodyPart] sırası.
 */
internal object StageNotices {

    /** Aynı anda görünen en çok kapsül; fazlası tek bir "+N" kapsülünde toplanır. */
    const val MAX_VISIBLE = 3

    fun build(
        openings: Openings?, fuelCapOpen: Boolean, tireAlert: Boolean, lowFuel: Boolean, serviceNear: Boolean, parked: Boolean,
    ): List<Notice> {
        val out = mutableListOf<Notice>()
        val o = openings ?: Openings.NONE
        if (o.doorFl) out += Notice(NoticeKind.DOOR, BodyPart.DOOR_LF)
        if (o.doorFr) out += Notice(NoticeKind.DOOR, BodyPart.DOOR_RF)
        if (o.doorRl) out += Notice(NoticeKind.DOOR, BodyPart.DOOR_LR)
        if (o.doorRr) out += Notice(NoticeKind.DOOR, BodyPart.DOOR_RR)
        if (o.hood) out += Notice(NoticeKind.HOOD)
        if (o.trunk) out += Notice(NoticeKind.TRUNK, strong = parked)
        if (tireAlert) out += Notice(NoticeKind.TIRE)
        o.windows.forEach { out += Notice(NoticeKind.WINDOW, it, strong = parked) }
        if (o.sunroof) out += Notice(NoticeKind.SUNROOF, strong = parked)
        if (fuelCapOpen) out += Notice(NoticeKind.FUEL_CAP)
        if (lowFuel) out += Notice(NoticeKind.FUEL_LOW)
        if (serviceNear) out += Notice(NoticeKind.SERVICE)
        return out.sortedWith(compareBy<Notice>({ !it.strong }, { it.kind.ordinal }, { it.part?.ordinal ?: -1 }))
    }

    /**
     * Görünen kapsüller ve "+N" sayısı. [fit]: genişliğe sığan en çok kapsül (çağıran ölçer); fazlası varsa "+N" için yer
     * açılır. Hiç sığmazsa ilk kapsül yine gösterilir (en önemli bildirim kaybolmaz).
     */
    fun <T> visible(all: List<T>, fit: Int = MAX_VISIBLE): Pair<List<T>, Int> {
        val n = maxOf(1, minOf(fit, MAX_VISIBLE))
        if (all.size <= n) return all to 0
        return all.take(n) to all.size - n
    }
}

/** En yakın benzin istasyonu araması: önce Yandex Haritalar, açılmazsa genel harita (geo:) araması. */
internal object NearestStation {
    /** Yandex arama terimi: "АЗС" (benzin istasyonu kategorisi; Türkçe arayüzde de tanınıyor). */
    const val YANDEX_QUERY = "АЗС"
    const val GEO_QUERY = "benzin istasyonu"

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun yandexUri(): String = "yandexmaps://maps.yandex.ru/?text=" + encode(YANDEX_QUERY)

    fun geoUri(): String = "geo:0,0?q=" + encode(GEO_QUERY)
}
