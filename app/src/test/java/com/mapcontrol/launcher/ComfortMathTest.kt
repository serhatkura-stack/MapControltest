package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortMathTest {

    @Test fun sunshadeDecoding() {
        assertEquals(SunshadeState.CLOSED, ComfortMath.decodeSunshade(1))
        assertEquals(SunshadeState.OPENING, ComfortMath.decodeSunshade(2))
        assertEquals(SunshadeState.CLOSING, ComfortMath.decodeSunshade(4))
        assertEquals(SunshadeState.OPEN, ComfortMath.decodeSunshade(5))
        assertEquals(SunshadeState.PARTIAL, ComfortMath.decodeSunshade(6))
        assertEquals(SunshadeState.UNKNOWN, ComfortMath.decodeSunshade(0))
        assertEquals(SunshadeState.UNKNOWN, ComfortMath.decodeSunshade(3))
        assertEquals(SunshadeState.UNKNOWN, ComfortMath.decodeSunshade(null))
        assertEquals(0f, SunshadeState.CLOSED.openFraction)
        assertEquals(1f, SunshadeState.OPEN.openFraction)
        assertEquals(0.5f, SunshadeState.PARTIAL.openFraction)
        assertNull(SunshadeState.UNKNOWN.openFraction)
        assertTrue(SunshadeState.OPENING.moving && SunshadeState.CLOSING.moving && !SunshadeState.OPEN.moving)
    }

    @Test fun defrostAndSteerDecoding() {
        assertEquals(true, ComfortMath.decodeFront(1, 0))
        assertEquals(true, ComfortMath.decodeFront(0, 1))
        assertEquals(false, ComfortMath.decodeFront(0, 0))
        assertEquals(true, ComfortMath.decodeFront(null, 1))
        assertNull(ComfortMath.decodeFront(null, null))
        assertEquals(true, ComfortMath.decodeRear(0, 1))
        assertEquals(false, ComfortMath.decodeRear(0, null))
        assertNull(ComfortMath.decodeRear(null, null))
        assertEquals(true, ComfortMath.decodeSteer(1))
        assertEquals(false, ComfortMath.decodeSteer(0))
        assertNull(ComfortMath.decodeSteer(null))
    }

    @Test fun gateNeverWritesWhenUnverified() {
        // Bayraklar bu derlemede kapalı: gerçek kapı da kapalı olmalı.
        assertFalse(ComfortWriter.COMFORT_WRITE_VERIFIED)
        assertFalse(ComfortWriter.canWrite(false, true, false))
        assertFalse(ComfortWriter.canWrite(true, false, false))
        assertFalse(ComfortWriter.canWrite(true, true, true))
        assertTrue(ComfortWriter.canWrite(true, true, false))
        var sent = 0
        val cmds = ComfortMath.defrostCommands(front = true, on = true)
        assertFalse(ComfortWriter.writeAll(ComfortWriter.canWrite(false, true, false), cmds) { sent++ })
        assertEquals(0, sent)
        assertTrue(ComfortWriter.writeAll(true, cmds) { sent++ })
        assertEquals(2, sent)
    }

    @Test fun commandsCarryFrostNavValues() {
        val on = ComfortMath.defrostCommands(front = true, on = true)
        assertEquals(listOf(65, 24), on.map { it.id })
        assertTrue(on.all { it.value == 1 })
        val rearOff = ComfortMath.defrostCommands(front = false, on = false)
        assertEquals(listOf(80, 159), rearOff.map { it.id })
        assertTrue(rearOff.all { it.value == 0 })
        assertEquals(listOf(160), ComfortMath.steerCommands(true).map { it.id })
        assertEquals(1, ComfortMath.steerCommands(true).single().value)
        assertEquals(10, ComfortMath.sunshadeCommand(2).id)
    }

    @Test fun stopDelayIsFractionOfTravel() {
        assertEquals(4000L, ComfortMath.stopDelayMs(0f, 0.5f, 8000L))
        assertEquals(2000L, ComfortMath.stopDelayMs(1f, 0.75f, 8000L))
        assertEquals(0L, ComfortMath.stopDelayMs(0.3f, 0.3f, 8000L))
        assertEquals(8000L, ComfortMath.stopDelayMs(-1f, 2f, 8000L))
        assertEquals(8000L, ComfortWriter.SUNSHADE_TRAVEL_MS)
    }

    @Test fun planWithoutStopValueOnlyFullOpenClose() {
        assertNull(ComfortWriter.SUNSHADE_STOP_VALUE)
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.planSunshade(0f, 1f))
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.planSunshade(1f, 0f))
        // Ara hedef durdurma değeri olmadan en yakın uca yuvarlanır.
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.planSunshade(0f, 0.6f, stopValue = null))
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.planSunshade(1f, 0.4f, stopValue = null))
    }

    @Test fun planWithStopValueStopsAfterFraction() {
        assertEquals(listOf(SunshadeStep(2, 0), SunshadeStep(9, 4000)), ComfortMath.planSunshade(0f, 0.5f, 9, 8000L))
        assertEquals(listOf(SunshadeStep(1, 0), SunshadeStep(9, 2000)), ComfortMath.planSunshade(1f, 0.75f, 9, 8000L))
        // Başlangıç bilinmiyorsa ara konum planlanamaz: uca yuvarlanır.
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.planSunshade(null, 0.7f, 9, 8000L))
    }

    @Test fun dragMapsToCoverageAndTarget() {
        // Aşağı = kapat: perde parmağı izler.
        assertEquals(0.5f, ComfortMath.dragCoverage(0f, 100f, 200f), 1e-6f)
        assertEquals(1f, ComfortMath.dragCoverage(0.5f, 500f, 200f), 0f)
        assertEquals(0f, ComfortMath.dragCoverage(0.5f, -500f, 200f), 0f)
        assertEquals(0.3f, ComfortMath.dragCoverage(0.3f, 10f, 0f), 0f)
        // Açıktan (örtü 0) çeyrek yol aşağı sürüklenince kapanır, azıysa açık kalır.
        assertEquals(0f, ComfortMath.releaseTarget(1f, 0.3f, partialAllowed = false), 0f)
        assertEquals(1f, ComfortMath.releaseTarget(1f, 0.2f, partialAllowed = false), 0f)
        // Kapalıdan yukarı sürükleme açar.
        assertEquals(1f, ComfortMath.releaseTarget(0f, 0.7f, partialAllowed = false), 0f)
        assertEquals(0f, ComfortMath.releaseTarget(0f, 0.9f, partialAllowed = false), 0f)
        // Ara konum izinliyse parmağın bıraktığı yer.
        assertEquals(0.4f, ComfortMath.releaseTarget(1f, 0.6f, partialAllowed = true), 1e-6f)
    }

    @Test fun tapToggles() {
        assertEquals(1f, ComfortMath.toggleTarget(SunshadeState.CLOSED))
        assertEquals(1f, ComfortMath.toggleTarget(SunshadeState.CLOSING))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.OPEN))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.OPENING))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.PARTIAL))
        assertNull(ComfortMath.toggleTarget(SunshadeState.UNKNOWN))
    }
}
