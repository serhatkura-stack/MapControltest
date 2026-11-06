package com.mapcontrol.launcher

import com.mapcontrol.launcher.TripHero.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHeroTest {

    @Test fun parkedShowsTrip() {
        assertEquals(Content.TRIP, TripHero.content(driving = false, connected = true, tripKm = 53.8))
    }

    @Test fun drivingShowsLiveConsumption() {
        assertEquals(Content.LIVE, TripHero.content(driving = true, connected = true, tripKm = 53.8))
        // Yolculuk km'si okunamasa da sürüşte canlı tüketim gösterilir.
        assertEquals(Content.LIVE, TripHero.content(driving = true, connected = true, tripKm = null))
    }

    @Test fun noDataWins() {
        assertEquals(Content.NO_DATA, TripHero.content(driving = true, connected = false, tripKm = 53.8))
        assertEquals(Content.NO_DATA, TripHero.content(driving = false, connected = false, tripKm = null))
        assertEquals(Content.NO_DATA, TripHero.content(driving = false, connected = true, tripKm = null))
    }

    @Test fun liveStatesMatchConsumptionTile() {
        assertEquals(TripHero.Live.WAITING, TripHero.live(ConsumptionSmoother.Display(ConsumptionSmoother.Mode.NONE)))
        assertEquals(TripHero.Live.IDLE, TripHero.live(ConsumptionSmoother.Display(ConsumptionSmoother.Mode.IDLE)))
        assertEquals(TripHero.Live.LOW_SPEED, TripHero.live(ConsumptionSmoother.Display(ConsumptionSmoother.Mode.TAKEOFF)))
        assertEquals(TripHero.Live.VALUE, TripHero.live(ConsumptionSmoother.Display(ConsumptionSmoother.Mode.VALUE, 7.2)))
        assertEquals(TripHero.Live.CAP, TripHero.live(ConsumptionSmoother.Display(ConsumptionSmoother.Mode.CAP, 30.0)))
    }

    @Test fun crossfadeOnlyBetweenTripAndLive() {
        assertTrue(TripHero.crossfade(Content.TRIP, Content.LIVE))
        assertTrue(TripHero.crossfade(Content.LIVE, Content.TRIP))
        assertFalse(TripHero.crossfade(null, Content.TRIP))
        assertFalse(TripHero.crossfade(Content.NO_DATA, Content.LIVE))
        assertFalse(TripHero.crossfade(Content.TRIP, Content.NO_DATA))
        assertFalse(TripHero.crossfade(Content.LIVE, Content.LIVE))
    }

    /** Sürüş durumu düzen geçişiyle aynı gecikmelerle: 5 sn hareket, kırmızı ışıkta kart titremez. */
    @Test fun cardFollowsDrivePolicyWithoutFlicker() {
        val p = DriveLayoutPolicy()
        fun at(sec: Long, moving: Boolean?, parked: Boolean = false): Content {
            p.update(sec * 1000, moving, parked, canSwitch = true)
            return TripHero.content(p.mode == LayoutMode.DRIVE, connected = true, tripKm = 12.0)
        }
        assertEquals(Content.TRIP, at(0, true))
        assertEquals(Content.TRIP, at(4, true))        // kısa kımıldama kartı değiştirmez
        assertEquals(Content.LIVE, at(5, true))
        assertEquals(Content.LIVE, at(10, false))      // kırmızı ışık
        assertEquals(Content.LIVE, at(100, false))
        assertEquals(Content.LIVE, at(101, true))      // yeşil: sürüşte kalır
        assertEquals(Content.LIVE, at(110, false, parked = true))
        assertEquals(Content.TRIP, at(112, false, parked = true)) // P/el freni 2 sn: yolculuk
    }

    @Test fun stillFallbackReturnsToTrip() {
        val p = DriveLayoutPolicy()
        p.update(0, true, false, true); p.update(5_000, true, false, true)
        p.update(10_000, false, false, true)
        p.update(10_000 + DriveLayoutPolicy.STILL_FALLBACK_MS, false, false, true)
        assertEquals(Content.TRIP, TripHero.content(p.mode == LayoutMode.DRIVE, true, 12.0))
    }

    @Test fun driveMotionNotifiesOnlyOnChange() {
        DriveMotion.set(false)
        val seen = ArrayList<Boolean>()
        val l: (Boolean) -> Unit = { seen += it }
        DriveMotion.add(l)
        DriveMotion.set(false); DriveMotion.set(true); DriveMotion.set(true); DriveMotion.set(false)
        DriveMotion.remove(l)
        DriveMotion.set(true)
        DriveMotion.set(false)
        assertEquals(listOf(true, false), seen)
    }

    @Test fun separateDriveLayoutIsOffByDefault() {
        assertFalse(DriveLayoutPrefs.DEFAULT_ON)
    }
}
