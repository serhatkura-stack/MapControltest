package com.mapcontrol.launcher

import com.mapcontrol.launcher.MediaFlipLogic.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Medya/telefon kartı: yüz kuralları, kalıcılık, kaydırma kararı; telefon panelinin düzenden kalkışı ve bant yerleşimi. */
class MediaFlipLogicTest {

    @Test
    fun noPhoneMeansMediaFaceAndHiddenFlip() {
        assertEquals(CardFace.MEDIA, MediaFlipLogic.shown(CardFace.PHONE, connected = false))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.shown(CardFace.MEDIA, connected = false))
        assertEquals(CardFace.PHONE, MediaFlipLogic.shown(CardFace.PHONE, connected = true))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.shown(CardFace.MEDIA, connected = true))
        assertFalse(MediaFlipLogic.flipAvailable(false))
        assertTrue(MediaFlipLogic.flipAvailable(true))
    }

    @Test
    fun disconnectAfterSeenConnectedFallsBackToMediaAndIsRemembered() {
        // Telefon yüzündeyken koptu: kayıt medyaya döner (geri bağlanınca kart kendiliğinden telefona dönmez).
        assertEquals(CardFace.MEDIA, MediaFlipLogic.savedAfter(CardFace.PHONE, wasConnected = true, connected = false))
        // İlk bildirim (durum bilinmiyor) kaydı değiştirmez: açılışta son yüz korunur.
        assertEquals(CardFace.PHONE, MediaFlipLogic.savedAfter(CardFace.PHONE, wasConnected = null, connected = false))
        assertEquals(CardFace.PHONE, MediaFlipLogic.savedAfter(CardFace.PHONE, wasConnected = null, connected = true))
        // Bağlanma ya da bağlı kalma (ör. arama bitti) kaydı değiştirmez.
        assertEquals(CardFace.PHONE, MediaFlipLogic.savedAfter(CardFace.PHONE, wasConnected = false, connected = true))
        assertEquals(CardFace.PHONE, MediaFlipLogic.savedAfter(CardFace.PHONE, wasConnected = true, connected = true))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.savedAfter(CardFace.MEDIA, wasConnected = true, connected = true))
    }

    @Test
    fun faceRoundTripsThroughStorageAndUnknownIsMedia() {
        CardFace.values().forEach { assertEquals(it, MediaFlipLogic.decode(MediaFlipLogic.encode(it))) }
        assertEquals(CardFace.MEDIA, MediaFlipLogic.decode(null))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.decode(""))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.decode("PHONE?"))
        assertEquals(CardFace.PHONE, MediaFlipLogic.other(CardFace.MEDIA))
        assertEquals(CardFace.MEDIA, MediaFlipLogic.other(CardFace.PHONE))
    }

    @Test
    fun iconGlowsOnlyOnPhoneFaceWhilePlaying() {
        assertTrue(MediaFlipLogic.iconGlows(CardFace.PHONE, playing = true))
        assertFalse(MediaFlipLogic.iconGlows(CardFace.PHONE, playing = false))
        assertFalse(MediaFlipLogic.iconGlows(CardFace.MEDIA, playing = true))
        assertFalse(MediaFlipLogic.iconGlows(CardFace.MEDIA, playing = false))
    }

    private fun swipe(dx: Float, dy: Float, onDrag: Boolean = false, enabled: Boolean = true) =
        MediaFlipLogic.swipe(dx, dy, slopPx = 10f, flipPx = 72f, startsOnDrag = onDrag, enabled = enabled)

    @Test
    fun horizontalSwipeFlipsVerticalAndSeekBarDoNot() {
        assertEquals(Swipe.WAIT, swipe(4f, 1f))
        assertEquals(Swipe.CAPTURE, swipe(20f, 2f))
        assertEquals(Swipe.FLIP, swipe(80f, 10f))
        assertEquals(Swipe.FLIP, swipe(-80f, -10f))
        // Dikey kayma: bırakılır (kaydırma alanları ve sayfa hareketi çalışır).
        assertEquals(Swipe.IGNORE, swipe(5f, 30f))
        // Çapraz (yatay dikeyin 1,5 katı değil): çevirmez.
        assertEquals(Swipe.IGNORE, swipe(80f, 70f))
        // İlerleme çubuğunda başlayan dokunuş hiçbir mesafede çevirmez.
        assertEquals(Swipe.IGNORE, swipe(200f, 0f, onDrag = true))
        // Bağlı telefon yok: çevirme kapalı.
        assertEquals(Swipe.IGNORE, swipe(200f, 0f, enabled = false))
        assertEquals(-1, MediaFlipLogic.direction(-50f))
        assertEquals(1, MediaFlipLogic.direction(50f))
        assertEquals(-1, MediaFlipLogic.direction(0f))
    }

    @Test
    fun hitTestIsHalfOpenAndEmptyRectNeverHits() {
        assertTrue(MediaFlipLogic.hits(10f, 10f, 10, 10, 20, 20))
        assertTrue(MediaFlipLogic.hits(19.9f, 19.9f, 10, 10, 20, 20))
        assertFalse(MediaFlipLogic.hits(20f, 15f, 10, 10, 20, 20))
        assertFalse(MediaFlipLogic.hits(15f, 9f, 10, 10, 20, 20))
        assertFalse(MediaFlipLogic.hits(10f, 10f, 10, 10, 10, 20))
    }

    // --- Telefon panelinin düzenden kalkışı ---

    private fun layout(left: WidgetType?, right: WidgetType?) = SahneLayout(
        left = left?.let { SahneSlot(it.key, it) },
        right = right?.let { SahneSlot(it.key, it) },
        strip = SahneSlots.defaultPark().strip,
    )

    @Test
    fun rightPhonePanelIsRemovedWhenMediaIsOnTheLeft() {
        val out = SahneSlots.migratePhonePanel(layout(WidgetType.MEDIA, WidgetType.PHONE))
        assertEquals(WidgetType.MEDIA, out.left?.type)
        assertNull(out.right)
        // Kayıttan okunurken de (telefon artık panel türü değil, sağ boş kalır; yolculuk eklenmez).
        val decoded = SahneSlots.decode(SahneSlots.encode(layout(WidgetType.MEDIA, WidgetType.PHONE)))!!
        assertEquals(WidgetType.MEDIA, decoded.left?.type)
        assertNull(decoded.right)
        assertFalse(SahneForm.fitsPanel(WidgetType.PHONE))
        assertFalse(WidgetType.PHONE in SahneForm.offered(SahneSlotKind.PANEL))
    }

    @Test
    fun phonePanelBecomesMediaWhenNoMediaPanelExists() {
        val out = SahneSlots.migratePhonePanel(layout(WidgetType.NAVIGATION, WidgetType.PHONE))
        assertEquals(WidgetType.NAVIGATION, out.left?.type)
        assertEquals(WidgetType.MEDIA, out.right?.type)
        val left = SahneSlots.migratePhonePanel(layout(WidgetType.PHONE, WidgetType.TRIP))
        assertEquals(WidgetType.MEDIA, left.left?.type)
        assertEquals(WidgetType.TRIP, left.right?.type)
        val unchanged = layout(WidgetType.MEDIA, WidgetType.TRIP)
        assertEquals(unchanged, SahneSlots.migratePhonePanel(unchanged))
    }

    @Test
    fun emptySideCollapsesOutsideEditingOnTwoPanelLayoutOnly() {
        val l = layout(WidgetType.MEDIA, null)
        assertTrue(SahneBand.occupies(l, 0, editing = false))
        assertFalse(SahneBand.occupies(l, 1, editing = false))
        // Düzenlemede boş yuva "+" ile görünür.
        assertTrue(SahneBand.occupies(l, 1, editing = true))
        // Dört panelde her yuva yerinde.
        val q = SahneSlots.defaultsQuad(LayoutMode.PARK).copy(right = null)
        assertTrue(SahneBand.occupies(q, 1, editing = false))
        assertTrue(SahneBand.occupies(SahneSlots.defaultPark(), 1, editing = false))
    }
}
