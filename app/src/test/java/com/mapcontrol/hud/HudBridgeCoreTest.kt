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
        var ok = true
        val engine = HudBridgeEngine(
            send = { if (ok) { sent.add(it); true } else false },
            iconSource = { icon },
            limiter = HudRateLimiter(1000, 1000), // sınırlayıcıyı ayrı sınıyoruz
        )
        fun subs() = sent.map { it[0] }
    }

    @Test fun startSendsClearThenStartThenRoadManeuverTotal() {
        val r = Rig()
        r.engine.onState(HudState("Ana Cd.", 850, 2900L), 0)
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
        assertArrayEquals(HudPayload.clear(), r.sent[0])
        assertArrayEquals(HudPayload.navStarted(), r.sent[1])
        assertArrayEquals(HudPayload.roadName("Ana Cd."), r.sent[2])
        // ikon bilinmiyor -> 0, tahmin yok
        assertArrayEquals(HudPayload.maneuverAlt(0, 850), r.sent[3])
        assertArrayEquals(HudPayload.totalDistanceAlt(2900L), r.sent[4])
    }

    @Test fun unchangedStateSendsNothing() {
        val r = Rig()
        val s = HudState("Ana Cd.", 850, 2900L)
        r.engine.onState(s, 0)
        r.sent.clear()
        r.engine.onState(s, 1000)
        r.engine.onState(s.copy(road = "Ana Cd."), 2000)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun maneuverThresholdTenMetersAndTwoHertz() {
        val r = Rig()
        r.engine.onState(HudState("A", 850, null), 0)
        r.sent.clear()
        r.engine.onState(HudState("A", 845, null), 1000)   // 5 m: yok
        assertTrue(r.sent.isEmpty())
        r.engine.onState(HudState("A", 840, null), 1100)   // 10 m ama 2 Hz içinde değil (son gönderim t=0, 1100 geçti) -> var
        assertEquals(listOf(50), r.subs())
        r.sent.clear()
        r.engine.onState(HudState("A", 820, null), 1300)   // 20 m ama 200 ms: 2 Hz engeli
        assertTrue(r.sent.isEmpty())
        r.engine.onState(HudState("A", 820, null), 1700)   // 600 ms geçti -> gider
        assertEquals(listOf(50), r.subs())
    }

    @Test fun iconChangeTriggersManeuverSend() {
        val r = Rig(icon = null)
        r.engine.onState(HudState(null, 500, null), 0)
        r.sent.clear()
        r.icon = 3
        r.engine.onState(HudState(null, 500, null), 1000)
        assertEquals(1, r.sent.size)
        assertArrayEquals(HudPayload.maneuverAlt(3, 500), r.sent[0])
    }

    @Test fun totalThresholdHundredMeters() {
        val r = Rig()
        r.engine.onState(HudState(null, null, 5000L), 0)
        r.sent.clear()
        r.engine.onState(HudState(null, null, 4950L), 1000)
        assertTrue(r.sent.isEmpty())
        r.engine.onState(HudState(null, null, 4900L), 2000)
        assertEquals(listOf(53), r.subs())
    }

    @Test fun roadSentOnlyWhenChanged() {
        val r = Rig()
        r.engine.onState(HudState("A", 500, null), 0)
        r.sent.clear()
        r.engine.onState(HudState("B", 500, null), 1000)
        assertEquals(listOf(48), r.subs())
    }

    @Test fun guidanceEndClearsOnceAndRestartResends() {
        val r = Rig()
        r.engine.onState(HudState("A", 500, 1000L), 0)
        r.sent.clear()
        r.engine.onGuidanceEnded(1000)
        r.engine.onGuidanceEnded(1100)
        assertEquals(listOf(3), r.subs())
        r.sent.clear()
        r.engine.onState(HudState("A", 500, 1000L), 2000)
        assertEquals(listOf(3, 52, 48, 50, 53), r.subs())
    }

    @Test fun yandexGoneForMoreThanFiveSecondsClears() {
        val r = Rig()
        r.engine.onState(HudState("A", 500, 1000L), 0)
        r.sent.clear()
        r.engine.tick(5000)
        assertTrue(r.sent.isEmpty())
        r.engine.tick(5001)
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

    @Test fun rateLimitDefersRemainingCommandsInsteadOfDroppingThem() {
        val sent = ArrayList<IntArray>()
        val e = HudBridgeEngine({ sent.add(it); true }, { null }, HudRateLimiter(5, 1000))
        e.onState(HudState("A", 500, 3000L), 0)
        assertEquals(5, sent.size)
        e.onState(HudState("B", 400, 3000L), 100)   // yeni yol + manevra: limit dolu
        assertEquals(5, sent.size)
        e.tick(1000)                                  // pencere açıldı
        assertEquals(listOf(3, 52, 48, 50, 53, 48, 50), sent.map { it[0] })
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
        r.engine.tick(2100)
        assertEquals(listOf(3, 52, 48, 50), r.subs())
    }

    @Test fun accessibilityListCheck() {
        val c = "com.mapcontrol.launchertest/com.mapcontrol.hud.YandexHudAccessibilityService"
        assertFalse(HudAccessibilityCheck.isListed(null, c))
        assertFalse(HudAccessibilityCheck.isListed("a.b/c.D", c))
        assertTrue(HudAccessibilityCheck.isListed("a.b/c.D:$c", c))
        assertTrue(HudAccessibilityCheck.isListed(c, c))
    }
}
