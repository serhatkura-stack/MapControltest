package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatStoneTest {
    @Test fun `dongu kapali 3 2 1 kapali`() {
        assertEquals(3, SeatHeatModel.nextLevel(0))
        assertEquals(3, SeatHeatModel.nextLevel(null))
        assertEquals(2, SeatHeatModel.nextLevel(3))
        assertEquals(1, SeatHeatModel.nextLevel(2))
        assertEquals(0, SeatHeatModel.nextLevel(1))
    }

    @Test fun `demo seviyeleri ayristirilir`() {
        assertEquals(3 to 0, SeatStone.parseLevels("3,0"))
        assertEquals(1 to 2, SeatStone.parseLevels(" 1 , 2 "))
        assertEquals(3 to 0, SeatStone.parseLevels("9,-4"))
        assertNull(SeatStone.parseLevels("3"))
        assertNull(SeatStone.parseLevels("a,b"))
        assertNull(SeatStone.parseLevels(null))
    }

    @Test fun `gorunum ayristirilir`() {
        assertEquals(SeatCardStyle.LAVA, SeatCardStyle.parse("Lava"))
        assertEquals(SeatCardStyle.CLASSIC, SeatCardStyle.parse("classic"))
        assertNull(SeatCardStyle.parse("x"))
    }

    @Test fun `kaplama seviyeyle artar`() {
        assertEquals(0f, SeatStone.coverage(0), 0f)
        assertTrue(SeatStone.coverage(1) < SeatStone.coverage(2))
        assertTrue(SeatStone.coverage(2) < SeatStone.coverage(3))
        assertEquals(0.45f, SeatStone.coverage(3), 0f)
    }

    @Test fun `cizim deterministik ve havuzlar hedefe yaklasir`() {
        val a = SeatStone.cracks(SeatStone.seed(false, true), 440f, 280f)
        val b = SeatStone.cracks(SeatStone.seed(false, true), 440f, 280f)
        assertEquals(a.size, b.size)
        assertEquals(a[0].points[1].x, b[0].points[1].x, 0f)
        var prev = 0f
        for (lv in 1..3) {
            val cov = SeatStone.estimateCoverage(SeatStone.blobs(lv, a, 1L, 440f, 280f), 440f, 280f)
            assertTrue("level $lv cov=$cov", cov >= SeatStone.coverage(lv) * 0.9f && cov > prev)
            prev = cov
        }
        assertTrue(SeatStone.blobs(0, a, 1L, 440f, 280f).isEmpty())
    }
}
