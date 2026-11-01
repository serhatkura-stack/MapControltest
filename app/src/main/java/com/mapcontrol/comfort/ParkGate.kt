package com.mapcontrol.comfort

/**
 * Park kapısı kararı (saf). Girdi: gösterge hızı (ID_SPEED_GAUGE_DISPLAY 119) ve eski hız (ID_CAR_SPEED 0).
 * Tavan perdesi ve bagaj yazmaları bu kapıdan geçmeden araca gitmez.
 *
 * Karar YALNIZCA hıza dayanır: 119 numaralı sinyal okunmalı ve 0 olmalı; eski hız cevap veriyorsa o da 0 olmalı.
 * Vites ve el freni değer anlamı Tiggo 7'de ölçülmediği için kapıya KATILMAZ, yalnızca günlüğe yazılır.
 */
object ParkGate {

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(gaugeSpeed: Int?, legacySpeed: Int?): Decision {
        if (gaugeSpeed == null) return Decision(false, "hız (119) okunamadı")
        if (gaugeSpeed !in 0..300) return Decision(false, "hız (119) geçersiz: $gaugeSpeed")
        if (gaugeSpeed != 0) return Decision(false, "araç hareketli: hız=$gaugeSpeed km/sa")
        if (legacySpeed != null && legacySpeed in 1..300) return Decision(false, "eski hız sinyali hareketli: $legacySpeed")
        return Decision(true, "park: hız=0")
    }
}
