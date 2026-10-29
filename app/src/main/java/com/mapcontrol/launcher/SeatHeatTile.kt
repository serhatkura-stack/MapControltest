package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Tek koltuk kutucuğu: yalnızca sürücü ya da yalnızca yolcu koltuğu, ısıtma veya soğutma. Yolcu kendi tarafına
 * koyup doğrudan dokunabilsin diye her koltuk ayrı widget'tır. Dokunmak seviyeyi bir artırır
 * (Kapalı → Düşük → Orta → Yüksek). Etkin seviye vurgu renginde; yazı seviyeyi söyler.
 * Okuma/yazma [SeatHeatModel] ile (alt çubukla aynı kod yolu; test sürümünde yalnızca okur).
 */
internal class SeatHeatTile(
    private val context: Context,
    private val vent: Boolean = false,
    private val driverSeat: Boolean = true,
) : SizedWidget {

    private val model = SeatHeatModel(context, vent) { bind(it) }

    /** Etkin seviye rengi: ısıtmada vurgu, soğutmada sakin bir turkuaz. */
    private fun activeColor(): Int = if (vent) SeatClimateColors.cool() else LauncherTheme.ACCENT

    private val seat = Seat(if (driverSeat) R.string.launcher_seat_driver else R.string.launcher_seat_passenger, driver = driverSeat)

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(seat.root, weightLp(1f))
    }

    override val view: View get() = root

    override fun applySpan(span: Span) = Unit

    override fun onVisible() = model.onVisible()

    override fun onHidden() = model.onHidden()

    override fun release() = Unit

    private fun bind(s: SeatHeatState) {
        seat.bind(s.connected, if (driverSeat) s.driver else s.passenger)
    }

    /** Tek koltuk: üstte ikon + ad, altta seviye yazısı ve üç dilimli seviye göstergesi. */
    private inner class Seat(@StringRes private val labelRes: Int, private val driver: Boolean) {
        private val iconView: ImageView = icon(context, if (vent) R.drawable.ic_mdi_car_seat_vent else R.drawable.ic_mdi_car_seat_heater, ICON_DP, LauncherTheme.TEXT_SECONDARY)
        private val value: TextView = text(context, VALUE_SP, medium = true)
        private val segments = List(SEGMENTS) { View(context) }

        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView)
                addView(gap(context, 12))
                addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(labelRes) })
            })
            addView(gap(context, 4, vertical = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                segments.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(context.dp(SEGMENT_W_DP), context.dp(SEGMENT_H_DP)).apply {
                        if (i > 0) marginStart = context.dp(SEGMENT_GAP_DP)
                    })
                }
            })
            setOnClickListener { model.cycle(driver) }
            pressFeedback()
        }

        fun bind(connected: Boolean, level: Int?) {
            // Soğutma sinyali desteklenmese de sakin "Kapalı" göster (okunamayan değer 0 sayılır).
            val known = connected && (level != null || vent)
            val lv = if (known) level ?: 0 else 0
            val active = lv > 0
            iconView.imageTintList = ColorStateList.valueOf(if (active) activeColor() else LauncherTheme.TEXT_SECONDARY)
            value.setTextColor(if (!known) LauncherTheme.TEXT_SECONDARY else if (active) activeColor() else LauncherTheme.TEXT)
            val levelText = if (known) context.getString(SeatHeatModel.levelLabel(lv)) else context.getString(R.string.launcher_unavailable)
            value.setTextSmooth(levelText)
            segments.forEachIndexed { i, v ->
                v.background = rounded(context, if (known && i < lv) activeColor() else LauncherTheme.TRACK, SEGMENT_RADIUS_DP)
            }
            root.isEnabled = known
            root.contentDescription = context.getString(labelRes) + ", " + levelText
        }
    }

    private companion object {
        const val PADDING_DP = 20
        const val ICON_DP = 28
        const val VALUE_SP = 32f
        const val SEGMENTS = 3
        const val SEGMENT_W_DP = 14
        const val SEGMENT_H_DP = 22
        const val SEGMENT_GAP_DP = 6
        const val SEGMENT_RADIUS_DP = 5f
    }
}

/** Soğutma vurgusu: [LauncherTheme] tek vurgusunu (mavi) bozmadan ısıtmadan ayrışan turkuaz. */
internal object SeatClimateColors {
    fun cool(): Int = if (LauncherTheme.night) 0xFF22D3EE.toInt() else 0xFF0E7490.toInt()
}
