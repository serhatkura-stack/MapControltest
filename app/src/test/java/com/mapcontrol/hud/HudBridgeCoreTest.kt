package com.mapcontrol.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudBridgeCoreTest {

    // --- mesafe çözümleyici ---

    @Test fun parsesMetersRussianAndTurkishAndLatin() {
        assertEquals(850L, HudDistanceParser.parseMeters("850", "м"))
        assertEquals(850L, HudDistanceParser.parseMeters("850", "m"))
        assertEquals(850L, HudDistanceParser.parseText("850 м"))
        assertEquals(850L, HudDistanceParser.parseText("850 m"))
        assertEquals(850L, HudDistanceParser.parseText("850 m"))
    }

    @Test fun parsesKilometersWithCommaOrDot() {
        assertEquals(1200L, HudDistanceParser.parseText("1,2 km"))
        assertEquals(1200L, HudDistanceParser.parseText("1.2 км"))
        assertEquals(1200L, HudDistanceParser.parseMeters("1,2", "км"))
        assertEquals(12000L, HudDistanceParser.parseText("12 km"))
        assertEquals(2900L, HudDistanceParser.parseText("2,9 km"))
    }

    @Test fun unknownOrMissingUnitIsNotGuessed() {
        assertNull(HudDistanceParser.parseText("850"))
        assertNull(HudDistanceParser.parseText("850 ft"))
        assertNull(HudDistanceParser.parseText("5 мин"))
        assertNull(HudDistanceParser.parseText("abc"))
        assertNull(HudDistanceParser.parseText(null))
        assertNull(HudDistanceParser.parseMeters("", "m"))
    }

    @Test fun mapperBuildsStateOrNull() {
        val s = HudStateMapper.map("850", "м", " Atatürk Cd. ", "2,9 km")!!
        assertEquals(HudState("Atatürk Cd.", 850, 2900L), s)
        assertNull(HudStateMapper.map(null, null, "X", null))
        assertEquals(HudState(null, null, 1200L), HudStateMapper.map(null, null, " ", "1.2 км"))
    }

    // --- beyaz liste ---

    @Test fun whitelistIsExactlyFiveSubcommands() {
        assertEquals(setOf(3, 52, 48, 50, 53), HudBridgeWhitelist.ALLOWED)
        for (sub in (0..255) - setOf(3, 52, 48, 50, 53)) {
            try {
                HudBridgeWhitelist.check(intArrayOf(sub, 1, 0))
                throw AssertionError("alt komut $sub geçmemeliydi")
            } catch (e: IllegalArgumentException) { /* beklenen */ }
        }
        // 49/51 HudPayload'da izinli ama köprüde YASAK.
        for (v in listOf(HudPayload.maneuver(9, 100), HudPayload.totalDistance(1000))) {
            try {
                HudBridgeWhitelist.check(v)
                throw AssertionError("köprü ${v[0]} göndermemeli")
            } catch (e: IllegalArgumentException) { /* beklenen */ }
        }
        assertArrayEquals(intArrayOf(3, 1, 1), HudBridgeWhitelist.check(HudPayload.clear()))
    }

    @Test fun whitelistStillValidatesLength() {
        try {
            HudBridgeWhitelist.check(intArrayOf(50, 3, 0, 0, 0))
            throw AssertionError("kısa 50 geçmemeliydi")
        } catch (e: IllegalArgumentException) { /* beklenen */ }
        try {
            HudBridgeWhitelist.check(IntArray(0))
            throw AssertionError("boş geçmemeliydi")
        } catch (e: IllegalArgumentException) { /* beklenen */ }
    }

    // --- hız sınırlayıcı ---

    @Test fun limiterAllowsFivePerSecondSliding() {
        val l = HudRateLimiter(5, 1000)
        repeat(5) { assertTrue(l.tryAcquire(it * 10L)) }
        assertFalse(l.tryAcquire(500))
        assertFalse(l.tryAcquire(999))
        assertTrue(l.tryAcquire(1000))      // ilk damga (t=0) düştü
        assertTrue(l.tryAcquire(1010))
        assertFalse(l.tryAcquire(1011))
    }

    // --- fark mantığı ---

    private class Rig(var icon: Int? = null) {
        val sent = ArrayList<IntArray>()
        val times = ArrayList<Long>()
        var now = 0L
        var ok = true
        val engine = HudBridgeEngine(
            send = { if (ok) { sent.add(it); times.add(now); true } else false },
            iconSource = { icon },
            limiter = HudRateLimiter(1000, 1000), // sınırlayıcıyı ayrı sınıyoruz
        )
        fun subs() = sent.map { it[0] }

        /** Sahte saat: [from]'dan [to]'ya (dahil) [step] ms adımlarla tik atar; durum verilirse her adımda onState. */
        fun run(to: Long, state: HudState? = null, step: Long = 200L) {
            while (now <= to) {
                if (state != null) engine.onState(state, now) else engine.tick(now)
                now += step
            }
        }
    }

    @Test fun startSendsClearThenStartThenRoadManeuverTotalPaced() {
        val r = Rig()
        r.run(1700, HudState("Ana Cd.", 850, 2900L))
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
        assertArrayEquals(HudPayload.clear(), r.sent[0])
        assertArrayEquals(HudPayload.navStarted(), r.sent[1])
        assertArrayEquals(HudPayload.roadName("Ana Cd."), r.sent[2])
        // ikon bilinmiyor -> 0, tahmin yok
        assertArrayEquals(HudPayload.maneuverAlt(0, 850), r.sent[3])
        assertArrayEquals(HudPayload.totalDistanceAlt(2900L), r.sent[4])
        for (i in 1 until r.times.size) assertTrue(r.times[i] - r.times[i - 1] >= 400)
        assertTrue(r.times.last() <= 1600)
    }

    @Test fun neverTwoSendsCloserThan400ms() {
        val r = Rig()
        r.engine.onState(HudState("A", 850, 2900L), 0)
        assertEquals(listOf(3), r.subs())
        r.engine.onState(HudState("A", 850, 2900L), 399)
        assertEquals(listOf(3), r.subs())
        r.now = 400
        r.engine.onState(HudState("A", 850, 2900L), 400)
        assertEquals(listOf(3, 52), r.subs())
    }

    @Test fun roadFallsBackToInstructionThenYandex() {
        val r = Rig()
        r.run(1700, HudState(null, 50, null, instruction = "Soldan devam edin"))
        assertArrayEquals(HudPayload.roadName("Soldan devam edin"), r.sent[2])
        val r2 = Rig()
        r2.run(1700, HudState(null, 50, null))
        assertArrayEquals(HudPayload.roadName("Yandex"), r2.sent[2])
    }

    @Test fun totalFallsBackToManeuverDistanceWhenUnknown() {
        val r = Rig()
        r.run(1700, HudState("A", 50, null))
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
        assertArrayEquals(HudPayload.totalDistanceAlt(50L), r.sent[4])
    }

    @Test fun longRoadNameIsTruncatedByPayload() {
        val r = Rig()
        r.run(1700, HudState("Ç".repeat(60), 50, 100L))
        assertTrue(r.sent[2][1] <= HudPayload.ROAD_NAME_MAX_BYTES)
    }

    @Test fun unchangedStateRefreshesManeuverEvery3sAndRoadTotalEvery10s() {
        val r = Rig()
        val s = HudState("Ana Cd.", 850, 2900L)
        r.run(1700, s)
        r.sent.clear(); r.times.clear()
        r.run(2900, s)
        assertTrue(r.sent.isEmpty())                       // 3 sn dolmadı
        r.run(4400, s)
        assertEquals(listOf(50), r.subs())                 // ~3 sn: manevra tazelendi
        r.sent.clear(); r.times.clear()
        r.run(12_000, s)
        assertTrue(r.subs().count { it == 50 } >= 2)
        assertEquals(1, r.subs().count { it == 48 })
        assertEquals(1, r.subs().count { it == 53 })
        assertTrue(r.sent.all { it[0] in HudBridgeWhitelist.ALLOWED })
    }

    @Test fun maneuverThresholdTenMeters() {
        val r = Rig()
        r.run(1700, HudState("A", 850, 2000L))
        r.sent.clear()
        r.now = 2000
        r.engine.onState(HudState("A", 845, 2000L), r.now)   // 5 m: yok
        assertTrue(r.sent.isEmpty())
        r.now = 2200
        r.engine.onState(HudState("A", 840, 2000L), r.now)   // 10 m: gider
        assertEquals(listOf(50), r.subs())
    }

    @Test fun iconChangeTriggersManeuverSend() {
        val r = Rig(icon = null)
        r.run(1700, HudState("A", 500, 1000L))
        r.sent.clear()
        r.icon = 3
        r.now = 2000
        r.engine.onState(HudState("A", 500, 1000L), r.now)
        assertEquals(1, r.sent.size)
        assertArrayEquals(HudPayload.maneuverAlt(3, 500), r.sent[0])
    }

    @Test fun totalThresholdHundredMeters() {
        val r = Rig()
        r.run(1700, HudState("A", 500, 5000L))
        r.sent.clear()
        r.now = 2000
        r.engine.onState(HudState("A", 500, 4950L), r.now)
        assertTrue(r.sent.isEmpty())
        r.now = 2200
        r.engine.onState(HudState("A", 500, 4900L), r.now)
        assertEquals(listOf(53), r.subs())
    }

    @Test fun roadSentOnlyWhenChanged() {
        val r = Rig()
        r.run(1700, HudState("A", 500, 1000L))
        r.sent.clear()
        r.now = 2000
        r.engine.onState(HudState("A", 500, 1000L), r.now)
        assertTrue(r.sent.isEmpty())
        r.engine.onState(HudState("B", 500, 1000L), r.now)
        assertEquals(listOf(48), r.subs())
    }

    @Test fun guidanceEndClearsOnceAndRestartResends() {
        val r = Rig()
        r.run(1700, HudState("A", 500, 1000L))
        r.sent.clear()
        r.now = 2000
        r.engine.onGuidanceEnded(r.now)
        r.now = 2600
        r.engine.onGuidanceEnded(r.now)
        assertEquals(listOf(3), r.subs())
        r.sent.clear()
        r.now = 3000
        r.run(5000, HudState("A", 500, 1000L))
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
    }

    @Test fun yandexGoneForMoreThanFiveSecondsClears() {
        val r = Rig()
        r.engine.onState(HudState("A", 500, 1000L), 0)
        r.sent.clear()
        r.engine.tick(5000)
        r.sent.clear()
        r.engine.tick(5501)
        assertEquals(listOf(3), r.subs())
        r.sent.clear()
        r.engine.tick(9000)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun neverSendsBeforeGuidanceOrAfterNothing() {
        val r = Rig()
        r.engine.onGuidanceEnded(0)
        r.engine.tick(10_000)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun rateLimiterStillCapsAtFivePerSecond() {
        val sent = ArrayList<IntArray>()
        val e = HudBridgeEngine({ sent.add(it); true }, { null }, HudRateLimiter(1, 1000))
        e.onState(HudState("A", 500, 3000L), 0)
        e.tick(400)
        assertEquals(1, sent.size)          // sınırlayıcı doldu
        e.tick(1000)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it[0] in HudBridgeWhitelist.ALLOWED })
    }

    @Test fun failedSendIsRetriedAfterBackoffAndStateNotAdvanced() {
        val r = Rig()
        r.ok = false
        r.engine.onState(HudState("A", 500, null), 0)
        assertTrue(r.sent.isEmpty())
        r.engine.tick(500)                            // geri çekilme süresi içinde: denemez
        r.ok = true
        r.engine.tick(1000)
        assertTrue(r.sent.isEmpty())
        r.now = 2100
        r.run(5000)
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
    }

    @Test fun accessibilityListCheck() {
        val c = "com.mapcontrol.launchertest/com.mapcontrol.hud.YandexHudAccessibilityService"
        assertFalse(HudAccessibilityCheck.isListed(null, c))
        assertFalse(HudAccessibilityCheck.isListed("a.b/c.D", c))
        assertTrue(HudAccessibilityCheck.isListed("a.b/c.D:$c", c))
        assertTrue(HudAccessibilityCheck.isListed(c, c))
    }
}
