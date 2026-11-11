package com.mapcontrol;

import java.util.Map;

/** Test derlemesinin boş yedeği: :app'teki GlitchTip.java'nın hata raporu çağrısı, hiçbir şey göndermez. */
public final class GlitchTip {
    private GlitchTip() {}

    public static void reportWarning(String message, Map<String, String> extras) {}
}
