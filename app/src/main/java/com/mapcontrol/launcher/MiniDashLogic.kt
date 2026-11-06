package com.mapcontrol.launcher

/**
 * Mini göstergenin (bölünmüş ekranın bir yarısı, bkz. [MiniDashActivity]) saf kuralları; JVM'de sınanır.
 *
 * Uyarı satırı yalnızca bölünmüş ekranda BAŞKA YERDE görünmeyen şeyleri söyler (ana ekran o sırada gizli, fabrika çubuğu
 * yalnızca klimayı gösterir): motor suyu sıcak, akü zayıf / yüksek, düşük lastik (psi ile), yakıt kapağı açık, düşük yakıt,
 * yaklaşan / geçmiş bakım ve kötü kabin havası. Eşikler Sahne ile aynıdır ([HealthMath], [StageCorners], [TireDecode]); yeni
 * eşik yoktur.
 */
internal object MiniAlerts {

    /**
     * Uyarı türü; sıra önem sırasıdır (küçük = önce): motor suyu / akü > düşük lastik > yakıt kapağı > yakıt az > bakım > hava.
     * Bunların hiçbiri araç çiziminde görünmez (§10: çizimde görünen kapı, bagaj, kaput, cam yazılmaz).
     */
    enum class Kind { ENGINE_HOT, BATTERY_LOW, BATTERY_HIGH, TIRE, FUEL_CAP, FUEL_LOW, SERVICE_OVERDUE, SERVICE_NEAR, AIR_BAD }

    /**
     * Tek uyarı ve metni kuracak değerler ([tireLow] lastikte, [serviceKm] bakımda, [pm25] havada, [coolantC] motor suyunda,
     * [batteryV] aküde).
     */
    data class Item(
        val kind: Kind,
        val tireLow: TireLow? = null,
        val serviceKm: Int? = null,
        val pm25: Int? = null,
        val coolantC: Int? = null,
        val batteryV: Double? = null,
    )

    /** Satırda aynı anda adlandırılan en çok uyarı; fazlası "+N" olur. */
    const val MAX_NAMED = 2

    fun build(
        tires: TireReading?,
        lowFuel: Boolean?,
        serviceKm: Int?,
        serviceDays: Int?,
        pm25In: Int?,
        batteryV: Double? = null,
        coolantC: Int? = null,
        fuelCapOpen: Boolean = false,
        batteryHighHeld: Boolean = false,
    ): List<Item> {
        val low = TireDecode.low(tires)
        return build(TireDecode.alertShown(tires?.warning, low), low, lowFuel, serviceKm, serviceDays, pm25In, batteryV, coolantC, fuelCapOpen, batteryHighHeld)
    }

    /**
     * Sahne uyarı şeridi de aynı kuralları kullanır (§10): araç kartının özetinden gelen lastik uyarısı ([tireAlert],
     * [tireLow]) ile. Kapı, bagaj, kaput, cam ve cam tavan burada yoktur; onları araç çizimi gösterir. Motor suyu ve akü
     * [HealthMath] eşikleriyle (okunamayan değer uyarı sayılmaz); yakıt kapağı çizimde yoktur, şeritte yazılır.
     */
    fun build(
        tireAlert: Boolean,
        tireLow: TireLow?,
        lowFuel: Boolean?,
        serviceKm: Int?,
        serviceDays: Int?,
        pm25In: Int?,
        batteryV: Double? = null,
        coolantC: Int? = null,
        fuelCapOpen: Boolean = false,
        /** Aşırı gerilim 30 sn sürdü mü ([TripData.batteryHighHeld]); değilse "Akü yüksek" çıkmaz. */
        batteryHighHeld: Boolean = false,
    ): List<Item> = buildList {
        val health = HealthMath.issues(batteryV, coolantC, null, null, null, batteryHighHeld)
        if (HealthMath.Issue.ENGINE_HOT in health) add(Item(Kind.ENGINE_HOT, coolantC = coolantC))
        if (HealthMath.Issue.BATTERY_LOW in health) add(Item(Kind.BATTERY_LOW, batteryV = batteryV))
        if (HealthMath.Issue.BATTERY_HIGH in health) add(Item(Kind.BATTERY_HIGH, batteryV = batteryV))
        if (tireAlert) add(Item(Kind.TIRE, tireLow = tireLow))
        if (fuelCapOpen) add(Item(Kind.FUEL_CAP))
        if (lowFuel == true) add(Item(Kind.FUEL_LOW))
        when {
            HealthMath.serviceOverdue(serviceKm, serviceDays) -> add(Item(Kind.SERVICE_OVERDUE))
            StageCorners.serviceNear(serviceKm, serviceDays) -> add(Item(Kind.SERVICE_NEAR, serviceKm = serviceKm))
        }
        if (StageCorners.airShown(pm25In)) add(Item(Kind.AIR_BAD, pm25 = pm25In))
    }

    /** Adlandırılan uyarılar ve "+N" sayısı (en önemli uyarı hiç kaybolmaz). */
    fun visible(all: List<Item>): Pair<List<Item>, Int> = StageNotices.visible(all, MAX_NAMED)
}

/**
 * Navigasyon ipucu: sonraki dönüş ve varış. [maneuver] kısa yön metni ("Sağa dönün"), [distanceM] dönüşe kalan metre,
 * [arrivalMinutesOfDay] varış saati (gün içi dakika, 0..1439); bilinmeyen alan null.
 */
internal data class NavHint(
    val maneuver: String?,
    val distanceM: Int?,
    val arrivalMinutesOfDay: Int?,
)

/** Navigasyon satırının saf biçimlendirmesi. */
internal object MiniNav {

    /** Satır yalnızca gösterecek bir şey varken görünür. */
    fun shown(h: NavHint?): Boolean = h != null && (!h.maneuver.isNullOrBlank() || h.distanceM != null || h.arrivalMinutesOfDay != null)

    /** Mesafe: 1 km altı 10 m'ye yuvarlanır ("350 m"); üstü 0,1 km ("1,2 km", 10 km üstü tam sayı). Geçersizse null. */
    fun distance(m: Int?, decimalSep: Char = ','): Pair<String, Boolean>? {
        if (m == null || m < 0) return null
        val meters = (m + 5) / 10 * 10
        if (meters < 1_000) return meters.toString() to false
        val tenths = (m + 50) / 100
        if (tenths >= 100) return ((m + 500) / 1_000).toString() to true
        return "${tenths / 10}$decimalSep${tenths % 10}" to true
    }

    /**
     * [NavigationSource] durumundan ipucu (mini göstergenin gerçek kaynağı, [NavigationMiniSource]): rota yoksa null; yön
     * metni "manevra · çıkış · yol" ([label] manevranın kısa adı, bilinmeyende null), mesafe metre, varış "HH:mm" → gün içi
     * dakika (okunamazsa null).
     */
    fun hint(s: NavigationState, label: (NavManeuver) -> String?, exit: (Int) -> String): NavHint? {
        if (!s.active) return null
        val text = listOfNotNull(label(s.maneuver), s.roundaboutExit?.let(exit), s.street?.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifEmpty { null }
        return NavHint(text, s.distance?.meters, minutesOfDay(s.arrivalTime))
    }

    /** "14:52" → 892; geçersizse null. */
    fun minutesOfDay(hhmm: String?): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(hhmm ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    /** Varış saati "HH:mm" (24 saat) ya da "h:mm" (12 saat, AM/PM çağıranda); geçersizse null. */
    fun arrival(minutesOfDay: Int?, is24: Boolean = true): String? {
        if (minutesOfDay == null || minutesOfDay !in 0 until 24 * 60) return null
        val h = minutesOfDay / 60
        val m = minutesOfDay % 60
        val hh = if (is24) h else (if (h % 12 == 0) 12 else h % 12)
        return (if (is24) "%02d:%02d" else "%d:%02d").format(hh, m)
    }
}

/**
 * Pencere boyutuna göre ölçüler (dp). Hedef bir yarı: ~960 × 610 (fabrika çubuğu altta ~110 px alabilir). Daha dar ya da
 * kısa pencerede (ör. üçte bir bölme) düğmeler küçülür ama sürüşte dokunma alanı [MIN_TOUCH_DP] altına inmez.
 */
internal data class MiniLayout(
    val paddingDp: Int,
    val clockSp: Float,
    val valueSp: Float,
    val playDp: Int,
    val skipDp: Int,
    val toggleHeightDp: Int,
    /** Medya panelinde kapak resmi (yükseklik yetiyorsa). */
    val showArtwork: Boolean,
    /** Üst satırda "Menzil" / "Yakıt" etiketleri (yer yetiyorsa). */
    val showLabels: Boolean,
    /**
     * Geniş ve kısa pencere (Tiggo 8 üst yarısı, 2560 × ~700 px ≈ 1707 × ~470 dp): tek satır yatay yerleşim (saat + menzil |
     * medya | rota / uyarı | koltuk tuşları), Tiggo 7 ana ekranının oranına benzer. 960 genişlikli yarılarda hep false.
     */
    val wide: Boolean = false,
) {
    companion object {
        const val MIN_TOUCH_DP = 64

        /** Geniş satır yerleşimi: pencere en az bu kadar geniş ve en-boy oranı en az bu kadar yatay. */
        const val WIDE_MIN_WIDTH_DP = 1200
        const val WIDE_MIN_ASPECT = 2.4f

        /** Saf kural (birim testlenir): 960 genişlikli yarılar (≈ 1,6) ve dar pencereler false. */
        fun isWide(widthDp: Int, heightDp: Int): Boolean =
            heightDp > 0 && widthDp >= WIDE_MIN_WIDTH_DP && widthDp.toFloat() / heightDp >= WIDE_MIN_ASPECT

        fun forSize(widthDp: Int, heightDp: Int): MiniLayout {
            val wide = isWide(widthDp, heightDp)
            val compact = widthDp < 760 || heightDp < 520
            val tight = heightDp < 440
            return MiniLayout(
                paddingDp = if (compact) 16 else 24,
                clockSp = if (compact) 48f else 60f,
                valueSp = if (compact) 28f else 34f,
                playDp = if (tight) 72 else if (compact) 76 else 88,
                skipDp = if (tight || compact) MIN_TOUCH_DP else 72,
                toggleHeightDp = if (tight) 88 else if (compact) 104 else 120,
                showArtwork = if (wide) heightDp >= 360 else heightDp >= 520 && widthDp >= 700,
                showLabels = widthDp >= 640,
                wide = wide,
            )
        }
    }
}
