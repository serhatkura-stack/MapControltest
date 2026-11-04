package com.mapcontrol.launcher

import com.desaysv.ivi.vdb.event.id.dsp.VDEventDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desay OEM komutlarından türetilen yeni widget'ların saf kuralları ve yazma kapıları. */
class OemWidgetsTest {
    private val cs = com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo.MODULE_CAR_SETTING
    private val hv = com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo.MODULE_HVAC

    private fun allowed(m: Int, i: Int, v: Int) = WidgetWritePolicy.evaluate(m, i, v) is WidgetWritePolicy.Verdict.Allowed

    // --- Klima tuşları ---

    @Test fun climateKeyCommandsMatchOem() {
        assertEquals(listOf(ComfortCmd(hv, 1, 1)), ClimateKeysMath.commands(ClimateKey.AC, true))
        assertEquals(listOf(ComfortCmd(hv, 1, 2)), ClimateKeysMath.commands(ClimateKey.AC, false))
        assertEquals(listOf(ComfortCmd(hv, 4, 1)), ClimateKeysMath.commands(ClimateKey.AUTO, true))
        assertEquals(listOf(ComfortCmd(hv, 4, 2)), ClimateKeysMath.commands(ClimateKey.AUTO, false))
        // Devridaim: 1 iç hava, 2 dış hava.
        assertEquals(listOf(ComfortCmd(hv, 8, 1)), ClimateKeysMath.commands(ClimateKey.RECIRC, true))
        assertEquals(listOf(ComfortCmd(hv, 8, 2)), ClimateKeysMath.commands(ClimateKey.RECIRC, false))
        // Güç: 18 ← 1 aç / 2 kapat.
        assertEquals(listOf(ComfortCmd(hv, 18, 1)), ClimateKeysMath.commands(ClimateKey.POWER, true))
        assertEquals(listOf(ComfortCmd(hv, 18, 2)), ClimateKeysMath.commands(ClimateKey.POWER, false))
    }

    @Test fun climateKeyReadDecoding() {
        assertEquals(true, ClimateKeysMath.decodeOn(1))
        assertEquals(false, ClimateKeysMath.decodeOn(0))
        assertEquals(false, ClimateKeysMath.decodeOn(2))
        assertNull(ClimateKeysMath.decodeOn(null))
        assertEquals(true, ClimateKeysMath.decodeRecirc(0))   // OEM getCirculationState: HVAC 8 == 0
        assertEquals(false, ClimateKeysMath.decodeRecirc(1))
        assertNull(ClimateKeysMath.decodeRecirc(null))
        val r = ClimateKeysReading(ac = true, auto = false, recirc = null, power = true)
        assertEquals(true, ClimateKeysMath.current(r, ClimateKey.AC))
        assertEquals(false, ClimateKeysMath.current(r, ClimateKey.AUTO))
        assertNull(ClimateKeysMath.current(r, ClimateKey.RECIRC))
        assertNull(ClimateKeysMath.current(null, ClimateKey.POWER))
    }

    @Test fun everyNewCommandIsWhitelisted() {
        for (key in ClimateKey.values()) for (on in listOf(true, false)) {
            ClimateKeysMath.commands(key, on).forEach { assertTrue("$key $on", allowed(it.module, it.id, it.value)) }
        }
        for (key in HeatKey.values()) for (on in listOf(true, false)) {
            HeatMath.commands(key, on).forEach { assertTrue("$key $on", allowed(it.module, it.id, it.value)) }
        }
        for (fade in 0..SoundFieldMath.MAX) assertTrue("fade $fade", allowed(VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID, fade))
    }

    @Test fun newWritesStayGatedAndRefuseOtherValues() {
        assertFalse(ComfortWriter.CLIMATE_KEYS_WRITE_VERIFIED)
        assertFalse(ComfortWriter.HEAT_WRITE_VERIFIED)
        assertFalse(ComfortWriter.REAR_SPEAKER_WRITE_VERIFIED)
        assertFalse(ComfortWriter.climateKeysEnabled)
        assertFalse(ComfortWriter.heatEnabled)
        assertFalse(ComfortWriter.rearSpeakerEnabled)
        assertFalse(ComfortWriter.setClimateKey(ClimateKey.AC, true))
        assertFalse(ComfortWriter.setHeat(HeatKey.STEERING, true))
        assertFalse(ComfortWriter.setFader(10, 0))
        assertNull(WidgetWritePolicy.find(hv, 1, 0))
        assertNull(WidgetWritePolicy.find(hv, 4, 3))
        assertNull(WidgetWritePolicy.find(hv, 8, 0))
        assertNull(WidgetWritePolicy.find(hv, 9, 2))        // HVAC 9 yalnızca ← 1
        assertNull(WidgetWritePolicy.find(cs, 160, 0))
        assertNull(WidgetWritePolicy.find(cs, 160, 2))
        assertNull(WidgetWritePolicy.find(VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID, SoundFieldMath.MAX + 1))
        assertNull(WidgetWritePolicy.find(VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID, -1))
        assertNotNull(WidgetWritePolicy.find(hv, 18, 1))
    }

    // --- Isıtmalar ---

    @Test fun steeringHeatIsAToggleSentWithDisplayActiveFirst() {
        // OEM onCarSteerWheelHeatClick: önce HVAC 9 ← 1, sonra CarSetting 160 ← 1; açma ve kapatma aynı.
        val expected = listOf(ComfortCmd(hv, 9, 1), ComfortCmd(cs, 160, 1))
        assertEquals(expected, HeatMath.commands(HeatKey.STEERING, true))
        assertEquals(expected, HeatMath.commands(HeatKey.STEERING, false))
    }

    @Test fun windshieldHeatOnOffAndReads() {
        assertEquals(listOf(ComfortCmd(hv, 65, 1)), HeatMath.commands(HeatKey.WINDSHIELD, true))
        assertEquals(listOf(ComfortCmd(hv, 65, 2)), HeatMath.commands(HeatKey.WINDSHIELD, false))
        assertEquals(true, HeatMath.decodeOn(1))
        assertEquals(false, HeatMath.decodeOn(0))
        assertNull(HeatMath.decodeOn(null))
        val r = HeatReading(steering = true, windshield = null)
        assertEquals(true, HeatMath.current(r, HeatKey.STEERING))
        assertNull(HeatMath.current(r, HeatKey.WINDSHIELD))
    }

    // --- Lastik basıncı ---

    @Test fun tireValuesDecodeToBar() {
        assertEquals(2.4, TireDecode.bar(240)!!, 1e-9)    // kPa
        assertEquals(2.4, TireDecode.bar(24)!!, 1e-9)     // 0,1 bar
        assertEquals(0.5, TireDecode.bar(50)!!, 1e-9)
        assertNull(TireDecode.bar(0))
        assertNull(TireDecode.bar(255 * 255))
        assertNull(TireDecode.bar(1000))
        assertNull(TireDecode.bar(-1))
    }

    @Test fun tireParseHandlesFourOneAndGarbage() {
        val four = TireDecode.parse(intArrayOf(240, 238, 230, 0), warningRaw = 0)!!
        assertTrue(four.perWheel)
        assertEquals(listOf(2.4, 2.38, 2.3, null), four.values)
        assertEquals(false, four.warning)
        val one = TireDecode.parse(intArrayOf(235))!!
        assertFalse(one.perWheel)
        assertEquals(listOf(2.35), one.values)
        assertNull(one.warning)
        assertNull(TireDecode.parse(null))
        assertNull(TireDecode.parse(intArrayOf()))
        assertNull(TireDecode.parse(intArrayOf(0, 0, 0, 0)))   // hiç sensör
        assertNull(TireDecode.parse(intArrayOf(240, 240)))      // 2 elemanlı: bilinmeyen düzen
        assertEquals(true, TireDecode.parse(intArrayOf(240, 240, 240, 240), warningRaw = 1)!!.warning)
        assertTrue(TireDecode.isLow(1.5))
        assertFalse(TireDecode.isLow(2.2))
        assertFalse(TireDecode.isLow(null))
    }

    @Test fun tireLowFindsFirstLowWheelAndShowsOnlyWhenLow() {
        val t = TireDecode.parse(intArrayOf(240, 160, 230, 220))!!   // 2,4 · 1,6 · 2,3 · 2,2 bar
        assertEquals(TireLow(1, 1.6), TireDecode.low(t))
        assertEquals(TireLow(null, 1.5), TireDecode.low(TireReading(listOf(1.5))))
        assertNull(TireDecode.low(TireDecode.parse(intArrayOf(240, 240, 240, 240))))
        assertNull(TireDecode.low(null))
        // Araç kartında uyarı yalnızca uyarı sinyali açıkken ya da bir değer düşükken görünür.
        assertFalse(TireDecode.alertShown(false, null))
        assertFalse(TireDecode.alertShown(null, null))
        assertTrue(TireDecode.alertShown(true, null))
        assertTrue(TireDecode.alertShown(null, TireLow(0, 1.5)))
    }

    @Test fun tirePressureShownInPsi() {
        assertEquals(26.0, TireDecode.LOW_PSI, 1e-9)
        assertEquals(23, TireDecode.psi(1.6))    // 23,2 psi
        assertEquals(35, TireDecode.psi(2.4))    // 34,8 psi
        assertEquals(26, TireDecode.psi(TireDecode.LOW_BAR))
        assertTrue(TireDecode.isLow(TireDecode.LOW_BAR - 0.01))
        assertFalse(TireDecode.isLow(TireDecode.LOW_BAR))
    }

    // --- Yolculuk toplamları ---

    @Test fun tripTotalsDecode() {
        assertEquals(11032, TripDecode.totalKm(11032))
        assertNull(TripDecode.totalKm(0))
        assertNull(TripDecode.totalKm(null))
        assertEquals(128.4, TripDecode.totalFuelL(1284)!!, 1e-9)
        assertNull(TripDecode.totalFuelL(0))
        assertEquals(72, TripDecode.drivingMinutes(intArrayOf(0, 1, 12, 30)))   // ID_DRIVE_TIME biçimi: saat = 0*256+1, dk 12
    }

    @Test fun drivingMinutesOtherShapes() {
        assertEquals(90, TripDecode.drivingMinutes(intArrayOf(90)))
        assertEquals(300, TripDecode.drivingMinutes(intArrayOf(1, 44)))        // 2 bayt: 1*256+44
        assertNull(TripDecode.drivingMinutes(intArrayOf(1, 2, 3)))
        assertNull(TripDecode.drivingMinutes(null))
    }

    // --- Arka hoparlör ---

    @Test fun rearSpeakerMuteLogic() {
        val front = SoundFieldMath.FRONT_EXTREME
        assertEquals(true, SoundFieldMath.rearMuted(front))
        assertEquals(false, SoundFieldMath.rearMuted(SoundFieldMath.CENTER))
        assertNull(SoundFieldMath.rearMuted(null))
        assertNull(SoundFieldMath.rearMuted(-10000))
        assertNull(SoundFieldMath.rearMuted(99))
        // Okuma boşken: son yazım, yoksa orta (Açık).
        assertEquals(SoundFieldReading(10, 10), SoundFieldMath.assumed(null, null))
        assertEquals(SoundFieldReading(7, 0), SoundFieldMath.assumed(7, 0))
        assertEquals(SoundFieldReading(10, 10), SoundFieldMath.assumed(99, -1))
        // Açıkken dokunuş: ön uç. Kapalıyken: saklanan değer; yoksa/geçersizse/uçla aynıysa orta.
        assertEquals(front, SoundFieldMath.targetFade(SoundFieldMath.CENTER, null))
        assertEquals(7, SoundFieldMath.targetFade(front, 7))
        assertEquals(SoundFieldMath.CENTER, SoundFieldMath.targetFade(front, null))
        assertEquals(SoundFieldMath.CENTER, SoundFieldMath.targetFade(front, front))
        assertEquals(SoundFieldMath.CENTER, SoundFieldMath.targetFade(front, 99))
        assertNull(SoundFieldMath.targetFade(null, 7))
    }

    @Test fun soundFieldConstantsAreInRange() {
        assertEquals(983242, VDEventDsp.SOUND_FIELD)
        assertTrue(SoundFieldMath.FRONT_EXTREME in 0..SoundFieldMath.MAX)
        assertEquals(10, SoundFieldMath.CENTER)
    }

    // --- Katalog ---

    @Test fun newWidgetsAreRegisteredWithSizes() {
        assertEquals(listOf(Span(2, 2), Span(2, 1)), WidgetType.REAR_SPEAKER.sizes)
        for (t in listOf("rear_speaker")) assertNotNull(WidgetType.fromKey(t))
    }
}
