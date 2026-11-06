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
            WidgetType.DRIVE_MODE, WidgetType.BREAK_REMINDER, WidgetType.SCREEN_OFF, WidgetType.PHONE_REMINDER,
            WidgetType.CONSUMPTION, WidgetType.WEB_PANEL, WidgetType.EQUALIZER, WidgetType.AUTOMATION, WidgetType.SPLIT_SCREEN,
            WidgetType.CLUSTER_MIRROR,
        )
        val panel = setOf(WidgetType.MEDIA, WidgetType.TRIP, WidgetType.CONSUMPTION, WidgetType.NAVIGATION)
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
        assertEquals(SahneForm.STRIP_TYPES - WidgetType.REAR_SPEAKER, SahneForm.offered(SahneSlotKind.STRIP))
        assertEquals(SahneForm.STRIP_TYPES, SahneForm.offered(SahneSlotKind.STRIP, quad = true))
        assertEquals(SahneForm.PANEL_TYPES, SahneForm.offered(SahneSlotKind.PANEL))
    }

    @Test
    fun appIsStripOnlyAndRemovedPanelTypesAreOut() {
        assertTrue(SahneForm.fitsStrip(WidgetType.APP))
        assertFalse(SahneForm.fitsPanel(WidgetType.APP))
        listOf(WidgetType.PHOTO, WidgetType.HEALTH, WidgetType.AIR, WidgetType.SEAT_DRIVER, WidgetType.CLOCK).forEach {
            assertFalse("$it", SahneForm.fitsPanel(it))
        }
        assertFalse(SahneForm.fitsStrip(WidgetType.PHOTO))
    }

    @Test
    fun disallowedPanelFallsBackToSideDefaultOrOtherFreeType() {
        // Sol: hava (artık yok) -> medya; sağ: sağlık -> yolculuk.
        var d = SahneSlots.decode("v1|air~a~|health~h~|")!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        // Medya sağda kullanımda: sol yedek medya olamaz, kullanılmayan diğer panel türü (yolculuk) gelir.
        d = SahneSlots.decode("v1|air~a~|media~m~|")!!
        assertEquals(WidgetType.TRIP, d.left?.type)
        assertEquals(WidgetType.MEDIA, d.right?.type)
        // Tüketim şeritte kullanımda olsa da yedek olabilir türler arasından kullanılmayan alınır; sağ yedeği yolculuk, sol medya.
        d = SahneSlots.decode("v1|clock~c~|health~h~|consumption~k~")!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(WidgetType.CONSUMPTION, d.strip[0]?.type)
        // Boş ya da bilinmeyen yuva yedek almaz.
        d = SahneSlots.decode("v1||nope~x~|")!!
        assertNull(d.left)
        assertNull(d.right)
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
            listOf(WidgetType.SEAT_DRIVER, WidgetType.TRUNK, WidgetType.CONSUMPTION, WidgetType.APP, WidgetType.DRIVE_MODE, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
        assertEquals(listOf<String?>(apps[0], apps[1]), SahneSlots.appPackages(d.strip[3]?.extra))
        // Tiggo 8 şeridinde arka hoparlör duruyor.
        assertEquals(WidgetType.REAR_SPEAKER, SahneSlots.defaultsQuad(LayoutMode.PARK, apps).strip[2]?.type)
    }

    @Test
    fun driveDefaultMatchesTheOwnerDecision() {
        val d = SahneSlots.defaultDrive(apps)
        assertEquals(STRIP_SLOTS, d.strip.size)
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.CONSUMPTION, d.right?.type)
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, WidgetType.SCREEN_OFF, WidgetType.APP, WidgetType.BREAK_REMINDER, WidgetType.DRIVE_MODE, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
    }

    @Test
    fun rearSpeakerHiddenOnTiggo7() {
        // Seçicide yok (Tiggo 7), Tiggo 8'de var; Tiggo 7 düzenine konamaz.
        assertFalse(WidgetType.REAR_SPEAKER in SahneForm.offered(SahneSlotKind.STRIP, quad = false))
        assertTrue(WidgetType.REAR_SPEAKER in SahneForm.offered(SahneSlotKind.STRIP, quad = true))
        val t7 = SahneSlots.defaultPark(apps)
        assertFalse(SahneForm.canPlace(t7, SahneSlotRef(SahneSlotKind.STRIP, 2), WidgetType.REAR_SPEAKER))
        assertTrue(SahneForm.canPlace(SahneSlots.defaultsQuad(LayoutMode.PARK, apps).let { SahneSlots.set(it, SahneSlotRef(SahneSlotKind.STRIP, 2), null) },
            SahneSlotRef(SahneSlotKind.STRIP, 2), WidgetType.REAR_SPEAKER))
        // Eski Park kaydı: arka hoparlör yuvası düzende olmayan ilk yedeğe (anlık tüketim) döner.
        val oldPark = SahneSlots.defaultPark(apps, rearSpeaker = true)
        val park = SahneSlots.forTiggo7(SahneSlots.decode(SahneSlots.encode(oldPark))!!)
        assertEquals(WidgetType.CONSUMPTION, park.strip[2]?.type)
        assertFalse(WidgetType.REAR_SPEAKER in park.types())
        // Eski Sürüş kaydı: tüketim panelde olduğundan ekranı kapat gelir.
        val drive = SahneSlots.forTiggo7(SahneSlots.defaultDrive(apps, rearSpeaker = true))
        assertEquals(WidgetType.SCREEN_OFF, drive.strip[1]?.type)
        assertEquals(drive, SahneSlots.sanitize(drive))
        // Yedeklerin hepsi düzendeyse yuva boşalır.
        var full = SahneSlots.defaultPark(apps, rearSpeaker = true)
        SahneForm.TIGGO7_REPLACEMENTS.forEachIndexed { i, type ->
            val ref = SahneSlotRef(SahneSlotKind.STRIP, listOf(0, 1, 4, 5)[i])
            full = SahneSlots.set(full, ref, SahneSlot(type.key, type))
        }
        assertNull(SahneSlots.forTiggo7(full).strip[2])
        // Gizli tür yoksa kayıt aynen kalır.
        assertEquals(t7, SahneSlots.forTiggo7(t7))
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
        assertNull(c.strip[5]) // kilit artık şeritte yok (sahnedeki hap)
        assertEquals(STRIP_SLOTS, c.strip.size)
    }

    @Test
    fun oldRecordMigratesSeatsAndDropsRemovedWidgets() {
        // Eski Park varsayılanı: ısıtma sürücü · perde · bagaj · buğu · uygulamalar · ısıtma yolcu (+ panelde soğutma yolcu).
        val old = "v1|media~media~|seat_vent_passenger~vp~|seat_heat_driver~seat_heat_driver~,sunshade~sunshade~,trunk~trunk~," +
            "defrost~defrost~,app~app_pair~a.b+c.d,seat_heat_passenger~seat_heat_passenger~"
        val d = SahneSlots.decode(old)!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type) // yolcu koltuğu artık panelde yok: sağ yedeği yolculuk
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, null, WidgetType.TRUNK, null, WidgetType.APP, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        ) // perde ve buğu boşaldı; panelden düşen yolcu koltuğu yolculuğa döndü, şeritteki yolcu koltuğu kaldı
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
        assertTrue(SahneForm.canPlace(d, right, WidgetType.NAVIGATION))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.HEALTH))
        // Biçim kuralları (seçki); Park/Sürüş ayrımı yok, fotoğraf da sürüşte konabilir.
        assertFalse(SahneForm.canPlace(d, right, WidgetType.APP))
        assertTrue(SahneForm.canPlace(d, strip0, WidgetType.APP))
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.PHOTO))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.PHOTO))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.LOCK))     // kilit hiçbir yuvada yok (sahnedeki hap)
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.LOCK))
        assertFalse(SahneForm.canPlace(d, strip0, WidgetType.HEALTH))  // sağlık yalnızca panelde
        assertFalse(SahneForm.canPlace(d, right, WidgetType.VEHICLE))
        // Koltuk artık yalnız şeritte.
        val noSeats = SahneSlots.set(d, strip0, null)
        assertFalse(SahneForm.canPlace(noSeats, right, WidgetType.SEAT_DRIVER))
        assertFalse(SahneForm.canPlace(d, right, WidgetType.SEAT_PASSENGER))
    }

    @Test
    fun setGetAndNewIds() {
        val d = SahneSlots.defaultPark(apps)
        val ref = SahneSlotRef(SahneSlotKind.STRIP, 3)
        val slot = SahneSlot(SahneSlots.newId(d, WidgetType.SCREEN_OFF), WidgetType.SCREEN_OFF)
        val e = SahneSlots.set(d, ref, slot)
        assertEquals(slot, SahneSlots.get(e, ref))
        assertEquals(d.left, e.left)
        assertEquals("screen_off", slot.id)
        assertEquals("drive_mode_2", SahneSlots.newId(d, WidgetType.DRIVE_MODE)) // sürüş modu zaten Park şeridinde
        assertEquals("app_2", SahneSlots.newId(SahneSlots.set(d, ref, SahneSlot("app", WidgetType.APP)), WidgetType.APP))
        assertNull(SahneSlots.get(SahneSlots.set(e, ref, null), ref))
    }

    @Test
    fun oldLockSlotBecomesDriveModeWhenFree() {
        // Eski Park varsayılanı: kilit 5. yuvada, sürüş modu yok → kilit yerine sürüş modu.
        val old = "v1|media~media~|trip~trip~|seat_driver~seat_driver~,trunk~trunk~,rear_speaker~rear_speaker~," +
            "app~app_pair~a.b+c.d,lock~lock~,seat_passenger~seat_passenger~"
        val d = SahneSlots.decode(old)!!
        assertEquals(
            listOf(WidgetType.SEAT_DRIVER, WidgetType.TRUNK, WidgetType.REAR_SPEAKER, WidgetType.APP, WidgetType.DRIVE_MODE, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
        assertEquals("drive_mode", d.strip[4]?.id)
        assertEquals(SahneSlots.defaultPark(listOf("a.b", "c.d"), rearSpeaker = true), d)
        assertEquals(d, SahneSlots.decode(SahneSlots.encode(d)))
    }

    @Test
    fun oldLockSlotEmptiesWhenDriveModeIsPlaced() {
        val d = SahneSlots.decode("v1|||lock~lock~,drive_mode~drive_mode~,lock~lock2~,,,")!!
        assertEquals(listOf(null, WidgetType.DRIVE_MODE, null, null, null, null), d.strip.map { it?.type })
        // Sürüş modu yoksa yalnızca ilk kilit döner, ikincisi boşalır.
        val e = SahneSlots.decode("v1|||,lock~lock~,,lock~l2~,,")!!
        assertEquals(listOf(null, WidgetType.DRIVE_MODE, null, null, null, null), e.strip.map { it?.type })
    }

    @Test
    fun migrateLockLeavesLayoutsWithoutLockUntouched() {
        val d = SahneSlots.defaultDrive(apps)
        assertTrue(d === SahneSlots.migrateLock(d))
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

    // --- Dört panelli biçim (Tiggo 8, 6 Kasım) ---

    private fun panel(l: SahneLayout, i: Int) = SahneSlots.get(l, SahneSlotRef(SahneSlotKind.PANEL, i))

    @Test
    fun quadDefaultsHaveTheFourPanelTypesOnce() {
        LayoutMode.values().forEach { mode ->
            val d = SahneSlots.defaultsQuad(mode, apps)
            assertTrue(d.quad)
            assertEquals(4, d.panelCount)
            assertEquals(listOf(WidgetType.MEDIA, WidgetType.TRIP, WidgetType.NAVIGATION, WidgetType.CONSUMPTION),
                (0..3).map { panel(d, it)?.type })
            assertEquals(SahneForm.PANEL_TYPES.toSet(), (0..3).mapNotNull { panel(d, it)?.type }.toSet())
            assertEquals(SahneSlots.defaults(mode, apps, rearSpeaker = true).strip, d.strip)
            assertEquals(d, SahneSlots.sanitize(d))
        }
    }

    @Test
    fun twoPanelRecordStaysInTheOldFormat() {
        val d = SahneSlots.defaultPark(apps)
        assertFalse(d.quad)
        val raw = SahneSlots.encode(d)
        assertEquals(4, raw.split("|").size)
        // Tiggo 7'de alt yuvalar yazılamaz ve okunmaz.
        assertEquals(d, SahneSlots.set(d, SahneSlotRef(SahneSlotKind.PANEL, 2), SahneSlot("nav_turn", WidgetType.NAVIGATION)))
        assertNull(panel(d, 3))
    }

    @Test
    fun quadRoundTripKeepsEmptyLowerSlots() {
        val d = SahneSlots.defaultsQuad(LayoutMode.PARK, apps)
        val raw = SahneSlots.encode(d)
        assertEquals(6, raw.split("|").size)
        assertEquals(d, SahneSlots.decode(raw))
        val gaps = SahneSlots.set(SahneSlots.set(d, SahneSlotRef(SahneSlotKind.PANEL, 2), null), SahneSlotRef(SahneSlotKind.PANEL, 3), null)
        val back = SahneSlots.decode(SahneSlots.encode(gaps))!!
        assertTrue(back.quad) // boşaltılmış alt yuvalar yeniden doldurulmaz
        assertNull(back.leftLower)
        assertNull(back.rightLower)
        assertEquals(back, SahneSlots.toQuad(back))
    }

    @Test
    fun oldTwoPanelRecordUpgradesToQuad() {
        val old = SahneSlots.decode("v1|media~media~|trip~trip~|seat_driver~seat_driver~,,,,,")!!
        assertFalse(old.quad)
        val q = SahneSlots.toQuad(old)
        assertTrue(q.quad)
        assertEquals(WidgetType.MEDIA, q.left?.type)
        assertEquals(WidgetType.TRIP, q.right?.type)
        assertEquals(WidgetType.NAVIGATION, q.leftLower?.type)
        assertEquals(WidgetType.CONSUMPTION, q.rightLower?.type)
        assertEquals(old.strip, q.strip)
        // Üstte navigasyon ve tüketim zaten varsa alt yuvalar kalan türleri alır.
        val q2 = SahneSlots.toQuad(SahneSlots.decode("v1|nav_turn~n~|consumption~c~|")!!)
        assertEquals(WidgetType.MEDIA, q2.leftLower?.type)
        assertEquals(WidgetType.TRIP, q2.rightLower?.type)
    }

    @Test
    fun quadDecodeDropsDuplicatesAndFallsBackPerSlot() {
        val d = SahneSlots.decode("v1|media~m~|trip~t~||media~m2~|health~h~")!!
        assertTrue(d.quad)
        assertNull(d.leftLower) // ikinci medya atılır
        assertEquals(WidgetType.CONSUMPTION, d.rightLower?.type) // izinsiz tür sağ alt yedeğe döner
        assertNull(SahneSlots.decode("v1|media~m~|trip~t~||x"))
    }

    @Test
    fun quadPlacementRuleCoversAllFourPanels() {
        val d = SahneSlots.defaultsQuad(LayoutMode.PARK, apps)
        val leftLower = SahneSlotRef(SahneSlotKind.PANEL, 2)
        assertFalse(SahneForm.canPlace(d, leftLower, WidgetType.MEDIA))
        assertFalse(SahneForm.canPlace(d, SahneSlotRef(SahneSlotKind.PANEL, 0), WidgetType.CONSUMPTION))
        assertTrue(SahneForm.canPlace(d, leftLower, WidgetType.NAVIGATION))
        val freed = SahneSlots.set(d, SahneSlotRef(SahneSlotKind.PANEL, 3), null)
        assertTrue(SahneForm.canPlace(freed, leftLower, WidgetType.CONSUMPTION))
        assertEquals(WidgetType.TRIP, SahneSlots.get(SahneSlots.set(freed, leftLower, SahneSlot("trip2", WidgetType.TRIP)), leftLower)?.type)
    }

    @Test
    fun panelContentShrinksOnlyWhenShorterThanItsDesign() {
        assertEquals(1f, PanelFit.scale(567, 567), 0f) // Tiggo 7: tasarım boyu, küçültme yok
        assertEquals(1f, PanelFit.scale(600, 567), 0f)
        assertEquals(1f, PanelFit.scale(300, 0), 0f) // kapalı
        assertEquals(469f / 567f, PanelFit.scale(469, 567), 1e-6f)
    }
}
