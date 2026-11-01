package com.mapcontrol.launcher

/**
 * Lastik basıncı okuması (bar). [values]: dört tekerlek sırayla ön sol, ön sağ, arka sol, arka sağ (sıra varsayım) ya da tek
 * değer; okunamayan tekerlek null. [warning]: ReadOnly 95 `ID_TIRE_PRESSURE_WARNING` sıfırdan farklı; okunamadıysa null.
 */
internal data class TireReading(val values: List<Double?>, val warning: Boolean? = null) {
    val perWheel: Boolean get() = values.size == WHEELS
    val anyValue: Boolean get() = values.any { it != null }

    companion object {
        const val WHEELS = 4
    }
}

/** Düşük basınç: hangi tekerlek ([wheel], 0..3; tekerlek ayrımı yoksa null) ve değeri (bar). */
internal data class TireLow(val wheel: Int?, val bar: Double?)

/**
 * Lastik basıncı sinyalinin (ReadOnly 140 `ID_TIRE_PRESSURE`) saf çözümü (JVM'de sınanır). ID yalnızca yeni Desay yazılımının
 * SDK'sında ve root dökümünde var; çözülmüş OEM uygulamalarının hiçbiri onu OKUMUYOR (araç içi lastik ekranı göstergede), bu yüzden
 * düzen ve birim KANITSIZ: yük 4 elemanlıysa tekerlek başına, 1 elemanlıysa tek değer sayılır; ham değer bar'a iki makul ölçekten
 * biriyle çevrilir ([bar]). Araçta ilk okuma çıktığında düzeltilecek; yanlış/boş her şey gösterilmez, uydurma sayı yazılmaz.
 * Türkiye'de sürücüler psi kullandığı için araç kartında değerler [psi] ile gösterilir.
 */
internal object TireDecode {

    /** Makul lastik basıncı aralığı (bar). Dışı geçersiz. */
    const val MIN_BAR = 0.5
    const val MAX_BAR = 7.0

    /** 1 bar = 14,5038 psi. */
    const val BAR_TO_PSI = 14.5038

    /** Düşük basınç eşiği, psi cinsinden (1,8 bar ≈ 26 psi). Bar eşiği bundan türetilir. */
    const val LOW_PSI = 26.0
    const val LOW_BAR = LOW_PSI / BAR_TO_PSI

    /**
     * Ham → bar. 50..700 kPa (ham/100); 5..49 ise 0,1 bar adımı (ham/10). 0, 0xFF/0xFFFF ve aralık dışı geçersiz (sensör yok).
     * İki ölçek örtüşmez (kPa tarafı >= 50).
     */
    fun bar(raw: Int): Double? {
        val v = when (raw) {
            in 50..700 -> raw / 100.0
            in 5..49 -> raw / 10.0
            else -> return null
        }
        return v.takeIf { it in MIN_BAR..MAX_BAR }
    }

    /** Okuma: ≥ 4 eleman → ilk dördü tekerlekler; 1 eleman → tek değer; başkası ya da hepsi geçersiz → null. */
    fun parse(raw: IntArray?, warningRaw: Int? = null): TireReading? {
        if (raw == null || raw.isEmpty()) return null
        val values = when {
            raw.size >= TireReading.WHEELS -> raw.take(TireReading.WHEELS).map(::bar)
            raw.size == 1 -> listOf(bar(raw[0]))
            else -> return null
        }
        if (values.all { it == null }) return null
        return TireReading(values, warningRaw?.let { it != 0 })
    }

    fun isLow(bar: Double?): Boolean = bar != null && bar < LOW_BAR

    /** Bar → psi, tam sayıya yuvarlanmış (gösterim için). */
    fun psi(bar: Double): Int = Math.round(bar * BAR_TO_PSI).toInt()

    /** En düşük (eşiğin altındaki ilk) tekerlek; yoksa null. Tekerlek ayrımı yoksa tek değer kontrol edilir. */
    fun low(t: TireReading?): TireLow? {
        if (t == null) return null
        if (t.perWheel) {
            val i = t.values.indexOfFirst { isLow(it) }
            return if (i >= 0) TireLow(i, t.values[i]) else null
        }
        val v = t.values.firstOrNull()
        return if (isLow(v)) TireLow(null, v) else null
    }

    /** Araç kartında lastik uyarısı yalnızca uyarı sinyali açıkken ya da bir değer eşiğin altındayken görünür. */
    fun alertShown(warning: Boolean?, low: TireLow?): Boolean = warning == true || low != null
}
