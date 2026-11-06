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
     * Tek uyarı ve metni kuracak değerler ([tireLow] lastikte, [serviceKm] bakımda, [pm25] havada, [batteryV] aküde).
     */
    data class Item(
        val kind: Kind,
        val tireLow: TireLow? = null,
        val serviceKm: Int? = null,
        val pm25: Int? = null,
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
        fuelCapOpen: Boolean = false,
        batteryHighHeld: Boolean = false,
    ): List<Item> {
        val low = TireDecode.low(tires)
        return build(TireDecode.alertShown(tires?.warning, low), low, lowFuel, serviceKm, serviceDays, pm25In, batteryV, fuelCapOpen, batteryHighHeld)
    }

    /**
     * Sahne uyarı şeridi de aynı kuralları kullanır (§10): araç kartının özetinden gelen lastik uyarısı ([tireAlert],
     * [tireLow]) ile. Kapı, bagaj, kaput, cam ve cam tavan burada yoktur; onları araç çizimi gösterir. Akü [HealthMath]
     * eşikleriyle (okunamayan değer uyarı sayılmaz); motor suyu uyarısı derece sinyali doğrulanana dek yok ([CoolantStatus]);
     * yakıt kapağı çizimde yoktur, şeritte yazılır.
     */
    fun build(
        tireAlert: Boolean,
        tireLow: TireLow?,
        lowFuel: Boolean?,
        serviceKm: Int?,
        serviceDays: Int?,
        pm25In: Int?,
        batteryV: Double? = null,
        fuelCapOpen: Boolean = false,
        /** Aşırı gerilim 30 sn sürdü mü ([TripData.batteryHighHeld]); değilse "Akü yüksek" çıkmaz. */
        batteryHighHeld: Boolean = false,
    ): List<Item> = buildList {
        val health = HealthMath.issues(batteryV, null, null, null, batteryHighHeld)
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
 * Pencere boyutuna göre yerleşim (dp); saf kural, JVM'de sınanır. İki biçim vardır:
 *
 * - Bölünmüş yarı (Tiggo 7 sol yarı ~960 × 606 dp, serbest pencere başlığı altında): üstte saat + menzil + yakıt + düğmeler
 *   (tek satır), ortada araç sahnesi | koltuk tuşları sütunu, altta tam genişlikte ince medya şeridi.
 * - Geniş ve kısa pencere ([wide]; tam ekran 1920 × 648, Tiggo 8 üst yarısı 1707 × ~470): solda bilgi sütunu (saat, menzil,
 *   yakıt, rota, düğmeler), ortada araç sahnesi ve altında medya şeridi, sağda koltuk tuşları.
 *
 * Çalan bir şey yokken medya şeridi hiç yer tutmaz (yalnızca üstteki "Müzik aç" düğmesi kalır); araç ve koltuklar büyür.
 * Dokunma alanları sürüşte de [MIN_TOUCH_DP] altına inmez.
 */
internal data class MiniLayout(
    val wide: Boolean,
    val paddingDp: Int,
    /** Bloklar arası boşluk. */
    val gapDp: Int,
    val clockSp: Float,
    /** Menzil ve yakıt rakamları; birim ("km") bunun yarısı. */
    val valueSp: Float,
    /** Bölünmüş biçimde üst satırın yüksekliği (geniş biçimde 0). */
    val headerDp: Int,
    /** "Tam ekran" / "Müzik aç" düğmelerinde yazı (dar pencerede yalnızca simge, yine 64 dp kare). */
    val pillText: Boolean,
    /** Koltuk tuşları sütununun genişliği. */
    val seatsDp: Int,
    /** Geniş biçimde bilgi sütununun genişliği (bölünmüşte 0). */
    val infoDp: Int,
    /** Medya şeridi yüksekliği (çalan bir şey varken). */
    val mediaDp: Int,
    /** Kapak resmi kenarı; 0 = kapak yok (yer yetmiyorsa). */
    val coverDp: Int,
    val playDp: Int,
    val skipDp: Int,
    /** Koltuk görseli kenarı (tuş yüksekliğiyle büyür: uzun tuş boş görünmesin). */
    val seatArtDp: Int,
    /** Koltuk değeri ("Isıtma · Orta") yazı boyu. */
    val seatValueSp: Float,
    /** Medya şeridi varken her koltuk tuşunun beklenen yüksekliği (yerleşim ağırlıklarla aynı sonucu verir). */
    val seatDp: Int,
    /** Rota satırı yalnızca tam ekranda: bölünmüş ekranda harita zaten yanda, aynı bilgiyi tekrarlamaz. */
    val showNav: Boolean,
) {
    companion object {
        const val MIN_TOUCH_DP = 64

        /** Geniş satır yerleşimi: pencere en az bu kadar geniş ve en-boy oranı en az bu kadar yatay. */
        const val WIDE_MIN_WIDTH_DP = 1200
        const val WIDE_MIN_ASPECT = 2.4f

        /** Saf kural (birim testlenir): 960 genişlikli yarılar (≈ 1,6) ve dar pencereler false. */
        fun isWide(widthDp: Int, heightDp: Int): Boolean =
            heightDp > 0 && widthDp >= WIDE_MIN_WIDTH_DP && widthDp.toFloat() / heightDp >= WIDE_MIN_ASPECT

        /**
         * [heightDp] pencerenin içerik yüksekliği; [captionDp] serbest pencere başlığının içeriğin üstünü örttüğü pay
         * ([FreeformCaption.overlap]); içerik bu payın altından başlar.
         */
        fun forSize(widthDp: Int, heightDp: Int, captionDp: Int = 0): MiniLayout {
            val usableH = heightDp - captionDp.coerceAtLeast(0)
            val wide = isWide(widthDp, usableH)
            val compact = widthDp < 760 || usableH < 520
            val tight = usableH < 440
            val padding = if (compact) 16 else 24
            val gap = if (compact) 12 else 16
            val header = if (wide) 0 else if (compact) 72 else 84
            var media = if (tight) 88 else if (compact) 96 else if (wide && usableH >= 560) 120 else 108
            val contentH = usableH - 2 * padding
            // Koltuk sütunu: bölünmüşte araçla aynı satırda (medya şeridi altta, tam genişlik), genişte tam yükseklik.
            fun seat(mediaH: Int): Int =
                if (wide) (contentH - gap) / 2 else (contentH - header - gap - mediaH - gap - gap) / 2
            // Koltuklar sığmıyorsa medya şeridi en ince haline iner.
            if (seat(media) < 96) media = 88
            val seatH = seat(media)
            return MiniLayout(
                wide = wide,
                paddingDp = padding,
                gapDp = gap,
                clockSp = if (wide) (if (usableH >= 560) 80f else 64f) else if (compact) 48f else 60f,
                valueSp = if (wide) 48f else if (compact) 30f else 38f,
                headerDp = header,
                pillText = wide || widthDp >= 860,
                seatsDp = if (wide) (widthDp * 0.29f).toInt().coerceIn(420, 600) else (widthDp * 0.38f).toInt().coerceIn(260, 380),
                infoDp = if (wide) (widthDp * 0.2f).toInt().coerceIn(340, 420) else 0,
                mediaDp = media,
                coverDp = if (media >= 96) media - 24 else 0,
                playDp = if (tight) 72 else 80,
                skipDp = MIN_TOUCH_DP,
                seatArtDp = (seatH * 0.5f).toInt().coerceIn(56, 120),
                seatValueSp = if (seatH >= 200) 28f else 24f,
                seatDp = seatH,
                showNav = wide,
            )
        }
    }
}
