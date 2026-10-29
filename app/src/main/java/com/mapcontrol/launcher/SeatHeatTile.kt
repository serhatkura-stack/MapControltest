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
 * Koltuk ısıtma kutucuğu. 1×1: sürücü koltuğu; 2×1: sürücü | yolcu. Dokunmak o koltuğun seviyesini
 * bir artırır (Kapalı → Düşük → Orta → Yüksek). Etkin seviye vurgu renginde; yazı seviyeyi söyler.
 * Okuma/yazma [SeatHeatModel] ile (alt çubukla aynı kod yolu; test sürümünde yalnızca okur).
 */
internal class SeatHeatTile(private val context: Context) : SizedWidget {

    private val model = SeatHeatModel(context) { bind(it) }

    private val driver = Seat(R.string.launcher_seat_driver, driver = true)
    private val passenger = Seat(R.string.launcher_seat_passenger, driver = false)

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(driver.root, weightLp(1f))
        addView(View(context).apply { setBackgroundColor(LauncherTheme.TRACK) },
            LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = context.dp(DIVIDER_GAP_DP)
                marginEnd = context.dp(DIVIDER_GAP_DP)
            })
        addView(passenger.root, weightLp(1f))
    }
    private val divider: View get() = root.getChildAt(1)

    override val view: View get() = root

    override fun applySpan(span: Span) {
        val both = span.w >= 2
        divider.visibility = if (both) View.VISIBLE else View.GONE
        passenger.root.visibility = if (both) View.VISIBLE else View.GONE
    }

    override fun onVisible() = model.onVisible()

    override fun onHidden() = model.onHidden()

    override fun release() = Unit

    private fun bind(s: SeatHeatState) {
        driver.bind(s.connected, s.driver)
        passenger.bind(s.connected, s.passenger)
    }

    /** Tek koltuk: üstte ikon + ad, altta seviye yazısı ve üç dilimli seviye göstergesi. */
    private inner class Seat(@StringRes private val labelRes: Int, private val driver: Boolean) {
        private val iconView: ImageView = icon(context, R.drawable.ic_mdi_car_seat_heater, ICON_DP, LauncherTheme.TEXT_SECONDARY)
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
            val known = connected && level != null
            val lv = if (known) level ?: 0 else 0
            val active = lv > 0
            iconView.imageTintList = ColorStateList.valueOf(if (active) LauncherTheme.ACCENT else LauncherTheme.TEXT_SECONDARY)
            value.setTextColor(if (!known) LauncherTheme.TEXT_SECONDARY else if (active) LauncherTheme.ACCENT else LauncherTheme.TEXT)
            val levelText = if (known) context.getString(SeatHeatModel.levelLabel(lv)) else context.getString(R.string.launcher_unavailable)
            value.setTextSmooth(levelText)
            segments.forEachIndexed { i, v ->
                v.background = rounded(context, if (known && i < lv) LauncherTheme.ACCENT else LauncherTheme.TRACK, SEGMENT_RADIUS_DP)
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
        const val DIVIDER_GAP_DP = 20
    }
}
