package com.mapcontrol.launcher

import kotlin.math.roundToLong

/** "Bizimki" çubuğunun saf hesapları (JVM'de sınanır): sıcaklık → renk alanı, fan kademesi → dönüş hızı. */
internal object BarMath {

    /** Renk alanının uçları: bu ve altı tam soğuk, bu ve üstü tam sıcak, ortası nötr. */
    const val COLD_C = 18.0
    const val NEUTRAL_C = 22.0
    const val HOT_C = 27.0

    /** Sıcaklık → -1 (tam soğuk) .. 0 (nötr) .. +1 (tam sıcak); parçalı doğrusal. LO/HI uçları da sınırdadır. */
    fun tempFraction(celsius: Double): Float = when {
        celsius <= COLD_C -> -1f
        celsius >= HOT_C -> 1f
        celsius < NEUTRAL_C -> ((celsius - NEUTRAL_C) / (NEUTRAL_C - COLD_C)).toFloat()
        else -> ((celsius - NEUTRAL_C) / (HOT_C - NEUTRAL_C)).toFloat()
    }

    /** Kademe 1 en yavaş (~2,4 sn/tur), 10 en hızlı (~0,36 sn/tur); 0 ya da okunamayan = durur (null). */
    fun fanPeriodMs(level: Int?): Long? {
        val l = level ?: return null
        if (l <= 0) return null
        val n = l.coerceAtMost(ClimateMath.FAN_MAX)
        val revPerSec = SLOW_REV_S + (n - 1) * (FAST_REV_S - SLOW_REV_S) / (ClimateMath.FAN_MAX - 1)
        return (1000.0 / revPerSec).roundToLong()
    }

    fun lerpChannel(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f) + 0.5f).toInt()

    /** İki ARGB rengi kanal kanal karıştırır. */
    fun lerpColor(a: Int, b: Int, t: Float): Int = android.graphics.Color.argb(
        lerpChannel(a ushr 24, b ushr 24, t),
        lerpChannel((a shr 16) and 0xFF, (b shr 16) and 0xFF, t),
        lerpChannel((a shr 8) and 0xFF, (b shr 8) and 0xFF, t),
        lerpChannel(a and 0xFF, b and 0xFF, t),
    )

    private const val SLOW_REV_S = 0.42
    private const val FAST_REV_S = 2.78
}
