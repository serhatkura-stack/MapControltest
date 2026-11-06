package com.mapcontrol.launcher

/**
 * Yolculuk kartının öncü satırı (saf karar, JVM'de sınanır). Araç sürüşteyken ([DriveMotion], düzen geçişiyle aynı
 * gecikmeler) öncü sayı canlı anlık tüketimdir ([ConsumptionSmoother], tüketim paneliyle aynı kurallar); dururken
 * ve parktayken bugünkü yolculuk içeriği ("Sıfırlamadan beri" km) değişmeden kalır.
 */
internal object TripHero {
    enum class Content { NO_DATA, TRIP, LIVE }

    /** Gösterilecek içerik: veri yoksa NO_DATA; sürüşte LIVE (yolculuk km'si okunamasa da); değilse km varsa TRIP. */
    fun content(driving: Boolean, connected: Boolean, tripKm: Double?): Content = when {
        !connected -> Content.NO_DATA
        driving -> Content.LIVE
        tripKm == null -> Content.NO_DATA
        else -> Content.TRIP
    }

    /** Canlı öncü satırın türü: tüketim kutucuğundaki durumların aynısı. */
    enum class Live { WAITING, IDLE, LOW_SPEED, VALUE, CAP }

    fun live(display: ConsumptionSmoother.Display): Live = when (display.mode) {
        ConsumptionSmoother.Mode.NONE -> Live.WAITING
        ConsumptionSmoother.Mode.IDLE -> Live.IDLE
        ConsumptionSmoother.Mode.TAKEOFF -> Live.LOW_SPEED
        ConsumptionSmoother.Mode.VALUE -> Live.VALUE
        ConsumptionSmoother.Mode.CAP -> Live.CAP
    }

    /** İki içerik arasında çapraz geçiş gerekir mi: yalnızca TRIP ile LIVE arasında (veri yokluğu anında değişir). */
    fun crossfade(from: Content?, to: Content): Boolean =
        from != null && from != to && from != Content.NO_DATA && to != Content.NO_DATA
}
