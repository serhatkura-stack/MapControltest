package com.mapcontrol.launcher

import android.content.Context
import android.content.Intent

/**
 * FrostNav'ın "Ana ekran" sekmesi için Java'dan çağrılan ince katman: launcher ayarlarını aynı tercih
 * anahtarlarıyla (ThemeMode, DriveAccent, LauncherBottomBar, DriveLayoutPrefs, LayoutStore) okur ve yazar.
 * Launcher bu değerlerin [signature]'ı değişmişse öne gelirken kendini yeniden kurar (bkz. HomeLauncherActivity.onResume).
 */
object HomeScreenPrefs {

    /** FrostNav "Ana ekran" sekmesinden gelir: launcher açılışta düzenleme moduna girer (bir kez tüketilir). */
    const val EXTRA_OPEN_EDIT = "launcher_open_edit"
    private const val PREFS_NAME = "launcher_settings"
    private const val KEY_LAYOUT_RESET_GEN = "layout_reset_gen"

    @JvmStatic fun themeIndex(context: Context): Int = ThemeMode.read(context).ordinal

    @JvmStatic fun setThemeIndex(context: Context, index: Int) {
        ThemeMode.values().getOrNull(index)?.let { ThemeMode.write(context, it) }
    }

    @JvmStatic fun driveAccent(context: Context): Boolean = DriveAccent.enabled(context)

    @JvmStatic fun setDriveAccent(context: Context, on: Boolean) = DriveAccent.setEnabled(context, on)

    @JvmStatic fun bottomBarIndex(context: Context): Int = LauncherBottomBar.mode(context).ordinal

    @JvmStatic fun setBottomBarIndex(context: Context, index: Int) {
        BottomBarMode.values().getOrNull(index)?.let { LauncherBottomBar.setMode(context, it) }
    }

    @JvmStatic fun autoDriveLayout(context: Context): Boolean = DriveLayoutPrefs.get(context)

    @JvmStatic fun setAutoDriveLayout(context: Context, on: Boolean) = DriveLayoutPrefs.set(context, on)

    /** Park ya da Sürüş düzenini varsayılana döndürür (düzenleme modundaki "Varsayılan" ile aynı depo). */
    @JvmStatic fun resetLayout(context: Context, drive: Boolean) {
        LayoutStore(context, if (drive) LayoutMode.DRIVE else LayoutMode.PARK).reset()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAYOUT_RESET_GEN, prefs.getInt(KEY_LAYOUT_RESET_GEN, 0) + 1).apply()
    }

    /** Launcher'ın kurulumda okuduğu tercihlerin özeti; farklıysa açık launcher yeniden kurulmalıdır. */
    @JvmStatic fun signature(context: Context): String {
        val gen = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_LAYOUT_RESET_GEN, 0)
        return "${themeIndex(context)}|${driveAccent(context)}|${bottomBarIndex(context)}|${autoDriveLayout(context)}|$gen"
    }

    /** Ana ekranı doğrudan düzenleme modunda açar. */
    @JvmStatic fun openEditor(context: Context) {
        context.startActivity(
            Intent(context, HomeLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_EDIT, true),
        )
    }
}
