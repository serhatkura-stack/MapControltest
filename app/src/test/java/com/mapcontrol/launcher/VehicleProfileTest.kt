package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {

    @Test fun detectsFromDisplay() {
        assertEquals(VehicleProfile.TIGGO7, VehicleProfile.detect(1920, 720))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.detect(2560, 1440))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.detect(1440, 2560))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.detect(1920, 1080)) // 16:9
        assertEquals(VehicleProfile.TIGGO7, VehicleProfile.detect(0, 0))
    }

    @Test fun overrideWins() {
        assertEquals(VehicleProfile.TIGGO7, VehicleProfile.choose(1, VehicleProfile.TIGGO8))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.choose(2, VehicleProfile.TIGGO7))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.choose(0, VehicleProfile.TIGGO8))
        assertEquals(VehicleProfile.TIGGO8, VehicleProfile.parse(" Tiggo8 "))
    }

    @Test fun rowsAndBar() {
        assertEquals(6, VehicleProfile.TIGGO7.gridRows)
        assertEquals(8, VehicleProfile.TIGGO8.gridRows)
        assertEquals(104, VehicleProfile.TIGGO7.barHeightDp(960f))
        assertEquals(85, VehicleProfile.TIGGO8.barHeightDp(960f))
        assertEquals("launcher_layout", LayoutKeys.prefsName(1, VehicleProfile.TIGGO7))
        assertEquals("launcher_layout_t8", LayoutKeys.prefsName(1, VehicleProfile.TIGGO8))
        assertEquals("launcher_layout_p2_t8", LayoutKeys.prefsName(2, VehicleProfile.TIGGO8))
    }

    @Test fun defaultsFitTheirGridWithoutOverlap() {
        for (p in VehicleProfile.values()) {
            val layouts = mapOf(
                "park" to defaultLayout(false, listOf("a", "b"), p),
                "drive" to defaultDriveLayout(false, emptyList(), p),
            )
            for ((name, l) in layouts) {
                val cells = HashSet<Int>()
                for (w in l) {
                    assertTrue("$p $name ${w.id} fits", w.fits(p.gridRows))
                    assertTrue("$p $name ${w.id} span", w.span in w.type.sizes)
                    for (r in w.row until w.row + w.span.h) for (c in w.col until w.col + w.span.w) {
                        assertTrue("$p $name overlap at $r,$c", cells.add(r * GRID_COLS + c))
                    }
                }
                assertEquals("$p $name ids unique", l.size, l.map { it.id }.toSet().size)
                assertEquals("$p $name fully used", GRID_COLS * p.gridRows, cells.size)
                assertFalse(l.isEmpty())
            }
        }
    }
}
