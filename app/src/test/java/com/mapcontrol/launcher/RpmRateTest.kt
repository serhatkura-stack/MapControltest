package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpmRateTest {

    @Test fun firstSampleStartsWindowWithoutCountingAChange() {
        val s = RpmRateStats()
        assertEquals(-1L, s.onSample(1_000, 190, RpmSource.POLL))
        assertNull(s.onSample(1_100, 190, RpmSource.POLL))
        val sum = s.drain(6_000, subscribed = false)!!
        assertEquals(0, sum.changes)
        assertEquals(-1L, sum.minGapMs)
        assertEquals(5_000L, sum.windowMs)
    }

    @Test fun gapsAndSourcesAreCounted() {
        val s = RpmRateStats()
        s.onSample(0, 190, RpmSource.PUSH)
        assertEquals(40L, s.onSample(40, 192, RpmSource.PUSH))
        assertNull(s.onSample(60, 192, RpmSource.PUSH))
        assertEquals(160L, s.onSample(200, 195, RpmSource.POLL))
        val sum = s.drain(1_000, subscribed = true)!!
        assertEquals(2, sum.changes)
        assertEquals(40L, sum.minGapMs)
        assertEquals(160L, sum.maxGapMs)
        assertEquals(1, sum.pushChanges)
        assertEquals(1, sum.pollChanges)
        assertEquals(3, sum.pushEvents)
        assertEquals(2.0, sum.changesPerSec, 1e-9)
        assertTrue(sum.subscribed)
    }

    @Test fun drainKeepsLastValueAndResetsWindow() {
        val s = RpmRateStats()
        s.onSample(0, 190, RpmSource.POLL)
        s.onSample(100, 191, RpmSource.POLL)
        s.drain(5_000, subscribed = false)
        // Aynı değer: değişim yok; yeni değer: aralık son değişimden (100) ölçülür.
        assertNull(s.onSample(5_100, 191, RpmSource.POLL))
        assertEquals(5_100L, s.onSample(5_200, 193, RpmSource.POLL))
        val sum = s.drain(10_000, subscribed = false)!!
        assertEquals(1, sum.changes)
        assertEquals(5_000L, sum.windowMs)
    }

    @Test fun drainWithoutSamplesIsNull() {
        assertNull(RpmRateStats().drain(5_000, subscribed = true))
    }

    @Test fun linesAreReadable() {
        assertEquals("raw=190 rpm=760 gap=- src=push", RpmRateLog.changeLine(190, 760, -1, RpmSource.PUSH))
        assertEquals("raw=192 rpm=768 gap=40ms src=poll", RpmRateLog.changeLine(192, 768, 40, RpmSource.POLL))
        val line = RpmRateLog.summaryLine(RpmRateSummary(5_000, 25, 40, 400, 20, 5, 60, true))
        assertEquals("özet 5000ms: değişim=25 (5.00/sn) min=40ms max=400ms push=20 poll=5 pushOlay=60 abonelik=var", line)
    }

    @Test fun decodeMatchesCarSignals() {
        assertNull(SportFastReader.decode(null))
        assertNull(SportFastReader.decode(intArrayOf()))
        assertEquals(190, SportFastReader.decode(intArrayOf(190)))
        assertEquals(0x0102, SportFastReader.decode(intArrayOf(1, 2)))
        assertEquals(0x01FF, SportFastReader.decode(intArrayOf(1, -1)))
    }
}
