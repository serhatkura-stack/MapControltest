package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDecodeTest {

    @Test fun instantIsTenths() {
        assertEquals(12.1, TripDecode.instantL100(121)!!, 1e-9)
        assertEquals(13.1, TripDecode.instantL100(131)!!, 1e-9)
        assertEquals(0.1, TripDecode.instantL100(1)!!, 1e-9)
        assertEquals(0.0, TripDecode.instantL100(0)!!, 1e-9)
    }

    @Test fun instantInvalidWhenStopped() {
        assertNull(TripDecode.instantL100(0xFFFF))
        assertNull(TripDecode.instantL100(null))
        assertNull(TripDecode.instantL100(-1))
    }

    @Test fun speedIsKmhDirectly() {
        assertEquals(64, TripDecode.speedKmh(64))
        assertEquals(0, TripDecode.speedKmh(0))
        assertNull(TripDecode.speedKmh(0xFFFF))
    }

    @Test fun speedFallsBackToLegacySignal() {
        assertEquals(65, TripDecode.speed(65, 0))
        assertEquals(40, TripDecode.speed(0, 40))
        assertEquals(40, TripDecode.speed(null, 40))
        assertEquals(0, TripDecode.speed(0, 0))
        assertNull(TripDecode.speed(null, null))
    }

    @Test fun movingBySpeedOrValidInstant() {
        assertEquals(true, TripDecode.moving(64, null))
        assertEquals(true, TripDecode.moving(0, 12.1))
        assertEquals(true, TripDecode.moving(null, 12.1))
        assertEquals(false, TripDecode.moving(0, null))
        assertNull(TripDecode.moving(null, null))
        assertFalse(TripDecode.moving(0, null)!!)
        assertTrue(TripDecode.moving(20, null)!!)
    }

    @Test fun tripSinceReset() {
        assertEquals(53.8, TripDecode.tripKm(538)!!, 1e-9)
        assertEquals(13.8, TripDecode.avgL100(138)!!, 1e-9)
        assertEquals(45, TripDecode.avgSpeedKmh(456))
        assertNull(TripDecode.avgL100(0))
        assertNull(TripDecode.avgSpeedKmh(0))
        assertNull(TripDecode.tripKm(null))
    }
}
