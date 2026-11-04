package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sahne aracının yerleşimi: üst engellerle çakışmaz, alt payı aşmaz, sığdığı kadar (%20'ye kadar) büyür. */
class StageFitTest {

    private val aspect = CarAnimSpec.BASE_H / CarAnimSpec.BASE_W
    private val gap = 10f

    /** 960 genişlikte sahne: menzil rakam satırı, yakıt satırı ve kilit hapı (emülatörde ölçülen ölçüler). */
    private fun obstacles() = listOf(
        StageFit.Box(30f, -8f, 290f, 81f),
        StageFit.Box(30f, 93f, 240f, 115f),
        StageFit.Box(790f, 14f, 930f, 86f),
    )

    /** Araç sol bilgi sütununun (30 + 220) sağında. */
    private fun fit(h: Float) = StageFit.car(250f, 930f, 8f, h - 32f, 612f, 510f, aspect, gap, obstacles())

    private fun overlaps(a: StageFit.Box, b: StageFit.Box) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Test
    fun neverOverlapsObstaclesOrBottomReserve() {
        listOf(396f, 423f, 508f, 600f).forEach { h ->
            val c = fit(h)
            obstacles().forEach { o -> assertTrue("$h $c $o", !overlaps(c, o)) }
            assertTrue(c.bottom <= h - 32f + 0.01f)
            assertTrue(c.left >= 250f - 0.5f && c.right <= 930f + 0.5f)
            assertEquals(aspect, c.height / c.width, 0.001f)
        }
    }

    @Test
    fun ourBarStageGrowsAtLeastTwelvePercent() {
        // Bizim alt çubuk (en alçak sahne, 396 px): araç menzilin sağına kayarak en az %12 büyür.
        val c = fit(396f)
        assertTrue("${c.width}", c.width >= 510f * 1.12f)
    }

    @Test
    fun tallerStagesReachTwentyPercent() {
        assertEquals(612f, fit(423f).width, 0.01f) // OEM çubuğu
        val tall = fit(508f) // çubuksuz
        assertEquals(612f, tall.width, 0.01f)
        // Bol yerde araç kendi alanının yatay ortasında durur (engellerin altına iner).
        assertEquals(590f, (tall.left + tall.right) / 2f, 1f)
    }

    @Test
    fun noObstaclesMeansCenteredMaxCar() {
        val c = StageFit.car(0f, 960f, 0f, 400f, 612f, 510f, aspect, gap, emptyList())
        assertEquals(612f, c.width, 0.01f)
        assertEquals(480f, (c.left + c.right) / 2f, 0.01f)
        assertEquals(200f, (c.top + c.bottom) / 2f, 0.01f) // dikeyde boşluğun ortasında
    }

    @Test
    fun impossibleSpaceFallsBackToMinimumSize() {
        val c = StageFit.car(0f, 960f, 0f, 100f, 612f, 510f, aspect, gap, emptyList())
        assertEquals(510f, c.width, 0.01f)
    }
}
