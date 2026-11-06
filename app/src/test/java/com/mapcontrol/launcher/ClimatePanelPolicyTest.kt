package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.launcher.ClimatePanelPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClimatePanelPolicyTest {
    private val hv = VDEventCarInfo.MODULE_HVAC

    private fun decide(explicit: Boolean = false, shownAtArm: Boolean = false, showing: Boolean, now: Long = 1_000, closes: Int = 0) =
        ClimatePanelPolicy.decide(explicit, shownAtArm, showing, now, deadlineMs = 4_000, closes = closes)

    @Test fun closesOnlyAPanelThatPoppedAfterOurWrite() {
        assertEquals(Decision.WAIT, decide(showing = false))
        assertEquals(Decision.CLOSE, decide(showing = true))
        assertEquals(Decision.CLOSE, decide(showing = true, closes = ClimatePanelPolicy.MAX_CLOSES - 1))
        assertEquals(Decision.LIMIT, decide(showing = true, closes = ClimatePanelPolicy.MAX_CLOSES))
        assertEquals(Decision.EXPIRED, decide(showing = true, now = 4_001))
    }

    @Test fun aRepeatedWriteDoesNotCutTheCloseSettleShort() {
        // Kapatmadan 300 ms sonra yeni yazma: bakış oturma süresinin sonuna (1200 ms sonra) kalır, 200 ms'ye değil.
        assertEquals(1_200L, ClimatePanelPolicy.nextCheckDelay(nowMs = 1_300, settleUntilMs = 2_500))
        assertEquals(ClimatePanelPolicy.POLL_MS, ClimatePanelPolicy.nextCheckDelay(nowMs = 2_450, settleUntilMs = 2_500))
        assertEquals(ClimatePanelPolicy.POLL_MS, ClimatePanelPolicy.nextCheckDelay(nowMs = 5_000, settleUntilMs = 0))
    }

    @Test fun aPanelTheCloseCannotTouchEndsTheGuardInsteadOfBurningCloses() {
        assertTrue(ClimatePanelBits.closeCanAct(null))
        assertTrue(ClimatePanelBits.closeCanAct(1))
        assertTrue(ClimatePanelBits.closeCanAct(2))
        assertTrue(ClimatePanelBits.closeCanAct(4))
        assertFalse(ClimatePanelBits.closeCanAct(0))
        assertFalse(ClimatePanelBits.closeCanAct(8))
        val d = ClimatePanelPolicy.decide(false, false, showingNow = true, nowMs = 1_000, deadlineMs = 4_000, closes = 0, closeCanAct = false)
        assertEquals(Decision.UNCLOSABLE, d)
        // Panel kapalıyken bit olmaması bir şey değiştirmez: beklemeye devam.
        assertEquals(Decision.WAIT, ClimatePanelPolicy.decide(false, false, showingNow = false, nowMs = 1_000, deadlineMs = 4_000, closes = 0, closeCanAct = false))
    }

    @Test fun neverClosesAnExplicitlyOpenedOrAlreadyOpenPanel() {
        // Yukarı kaydırma ile açılan panel: süre içinde de olsa kapanmaz.
        assertEquals(Decision.KEEP_EXPLICIT_OPEN, decide(explicit = true, showing = true))
        assertEquals(Decision.KEEP_EXPLICIT_OPEN, decide(explicit = true, shownAtArm = true, showing = true))
        // Yazmadan önce zaten açıktı (kullanıcının paneli).
        assertEquals(Decision.KEEP_ALREADY_OPEN, decide(shownAtArm = true, showing = true))
    }

    @Test fun quietWritesUseTheOemDockIdsWithTheSameRawValue() {
        assertEquals(76, ClimateWriter.tempId(ClimateSide.DRIVER, quiet = true))
        assertEquals(78, ClimateWriter.tempId(ClimateSide.PASSENGER, quiet = true))
        assertEquals(77, ClimateWriter.fanId(quiet = true))
        assertEquals(HvacID.ID_AC_TEMPERATURE_DRIVER, ClimateWriter.tempId(ClimateSide.DRIVER, quiet = false))
        assertEquals(HvacID.ID_AC_TEMPERATURE_COPILOT, ClimateWriter.tempId(ClimateSide.PASSENGER, quiet = false))
        assertEquals(HvacID.ID_AC_BLOW_SPEED_LEVEL, ClimateWriter.fanId(quiet = false))
    }

    @Test fun dockIdsAreWhitelistedWithOemValuesOnly() {
        var c = ClimateMath.LO_TEMP
        while (c <= ClimateMath.HI_TEMP) {
            for (side in ClimateSide.values()) {
                assertNotNull("temp $c $side", WidgetWritePolicy.find(hv, ClimateWriter.tempId(side, true), ClimateMath.encodeTemp(c)))
            }
            c += 0.5
        }
        for (f in 1..ClimateMath.FAN_MAX) assertNotNull(WidgetWritePolicy.find(hv, ClimateWriter.fanId(true), ClimateMath.encodeFan(f)))
        assertNull(WidgetWritePolicy.find(hv, 76, 31))
        assertNull(WidgetWritePolicy.find(hv, 78, -1))
        assertNull(WidgetWritePolicy.find(hv, 77, 0))
        assertNull(WidgetWritePolicy.find(hv, 77, 11))
        // HVAC 9'u biz yazmayız (kapatmada 9 ← 2'yi OEM HvacService gönderir): beyaz listede YOK.
        for (v in 0..3) assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_DISPLAY_ACTIVE, v))
    }

    @Test fun ourCarsLowercaseShowKeyIsReadFirst() {
        assertEquals("hvacshow", ClimatePanelBits.SHOW_KEYS.first())
        // Bizim araç (SVHvac) yalnız küçük harfli anahtarı yazar; eskiden okunan "hvacShow" null kalıyordu.
        assertTrue(ClimatePanelBits.anyShown { if (it == "hvacshow") "true" else null })
        assertFalse(ClimatePanelBits.anyShown { if (it == "hvacshow") "false" else null })
        assertFalse(ClimatePanelBits.anyShown { null })
        // Arkadaşın build'i: yedek anahtarlar.
        assertTrue(ClimatePanelBits.anyShown { if (it == "hvacShow") "true" else null })
        assertTrue(ClimatePanelBits.anyShown { if (it == "HVAC_DIALOG_IS_SHOW") "true" else null })
        assertFalse(ClimatePanelBits.anyShown { "TRUE" })
    }

    @Test fun hvac9IsABitField() {
        assertFalse(ClimatePanelBits.frontRow(null))
        assertFalse(ClimatePanelBits.frontRow(0))
        assertTrue(ClimatePanelBits.frontRow(1))
        assertFalse(ClimatePanelBits.frontRow(2))
        assertTrue(ClimatePanelBits.pm25(2))
        assertTrue(ClimatePanelBits.fragrance(4))
        assertTrue(ClimatePanelBits.frontRow(5))
        assertTrue(ClimatePanelBits.fragrance(5))
        assertFalse(ClimatePanelBits.pm25(5))
        assertEquals("null", ClimatePanelBits.describe(null))
        assertEquals("0", ClimatePanelBits.describe(0))
        assertEquals("1[front]", ClimatePanelBits.describe(1))
        assertEquals("7[front,pm25,fragrance]", ClimatePanelBits.describe(7))
    }

    @Test fun closeWaitsForEcuAndCloseAnimationBeforeRechecking() {
        // ECU ~130 ms + 800 ms animasyon; daha kısa beklenirse 9 ← 2 boş yere yinelenir.
        assertTrue(ClimatePanelPolicy.CLOSE_SETTLE_MS >= 1_000L)
        assertTrue(ClimatePanelPolicy.CLOSE_SETTLE_MS < ClimatePanelPolicy.GUARD_MS)
    }

    @Test fun dockFanIdStaysAt77UntilCarTestEvenThoughOemAutoDockUses12() {
        assertEquals(77, ClimateWriter.fanId(quiet = true))
        assertEquals(12, ClimateWriter.OEM_DOCK_FAN_ID_T1E24)
    }
}
