package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilesTest {
    @Test fun profileOneKeepsExistingNames() {
        // Taşıma gerekmez: mevcut dosya/anahtar adları profil 1'e aittir.
        assertEquals("launcher_layout", Profiles.key("launcher_layout", 1))
        assertEquals("theme_mode", Profiles.key("theme_mode", 1))
        assertEquals("slot_0", Profiles.key("slot_0", 1))
    }

    @Test fun otherProfilesGetSuffix() {
        assertEquals("launcher_nav_p2", Profiles.key("launcher_nav", 2))
        assertEquals("theme_mode_p3", Profiles.key("theme_mode", 3))
    }

    @Test fun invalidIdFallsBackToProfileOne() {
        assertEquals(1, Profiles.sanitize(0))
        assertEquals(1, Profiles.sanitize(4))
        assertEquals(1, Profiles.sanitize(-7))
        assertEquals("launcher_apps", Profiles.key("launcher_apps", 99))
        assertEquals(3, Profiles.sanitize(3))
    }

    @Test fun autoSelectIsNotWiredYet() {
        assertNull(Profiles.autoSelectFromVehicle(4))
    }
}
