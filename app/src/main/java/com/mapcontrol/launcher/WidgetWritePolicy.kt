package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.comfort.ComfortPlan
import com.mapcontrol.comfort.ParkGate

/**
 * Launcher widget'larından araca yazma testinin SAF kuralları (JVM'de sınanır): beyaz liste, park kapısı kararı,
 * kapı birleşimi ve günlük satırı. Yalnızca test sürümü (`launchertest`, LauncherEdition) kullanır; gerçek `app` sürümünde
 * hiçbir çağıran yoktur. Liste, widget'ların GÖNDEREBİLECEĞİ (modül, kimlik, değer) çiftlerinden türetildi (bkz. denetim).
 */
internal object WidgetWritePolicy {

    const val LOG_TAG = "WidgetWrite"

    private const val CS = VDEventCarInfo.MODULE_CAR_SETTING
    private const val HV = VDEventCarInfo.MODULE_HVAC
    private const val VR = ComfortPlan.MODULE_VR
    private const val RO = VDEventCarInfo.MODULE_READONLY_INFO

    /** Beyaz liste girdisi: widget adı, modül, kimlik, izinli değerler ve yazmadan sonra 3 sn izlenecek sinyaller. */
    class Entry(
        val widget: String,
        val module: Int,
        val id: Int,
        val values: Set<Int>,
        val readBack: List<ComfortPlan.Signal>,
    ) {
        fun matches(m: Int, i: Int, v: Int) = module == m && id == i && v in values
    }

    private fun sig(name: String, module: Int, id: Int) = ComfortPlan.Signal(name, module, id)

    private val SHADE_READ = listOf(sig("cs10_sunshade", CS, CarSettingID.ID_CAR_SUNSHADE))
    private val TRUNK_READ = listOf(
        sig("ro98_plg_position", RO, ReadOnlyID.ID_PLG_POSITION),
        sig("ro19_trunk_state", RO, ReadOnlyID.ID_TRUNK_STATE),
        sig("cs92_rear_door_control", CS, CarSettingID.ID_CAR_REAR_DOOR_CONTROL),
    )

    /** Koltuk ısıtma/soğutma: ham seviye 0..3 ya da sarmalanmış 1..4 (kodlama doğrulanmadı, ikisi de izinli). */
    private val SEAT_VALUES = (0..4).toSet()

    val ENTRIES: List<Entry> = listOf(
        seat("SeatHeatDriver", CarSettingID.ID_CAR_DRIVER_SEAT_HEAT),
        seat("SeatHeatPassenger", CarSettingID.ID_CAR_COPILOT_SEAT_HEAT),
        seat("SeatVentDriver", CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION),
        seat("SeatVentPassenger", CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION),
        // Klima: sıcaklık ham = (°C - 15,5) * 2 → 16,0..32,0 °C = 1..33; fan 0..10.
        Entry("ClimateTempDriver", HV, HvacID.ID_AC_TEMPERATURE_DRIVER, (1..33).toSet(),
            listOf(sig("hvac10_temp_driver", HV, HvacID.ID_AC_TEMPERATURE_DRIVER))),
        Entry("ClimateTempPassenger", HV, HvacID.ID_AC_TEMPERATURE_COPILOT, (1..33).toSet(),
            listOf(sig("hvac13_temp_passenger", HV, HvacID.ID_AC_TEMPERATURE_COPILOT))),
        Entry("ClimateFan", HV, HvacID.ID_AC_BLOW_SPEED_LEVEL, (0..10).toSet(),
            listOf(sig("hvac12_blow_speed", HV, HvacID.ID_AC_BLOW_SPEED_LEVEL))),
        // Perde: yalnızca doğrulanmış VR 124 (1 aç, 2 kapat). CarSetting 10 yazmaları yok sayılıyor, listede yok.
        Entry("Sunshade", VR, ComfortPlan.ID_VR_SUN_SHADE, setOf(1, 2), SHADE_READ),
        // Buğu: 1 aç (doğrulandı); kapatma adayı 2 (0 kapatmıyor, listede yok).
        Entry("DefrostFront", HV, HvacID.ID_AC_MAX_FRONT_DEFOREST, setOf(1, 2),
            listOf(sig("hvac24_max_front_deforest", HV, HvacID.ID_AC_MAX_FRONT_DEFOREST))),
        Entry("DefrostRear", HV, HvacID.ID_AC_REAR_DEFROST, setOf(1, 2),
            listOf(sig("hvac80_rear_defrost", HV, HvacID.ID_AC_REAR_DEFROST))),
        // Bagaj: VR 121 (1 aç, 2 kapat) ya da CarSetting 92 (3 aç, 4 kapat); ikisi de doğrulanmadı.
        Entry("TrunkVr", VR, ComfortPlan.ID_VR_TRUNK, setOf(1, 2), TRUNK_READ),
        Entry("TrunkCs", CS, CarSettingID.ID_CAR_REAR_DOOR_CONTROL, setOf(3, 4), TRUNK_READ),
        // Merkezi kilit: VehicleQuickControls.centralLockValue → 2 kilitle, 1 aç.
        Entry("Lock", CS, CarSettingID.ID_CAR_IVI_LOCK, setOf(1, 2),
            listOf(sig("cs169_ivi_lock", CS, CarSettingID.ID_CAR_IVI_LOCK))),
    )

    private fun seat(widget: String, id: Int) =
        Entry(widget, CS, id, SEAT_VALUES, listOf(sig("cs${id}_${widget.lowercase()}", CS, id)))

    /** Girdi beyaz listede mi; değilse null. */
    fun find(module: Int, id: Int, value: Int): Entry? = ENTRIES.firstOrNull { it.matches(module, id, value) }

    sealed class Verdict {
        class Allowed(val entry: Entry) : Verdict()
        object NotWhitelisted : Verdict()
        class NotParked(val reason: String) : Verdict()
    }

    /**
     * Karar: önce beyaz liste, sonra park kapısı. [park] TEMBELDİR: yalnızca liste geçilirse (ve gönderimden hemen önce) çağrılır.
     */
    fun evaluate(module: Int, id: Int, value: Int, park: () -> ParkGate.Decision): Verdict {
        val entry = find(module, id, value) ?: return Verdict.NotWhitelisted
        val decision = park()
        return if (decision.allowed) Verdict.Allowed(entry) else Verdict.NotParked(decision.reason)
    }

    /** Özellik kapısı: doğrulanmış ya da test anahtarı açık. Gerçek sürümde test anahtarı hep false'tur. */
    fun gate(verified: Boolean, writeTest: Boolean): Boolean = verified || writeTest

    /** Günlük sonucu metni. */
    fun resultText(v: Verdict): String = when (v) {
        is Verdict.Allowed -> "sent"
        is Verdict.NotWhitelisted -> "refused-not-whitelisted"
        is Verdict.NotParked -> "refused-not-parked(${v.reason})"
    }

    /** Tek satır: `widget=… module=… id=… value=… result=…`. Beyaz liste dışıysa widget adı "?" olur. */
    fun logLine(widget: String?, module: Int, id: Int, value: Int, result: String): String =
        "widget=${widget ?: "?"} module=$module id=$id value=$value result=$result"
}
