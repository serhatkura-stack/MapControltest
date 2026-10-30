package com.mapcontrol.hud

/**
 * HUD (VDBus modülü 327683, komut 200) yük kodlayıcıları ve beyaz liste. SAF kod: VDBus'a, Android'e dokunmaz,
 * JVM'de sınanır. Araca yazan tek sınıf launchertest'teki HudNavSender'dır; o da her diziyi göndermeden önce
 * [validate] ile geçirir. Şema: katalog/HUD-ARASTIRMA.md bölüm 3 (SVVDSCarInfo + SVVDSMap adaptör kodundan).
 *
 * Yük biçimi: `[altKomut, n, n adet bayt]`, her eleman 0..255; çok baytlı alanlar küçük-uç (little-endian).
 */
object HudPayload {

    /** VDEventCarInfo.MODULE_HUD. */
    const val MODULE_HUD = 327683

    /** HUDID.ID_HUD_IHU_INTERCONNECT. CMD_ID'ye başka değer ASLA yazılmaz (2, 3, 10-15 HUD ayarlarını bozar). */
    const val CMD_ID = 200

    const val SUB_CLEAR = 3
    const val SUB_NAV_STARTED = 52
    const val SUB_ROAD_NAME = 48
    const val SUB_MANEUVER = 49
    const val SUB_TOTAL = 51
    const val SUB_MANEUVER_ALT = 50
    const val SUB_TOTAL_ALT = 53

    /** Yol adı için azami UTF-8 bayt sayısı (adaptör kodunda 47). */
    const val ROAD_NAME_MAX_BYTES = 47

    /** İzinli alt komutlar. Başka her şey [validate] ve kodlayıcılarda reddedilir. */
    val ALLOWED_SUBCOMMANDS: Set<Int> = setOf(
        SUB_CLEAR, SUB_NAV_STARTED, SUB_ROAD_NAME, SUB_MANEUVER, SUB_TOTAL, SUB_MANEUVER_ALT, SUB_TOTAL_ALT,
    )

    /** Sabit uzunluklu alt komutların bildirilen veri uzunluğu. 48 (yol adı) değişkendir. */
    private val FIXED_LENGTH = mapOf(
        SUB_CLEAR to 1, SUB_NAV_STARTED to 1, SUB_MANEUVER to 5, SUB_TOTAL to 4, SUB_MANEUVER_ALT to 6,
        SUB_TOTAL_ALT to 5,
    )

    const val UNIT_METER = 0
    const val UNIT_KM = 1

    /** Beyaz liste dışı alt komut için. */
    class SubcommandNotAllowed(val sub: Int) : IllegalArgumentException("HUD alt komutu izinli değil: $sub")

    /** Alt komut izinli değilse [SubcommandNotAllowed] fırlatır. */
    fun requireAllowed(sub: Int) {
        if (sub !in ALLOWED_SUBCOMMANDS) throw SubcommandNotAllowed(sub)
    }

    /**
     * Gönderilecek dizinin son kontrolü: izinli alt komut, bildirilen uzunluk gerçek uzunlukla uyumlu,
     * sabit uzunluklu komutlarda beklenen uzunluk, her eleman 0..255. Geçerliyse aynı diziyi döndürür.
     */
    fun validate(values: IntArray): IntArray {
        require(values.size >= 2) { "HUD yükü en az [altKomut, uzunluk] içermeli" }
        val sub = values[0]
        requireAllowed(sub)
        val declared = values[1]
        require(declared == values.size - 2) { "HUD yük uzunluğu uyuşmuyor: bildirilen $declared, gerçek ${values.size - 2}" }
        val expected = FIXED_LENGTH[sub]
        if (expected != null) require(declared == expected) { "HUD alt komut $sub için uzunluk $expected olmalı, $declared geldi" }
        if (sub == SUB_ROAD_NAME) require(declared <= ROAD_NAME_MAX_BYTES) { "Yol adı en çok $ROAD_NAME_MAX_BYTES bayt" }
        require(values.all { it in 0..255 }) { "HUD yükünde 0..255 dışı bayt var" }
        return values
    }

    /** HUD ekranını temizle: `[3, 1, 1]`. */
    fun clear(): IntArray = validate(intArrayOf(SUB_CLEAR, 1, 1))

    /** Navigasyon başladı: `[52, 1, 0]`. */
    fun navStarted(): IntArray = validate(intArrayOf(SUB_NAV_STARTED, 1, 0))

    /** Sonraki yol adı: `[48, len, UTF-8...]`, en çok 47 bayt; karakter ortasından kesilmez. */
    fun roadName(name: String): IntArray {
        val bytes = truncateUtf8(name, ROAD_NAME_MAX_BYTES)
        val out = IntArray(2 + bytes.size)
        out[0] = SUB_ROAD_NAME
        out[1] = bytes.size
        for (i in bytes.indices) out[2 + i] = bytes[i].toInt() and 0xFF
        return validate(out)
    }

    /**
     * Standart manevra oku + kalan mesafe: `[49, 5, icLo, icHi, mLo, mMid, mHi]`. Mesafe metre, 3 bayta
     * (0..16 777 215) kıstırılır. İkon 0..255 dışındaysa reddedilir (kıstırmak başka bir oku göstermek olur).
     */
    fun maneuver(icon: Int, meters: Int): IntArray {
        requireIcon(icon)
        return validate(intArrayOf(SUB_MANEUVER, 5) + le(icon.toLong(), 2) + le(clampLong(meters.toLong(), MAX_3), 3))
    }

    /** Standart rota toplam kalan mesafe: `[51, 4, b0..b3]`, metre, 4 bayta kıstırılır. */
    fun totalDistance(meters: Long): IntArray =
        validate(intArrayOf(SUB_TOTAL, 4) + le(clampLong(meters, MAX_4), 4))

    /**
     * Alternatif aile (T18FL4/M1EFL1) manevra: `[50, 6, icLo, icHi, d0, d1, d2, birim]`; mesafe [scaleAlt] ile
     * (değer, birim) çiftine çevrilir, değer 3 bayta kıstırılır.
     */
    fun maneuverAlt(icon: Int, meters: Int): IntArray {
        requireIcon(icon)
        val (value, unit) = scaleAlt(meters.toLong())
        return validate(
            intArrayOf(SUB_MANEUVER_ALT, 6) + le(icon.toLong(), 2) + le(clampLong(value, MAX_3), 3) + intArrayOf(unit),
        )
    }

    /** Alternatif aile toplam kalan: `[53, 5, d0..d3, birim]`. */
    fun totalDistanceAlt(meters: Long): IntArray {
        val (value, unit) = scaleAlt(meters)
        return validate(intArrayOf(SUB_TOTAL_ALT, 5) + le(clampLong(value, MAX_4), 4) + intArrayOf(unit))
    }

    /**
     * NaviUtil.unitValueChange(0, metre): >= 10000 m -> round(m/1000)*10, km (0,1 km adım); 1000..9999 m ->
     * m*10/1000, km; < 1000 m -> m*10, metre (0,1 m adım). Negatif 0 sayılır.
     */
    fun scaleAlt(meters: Long): Pair<Long, Int> {
        val m = meters.coerceAtLeast(0)
        return when {
            m >= 10_000 -> Pair(Math.round(m / 1000.0) * 10L, UNIT_KM)
            m >= 1_000 -> Pair(m * 10 / 1000, UNIT_KM)
            else -> Pair(m * 10, UNIT_METER)
        }
    }

    /** Küçük-uç: en düşük bayt önce. [value] `n` baytın sığabileceği aralığa kıstırılmaz; üst baytlar atılır. */
    fun le(value: Long, n: Int): IntArray = IntArray(n) { i -> ((value shr (8 * i)) and 0xFF).toInt() }

    /** [s]'nin UTF-8 baytlarını en çok [maxBytes] baytla, kod noktası sınırında keserek döndürür. */
    fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chunk = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + chunk.size > maxBytes) break
            out.write(chunk, 0, chunk.size)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    private const val MAX_3 = 0xFFFFFFL
    private const val MAX_4 = 0xFFFFFFFFL

    private fun clampLong(v: Long, max: Long) = v.coerceIn(0L, max)

    private fun requireIcon(icon: Int) = require(icon in 0..255) { "İkon numarası 0..255 olmalı: $icon" }
}

/**
 * Park kapısı kararı (saf). Girdi: gösterge hızı (ID_SPEED_GAUGE_DISPLAY 119), eski hız (ID_CAR_SPEED 0),
 * vites (ID_GEARBOX_STATE 26) ve el freni (ID_HANDBRAKE_STATE 75).
 *
 * Karar YALNIZCA hıza dayanır: 119 numaralı sinyal okunmalı ve 0 olmalı; eski hız cevap veriyorsa o da 0 olmalı.
 * Vites ve el freni değer anlamı Tiggo 7'de ölçülmediği için (P'nin kodu bilinmiyor; 26 sinyali sürüşe
 * geçerken 6 -> 7 görüldü ama kayıt P'yi göstermiyor) kapıya KATILMAZ, yalnızca günlüğe yazılır.
 */
object HudParkGate {

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(gaugeSpeed: Int?, legacySpeed: Int?): Decision {
        if (gaugeSpeed == null) return Decision(false, "hız (119) okunamadı")
        if (gaugeSpeed !in 0..300) return Decision(false, "hız (119) geçersiz: $gaugeSpeed")
        if (gaugeSpeed != 0) return Decision(false, "araç hareketli: hız=$gaugeSpeed km/sa")
        if (legacySpeed != null && legacySpeed in 1..300) return Decision(false, "eski hız sinyali hareketli: $legacySpeed")
        return Decision(true, "park: hız=0")
    }
}
