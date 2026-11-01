package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarMathTest {
    @Test fun tempFractionEndsAndNeutral() {
        assertEquals(-1f, BarMath.tempFraction(15.5), 0f)
        assertEquals(-1f, BarMath.tempFraction(18.0), 0f)
        assertEquals(0f, BarMath.tempFraction(22.0), 0f)
        assertEquals(1f, BarMath.tempFraction(27.0), 0f)
        assertEquals(1f, BarMath.tempFraction(30.5), 0f)
    }

    @Test fun tempFractionIsMonotonic() {
        var prev = -2f
        var c = 15.5
        while (c <= 30.5) {
            val f = BarMath.tempFraction(c)
            assertTrue(f >= prev)
            prev = f
            c += 0.5
        }
        assertEquals(-0.5f, BarMath.tempFraction(20.0), 0.0001f)
        assertEquals(0.4f, BarMath.tempFraction(24.0), 0.0001f)
    }

    @Test fun fanSpinStopsWhenOffAndSpeedsUp() {
        assertNull(BarMath.fanPeriodMs(0))
        assertNull(BarMath.fanPeriodMs(null))
        val slow = BarMath.fanPeriodMs(1)!!
        val fast = BarMath.fanPeriodMs(10)!!
        assertTrue(slow in 2300L..2500L)
        assertTrue(fast in 300L..420L)
        var prev = Long.MAX_VALUE
        for (l in 1..10) { val p = BarMath.fanPeriodMs(l)!!; assertTrue(p < prev); prev = p }
        assertEquals(fast, BarMath.fanPeriodMs(99))
    }

    @Test fun lerpChannelClamps() {
        assertEquals(0, BarMath.lerpChannel(0, 100, -1f))
        assertEquals(100, BarMath.lerpChannel(0, 100, 2f))
        assertEquals(50, BarMath.lerpChannel(0, 100, 0.5f))
    }

    @Test fun slotsParseFallsBackPerSlot() {
        assertEquals(BarSlots.DEFAULTS, BarSlots.parse(null))
        assertEquals(BarSlots.DEFAULTS, BarSlots.parse(""))
        assertEquals(listOf("rear", "apps", "car", "app:com.x", "phone"), BarSlots.parse("rear,bogus,car,app:com.x,"))
        assertEquals(listOf("trunk", "cam360", "frostnav", "homeset", "app:a.b"), BarSlots.parse(" trunk ,cam360,frostnav,homeset,app:a.b,extra"))
    }

    @Test fun slotsValidAndRoundTrip() {
        assertTrue(BarSlots.valid("home"))
        assertTrue(BarSlots.valid("app:com.spotify"))
        assertTrue(!BarSlots.valid("app:"))
        assertTrue(!BarSlots.valid("nope"))
        assertTrue(!BarSlots.valid(null))
        val s = listOf("rear", "trunk", "car", "app:com.x.y", "phone")
        assertEquals(s, BarSlots.parse(BarSlots.serialize(s)))
    }

    @Test fun barStyleParse() {
        assertEquals(BarStyle.A, BarStyle.resolve(null))
        assertEquals(BarStyle.B, BarStyle.resolve(" B "))
        assertEquals(BarStyle.A, BarStyle.resolve("zzz"))
    }
}
