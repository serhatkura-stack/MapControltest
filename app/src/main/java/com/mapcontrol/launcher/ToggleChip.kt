package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Açma/kapama düğmesi ("çip"): üstte ikon, altında ad, isteğe bağlı durum yazısı. Açıkken vurgu dolgulu, kapalıyken
 * [LauncherTheme.CONTROL] zemin; durum bilinmiyorsa soluk. Konfor kutucukları (klima tuşları, ısıtmalar) paylaşır;
 * renkler her [bind] çağrısında jetonlardan okunur, bu yüzden tema değişince tekrar [bind] yeterlidir.
 */
internal class ToggleChip(
    private val context: Context,
    @DrawableRes iconRes: Int,
    @StringRes private var labelRes: Int,
    onClick: () -> Unit,
) {
    private val iconView: ImageView = icon(context, iconRes, ICON_DP, LauncherTheme.TEXT)
    private val name = text(context, NAME_SP, LauncherTheme.TEXT, medium = true).apply { gravity = Gravity.CENTER }
    private val stateLabel = text(context, STATE_SP, LauncherTheme.TEXT_SECONDARY).apply { gravity = Gravity.CENTER }
    private var compact = false

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val pad = context.dp(CHIP_PAD_DP)
        setPadding(pad, pad, pad, pad)
        addView(iconView, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
        addView(gap(context, 4, vertical = true))
        addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
        pressFeedback()
    }

    init { name.setText(labelRes) }

    /** Alçak yerleşimde durum yazısı gizlenir (yalnızca dolgu rengi söyler); ad ve erişilebilirlik metni korunur. */
    fun setCompact(value: Boolean) {
        compact = value
        stateLabel.visibility = if (value) android.view.View.GONE else android.view.View.VISIBLE
    }

    fun setLabel(@StringRes res: Int) {
        labelRes = res
        name.setText(res)
    }

    fun setIcon(@DrawableRes res: Int) = iconView.setImageResource(res)

    /**
     * [on] true/false durumu, null bilinmiyor. [stateRes] verilirse durum yazısı odur (yoksa Açık/Kapalı); bilinmeyende
     * "—" ("Araç verisi bekleniyor" kısaltması) gösterilir.
     */
    fun bind(on: Boolean?, @StringRes stateRes: Int? = null) {
        val known = on != null
        val active = on == true
        view.background = rounded(context, if (active) DriveAccent.buttonFill(LauncherTheme.ACCENT) else LauncherTheme.CONTROL, CHIP_RADIUS_DP)
        val fg = if (active) LauncherTheme.ON_ACCENT else if (known) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY
        iconView.imageTintList = ColorStateList.valueOf(fg)
        name.setTextColor(fg)
        stateLabel.setTextColor(if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT_SECONDARY)
        val state = context.getString(
            when {
                !known -> R.string.launcher_unavailable
                stateRes != null -> stateRes
                active -> R.string.launcher_comfort_on
                else -> R.string.launcher_comfort_off
            },
        )
        stateLabel.setTextSmooth(state)
        view.isEnabled = known || LauncherEdition.READ_ONLY
        view.contentDescription = context.getString(labelRes) + ", " + state
    }

    private companion object {
        const val ICON_DP = 30
        const val NAME_SP = 18f
        const val STATE_SP = 16f
        const val CHIP_PAD_DP = 4
        const val CHIP_RADIUS_DP = 16f
    }
}
