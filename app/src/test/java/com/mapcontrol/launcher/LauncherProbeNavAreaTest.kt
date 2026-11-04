package com.mapcontrol.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherProbeNavAreaTest {
    @Test
    fun yalnizcaIzinliDegerlerKabulEdilir() {
        assertTrue(LauncherProbe.isValidNavArea(0))
        assertTrue(LauncherProbe.isValidNavArea(9))
        assertTrue(LauncherProbe.isValidNavArea(10))
        assertFalse(LauncherProbe.isValidNavArea(-1))
        assertFalse(LauncherProbe.isValidNavArea(1))
        assertFalse(LauncherProbe.isValidNavArea(11))
    }
}
