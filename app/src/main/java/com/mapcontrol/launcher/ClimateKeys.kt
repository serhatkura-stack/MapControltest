package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo

/** Klima tuşları: A/C, Oto, iç/dış hava, klima gücü. */
internal enum class ClimateKey { AC, AUTO, RECIRC, POWER }

/** Klima tuşlarının okunan durumu; okunamayan alan null. */
internal data class ClimateKeysReading(
    val ac: Boolean? = null,
    val auto: Boolean? = null,
    /** true iç hava (devridaim), false dış hava. */
    val recirc: Boolean? = null,
    val power: Boolean? = null,
)

/**
 * Klima tuşlarının saf kuralları (JVM'de sınanır). Kaynak: Desay SVSystemUI `HvacModel` (T1E24 dalı) ve SVVrControl `a/b.java`,
 * bkz. katalog/oem-komut-kodlamalari.md. Okuma: HVAC 1 (A/C), 4 (Oto), 18 (güç) `== 1` açık; HVAC 8 `== 0` iç hava
 * (OEM `getCirculationState`). Yazma: 1 = aç / 2 = kapat; devridaimde 1 = iç hava / 2 = dış hava. Hepsi araçta DOĞRULANMADI.
 */
internal object ClimateKeysMath {

    /** HvacID.ID_CIRCULATION_MODE: yeni yazılımda devridaim durumu (bu SDK jar'ında yok); yalnızca veri denetimi tablosunda okunur. */
    const val ID_CIRCULATION_MODE_NEW = 111

    const val ON = 1
    const val OFF = 2
    const val RECIRC = 1
    const val FRESH = 2

    /** HVAC 1 / 4 / 18 okuması: 1 açık, başka her değer kapalı; okunamadıysa null. */
    fun decodeOn(raw: Int?): Boolean? = raw?.let { it == ON }

    /** HVAC 8 okuması: 0 iç hava (OEM `getCirculationState() == (HVAC 8 == 0)`), diğerleri dış hava; okunamadıysa null. */
    fun decodeRecirc(raw: Int?): Boolean? = raw?.let { it == 0 }

    fun id(key: ClimateKey): Int = when (key) {
        ClimateKey.AC -> HvacID.ID_AC_STATE
        ClimateKey.AUTO -> HvacID.ID_AC_AUTO_STATE
        ClimateKey.RECIRC -> HvacID.ID_AC_CIRCULATION_MODE
        ClimateKey.POWER -> HvacID.ID_AC_WORKING
    }

    /** [target] true: açık (iç hava); false: kapalı (dış hava). */
    fun commands(key: ClimateKey, target: Boolean): List<ComfortCmd> {
        val value = when (key) {
            ClimateKey.RECIRC -> if (target) RECIRC else FRESH
            else -> if (target) ON else OFF
        }
        return listOf(ComfortCmd(VDEventCarInfo.MODULE_HVAC, id(key), value))
    }

    fun current(r: ClimateKeysReading?, key: ClimateKey): Boolean? = when (key) {
        ClimateKey.AC -> r?.ac
        ClimateKey.AUTO -> r?.auto
        ClimateKey.RECIRC -> r?.recirc
        ClimateKey.POWER -> r?.power
    }
}
