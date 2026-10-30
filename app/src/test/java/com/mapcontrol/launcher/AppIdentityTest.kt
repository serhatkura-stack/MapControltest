package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {

    private fun placed(id: String, type: WidgetType) = PlacedWidget(id, type, 0, 0, type.sizes.first())

    @Test
    fun packageLikeLabelsFallBackToPrettyLastSegment() {
        val pkg = "com.desaysv.vehiclesetting"
        assertEquals("Vehiclesetting", AppIdentity.displayLabel(pkg, pkg))
        assertEquals("Vehiclesetting", AppIdentity.displayLabel(pkg, ""))
        assertEquals("Vehiclesetting", AppIdentity.displayLabel(pkg, "com.desaysv.vehiclesetti"))
        assertEquals("Some app", AppIdentity.displayLabel("com.x.some_app", "com.x.some_app"))
    }

    @Test
    fun realLabelsAreKept() {
        assertEquals("Ses kaynağı", AppIdentity.displayLabel("com.desaysv.svaudioapp", " Ses kaynağı "))
        assertEquals("Video", AppIdentity.displayLabel("com.desaysv.videoapp", "Video"))
        assertFalse(AppIdentity.looksLikePackage("Spotify", "com.spotify.music"))
        assertFalse(AppIdentity.looksLikePackage("Maps v2.0", "com.x.maps"))
        assertTrue(AppIdentity.looksLikePackage("com.x.maps", "other"))
    }

    @Test
    fun overrideWinsAndKnownPackagesAreListed() {
        assertEquals("Araç ayarları", AppIdentity.displayLabel("com.desaysv.vehiclesetting", "x", "Araç ayarları"))
        assertTrue(AppIdentity.OVERRIDES.containsKey("com.desaysv.vehiclesetting"))
        assertTrue(AppIdentity.OVERRIDES.containsKey("com.desaysv.bluetooth.phone"))
    }

    @Test
    fun onScreenWidgetsAreNotAddableAgainButAppIs() {
        val layout = listOf(
            placed("a", WidgetType.MEDIA), placed("b", WidgetType.SEAT_HEAT_DRIVER),
            placed("c", WidgetType.APP), placed("d", WidgetType.APP),
        )
        val addable = addableWidgetTypes(layout)
        assertFalse(WidgetType.MEDIA in addable)
        assertFalse(WidgetType.SEAT_HEAT_DRIVER in addable)
        assertTrue(WidgetType.SEAT_HEAT_PASSENGER in addable)
        assertTrue(WidgetType.APP in addable)
        assertFalse(isAddable(WidgetType.MEDIA, layout))
    }

    @Test
    fun existingDuplicatesDoNotBreakLogic() {
        val layout = listOf(placed("a", WidgetType.LOCK), placed("b", WidgetType.LOCK))
        assertFalse(isAddable(WidgetType.LOCK, layout))
        assertEquals(setOf(WidgetType.LOCK), onScreenTypes(layout))
        assertTrue(LayoutStore.isValid(listOf(
            PlacedWidget("a", WidgetType.LOCK, 0, 0, Span(2, 2)),
            PlacedWidget("b", WidgetType.LOCK, 2, 0, Span(2, 2)))))
    }
}
