package com.mapcontrol.vehicle

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.mapcontrol.launcher.ClimateMath
import com.mapcontrol.launcher.WidgetWritePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.1: launcher klima gücünü tek yazmayla değiştirir (HVAC 18 ← 1 aç / 2 kapat, OEM güç düğmesi gibi; bkz. ClimateMathTest).
 * MapControl konfor otomasyonu hâlâ uzun diziyi kullanır; burada ikisinin güç yazması aynı kalmalı ve dizi beyaz listede olmalı.
 */
class ClimatePowerParityTest {
    private fun pairs(list: List<ClimateAutomationWrites.Write>): List<Pair<Int, Int>> {
        list.forEach { assertEquals(ClimateAutomationWrites.MODULE_HVAC, it.module) }
        return list.map { it.id to it.value }
    }

    @Test fun launcherPowerOnIsAutomationFirstWrite() {
        val first = pairs(ClimateAutomationWrites.onWrites(ClimateAutomationStore.CLIMATE, 0)).first()
        assertEquals(HvacID.ID_AC_WORKING to ClimateMath.powerValue(true), first)
    }

    @Test fun launcherPowerOffIsInAutomationOffWrites() {
        val off = pairs(ClimateAutomationWrites.offWrites(ClimateAutomationStore.CLIMATE))
        assertTrue(off.contains(HvacID.ID_AC_WORKING to ClimateMath.powerValue(false)))
    }

    @Test fun everyAutomationWriteIsWhitelisted() {
        val all = pairs(ClimateAutomationWrites.onWrites(ClimateAutomationStore.CLIMATE, 0)) +
            pairs(ClimateAutomationWrites.climateFollowUpWrites()) +
            pairs(ClimateAutomationWrites.offWrites(ClimateAutomationStore.CLIMATE))
        all.forEach { (id, v) ->
            assertNotNull("id=$id v=$v", WidgetWritePolicy.find(ClimateAutomationWrites.MODULE_HVAC, id, v))
        }
    }

    @Test fun zeroNeverWhitelistedForPowerAcAuto() {
        pairs(ClimateAutomationWrites.climateFollowUpWrites()).forEach { (id, _) ->
            assertNull(WidgetWritePolicy.find(ClimateAutomationWrites.MODULE_HVAC, id, 0))
        }
        assertNull(WidgetWritePolicy.find(ClimateAutomationWrites.MODULE_HVAC, HvacID.ID_AC_WORKING, 0))
    }
}
