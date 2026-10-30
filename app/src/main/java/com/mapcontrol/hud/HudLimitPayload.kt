package com.mapcontrol.hud

/**
 * Hız limiti tabelası (VDBus modülü 327682 = VDEventCarInfo.MODULE_NEW_ENERGY, komut 123 ve 125) yük kodlayıcıları
 * ve beyaz liste. SAF kod: VDBus'a, Android'e dokunmaz, JVM'de sınanır. Kaynak: Desay uygulamasının ISAUtil sınıfı
 * (tersine mühendislik). Araca yazan tek sınıf launchertest'teki HudLimitSender'dır; her yazmadan önce [validate].
 *
 *  - komut 123, int[8]: [durum, limitKmh, birim(0), yolTipi(10), ülke(34 = TUR), yağmur(0), kar(0), sis(0)]
 *  - komut 125, int[4]: [0, 0, 0, tabelaİndeksi] (indeks: [signIndex] tablosu, bilinmeyen limit -> 0)
 */
object HudLimitPayload {

    /** VDEventCarInfo.MODULE_NEW_ENERGY. */
    const val MODULE = 327682

    const val CMD_LIMIT = 123
    const val CMD_SIGN = 125
    /** Özgün uygulamanın her 1 sn yinelediği durum komutu; [4] = NavSpeedLimitIsUnknown. */
    const val CMD_STATE = 112

    const val LEN_LIMIT = 8
    const val LEN_SIGN = 4
    const val LEN_STATE = 8

    const val MAX_KMH = 250
    const val MAX_SIGN_INDEX = 43

    const val DEFAULT_STATUS = 1
    const val DEFAULT_ROAD_TYPE = 10
    const val COUNTRY_TUR = 34

    /** Limit (km/sa) -> tabela resim indeksi. Tabloda olmayan limit 0 (tabela yok). */
    val SIGN_INDEX: Map<Int, Int> = mapOf(
        5 to 1, 6 to 35, 7 to 36, 8 to 2, 10 to 3, 12 to 37, 15 to 4, 16 to 5, 20 to 6, 24 to 7, 25 to 8,
        30 to 9, 32 to 10, 35 to 11, 40 to 12, 45 to 13, 48 to 14, 50 to 15, 55 to 16, 56 to 17, 60 to 18,
        64 to 19, 65 to 20, 70 to 21, 72 to 22, 75 to 38, 80 to 23, 82 to 39, 85 to 40, 88 to 41, 90 to 24,
        95 to 42, 97 to 25, 100 to 26, 110 to 27, 113 to 28, 120 to 29, 130 to 30, 140 to 43, 145 to 31,
        150 to 32, 160 to 33, 180 to 34,
    )

    /** Beyaz liste: (modül, komut) -> zorunlu dizi uzunluğu. Başka hiçbir şey gitmez. */
    val ALLOWED: Map<Pair<Int, Int>, Int> = mapOf(
        Pair(MODULE, CMD_LIMIT) to LEN_LIMIT,
        Pair(MODULE, CMD_SIGN) to LEN_SIGN,
        Pair(MODULE, CMD_STATE) to LEN_STATE,
    )

    fun signIndex(kmh: Int): Int = SIGN_INDEX[kmh] ?: 0

    /** Gönderilecek yük: hedef modül, komut ve int[]; [validate]'ten geçmiş olmalı. */
    class Payload(val module: Int, val cmd: Int, val values: IntArray)

    /** Modül/komut beyaz listede mi, uzunluk tam mı, limit 0..250 mi, indeks 0..43 mü, her eleman 0..255 mi. */
    fun validate(module: Int, cmd: Int, values: IntArray): Payload {
        val expected = ALLOWED[Pair(module, cmd)]
            ?: throw IllegalArgumentException("Hız limiti komutu izinli değil: modül $module komut $cmd")
        require(values.size == expected) { "Komut $cmd için uzunluk $expected olmalı, ${values.size} geldi" }
        require(values.all { it in 0..255 }) { "Yükte 0..255 dışı eleman var" }
        if (cmd == CMD_LIMIT) require(values[1] in 0..MAX_KMH) { "Limit 0..$MAX_KMH km/sa olmalı: ${values[1]}" }
        if (cmd == CMD_SIGN) {
            require(values[0] == 0 && values[1] == 0 && values[2] == 0) { "Komut 125 ilk üç eleman 0 olmalı" }
            require(values[3] in 0..MAX_SIGN_INDEX) { "Tabela indeksi 0..$MAX_SIGN_INDEX olmalı: ${values[3]}" }
        }
        return Payload(module, cmd, values)
    }

    /** Komut 123: `[durum, limit, 0, yolTipi, ülke, 0, 0, 0]`. */
    fun limit(kmh: Int, status: Int = DEFAULT_STATUS): Payload {
        require(kmh in 0..MAX_KMH) { "Limit 0..$MAX_KMH km/sa olmalı: $kmh" }
        return validate(
            MODULE, CMD_LIMIT, intArrayOf(status, kmh, 0, DEFAULT_ROAD_TYPE, COUNTRY_TUR, 0, 0, 0),
        )
    }

    /** Komut 125: `[0, 0, 0, tabelaİndeksi]`. */
    fun sign(kmh: Int): Payload {
        require(kmh in 0..MAX_KMH) { "Limit 0..$MAX_KMH km/sa olmalı: $kmh" }
        return validate(MODULE, CMD_SIGN, intArrayOf(0, 0, 0, signIndex(kmh)))
    }

    /** Temizleme: durum 0, limit 0 (komut 123) ve tabela 0 (komut 125). */
    fun clearLimit(): Payload = limit(0, 0)
    fun clearSign(): Payload = sign(0)

    /** Komut 112: [döner kavşak mes. hi, lo, zamanlı limit, kavşakta, LİMİT BİLİNMİYOR, değişken limit, VSL mes., 0]. */
    fun unknownLimit(): Payload = validate(MODULE, CMD_STATE, intArrayOf(0, 0, 0, 0, 1, 0, 0, 0))
}
