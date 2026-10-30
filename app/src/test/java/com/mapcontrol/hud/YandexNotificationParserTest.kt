package com.mapcontrol.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexNotificationParserTest {

    private fun p() = YandexNotificationParser()

    // --- mesafe biçimleri ---

    @Test fun distanceFormats() {
        assertEquals(250L, YandexNotificationParser.parseDistance("250 m"))
        assertEquals(1200L, YandexNotificationParser.parseDistance("1,2 km"))
        assertEquals(1200L, YandexNotificationParser.parseDistance("1.2 km"))
        assertEquals(800L, YandexNotificationParser.parseDistance("800 м"))
        assertEquals(20L, YandexNotificationParser.parseDistance("20 m"))
        assertEquals(500L, YandexNotificationParser.parseDistance("500 m"))
        assertEquals(250L, YandexNotificationParser.parseDistance("250 m"))
        assertEquals(1500L, YandexNotificationParser.parseDistance("1,5 км"))
        assertNull(YandexNotificationParser.parseDistance("Navigator başlatıldı"))
        assertNull(YandexNotificationParser.parseDistance(null))
        assertNull(YandexNotificationParser.parseDistance(""))
    }

    @Test fun looseDistanceInsideLongerTitle() {
        assertEquals(300L, YandexNotificationParser.parseDistance("300 m · 5 dk"))
    }

    // --- gerçek gözlemlenen metinler ---

    @Test fun observedInstructionsWithoutIcon() {
        assertEquals(Maneuver.RIGHT, p().parse("250 m", "Sağa dönün", null)!!.maneuver)
        assertEquals(Maneuver.UTURN, p().parse("20 m", "Soldan U dönüşü yapın", null)!!.maneuver)
        assertEquals(Maneuver.ROUNDABOUT_ENTER, p().parse("500 m", "Döner kavşağa girin", null)!!.maneuver)
        assertEquals(Maneuver.SLIGHT_RIGHT, p().parse("500 m", "Sağdan devam edin", null)!!.maneuver)
        assertEquals(Maneuver.SLIGHT_LEFT, p().parse("500 m", "Soldan devam edin", null)!!.maneuver)
        assertEquals(Maneuver.LEFT, p().parse("500 m", "Sola dönün", null)!!.maneuver)
    }

    @Test fun observedStreetIsNotAnInstruction() {
        val u = p().parse("250 m", "Güney Bağlantı Yolu", null)!!
        assertEquals("Güney Bağlantı Yolu", u.street)
        assertNull(u.maneuver)
        assertEquals(250L, u.distanceM)
    }

    @Test fun foregroundPlaceholderIsNoData() {
        assertNull(p().parse("Navigator başlatıldı", null, null))
        assertNull(p().parse("Navigator başlatıldı", "Güney Bağlantı Yolu", null))
    }

    // --- ikon adı (öncelik 1) ---

    @Test fun iconNames() {
        val m = { n: String? -> YandexNotificationParser.maneuverFromIconName(n) }
        assertEquals(Maneuver.STRAIGHT, m("notification_straight_sdl"))
        assertEquals(Maneuver.SLIGHT_LEFT, m("notification_slight_left_sdl"))
        assertEquals(Maneuver.SLIGHT_RIGHT, m("notification_slight_right_sdl"))
        assertEquals(Maneuver.LEFT, m("notification_left_sdl"))
        assertEquals(Maneuver.RIGHT, m("notification_right_sdl"))
        assertEquals(Maneuver.SHARP_LEFT, m("notification_hard_left_sdl"))
        assertEquals(Maneuver.SHARP_RIGHT, m("notification_hard_right_sdl"))
        assertEquals(Maneuver.SLIGHT_LEFT, m("notification_fork_left_sdl"))
        assertEquals(Maneuver.SLIGHT_RIGHT, m("notification_fork_right_sdl"))
        assertEquals(Maneuver.UTURN, m("notification_uturn_left_sdl"))
        assertEquals(Maneuver.UTURN, m("notification_uturn_right_sdl"))
        assertEquals(Maneuver.ROUNDABOUT_ENTER, m("notification_enter_roundabout_sdl"))
        assertEquals(Maneuver.ROUNDABOUT_EXIT, m("notification_leave_roundabout_sdl"))
        assertEquals(Maneuver.SLIGHT_LEFT, m("notification_exit_left_sdl"))
        assertEquals(Maneuver.SLIGHT_RIGHT, m("notification_exit_right_sdl"))
        assertEquals(Maneuver.DESTINATION, m("notification_finish_sdl"))
        assertNull(m("notification_board_ferry_sdl"))
        assertNull(m("directions_lane_unknown_32"))
        assertNull(m(null))
    }

    @Test fun iconWinsOverText() {
        val u = p().parse("250 m", "Sağa dönün", "notification_left_sdl")!!
        assertEquals(Maneuver.LEFT, u.maneuver)
    }

    @Test fun iconWithStreetText() {
        val u = p().parse("250 m", "Güney Bağlantı Yolu", "notification_slight_right_sdl")!!
        assertEquals(Maneuver.SLIGHT_RIGHT, u.maneuver)
        assertEquals("Güney Bağlantı Yolu", u.street)
    }

    // --- Türkçe/İngilizce/Rusça kalıplar ---

    @Test fun sharpAndRoundaboutExit() {
        assertEquals(Maneuver.SHARP_LEFT, p().parse("100 m", "Keskin sola dönün", null)!!.maneuver)
        assertEquals(Maneuver.SHARP_RIGHT, p().parse("100 m", "Keskin sağa dönün", null)!!.maneuver)
        val u = p().parse("100 m", "Döner kavşakta 2. çıkıştan çıkın", null)!!
        assertEquals(Maneuver.ROUNDABOUT_EXIT, u.maneuver)
        assertEquals(2, u.roundaboutExit)
        assertEquals(3, p().parse("100 m", "3. çıkış", null)!!.roundaboutExit)
    }

    @Test fun straightAndDestination() {
        assertEquals(Maneuver.STRAIGHT, p().parse("1,2 km", "Düz devam edin", null)!!.maneuver)
        assertEquals(Maneuver.STRAIGHT, p().parse("1,2 km", "Devam edin", null)!!.maneuver)
        assertEquals(Maneuver.DESTINATION, p().parse("50 m", "Varış noktasına ulaştınız", null)!!.maneuver)
        assertEquals(Maneuver.DESTINATION, p().parse("50 m", "Hedefe ulaştınız", null)!!.maneuver)
    }

    @Test fun englishAndRussianLoosely() {
        assertEquals(Maneuver.RIGHT, p().parse("250 m", "Turn right", null)!!.maneuver)
        assertEquals(Maneuver.SLIGHT_LEFT, p().parse("250 m", "Slight left", null)!!.maneuver)
        assertEquals(Maneuver.SHARP_RIGHT, p().parse("250 m", "Sharp right", null)!!.maneuver)
        assertEquals(Maneuver.UTURN, p().parse("250 m", "Make a U-turn", null)!!.maneuver)
        assertEquals(Maneuver.ROUNDABOUT_ENTER, p().parse("250 m", "Enter the roundabout", null)!!.maneuver)
        assertEquals(2, p().parse("250 m", "Take the 2nd exit", null)!!.roundaboutExit)
        assertEquals(Maneuver.LEFT, p().parse("250 м", "Поверните налево", null)!!.maneuver)
        assertEquals(Maneuver.RIGHT, p().parse("250 м", "Поверните направо", null)!!.maneuver)
        assertEquals(Maneuver.UTURN, p().parse("250 м", "Развернитесь", null)!!.maneuver)
        assertEquals(Maneuver.SHARP_LEFT, p().parse("250 м", "Резко налево", null)!!.maneuver)
        assertEquals(Maneuver.STRAIGHT, p().parse("250 м", "Двигайтесь прямо", null)!!.maneuver)
    }

    @Test fun keepLeftTextOverridesPlainLeftIcon() {
        val l = p().parse("50 m", "Soldan devam edin", "notification_left_sdl")!!
        assertEquals(Maneuver.SLIGHT_LEFT, l.maneuver)
        assertEquals(4, HudManeuverIcons.hudIcon(l.maneuver, l.roundaboutExit))
        assertEquals("Soldan devam edin", l.instruction)
        val r = p().parse("50 m", "Sağdan devam edin", "notification_right_sdl")!!
        assertEquals(Maneuver.SLIGHT_RIGHT, r.maneuver)
        assertEquals(5, HudManeuverIcons.hudIcon(r.maneuver, r.roundaboutExit))
        assertEquals(Maneuver.SLIGHT_RIGHT, p().parse("50 m", "Hafif sağa dönün", "notification_right_sdl")!!.maneuver)
    }

    @Test fun keepTextDoesNotOverrideSharpUturnOrRoundaboutIcons() {
        assertEquals(Maneuver.SHARP_LEFT, p().parse("50 m", "Soldan devam edin", "notification_hard_left_sdl")!!.maneuver)
        assertEquals(Maneuver.UTURN, p().parse("50 m", "Soldan devam edin", "notification_uturn_left_sdl")!!.maneuver)
        assertEquals(Maneuver.ROUNDABOUT_ENTER, p().parse("50 m", "Soldan devam edin", "notification_enter_roundabout_sdl")!!.maneuver)
    }

    @Test fun uppercaseTurkishI() {
        assertEquals(Maneuver.SLIGHT_RIGHT, p().parse("100 m", "SAĞDAN DEVAM EDİN", null)!!.maneuver)
        assertEquals(Maneuver.UTURN, p().parse("100 m", "SOLDAN U DÖNÜŞÜ YAPIN", null)!!.maneuver)
    }

    // --- sokak ve manevra ayrı hatırlanır ---

    @Test fun streetAndManeuverRememberedSeparately() {
        val parser = p()
        val a = parser.parse("500 m", "Sağa dönün", null, 0L)!!
        assertEquals(Maneuver.RIGHT, a.maneuver)
        assertNull(a.street)
        val b = parser.parse("480 m", "Güney Bağlantı Yolu", null, 1000L)!!
        assertEquals(Maneuver.RIGHT, b.maneuver)
        assertEquals("Güney Bağlantı Yolu", b.street)
        val c = parser.parse("470 m", "Sağa dönün", null, 2000L)!!
        assertEquals("Güney Bağlantı Yolu", c.street)
        assertEquals(Maneuver.RIGHT, c.maneuver)
    }

    @Test fun rememberedManeuverDroppedWhenDistanceJumps() {
        val parser = p()
        parser.parse("20 m", "Sağa dönün", null, 0L)
        val next = parser.parse("900 m", "Atatürk Cd.", null, 3000L)!!
        assertNull(next.maneuver)
        assertEquals("Atatürk Cd.", next.street)
    }

    @Test fun rememberedStateForgottenAfterLongGap() {
        val parser = p()
        parser.parse("250 m", "Sağa dönün", null, 0L)
        val u = parser.parse("250 m", "Yan Yol", null, YandexNotificationParser.MEMORY_MS + 1)!!
        assertNull(u.maneuver)
    }

    @Test fun roundaboutExitSurvivesIconOnlyUpdate() {
        val parser = p()
        parser.parse("100 m", "Döner kavşakta 2. çıkıştan çıkın", "notification_enter_roundabout_sdl", 0L)
        val u = parser.parse("90 m", "Güney Bağlantı Yolu", "notification_enter_roundabout_sdl", 1000L)!!
        assertEquals(Maneuver.ROUNDABOUT_ENTER, u.maneuver)
        assertEquals(2, u.roundaboutExit)
    }

    // --- HUD ikonu ---

    @Test fun hudIconMapping() {
        fun i(m: Maneuver?, e: Int? = null) = HudManeuverIcons.hudIcon(m, e)
        assertEquals(2, i(Maneuver.LEFT))
        assertEquals(3, i(Maneuver.RIGHT))
        assertEquals(4, i(Maneuver.SLIGHT_LEFT))
        assertEquals(5, i(Maneuver.SLIGHT_RIGHT))
        assertEquals(6, i(Maneuver.SHARP_LEFT))
        assertEquals(7, i(Maneuver.SHARP_RIGHT))
        assertEquals(8, i(Maneuver.UTURN))
        assertEquals(9, i(Maneuver.STRAIGHT))
        assertEquals(11, i(Maneuver.ROUNDABOUT_ENTER))
        assertEquals(15, i(Maneuver.DESTINATION))
        assertEquals(30, i(Maneuver.ROUNDABOUT_EXIT, 2))
        assertEquals(31, i(Maneuver.ROUNDABOUT_ENTER, 3))
        assertEquals(0, i(Maneuver.ROUNDABOUT_EXIT))
        assertEquals(0, i(null))
        assertEquals(11, i(Maneuver.ROUNDABOUT_ENTER, 40))
    }

    // --- birleştirici ---

    @Test fun mergerNotificationPrimaryA11yAddsTotal() {
        val m = HudNavMerger()
        m.onNotification(NavUpdate(250, "Ana Cd.", Maneuver.LEFT, null), 0)
        assertEquals(HudState("Ana Cd.", 250, null), m.state(0))
        assertEquals(2, m.icon())
        m.onAccessibility(HudState("Başka", 999, 5000L), 100)
        assertEquals(HudState("Ana Cd.", 250, 5000L), m.state(200))
    }

    @Test fun mergerWorksFromNotificationAlone() {
        val m = HudNavMerger()
        m.onNotification(NavUpdate(20, null, null, null), 0)
        assertEquals(HudState(null, 20, null), m.state(10))
        assertNull(m.icon())
    }

    @Test fun mergerStaleNotificationFallsBackToA11yThenNull() {
        val m = HudNavMerger()
        m.onNotification(NavUpdate(250, "A", Maneuver.RIGHT, null), 0)
        m.onAccessibility(HudState("B", 240, 3000L), 5900)
        assertEquals(HudState("B", 240, 3000L), m.state(6000))
        assertNull(m.icon())
        assertNull(m.state(20_000))
    }

    @Test fun engineSendsNotificationIconToHud() {
        val sent = ArrayList<IntArray>()
        val m = HudNavMerger()
        val e = HudBridgeEngine({ sent.add(it); true }, { m.icon() }, HudRateLimiter(1000, 1000))
        m.onNotification(NavUpdate(250, "Ana Cd.", Maneuver.UTURN, null), 0)
        for (t in 0..2000 step 200) e.onState(m.state(0)!!, t.toLong())
        assertTrue(sent.any { it[0] == 50 && it[2] == 8 })
    }
}
