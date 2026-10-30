package com.mapcontrol.hud

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.mapcontrol.launcher.Profiles
import com.mapcontrol.media.LauncherMediaNotificationListener

/** "Yandex → HUD" tercihleri (profil başına) ve bildirim erişimi durumu. Varsayılan KAPALI. */
internal object HudBridgeSettings {
    private const val PREFS = "launcher_settings"
    private const val KEY_ENABLED = "hud_bridge"
    private const val KEY_STRAIGHT = "hud_bridge_force_straight"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(Profiles.key(c, KEY_ENABLED), false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(Profiles.key(c, KEY_ENABLED), on).apply()
    }

    /** Hata ayıklama: ikon tanınmasa da düz ok (9) gönder. Satıra uzun basınca değişir. */
    fun forceStraight(c: Context): Boolean = prefs(c).getBoolean(KEY_STRAIGHT, false)

    fun toggleForceStraight(c: Context): Boolean {
        val n = !forceStraight(c)
        prefs(c).edit().putBoolean(KEY_STRAIGHT, n).apply()
        return n
    }

    /** Bildirim dinleyicisi (ana kaynak) bileşeni. */
    fun listenerComponent(c: Context): String =
        ComponentName(c.packageName, LauncherMediaNotificationListener::class.java.name).flattenToString()

    /** Bildirim erişimi verilmiş mi (ana kaynak; güvenli ayarlar yalnızca OKUNUR). Erişilebilirlik gerekmez. */
    fun serviceGranted(c: Context): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
        return HudAccessibilityCheck.isListed(list, listenerComponent(c))
    }

    /** İsteğe bağlı erişilebilirlik hizmeti etkin mi (toplam mesafe için). */
    fun accessibilityGranted(c: Context): Boolean {
        if (YandexHudAccessibilityService.connected) return true
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return HudAccessibilityCheck.isListed(
            list, ComponentName(c.packageName, YandexHudAccessibilityService::class.java.name).flattenToString())
    }
}
