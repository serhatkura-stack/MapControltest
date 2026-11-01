package com.mapcontrol.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.mapcontrol.R
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * "Bizimki" alt çubuğunun klima bölümleri: sürücü sıcaklığı, fan, yolcu sıcaklığı. Zeminsiz; çubuğun orta yamuğunda
 * durur ([OurBottomBar] yerleştirir). Yazı öncelikli: ince, sabit genişlikli rakamlar; renk yalnızca fan kademesinde.
 *  - sıcaklık: dokunuşta sağ yarı +0,5 / sol yarı −0,5 (basılan yarı hafifçe aydınlanır), dikey sürükleme
 *    (her 40dp = 0,5 °C, yukarı = sıcak), uzun basış diğer tarafı bu değere eşitler;
 *  - fan: dokunuş kapat/aç, yatay sürükleme 1..10; altında ince kademe çizgisi.
 *
 * Bırakınca değer "işlenir" ([commit]): yazma doğrulanmadıkça ([ClimateWriter.enabled] false) ekran ~1,5 sn sonra
 * aracın gerçek değerine döner ve sakin bir bilgi mesajı çıkar; araca hiçbir şey gitmez. Sürekli animasyon yok.
 */
internal class ClimateControl(
    private val context: Context,
    private val io: Executor,
    private val main: Handler,
) {
    private var readout = ClimateReadout()
    private var pendingDriver: Double? = null
    private var pendingPassenger: Double? = null
    private var pendingFan: Int? = null
    private var lastOnFan = FAN_DEFAULT_ON
    private var dragging = false
    private var toast: Toast? = null

    /** Tema bağlı atamalar (kurucuda bir kez, [applyTheme]'de yeniden); bkz. [ThemeBindings]. */
    private val theme = ThemeBindings()
    private val bubble = ValueBubble()
    private val driver = TempCell(ClimateSide.DRIVER, R.string.launcher_climate_driver)
    private val passenger = TempCell(ClimateSide.PASSENGER, R.string.launcher_climate_passenger)
    private val fan = FanCell()

    val driverView: View get() = driver.view
    val fanView: View get() = fan.view
    val passengerView: View get() = passenger.view

    private val revert = Runnable {
        pendingDriver = null
        pendingPassenger = null
        pendingFan = null
        render()
    }

    /** Aracın son okuması; bekleyen (henüz işlenmiş) bir değer varken ekran onu gösterir. */
    fun bind(r: ClimateReadout) {
        readout = r
        render()
    }

    /** Tema değişti: renkler jetonlardan yeniden alınır, değerler yeniden yazılır (görünümler korunur). */
    fun applyTheme() {
        theme.reapply()
        fan.invalidateLevel()
        driver.invalidate()
        passenger.invalidate()
        render()
    }

    /** Ekran gizlenirken: baloncuk ve bekleyen geri dönüş temizlenir. */
    fun onHidden() {
        main.removeCallbacks(revert)
        bubble.dismiss()
        toast?.cancel()
        dragging = false
        revert.run()
    }

    // --- Durum ---

    private fun shownTemp(side: ClimateSide): Double? =
        if (side == ClimateSide.DRIVER) pendingDriver ?: readout.driverTemp else pendingPassenger ?: readout.passengerTemp

    private fun shownFan(): Int? = pendingFan ?: readout.fan

    private fun render() {
        if (dragging) return
        driver.show(shownTemp(ClimateSide.DRIVER))
        passenger.show(shownTemp(ClimateSide.PASSENGER))
        fan.show(shownFan())
    }

    // --- Onay ("commit") ---

    private fun commitTemp(side: ClimateSide, celsius: Double) {
        if (side == ClimateSide.DRIVER) pendingDriver = celsius else pendingPassenger = celsius
        afterCommit()
        if (ClimateWriter.enabled) io.runSafely { ClimateWriter.setTemperature(side, celsius) }
    }

    private fun commitFan(level: Int) {
        pendingFan = level
        if (level > 0) lastOnFan = level
        afterCommit()
        if (ClimateWriter.enabled) io.runSafely { ClimateWriter.setFan(level) }
    }

    /**
     * Yazma kapalıysa ~1,5 sn sonra araçtaki değere dön ve bilgi ver; açıksa aracın yeni değeri okunana
     * (en geç [CONFIRM_MS]) kadar hedefi göster.
     */
    private fun afterCommit() {
        render()
        main.removeCallbacks(revert)
        if (ClimateWriter.enabled) {
            main.postDelayed(revert, CONFIRM_MS)
            return
        }
        main.postDelayed(revert, REVERT_MS)
        val msg = if (LauncherEdition.READ_ONLY) R.string.launcher_read_only_climate else R.string.launcher_climate_pending
        toast?.cancel()
        toast = Toast.makeText(context, msg, Toast.LENGTH_LONG).also { it.show() }
    }

    // --- Sıcaklık hücresi ---

    /**
     * Ortada büyük ince rakam, iki yanda ince ‹ › (OEM x'inde: hücre kenarından 31). Derece işareti ikincil tonda ve
     * rakamların dışına asılır: rakamlar hücrenin tam ortasında kalır. Değer yoksa "—" ve oklar sönük.
     */
    private inner class TempCell(private val side: ClimateSide, private val labelRes: Int) {
        private val label = text(context, TEMP_SP).apply {
            typeface = NUMERAL_FACE
            gravity = Gravity.CENTER
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT) }
        }
        private val chevLeft = chevron(R.drawable.ic_bar_chevron_left)
        private val chevRight = chevron(R.drawable.ic_bar_chevron_right)
        /** Basılan yarının yumuşak örtüsü (sol yarı −, sağ yarı +). */
        private val halfPress = View(context).apply {
            visibility = View.INVISIBLE
            theme.bind { background = rounded(context, LauncherTheme.DOCK_PRESS, PRESS_RADIUS_DP) }
        }
        private var shown: Double? = null
        private var hasValue = false

        val view: FrameLayout = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            val inset = context.dp(PRESS_INSET_DP)
            addView(halfPress, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT).apply { setMargins(inset, inset, inset, inset) })
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val chev = context.dp(CHEVRON_DP)
            val edge = context.dp(CHEVRON_CENTER_DP - CHEVRON_DP / 2)
            addView(chevLeft, FrameLayout.LayoutParams(chev, chev, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = edge })
            addView(chevRight, FrameLayout.LayoutParams(chev, chev, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = edge })
        }

        private fun chevron(res: Int): ImageView = icon(context, res, CHEVRON_DP).apply {
            theme.bind { setTint(LauncherTheme.DOCK_TEXT_SECONDARY) }
        }

        private var start = 0.0
        private var target = 0.0
        private var downX = 0f
        private var downY = 0f
        private var active = false
        private var moved = false
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            showHalf(null)
            val other = if (side == ClimateSide.DRIVER) ClimateSide.PASSENGER else ClimateSide.DRIVER
            bubble.show(view, format(start), context.getString(R.string.launcher_climate_sync))
            commitTemp(side, start)
            commitTemp(other, start)
            main.postDelayed({ if (!dragging) bubble.dismiss() }, SYNC_SHOW_MS)
        }

        init {
            view.setOnTouchListener { _, e -> touch(e) }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INCREASE, context.getString(R.string.launcher_climate_increase)))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_DECREASE, context.getString(R.string.launcher_climate_decrease)))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    val base = shownTemp(side) ?: return super.performAccessibilityAction(host, action, args)
                    return when (action) {
                        ACTION_INCREASE -> { commitTemp(side, ClimateMath.targetTemp(base, 1)); true }
                        ACTION_DECREASE -> { commitTemp(side, ClimateMath.targetTemp(base, -1)); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
        }

        /** Tema değişti: önbellekli değer bırakılır ki bir sonraki [show] renkli derece işaretini yeniden kursun. */
        fun invalidate() { hasValue = false }

        fun show(c: Double?) {
            if (hasValue && c == shown) return
            hasValue = true
            shown = c
            setLabel(c)
            val chevAlpha = if (c == null) EMPTY_ALPHA else 1f
            chevLeft.alpha = chevAlpha
            chevRight.alpha = chevAlpha
            view.contentDescription = context.getString(labelRes) + ", " + (if (c == null) DASH else format(c))
        }

        private fun setLabel(c: Double?) {
            if (c == null) {
                label.text = DASH
                label.setTextColor(LauncherTheme.DOCK_TEXT_SECONDARY)
                label.translationX = 0f
                return
            }
            label.setTextColor(LauncherTheme.DOCK_TEXT)
            val end = endLabel(c)
            label.text = end ?: styled(c)
            // Derece işareti asılı: rakam grubu hücre ortasında kalsın diye yarı genişliği kadar sağa kayar.
            label.translationX = if (end == null) label.paint.measureText(DEGREE) / 2f else 0f
        }

        /** Basılan yarıyı gösterir (−1 sol, +1 sağ) ya da gizler (null). */
        private fun showHalf(dir: Int?) {
            if (dir == null) { halfPress.visibility = View.INVISIBLE; return }
            val lp = halfPress.layoutParams as FrameLayout.LayoutParams
            lp.width = view.width / 2 - context.dp(PRESS_INSET_DP) * 2
            lp.gravity = if (dir < 0) Gravity.START else Gravity.END
            halfPress.layoutParams = lp
            halfPress.visibility = View.VISIBLE
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val base = shownTemp(side)
                    active = base != null
                    moved = false
                    longFired = false
                    if (base != null) {
                        start = base
                        target = base
                        downX = e.rawX
                        downY = e.rawY
                        showHalf(if (e.x < view.width / 2f) -1 else 1)
                        main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> if (active && !longFired) {
                    val dy = downY - e.rawY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (!moved && abs(dy) > slop && abs(dy) > abs(e.rawX - downX)) {
                        moved = true
                        showHalf(null)
                        dragging = true
                        main.removeCallbacks(longPress)
                        main.removeCallbacks(revert)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dy, context.resources.displayMetrics.density, ClimateMath.TEMP_DRAG_DP_PER_STEP)
                        target = ClimateMath.targetTemp(start, steps)
                        setLabel(target)
                        bubble.show(view, format(target), null)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    showHalf(null)
                    when {
                        !active || longFired -> Unit
                        moved -> {
                            dragging = false
                            bubble.dismiss()
                            hasValue = false
                            commitTemp(side, target)
                        }
                        else -> commitTemp(side, ClimateMath.targetTemp(start, if (e.x < view.width / 2f) -1 else 1))
                    }
                    endGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    showHalf(null)
                    bubble.dismiss()
                    endGesture()
                    hasValue = false
                    render()
                }
            }
            return true
        }

        private fun endGesture() {
            if (moved) dragging = false
            active = false
            moved = false
        }
    }

    // --- Fan hücresi ---

    /**
     * Fan simgesi ve kademe rakamı yan yana (grup x 960'ta ortalı), altında ince kademe çizgisi: dolu kısım
     * vurgu renginde. Çubuktaki tek renk budur ve yalnızca fan çalışırken görünür.
     */
    private inner class FanCell {
        private val number = text(context, FAN_SP).apply {
            typeface = NUMERAL_FACE
            gravity = Gravity.CENTER
        }
        private val fanIcon = icon(context, R.drawable.ic_bar_fan, FAN_ICON_DP)
        private val fill = View(context)
        private val track = FrameLayout(context).apply {
            theme.bind { background = rounded(context, LauncherTheme.DOCK_TRACK, LEVEL_H_DP / 2f) }
            addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        private var level = -1
        private var hasValue = true

        /** Tema değişti: kademe çizgisi ve renkler yeniden boyanır (önbellekli düzey sıfırlanır). */
        fun invalidateLevel() {
            val l = level
            level = -1
            if (l >= 0) setLevel(l, hasValue)
        }

        val view: FrameLayout = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            theme.bind { foreground = dockPress(context) }
            // Simge + rakam tek grup olarak hücre ortasında (OEM fan merkezi x 960); ince çizgi grubun altında.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(fanIcon, LinearLayout.LayoutParams(context.dp(FAN_ICON_DP), context.dp(FAN_ICON_DP)))
                addView(number, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = context.dp(FAN_GAP_DP) })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(FAN_ICON_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = context.dp(CONTENT_CY_DP - FAN_ICON_DP / 2) })
            addView(track, FrameLayout.LayoutParams(context.dp(LEVEL_W_DP), context.dp(LEVEL_H_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = context.dp(LEVEL_TOP_DP) })
        }

        private var start = 0
        private var target = 0
        private var downX = 0f
        private var downY = 0f
        private var active = false
        private var moved = false

        init {
            view.setOnTouchListener { _, e -> touch(e) }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_INCREASE, context.getString(R.string.launcher_climate_increase)))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_DECREASE, context.getString(R.string.launcher_climate_decrease)))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    val base = shownFan() ?: return super.performAccessibilityAction(host, action, args)
                    return when (action) {
                        ACTION_INCREASE -> { commitFan(ClimateMath.targetFan(base, 1)); true }
                        ACTION_DECREASE -> { commitFan(ClimateMath.targetFan(base, -1)); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
        }

        fun show(l: Int?) {
            number.text = l?.toString() ?: DASH
            setLevel(l ?: 0, l != null)
            view.contentDescription = context.getString(R.string.launcher_climate_fan) + ", " + (l?.toString() ?: DASH)
        }

        /** Rakam/simge rengi (kapalı ya da veri yok: ikincil) ve dolu çizgi oranı; yalnızca değişince. */
        private fun setLevel(l: Int, known: Boolean) {
            val n = l.coerceIn(0, ClimateMath.FAN_MAX)
            if (n == level && known == hasValue) return
            level = n
            hasValue = known
            val on = known && n > 0
            val fg = if (on) LauncherTheme.DOCK_TEXT else LauncherTheme.DOCK_TEXT_SECONDARY
            number.setTextColor(fg)
            fanIcon.setTint(fg)
            fanIcon.alpha = if (known) 1f else EMPTY_ALPHA
            fill.background = rounded(context, LauncherTheme.ACCENT, LEVEL_H_DP / 2f)
            val lp = fill.layoutParams
            lp.width = context.dp(LEVEL_W_DP) * n / ClimateMath.FAN_MAX
            fill.layoutParams = lp
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val base = shownFan()
                    active = base != null
                    moved = false
                    if (base != null) {
                        start = base
                        target = base
                        downX = e.rawX
                        downY = e.rawY
                    }
                }
                MotionEvent.ACTION_MOVE -> if (active) {
                    val dx = e.rawX - downX
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (!moved && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) {
                        moved = true
                        view.isPressed = false
                        dragging = true
                        main.removeCallbacks(revert)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dx, context.resources.displayMetrics.density, ClimateMath.FAN_DRAG_DP_PER_STEP)
                        target = ClimateMath.targetFan(start, steps)
                        number.text = target.toString()
                        setLevel(target, true)
                        bubble.show(view, target.toString(), null)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    when {
                        !active -> Unit
                        moved -> {
                            dragging = false
                            bubble.dismiss()
                            commitFan(target)
                        }
                        else -> {
                            // Dokunuş: açıksa kapat (0), kapalıysa son açık kademeye dön.
                            if (start > 0) lastOnFan = start
                            commitFan(if (start > 0) 0 else lastOnFan)
                        }
                    }
                    if (moved) dragging = false
                    active = false
                    moved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    bubble.dismiss()
                    if (moved) dragging = false
                    active = false
                    moved = false
                    render()
                }
            }
            return true
        }
    }

    // --- Değer baloncuğu ---

    /** Sürüklerken parmağın kapattığı değer, hücrenin üstünde büyük yazılır; ayrı pencere, dokunmayı geçirir. */
    private inner class ValueBubble {
        private val value = text(context, BUBBLE_SP).apply {
            typeface = NUMERAL_FACE
            gravity = Gravity.CENTER
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT) }
        }
        private val caption = text(context, LauncherTheme.BODY_SP).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT_SECONDARY) }
        }
        private val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = context.dp(LauncherTheme.GAP_SM_DP)
            setPadding(p * 2, p, p * 2, p)
            addView(value)
            addView(caption)
        }
        private val popup = PopupWindow(panel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isTouchable = false
            isOutsideTouchable = false
            elevation = context.dpF(8f)
        }

        fun show(anchor: View, text: String, captionText: String?) {
            value.text = text
            caption.text = captionText
            caption.visibility = if (captionText == null) View.GONE else View.VISIBLE
            panel.background = rounded(context, LauncherTheme.DOCK_WELL, BUBBLE_RADIUS_DP, LauncherTheme.DOCK_EDGE)
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = maxOf(panel.measuredWidth, context.dp(BUBBLE_MIN_WIDTH_DP))
            val h = panel.measuredHeight
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val x = loc[0] + anchor.width / 2 - w / 2
            val y = loc[1] - h - context.dp(BUBBLE_GAP_DP)
            if (popup.isShowing) popup.update(x, y, w, h)
            else {
                popup.width = w
                popup.height = h
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        }

        fun dismiss() {
            if (popup.isShowing) popup.dismiss()
        }
    }

    /** Uçlar "LO" / "HI" yazılır (derece işareti yok); aradaki değerler 0,5 °C adımlı. */
    private fun format(c: Double): String = endLabel(c) ?: String.format(Locale.US, "%.1f°", c)

    private fun endLabel(c: Double): String? = when {
        ClimateMath.isLo(c) -> context.getString(R.string.launcher_climate_lo)
        ClimateMath.isHi(c) -> context.getString(R.string.launcher_climate_hi)
        else -> null
    }

    /** Ekran değeri: rakamlar tam ton, derece işareti ikincil tonda (aynı boyda; glif zaten üstte durur). */
    private fun styled(c: Double): CharSequence {
        val num = String.format(Locale.US, "%.1f", c)
        return SpannableStringBuilder(num).append(DEGREE).apply {
            setSpan(ForegroundColorSpan(LauncherTheme.DOCK_TEXT_SECONDARY), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    companion object {
        private const val DASH = "—"
        private const val DEGREE = "°"
        /** Çubuk rakamları: Roboto Light, sabit genişlikli (tnum, [text]). */
        private val NUMERAL_FACE: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private const val TEMP_SP = 40f
        private const val FAN_SP = 30f
        private const val BUBBLE_SP = 56f
        private const val CHEVRON_DP = 28
        /** Ok merkezi hücre kenarından 31 (OEM x 661/838 ve 1082/1260). */
        private const val CHEVRON_CENTER_DP = 31
        private const val PRESS_INSET_DP = 6
        private const val PRESS_RADIUS_DP = 14f
        /** Sönük öğe (değer yok): oklar ve fan simgesi. */
        private const val EMPTY_ALPHA = 0.4f
        /** Hücre içi içerik merkezi (OEM simge/yazı merkezi y 670 → gövde içinde 39). */
        private const val CONTENT_CY_DP = 37
        private const val FAN_ICON_DP = 32
        private const val FAN_GAP_DP = 8
        /** Kademe çizgisi: OEM'deki 84x5 çizginin yerinde, daha ince. */
        private const val LEVEL_W_DP = 72
        private const val LEVEL_H_DP = 3
        private const val LEVEL_TOP_DP = 64
        private const val BUBBLE_RADIUS_DP = 20f
        private const val BUBBLE_MIN_WIDTH_DP = 140
        private const val BUBBLE_GAP_DP = 12

        /** Yazma kapalıyken ekranın araçtaki değere dönme gecikmesi. */
        private const val REVERT_MS = 1_500L
        /** Yazma açıkken hedefin, araç okuması yetişene kadar gösterileceği en uzun süre. */
        private const val CONFIRM_MS = 4_000L
        private const val SYNC_SHOW_MS = 900L
        private const val FAN_DEFAULT_ON = 2

        private const val ACTION_INCREASE = 0x1C01
        private const val ACTION_DECREASE = 0x1C02
    }
}
