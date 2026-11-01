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
        assertEquals(SunshadeState.PARTIAL, ComfortMath.decodeSunshade(3))
        assertEquals(SunshadeState.OPEN, ComfortMath.decodeSunshade(5))
        assertEquals(SunshadeState.OPEN, ComfortMath.decodeSunshade(6))
        assertEquals(SunshadeState.UNKNOWN, ComfortMath.decodeSunshade(0))
        assertEquals(SunshadeState.UNKNOWN, ComfortMath.decodeSunshade(null))
        assertEquals(0f, SunshadeState.CLOSED.openFraction)
        assertEquals(1f, SunshadeState.OPEN.openFraction)
        assertEquals(0.5f, SunshadeState.PARTIAL.openFraction)
        assertNull(SunshadeState.UNKNOWN.openFraction)
        assertTrue(SunshadeState.OPENING.moving && SunshadeState.CLOSING.moving && !SunshadeState.OPEN.moving)
    }

    @Test fun defrostDecoding() {
        assertEquals(true, ComfortMath.decodeFront(1))
        assertEquals(false, ComfortMath.decodeFront(0))
        assertNull(ComfortMath.decodeFront(null))
        assertEquals(true, ComfortMath.decodeRear(1))
        assertEquals(false, ComfortMath.decodeRear(0))
        assertNull(ComfortMath.decodeRear(null))
    }

    @Test fun gateNeverWritesWhenUnverified() {
        // Ana anahtar açık, ama yalnızca perde özelliği doğrulandı (buğu kapatma kapalı).
        assertTrue(ComfortWriter.COMFORT_WRITE_VERIFIED)
        assertTrue(ComfortWriter.SUNSHADE_WRITE_VERIFIED)
        assertFalse(ComfortWriter.DEFROST_WRITE_VERIFIED)
        assertFalse(ComfortWriter.canWrite(false, true, false))
        assertFalse(ComfortWriter.canWrite(true, false, false))
        assertFalse(ComfortWriter.canWrite(true, true, true))
        assertTrue(ComfortWriter.canWrite(true, true, false))
        var sent = 0
        val cmds = ComfortMath.defrostCommands(front = true, on = true)
        assertFalse(ComfortWriter.writeAll(ComfortWriter.canWrite(false, true, false), cmds) { sent++ })
        assertEquals(0, sent)
        assertTrue(ComfortWriter.writeAll(true, cmds) { sent++ })
        assertEquals(1, sent)
    }

    @Test fun commandsCarryFrostNavValues() {
        val on = ComfortMath.defrostCommands(front = true, on = true)
        assertEquals(listOf(24), on.map { it.id })
        assertTrue(on.all { it.value == 1 })
        val rearOff = ComfortMath.defrostCommands(front = false, on = false)
        assertEquals(listOf(80), rearOff.map { it.id })
        assertTrue(rearOff.all { it.value == 2 })
        val shade = ComfortMath.sunshadeCommand(1)
        assertEquals(327696, shade.module)
        assertEquals(124, shade.id)
        assertEquals(1, ComfortWriter.SUNSHADE_OPEN_VALUE)
        assertEquals(2, ComfortWriter.SUNSHADE_CLOSE_VALUE)
    }

    @Test fun stopDelayIsFractionOfTravel() {
        assertEquals(4000L, ComfortMath.stopDelayMs(0f, 0.5f, 8000L))
        assertEquals(2000L, ComfortMath.stopDelayMs(1f, 0.75f, 8000L))
        assertEquals(0L, ComfortMath.stopDelayMs(0.3f, 0.3f, 8000L))
        assertEquals(8000L, ComfortMath.stopDelayMs(-1f, 2f, 8000L))
        assertEquals(6800L, ComfortWriter.SUNSHADE_OPEN_MS)
        assertEquals(8100L, ComfortWriter.SUNSHADE_CLOSE_MS)
    }

    @Test fun planWithoutStopValueOnlyFullOpenClose() {
        assertNull(ComfortWriter.SUNSHADE_STOP_VALUE)
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.planSunshade(0f, 1f))
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.planSunshade(1f, 0f))
        // Ara hedef durdurma değeri olmadan en yakın uca yuvarlanır.
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.planSunshade(0f, 0.6f, stopValue = null))
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.planSunshade(1f, 0.4f, stopValue = null))
    }

    @Test fun planWithStopValueStopsAfterFraction() {
        assertEquals(listOf(SunshadeStep(1, 0), SunshadeStep(9, 4000)), ComfortMath.planSunshade(0f, 0.5f, 9, 8000L))
        assertEquals(listOf(SunshadeStep(2, 0), SunshadeStep(9, 2000)), ComfortMath.planSunshade(1f, 0.75f, 9, 8000L))
        // Başlangıç bilinmiyorsa ara konum planlanamaz: uca yuvarlanır.
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.planSunshade(null, 0.7f, 9, 8000L))
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

    @Test fun tapStepsLikeTheCar() {
        // Dokunma aç/kapat; hareket halindeyse ters yön; ara konum yok.
        assertEquals(1f, ComfortMath.tapStep(SunshadeState.CLOSED, null))
        assertEquals(1f, ComfortMath.tapStep(SunshadeState.PARTIAL, null))
        assertEquals(0f, ComfortMath.tapStep(SunshadeState.PARTIAL, true))
        assertEquals(1f, ComfortMath.tapStep(SunshadeState.PARTIAL, false))
        assertEquals(0f, ComfortMath.tapStep(SunshadeState.OPEN, null))
        assertEquals(0f, ComfortMath.tapStep(SunshadeState.OPENING, null))
        assertEquals(1f, ComfortMath.tapStep(SunshadeState.CLOSING, null))
        assertNull(ComfortMath.tapStep(SunshadeState.UNKNOWN, null))
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.stepPlan(0f, 1f))
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.stepPlan(1f, 0f))
        // Hareket halinde mevcut açıklık bilinmez: kapat hedefi kapat (2), aç hedefi aç (1).
        assertEquals(listOf(SunshadeStep(2, 0)), ComfortMath.stepPlan(null, 0f))
        assertEquals(listOf(SunshadeStep(1, 0)), ComfortMath.stepPlan(null, 1f))
    }

    @Test fun tapToggles() {
        assertEquals(1f, ComfortMath.toggleTarget(SunshadeState.CLOSED))
        assertEquals(1f, ComfortMath.toggleTarget(SunshadeState.CLOSING))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.OPEN))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.OPENING))
        assertEquals(0f, ComfortMath.toggleTarget(SunshadeState.PARTIAL))
        assertNull(ComfortMath.toggleTarget(SunshadeState.UNKNOWN))
    }

    @Test fun trunkDecoding() {
        fun d(pos: Int?, latch: Int? = null, motion: Int? = 2) = TrunkMath.decode(TrunkReading(pos, latch, motion))
        assertEquals(TrunkState.CLOSED, d(0, 0))
        assertEquals(TrunkState.CLOSED, d(0, null))
        assertEquals(TrunkState.OPEN, d(0, 1)) // mandal açık, konum henüz 0
        assertEquals(TrunkState.PARTIAL, d(40, 1))
        assertEquals(TrunkState.PARTIAL, d(78, 1))
        assertEquals(TrunkState.OPEN, d(79, 1))
        assertEquals(TrunkState.OPEN, d(81, 1))
        assertEquals(TrunkState.OPENING, d(30, 1, 3))
        assertEquals(TrunkState.CLOSING, d(30, 1, 4))
        assertEquals(TrunkState.OPENING, d(null, null, 3))
        // Konum okunamazsa mandal.
        assertEquals(TrunkState.CLOSED, d(null, 0, null))
        assertEquals(TrunkState.OPEN, d(null, 1, null))
        assertEquals(TrunkState.UNKNOWN, d(null, null, null))
        assertEquals(TrunkState.UNKNOWN, TrunkMath.decode(null))
        assertTrue(TrunkState.OPENING.moving && TrunkState.CLOSING.moving && !TrunkState.OPEN.moving && !TrunkState.PARTIAL.moving)
    }

    @Test fun trunkPercent() {
        assertEquals(0, TrunkMath.percent(0))
        assertEquals(100, TrunkMath.percent(81))
        assertEquals(100, TrunkMath.percent(95))
        assertEquals(0, TrunkMath.percent(-3))
        assertEquals(41, TrunkMath.percent(33)) // 33/81 = 40,7
        assertEquals(49, TrunkMath.percent(40)) // 40/81 = 49,4
        assertNull(TrunkMath.percent(null))
        assertEquals(0f, TrunkMath.openFraction(TrunkReading(0, 0, 2)))
        assertEquals(1f, TrunkMath.openFraction(TrunkReading(81, 1, 2)))
        assertEquals(1f, TrunkMath.openFraction(TrunkReading(null, 1, 2)))
        assertEquals(0f, TrunkMath.openFraction(TrunkReading(null, 0, null)))
        assertNull(TrunkMath.openFraction(TrunkReading(null, null, null)))
        assertNull(TrunkMath.openFraction(null))
    }

    @Test fun trunkTapAction() {
        assertEquals(TrunkAction.OPEN, TrunkMath.tapAction(TrunkState.CLOSED, false))
        assertEquals(TrunkAction.CLOSE_CONFIRM, TrunkMath.tapAction(TrunkState.OPEN, false))
        assertEquals(TrunkAction.CLOSE_CONFIRM, TrunkMath.tapAction(TrunkState.PARTIAL, true))
        assertEquals(TrunkAction.STOP_UNKNOWN, TrunkMath.tapAction(TrunkState.OPENING, false))
        assertEquals(TrunkAction.STOP_UNKNOWN, TrunkMath.tapAction(TrunkState.CLOSING, false))
        assertEquals(TrunkAction.STOP, TrunkMath.tapAction(TrunkState.OPENING, true))
        assertEquals(TrunkAction.STOP, TrunkMath.tapAction(TrunkState.CLOSING, true))
        assertEquals(TrunkAction.NONE, TrunkMath.tapAction(TrunkState.UNKNOWN, true))
    }

    @Test fun trunkWriteIsOffUntilVerifiedAndParked() {
        // Doğrulanmadı: kutucuk salt-okur, araca hiçbir şey gitmez.
        assertFalse(ComfortWriter.TRUNK_WRITE_VERIFIED)
        assertFalse(ComfortWriter.trunkEnabled)
        assertFalse(ComfortWriter.trunkStopKnown)
        assertEquals(TrunkWriteResult.DISABLED, ComfortWriter.stopTrunk())
        val sent = ArrayList<ComfortCmd>()
        val cmd = listOf(TrunkMath.command(true, true))
        assertEquals(TrunkWriteResult.DISABLED, ComfortWriter.writeParked(false, { error("park okunmamalı") }, cmd) { sent += it })
        assertEquals(TrunkWriteResult.NOT_PARKED, ComfortWriter.writeParked(true, { false }, cmd) { sent += it })
        assertTrue(sent.isEmpty())
        assertEquals(TrunkWriteResult.SENT, ComfortWriter.writeParked(true, { true }, cmd) { sent += it })
        assertEquals(cmd, sent)
    }

    @Test fun trunkCommandValues() {
        assertEquals(ComfortCmd(327696, 121, 1), TrunkMath.command(true, true))
        assertEquals(ComfortCmd(327696, 121, 2), TrunkMath.command(false, true))
        assertEquals(ComfortCmd(327681, 92, 3), TrunkMath.command(true, false))
        assertEquals(ComfortCmd(327681, 92, 4), TrunkMath.command(false, false))
    }

    @Test fun trunkWidgetRegistered() {
        val t = WidgetType.fromKey("trunk")
        assertEquals(WidgetType.TRUNK, t)
        assertEquals(listOf(Span(2, 2), Span(2, 4)), WidgetType.TRUNK.sizes)
        assertFalse(WidgetType.TRUNK.parkOnly)
    }
}
