package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sahne köşe notları: normalde gizli, yalnızca bakım yakın / uyarı / kötü havada görünür. */
class StageCornersTest {

    @Test
    fun calmCarHidesHealthCorner() {
        assertFalse(StageCorners.healthShown(14.1, 89, 3978, 155, tireAlert = false))
        assertFalse(StageCorners.healthShown(null, null, null, null, tireAlert = false))
    }

    @Test
    fun serviceNearThresholdIs1000Km() {
        assertEquals(1000, StageCorners.SERVICE_NEAR_KM)
        assertTrue(StageCorners.serviceNear(1000, 200))
        assertTrue(StageCorners.serviceNear(800, null))
        assertFalse(StageCorners.serviceNear(1001, 200))
        // Gün yaklaşması tek başına köşeyi açmaz (karar km); ama gün 0'a indiyse bakım geçmiştir.
        assertFalse(StageCorners.serviceNear(5000, 20))
        assertTrue(StageCorners.serviceNear(5000, 0))
        assertTrue(StageCorners.serviceNear(0, null))
        assertTrue(StageCorners.serviceNear(-50, null))
        assertFalse(StageCorners.serviceNear(null, null))
    }

    @Test
    fun warningsOpenHealthCorner() {
        assertTrue(StageCorners.healthShown(11.4, 89, 3978, 155, tireAlert = false)) // akü düşük
        assertTrue(StageCorners.healthShown(15.6, 89, 3978, 155, tireAlert = false)) // akü yüksek
        assertTrue(StageCorners.healthShown(14.1, 118, 3978, 155, tireAlert = false)) // motor sıcak
        assertTrue(StageCorners.healthShown(14.1, 89, 3978, 155, tireAlert = true)) // lastik
        assertTrue(StageCorners.healthShown(14.1, 89, 640, 155, tireAlert = false)) // bakım yakın
        assertFalse(StageCorners.healthWarning(12.0, 110)) // sınır değerler normal
    }

    @Test
    fun healthNotesNameTheReasonNotLowFuel() {
        assertEquals(listOf(StageCorners.HealthNote.SERVICE_NEAR), StageCorners.healthNotes(14.1, 89, 800, 40, tireAlert = false))
        assertEquals(listOf(StageCorners.HealthNote.TIRE, StageCorners.HealthNote.SERVICE_NEAR),
            StageCorners.healthNotes(14.1, 89, 800, 40, tireAlert = true))
        assertEquals(listOf(StageCorners.HealthNote.ENGINE_HOT, StageCorners.HealthNote.BATTERY_LOW, StageCorners.HealthNote.SERVICE_OVERDUE),
            StageCorners.healthNotes(11.4, 118, 0, 0, tireAlert = false))
        assertTrue(StageCorners.healthNotes(14.1, 89, 3978, 20, tireAlert = false).isEmpty()) // yalnız gün yakın: not yok
    }

    @Test
    fun airCornerOnlyWhenBad() {
        assertEquals(76, StageCorners.AIR_BAD_PM25)
        assertFalse(StageCorners.airShown(null))
        assertFalse(StageCorners.airShown(4))
        assertFalse(StageCorners.airShown(75)) // "Orta": not çıkmaz
        assertTrue(StageCorners.airShown(76))
        assertTrue(StageCorners.airShown(120))
    }
}
