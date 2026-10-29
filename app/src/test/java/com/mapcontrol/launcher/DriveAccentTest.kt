package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAccentTest {

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun contrastOnWhite(c: Int) = 1.05 / (lum(c) + 0.05)

    @Test fun normalAndUnknownKeepDefaultAccent() {
        for (night in listOf(true, false)) {
            assertNull(DriveAccent.accentFor(DriveAccent.NORMAL, night))
            assertNull(DriveAccent.accentFor(9, night))
            assertNull(DriveAccent.accentFor(-1, night))
        }
        assertFalse(DriveAccent.hasBadge(DriveAccent.NORMAL))
    }

    @Test fun everyOtherModeHasDistinctReadableAccent() {
        val modes = listOf(0, 2, 3, 4, 5, 6)
        val seen = HashSet<Int>()
        for (m in modes) {
            val n = DriveAccent.accentFor(m, true)
            val d = DriveAccent.accentFor(m, false)
            assertNotNull(n); assertNotNull(d)
            assertTrue("night $m", contrastOnWhite(n!!) >= 3.0)
            assertTrue("day $m", contrastOnWhite(d!!) >= 4.5)
            assertTrue(seen.add(n)); assertTrue(seen.add(d))
            assertTrue(DriveAccent.hasBadge(m))
        }
    }

    @Test fun debounceAppliesOnlyAfterStableWindow() {
        val p = DriveModePolicy(2_000)
        assertNull(p.update(0, 2, true))
        assertNull(p.update(1_999, 2, true))
        assertEquals(2, p.update(2_000, 2, true))
        assertNull(p.update(5_000, 2, true))
    }

    @Test fun flickerResetsTimerAndReturningToAppliedCancels() {
        val p = DriveModePolicy(2_000)
        assertNull(p.update(0, 2, true))
        assertNull(p.update(1_500, 0, true))      // başka mod: sayaç yeniden
        assertNull(p.update(3_000, 0, true))
        assertEquals(0, p.update(3_500, 0, true))
        assertNull(p.update(4_000, 1, true))
        assertNull(p.update(5_000, 0, true))      // uygulanan moda dönüş: iptal
        assertNull(p.update(9_000, 0, true))
    }

    @Test fun deferredWhileBlockedAndNoDataKeepsMode() {
        val p = DriveModePolicy(2_000)
        assertNull(p.update(0, 2, false))
        assertNull(p.update(10_000, 2, false))
        assertEquals(2, p.update(10_500, 2, true))
        assertNull(p.update(20_000, null, true))
    }
}
