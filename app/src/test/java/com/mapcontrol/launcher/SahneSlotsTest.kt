package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sahne düzeninin saf mantığı: biçimler, varsayılanlar, kayıt biçimi, temizleme ve yerleştirme kuralları. */
class SahneSlotsTest {

    private val apps = listOf("com.google.android.apps.youtube.music", "com.google.android.apps.maps")

    @Test
    fun curatedSlotListsMatchTheOwnerDecision() {
        val strip = setOf(
            WidgetType.SEAT_DRIVER, WidgetType.SEAT_PASSENGER, WidgetType.TRUNK, WidgetType.APP, WidgetType.REAR_SPEAKER,
            WidgetType.LOCK, WidgetType.DRIVE_MODE, WidgetType.BREAK_REMINDER, WidgetType.SCREEN_OFF, WidgetType.PHONE_REMINDER,
            WidgetType.CONSUMPTION, WidgetType.WEB_PANEL, WidgetType.EQUALIZER, WidgetType.AUTOMATION, WidgetType.SPLIT_SCREEN,
            WidgetType.CLUSTER_MIRROR,
        )
        val panel = setOf(
            WidgetType.MEDIA, WidgetType.TRIP, WidgetType.CONSUMPTION, WidgetType.HEALTH, WidgetType.AIR, WidgetType.AWD,
            WidgetType.PHOTO, WidgetType.CLOCK, WidgetType.WEB_PANEL, WidgetType.EQUALIZER, WidgetType.AUTOMATION,
            WidgetType.SPLIT_SCREEN, WidgetType.CLUSTER_MIRROR, WidgetType.BREAK_REMINDER, WidgetType.SEAT_DRIVER, WidgetType.SEAT_PASSENGER,
        )
        assertEquals(strip, SahneForm.STRIP_TYPES.toSet())
        assertEquals(panel, SahneForm.PANEL_TYPES.toSet())
        assertEquals(SahneForm.STRIP_TYPES.size, strip.size) // yinelenen yok
        assertEquals(SahneForm.PANEL_TYPES.size, panel.size)
        WidgetType.values().forEach { t ->
            assertEquals("$t şerit", t in strip, SahneForm.fitsStrip(t))
            assertEquals("$t panel", t in panel, SahneForm.fitsPanel(t))
        }
        // Seçici yalnızca listeyi sunar: araç kartı (sahnenin kendisi) hiçbir yuvada yok.
        assertFalse(WidgetType.VEHICLE in SahneForm.offered(SahneSlotKind.PANEL) || WidgetType.VEHICLE in SahneForm.offered(SahneSlotKind.STRIP))
        assertEquals(SahneForm.STRIP_TYPES, SahneForm.offered(SahneSlotKind.STRIP))
        assertEquals(SahneForm.PANEL_TYPES, SahneForm.offered(SahneSlotKind.PANEL))
    }

    @Test
    fun appIsStripOnlyAndPhotoIsPanelOnly() {
        assertTrue(SahneForm.fitsStrip(WidgetType.APP))
        assertFalse(SahneForm.fitsPanel(WidgetType.APP))
        assertTrue(SahneForm.fitsPanel(WidgetType.PHOTO))
        assertFalse(SahneForm.fitsStrip(WidgetType.PHOTO))
    }

    @Test
    fun panelAndStripSpansAreAllowedSizes() {
        WidgetType.values().forEach { t ->
            assertTrue(SahneForm.panelSpan(t) in t.sizes)
            assertTrue(SahneForm.stripSpan(t) in t.sizes)
        }
    }

    @Test
    fun largeFormFollowsSizes() {
        assertTrue(SahneForm.hasLargeForm(WidgetType.MEDIA))
        assertTrue(SahneForm.hasLargeForm(WidgetType.TRIP))
        assertFalse(SahneForm.hasLargeForm(WidgetType.CONSUMPTION))
        assertFalse(SahneForm.hasLargeForm(WidgetType.LOCK))
        assertTrue(SahneForm.hasLargeForm(WidgetType.SEAT_DRIVER))
        assertTrue(SahneForm.hasLargeForm(WidgetType.SEAT_PASSENGER))
    }

    @Test
    fun parkDefaultMatchesTheApprovedDesign() {
        val d = SahneSlots.defaultPark(apps)
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, WidgetType.TRUNK, WidgetType.REAR_SPEAKER, WidgetType.APP, WidgetType.LOCK, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
        assertEquals(listOf<String?>(apps[0], apps[1]), SahneSlots.appPackages(d.strip[3]?.extra))
    }

    @Test
    fun driveDefaultMatchesTheOwnerDecision() {
        val d = SahneSlots.defaultDrive(apps)
        assertEquals(STRIP_SLOTS, d.strip.size)
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.CONSUMPTION, d.right?.type)
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, WidgetType.REAR_SPEAKER, WidgetType.APP, WidgetType.BREAK_REMINDER, WidgetType.DRIVE_MODE, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
    }

    @Test
    fun defaultsUseOnlyCuratedTypesWithSeatsAtBothEnds() {
        listOf(SahneSlots.defaultPark(apps), SahneSlots.defaultDrive(apps)).forEach { d ->
            assertTrue(d.left!!.type in SahneForm.PANEL_TYPES && d.right!!.type in SahneForm.PANEL_TYPES)
            assertTrue(d.strip.all { it!!.type in SahneForm.STRIP_TYPES })
            assertEquals(WidgetType.SEAT_DRIVER, d.strip.first()?.type)
            assertEquals(WidgetType.SEAT_PASSENGER, d.strip.last()?.type)
        }
    }

    @Test
    fun defaultsAreAlreadyClean() {
        listOf(SahneSlots.defaultPark(apps), SahneSlots.defaultDrive(apps), SahneSlots.defaultPark()).forEach { d ->
            assertEquals(d, SahneSlots.sanitize(d))
            assertEquals(d.slots().size, d.slots().map { it.id }.toSet().size)
        }
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val d = SahneSlots.defaultPark(apps)
        assertEquals(d, SahneSlots.decode(SahneSlots.encode(d)))
        val withGaps = SahneSlots.set(SahneSlots.set(d, SahneSlotRef(SahneSlotKind.PANEL, 1), null), SahneSlotRef(SahneSlotKind.STRIP, 2), null)
        assertEquals(withGaps, SahneSlots.decode(SahneSlots.encode(withGaps)))
    }

    @Test
    fun badRecordsFallBackOrClean() {
        assertNull(SahneSlots.decode(null))
        assertNull(SahneSlots.decode(""))
        assertNull(SahneSlots.decode("v9|media~media~||"))
        assertNull(SahneSlots.decode("garbage"))
        // Bilinmeyen tür yalnızca kendi yuvasını boşaltır; eksik şerit yuvaları boş tamamlanır.
        val d = SahneSlots.decode("v1|nope~x~|trip~trip~|seat_heat_driver~s~")!!
        assertNull(d.left)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(STRIP_SLOTS, d.strip.size)
        assertEquals(WidgetType.SEAT_DRIVER, d.strip[0]?.type)
        assertTrue(d.strip.drop(1).all { it == null })
    }

    @Test
    fun removedHeatsCardEmptiesItsSlotOnDecode() {
        // Tiggo 7'de direksiyon/ön cam ısıtma donanımı yok: eski "heats" yuvası boşalır, kalanlar korunur.
        val d = SahneSlots.decode("v1|heats~h~|trip~trip~|")!!
        assertNull(d.left)
        assertEquals(WidgetType.TRIP, d.right?.type)
    }

    @Test
    fun sanitizeDropsMisfitsAndDuplicates() {
        val raw = SahneLayout(
            left = SahneSlot("app", WidgetType.APP),
            right = SahneSlot("trip", WidgetType.TRIP),
            strip = listOf(
                SahneSlot("trip2", WidgetType.TRIP),
                SahneSlot("photo", WidgetType.PHOTO),
                SahneSlot("a1", WidgetType.APP),
                SahneSlot("a2", WidgetType.APP),
                SahneSlot("a2", WidgetType.APP),
                SahneSlot("lock", WidgetType.LOCK),
                SahneSlot("extra", WidgetType.CLOCK),
            ),
        )
        val c = SahneSlots.sanitize(raw)
        assertNull(c.left) // uygulama panelde olmaz
        assertEquals(WidgetType.TRIP, c.right?.type)
        assertNull(c.strip[0]) // ikinci yolculuk
        assertNull(c.strip[1]) // fotoğraf şeritte olmaz
        assertEquals("a1", c.strip[2]?.id)
        assertEquals("a2", c.strip[3]?.id) // APP birden çok olabilir
        assertNull(c.strip[4]) // aynı kimlik
        assertEquals(WidgetType.LOCK, c.strip[5]?.type)
        assertEquals(STRIP_SLOTS, c.strip.size)
    }

    @Test
    fun oldRecordMigratesSeatsAndDropsRemovedWidgets() {
        // Eski Park varsayılanı: ısıtma sürücü · perde · bagaj · buğu · uygulamalar · ısıtma yolcu (+ panelde soğutma yolcu).
        val old = "v1|media~media~|seat_vent_passenger~vp~|seat_heat_driver~seat_heat_driver~,sunshade~sunshade~,trunk~trunk~," +
            "defrost~defrost~,app~app_pair~a.b+c.d,seat_heat_passenger~seat_heat_passenger~"
        val d = SahneSlots.decode(old)!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.SEAT_PASSENGER, d.right?.type) // soğutma yolcu → birleşik yolcu koltuğu (panelde)
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, null, WidgetType.TRUNK, null, WidgetType.APP, null),
            d.strip.map { it?.type },
        ) // perde ve buğu boşaldı; yolcu ısıtma ikinci yolcu koltuğu olduğundan atıldı (ilki kalır)
        assertEquals(d, SahneSlots.decode(SahneSlots.encode(d))) // yeniden yazınca aynı
    }

    @Test
    fun heatAndVentOfOneSideKeepOnlyOneMergedSeat() {
        val d = SahneSlots.decode("v1|||seat_heat_driver~h~,seat_vent_driver~v~,,,,")!!
        assertEquals(listOf("h" to WidgetType.SEAT_DRIVER), d.slots().map { it.id to it.type })
    }

    @Test
    fun placementRules() {
        val d = SahneSlots.defaultPark(apps)
        val right = SahneSlotRef(SahneSlotKind.PANEL, 1)
        val strip0 = SahneSlotRef(SahneSlotKind.STRIP, 0)
        // Ekranda başka yuvada olan tekil tür konamaz; kendi yerine yeniden konabilir.
        assertFalse(SahneForm.canPlace(d, right, WidgetType.MEDIA))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.TRIP))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.HEALTH))
        // Biçim kuralları (seçki); Park/Sürüş ayrımı yok, fotoğraf da sürüşte konabilir.
        assertFalse(SahneForm.canPlace(d, right, WidgetType.APP))
        assertTrue(SahneForm.canPlace(d, strip0, WidgetType.APP))
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.PHOTO))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.PHOTO))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.LOCK))     // kilit yalnızca şeritte
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.HEALTH))  // sağlık yalnızca panelde
        assertFalse(SahneForm.canPlace(d, right, WidgetType.VEHICLE))
        // Koltuk her iki taraftan panele de konabilir (şeritte zaten olan taraf hariç).
        val noSeats = SahneSlots.set(d, strip0, null)
        assertTrue(SahneForm.canPlace(noSeats, right, WidgetType.SEAT_DRIVER))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.SEAT_PASSENGER))
    }

    @Test
    fun setGetAndNewIds() {
        val d = SahneSlots.defaultPark(apps)
        val ref = SahneSlotRef(SahneSlotKind.STRIP, 3)
        val slot = SahneSlot(SahneSlots.newId(d, WidgetType.DRIVE_MODE), WidgetType.DRIVE_MODE)
        val e = SahneSlots.set(d, ref, slot)
        assertEquals(slot, SahneSlots.get(e, ref))
        assertEquals(d.left, e.left)
        assertEquals("drive_mode", slot.id)
        assertEquals("lock_2", SahneSlots.newId(d, WidgetType.LOCK)) // kilit zaten Park şeridinde
        assertEquals("app_2", SahneSlots.newId(SahneSlots.set(d, ref, SahneSlot("app", WidgetType.APP)), WidgetType.APP))
        assertNull(SahneSlots.get(SahneSlots.set(e, ref, null), ref))
    }

    @Test
    fun appPackagesParse() {
        assertEquals(listOf<String?>(null, null), SahneSlots.appPackages(null))
        assertEquals(listOf<String?>("a.b", null), SahneSlots.appPackages("a.b"))
        assertEquals(listOf<String?>("a.b", "c.d"), SahneSlots.appPackages("a.b+c.d"))
        assertNull(SahneSlots.appExtra(emptyList()))
        assertEquals("x+y", SahneSlots.appExtra(listOf("w", "x", "y", "z"), offset = 1))
    }

    @Test
    fun sahneStorageNeverSharesSerbestKeys() {
        assertNotEquals(LayoutKeys.prefsName(0), SahneStore.prefsName(0))
        assertNotEquals(LayoutKeys.prefsName(1, VehicleProfile.TIGGO8), SahneStore.prefsName(1, VehicleProfile.TIGGO8))
        assertNotEquals(SahneStore.key(LayoutMode.PARK), SahneStore.key(LayoutMode.DRIVE))
        assertTrue(SahneStore.key(LayoutMode.PARK) !in setOf(LayoutKeys.PARK, LayoutKeys.DRIVE))
        assertNotEquals(SahneStore.prefsName(0), SahneStore.prefsName(0, VehicleProfile.TIGGO8))
    }

    @Test
    fun homeStyleDefaultsToSahne() {
        assertEquals(HomeStyle.SAHNE, HomeStyle.parse(null))
        assertEquals(HomeStyle.SAHNE, HomeStyle.parse("bilinmeyen"))
        assertEquals(HomeStyle.SERBEST, HomeStyle.parse("serbest"))
        assertEquals(HomeStyle.SAHNE, HomeStyle.parse(" Sahne "))
    }
}
