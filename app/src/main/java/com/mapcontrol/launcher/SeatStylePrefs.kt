package com.mapcontrol.launcher

import android.content.Context

/** Koltuk kartı görünümü seçimi (launcher ayarları); debug isteği ([LauncherDemo.seatStyleOverride]) önceliklidir. */
internal object SeatStylePrefs {
    private const val PREFS = "launcher_settings"
    private const val KEY = "seat_card_style"

    fun current(context: Context): SeatCardStyle {
        LauncherDemo.seatStyleOverride?.let { return it }
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(Profiles.key(context, KEY), null)
        return SeatCardStyle.parse(raw) ?: SeatCardStyle.CLASSIC
    }

    fun set(context: Context, style: SeatCardStyle) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(Profiles.key(context, KEY), style.key).apply()
    }
}
