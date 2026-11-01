package com.mapcontrol.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R

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

/** Isıtmaları okur (yalnızca okuma; arka plan thread'inde). */
internal fun ComfortReader.readHeat(): ComfortReading {
    if (LauncherDemo.enabled) return LauncherDemo.comfort()
    val proxy = CarInfoProxy.getInstance()
    if (!proxy.isServiceConnnected) return ComfortReading()
    return try {
        ComfortReading(
            connected = true,
            heat = HeatReading(
                steering = HeatMath.decodeOn(CarSignals.readInt(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_STEER_WHEEL_HEAT)),
                windshield = HeatMath.decodeOn(CarSignals.readInt(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_FRONT_GLASS_HEAT)),
            ),
        )
    } catch (e: RuntimeException) {
        ComfortReading()
    }
}

/** Isıtmalar: direksiyon ve ön cam ısıtma, iki açma/kapama düğmesi (buğu kutucuğuyla aynı düzen). */
internal class HeatTile(context: Context) : ComfortTile(context) {

    private val pending = HashMap<HeatKey, Boolean>()

    private val steering = ToggleChip(context, R.drawable.ic_mdi_steering, R.string.launcher_heat_steering) { toggle(HeatKey.STEERING) }
    private val windshield = ToggleChip(context, R.drawable.ic_mdi_defrost_front, R.string.launcher_heat_windshield) { toggle(HeatKey.WINDSHIELD) }

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, PADDING_DP).apply {
        gravity = Gravity.CENTER
        theme.bind { background = cardSurface(context) }
        addView(steering.view, weightLp(1f))
        addView(gap(context, GAP_DP))
        addView(windshield.view, weightLp(1f))
    }

    override val view: View get() = root

    override fun readNow(): ComfortReading = ComfortReader.readHeat()

    override fun applySpan(span: Span) = Unit

    override fun clearPending() = pending.clear()

    private fun shown(key: HeatKey): Boolean? =
        pending[key] ?: HeatMath.current(reading.heat, key).takeIf { reading.connected }

    override fun render() {
        steering.bind(shown(HeatKey.STEERING))
        windshield.bind(shown(HeatKey.WINDSHIELD))
    }

    private fun toggle(key: HeatKey) {
        val current = shown(key)
        // Okunamıyorsa (test sürümü dışında) tahmin etme.
        if (current == null && !LauncherEdition.READ_ONLY) return
        val target = !(current ?: false)
        pending[key] = target
        val enabled = ComfortWriter.heatEnabled
        if (enabled) io.runSafely { ComfortWriter.setHeat(key, target) }
        afterCommit(enabled)
    }

    private companion object {
        const val PADDING_DP = 14
        const val GAP_DP = 12
    }
}
