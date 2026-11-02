package com.mapcontrol.launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.mapcontrol.R

/** Birleşik koltuğun etkin modu: kapalı, ısıtma ya da soğutma. */
internal enum class SeatMode { OFF, HEAT, COOL }

/** Koltuğun gösterilen durumu: [known] false iken araçtan okunamıyor. [level] etkin modun seviyesi (1..3; kapalıda 0). */
internal data class SeatShown(val known: Boolean, val mode: SeatMode, val level: Int)

/** Seçicideki hedef: ısıtma ya da soğutma seviyesi (1..3) veya kapalı. */
internal data class SeatTarget(val mode: SeatMode, val level: Int = 0)

/** Tek yazma: [vent] true soğutma, false ısıtma; [level] 0..3 (kodlama [SeatHeatModel] içindedir: seviye + 1 yazılır). */
internal data class SeatWrite(val vent: Boolean, val level: Int)

/**
 * Birleşik koltuğun saf kuralları (JVM'de sınanır). Isıtma ve soğutma aynı anda açık olmaz: birini seçmek önce ötekini kapatır.
 */
internal object SeatChoice {

    /**
     * Okunan seviyelerden gösterilecek durum. Isıtma ölçüldü; soğutma sinyali okunamazsa 0 sayılır (bkz. [SeatHeatModel.decodeVent]).
     * İkisi birden açık okunursa (beklenmez) ısıtma gösterilir.
     */
    fun shown(connected: Boolean, heat: Int?, vent: Int?): SeatShown {
        if (!connected || heat == null) return SeatShown(false, SeatMode.OFF, 0)
        val h = heat.coerceIn(0, MAX)
        val v = (vent ?: 0).coerceIn(0, MAX)
        return when {
            h > 0 -> SeatShown(true, SeatMode.HEAT, h)
            v > 0 -> SeatShown(true, SeatMode.COOL, v)
            else -> SeatShown(true, SeatMode.OFF, 0)
        }
    }

    /**
     * [target] için sırayla gönderilecek yazmalar: ısıtma seçilirken soğutma açıksa önce soğutma kapatılır (ve tersi);
     * zaten istenen seviyedeki sinyale yazılmaz. Kapalı: açık olan(lar) kapatılır.
     */
    fun plan(heat: Int, vent: Int, target: SeatTarget): List<SeatWrite> {
        val out = ArrayList<SeatWrite>(2)
        val level = target.level.coerceIn(1, MAX)
        when (target.mode) {
            SeatMode.HEAT -> {
                if (vent > 0) out += SeatWrite(vent = true, level = 0)
                if (heat != level) out += SeatWrite(vent = false, level = level)
            }
            SeatMode.COOL -> {
                if (heat > 0) out += SeatWrite(vent = false, level = 0)
                if (vent != level) out += SeatWrite(vent = true, level = level)
            }
            SeatMode.OFF -> {
                if (heat > 0) out += SeatWrite(vent = false, level = 0)
                if (vent > 0) out += SeatWrite(vent = true, level = 0)
            }
        }
        return out
    }

    /** Koltuk görselinin anahtarı ([SahneArt]): ısıtma/soğutma seviyesi varsa kendi görseli, yoksa kapalı koltuk. */
    fun artKey(mode: SeatMode, level: Int): String = when {
        level > 0 && mode == SeatMode.HEAT -> "seat_heat_on"
        level > 0 && mode == SeatMode.COOL -> "seat_vent_on"
        else -> "seat_off"
    }

    /** Seçicide bu düğme şu anki durum mu (vurgulu çizilir). */
    fun isCurrent(shown: SeatShown, target: SeatTarget): Boolean =
        shown.known && shown.mode == target.mode && (target.mode == SeatMode.OFF || shown.level == target.level)

    const val MAX = 3
}

/**
 * Birleşik koltuk widget'ı (taraf başına bir tane): ısıtma (turuncu, "Isıtma 2"), soğutma (buz mavisi, "Soğutma 1") ya da
 * "Kapalı"; seviye dilimleri ve duruma bağlı koltuk görseli (yolcu yatay çevrilir). Dokunuş öğenin üstünde küçük bir seçici
 * açar: "Isıtma" 1·2·3, "Soğutma" 1·2·3 ve "Kapalı"; tek dokunuş yazar ve kapanır. Büyük biçimde (4×4, Sahne paneli) büyük
 * görsel ve aynı satırlar panelin içindedir. Okuma/yazma iki [SeatHeatModel] ile (alt çubukla aynı kod yolu ve doğrulama
 * bayrakları); ikisi aynı sıralı kuyruğu paylaşır ki "önce kapat, sonra aç" sırası korunsun.
 */
internal class SeatTile(
    private val activity: Activity,
    private val driverSeat: Boolean,
) : SizedWidget, Themeable, StripSource {

    private val io = LauncherIo.serial()
    private var heat = SeatHeatState()
    private var vent = SeatHeatState()
    private val heatModel = SeatHeatModel(activity, vent = false, io = io) { heat = it; bind() }
    private val ventModel = SeatHeatModel(activity, vent = true, io = io) { vent = it; bind() }

    private var shown = SeatShown(false, SeatMode.OFF, 0)
    private var big: Boolean? = null
    private var popup: PopupWindow? = null
    private var dismissedAt = 0L

    private val titleRes = if (driverSeat) R.string.launcher_widget_seat_driver else R.string.launcher_widget_seat_passenger

    private val art = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleX = if (driverSeat) 1f else -1f
    }
    private val artBox = FrameLayout(activity).apply { addView(art, FrameLayout.LayoutParams(MATCH, MATCH)) }
    private var artKey: String? = null
    private val title = sahneOrText(TITLE_SP, 400)
    private val value = sahneOrText(VALUE_SP, 500).apply {
        maxLines = 1
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 16, VALUE_SP.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val pips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    /** Büyük biçimin seçici satırları (yeniden bağlanınca vurgu güncellenir). */
    private var inlineChooser: Chooser? = null

    private val root = LinearLayout(activity).apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { if (big != true) openChooser(this) }
        pressFeedback()
    }

    override val view: View get() = root

    override fun applySpan(span: Span) {
        val b = span.w >= BIG_MIN_COLS && span.h >= BIG_MIN_COLS
        if (b == big) return
        big = b
        arrange(b)
        bind()
    }

    /** Küçük: görsel solda, başlık/değer/dilimler sağda. Büyük: başlık, büyük görsel, değer ve satır içi seçici. */
    private fun arrange(b: Boolean) {
        listOf(artBox, title, value, pips).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        root.removeAllViews()
        inlineChooser = null
        root.background = if (LauncherTheme.sahne) null else cardSurface(activity)
        // Büyük biçimde dokunuş satır içi düğmelerdedir; kök dokunuş almaz.
        root.isClickable = !b
        val maxSp = if (b) BIG_VALUE_SP else VALUE_SP
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value, 16, maxSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        title.textSize = if (b) BIG_TITLE_SP else TITLE_SP
        if (!b) {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            val p = activity.dp(SMALL_PAD_DP)
            root.setPadding(p, p, p, p)
            root.addView(artBox, LinearLayout.LayoutParams(activity.dp(SMALL_ART_W_DP), activity.dp(SMALL_ART_DP)))
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(value, LinearLayout.LayoutParams(MATCH, activity.dp(VALUE_H_DP)))
                addView(pips, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = activity.dp(6) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = activity.dp(10) })
            return
        }
        // Büyük: üstte başlık; altında solda büyük koltuk görseli, değer ve dilimler, sağda satır içi seçici (etiket üstte, 1·2·3 altında).
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.NO_GRAVITY
        root.setPadding(activity.dp(BIG_PAD_DP), activity.dp(BIG_PAD_TOP_DP), activity.dp(BIG_PAD_DP), activity.dp(BIG_PAD_DP))
        root.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        val c = Chooser(onPicked = {}, stacked = true)
        inlineChooser = c
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(artBox, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = activity.dp(8) })
                addView(value, LinearLayout.LayoutParams(MATCH, activity.dp(BIG_VALUE_H_DP)))
                addView(pips, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = activity.dp(8); bottomMargin = activity.dp(4) })
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(c.view, LinearLayout.LayoutParams(activity.dp(BIG_CHOOSER_W_DP), WRAP).apply { marginStart = activity.dp(16) })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = activity.dp(6) })
    }

    override fun onVisible() {
        heatModel.onVisible()
        ventModel.onVisible()
    }

    override fun onHidden() {
        heatModel.onHidden()
        ventModel.onHidden()
        popup?.dismiss()
    }

    override fun release() {
        popup?.dismiss()
    }

    override fun applyTheme() {
        big?.let { arrange(it) }
        artKey = null
        bind()
    }

    // --- Şerit ---

    override var onStripChanged: (() -> Unit)? = null
    private var stripNow: StripState? = null
    override fun stripState(): StripState? = stripNow
    override fun stripTap(part: Int) = openChooser(root)
    override fun stripTapFrom(anchor: View, part: Int) = openChooser(anchor)

    // --- Durum ---

    private fun heatColor(): Int = LauncherTheme.HEAT
    /** Soğutma: buz mavisi (Sahne'de [Sahne.ICE]). */
    private fun coolColor(): Int = if (LauncherTheme.sahne) Sahne.ICE else LauncherTheme.COOL
    private fun ink(): Int = if (LauncherTheme.sahne) Sahne.INK else LauncherTheme.TEXT
    private fun ink3(): Int = if (LauncherTheme.sahne) Sahne.INK_3 else LauncherTheme.TEXT_SECONDARY

    private fun valueText(s: SeatShown): String = when {
        !s.known -> activity.getString(R.string.launcher_unavailable)
        s.mode == SeatMode.HEAT -> activity.getString(R.string.launcher_seat_heat_level, s.level)
        s.mode == SeatMode.COOL -> activity.getString(R.string.launcher_seat_cool_level, s.level)
        else -> activity.getString(R.string.launcher_seat_off)
    }

    private fun bind() {
        val s = SeatChoice.shown(heat.connected, if (driverSeat) heat.driver else heat.passenger,
            if (driverSeat) vent.driver else vent.passenger)
        shown = s
        val color = when (s.mode) { SeatMode.HEAT -> heatColor(); SeatMode.COOL -> coolColor(); SeatMode.OFF -> ink() }
        title.text = activity.getString(titleRes)
        title.setTextColor(ink3())
        val text = valueText(s)
        value.setTextSmooth(text)
        value.setTextColor(if (!s.known) ink3() else color)
        bindPips(s)
        val tone = when { !s.known -> StripTone.MUTED; s.mode == SeatMode.HEAT -> StripTone.HEAT; s.mode == SeatMode.COOL -> StripTone.COOL; else -> StripTone.NORMAL }
        val key = SahneArt.seatKey(tone, s.level)
        if (key != artKey) {
            artKey = key
            art.setImageDrawable(SahneArt.drawable(activity, key))
        }
        art.colorFilter = SahneArt.filter(false)
        artBox.background = SahneArt.spotlight()
        root.contentDescription = activity.getString(titleRes) + ", " + text
        root.isEnabled = s.known || LauncherEdition.READ_ONLY
        inlineChooser?.bind(s)
        (popup?.contentView?.tag as? Chooser)?.bind(s)
        stripNow = StripState(activity.getString(titleRes), text, R.drawable.ic_mdi_car_seat_heater, tone,
            level = if (s.mode == SeatMode.OFF) 0 else s.level, levels = SeatChoice.MAX)
        onStripChanged?.invoke()
    }

    private fun bindPips(s: SeatShown) {
        if (pips.childCount != SeatChoice.MAX) {
            pips.removeAllViews()
            repeat(SeatChoice.MAX) { i ->
                pips.addView(View(activity), LinearLayout.LayoutParams(activity.dp(PIP_W_DP), activity.dp(PIP_H_DP)).apply {
                    if (i > 0) marginStart = activity.dp(5)
                })
            }
        }
        val on = when (s.mode) { SeatMode.HEAT -> heatColor(); SeatMode.COOL -> coolColor(); SeatMode.OFF -> 0 }
        val track = if (LauncherTheme.sahne) Sahne.TRACK else LauncherTheme.TRACK
        for (i in 0 until SeatChoice.MAX) {
            pips.getChildAt(i).background = rounded(activity, if (s.known && i < s.level && on != 0) on else track, PIP_H_DP / 2f)
        }
    }

    // --- Yazma ---

    private fun choose(target: SeatTarget) {
        val h = (if (driverSeat) heat.driver else heat.passenger) ?: 0
        val v = (if (driverSeat) vent.driver else vent.passenger) ?: 0
        val plan = SeatChoice.plan(h, v, target)
        // Hedefin kendisi yazılamıyorsa (soğutma araç testinden önce kapalı) hiçbir şeye dokunma: ısıtmayı boşuna kapatma.
        val blocked = plan.firstOrNull { it.level > 0 && !(if (it.vent) ventModel else heatModel).writable }
        if (blocked != null) {
            (if (blocked.vent) ventModel else heatModel).set(driverSeat, blocked.level) // yalnızca açıklamayı gösterir
            return
        }
        for (w in plan) {
            val model = if (w.vent) ventModel else heatModel
            // Kapatma yazılamıyorsa (soğutma doğrulanmadı; okuması da hep 0) sessizce atlanır.
            if (!model.writable && !LauncherEdition.READ_ONLY) continue
            model.set(driverSeat, w.level)
        }
    }

    // --- Seçici ---

    /** Öğenin üstünde (yer yoksa altında) küçük seçici; dışına dokununca kapanır. */
    private fun openChooser(anchor: View) {
        if (activity.isFinishing || activity.isDestroyed || !anchor.isAttachedToWindow) return
        if (popup?.isShowing == true) { popup?.dismiss(); return }
        // Öğeye ikinci dokunuş: önce dışarı dokunuşla kapandı, hemen yeniden açılmasın (aç/kapa gibi davranır).
        if (android.os.SystemClock.uptimeMillis() - dismissedAt < REOPEN_GUARD_MS) return
        val chooser = Chooser(onPicked = { popup?.dismiss() })
        chooser.bind(shown)
        val content = chooser.view.apply {
            tag = chooser
            background = if (LauncherTheme.sahne) GlassDrawable(activity, Sahne.STRIP_RADIUS_DP) else rounded(activity, LauncherTheme.CARD, LauncherTheme.CARD_RADIUS_DP, LauncherTheme.TRACK)
            val p = activity.dp(CHOOSER_PAD_DP)
            setPadding(p, p, p, p)
        }
        val w = activity.dp(CHOOSER_W_DP)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = content.measuredHeight
        // Odak almayan pencere: sistem gezinme çubuğu geri gelmez, ekran kaymaz; dışarı dokunuş seçiciyi kapatır.
        val pw = PopupWindow(content, w, h, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isTouchable = true
            isOutsideTouchable = true
            elevation = activity.dpF(12f)
            setOnDismissListener { popup = null; dismissedAt = android.os.SystemClock.uptimeMillis() }
        }
        popup = pw
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = activity.window.decorView.width
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(activity.dp(12), maxOf(activity.dp(12), screenW - w - activity.dp(12)))
        val gapPx = activity.dp(CHOOSER_GAP_DP)
        val above = loc[1] - h - gapPx
        val y = if (above >= activity.dp(8)) above else loc[1] + anchor.height + gapPx
        content.alpha = 0f
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        content.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    /** "Isıtma" 1·2·3, "Soğutma" 1·2·3 ve "Kapalı"; şu anki durum vurgulu. Dokunuş yazar ve [onPicked] çağırır. */
    private inner class Chooser(private val onPicked: () -> Unit, private val stacked: Boolean = false) {
        private val buttons = ArrayList<Pair<SeatTarget, TextView>>()
        val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        init {
            addMode(R.string.launcher_seat_row_heat, SeatMode.HEAT, first = true)
            addMode(R.string.launcher_seat_row_cool, SeatMode.COOL, first = false)
            view.addView(button(SeatTarget(SeatMode.OFF), activity.getString(R.string.launcher_seat_off)),
                LinearLayout.LayoutParams(MATCH, activity.dp(BTN_DP)).apply { topMargin = activity.dp(ROW_GAP_DP) })
        }

        /** Yan yana (seçici: etiket solda) ya da üst üste (panel: etiket üstte, düğmeler tam genişlikte). */
        private fun addMode(labelRes: Int, mode: SeatMode, first: Boolean) {
            val label = sahneOrText(if (stacked) STACKED_LABEL_SP else LABEL_SP, 500).apply {
                setText(labelRes)
                setTextColor(if (mode == SeatMode.HEAT) heatColor() else coolColor())
                maxLines = 1
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (!stacked) addView(label, LinearLayout.LayoutParams(activity.dp(LABEL_W_DP), WRAP))
                for (l in 1..SeatChoice.MAX) {
                    addView(button(SeatTarget(mode, l), l.toString()), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        if (l > 1 || !stacked) marginStart = activity.dp(BTN_GAP_DP)
                    })
                }
            }
            if (stacked) view.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (!first) topMargin = activity.dp(ROW_GAP_DP) })
            view.addView(row, LinearLayout.LayoutParams(MATCH, activity.dp(BTN_DP)).apply {
                topMargin = activity.dp(if (stacked) 4 else if (first) 0 else ROW_GAP_DP)
            })
        }

        private fun button(target: SeatTarget, label: String): TextView = sahneOrText(BTN_SP, 600).apply {
            text = label
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val rowName = when (target.mode) {
                SeatMode.HEAT -> activity.getString(R.string.launcher_seat_row_heat)
                SeatMode.COOL -> activity.getString(R.string.launcher_seat_row_cool)
                SeatMode.OFF -> null
            }
            contentDescription = if (rowName == null) label else activity.getString(R.string.launcher_seat_level_desc, rowName, target.level)
            setOnClickListener {
                choose(target)
                onPicked()
            }
            pressFeedback()
            buttons += target to this
        }

        fun bind(s: SeatShown) {
            val fill = if (LauncherTheme.sahne) Sahne.SOFT_FILL else LauncherTheme.CONTROL
            val hair = if (LauncherTheme.sahne) Sahne.HAIR_2 else 0
            buttons.forEach { (t, b) ->
                val current = SeatChoice.isCurrent(s, t)
                val c = when (t.mode) { SeatMode.HEAT -> heatColor(); SeatMode.COOL -> coolColor(); SeatMode.OFF -> ink() }
                b.background = if (current) rounded(activity, (c and 0x00FFFFFF) or 0x2E000000, BTN_RADIUS_DP, c)
                else rounded(activity, fill, BTN_RADIUS_DP, hair)
                b.setTextColor(if (current) c else ink())
                b.isSelected = current
            }
        }
    }

    private fun sahneOrText(sp: Float, weight: Int): TextView =
        if (LauncherTheme.sahne) sahneText(activity, sp, Sahne.INK, weight) else text(activity, sp, LauncherTheme.TEXT, medium = weight >= 500)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val BIG_MIN_COLS = 4
        const val SMALL_PAD_DP = 16
        const val SMALL_ART_DP = 72
        const val SMALL_ART_W_DP = 80
        const val BIG_PAD_DP = 18
        const val BIG_PAD_TOP_DP = 12
        const val TITLE_SP = 15f
        const val VALUE_SP = 26f
        const val VALUE_H_DP = 34
        const val BIG_VALUE_H_DP = 46
        const val BIG_VALUE_SP = 36f
        const val BIG_TITLE_SP = 20f
        const val PIP_W_DP = 22
        const val PIP_H_DP = 6
        const val CHOOSER_W_DP = 440
        const val CHOOSER_PAD_DP = 14
        const val CHOOSER_GAP_DP = 10
        const val LABEL_W_DP = 108
        const val BIG_CHOOSER_W_DP = 236
        const val STACKED_LABEL_SP = 15f
        const val LABEL_SP = 18f
        const val BTN_DP = LauncherTheme.TOUCH_MIN_DP
        const val BTN_SP = 22f
        const val BTN_GAP_DP = 8
        const val BTN_RADIUS_DP = 16f
        const val ROW_GAP_DP = 8
        const val FADE_MS = 120L
        const val REOPEN_GUARD_MS = 350L
    }
}
