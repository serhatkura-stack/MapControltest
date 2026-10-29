package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
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
 * "Ayarlar" paneli: launcher kartlarıyla aynı dünyada, sistem diyaloğu değil. Üstte tema seçimi
 * (Otomatik | Gündüz | Gece), altında ana ekranı düzenleme, aracın kendi ayarları ve (tam sürümde)
 * MapControl ayarları. Satırlar en az 80dp: sürücü yanlış satıra basmasın.
 * Tema değişince etkinlik yeniden kurulur; renkler yalnızca kurulumda okunur.
 */
class LauncherSettings(private val activity: Activity, private val onEditHome: () -> Unit) {

    private val launcher = Executors.newSingleThreadExecutor()
    private var dialog: Dialog? = null

    fun show() {
        if (dialog?.isShowing == true) return
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(buildPanel(d))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(activity.dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        d.setOnDismissListener { dialog = null }
        dialog = d
        d.show()
    }

    fun release() {
        dialog?.dismiss()
        launcher.shutdown()
    }

    private fun buildPanel(d: Dialog): View = card(activity).apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(activity, LauncherTheme.SECTION_SP, bold = true).apply { setText(R.string.launcher_settings) },
                weightLp(1f).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT })
            addView(iconButton(activity, R.drawable.ic_mdi_close, activity.getString(R.string.launcher_close)) { d.dismiss() })
        }, fullWidth())

        addView(gap(activity, 24, vertical = true))
        addView(text(activity, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(R.string.launcher_theme) }, fullWidth())
        addView(gap(activity, 12, vertical = true))
        addView(themeSegments(d), fullWidth())

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
        if (LauncherEdition.HAS_SETTINGS) {
            addView(gap(activity, 12, vertical = true))
            addView(row(R.drawable.ic_mdi_cog, R.string.launcher_mapcontrol_settings) {
                d.dismiss()
                LauncherEdition.openSettings(activity)
            }, fullWidth())
        }

        addView(gap(activity, 24, vertical = true))
        addView(text(activity, CREDIT_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 2).apply {
            setText(R.string.launcher_credit_car_image)
        }, fullWidth())
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
                            activity.recreate()
                        }
                    }
                    pressFeedback()
                }, LinearLayout.LayoutParams(0, activity.dp(LauncherTheme.TOUCH_MIN_DP), 1f))
            }
        }
    }

    private fun row(@DrawableRes iconRes: Int, @StringRes labelRes: Int, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(activity, LauncherTheme.CONTROL, SEGMENT_RADIUS_DP)
            minimumHeight = activity.dp(ROW_HEIGHT_DP)
            val p = activity.dp(24)
            setPadding(p, 0, p, 0)
            addView(icon(activity, iconRes, 32, LauncherTheme.TEXT_SECONDARY))
            addView(gap(activity, 20))
            addView(text(activity, LauncherTheme.BODY_SP, medium = true).apply { setText(labelRes) })
            contentDescription = activity.getString(labelRes)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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
        const val PANEL_WIDTH_DP = 760
        const val ROW_HEIGHT_DP = 80
        const val SEGMENT_RADIUS_DP = 16f
        const val CREDIT_SP = 14f
    }
}
