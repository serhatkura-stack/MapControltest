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
 * (Kapalı → Düşük → Orta → Yüksek). Etkin seviye ısıtmada turuncu, soğutmada camgöbeği; yazı seviyeyi söyler.
 * Okuma/yazma [SeatHeatModel] ile (alt çubukla aynı kod yolu; test sürümünde yalnızca okur).
 */
internal class SeatHeatTile(
    private val context: Context,
    private val vent: Boolean = false,
    private val driverSeat: Boolean = true,
) : SizedWidget, Themeable {

    /** Tema bağlı atamalar (kurucuda bir kez, [applyTheme]'de yeniden); bkz. [ThemeBindings]. */
    private val theme = ThemeBindings()

    private val model = SeatHeatModel(context, vent) { bind(it) }

    /** Etkin seviye rengi: ısıtma sıcak turuncu, soğutma camgöbeği (sürüş vurgusundan bağımsız). */
    private fun activeColor(): Int = if (vent) LauncherTheme.COOL else LauncherTheme.HEAT

    private val seat = Seat(
        when {
            vent && driverSeat -> R.string.launcher_seat_title_vent_driver
            vent -> R.string.launcher_seat_title_vent_passenger
            driverSeat -> R.string.launcher_seat_title_heat_driver
            else -> R.string.launcher_seat_title_heat_passenger
        },
        driver = driverSeat,
    )

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        theme.bind { background = cardSurface(context) }
        addView(seat.root, weightLp(1f))
    }

    override val view: View get() = root

    /** Tema değişti: yüzey ve koltuk içeriği yeni renklerle yeniden atanır (seviye ve model korunur). */
    override fun applyTheme() {
        theme.reapply()
        seat.applyTheme()
    }

    /** 3 sütun ve üstünde seviye yazısı ve dilimler büyür; yalnızca boyut değişince çalışır. */
    override fun applySpan(span: Span) = seat.applySize(span.w >= BIG_MIN_COLS)

    override fun onVisible() = model.onVisible()

    override fun onHidden() = model.onHidden()

    override fun release() = Unit

    private fun bind(s: SeatHeatState) {
        seat.bind(s.connected, if (driverSeat) s.driver else s.passenger)
    }

    /** Tek koltuk: üstte ikon + ad, altta seviye yazısı ve üç dilimli seviye göstergesi. */
    private inner class Seat(@StringRes private val labelRes: Int, private val driver: Boolean) {
        private val iconView: ImageView = icon(context, if (vent) R.drawable.ic_mdi_car_seat_vent else R.drawable.ic_mdi_car_seat_heater, ICON_DP)
            .also { v -> theme.bind { v.setTint(LauncherTheme.TEXT_SECONDARY) } }
        private val value: TextView = text(context, VALUE_SP, medium = true).apply { theme.bind { setTextColor(LauncherTheme.TEXT) } }
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
                addView(text(context, LauncherTheme.LABEL_SP).apply {
                    setText(labelRes)
                    theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
                })
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

        private var big: Boolean? = null

        fun applySize(big: Boolean) {
            if (big == this.big) return
            this.big = big
            val iconDp = if (big) ICON_BIG_DP else ICON_DP
            iconView.layoutParams = LinearLayout.LayoutParams(context.dp(iconDp), context.dp(iconDp))
            value.textSize = if (big) VALUE_BIG_SP else VALUE_SP
            val w = if (big) SEGMENT_W_BIG_DP else SEGMENT_W_DP
            val h = if (big) SEGMENT_H_BIG_DP else SEGMENT_H_DP
            val gapDp = if (big) SEGMENT_GAP_BIG_DP else SEGMENT_GAP_DP
            segments.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(context.dp(w), context.dp(h)).apply {
                    if (i > 0) marginStart = context.dp(gapDp)
                }
                v.background = rounded(context, segmentColor(i), if (big) SEGMENT_RADIUS_BIG_DP else SEGMENT_RADIUS_DP)
            }
        }

        private var lastLevel = 0
        private var lastKnown = false
        private var lastConnected = false
        private var lastRaw: Int? = null
        private var bound = false

        /** Tema değişti: ikon, yazı ve dilimler son duruma göre yeni renklerle yeniden atanır; hiç bağlanmadıysa yalnızca sabit renkler. */
        fun applyTheme() {
            if (bound) bind(lastConnected, lastRaw)
        }

        private fun segmentColor(i: Int) = if (lastKnown && i < lastLevel) activeColor() else LauncherTheme.TRACK

        fun bind(connected: Boolean, level: Int?) {
            // Soğutma sinyali desteklenmese de sakin "Kapalı" göster (okunamayan değer 0 sayılır).
            bound = true
            lastConnected = connected
            lastRaw = level
            val known = connected && (level != null || vent)
            val lv = if (known) level ?: 0 else 0
            val active = lv > 0
            lastKnown = known
            lastLevel = lv
            iconView.imageTintList = ColorStateList.valueOf(if (active) activeColor() else LauncherTheme.TEXT_SECONDARY)
            value.setTextColor(if (active) activeColor() else LauncherTheme.TEXT_SECONDARY)
            val levelText = if (known) context.getString(SeatHeatModel.levelLabel(lv)) else context.getString(R.string.launcher_unavailable)
            value.setTextSmooth(levelText)
            segments.forEachIndexed { i, v ->
                v.background = rounded(context, segmentColor(i), if (big == true) SEGMENT_RADIUS_BIG_DP else SEGMENT_RADIUS_DP)
            }
            // Test sürümünde veri okunamasa da dokunuş açıklama göstermeli (sessiz kalmasın).
            root.isEnabled = known || LauncherEdition.READ_ONLY
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
        /** Geniş kutucukta (3+ sütun): iç yükseklik 85dp'ye sığan büyük dilimler ve yazı. */
        const val BIG_MIN_COLS = 3
        const val ICON_BIG_DP = 32
        const val VALUE_BIG_SP = 36f
        const val SEGMENT_W_BIG_DP = 40
        const val SEGMENT_H_BIG_DP = 36
        const val SEGMENT_GAP_BIG_DP = 8
        const val SEGMENT_RADIUS_BIG_DP = 8f
    }
}
