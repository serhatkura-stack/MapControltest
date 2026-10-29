package com.mapcontrol.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.Display
import androidx.annotation.StringRes
import com.mapcontrol.R
import com.mapcontrol.util.AppLaunchHelper
import java.util.concurrent.Executors

/** Launcher görünümü: sistemi izle ya da sabit gündüz/gece. */
enum class ThemeMode(val key: String, @StringRes val label: Int) {
    AUTO("auto", R.string.launcher_theme_auto),
    DAY("day", R.string.launcher_theme_day),
    NIGHT("night", R.string.launcher_theme_night);

    companion object {
        private const val PREFS_NAME = "launcher_settings"
        private const val KEY = "theme_mode"

        fun read(context: Context): ThemeMode {
            val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
            return values().firstOrNull { it.key == key } ?: AUTO
        }

        fun write(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY, mode.key).apply()
        }
    }
}

/**
 * Üst şeritteki "Ayarlar" menüsü: tema seçimi, aracın kendi ayar uygulaması ve (tam sürümde) MapControl ayarları.
 * Tema değişince etkinlik yeniden kurulur; renkler yalnızca kurulumda okunur.
 */
class LauncherSettings(private val activity: Activity) {

    private class Entry(@StringRes val label: Int, val action: () -> Unit)

    private val launcher = Executors.newSingleThreadExecutor()

    fun show() {
        val entries = buildList {
            add(Entry(R.string.launcher_theme) { showThemePicker() })
            if (isInstalled(VEHICLE_SETTINGS_PACKAGE)) {
                add(Entry(R.string.launcher_vehicle_settings) { openApp(VEHICLE_SETTINGS_PACKAGE) })
            }
            if (LauncherEdition.HAS_SETTINGS) {
                add(Entry(R.string.launcher_mapcontrol_settings) { LauncherEdition.openSettings(activity) })
            }
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.launcher_settings)
            .setItems(entries.map { activity.getString(it.label) }.toTypedArray()) { _, which -> entries[which].action() }
            .show()
    }

    fun release() = launcher.shutdown()

    private fun showThemePicker() {
        val modes = ThemeMode.values()
        val current = ThemeMode.read(activity)
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.launcher_theme)
            .setSingleChoiceItems(modes.map { activity.getString(it.label) }.toTypedArray(), current.ordinal) { dialog, which ->
                dialog.dismiss()
                if (modes[which] != current) {
                    ThemeMode.write(activity, modes[which])
                    activity.recreate()
                }
            }
            .show()
    }

    /** Java yardımcısı senkron çalıştığı için arka planda açılır. */
    private fun openApp(packageName: String) {
        launcher.runSafely {
            AppLaunchHelper.launchAppOnDisplay(activity, packageName, Display.DEFAULT_DISPLAY, 0)
        }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        activity.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        /** Aracın kendi araç ayarları uygulaması (Desay SV). */
        const val VEHICLE_SETTINGS_PACKAGE = "com.desaysv.vehiclesetting"
    }
}
