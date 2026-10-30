package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLayoutTest {
    private fun policy() = DriveLayoutPolicy()
    private fun DriveLayoutPolicy.at(sec: Long, moving: Boolean?, parked: Boolean = false, can: Boolean = true) =
        update(sec * 1000, moving, parked, can)

    @Test fun entersDriveAfterFiveSecondsOfMotion() {
        val p = policy()
        assertNull(p.at(0, true)); assertNull(p.at(4, true))
        assertEquals(LayoutMode.DRIVE, p.at(5, true))
        assertEquals(LayoutMode.DRIVE, p.mode)
    }

    @Test fun shortMotionDoesNotSwitch() {
        val p = policy()
        p.at(0, true); p.at(3, false)
        assertNull(p.at(6, true)); assertNull(p.at(10, true))
        assertEquals(LayoutMode.DRIVE, p.at(11, true))
    }

    @Test fun unknownSpeedResetsCounters() {
        val p = policy()
        p.at(0, true); p.at(4, null)
        assertNull(p.at(6, true))
    }

    @Test fun redLightStaysInDrive() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        p.at(10, false)
        assertNull(p.at(100, false))      // 90 sn kırmızı ışık
        assertEquals(LayoutMode.DRIVE, p.mode)
    }

    @Test fun fallbackReturnsToParkAfter180Seconds() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        p.at(10, false)
        assertNull(p.at(189, false))
        assertEquals(LayoutMode.PARK, p.at(190, false))
    }

    @Test fun movingAgainRestartsStillTimer() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        p.at(10, false); p.at(150, false); p.at(151, true)
        p.at(152, false)
        assertNull(p.at(320, false))
        assertEquals(LayoutMode.PARK, p.at(332, false))
    }

    @Test fun parkGearSwitchesAfterTwoSeconds() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        p.at(10, false, parked = true)
        assertNull(p.at(11, false, parked = true))
        assertEquals(LayoutMode.PARK, p.at(12, false, parked = true))
    }

    @Test fun parkGearBlipDoesNotSwitch() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        p.at(10, false, parked = true); p.at(11, false, parked = false)
        assertNull(p.at(13, false, parked = true))
        assertNull(p.at(14, false, parked = true))
        assertEquals(LayoutMode.PARK, p.at(15, false, parked = true))
    }

    @Test fun deferredWhileBusyThenSwitches() {
        val p = policy()
        p.at(0, true)
        assertNull(p.at(20, true, can = false))
        assertEquals(LayoutMode.DRIVE, p.at(21, true))
    }

    @Test fun immediateParkForDemo() {
        val p = policy()
        p.at(0, true); p.at(5, true)
        assertEquals(LayoutMode.PARK, p.update(6000, false, false, true, immediatePark = true))
    }

    @Test fun signalsMeasuredOnCar() {
        assertEquals(1, DriveSignals.PARK_GEAR_VALUE)
        assertEquals(1, DriveSignals.HANDBRAKE_ON_VALUE)
    }

    @Test fun layoutKeysPerProfileAndMode() {
        assertEquals("widgets_v1", LayoutKeys.key(LayoutMode.PARK))   // mevcut anahtar korunur
        assertEquals("widgets_drive_v1", LayoutKeys.key(LayoutMode.DRIVE))
        assertEquals("launcher_layout", LayoutKeys.prefsName(1))
        assertEquals("launcher_layout_p2", LayoutKeys.prefsName(2))
        assertEquals("launcher_layout_p3", LayoutKeys.prefsName(3))
        assertEquals("auto_drive_layout", DriveLayoutPrefs.key(1))
        assertEquals("auto_drive_layout_p2", DriveLayoutPrefs.key(2))
    }

    @Test fun defaultDriveLayoutsAreValid() {
        for (oem in listOf(true, false)) {
            val l = defaultDriveLayout(oem, List(8) { "pkg$it" })
            assertTrue("oem=$oem", LayoutStore.isValid(l))
            assertTrue(l.any { it.type == WidgetType.MEDIA && it.span == Span(6, 4) })
        }
    }
}
