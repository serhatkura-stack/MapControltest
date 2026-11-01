package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/** Stil B'nin yuvalarına atanabilen eylemler. Uygulama yuvası ayrıca "app:<paket>" anahtarıyla tutulur. */
internal enum class SlotKind(val id: String, @DrawableRes val icon: Int, @StringRes val label: Int, val needsMain: Boolean = false) {
    HOME("home", R.drawable.ic_mdi_home, R.string.launcher_bar_home),
    APPS("apps", R.drawable.ic_mdi_view_grid, R.string.launcher_bar_apps),
    CAR("car", R.drawable.ic_mdi_car, R.string.launcher_vehicle_settings),
    SETTINGS("settings", R.drawable.ic_mdi_cog, R.string.launcher_bar_settings),
    PHONE("phone", R.drawable.ic_mdi_phone, R.string.launcher_app_phone),
    REAR("rear", R.drawable.ic_mdi_volume_high, R.string.launcher_widget_rear_speaker),
    TRUNK("trunk", R.drawable.ic_mdi_car_trunk, R.string.launcher_widget_trunk),
    FROSTNAV("frostnav", R.drawable.ic_mdi_map, R.string.launcher_bar_slot_frostnav, needsMain = true),
    CAM360("cam360", R.drawable.ic_mdi_camera, R.string.launcher_bar_slot_cam360, needsMain = true),
    HOME_SETTINGS("homeset", R.drawable.ic_mdi_pencil, R.string.launcher_bar_slot_home_settings, needsMain = true);

    companion object {
        fun byId(id: String?): SlotKind? = values().firstOrNull { it.id == id }
    }
}

/**
 * Stil B'nin yuvaları: sol üç, sağ iki (sıra: sol 0..2, sağ 3..4). Kayıt profil başına tek metin
 * ("home,apps,car,settings,phone"); bilinmeyen ya da bozuk giriş o yuvanın varsayılanına döner.
 */
internal object BarSlots {
    const val COUNT = 5
    const val APP_PREFIX = "app:"
    val DEFAULTS: List<String> = listOf("home", "apps", "car", "settings", "phone")

    private const val PREFS = "launcher_settings"
    private const val KEY = "bar_slots"
    private const val KEY_HINT = "bar_slots_hint"

    /** Geçerli yuva anahtarı mı: bilinen eylem ya da "app:<boş olmayan paket>". */
    fun valid(id: String?): Boolean =
        id != null && (SlotKind.byId(id) != null || (id.startsWith(APP_PREFIX) && id.length > APP_PREFIX.length))

    fun parse(raw: String?): List<String> {
        val parts = raw?.split(',').orEmpty()
        return List(COUNT) { i -> parts.getOrNull(i)?.trim()?.takeIf(::valid) ?: DEFAULTS[i] }
    }

    fun serialize(slots: List<String>): String = slots.joinToString(",")

    fun load(context: Context): List<String> =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(Profiles.key(context, KEY), null))

    fun save(context: Context, slots: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(Profiles.key(context, KEY), serialize(slots)).apply()
    }

    /** İpucu ("uzun bas") ilk gösterimde bir kez; true dönerse çağıran göstermeli. */
    fun consumeHint(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HINT, false)) return false
        prefs.edit().putBoolean(KEY_HINT, true).apply()
        return true
    }

    /** Bu derlemede seçilebilen eylemler (salt-okur test sürümünde MapControl'e açılanlar yok). */
    fun available(): List<SlotKind> = SlotKind.values().filter { !it.needsMain || LauncherEdition.HAS_SETTINGS }
}

/**
 * Yuva seçici: eylemler bir ızgarada, sonda "Uygulama seç…" ve "Varsayılana dön". Seçimi [onPick] anahtar olarak alır;
 * uygulama seçimi [onPickApp] ile çağırana bırakılır (uygulama listesi orada).
 */
internal fun showSlotPicker(
    activity: Activity,
    current: String,
    default: String,
    onPick: (String) -> Unit,
    onPickApp: () -> Unit,
): Dialog = showLauncherSheet(activity, R.string.launcher_bar_slot_title, PICKER_WIDTH_DP) { d ->
    data class Option(val icon: Int, val label: String, val selected: Boolean, val action: () -> Unit)
    val options = ArrayList<Option>()
    BarSlots.available().forEach { k ->
        options += Option(k.icon, activity.getString(k.label), k.id == current) { d.dismiss(); onPick(k.id) }
    }
    options += Option(R.drawable.ic_mdi_package_variant, activity.getString(R.string.launcher_bar_slot_app), current.startsWith(BarSlots.APP_PREFIX)) {
        d.dismiss(); onPickApp()
    }
    options += Option(R.drawable.ic_mdi_restart, activity.getString(R.string.launcher_bar_slot_reset), false) { d.dismiss(); onPick(default) }
    options.chunked(PICKER_COLUMNS).forEach { chunk ->
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            chunk.forEach { o -> addView(pickerTile(activity, o.icon, o.label, o.selected, o.action), LinearLayout.LayoutParams(0, activity.dp(PICKER_TILE_DP), 1f)
                .apply { setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4)) }) }
            repeat(PICKER_COLUMNS - chunk.size) { addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

private fun pickerTile(context: Context, @DrawableRes iconRes: Int, label: String, selected: Boolean, onClick: () -> Unit): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(context, if (selected) LauncherTheme.ACCENT else LauncherTheme.CONTROL, 18f)
        isClickable = true
        isFocusable = true
        contentDescription = label
        val fg = if (selected) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT
        addView(icon(context, iconRes, 36, fg))
        addView(gap(context, 6, vertical = true))
        addView(text(context, LauncherTheme.LABEL_SP, fg, medium = true).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(6); marginEnd = context.dp(6)
        })
        setOnClickListener { onClick() }
        pressFeedback()
    }

private const val PICKER_WIDTH_DP = 1100
private const val PICKER_COLUMNS = 6
private const val PICKER_TILE_DP = 124
