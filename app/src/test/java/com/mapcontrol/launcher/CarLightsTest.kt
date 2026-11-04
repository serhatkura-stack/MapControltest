package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dijital ikiz ışıkları (§11): yalnızca doğrulanmış sinyaller, demo listesi ve kapak rengi. */
class CarLightsTest {

    @Test
    fun measuredSignalsDecode() {
        val l = CarLightSignals.decode(head = 1, left = 1, right = 0, gear = 2, brake = 1, hazard = 1)
        assertTrue(l.head)
        assertTrue(l.left)
        assertFalse(l.right)
        assertTrue(l.reverse)
        // Fren ve dörtlü anahtarı araçta doğrulanmadı: okunsa bile yanmaz.
        assertFalse(l.brake)
        assertFalse(l.hazard)
        assertEquals(CarLights.OFF, CarLightSignals.decode(0, 0, 0, 1, 0, 0))
        assertEquals(CarLights.OFF, CarLightSignals.decode(null, null, null, null, null, null))
    }

    @Test
    fun unverifiedSignalsAreNotEvenRead() {
        val asked = mutableListOf<Int>()
        CarLightSignals.read { asked += it; 0 }
        assertTrue(com.desaysv.ivi.extra.project.carinfo.ReadOnlyID.ID_BRAKE_PEDAL !in asked)
        assertTrue(com.desaysv.ivi.extra.project.carinfo.ReadOnlyID.ID_HAZARD_LIGHT !in asked)
        assertEquals(4, asked.size)
    }

    @Test
    fun demoListParses() {
        val l = CarLights.parse(" head, Brake ,left,hazard,nope")
        assertEquals(CarLights(head = true, brake = true, left = true, hazard = true), l)
        assertTrue(l.blinkLeft && l.blinkRight)
        assertEquals(CarLights.OFF, CarLights.parse(""))
        assertEquals(CarLights.OFF, CarLights.parse(null))
    }

    @Test
    fun coverColorPicksDominantHueAndIgnoresGray() {
        val orange = 0xFFFF7A3D.toInt()
        val purple = 0xFF3B1E6E.toInt()
        val px = IntArray(576) { if (it < 400) orange else purple }
        val c = AmbientColor.dominant(px)!!
        // Turuncu tonda (kırmızı > yeşil > mavi), parlaklık 0,9.
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        assertTrue("$r $g $b", r > g && g > b)
        assertEquals(230f, r.toFloat(), 2f)
        assertNull(AmbientColor.dominant(IntArray(576) { 0xFF808080.toInt() }))
        assertNull(AmbientColor.dominant(IntArray(576) { 0xFF000000.toInt() }))
    }
}
