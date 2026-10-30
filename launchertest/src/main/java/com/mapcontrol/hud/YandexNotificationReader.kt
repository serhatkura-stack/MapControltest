package com.mapcontrol.hud

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.RemoteViews

/**
 * Yandex bildiriminden ikon kaynak adını okur (en iyi çaba, tamamı try/catch): RemoteViews.mActions içindeki
 * ReflectionAction (methodName == "setImageResource") değerleri -> Yandex paketinden getResourceEntryName.
 * Gizli API; engellenirse null döner ve çözümleme metne düşer.
 */
internal object YandexNotificationReader {

    private const val TAG = "HudBridge"
    const val PACKAGE = "ru.yandex.yandexmaps"

    /** Bildirimdeki ikon kaynak adları (notification_*_sdl önde). Okunamazsa boş. */
    @Suppress("DEPRECATION")
    fun iconEntryNames(context: Context, sbn: StatusBarNotification): List<String> {
        val n = sbn.notification ?: return emptyList()
        val ids = LinkedHashSet<Int>()
        for (rv in listOf(n.contentView, n.bigContentView, n.headsUpContentView)) {
            if (rv != null) ids.addAll(imageResourceIds(rv))
        }
        if (ids.isEmpty()) return emptyList()
        val res = resourcesOf(context, sbn) ?: return emptyList()
        val names = ArrayList<String>()
        for (id in ids) {
            try { names.add(res.getResourceEntryName(id)) } catch (_: Exception) { }
        }
        return names.sortedBy { if (it.startsWith("notification_")) 0 else 1 }
    }

    private fun resourcesOf(context: Context, sbn: StatusBarNotification): android.content.res.Resources? {
        try { return context.createPackageContext(PACKAGE, 0).resources } catch (_: Throwable) { }
        try { return context.packageManager.getResourcesForApplication(PACKAGE) } catch (_: Throwable) { }
        return null
    }

    private fun imageResourceIds(rv: RemoteViews): List<Int> {
        val out = ArrayList<Int>()
        try {
            val f = RemoteViews::class.java.getDeclaredField("mActions")
            f.isAccessible = true
            val actions = f.get(rv) as? Iterable<*> ?: return out
            for (a in actions) {
                if (a == null) continue
                try {
                    val method = fieldValue(a, "methodName", "mMethodName") as? String
                    if (method != "setImageResource") continue
                    val v = fieldValue(a, "value", "mValue") as? Int ?: continue
                    if (v != 0) out.add(v)
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "RemoteViews.mActions okunamadı: $t")
        }
        return out
    }

    private fun fieldValue(target: Any, vararg names: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            for (n in names) {
                try {
                    val f = c.getDeclaredField(n)
                    f.isAccessible = true
                    return f.get(target)
                } catch (_: NoSuchFieldException) { }
            }
            c = c.superclass
        }
        return null
    }

    fun title(n: Notification): String? = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    fun text(n: Notification): String? = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}
