package com.mapcontrol.launcher

/**
 * Yolculuk kutucuğunun "Toplam" bölümü (saf mantık, JVM'de sınanır): hangi satırların gösterileceği ve sürüş süresi seçimi.
 * Eksik sinyaller (eski yazılımda 150-153 olmayabilir) satırdan düşer; hiçbiri yoksa bölüm boştur.
 */
internal object TripTotals {
    enum class Kind { ODOMETER, FUEL, TIME }

    /** Sürüş süresi: bu sürüş (151); yoksa sıfırlamadan beri (152). */
    fun drivingMinutes(run: Int?, reset: Int?): Int? = run ?: reset

    /** Gösterilecek satırlar sırasıyla: toplam km ([Int]), toplam yakıt L ([Double]), sürüş dakikası ([Int]). */
    fun rows(totalKm: Int?, fuelL: Double?, minutes: Int?): List<Pair<Kind, Any>> = listOfNotNull(
        totalKm?.let { Kind.ODOMETER to it },
        fuelL?.let { Kind.FUEL to it },
        minutes?.let { Kind.TIME to it },
    )
}
