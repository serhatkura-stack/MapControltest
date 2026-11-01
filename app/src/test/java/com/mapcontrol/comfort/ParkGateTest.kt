package com.mapcontrol.comfort

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkGateTest {

    @Test fun parkGate() {
        assertTrue(ParkGate.evaluate(0, null).allowed)
        assertTrue(ParkGate.evaluate(0, 0).allowed)
        assertFalse(ParkGate.evaluate(null, 0).allowed)      // 119 okunamazsa izin yok
        assertFalse(ParkGate.evaluate(1, 0).allowed)
        assertFalse(ParkGate.evaluate(0, 5).allowed)         // eski hız hareketli
        assertFalse(ParkGate.evaluate(-1, 0).allowed)
        assertFalse(ParkGate.evaluate(65535, 0).allowed)
    }
}
