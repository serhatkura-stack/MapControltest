package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniDashLogicTest {

    private val okTires = TireReading(listOf(2.4, 2.4, 2.3, 2.2), warning = false)

    // --- Uyarılar ---

    @Test fun calmWhenEverythingFine() {
        assertTrue(MiniAlerts.build(okTires, lowFuel = false, serviceKm = 3_978, serviceDays = 155, pm25In = 4).isEmpty())
    }

    @Test fun unknownValuesAreNotAlerts() {
        assertTrue(MiniAlerts.build(null, null, null, null, null).isEmpty())
    }

    @Test fun lowTireNamesWheelAndBar() {
        val a = MiniAlerts.build(TireReading(listOf(2.4, 2.4, 1.6, 2.2), warning = false), false, null, null, null)
        assertEquals(listOf(MiniAlerts.Kind.TIRE), a.map { it.kind })
        assertEquals(2, a[0].tireLow?.wheel)
        assertEquals(1.6, a[0].tireLow?.bar!!, 1e-9)
        assertEquals(23, TireDecode.psi(a[0].tireLow!!.bar!!))
    }

    @Test fun tireWarningFlagWithoutLowValueStillAlerts() {
        val a = MiniAlerts.build(okTires.copy(warning = true), false, null, null, null)
        assertEquals(MiniAlerts.Kind.TIRE, a.single().kind)
        assertNull(a.single().tireLow)
    }

    @Test fun orderIsImportanceOrder() {
        val a = MiniAlerts.build(TireReading(listOf(1.5, 2.4, 2.4, 2.4)), lowFuel = true, serviceKm = 800, serviceDays = 40, pm25In = 80)
        assertEquals(listOf(MiniAlerts.Kind.TIRE, MiniAlerts.Kind.FUEL_LOW, MiniAlerts.Kind.SERVICE_NEAR, MiniAlerts.Kind.AIR_BAD), a.map { it.kind })
        assertEquals(800, a[2].serviceKm)
        assertEquals(80, a[3].pm25)
    }

    @Test fun healthAndFuelCapComeFirstInImportanceOrder() {
        val a = MiniAlerts.build(
            TireReading(listOf(1.5, 2.4, 2.4, 2.4)), lowFuel = true, serviceKm = 800, serviceDays = 40, pm25In = 80,
            batteryV = 11.4, fuelCapOpen = true,
        )
        assertEquals(
            listOf(
                MiniAlerts.Kind.BATTERY_LOW, MiniAlerts.Kind.TIRE, MiniAlerts.Kind.FUEL_CAP,
                MiniAlerts.Kind.FUEL_LOW, MiniAlerts.Kind.SERVICE_NEAR, MiniAlerts.Kind.AIR_BAD,
            ),
            a.map { it.kind },
        )
        assertEquals(11.4, a[0].batteryV!!, 1e-9)
        val (named, more) = MiniAlerts.visible(a)
        assertEquals(listOf(MiniAlerts.Kind.BATTERY_LOW, MiniAlerts.Kind.TIRE), named.map { it.kind })
        assertEquals(4, more)
    }

    @Test fun healthUsesHealthMathThresholds() {
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 12.6).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.BATTERY_HIGH), MiniAlerts.build(null, null, null, null, null, batteryV = 15.4, batteryHighHeld = true).map { it.kind })
        // 30 sn sürmeyen aşırı gerilim şeride çıkmaz; 14,5 V (çalışan motor) hiç çıkmaz.
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 15.4).isEmpty())
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 14.5, batteryHighHeld = true).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.FUEL_CAP), MiniAlerts.build(null, null, null, null, null, fuelCapOpen = true).map { it.kind })
    }

    @Test fun serviceThresholdsMatchSahne() {
        assertTrue(MiniAlerts.build(null, null, StageCorners.SERVICE_NEAR_KM, 200, null).any { it.kind == MiniAlerts.Kind.SERVICE_NEAR })
        assertTrue(MiniAlerts.build(null, null, StageCorners.SERVICE_NEAR_KM + 1, 200, null).isEmpty())
        // Gün yaklaşması Sahne'de bakım notu açmaz (yalnızca km ya da geçmiş).
        assertTrue(MiniAlerts.build(null, null, 5_000, 20, null).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.SERVICE_OVERDUE), MiniAlerts.build(null, null, 0, 0, null).map { it.kind })
        assertEquals(listOf(MiniAlerts.Kind.SERVICE_OVERDUE), MiniAlerts.build(null, null, 4_000, 0, null).map { it.kind })
    }

    @Test fun airOnlyWhenPoor() {
        assertTrue(MiniAlerts.build(null, null, null, null, StageCorners.AIR_BAD_PM25 - 1).isEmpty())
        assertEquals(MiniAlerts.Kind.AIR_BAD, MiniAlerts.build(null, null, null, null, StageCorners.AIR_BAD_PM25).single().kind)
    }

    @Test fun twoNamedThenPlusN() {
        val all = MiniAlerts.build(TireReading(listOf(1.5, 2.4, 2.4, 2.4)), true, 800, 40, 80)
        val (named, more) = MiniAlerts.visible(all)
        assertEquals(2, named.size)
        assertEquals(MiniAlerts.Kind.TIRE, named[0].kind)
        assertEquals(2, more)
        val (one, none) = MiniAlerts.visible(all.take(1))
        assertEquals(1, one.size)
        assertEquals(0, none)
    }

    // --- Navigasyon ---

    @Test fun navHiddenWithoutData() {
        assertFalse(MiniNav.shown(null))
        assertFalse(MiniNav.shown(NavHint(" ", null, null)))
        assertTrue(MiniNav.shown(NavHint(null, 200, null)))
        assertTrue(MiniNav.shown(NavHint("Sağa dönün", null, null)))
    }

    @Test fun navDistanceFormatting() {
        assertEquals("350" to false, MiniNav.distance(347))
        assertEquals("0" to false, MiniNav.distance(3))
        assertEquals("1,0" to true, MiniNav.distance(996))
        assertEquals("1,2" to true, MiniNav.distance(1_240))
        assertEquals("9,9" to true, MiniNav.distance(9_940))
        assertEquals("12" to true, MiniNav.distance(12_400))
        assertEquals("1.5" to true, MiniNav.distance(1_500, '.'))
        assertNull(MiniNav.distance(-1))
        assertNull(MiniNav.distance(null))
    }

    @Test fun navArrival() {
        assertEquals("14:32", MiniNav.arrival(14 * 60 + 32))
        assertEquals("09:05", MiniNav.arrival(9 * 60 + 5))
        assertEquals("2:32", MiniNav.arrival(14 * 60 + 32, is24 = false))
        assertEquals("12:00", MiniNav.arrival(0, is24 = false))
        assertNull(MiniNav.arrival(24 * 60))
        assertNull(MiniNav.arrival(null))
    }

    // --- Ölçüler ---

    @Test fun halfScreenGetsFullSizes() {
        val m = MiniLayout.forSize(960, 610)
        assertEquals(88, m.playDp)
        assertEquals(72, m.skipDp)
        assertTrue(m.showArtwork)
        assertTrue(m.showLabels)
    }

    @Test fun touchTargetsNeverBelowMinimum() {
        for (w in listOf(480, 640, 760, 960, 1280)) for (h in listOf(360, 440, 520, 610, 720)) {
            val m = MiniLayout.forSize(w, h)
            assertTrue("$w x $h", m.skipDp >= MiniLayout.MIN_TOUCH_DP)
            assertTrue("$w x $h", m.playDp >= MiniLayout.MIN_TOUCH_DP)
            assertTrue("$w x $h", m.toggleHeightDp >= MiniLayout.MIN_TOUCH_DP)
        }
    }

    @Test fun tiggo8TopHalfUsesWideRow() {
        // 2560 x ~700 px @240 dpi = 1707 x ~467 dp
        val m = MiniLayout.forSize(1707, 467)
        assertTrue(m.wide)
        assertTrue(m.showArtwork)
        assertTrue(m.showLabels)
        assertTrue(m.skipDp >= MiniLayout.MIN_TOUCH_DP)
        assertTrue(MiniLayout.forSize(1707, 480).wide)
    }

    @Test fun narrowerHalvesStayColumn() {
        assertFalse(MiniLayout.forSize(960, 610).wide)
        assertFalse(MiniLayout.forSize(960, 520).wide)
        assertFalse(MiniLayout.forSize(640, 610).wide)
        assertFalse(MiniLayout.forSize(1280, 720).wide)
        assertFalse(MiniLayout.forSize(1707, 960).wide)
        assertFalse(MiniLayout.isWide(1707, 0))
    }

    @Test fun thirdScreenIsCompact() {
        val m = MiniLayout.forSize(640, 610)
        assertEquals(MiniLayout.MIN_TOUCH_DP, m.skipDp)
        assertFalse(m.showArtwork)
        assertTrue(m.showLabels)
    }

    // --- Gerçek navigasyon kaynağı ---

    @Test fun navStateBecomesHint() {
        assertNull(MiniNav.hint(NavigationState.NONE, { "x" }, { "Çıkış $it" }))
        val h = MiniNav.hint(NavigationState.DEMO, { if (it == NavManeuver.RIGHT) "Sağa dönün" else null }, { "Çıkış $it" })!!
        assertEquals("Sağa dönün · Atatürk Cd.", h.maneuver)
        assertEquals(300, h.distanceM)
        assertEquals(14 * 60 + 52, h.arrivalMinutesOfDay)
        assertNull(MiniNav.minutesOfDay("25:00"))
        assertEquals(5, MiniNav.minutesOfDay("0:05"))
    }
}
