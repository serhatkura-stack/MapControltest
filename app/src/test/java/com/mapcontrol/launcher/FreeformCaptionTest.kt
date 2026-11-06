package com.mapcontrol.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformCaptionTest {
    @Test fun hidesWhenWindowSitsRightUnderStatusBar() {
        // Sistem bölme sınırlarını kırptı: pencere 70'te, başlık ekranda → gizle.
        assertTrue(FreeformCaption.shouldHide(windowTop = 70, usableTop = 70))
        assertTrue(FreeformCaption.shouldHide(windowTop = 71, usableTop = 70))
    }

    @Test fun keepsWhenCaptionAlreadyTuckedUnderStatusBar() {
        // Bölme başlığı durum çubuğunun altına itti (28): gizlersek içerik durum çubuğunun altına kayardı.
        assertFalse(FreeformCaption.shouldHide(windowTop = 28, usableTop = 70))
    }

    @Test fun keepsForManuallyPlacedWindowsOrUnknownStatusBar() {
        assertFalse(FreeformCaption.shouldHide(windowTop = 200, usableTop = 70))
        assertFalse(FreeformCaption.shouldHide(windowTop = 0, usableTop = -1))
    }
}
