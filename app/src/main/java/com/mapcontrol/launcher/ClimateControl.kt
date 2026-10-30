package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.mapcontrol.R
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * "Bizimki" alt çubuğunun klima bölümleri: sürücü sıcaklığı, fan, yolcu sıcaklığı. Her biri ayrı bir kart yüzeyidir
 * (çubuk üçünü ayrı yerleştirir) ve etkileşimlidir:
 *  - sıcaklık: dikey sürükleme (her 40dp = 0,5 °C, yukarı = sıcak), dokunuşta sağ yarı +0,5 / sol yarı −0,5,
 *    uzun basış diğer tarafı bu değere eşitler;
 *  - fan: yatay sürükleme 1..7, dokunuş kapat/aç, altında ince 7 noktalı kademe göstergesi.
 *
 * Bırakınca değer "işlenir" ([commit]): yazma doğrulanmadıkça ([ClimateWriter.enabled] false) ekran ~1,5 sn sonra
 * aracın gerçek değerine döner ve sakin bir bilgi mesajı çıkar; araca hiçbir şey gitmez. Sürekli animasyon yok;
 * yalnızca kısa (≤200 ms) renk/alfa geçişleri.
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

    private val bubble = ValueBubble()
    private val driver = TempCell(ClimateSide.DRIVER, R.string.launcher_climate_driver, R.string.launcher_climate_driver_short)
    private val passenger = TempCell(ClimateSide.PASSENGER, R.string.launcher_climate_passenger, R.string.launcher_climate_passenger_short)
    private val fan = FanCell()

    /** Ayrı yüzeyler; yerleşimi ([OurBottomBar]) belirler. */
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

    private inner class TempCell(private val side: ClimateSide, private val labelRes: Int, shortRes: Int) {
        private val label = text(context, TEMP_SP, medium = true).apply { gravity = Gravity.CENTER }
        private val tint = GradientDrawable().apply {
            cornerRadius = context.dpF(LauncherTheme.CARD_RADIUS_DP)
            alpha = 0
        }
        private var tintAnim: ValueAnimator? = null

        val view: FrameLayout = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(cardSurface(context), tint))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                    text = context.getString(shortRes)
                    gravity = Gravity.CENTER
                })
                addView(gap(context, 2, vertical = true))
                addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Dokunma bölgelerinin ipucu: sol yarı −0,5, sağ yarı +0,5.
            val chev = context.dp(CHEVRON_DP)
            val edge = context.dp(CHEVRON_EDGE_DP)
            addView(icon(context, R.drawable.ic_mdi_chevron_left, CHEVRON_DP, LauncherTheme.TEXT_SECONDARY).apply { alpha = CHEVRON_ALPHA },
                FrameLayout.LayoutParams(chev, chev, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = edge })
            addView(icon(context, R.drawable.ic_mdi_chevron_right, CHEVRON_DP, LauncherTheme.TEXT_SECONDARY).apply { alpha = CHEVRON_ALPHA },
                FrameLayout.LayoutParams(chev, chev, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = edge })
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
            val other = if (side == ClimateSide.DRIVER) ClimateSide.PASSENGER else ClimateSide.DRIVER
            bubble.show(view, format(start), context.getString(R.string.launcher_climate_sync), 0)
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

        fun show(c: Double?) {
            label.setTextSmooth(if (c == null) DASH else styled(c))
            view.contentDescription = context.getString(labelRes) + ", " + (if (c == null) DASH else format(c))
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
                        main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> if (active && !longFired) {
                    val dy = downY - e.rawY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (!moved && abs(dy) > slop && abs(dy) > abs(e.rawX - downX)) {
                        moved = true
                        dragging = true
                        main.removeCallbacks(longPress)
                        main.removeCallbacks(revert)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dy, context.resources.displayMetrics.density, ClimateMath.TEMP_DRAG_DP_PER_STEP)
                        target = ClimateMath.targetTemp(start, steps)
                        label.text = styled(target)
                        val color = tintFor(target - start)
                        showTint(color)
                        bubble.show(view, format(target), null, color)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        !active || longFired -> Unit
                        moved -> {
                            dragging = false
                            bubble.dismiss()
                            fadeTint()
                            commitTemp(side, target)
                        }
                        else -> {
                            val dir = if (e.x < view.width / 2f) -1 else 1
                            showTint(tintFor(dir.toDouble()))
                            fadeTint()
                            commitTemp(side, ClimateMath.targetTemp(start, dir))
                        }
                    }
                    endGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    bubble.dismiss()
                    fadeTint()
                    endGesture()
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

        private fun showTint(color: Int) {
            tintAnim?.cancel()
            tint.setColor(color)
            tint.alpha = 255
        }

        private fun fadeTint() {
            tintAnim?.cancel()
            tintAnim = ValueAnimator.ofInt(tint.alpha, 0).setDuration(TINT_FADE_MS).apply {
                addUpdateListener { tint.alpha = it.animatedValue as Int }
                start()
            }
        }
    }

    // --- Fan hücresi ---

    private inner class FanCell {
        private val number = text(context, FAN_SP, medium = true).apply { gravity = Gravity.CENTER }
        private val segments = List(ClimateMath.FAN_MAX) {
            View(context).apply { background = rounded(context, inactiveSegment(), SEGMENT_RADIUS_DP) }
        }
        private val tint = GradientDrawable().apply {
            cornerRadius = context.dpF(LauncherTheme.CARD_RADIUS_DP)
            setColor(TINT_FAN)
            alpha = 0
        }
        private var tintAnim: ValueAnimator? = null
        private var level = -1

        val view: FrameLayout = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(cardSurface(context), tint))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon(context, R.drawable.ic_mdi_fan, FAN_ICON_DP, LauncherTheme.TEXT_SECONDARY))
                    addView(gap(context, LauncherTheme.GAP_S_DP))
                    addView(number, LinearLayout.LayoutParams(context.dp(FAN_NUMBER_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(gap(context, LauncherTheme.GAP_SM_DP, vertical = true))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    segments.forEachIndexed { i, v ->
                        if (i > 0) addView(gap(context, SEGMENT_GAP_DP))
                        addView(v, LinearLayout.LayoutParams(context.dp(SEGMENT_W_DP), context.dp(SEGMENT_H_DP)))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
            number.setTextSmooth(l?.toString() ?: DASH)
            setLevel(l ?: 0)
            view.contentDescription = context.getString(R.string.launcher_climate_fan) + ", " + (l?.toString() ?: DASH)
        }

        /** Kademe kadar nokta vurgu renginde, kalanı iz renginde; animasyonsuz, yalnızca değişince. */
        private fun setLevel(l: Int) {
            val n = l.coerceIn(0, ClimateMath.FAN_MAX)
            if (n == level) return
            level = n
            segments.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setColor(if (i < n) LauncherTheme.ACCENT else inactiveSegment())
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                        dragging = true
                        main.removeCallbacks(revert)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dx, context.resources.displayMetrics.density, ClimateMath.FAN_DRAG_DP_PER_STEP)
                        target = ClimateMath.targetFan(start, steps)
                        number.text = target.toString()
                        setLevel(target)
                        showTint()
                        bubble.show(view, target.toString(), null, TINT_FAN)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when {
                        !active -> Unit
                        moved -> {
                            dragging = false
                            bubble.dismiss()
                            fadeTint()
                            commitFan(target)
                        }
                        else -> {
                            showTint()
                            fadeTint()
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
                    bubble.dismiss()
                    fadeTint()
                    if (moved) dragging = false
                    active = false
                    moved = false
                    render()
                }
            }
            return true
        }

        private fun showTint() {
            tintAnim?.cancel()
            tint.alpha = 255
        }

        private fun fadeTint() {
            tintAnim?.cancel()
            tintAnim = ValueAnimator.ofInt(tint.alpha, 0).setDuration(TINT_FADE_MS).apply {
                addUpdateListener { tint.alpha = it.animatedValue as Int }
                start()
            }
        }
    }

    // --- Değer baloncuğu ---

    /** Kapsülün üstünde büyük değer; ayrı bir pencere (dokunmayı geçirir), çubuğun kırpmasına takılmaz. */
    private inner class ValueBubble {
        private val value = text(context, BUBBLE_SP, bold = true).apply { gravity = Gravity.CENTER }
        private val caption = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
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

        fun show(anchor: View, text: String, captionText: String?, tintColor: Int) {
            value.text = text
            caption.text = captionText
            caption.visibility = if (captionText == null) View.GONE else View.VISIBLE
            panel.background = rounded(context, LauncherTheme.CONTROL, BUBBLE_RADIUS_DP, tintColor)
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

    private fun format(c: Double): String = String.format(Locale.US, "%.1f°", c)

    /** Etkin olmayan kademe noktası: ikincil metin renginin soluk hâli; gecede de görünür kalır. */
    private fun inactiveSegment(): Int = (LauncherTheme.TEXT_SECONDARY and 0x00FFFFFF) or 0x59000000

    /** Ekran değeri: derece işareti küçük ve ikincil tonda, rakamlar tam boy. */
    private fun styled(c: Double): CharSequence {
        val num = String.format(Locale.US, "%.1f", c)
        return SpannableStringBuilder(num).append('°').apply {
            setSpan(RelativeSizeSpan(DEGREE_SCALE), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(LauncherTheme.TEXT_SECONDARY), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun tintFor(delta: Double): Int = when {
        delta > 0 -> TINT_WARM
        delta < 0 -> TINT_COOL
        else -> 0
    }

    companion object {
        private const val DASH = "—"
        private const val TEMP_SP = 44f
        private const val BUBBLE_SP = 56f
        private const val DEGREE_SCALE = 0.7f
        private const val CHEVRON_DP = 28
        private const val CHEVRON_EDGE_DP = 8
        private const val CHEVRON_ALPHA = 0.6f
        private const val FAN_SP = 32f
        private const val FAN_ICON_DP = 32
        private const val FAN_NUMBER_DP = 36
        private const val SEGMENT_W_DP = 18
        private const val SEGMENT_H_DP = 8
        private const val SEGMENT_GAP_DP = 6
        private const val SEGMENT_RADIUS_DP = 4f
        private const val BUBBLE_RADIUS_DP = 20f
        private const val BUBBLE_MIN_WIDTH_DP = 140
        private const val BUBBLE_GAP_DP = 12

        /** Yazma kapalıyken ekranın araçtaki değere dönme gecikmesi. */
        private const val REVERT_MS = 1_500L
        /** Yazma açıkken hedefin, araç okuması yetişene kadar gösterileceği en uzun süre. */
        private const val CONFIRM_MS = 4_000L
        private const val SYNC_SHOW_MS = 900L
        private const val TINT_FADE_MS = 200L
        private const val FAN_DEFAULT_ON = 2

        private const val ACTION_INCREASE = 0x1C01
        private const val ACTION_DECREASE = 0x1C02

        // Düşük alfalı renk tonları: aşağı = serin mavi, yukarı = sıcak turuncu (~%22), fan nötr mavi.
        private val TINT_COOL = 0x3860A5FA.toInt()
        private val TINT_WARM = 0x38FB923C.toInt()
        private val TINT_FAN = 0x3860A5FA.toInt()
    }
}
