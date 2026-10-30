package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R
import com.mapcontrol.util.AppLaunchHelper

/** Launcher görünümü: sistemi izle ya da sabit gündüz/gece. */
enum class ThemeMode(val key: String, @StringRes val label: Int) {
    AUTO("auto", R.string.launcher_theme_auto),
    DAY("day", R.string.launcher_theme_day),
    NIGHT("night", R.string.launcher_theme_night);

    companion object {
        private const val PREFS_NAME = "launcher_settings"
        private const val KEY = "theme_mode"

        fun read(context: Context): ThemeMode {
            val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(Profiles.key(context, KEY), null)
            return values().firstOrNull { it.key == key } ?: AUTO
        }

        /** Kayıtlı moddan sonraki mod (tıklama anında okunur, hızlı ikinci dokunuş da ilerletir); yazar ve döndürür. */
        fun next(context: Context): ThemeMode {
            val all = values()
            val n = all[(read(context).ordinal + 1) % all.size]
            write(context, n)
            return n
        }

        fun write(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(Profiles.key(context, KEY), mode.key).apply()
        }
    }
}

/**
 * "Ayarlar" paneli: launcher kartlarıyla aynı dünyada, sistem diyaloğu değil. Üstte tema seçimi
 * (Otomatik | Gündüz | Gece), altında ana ekranı düzenleme, aracın kendi ayarları ve (tam sürümde)
 * MapControl ayarları. Satırlar en az 80dp: sürücü yanlış satıra basmasın.
 * Tema değişince etkinlik yeniden kurulur; renkler yalnızca kurulumda okunur.
 */
class LauncherSettings(private val activity: Activity, private val onEditHome: () -> Unit) {

    private val launcher = LauncherIo.serial()
    private var dialog: Dialog? = null
    private var dataCheck: DataCheckSheet? = null

    fun show() {
        if (dialog?.isShowing == true) return
        dialog = showLauncherSheet(activity, R.string.launcher_settings) { d -> addScrollable(d) }.apply {
            setOnDismissListener { dialog = null }
        }
    }

    fun release() {
        dialog?.dismiss()
        dataCheck?.release()
    }

    /** Satırlar araç ekranının yüksekliğini aşabilir (tema + alt çubuk + 4-5 satır): panel kaydırılır. */
    private fun LinearLayout.addScrollable(d: Dialog) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, activity.dp(16)) }
        content.buildPanel(d)
        val maxH = activity.resources.displayMetrics.heightPixels - activity.dp(SCROLL_RESERVED_DP)
        addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH))
    }

    private fun LinearLayout.buildPanel(d: Dialog) {
        frostNavPackage()?.let { pkg ->
            addView(row(R.drawable.ic_mdi_open_in_new, R.string.launcher_open_frostnav, R.string.launcher_open_frostnav_hint) {
                d.dismiss()
                openFrostNav(pkg)
            }, fullWidth())
            addView(gap(activity, 24, vertical = true))
        }
        addView(text(activity, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(R.string.launcher_theme) }, fullWidth())
        addView(gap(activity, 12, vertical = true))
        addView(themeSegments(d), fullWidth())

        addView(gap(activity, 12, vertical = true))
        addView(driveModeToggle(d), fullWidth())

        addView(gap(activity, 12, vertical = true))
        addView(text(activity, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(R.string.launcher_bottom_bar) }, fullWidth())
        addView(gap(activity, 12, vertical = true))
        addView(bottomBarSegments(d), fullWidth())

        addView(gap(activity, 12, vertical = true))
        addView(autoDriveLayoutToggle(d), fullWidth())

        addView(gap(activity, 24, vertical = true))
        addView(row(R.drawable.ic_mdi_view_grid, R.string.launcher_edit_title) {
            d.dismiss()
            onEditHome()
        }, fullWidth())
        if (isInstalled(VEHICLE_SETTINGS_PACKAGE)) {
            addView(gap(activity, 12, vertical = true))
            addView(row(R.drawable.ic_mdi_car, R.string.launcher_vehicle_settings) {
                d.dismiss()
                openApp(VEHICLE_SETTINGS_PACKAGE)
            }, fullWidth())
        }
        addView(gap(activity, 12, vertical = true))
        addView(row(R.drawable.ic_mdi_magnify, R.string.launcher_data_check, R.string.launcher_data_check_hint) {
            d.dismiss()
            dataCheck = (dataCheck ?: DataCheckSheet(activity)).also { it.show() }
        }, fullWidth())
        if (LauncherEdition.HAS_SETTINGS) {
            addView(gap(activity, 12, vertical = true))
            addView(row(R.drawable.ic_mdi_cog, R.string.launcher_mapcontrol_settings) {
                d.dismiss()
                LauncherEdition.openSettings(activity)
            }, fullWidth())
        }
    }

    /** Üç eşit parça; seçili olan vurgu renginde. Seçim kaydedilir ve ekran yeni temayla kurulur. */
    private fun themeSegments(d: Dialog): View {
        val current = ThemeMode.read(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(activity, LauncherTheme.CONTROL, SEGMENT_RADIUS_DP)
            val p = activity.dp(6)
            setPadding(p, p, p, p)
            ThemeMode.values().forEachIndexed { i, mode ->
                if (i > 0) addView(gap(activity, 6))
                val selected = mode == current
                addView(text(activity, LauncherTheme.BODY_SP,
                    if (selected) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
                    setText(mode.label)
                    gravity = Gravity.CENTER
                    if (selected) background = rounded(activity, LauncherTheme.ACCENT, SEGMENT_RADIUS_DP - 4)
                    isClickable = true
                    isFocusable = true
                    isSelected = selected
                    contentDescription = activity.getString(R.string.launcher_theme) + ": " + activity.getString(mode.label)
                    setOnClickListener {
                        d.dismiss()
                        if (mode != current) {
                            ThemeMode.write(activity, mode)
                            ThemeTransition.retheme(activity)
                        }
                    }
                    pressFeedback()
                }, LinearLayout.LayoutParams(0, activity.dp(LauncherTheme.TOUCH_MIN_DP), 1f))
            }
        }
    }

    /** "Sürüş moduna göre renk": açık/kapalı satırı; değişince ekran yeni vurguyla kurulur. */
    private fun driveModeToggle(d: Dialog): View {
        val on = DriveAccent.enabled(activity)
        return row(R.drawable.ic_mdi_car_sports, if (on) R.string.launcher_drive_theme_on else R.string.launcher_drive_theme_off) {
            d.dismiss()
            DriveAccent.setEnabled(activity, !on)
            (activity as? HomeLauncherActivity)?.refreshDrivePrefs()
            if (on) LauncherTheme.driveMode = DriveAccent.NORMAL
            // Kapatınca vurgu hemen varsayılana döner; açınca mod araçtan okunup ~2 sn sonra uygulanır.
            if (on) ThemeTransition.retheme(activity)
        }
    }

    /** "Otomatik sürüş düzeni": açık/kapalı satırı; değişince ekran yeni seçimle kurulur. */
    private fun autoDriveLayoutToggle(d: Dialog): View {
        val on = DriveLayoutPrefs.get(activity)
        return row(R.drawable.ic_mdi_view_grid, if (on) R.string.launcher_auto_drive_layout_on else R.string.launcher_auto_drive_layout_off) {
            d.dismiss()
            DriveLayoutPrefs.set(activity, !on)
            (activity as? HomeLauncherActivity)?.refreshDrivePrefs()
            ThemeTransition.recreate(activity)
        }
    }

    /**
     * "Alt çubuk": Orijinal | Bizimki | Gizli. Seçim kaydedilir ve ekran yeniden kurulur (OEM çubuğu gizlenir ya da
     * görünür, kendi çubuğumuz eklenir ya da kalkar). Tema seçimiyle aynı üç parçalı görünüm.
     */
    private fun bottomBarSegments(d: Dialog): View {
        val current = LauncherBottomBar.mode(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(activity, LauncherTheme.CONTROL, SEGMENT_RADIUS_DP)
            val p = activity.dp(6)
            setPadding(p, p, p, p)
            BottomBarMode.values().forEachIndexed { i, mode ->
                if (i > 0) addView(gap(activity, 6))
                val selected = mode == current
                addView(text(activity, LauncherTheme.BODY_SP,
                    if (selected) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
                    setText(mode.label)
                    gravity = Gravity.CENTER
                    if (selected) background = rounded(activity, LauncherTheme.ACCENT, SEGMENT_RADIUS_DP - 4)
                    isClickable = true
                    isFocusable = true
                    isSelected = selected
                    contentDescription = activity.getString(R.string.launcher_bottom_bar) + ": " + activity.getString(mode.label)
                    setOnClickListener {
                        d.dismiss()
                        if (mode != current) {
                            LauncherBottomBar.setMode(activity, mode)
                            ThemeTransition.recreate(activity)
                        }
                    }
                    pressFeedback()
                }, LinearLayout.LayoutParams(0, activity.dp(LauncherTheme.TOUCH_MIN_DP), 1f))
            }
        }
    }

    private fun row(
        @DrawableRes iconRes: Int,
        @StringRes labelRes: Int,
        @StringRes hintRes: Int = 0,
        onClick: () -> Unit,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(activity, LauncherTheme.CONTROL, SEGMENT_RADIUS_DP)
            minimumHeight = activity.dp(ROW_HEIGHT_DP)
            val p = activity.dp(24)
            setPadding(p, 0, p, 0)
            addView(icon(activity, iconRes, 32, LauncherTheme.TEXT_SECONDARY))
            addView(gap(activity, 20))
            if (hintRes == 0) {
                addView(text(activity, LauncherTheme.BODY_SP, medium = true).apply { setText(labelRes) })
            } else {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(activity, LauncherTheme.BODY_SP, medium = true).apply { setText(labelRes) })
                    addView(text(activity, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                        setText(hintRes)
                    })
                })
            }
            contentDescription = activity.getString(labelRes)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    private fun fullWidth() = sheetFullWidth()

    /** Java yardımcısı senkron çalıştığı için arka planda açılır. */
    private fun openApp(packageName: String) {
        launcher.runSafely {
            AppLaunchHelper.launchAppOnDisplay(activity, packageName, Display.DEFAULT_DISPLAY, 0)
        }
    }

    /** Launcher FrostNav'ın dışında çalışıyorsa ve FrostNav kuruluysa açılacak paket; yoksa null. */
    private fun frostNavPackage(): String? = FrostNavLink.choosePackage(
        activity.packageName,
        listOf(FrostNavLink.PRIMARY_PACKAGE, FrostNavLink.FALLBACK_PACKAGE).filter { isInstalled(it) }.toSet(),
    )

    private fun openFrostNav(packageName: String) {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (intent == null) throw ActivityNotFoundException(packageName)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.launcher_open_frostnav_failed, Toast.LENGTH_SHORT).show()
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
        const val ROW_HEIGHT_DP = 80
        /** Başlık, iç boşluk ve ekran kenarı için ayrılan yükseklik. */
        /** Başlık + kart dolgusu + sistem çubuğu (1920x720 ekranda 48 px) düşülür; aksi halde son satır pencere altında kırpılır. */
        const val SCROLL_RESERVED_DP = 250
        const val SEGMENT_RADIUS_DP = 16f
    }
}
