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
        // Telefon yalnız dört panelde (Tiggo 8) ayrı panel; Tiggo 7'de medya kartının arka yüzü (6-7 Kasım).
        val panel = setOf(WidgetType.MEDIA, WidgetType.TRIP, WidgetType.CONSUMPTION, WidgetType.NAVIGATION, WidgetType.PHONE)
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
        // Arka hoparlör yazması doğrulanmadı: Tiggo 8 seçicisinde de yok (7 Kasım).
        assertEquals(SahneForm.STRIP_TYPES - WidgetType.REAR_SPEAKER, SahneForm.offered(SahneSlotKind.STRIP, quad = true))
        assertEquals(SahneForm.PANEL_TYPES - WidgetType.PHONE, SahneForm.offered(SahneSlotKind.PANEL))
        assertEquals(SahneForm.PANEL_TYPES, SahneForm.offered(SahneSlotKind.PANEL, quad = true))
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
        var d = SahneSlots.decode("v2|air~a~|health~h~|")!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        // Medya sağda kullanımda: sol yedek medya olamaz, kullanılmayan diğer panel türü (yolculuk) gelir.
        d = SahneSlots.decode("v2|air~a~|media~m~|")!!
        assertEquals(WidgetType.TRIP, d.left?.type)
        assertEquals(WidgetType.MEDIA, d.right?.type)
        // Tüketim şeritte kullanımda olsa da yedek olabilir türler arasından kullanılmayan alınır; sağ yedeği yolculuk, sol medya.
        d = SahneSlots.decode("v2|clock~c~|health~h~|consumption~k~")!!
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(WidgetType.CONSUMPTION, d.strip[0]?.type)
        // Boş ya da bilinmeyen yuva yedek almaz.
        d = SahneSlots.decode("v2||nope~x~|")!!
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
            listOf(WidgetType.SEAT_DRIVER, WidgetType.TRUNK, WidgetType.SCREEN_OFF, WidgetType.APP, WidgetType.DRIVE_MODE, WidgetType.SEAT_PASSENGER),
            d.strip.map { it?.type },
        )
        assertEquals(listOf<String?>(apps[0], apps[1]), SahneSlots.appPackages(d.strip[3]?.extra))
        // Yolculuk kartı sürüşte canlı tüketimi gösterir: şeritte anlık tüketim yinelenmez.
        assertFalse(WidgetType.CONSUMPTION in d.strip.map { it?.type })
        // Tiggo 8 şeridinde de arka hoparlör yok (yazması doğrulanmadı, 7 Kasım): ekranı kapat.
        assertEquals(WidgetType.SCREEN_OFF, SahneSlots.defaultsQuad(LayoutMode.PARK, apps).strip[2]?.type)
        assertEquals(WidgetType.SCREEN_OFF, SahneSlots.defaultsQuad(LayoutMode.DRIVE, apps).strip[1]?.type)
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
        // Seçicide yok (Tiggo 7; doğrulanana dek Tiggo 8'de de); hiçbir düzene konamaz.
        assertFalse(WidgetType.REAR_SPEAKER in SahneForm.offered(SahneSlotKind.STRIP, quad = false))
        assertFalse(WidgetType.REAR_SPEAKER in SahneForm.offered(SahneSlotKind.STRIP, quad = true))
        val t7 = SahneSlots.defaultPark(apps)
        assertFalse(SahneForm.canPlace(t7, SahneSlotRef(SahneSlotKind.STRIP, 2), WidgetType.REAR_SPEAKER))
        assertFalse(SahneForm.canPlace(SahneSlots.defaultsQuad(LayoutMode.PARK, apps).let { SahneSlots.set(it, SahneSlotRef(SahneSlotKind.STRIP, 2), null) },
            SahneSlotRef(SahneSlotKind.STRIP, 2), WidgetType.REAR_SPEAKER))
        // Eski Park kaydı: arka hoparlör yuvası düzende olmayan ilk yedeğe (ekranı kapat) döner; yolculuk paneli varken tüketim gelmez.
        val oldPark = SahneSlots.defaultPark(apps, rearSpeaker = true)
        val park = SahneSlots.forTiggo7(SahneSlots.decode(SahneSlots.encode(oldPark))!!)
        assertEquals(WidgetType.SCREEN_OFF, park.strip[2]?.type)
        assertFalse(WidgetType.CONSUMPTION in park.types())
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
        val d = SahneSlots.decode("v2|nope~x~|trip~trip~|seat_heat_driver~s~")!!
        assertNull(d.left)
        assertEquals(WidgetType.TRIP, d.right?.type)
        assertEquals(STRIP_SLOTS, d.strip.size)
        assertEquals(WidgetType.SEAT_DRIVER, d.strip[0]?.type)
        assertTrue(d.strip.drop(1).all { it == null })
    }

    @Test
    fun removedHeatsCardEmptiesItsSlotOnDecode() {
        // Tiggo 7'de direksiyon/ön cam ısıtma donanımı yok: eski "heats" yuvası boşalır, kalanlar korunur.
        val d = SahneSlots.decode("v2|heats~h~|trip~trip~|")!!
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
        val old = "v2|media~media~|seat_vent_passenger~vp~|seat_heat_driver~seat_heat_driver~,sunshade~sunshade~,trunk~trunk~," +
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
        val d = SahneSlots.decode("v2|||seat_heat_driver~h~,seat_vent_driver~v~,,,,")!!
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
        val slot = SahneSlot(SahneSlots.newId(d, WidgetType.BREAK_REMINDER), WidgetType.BREAK_REMINDER)
        val e = SahneSlots.set(d, ref, slot)
        assertEquals(slot, SahneSlots.get(e, ref))
        assertEquals(d.left, e.left)
        assertEquals("break_reminder", slot.id)
        assertEquals("drive_mode_2", SahneSlots.newId(d, WidgetType.DRIVE_MODE)) // sürüş modu zaten Park şeridinde
        assertEquals("app_2", SahneSlots.newId(SahneSlots.set(d, ref, SahneSlot("app", WidgetType.APP)), WidgetType.APP))
        assertNull(SahneSlots.get(SahneSlots.set(e, ref, null), ref))
    }

    @Test
    fun oldLockSlotBecomesDriveModeWhenFree() {
        // Eski Park varsayılanı: kilit 5. yuvada, sürüş modu yok → kilit yerine sürüş modu.
        val old = "v2|media~media~|trip~trip~|seat_driver~seat_driver~,trunk~trunk~,rear_speaker~rear_speaker~," +
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
        val d = SahneSlots.decode("v2|||lock~lock~,drive_mode~drive_mode~,lock~lock2~,,,")!!
        assertEquals(listOf(null, WidgetType.DRIVE_MODE, null, null, null, null), d.strip.map { it?.type })
        // Sürüş modu yoksa yalnızca ilk kilit döner, ikincisi boşalır.
        val e = SahneSlots.decode("v2|||,lock~lock~,,lock~l2~,,")!!
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
    fun sahneStorageKeysPerModeAndVehicle() {
        assertNotEquals(SahneStore.key(LayoutMode.PARK), SahneStore.key(LayoutMode.DRIVE))
        assertNotEquals(SahneStore.prefsName(0), SahneStore.prefsName(0, VehicleProfile.TIGGO8))
    }

    @Test
    fun removedWidgetKeysResolveToNull() {
        // Kaldırılan kutucukların anahtarları çözülmez; eski Sahne kaydında o yuva boşalır.
        listOf("range", "tires", "doors", "sunroof", "nope").forEach { assertNull(WidgetType.fromKey(it)) }
        assertEquals(WidgetType.LOCK, WidgetType.fromKey("lock"))
    }

    // --- Dört panelli biçim (Tiggo 8, 6 Kasım) ---

    private fun panel(l: SahneLayout, i: Int) = SahneSlots.get(l, SahneSlotRef(SahneSlotKind.PANEL, i))

    @Test
    fun quadDefaultsHaveTheFourPanelTypesOnce() {
        LayoutMode.values().forEach { mode ->
            val d = SahneSlots.defaultsQuad(mode, apps)
            assertTrue(d.quad)
            assertEquals(4, d.panelCount)
            // 7 Kasım: sağ alt telefon (tüketim yolculuk kartının canlı satırını yineliyordu); medya kartı yalnız medya.
            assertEquals(listOf(WidgetType.MEDIA, WidgetType.TRIP, WidgetType.NAVIGATION, WidgetType.PHONE),
                (0..3).map { panel(d, it)?.type })
            assertFalse(WidgetType.CONSUMPTION in d.types())
            assertTrue(SahneForm.hasPhonePanel(d))
            assertEquals(SahneSlots.defaults(mode, apps).strip, d.strip)
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
        val old = SahneSlots.decode("v2|media~media~|trip~trip~|seat_driver~seat_driver~,,,,,")!!
        assertFalse(old.quad)
        val q = SahneSlots.toQuad(old)
        assertTrue(q.quad)
        assertEquals(WidgetType.MEDIA, q.left?.type)
        assertEquals(WidgetType.TRIP, q.right?.type)
        assertEquals(WidgetType.NAVIGATION, q.leftLower?.type)
        assertEquals(WidgetType.PHONE, q.rightLower?.type)
        assertEquals(old.strip, q.strip)
        // Üstte navigasyon ve tüketim zaten varsa alt yuvalar kalan türleri alır.
        val q2 = SahneSlots.toQuad(SahneSlots.decode("v2|nav_turn~n~|consumption~c~|")!!)
        assertEquals(WidgetType.MEDIA, q2.leftLower?.type)
        assertEquals(WidgetType.PHONE, q2.rightLower?.type)
    }

    @Test
    fun quadDecodeDropsDuplicatesAndFallsBackPerSlot() {
        val d = SahneSlots.decode("v2|media~m~|trip~t~||media~m2~|health~h~")!!
        assertTrue(d.quad)
        assertNull(d.leftLower) // ikinci medya atılır
        assertEquals(WidgetType.PHONE, d.rightLower?.type) // izinsiz tür sağ alt yedeğe döner
        assertNull(SahneSlots.decode("v2|media~m~|trip~t~||x"))
    }

    @Test
    fun quadPlacementRuleCoversAllFourPanels() {
        val d = SahneSlots.defaultsQuad(LayoutMode.PARK, apps)
        val leftLower = SahneSlotRef(SahneSlotKind.PANEL, 2)
        assertFalse(SahneForm.canPlace(d, leftLower, WidgetType.MEDIA))
        assertFalse(SahneForm.canPlace(d, SahneSlotRef(SahneSlotKind.PANEL, 0), WidgetType.PHONE)) // telefon zaten sağ altta
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
    @Test
    fun encodeWritesV3() {
        assertTrue(SahneSlots.encode(SahneSlots.defaultPark(apps)).startsWith("v3|"))
        assertTrue(SahneSlots.encode(SahneSlots.defaultsQuad(LayoutMode.PARK, apps)).startsWith("v3|"))
        assertNull(SahneSlots.decode("v4|media~m~|trip~t~|"))
        assertTrue(SahneSlots.isLegacy("v1|||") && SahneSlots.isLegacy("v2|||"))
        assertFalse(SahneSlots.isLegacy("v3|||"))
    }

    // --- Tiggo 8 telefon paneli (7 Kasım) ---

    @Test
    fun tiggo8SavedRearSpeakerBecomesScreenOff() {
        // 6 Kasım Tiggo 8 Park kaydı: şeritte arka hoparlör → ekranı kapat; Sürüş: aynı.
        val park = SahneSlots.forTiggo8(SahneSlots.defaultPark(apps, rearSpeaker = true).copy(quad = true,
            leftLower = SahneSlot("nav_turn", WidgetType.NAVIGATION), rightLower = SahneSlot("phone", WidgetType.PHONE)))
        assertEquals(WidgetType.SCREEN_OFF, park.strip[2]?.type)
        assertFalse(WidgetType.REAR_SPEAKER in park.types())
        assertEquals(SahneSlots.defaultsQuad(LayoutMode.PARK, apps), park)
        val drive = SahneSlots.forTiggo8(SahneSlots.defaultDrive(apps, rearSpeaker = true))
        assertEquals(WidgetType.SCREEN_OFF, drive.strip[1]?.type)
        // Ekranı kapat zaten şeritteyse mola hatırlatıcı gelir.
        val both = SahneSlots.set(SahneSlots.defaultPark(apps, rearSpeaker = true), SahneSlotRef(SahneSlotKind.STRIP, 1),
            SahneSlot("screen_off", WidgetType.SCREEN_OFF))
        assertEquals(WidgetType.BREAK_REMINDER, SahneSlots.forTiggo8(both).strip[2]?.type)
        // Arka hoparlör yoksa aynen.
        val q = SahneSlots.defaultsQuad(LayoutMode.DRIVE, apps)
        assertEquals(q, SahneSlots.forTiggo8(q))
    }

    @Test
    fun legacyQuadConsumptionBecomesPhoneOnce() {
        // 6 Kasım Tiggo 8 varsayılanı (v2): sağ alt tüketim → telefon.
        val old = "v2|media~media~|trip~trip~|seat_driver~seat_driver~,,,,,|nav_turn~nav_turn~|consumption~consumption~"
        val d = SahneSlots.decode(old)!!
        assertEquals(WidgetType.PHONE, d.rightLower?.type)
        assertEquals(WidgetType.PHONE.key, d.rightLower?.id)
        assertEquals(WidgetType.MEDIA, d.left?.type)
        assertEquals(WidgetType.NAVIGATION, d.leftLower?.type)
        // v1 dört panelli kayıt da geçer.
        assertEquals(WidgetType.PHONE, SahneSlots.decode("v1|media~m~|trip~t~|,,,,,|nav_turn~n~|consumption~c~")!!.rightLower?.type)
        // v3 kaydı: kullanıcı tüketimi geçişten sonra bilerek koymuş → dokunulmaz.
        val again = SahneSlots.encode(d.copy(rightLower = SahneSlot("consumption", WidgetType.CONSUMPTION)))
        assertTrue(again.startsWith("v3|"))
        assertEquals(WidgetType.CONSUMPTION, SahneSlots.decode(again)!!.rightLower?.type)
    }

    @Test
    fun legacyQuadPhoneMigrationLeavesOtherChoicesAlone() {
        // Sağ altta başka tür → aynen.
        assertEquals(WidgetType.NAVIGATION,
            SahneSlots.decode("v2|media~m~|trip~t~|,,,,,|consumption~c~|nav_turn~n~")!!.rightLower?.type)
        // Telefon zaten başka yuvadaysa tüketim kalır.
        val phoneUp = SahneSlots.decode("v2|media~m~|${WidgetType.PHONE.key}~p~|,,,,,|nav_turn~n~|consumption~c~")!!
        assertEquals(WidgetType.PHONE, phoneUp.right?.type)
        assertEquals(WidgetType.CONSUMPTION, phoneUp.rightLower?.type)
        // İki panelli kayıtta sağdaki tüketim değişmez.
        assertEquals(WidgetType.CONSUMPTION, SahneSlots.decode("v2|media~m~|consumption~c~|,,,,,")!!.right?.type)
    }

    @Test
    fun quadKeepsPhonePanelsTiggo7DropsThem() {
        val quad = SahneLayout(SahneSlot("phone", WidgetType.PHONE), SahneSlot("trip", WidgetType.TRIP), SahneSlots.defaultPark().strip,
            quad = true, leftLower = SahneSlot("nav_turn", WidgetType.NAVIGATION), rightLower = SahneSlot("media", WidgetType.MEDIA))
        assertEquals(quad, SahneSlots.migratePhonePanel(quad))
        assertEquals(quad, SahneSlots.sanitize(quad))
        assertEquals(WidgetType.PHONE, SahneSlots.decode(SahneSlots.encode(quad))!!.left?.type)
        // Tiggo 7'de telefon paneli seçilemez ve temizlemede kalmaz.
        val t7 = SahneSlots.defaultPark(apps)
        assertFalse(SahneForm.canPlace(t7, SahneSlotRef(SahneSlotKind.PANEL, 1), WidgetType.PHONE))
        assertTrue(SahneForm.canPlace(SahneSlots.defaultsQuad(LayoutMode.PARK, apps).copy(rightLower = null),
            SahneSlotRef(SahneSlotKind.PANEL, 3), WidgetType.PHONE))
        assertNull(SahneSlots.sanitize(t7.copy(right = SahneSlot("phone", WidgetType.PHONE))).right)
        assertFalse(SahneForm.hasPhonePanel(t7))
    }

    @Test
    fun v1TripLayoutLosesStripConsumptionOnce() {
        // Eski Park varsayılanı (v1): tüketim şeritte, sağda yolculuk → tüketim yuvası ekranı kapata döner.
        val old = "v1|media~media~|trip~trip~|seat_driver~seat_driver~,trunk~trunk~,consumption~consumption~," +
            "app~app_pair~a.b+c.d,drive_mode~drive_mode~,seat_passenger~seat_passenger~"
        val d = SahneSlots.decode(old)!!
        assertEquals(SahneSlots.defaultPark(listOf("a.b", "c.d")), d)
        // Yeniden yazılınca v3 olur ve bir daha dokunulmaz.
        val again = SahneSlots.encode(d)
        assertTrue(again.startsWith("v3|"))
        assertEquals(d, SahneSlots.decode(again))
    }

    @Test
    fun v1MigrationUsesBreakReminderWhenScreenOffIsTaken() {
        val d = SahneSlots.decode("v1|media~m~|trip~t~|screen_off~s~,consumption~c~,,,,")!!
        assertEquals(listOf(WidgetType.SCREEN_OFF, WidgetType.BREAK_REMINDER, null, null, null, null), d.strip.map { it?.type })
        assertEquals("break_reminder", d.strip[1]?.id)
        // İkisi de düzendeyse tüketim kalır.
        val e = SahneSlots.decode("v1|media~m~|trip~t~|screen_off~s~,consumption~c~,break_reminder~b~,,,")!!
        assertEquals(WidgetType.CONSUMPTION, e.strip[1]?.type)
    }

    @Test
    fun v1MigrationNeedsTripPanelAndV2IsNeverTouched() {
        // Yolculuk paneli yok (eski Sürüş varsayılanı: sağda tüketim paneli) → aynen.
        val drive = SahneSlots.decode("v1|media~m~|nav_turn~n~|consumption~c~,,,,,")!!
        assertEquals(WidgetType.CONSUMPTION, drive.strip[0]?.type)
        // v2 kaydı: kullanıcı tüketimi bilerek geri eklemiş olabilir → dokunulmaz.
        val v2 = SahneSlots.decode("v2|media~m~|trip~t~|consumption~c~,,,,,")!!
        assertEquals(WidgetType.CONSUMPTION, v2.strip[0]?.type)
        // Dört panelli v1: yolculuk sağ üstte; şeritteki tüketim ekranı kapata döner.
        val quad = SahneSlots.decode("v1|media~m~|trip~t~|consumption~c~,,,,,|nav_turn~n~|")!!
        assertEquals(WidgetType.SCREEN_OFF, quad.strip[0]?.type)
        // Saf geçiş: yolculuk solda da sayılır.
        val left = SahneSlots.decode("v2|trip~t~|media~m~|,,consumption~c~,,,")!!
        assertEquals(WidgetType.SCREEN_OFF, SahneSlots.migrateTripConsumption(left).strip[2]?.type)
    }

    @Test
    fun tiggo7ReplacementSkipsConsumptionWithTripPanel() {
        // Yedeklerin ilk üçü düzende: tüketim sırası gelse de yolculuk paneli varken konmaz, yuva boşalır.
        var d = SahneSlots.defaultPark(apps, rearSpeaker = true)
        listOf(WidgetType.SCREEN_OFF, WidgetType.BREAK_REMINDER, WidgetType.PHONE_REMINDER).forEachIndexed { i, t ->
            d = SahneSlots.set(d, SahneSlotRef(SahneSlotKind.STRIP, listOf(0, 1, 4)[i]), SahneSlot(t.key, t))
        }
        assertNull(SahneSlots.forTiggo7(d).strip[2])
        // Yolculuk paneli yoksa tüketim yedek olabilir.
        val noTrip = SahneSlots.set(d, SahneSlotRef(SahneSlotKind.PANEL, 1), SahneSlot("nav_turn", WidgetType.NAVIGATION))
        assertEquals(WidgetType.CONSUMPTION, SahneSlots.forTiggo7(noTrip).strip[2]?.type)
    }
}
