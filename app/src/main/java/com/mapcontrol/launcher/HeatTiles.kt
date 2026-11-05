package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo

/** Isıtmalar: direksiyon ve ön cam. */
internal enum class HeatKey { STEERING, WINDSHIELD }

/** Isıtma durumları; okunamayan alan null. */
internal data class HeatReading(val steering: Boolean? = null, val windshield: Boolean? = null)

/**
 * Isıtmaların saf kuralları (JVM'de sınanır). Kaynak: Desay SVSystemUI `HvacModel` (katalog/oem-komut-kodlamalari.md):
 *  - Direksiyon: okuma CarSetting 160 `== 1` açık (`getCarSteerWheelHeat`). Yazma bir ANAHTARDIR: `onCarSteerWheelHeatClick` her
 *    tıkta önce HVAC 9 (ekran etkin) ← 1, sonra CarSetting 160 ← 1 gönderir, durumdan bağımsız; araç açıkken kapatır, kapalıyken açar.
 *  - Ön cam ısıtma: okuma HVAC 65 `== 1`; yazma `onFrontHeatingClick` 65 ← (açıksa 2, değilse 1).
 * Hepsi araçta DOĞRULANMADI.
 */
internal object HeatMath {

    const val STEER_TOGGLE_VALUE = 1
    const val ON = 1
    const val OFF = 2

    /** Okuma: 1 açık, başka her değer kapalı; okunamadıysa null. */
    fun decodeOn(raw: Int?): Boolean? = raw?.let { it == 1 }

    /**
     * Yazma komutları. Direksiyonda [on] yok sayılır (anahtar): çağıran yalnızca durum "değişmeli" ise çağırır.
     */
    fun commands(key: HeatKey, on: Boolean): List<ComfortCmd> = when (key) {
        HeatKey.STEERING -> listOf(
            ComfortCmd(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_DISPLAY_ACTIVE, STEER_TOGGLE_VALUE),
            ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_STEER_WHEEL_HEAT, STEER_TOGGLE_VALUE),
        )
        HeatKey.WINDSHIELD -> listOf(ComfortCmd(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_FRONT_GLASS_HEAT, if (on) ON else OFF))
    }

    fun current(r: HeatReading?, key: HeatKey): Boolean? = if (key == HeatKey.STEERING) r?.steering else r?.windshield
}
