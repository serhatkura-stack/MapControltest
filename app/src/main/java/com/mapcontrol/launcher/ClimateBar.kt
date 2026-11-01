package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R

/**
 * Alt kart: sürücü koltuk ısıtma · ortada uygulama şeridi · yolcu koltuk ısıtma.
 * A/C, buğu ve sıcaklık/fan araç sistem çubuğunda ve fiziksel tuşlarda var; burada tekrarlanmaz.
 * Okuma/yazma mantığı [SeatHeatModel]'de (koltuk kutucuğuyla ortak).
 */
class ClimateBar(private val context: Context, appDock: View) : LauncherWidget {

    private val model = SeatHeatModel(context) { bind(it) }

    private val driverSeat = ToggleTile(R.drawable.ic_mdi_car_seat_heater, R.string.launcher_seat_heat_driver, levels = true) {
        model.cycle(driver = true)
    }
    private val passengerSeat = ToggleTile(R.drawable.ic_mdi_car_seat_heater, R.string.launcher_seat_heat_passenger, levels = true) {
        model.cycle(driver = false)
    }
    override val view: View = card(context, LinearLayout.HORIZONTAL, paddingDp = LauncherTheme.CARD_PAD_COMPACT_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(driverSeat.root)
        addView(appDock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = context.dp(LauncherTheme.GAP_L_DP)
            marginEnd = context.dp(LauncherTheme.GAP_L_DP)
        })
        addView(passengerSeat.root)
    }

    override fun onVisible() = model.onVisible()

    override fun onHidden() = model.onHidden()

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz; ayrılacak bir kaynak yok. */
    override fun release() = Unit

    private fun bind(s: SeatHeatState) {
        driverSeat.bind(s.connected && s.driver != null, (s.driver ?: 0) > 0, s.driver ?: 0)
        passengerSeat.bind(s.connected && s.passenger != null, (s.passenger ?: 0) > 0, s.passenger ?: 0)
    }

    /**
     * 88dp kare düğme: ikon + kısa etiket; açıkken vurgu renginde. Koltuk düğmelerinde etiket
     * seviyeyi yazıyla söyler (Kapalı / Düşük / Orta / Yüksek): renk ya da nokta saymaya gerek kalmaz.
     */
    private inner class ToggleTile(iconRes: Int, private val labelRes: Int, private val levels: Boolean = false, onTap: () -> Unit) {
        private val iconView: ImageView = icon(context, iconRes, LauncherTheme.ICON_M_DP)
        private val labelView = text(context, LauncherTheme.LABEL_SP, medium = true).apply {
            gravity = Gravity.CENTER
            setText(if (levels) R.string.launcher_seat_off else labelRes)
        }
        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(TILE_DP), context.dp(TILE_DP))
            background = rounded(context, LauncherTheme.CONTROL, TILE_RADIUS_DP)
            contentDescription = context.getString(labelRes)
            isClickable = true
            addView(iconView)
            addView(gap(context, LauncherTheme.GAP_S_DP / 2, vertical = true))
            addView(labelView)
            setOnClickListener { onTap() }
            pressFeedback()
        }

        fun bind(enabled: Boolean, active: Boolean, level: Int = 0) {
            root.isEnabled = enabled
            root.alpha = if (enabled) 1f else DISABLED_ALPHA
            (root.background as android.graphics.drawable.GradientDrawable)
                .setColor(if (active) LauncherTheme.ACCENT else LauncherTheme.CONTROL)
            val fg = if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT
            iconView.imageTintList = ColorStateList.valueOf(fg)
            labelView.setTextColor(fg)
            if (levels) {
                val levelText = context.getString(SeatHeatModel.levelLabel(level))
                labelView.text = levelText
                root.contentDescription = context.getString(labelRes) + ", " + levelText
            }
        }
    }

    private companion object {
        const val TILE_DP = 88
        /** Tüm alt çubuk düğmeleri (sol ve sağ) tek biçim: 88dp kare, 16dp köşe. */
        const val TILE_RADIUS_DP = 16f
        const val DISABLED_ALPHA = 0.55f
    }
}
