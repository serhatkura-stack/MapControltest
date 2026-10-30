package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import kotlin.math.roundToInt

/** Klima tarafı: sürücü ya da yolcu. */
internal enum class ClimateSide { DRIVER, PASSENGER }

/**
 * Klima sürüklemesinin saf hesapları (arayüzden bağımsız, birim testli): sürükleme mesafesi → adım,
 * sınırlama, ham kodlama.
 */
internal object ClimateMath {
    const val MIN_TEMP = 16.0
    const val MAX_TEMP = 32.0
    const val TEMP_STEP = 0.5

    const val FAN_MIN = 1
    // Araçta ölçüldü: A/C açıkken 4, maks. ön buğu çözmede 10, buğu kapanınca 1 okundu; en az 10'a kadar çıkıyor.
    // Gösterim için 10; yazma kapılı (ölçek doğrulanana kadar araca gitmez).
    const val FAN_MAX = 10

    /** Sıcaklık: dikey her 40dp yol = 1 adım (0,5 °C). */
    const val TEMP_DRAG_DP_PER_STEP = 40f

    /** Fan: yatay her 12dp yol = 1 kademe (hücre 120dp; tüm 1..10 aralığı tek harekette). */
    const val FAN_DRAG_DP_PER_STEP = 12f

    /**
     * Sürükleme mesafesinden (px) tam adım sayısı; sıfıra doğru yuvarlanır, yani yarım adım sayılmaz.
     * [delta] işaretlidir: sıcaklıkta yukarı = pozitif (çağıran `downY - y` verir), fanda sağ = pozitif.
     */
    fun stepsForDrag(delta: Float, density: Float, dpPerStep: Float): Int =
        (delta / (dpPerStep * density)).toInt()

    /** 0,5'e yuvarlar ve [MIN_TEMP]..[MAX_TEMP] aralığına sıkıştırır. */
    fun clampTemp(c: Double): Double =
        ((c / TEMP_STEP).roundToInt() * TEMP_STEP).coerceIn(MIN_TEMP, MAX_TEMP)

    /** [start] sıcaklığından [steps] adım sonra hedef (sınırlı). */
    fun targetTemp(start: Double, steps: Int): Double = clampTemp(start + steps * TEMP_STEP)

    /**
     * Fan hedefi. 0 (kapalı) yalnızca dokunarak ya da sola kaydırmadan gelir: sürüklemede 1..10'a sıkışır,
     * kapalıyken sola sürükleme kapalı kalır.
     */
    fun targetFan(start: Int, steps: Int): Int = when {
        steps == 0 -> start
        start == 0 && steps < 0 -> 0
        else -> (start + steps).coerceIn(FAN_MIN, FAN_MAX)
    }

    /** Ham değer: °C = 15,5 + ham/2 ([OurBottomBar.decodeTemp]'in tersi). */
    fun encodeTemp(c: Double): Int = ((clampTemp(c) - 15.5) * 2).roundToInt()

    /** Fan ham değeri doğrudan kademedir (okuma da öyle); 0..10. */
    fun encodeFan(level: Int): Int = level.coerceIn(0, FAN_MAX)
}

/**
 * Klima yazma noktası. Kodlama araçta DOĞRULANMADI; [CLIMATE_WRITE_VERIFIED] true olana kadar (ve test
 * sürümünde hiçbir zaman) araca HİÇBİR ŞEY gitmez. Yazma gövdesi bayrağın arkasında; araca ulaşan tek yol
 * `LauncherEdition.sendItemValue` (test sürümünde boş).
 */
internal object ClimateWriter {

    /** Araç testinde doğrulanınca true yapılır (koltuk soğutmadaki SEAT_VENT_WRITE_VERIFIED gibi). */
    const val CLIMATE_WRITE_VERIFIED = false

    /** Saf kapı: yalnızca doğrulanmışsa ve salt-okur sürüm değilse yazılır. */
    fun canWrite(verified: Boolean, readOnly: Boolean): Boolean = verified && !readOnly

    /** Arayüzün "yazılacak mı / bilgi mesajı mı" kararı için. */
    val enabled: Boolean get() = canWrite(CLIMATE_WRITE_VERIFIED, LauncherEdition.READ_ONLY)

    /** Sıcaklığı yazar. Kapalıyken false döner ve araca dokunmaz. */
    fun setTemperature(side: ClimateSide, celsius: Double): Boolean {
        if (!enabled) return false
        val id = if (side == ClimateSide.DRIVER) HvacID.ID_AC_TEMPERATURE_DRIVER else HvacID.ID_AC_TEMPERATURE_COPILOT
        return send(id, ClimateMath.encodeTemp(celsius))
    }

    /** Fan kademesini (0 = kapalı, 1..10) yazar. Kapalıyken false döner ve araca dokunmaz. */
    fun setFan(level: Int): Boolean {
        if (!enabled) return false
        return send(HvacID.ID_AC_BLOW_SPEED_LEVEL, ClimateMath.encodeFan(level))
    }

    private fun send(id: Int, raw: Int): Boolean {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return false
        LauncherEdition.sendItemValue(proxy, VDEventCarInfo.MODULE_HVAC, id, raw)
        return true
    }
}
