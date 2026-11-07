package com.mapcontrol.launcher

import android.content.Context
import com.mapcontrol.util.TargetPackageStore

/**
 * Başlatıcının "harita uygulaması" seçimi: Navigasyon kartının "Haritayı aç" düğmesi, Ev / İş kısayolları, en yakın istasyon
 * araması ve kartın canlı rehberliği (hangi uygulamanın bildirimi okunur) hep bu seçimi kullanır.
 */
enum class MapApp(val key: String, val packageName: String, val label: String, val fullName: String) {
    GOOGLE_MAPS("google_maps", NavigationSource.GOOGLE_MAPS, "Google", "Google Maps"),
    YANDEX_MAPS("yandex_maps", NavigationSource.YANDEX_MAPS, "Yandex", "Yandex Maps"),
    YANDEX_NAVI("yandex_navi", NavigationSource.YANDEX_NAVI, "Yandex Navi", "Yandex Navigator");

    companion object {
        /** Varsayılan sıra (FrostNav hedefi uygun değilse): Google Haritalar, Yandex Haritalar, Yandex Navigator. */
        @JvmField val ORDER: List<MapApp> = listOf(GOOGLE_MAPS, YANDEX_MAPS, YANDEX_NAVI)

        fun fromKey(key: String?): MapApp? = values().firstOrNull { it.key == key }

        fun fromPackage(pkg: String?): MapApp? = values().firstOrNull { it.packageName == pkg }
    }
}

/** Seçimin saf kuralları (Android'e bağlı değil; JVM'de sınanır). */
object MapAppChoice {

    /** Kurulu seçenekler, [MapApp.ORDER] sırasıyla (seçim penceresi yalnız bunları listeler). */
    fun choices(installed: Set<String>): List<MapApp> = MapApp.ORDER.filter { it.packageName in installed }

    /**
     * Varsayılan: FrostNav'ın yansıtma hedefi bu üçten biri ve kuruluysa o; değilse kurulu ilk seçenek; hiçbiri yoksa null.
     */
    fun defaultFor(frostNavTarget: String?, installed: Set<String>): MapApp? {
        val target = MapApp.fromPackage(frostNavTarget?.trim())
        if (target != null && target.packageName in installed) return target
        return choices(installed).firstOrNull()
    }

    /** Etkin seçim: kayıtlı seçim kuruluysa o; değilse varsayılan ([defaultFor]). */
    fun resolve(savedKey: String?, frostNavTarget: String?, installed: Set<String>): MapApp? {
        val saved = MapApp.fromKey(savedKey)
        if (saved != null && saved.packageName in installed) return saved
        return defaultFor(frostNavTarget, installed)
    }

    /**
     * Kartın rehberlik için okuduğu bildirim paketleri. Google seçiliyse yalnız Google Haritalar; Yandex seçiliyse (ya da
     * hiçbir seçenek kurulu değilse) eskisi gibi iki Yandex paketi.
     */
    fun notificationPackages(app: MapApp?): Set<String> =
        if (app == MapApp.GOOGLE_MAPS) setOf(MapApp.GOOGLE_MAPS.packageName)
        else setOf(NavigationSource.YANDEX_MAPS, NavigationSource.YANDEX_NAVI)
}

/** Seçimin kaydı (profil bağımsız, "launcher_nav" tercih dosyası; Ev / İş konumlarıyla aynı yer). */
object MapAppPrefs {
    private const val PREFS = "launcher_nav"
    private const val KEY = "map_app"

    fun installedSet(context: Context): Set<String> = MapApp.ORDER.map { it.packageName }.filter { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }.toSet()

    private fun saved(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    private fun frostNavTarget(context: Context): String? = try {
        TargetPackageStore.read(context)
    } catch (e: Exception) {
        null
    }

    /** Etkin harita uygulaması; kurulu seçenek yoksa null. */
    @JvmStatic
    fun current(context: Context): MapApp? = MapAppChoice.resolve(saved(context), frostNavTarget(context), installedSet(context))

    /** Kullanıcı seçimi kaydedilir ve kartın rehberlik kaynağı hemen değişir. */
    @JvmStatic
    fun set(context: Context, app: MapApp) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, app.key).apply()
        NavigationSource.setMapApp(app)
    }
}
