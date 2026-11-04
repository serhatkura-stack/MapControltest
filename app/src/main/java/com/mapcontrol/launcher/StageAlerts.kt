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

/** Sahne aracının yeri ve boyu (saf, JVM'de sınanır); bkz. [centered]. */
internal object StageFit {

    /** Sahne pikseli dikdörtgen. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Sahnenin tam ortasındaki araç (§10/§11): yatayda [centerX]'e sabit, en büyük genişlik [maxW]'den [minW]'ye aranır.
     * Üstteki engellerle (omuzlardaki menzil ve kilit) yatayda çakışırsa araç onların altına iner; çakışma hesabında aracın
     * yuvarlak köşeleri için her iki yandan genişliğin [cornerInset] oranı boş sayılır (omuz köşeye biraz girebilir). Dikeyde
     * kalan boşluğun ortasına oturur. Hiçbir genişlik sığmazsa [minW] en üstte döner.
     */
    fun centered(
        centerX: Float, minTop: Float, maxBottom: Float, maxW: Float, minW: Float, aspect: Float, gap: Float,
        above: List<Box>, cornerInset: Float = 0f, step: Float = 2f,
    ): Box {
        var w = maxW
        while (w >= minW) {
            val h = w * aspect
            val x = centerX - w / 2f
            val inset = w * cornerInset
            val top = above.filter { it.left < x + w - inset + gap && it.right > x + inset - gap }
                .maxOfOrNull { it.bottom + gap }?.coerceAtLeast(minTop) ?: minTop
            if (top + h <= maxBottom) {
                val y = top + (maxBottom - top - h) / 2f
                return Box(x, y, x + w, y + h)
            }
            w -= step
        }
        val x = centerX - minW / 2f
        return Box(x, minTop, x + minW, minTop + minW * aspect)
    }
}

/**
 * "+N" kuralı (saf, JVM'de sınanır): Sahne uyarı şeridi ve mini gösterge en çok [MAX_VISIBLE] maddeyi adlandırır, fazlası tek
 * bir "+N" olur. §10 (4 Kasım, son karar): araç çiziminde görünen şeyler (kapı, bagaj, kaput, cam, cam tavan) şeride yazılmaz;
 * şerit yalnızca arabada görünmeyenler içindir ([MiniAlerts]: düşük lastik, bakım, yakıt az, kötü hava).
 */
internal object StageNotices {

    /** Aynı anda adlandırılan en çok madde; fazlası tek bir "+N" olur. */
    const val MAX_VISIBLE = 2

    /**
     * Görünen maddeler ve "+N" sayısı. [fit]: genişliğe sığan en çok madde (çağıran ölçer). Hiç sığmazsa ilk madde yine
     * gösterilir (en önemli uyarı kaybolmaz).
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
