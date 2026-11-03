package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.desaysv.ivi.vdb.event.id.dsp.VDEventDsp
import com.mapcontrol.comfort.ComfortPlan

/**
 * Launcher widget'larından araca yazma testinin SAF kuralları (JVM'de sınanır): beyaz liste, kapı birleşimi ve günlük
 * satırı. Sürüş kilidi yoktur: her widget sürerken de park halinde de kullanılır; aracın kendisi bir komutu reddederse geri
 * okuma bunu gösterir (widget eski duruma döner). Yalnızca test sürümü (`launchertest`, LauncherEdition) kullanır; gerçek `app` sürümünde
 * hiçbir çağıran yoktur. Liste, widget'ların GÖNDEREBİLECEĞİ (modül, kimlik, değer) çiftlerinden türetildi (bkz. denetim).
 */
internal object WidgetWritePolicy {

    const val LOG_TAG = "WidgetWrite"

    private const val CS = VDEventCarInfo.MODULE_CAR_SETTING
    private const val HV = VDEventCarInfo.MODULE_HVAC
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

    private val TRUNK_READ = listOf(
        sig("ro98_plg_position", RO, ReadOnlyID.ID_PLG_POSITION),
        sig("ro19_trunk_state", RO, ReadOnlyID.ID_TRUNK_STATE),
        sig("cs92_rear_door_control", CS, CarSettingID.ID_CAR_REAR_DOOR_CONTROL),
    )

    /** Koltuk ısıtma/soğutma: ham seviye 0..3 ya da sarmalanmış 1..4 (kodlama doğrulanmadı, ikisi de izinli). */
    private val SEAT_VALUES = (0..4).toSet()

    private val TEMP_VALUES = (ClimateMath.RAW_LO..ClimateMath.RAW_HI).toSet()

    val ENTRIES: List<Entry> = listOf(
        seat("SeatHeatDriver", CarSettingID.ID_CAR_DRIVER_SEAT_HEAT),
        seat("SeatHeatPassenger", CarSettingID.ID_CAR_COPILOT_SEAT_HEAT),
        seat("SeatVentDriver", CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION),
        seat("SeatVentPassenger", CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION),
        // Klima: sıcaklık ham = (°C - 15,5) * 2 → LO = 0, 16,0..30,0 °C = 1..29, HI = 30 (OEM aralığı; 31..33 yok); fan 0..10.
        Entry("ClimateTempDriver", HV, HvacID.ID_AC_TEMPERATURE_DRIVER, TEMP_VALUES,
            listOf(sig("hvac10_temp_driver", HV, HvacID.ID_AC_TEMPERATURE_DRIVER))),
        Entry("ClimateTempPassenger", HV, HvacID.ID_AC_TEMPERATURE_COPILOT, TEMP_VALUES,
            listOf(sig("hvac13_temp_passenger", HV, HvacID.ID_AC_TEMPERATURE_COPILOT))),
        // Klima gücü (HVAC 18): 1 aç / 2 kapat. Sıcaklık/fan yazmadan önce otomatik 1 de buradan geçer.
        Entry("ClimatePower", HV, HvacID.ID_AC_WORKING, setOf(ClimateMath.POWER_ON, ClimateMath.POWER_OFF),
            listOf(sig("hvac18_power", HV, HvacID.ID_AC_WORKING))),
        Entry("ClimateFan", HV, HvacID.ID_AC_BLOW_SPEED_LEVEL, (1..10).toSet(),
            listOf(sig("hvac12_blow_speed", HV, HvacID.ID_AC_BLOW_SPEED_LEVEL))),
        // Bagaj: yalnızca CarSetting 92, 1 aç / 2 kapat (OEM SVSetting i0/m.D, SVSystemUI QuickSettingPresenter.triggerTrunkState); doğrulanmadı.
        Entry("Trunk", CS, CarSettingID.ID_CAR_REAR_DOOR_CONTROL, setOf(1, 2), TRUNK_READ),
        // Klima tuşları (HVAC 1 A/C, 4 Oto, 8 devridaim): 1 aç / 2 kapat; devridaimde 1 iç hava / 2 dış hava (OEM HvacModel, SVVrControl a/b.java).
        Entry("ClimateAc", HV, HvacID.ID_AC_STATE, setOf(1, 2), listOf(sig("hvac1_ac", HV, HvacID.ID_AC_STATE))),
        Entry("ClimateAuto", HV, HvacID.ID_AC_AUTO_STATE, setOf(1, 2), listOf(sig("hvac4_auto", HV, HvacID.ID_AC_AUTO_STATE))),
        Entry("ClimateRecirc", HV, HvacID.ID_AC_CIRCULATION_MODE, setOf(1, 2), listOf(sig("hvac8_circulation", HV, HvacID.ID_AC_CIRCULATION_MODE))),
        // Isıtmalar: direksiyon anahtarı (HVAC 9 ← 1, ardından CarSetting 160 ← 1) ve ön cam ısıtma (HVAC 65: 1 aç / 2 kapat).
        Entry("SteerHeatDisplay", HV, HvacID.ID_AC_DISPLAY_ACTIVE, setOf(1), listOf(sig("hvac9_display_active", HV, HvacID.ID_AC_DISPLAY_ACTIVE))),
        Entry("SteerHeat", CS, CarSettingID.ID_CAR_STEER_WHEEL_HEAT, setOf(1), listOf(sig("cs160_steer_wheel_heat", CS, CarSettingID.ID_CAR_STEER_WHEEL_HEAT))),
        Entry("WindshieldHeat", HV, HvacID.ID_AC_FRONT_GLASS_HEAT, setOf(1, 2), listOf(sig("hvac65_front_glass_heat", HV, HvacID.ID_AC_FRONT_GLASS_HEAT))),
        // Arka hoparlör: DSP SOUND_FIELD (modül = olay kimliği, kimlik sabit; değer = fade 0..20, balance gönderirken korunur). Uç değeri doğrulanmadı.
        Entry("RearSpeaker", VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID, (0..SoundFieldMath.MAX).toSet(),
            listOf(sig("dsp_sound_field", VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID))),
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
    }

    /** Karar yalnızca beyaz listedir; hız ya da vites sorulmaz (sürüşte de park halinde de aynı). */
    fun evaluate(module: Int, id: Int, value: Int): Verdict =
        find(module, id, value)?.let { Verdict.Allowed(it) } ?: Verdict.NotWhitelisted

    /** Özellik kapısı: doğrulanmış ya da test anahtarı açık. Gerçek sürümde test anahtarı hep false'tur. */
    fun gate(verified: Boolean, writeTest: Boolean): Boolean = verified || writeTest

    /** Günlük sonucu metni. */
    fun resultText(v: Verdict): String = when (v) {
        is Verdict.Allowed -> "sent"
        is Verdict.NotWhitelisted -> "refused-not-whitelisted"
    }

    /** Tek satır: `widget=… module=… id=… value=… result=…`. Beyaz liste dışıysa widget adı "?" olur. */
    fun logLine(widget: String?, module: Int, id: Int, value: Int, result: String): String =
        "widget=${widget ?: "?"} module=$module id=$id value=$value result=$result"
}
