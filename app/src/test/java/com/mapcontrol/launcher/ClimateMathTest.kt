package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateMathTest {
    private val tempDp = ClimateMath.TEMP_DRAG_DP_PER_STEP

    @Test fun dragDistanceToSteps() {
        assertEquals(0, ClimateMath.stepsForDrag(39f, 1f, tempDp))
        assertEquals(1, ClimateMath.stepsForDrag(40f, 1f, tempDp))
        assertEquals(-2, ClimateMath.stepsForDrag(-85f, 1f, tempDp))
        // 2x yoğunluk: 40dp = 80px
        assertEquals(0, ClimateMath.stepsForDrag(79f, 2f, tempDp))
        assertEquals(3, ClimateMath.stepsForDrag(240f, 2f, tempDp))
    }

    @Test fun tempClampsToRangeAndHalfSteps() {
        assertEquals(32.0, ClimateMath.targetTemp(31.5, 5), 0.0)
        assertEquals(16.0, ClimateMath.targetTemp(16.5, -9), 0.0)
        assertEquals(22.5, ClimateMath.targetTemp(22.0, 1), 0.0)
        assertEquals(22.5, ClimateMath.clampTemp(22.4), 0.0)
    }

    @Test fun fanTargets() {
        assertEquals(10, ClimateMath.targetFan(6, 6))
        assertEquals(1, ClimateMath.targetFan(2, -5))
        assertEquals(0, ClimateMath.targetFan(0, -2))
        assertEquals(1, ClimateMath.targetFan(0, 1))
        assertEquals(3, ClimateMath.targetFan(3, 0))
    }

    @Test fun rawEncodingInvertsDecode() {
        assertEquals(1, ClimateMath.encodeTemp(16.0))
        assertEquals(21, ClimateMath.encodeTemp(26.0))
        assertEquals(33, ClimateMath.encodeTemp(32.0))
        assertEquals(33, ClimateMath.encodeTemp(40.0))
        for (raw in 1..33) assertEquals(raw, ClimateMath.encodeTemp(OurBottomBar.decodeTemp(raw)!!))
        assertEquals(10, ClimateMath.encodeFan(12))
    }

    @Test fun neverWritesUnlessVerifiedAndNotReadOnly() {
        assertFalse(ClimateWriter.canWrite(verified = false, readOnly = false))
        assertFalse(ClimateWriter.canWrite(verified = false, readOnly = true))
        assertFalse(ClimateWriter.canWrite(verified = true, readOnly = true))
        assertTrue(ClimateWriter.canWrite(verified = true, readOnly = false))
    }

    @Test fun shippedFlagIsOffAndWritersAreInert() {
        assertFalse(ClimateWriter.CLIMATE_WRITE_VERIFIED)
        assertFalse(ClimateWriter.enabled)
        assertFalse(ClimateWriter.setTemperature(ClimateSide.DRIVER, 24.0))
        assertFalse(ClimateWriter.setFan(3))
    }
}
