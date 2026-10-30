package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.comfort.ParkGate
import com.mapcontrol.vehicle.VehicleQuickControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetWritePolicyTest {
    private val cs = VDEventCarInfo.MODULE_CAR_SETTING
    private val hv = VDEventCarInfo.MODULE_HVAC
    private val vr = 327696
    private val parked = { ParkGate.Decision(true, "park") }
    private val moving = { ParkGate.Decision(false, "araç hareketli") }

    private fun allowed(m: Int, i: Int, v: Int) = WidgetWritePolicy.evaluate(m, i, v, parked) is WidgetWritePolicy.Verdict.Allowed

    @Test fun everyValueTheWidgetsCanSendIsWhitelisted() {
        // Koltuk: SeatHeatModel.encode(0..3) (ısıtma ve soğutma aynı kodlama).
        for (level in 0..3) {
            val v = SeatHeatModel.encode(level)
            assertTrue(allowed(cs, CarSettingID.ID_CAR_DRIVER_SEAT_HEAT, v))
            assertTrue(allowed(cs, CarSettingID.ID_CAR_COPILOT_SEAT_HEAT, v))
            assertTrue(allowed(cs, CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION, v))
            assertTrue(allowed(cs, CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION, v))
        }
        // Klima: 16,0..32,0 °C 0,5 adım ve fan 0..10.
        var c = ClimateMath.MIN_TEMP
        while (c <= ClimateMath.MAX_TEMP) {
            assertTrue("temp $c", allowed(hv, HvacID.ID_AC_TEMPERATURE_DRIVER, ClimateMath.encodeTemp(c)))
            assertTrue("temp $c", allowed(hv, HvacID.ID_AC_TEMPERATURE_COPILOT, ClimateMath.encodeTemp(c)))
            c += 0.5
        }
        for (f in 0..ClimateMath.FAN_MAX) assertTrue(allowed(hv, HvacID.ID_AC_BLOW_SPEED_LEVEL, ClimateMath.encodeFan(f)))
        // Perde, buğu, bagaj, kilit.
        for (v in listOf(ComfortWriter.SUNSHADE_OPEN_VALUE, ComfortWriter.SUNSHADE_CLOSE_VALUE)) {
            val cmd = ComfortMath.sunshadeCommand(v)
            assertTrue(allowed(cmd.module, cmd.id, cmd.value))
        }
        for (front in listOf(true, false)) for (on in listOf(true, false)) {
            ComfortMath.defrostCommands(front, on).forEach { assertTrue(allowed(it.module, it.id, it.value)) }
        }
        for (open in listOf(true, false)) for (viaVr in listOf(true, false)) {
            val cmd = TrunkMath.command(open, viaVr)
            assertTrue(allowed(cmd.module, cmd.id, cmd.value))
        }
        for (locked in listOf(true, false)) {
            assertTrue(allowed(cs, CarSettingID.ID_CAR_IVI_LOCK, VehicleQuickControls.centralLockValue(locked)))
        }
    }

    @Test fun lockEncodingIsLockTwoUnlockOne() {
        assertEquals(2, VehicleQuickControls.centralLockValue(true))
        assertEquals(1, VehicleQuickControls.centralLockValue(false))
    }

    @Test fun everythingElseIsRefused() {
        assertNull(WidgetWritePolicy.find(cs, 10, 1))                         // CarSetting 10 (perde) yok sayılıyor, listede yok
        assertNull(WidgetWritePolicy.find(vr, 124, 3))                        // perde durdurma adayı yok
        assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_MAX_FRONT_DEFOREST, 0))  // 0 buğuyu kapatmıyor
        assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_REAR_DEFROST, 3))
        assertNull(WidgetWritePolicy.find(cs, CarSettingID.ID_CAR_IVI_LOCK, 0))
        assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_TEMPERATURE_DRIVER, 0))
        assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_TEMPERATURE_DRIVER, 34))
        assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_BLOW_SPEED_LEVEL, 11))
        assertNull(WidgetWritePolicy.find(cs, CarSettingID.ID_CAR_DRIVER_SEAT_HEAT, 5))
        assertNull(WidgetWritePolicy.find(cs, CarSettingID.ID_CAR_STEER_WHEEL_HEAT, 1))   // direksiyon ısıtma widget'ı yok
        assertNull(WidgetWritePolicy.find(cs, CarSettingID.ID_CAR_REAR_DOOR_CONTROL, 2))  // bagaj durdurma değeri gönderilmiyor
        assertNull(WidgetWritePolicy.find(0, 0, 0))
        assertEquals(WidgetWritePolicy.Verdict.NotWhitelisted, WidgetWritePolicy.evaluate(cs, 10, 1, parked))
    }

    @Test fun parkGateIsCheckedLazilyAndOnlyForWhitelistedWrites() {
        var calls = 0
        val counting = { calls++; ParkGate.Decision(true, "park") }
        WidgetWritePolicy.evaluate(cs, 10, 1, counting)
        assertEquals("beyaz liste dışı: park okunmaz", 0, calls)
        WidgetWritePolicy.evaluate(vr, 124, 1, counting)
        assertEquals(1, calls)
    }

    @Test fun notParkedIsRefusedWithReason() {
        val v = WidgetWritePolicy.evaluate(vr, 124, 1, moving)
        assertTrue(v is WidgetWritePolicy.Verdict.NotParked)
        assertEquals("refused-not-parked(araç hareketli)", WidgetWritePolicy.resultText(v))
        assertEquals("sent", WidgetWritePolicy.resultText(WidgetWritePolicy.evaluate(vr, 124, 1, parked)))
        assertEquals("refused-not-whitelisted", WidgetWritePolicy.resultText(WidgetWritePolicy.Verdict.NotWhitelisted))
        // Okunamayan hız da reddedilir (gerçek ParkGate ile).
        assertFalse(WidgetWritePolicy.evaluate(vr, 124, 1) { ParkGate.evaluate(null, null) } is WidgetWritePolicy.Verdict.Allowed)
    }

    @Test fun gateOpensWithVerifiedOrTestSwitch() {
        assertFalse(WidgetWritePolicy.gate(false, false))
        assertTrue(WidgetWritePolicy.gate(true, false))
        assertTrue(WidgetWritePolicy.gate(false, true))
        // Test anahtarı açık + salt-okur değil: doğrulanmamış özellikler yazılır; salt-okurken hiçbir şey.
        assertTrue(ComfortWriter.canWrite(true, WidgetWritePolicy.gate(false, true), readOnly = false))
        assertFalse(ComfortWriter.canWrite(true, WidgetWritePolicy.gate(false, true), readOnly = true))
        assertFalse(ClimateWriter.canWrite(WidgetWritePolicy.gate(false, false), readOnly = false))
        // Gerçek sürümde anahtar sabit false: doğrulanmamış özellikler kapalı kalır.
        assertFalse(LauncherEdition.WRITE_TEST)
        assertFalse(ComfortWriter.trunkEnabled)
        assertFalse(ComfortWriter.defrostEnabled)
        assertFalse(ClimateWriter.enabled)
    }

    @Test fun logLineFormat() {
        assertEquals("widget=Sunshade module=327696 id=124 value=1 result=sent", WidgetWritePolicy.logLine("Sunshade", vr, 124, 1, "sent"))
        assertEquals("widget=? module=327681 id=10 value=1 result=refused-not-whitelisted",
            WidgetWritePolicy.logLine(null, cs, 10, 1, "refused-not-whitelisted"))
    }

    @Test fun entriesAreUniqueAndHaveReadBack() {
        val keys = WidgetWritePolicy.ENTRIES.map { it.module to it.id }
        assertEquals(keys.size, keys.toSet().size)
        WidgetWritePolicy.ENTRIES.forEach { assertTrue(it.widget, it.readBack.isNotEmpty()); assertNotNull(it.values) }
    }

    @Test fun decodeFanAcceptsUpToTen() {
        assertEquals(10, OurBottomBar.decodeFan(10))
        assertNull(OurBottomBar.decodeFan(11))
    }
}
