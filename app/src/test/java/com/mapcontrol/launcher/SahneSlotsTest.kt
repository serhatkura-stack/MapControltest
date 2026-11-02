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
    fun everyWidgetTypeHasAPanelOrStripForm() {
        WidgetType.values().forEach { t ->
            assertTrue("$t sahnede yer bulamıyor", SahneForm.fitsPanel(t) || SahneForm.fitsStrip(t))
        }
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
    }

    @Test
    fun parkDefaultMatchesTheApprovedDesign() {
        val d = SahneSlots.defaultPark(apps)
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(
            listOf(WidgetType.SEAT_HEAT_DRIVER, WidgetType.SUNSHADE, WidgetType.TRUNK, WidgetType.DEFROST, WidgetType.APP, WidgetType.SEAT_HEAT_PASSENGER),
            d.strip.map { it?.type },
        )
        assertEquals(listOf<String?>(apps[0], apps[1]), SahneSlots.appPackages(d.strip[4]?.extra))
    }

    @Test
    fun driveDefaultHasNoParkOnlyWidgets() {
        val d = SahneSlots.defaultDrive(apps)
        assertEquals(STRIP_SLOTS, d.strip.size)
        assertTrue(d.slots().none { it.type.parkOnly })
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.REAR_SPEAKER, d.strip[1]?.type)
        assertEquals(WidgetType.BREAK_REMINDER, d.strip[4]?.type)
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
        assertEquals(WidgetType.SEAT_HEAT_DRIVER, d.strip[0]?.type)
        assertTrue(d.strip.drop(1).all { it == null })
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
    fun visibleForModeHidesParkOnlyWhileDriving() {
        val d = SahneSlots.set(SahneSlots.defaultPark(apps), SahneSlotRef(SahneSlotKind.PANEL, 1), SahneSlot("photo", WidgetType.PHOTO))
        assertEquals(WidgetType.PHOTO, SahneSlots.visibleForMode(d, LayoutMode.PARK).right?.type)
        assertNull(SahneSlots.visibleForMode(d, LayoutMode.DRIVE).right)
        assertEquals(d.left, SahneSlots.visibleForMode(d, LayoutMode.DRIVE).left)
    }

    @Test
    fun placementRules() {
        val d = SahneSlots.defaultPark(apps)
        val right = SahneSlotRef(SahneSlotKind.PANEL, 1)
        val strip0 = SahneSlotRef(SahneSlotKind.STRIP, 0)
        // Ekranda başka yuvada olan tekil tür konamaz; kendi yerine yeniden konabilir.
        assertFalse(SahneForm.canPlace(d, right, WidgetType.MEDIA, LayoutMode.PARK))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.TRIP, LayoutMode.PARK))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.HEALTH, LayoutMode.PARK))
        // Biçim ve mod kuralları.
        assertFalse(SahneForm.canPlace(d, right, WidgetType.APP, LayoutMode.PARK))
        assertTrue(SahneForm.canPlace(d, strip0, WidgetType.APP, LayoutMode.PARK))
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.PHOTO, LayoutMode.PARK))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.PHOTO, LayoutMode.DRIVE))
        assertTrue(SahneForm.canPlace(d, right, WidgetType.PHOTO, LayoutMode.PARK))
    }

    @Test
    fun setGetAndNewIds() {
        val d = SahneSlots.defaultPark(apps)
        val ref = SahneSlotRef(SahneSlotKind.STRIP, 3)
        val slot = SahneSlot(SahneSlots.newId(d, WidgetType.LOCK), WidgetType.LOCK)
        val e = SahneSlots.set(d, ref, slot)
        assertEquals(slot, SahneSlots.get(e, ref))
        assertEquals(d.left, e.left)
        assertEquals("lock", slot.id)
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
