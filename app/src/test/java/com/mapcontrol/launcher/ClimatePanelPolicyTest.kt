package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.launcher.ClimatePanelPolicy.Decision
import org.junit.Assert.assertEquals
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
        // Panel kapatma araca yazma değildir: HVAC 9 (görüntü etkin) beyaz listede YOK.
        for (v in 0..3) assertNull(WidgetWritePolicy.find(hv, HvacID.ID_AC_DISPLAY_ACTIVE, v))
    }
}
