package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
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
 *  - fan: yatay sürükleme 1..10, dokunuş kapat/aç, altında ince 10 noktalı kademe göstergesi.
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
    private val flatBar = VehicleProfile.active.barStyle == BarStyle.FLAT
    /** Sahne: ince Manrope rakamlar (gece 300), buz mavisi fan noktaları, taslak ölçüleri. */
    private val sahne = LauncherTheme.sahne
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
    private val driver = TempCell(ClimateSide.DRIVER, R.string.launcher_climate_driver, R.string.launcher_climate_driver_short)
    private val passenger = TempCell(ClimateSide.PASSENGER, R.string.launcher_climate_passenger, R.string.launcher_climate_passenger_short)
    private val fan = FanCell()

    /** Dock bölümleri (zeminsiz, koyu dock üstünde); yerleşimi ([OurBottomBar]) belirler. */
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

    /** Tema değişti: hücrelerin renkleri, yüzey katmanları ve değer yazıları yeniden atanır (görünümler korunur). */
    fun applyTheme() {
        theme.reapply()
        driver.resetTint(); passenger.resetTint(); fan.resetTint()
        fan.invalidateLevel()
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
        private val label = text(context, if (sahne) SAHNE_TEMP_SP else if (flatBar) FLAT_TEMP_SP else TEMP_SP, medium = true).apply {
            gravity = Gravity.CENTER
            if (sahne) SahneFonts.apply(this, 400, -0.02f, dayBump = false)
            theme.bind { setTextColor(if (sahne) Sahne.NUMERAL else LauncherTheme.DOCK_TEXT) }
        }
        private val tint = GradientDrawable().apply { alpha = 0 }
        private var tintAnim: ValueAnimator? = null

        /** Tema değişti: gesture ortasında kalmış bir ton varsa sönük başlar (renk jetonlardan yeniden alınır). */
        fun resetTint() {
            tintAnim?.cancel()
            tint.alpha = 0
        }

        val view: FrameLayout = FrameLayout(context).apply {
            background = tint
            theme.bind { foreground = dockPressOverlay(context) }
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(context, if (sahne) 20f else LauncherTheme.LABEL_SP).apply {
                    text = context.getString(shortRes)
                    gravity = Gravity.CENTER
                    if (sahne) SahneFonts.apply(this, 400)
                    theme.bind { setTextColor(if (sahne) Sahne.HEADER_DATE else LauncherTheme.DOCK_TEXT_SECONDARY) }
                })
                addView(gap(context, 2, vertical = true))
                addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Dokunma bölgelerinin ipucu: sol yarı −0,5, sağ yarı +0,5.
            val chevDp = if (sahne) SAHNE_CHEVRON_DP else CHEVRON_DP
            val chev = context.dp(chevDp)
            val edge = context.dp(if (sahne) SAHNE_CHEVRON_EDGE_DP else CHEVRON_EDGE_DP)
            addView(icon(context, if (sahne) R.drawable.ic_sahne_chev_left else R.drawable.ic_mdi_chevron_left, chevDp).apply {
                alpha = if (sahne) 1f else CHEVRON_ALPHA
                theme.bind { setTint(if (sahne) Sahne.CONTROL_ICON else LauncherTheme.DOCK_TEXT_SECONDARY) }
            }, FrameLayout.LayoutParams(chev, chev, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = edge })
            addView(icon(context, if (sahne) R.drawable.ic_sahne_chev_right else R.drawable.ic_mdi_chevron_right, chevDp).apply {
                alpha = if (sahne) 1f else CHEVRON_ALPHA
                theme.bind { setTint(if (sahne) Sahne.CONTROL_ICON else LauncherTheme.DOCK_TEXT_SECONDARY) }
            }, FrameLayout.LayoutParams(chev, chev, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = edge })
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
                    view.isPressed = true
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
                        view.isPressed = false
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
                    view.isPressed = false
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
                    view.isPressed = false
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
        private val number = text(context, if (sahne) SAHNE_FAN_SP else if (flatBar) FLAT_FAN_SP else FAN_SP, medium = true).apply {
            gravity = Gravity.CENTER
            if (sahne) SahneFonts.apply(this, 400, dayBump = false)
            theme.bind { setTextColor(if (sahne) Sahne.NUMERAL else LauncherTheme.DOCK_TEXT) }
        }
        /** Sahne: noktalar yerine tek 220 × 8dp iz ve dolgu (tek bakışta okunur). */
        private val track = if (sahne) FanTrack(context) else null
        private val segments = if (sahne) emptyList() else List(ClimateMath.FAN_MAX) {
            View(context).apply { background = rounded(context, inactiveSegment(), if (sahne) SAHNE_SEGMENT_H_DP / 2f else SEGMENT_RADIUS_DP) }
        }
        private val tint = GradientDrawable().apply { alpha = 0 }
        private var tintAnim: ValueAnimator? = null
        private var level = -1

        init {
            theme.bind { tint.setColor(TINT_FAN) }
        }

        /** Tema değişti: kademe noktaları yeni renklerle yeniden boyanır (önbellekli düzey sıfırlanır). */
        fun invalidateLevel() {
            val l = level
            level = -1
            if (l >= 0) setLevel(l)
            track?.invalidate()
        }

        fun resetTint() {
            tintAnim?.cancel()
            tint.alpha = 0
        }

        val view: FrameLayout = FrameLayout(context).apply {
            background = tint
            theme.bind { foreground = dockPressOverlay(context) }
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon(context, if (sahne) R.drawable.ic_sahne_fan else R.drawable.ic_mdi_fan, if (sahne) SAHNE_FAN_ICON_DP else FAN_ICON_DP).also { ic ->
                        theme.bind { ic.setTint(if (sahne) Sahne.ICE else LauncherTheme.DOCK_TEXT_SECONDARY) }
                    })
                    addView(gap(context, if (sahne) 14 else LauncherTheme.GAP_S_DP))
                    addView(number, LinearLayout.LayoutParams(if (sahne) LinearLayout.LayoutParams.WRAP_CONTENT else context.dp(FAN_NUMBER_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(gap(context, if (sahne) 10 else LauncherTheme.GAP_SM_DP, vertical = true))
                if (track != null) addView(track, LinearLayout.LayoutParams(context.dp(SAHNE_TRACK_W_DP), context.dp(SAHNE_TRACK_H_DP)))
                else addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    segments.forEachIndexed { i, v ->
                        if (i > 0) addView(gap(context, if (sahne) SAHNE_SEGMENT_GAP_DP else SEGMENT_GAP_DP))
                        addView(v, LinearLayout.LayoutParams(context.dp(if (sahne) SAHNE_SEGMENT_W_DP else SEGMENT_W_DP), context.dp(if (sahne) SAHNE_SEGMENT_H_DP else SEGMENT_H_DP)))
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
            track?.set(n / ClimateMath.FAN_MAX.toFloat())
            segments.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setColor(if (i < n) (if (sahne) Sahne.ICE else LauncherTheme.ACCENT) else inactiveSegment())
            }
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
                        setLevel(target)
                        showTint()
                        bubble.show(view, target.toString(), null, TINT_FAN)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
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
                    view.isPressed = false
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
        private val value = text(context, BUBBLE_SP, bold = true).apply {
            gravity = Gravity.CENTER
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT) }
        }
        private val caption = text(context, LauncherTheme.LABEL_SP).apply {
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

        fun show(anchor: View, text: String, captionText: String?, tintColor: Int) {
            value.text = text
            caption.text = captionText
            caption.visibility = if (captionText == null) View.GONE else View.VISIBLE
            panel.background = rounded(context, LauncherTheme.DOCK_BOTTOM, BUBBLE_RADIUS_DP, tintColor)
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
    private fun format(c: Double): String = endLabel(c) ?: String.format(Locale.getDefault(), "%.1f°", c)

    private fun endLabel(c: Double): String? = when {
        ClimateMath.isLo(c) -> context.getString(R.string.launcher_climate_lo)
        ClimateMath.isHi(c) -> context.getString(R.string.launcher_climate_hi)
        else -> null
    }

    /** Etkin olmayan kademe noktası: dock yüzeyine göre gece %25 beyaz, gündüz %20 koyu. */
    private fun inactiveSegment(): Int = LauncherTheme.DOCK_TRACK

    /** Ekran değeri: derece işareti küçük ve ikincil tonda, rakamlar tam boy. */
    private fun styled(c: Double): CharSequence {
        endLabel(c)?.let { return it }
        val num = String.format(Locale.getDefault(), "%.1f", c)
        return SpannableStringBuilder(num).append('°').apply {
            setSpan(RelativeSizeSpan(if (sahne) 0.52f else DEGREE_SCALE), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (sahne) setSpan(android.text.style.SuperscriptSpan(), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(LauncherTheme.DOCK_TEXT_SECONDARY), num.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun tintFor(delta: Double): Int = when {
        delta > 0 -> TINT_WARM
        delta < 0 -> TINT_COOL
        else -> 0
    }

    companion object {
        private const val DASH = "—"
        /** Sahne ölçüleri (taslak): sıcaklık 46, fan 40, fan noktaları 18 × 6 / 7 aralık, ok ikonları 30 ve kenardan 21. */
        private const val SAHNE_TEMP_SP = 46f
        private const val SAHNE_FAN_SP = 40f
        private const val SAHNE_FAN_ICON_DP = 30
        private const val SAHNE_SEGMENT_W_DP = 18
        private const val SAHNE_SEGMENT_H_DP = 6
        private const val SAHNE_SEGMENT_GAP_DP = 7
        private const val SAHNE_TRACK_W_DP = 220
        private const val SAHNE_TRACK_H_DP = 8
        private const val SAHNE_CHEVRON_DP = 30
        private const val SAHNE_CHEVRON_EDGE_DP = 21
        private const val TEMP_SP = 56f
        /** Düz (Tiggo 8) ince şeritte küçültülmüş yazılar. */
        private const val FLAT_TEMP_SP = 38f
        private const val FLAT_FAN_SP = 30f
        private const val BUBBLE_SP = 56f
        private const val DEGREE_SCALE = 0.8f
        private const val CHEVRON_DP = 32
        private const val CHEVRON_EDGE_DP = 8
        private const val CHEVRON_ALPHA = 0.85f
        private const val FAN_SP = 44f
        private const val FAN_ICON_DP = 40
        private const val FAN_NUMBER_DP = 48
        private const val SEGMENT_W_DP = 17
        private const val SEGMENT_H_DP = 10
        private const val SEGMENT_GAP_DP = 5
        private const val SEGMENT_RADIUS_DP = 5f
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

        // Düşük alfalı renk tonları (aşağı serin mavi, yukarı sıcak turuncu, fan mavi) temadan gelir: LauncherTheme.DOCK_TINT_*.
        private val TINT_COOL get() = LauncherTheme.DOCK_TINT_COOL
        private val TINT_WARM get() = LauncherTheme.DOCK_TINT_WARM
        private val TINT_FAN get() = LauncherTheme.DOCK_TINT_FAN
    }
}

/** Sahne fan göstergesi: yuvarlak uçlu iz (gece #2A2F38, gündüz #DAD8D3) ve buz mavisi dolgu; yalnızca değişince çizilir. */
private class FanTrack(context: android.content.Context) : View(context) {
    private var fraction = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(f: Float) {
        val v = f.coerceIn(0f, 1f)
        if (v == fraction) return
        fraction = v
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = if (LauncherTheme.night) 0xFF2A2F38.toInt() else 0xFFDAD8D3.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction <= 0f) return
        rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
        paint.color = Sahne.ICE
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
