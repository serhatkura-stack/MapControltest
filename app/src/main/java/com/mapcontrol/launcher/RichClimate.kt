package com.mapcontrol.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import com.mapcontrol.R
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Stil A "Klima odaklı": çubuğun orta yamuğunun zengin içeriği. Üç bölüm, her biri etkileşimli:
 *  - sıcaklık (sürücü/yolcu): rakamın arkasında sete göre ısınan/soğuyan renk alanı (≤18 °C mavi, ~22 nötr, ≥27 kırmızı);
 *    yatay kaydırma ya da ‹ › dokunuşlarıyla 0,5 °C adım; uzun bas = diğer tarafı eşitle; yanında koltuk ısıtma/soğutma
 *    düğmesi (dokun = ısıtma, uzun bas = soğutma; [SeatGlow] turuncu/camgöbeği);
 *  - fan: kademeyle hızlanan dönen ikon ve etrafında 10 dilimli yay; dokununca A/C · Oto · İç/Dış hava · Klima ve
 *    kademe şeridi içeren küçük bir pencere açılır, yatay kaydırma kademeyi değiştirir.
 * Bütün yazmalar eskisiyle aynı yoldan gider: sıcaklık/fan [ClimateControl.commitTempFor]/[ClimateControl.commitFanLevel]
 * (ClimateWriter kapısı), tuşlar ComfortWriter.setClimateKey, koltuk SeatHeatModel. Doğrulanmamış yazmalar kapalıdır.
 */
internal class RichClimate(
    private val activity: Activity,
    private val io: Executor,
    private val main: Handler,
    private val climate: ClimateControl,
    /** Orta yamuğun renk geçişi (sol, sağ ARGB); çubuğun yüzeyi ([DockSurface.setTint]) uygular. */
    private val onTint: (Int, Int) -> Unit = { _, _ -> },
) {
    private val context = activity
    private val theme = ThemeBindings()

    private val heatModel = SeatHeatModel(context, vent = false) { s -> seatState(s, vent = false) }
    private val ventModel = SeatHeatModel(context, vent = true) { s -> seatState(s, vent = true) }

    private val driver = TempCell(ClimateSide.DRIVER, R.string.launcher_climate_driver)
    private val passenger = TempCell(ClimateSide.PASSENGER, R.string.launcher_climate_passenger)
    private val fan = FanCell()
    private var visible = false

    val driverView: View get() = driver.view
    val fanView: View get() = fan.view
    val passengerView: View get() = passenger.view

    init {
        climate.onRender = { render() }
    }

    fun onVisible() {
        visible = true
        heatModel.onVisible()
        ventModel.onVisible()
        fan.applySpin()
        render()
    }

    fun onHidden() {
        visible = false
        heatModel.onHidden()
        ventModel.onHidden()
        fan.stopSpin()
        fan.popover.dismiss()
    }

    fun applyTheme() {
        theme.reapply()
        fan.popover.dismiss()
        driver.refreshTheme()
        passenger.refreshTheme()
        fan.refreshTheme()
        render()
    }

    private fun render() {
        driver.show(climate.shownTempFor(ClimateSide.DRIVER))
        passenger.show(climate.shownTempFor(ClimateSide.PASSENGER))
        fan.show(climate.shownFanLevel())
        fan.popover.renderFan()
        onTint(tintFor(climate.shownTempFor(ClimateSide.DRIVER)), tintFor(climate.shownTempFor(ClimateSide.PASSENGER)))
    }

    /** Sıcaklığın orta yamuğa yaydığı renk: nötrde saydam, uçlarda hafif mavi/kırmızı-turuncu. */
    private fun tintFor(c: Double?): Int {
        if (c == null) return 0
        val f = BarMath.tempFraction(c)
        val a = abs(f)
        return SeatGlow.withAlpha(if (f < 0) coldColor() else hotColor(), (if (LauncherTheme.night) 0.34f else 0.24f) * a)
    }

    private fun seatState(s: SeatHeatState, vent: Boolean) {
        driver.seat.setLevel(vent, s.driver, s.connected)
        passenger.seat.setLevel(vent, s.passenger, s.connected)
    }

    // --- Sıcaklık hücresi ---

    private inner class TempCell(private val side: ClimateSide, private val labelRes: Int) {
        private val field = TempField()
        private val number = text(context, TEMP_SP, bold = true).apply {
            gravity = Gravity.CENTER
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT) }
        }
        val seat = SeatButton(driver = side == ClimateSide.DRIVER)
        private val chevL = chevron(R.drawable.ic_mdi_chevron_left)
        private val chevR = chevron(R.drawable.ic_mdi_chevron_right)

        /** Koltuk düğmesi iç kenarda değil dış kenarda: sürücüde solda, yolcuda sağda. */
        val view: FrameLayout = FrameLayout(context).apply {
            val seatOnLeft = side == ClimateSide.DRIVER
            val fieldStart = if (seatOnLeft) context.dp(SEAT_ZONE_DP) else 0
            addView(field, FrameLayout.LayoutParams(context.dp(FIELD_W_DP), context.dp(FIELD_H_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = fieldStart + context.dp(FIELD_GAP_DP) })
            addView(number, FrameLayout.LayoutParams(context.dp(FIELD_W_DP - 2 * CHEVRON_ZONE_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = fieldStart + context.dp(FIELD_GAP_DP + CHEVRON_ZONE_DP) })
            addView(chevL, FrameLayout.LayoutParams(context.dp(CHEVRON_DP), context.dp(CHEVRON_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = fieldStart + context.dp(FIELD_GAP_DP + (CHEVRON_ZONE_DP - CHEVRON_DP) / 2 + 2) })
            addView(chevR, FrameLayout.LayoutParams(context.dp(CHEVRON_DP), context.dp(CHEVRON_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = fieldStart + context.dp(FIELD_GAP_DP + FIELD_W_DP - CHEVRON_ZONE_DP + (CHEVRON_ZONE_DP - CHEVRON_DP) / 2 - 2) })
            val seatStart = if (seatOnLeft) 0 else context.dp(FIELD_W_DP + FIELD_GAP_DP * 2)
            addView(seat.view, FrameLayout.LayoutParams(context.dp(SEAT_ZONE_DP), context.dp(SEAT_BOX_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = seatStart })
            isClickable = true
            isFocusable = true
        }

        private fun chevron(res: Int) = icon(context, res, CHEVRON_DP).apply {
            alpha = CHEVRON_ALPHA
            theme.bind { setTint(LauncherTheme.DOCK_TEXT) }
        }

        private var start = 0.0
        private var target = 0.0
        private var downX = 0f
        private var downY = 0f
        private var active = false
        private var moved = false
        private var longFired = false
        private var shown: Double? = null
        private val longPress = Runnable {
            longFired = true
            val other = if (side == ClimateSide.DRIVER) ClimateSide.PASSENGER else ClimateSide.DRIVER
            Toast.makeText(context, context.getString(R.string.launcher_climate_sync) + " " + climate.formatTemp(start), Toast.LENGTH_SHORT).show()
            climate.commitTempFor(side, start)
            climate.commitTempFor(other, start)
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
                    val base = climate.shownTempFor(side) ?: return super.performAccessibilityAction(host, action, args)
                    return when (action) {
                        ACTION_INCREASE -> { climate.commitTempFor(side, ClimateMath.targetTemp(base, 1)); true }
                        ACTION_DECREASE -> { climate.commitTempFor(side, ClimateMath.targetTemp(base, -1)); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
        }

        fun refreshTheme() {
            field.invalidate()
            seat.refreshTheme()
        }

        fun show(c: Double?) {
            shown = c
            number.setTextSmooth(if (c == null) DASH else climate.styledTemp(c))
            field.setFraction(if (c == null) 0f else BarMath.tempFraction(c))
            view.contentDescription = context.getString(labelRes) + ", " + (if (c == null) DASH else climate.formatTemp(c))
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    field.setPressedLook(true)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val base = climate.shownTempFor(side)
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
                    val dx = e.rawX - downX
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (!moved && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) {
                        moved = true
                        field.setPressedLook(false)
                        main.removeCallbacks(longPress)
                        climate.setDragging(true)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dx, context.resources.displayMetrics.density, DRAG_DP_PER_STEP)
                        target = ClimateMath.targetTemp(start, steps)
                        number.text = climate.styledTemp(target)
                        field.setFraction(BarMath.tempFraction(target))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    field.setPressedLook(false)
                    main.removeCallbacks(longPress)
                    when {
                        !active || longFired -> Unit
                        moved -> {
                            climate.setDragging(false)
                            climate.commitTempFor(side, target)
                        }
                        else -> {
                            val dir = if (e.x < field.left + field.width / 2f) -1 else 1
                            climate.commitTempFor(side, ClimateMath.targetTemp(start, dir))
                        }
                    }
                    endGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    field.setPressedLook(false)
                    main.removeCallbacks(longPress)
                    endGesture()
                    climate.setDragging(false)
                    render()
                }
            }
            return true
        }

        private fun endGesture() {
            if (moved) climate.setDragging(false)
            active = false
            moved = false
        }
    }

    // --- Sıcaklık renk alanı ---

    /** Rakamın arkasındaki yumuşak alan: nötr iken zar zor görünür, soğukta mavi, sıcakta kırmızı-turuncu. */
    private inner class TempField : View(context) {
        private var fraction = 0f
        private var anim: ValueAnimator? = null
        private var press = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun setFraction(f: Float) {
            if (f == fraction && anim == null) return
            anim?.cancel()
            if (!isAttachedToWindow || width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
                fraction = f
                anim = null
                invalidate()
                return
            }
            val from = fraction
            anim = ValueAnimator.ofFloat(from, f).setDuration(ADAPT_MS).apply {
                addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun setPressedLook(on: Boolean) {
            if (press == on) return
            press = on
            invalidate()
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val a = abs(fraction)
            val night = LauncherTheme.night
            val base = if (fraction < 0) coldColor() else hotColor()
            val neutral = LauncherTheme.DOCK_TEXT
            val topA = if (night) 0.07f + 0.52f * a else 0.06f + 0.42f * a
            val botA = if (night) 0.03f + 0.28f * a else 0.03f + 0.22f * a
            val t = (a * 5f).coerceAtMost(1f)
            val topC = BarMath.lerpColor(SeatGlow.withAlpha(neutral, 0.07f), SeatGlow.withAlpha(base, topA), t)
            val botC = BarMath.lerpColor(SeatGlow.withAlpha(neutral, 0.03f), SeatGlow.withAlpha(base, botA), t)
            val r = context.dpF(FIELD_RADIUS_DP)
            val inset = context.dpF(1f)
            rect.set(inset, inset, width - inset, height - inset)
            fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), topC, botC, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, r, r, fill)
            if (press) {
                fill.shader = null
                fill.color = LauncherTheme.DOCK_PRESS
                canvas.drawRoundRect(rect, r, r, fill)
            }
            stroke.strokeWidth = context.dpF(1f)
            stroke.color = BarMath.lerpColor(SeatGlow.withAlpha(neutral, 0.10f), SeatGlow.withAlpha(base, if (night) 0.65f else 0.55f), a)
            canvas.drawRoundRect(rect, r, r, stroke)
            // Alt kenarda küçük parıltı çizgisi: renk alanının "sıcaklık göstergesi" gibi okunmasını sağlar.
            if (a > 0.04f) {
                val w = width * (0.28f + 0.30f * a)
                val h = context.dpF(3f)
                glow.color = SeatGlow.withAlpha(base, 0.35f + 0.55f * a)
                val cx = width / 2f
                canvas.drawRoundRect(cx - w / 2, height - inset - h - context.dpF(5f), cx + w / 2, height - inset - context.dpF(5f) + h, h / 2, h / 2, glow)
            }
        }
    }

    // --- Koltuk düğmesi ---

    /** Koltuk silüeti ([SeatSideView]) ve seviye noktaları; ısıtma turuncu, soğutma camgöbeği. Dokun = ısıt, uzun bas = soğut. */
    private inner class SeatButton(private val driver: Boolean) {
        private val heat = SeatSideView(context, vent = false)
        private val vent = SeatSideView(context, vent = true)
        private val pips = Pips()
        private var heatLevel = 0
        private var ventLevel = 0
        private var connected = false

        val view: FrameLayout = FrameLayout(context).apply {
            addView(heat, FrameLayout.LayoutParams(context.dp(SEAT_DP), context.dp(SEAT_DP), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = context.dp(2) })
            addView(vent, FrameLayout.LayoutParams(context.dp(SEAT_DP), context.dp(SEAT_DP), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = context.dp(2) })
            addView(pips, FrameLayout.LayoutParams(context.dp(PIPS_W_DP), context.dp(PIPS_H_DP), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = context.dp(2) })
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.launcher_bar_seat_heat) + " / " + context.getString(R.string.launcher_bar_seat_vent)
            setOnClickListener { heatModel.cycle(driver) }
            setOnLongClickListener { ventModel.cycle(driver); true }
            theme.bind { foreground = dockPressOverlay(context) }
            vent.visibility = View.GONE
        }

        fun refreshTheme() {
            heat.refreshTheme()
            vent.refreshTheme()
            pips.invalidate()
        }

        fun setLevel(isVent: Boolean, level: Int?, ok: Boolean) {
            connected = ok
            if (isVent) ventLevel = level ?: 0 else heatLevel = level ?: 0
            // Araç aynı anda ikisini yapmaz; ısıtma varsa o, yalnızca soğutma açıksa soğutma silüeti görünür.
            val showVent = ventLevel > 0 && heatLevel == 0
            heat.visibility = if (showVent) View.GONE else View.VISIBLE
            vent.visibility = if (showVent) View.VISIBLE else View.GONE
            heat.setLevel(heatLevel)
            vent.setLevel(ventLevel)
            pips.set(if (showVent) ventLevel else heatLevel, showVent)
            view.alpha = if (ok) 1f else 0.5f
        }
    }

    /** Üç küçük çubuk: seviye kadarı koltuğun rengiyle dolu, kalanı iz renginde. */
    private inner class Pips : View(context) {
        private var level = 0
        private var vent = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun set(l: Int, isVent: Boolean) {
            if (l == level && isVent == vent) return
            level = l
            vent = isVent
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val gap = context.dpF(3f)
            val w = (width - 2 * gap) / 3f
            val r = height / 2f
            val on = if (vent) LauncherTheme.COOL else LauncherTheme.HEAT
            for (i in 0 until 3) {
                paint.color = if (i < level) on else LauncherTheme.DOCK_TRACK
                rect.set(i * (w + gap), 0f, i * (w + gap) + w, height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    // --- Fan hücresi ---

    private inner class FanCell {
        private val dial = FanDial()
        private val fanIcon = icon(context, R.drawable.ic_mdi_fan, FAN_ICON_DP).also { ic -> theme.bind { ic.setTint(LauncherTheme.DOCK_TEXT) } }
        private val number = text(context, FAN_SP, medium = true).apply {
            gravity = Gravity.CENTER
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT) }
        }
        val popover = ClimatePopover()
        private var spin: ObjectAnimator? = null
        private var spinPeriod = -1L
        private var level: Int? = null

        val view: FrameLayout = FrameLayout(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(FrameLayout(context).apply {
                    addView(dial, FrameLayout.LayoutParams(context.dp(DIAL_DP), context.dp(DIAL_DP)))
                    addView(fanIcon, FrameLayout.LayoutParams(context.dp(FAN_ICON_DP), context.dp(FAN_ICON_DP), Gravity.CENTER))
                }, LinearLayout.LayoutParams(context.dp(DIAL_DP), context.dp(DIAL_DP)))
                addView(number, LinearLayout.LayoutParams(context.dp(FAN_NUMBER_DP), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(4) })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            theme.bind { foreground = dockPressOverlay(context) }
            isClickable = true
            isFocusable = true
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
                    val base = climate.shownFanLevel() ?: return super.performAccessibilityAction(host, action, args)
                    return when (action) {
                        ACTION_INCREASE -> { climate.commitFanLevel(ClimateMath.targetFan(base, 1)); true }
                        ACTION_DECREASE -> { climate.commitFanLevel(ClimateMath.targetFan(base, -1)); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
        }

        fun refreshTheme() = dial.invalidate()

        fun show(l: Int?) {
            level = l
            number.setTextSmooth(l?.toString() ?: DASH)
            dial.set(l ?: 0)
            applySpin()
            view.contentDescription = context.getString(R.string.launcher_climate_fan) + ", " + (l?.toString() ?: DASH)
        }

        /** İkon, kademeyle hızlanan sabit dönüş; kademe 0 ya da animasyonlar kapalı (sistem ölçeği 0) ya da gizliyken durur. */
        fun applySpin() {
            val period = BarMath.fanPeriodMs(level)
            if (period == null || !visible || !ValueAnimator.areAnimatorsEnabled()) { stopSpin(); return }
            if (spin?.isRunning == true && spinPeriod == period) return
            val from = fanIcon.rotation % 360f
            spin?.cancel()
            spinPeriod = period
            spin = ObjectAnimator.ofFloat(fanIcon, View.ROTATION, from, from + 360f).apply {
                duration = period
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        fun stopSpin() {
            spin?.cancel()
            spin = null
            spinPeriod = -1L
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val base = climate.shownFanLevel()
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
                        climate.setDragging(true)
                    }
                    if (moved) {
                        val steps = ClimateMath.stepsForDrag(dx, context.resources.displayMetrics.density, ClimateMath.FAN_DRAG_DP_PER_STEP)
                        target = ClimateMath.targetFan(start, steps)
                        number.text = target.toString()
                        dial.set(target)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    when {
                        !active -> popover.toggle(view)
                        moved -> {
                            climate.setDragging(false)
                            climate.rememberOnFan(target)
                            climate.commitFanLevel(target)
                        }
                        else -> popover.toggle(view)
                    }
                    active = false
                    moved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (moved) climate.setDragging(false)
                    active = false
                    moved = false
                    render()
                }
            }
            return true
        }
    }

    /** 10 dilimli yay: altta açık 270° yay; kademe kadar dilim vurgu renginde. */
    private inner class FanDial : View(context) {
        private var level = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun set(l: Int) {
            val n = l.coerceIn(0, ClimateMath.FAN_MAX)
            if (n == level) return
            level = n
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val sw = context.dpF(DIAL_STROKE_DP)
            paint.strokeWidth = sw
            val pad = sw / 2f + context.dpF(1f)
            oval.set(pad, pad, width - pad, height - pad)
            val count = ClimateMath.FAN_MAX
            val sweepTotal = 270f
            val gapDeg = 5f
            val seg = (sweepTotal - gapDeg * (count - 1)) / count
            for (i in 0 until count) {
                paint.color = if (i < level) LauncherTheme.ACCENT else LauncherTheme.DOCK_TRACK
                canvas.drawArc(oval, 135f + i * (seg + gapDeg), seg, false, paint)
            }
        }
    }

    // --- Fan penceresi (A/C · Oto · İç/Dış hava · Klima, kademe şeridi) ---

    private inner class ClimatePopover {
        private val pending = HashMap<ClimateKey, Boolean>()
        private var keys: ClimateKeysReading? = null
        private var connected = false
        private val chips = linkedMapOf(
            ClimateKey.AC to PopChip(R.drawable.ic_mdi_snowflake, R.string.launcher_keys_ac),
            ClimateKey.AUTO to PopChip(R.drawable.ic_mdi_thermometer, R.string.launcher_keys_auto),
            ClimateKey.RECIRC to PopChip(R.drawable.ic_mdi_refresh, R.string.launcher_keys_recirc),
            ClimateKey.POWER to PopChip(R.drawable.ic_mdi_power, R.string.launcher_keys_power),
        )
        private val segments = List(ClimateMath.FAN_MAX) { i ->
            View(context).apply {
                isClickable = true
                contentDescription = context.getString(R.string.launcher_bar_fan_title) + " " + (i + 1)
                setOnClickListener { setFan(i + 1) }
            }
        }
        private val title = text(context, LauncherTheme.LABEL_SP).apply {
            setText(R.string.launcher_bar_fan_title)
            theme.bind { setTextColor(LauncherTheme.DOCK_TEXT_SECONDARY) }
        }
        private val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dp(PANEL_PAD_DP)
            setPadding(p, p, p, p)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                chips.values.forEachIndexed { i, c ->
                    if (i > 0) addView(gap(context, 8))
                    addView(c.view, LinearLayout.LayoutParams(context.dp(CHIP_W_DP), context.dp(CHIP_H_DP)))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(gap(context, 14, vertical = true))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(gap(context, 8, vertical = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                segments.forEachIndexed { i, v ->
                    if (i > 0) addView(gap(context, 4))
                    addView(v, LinearLayout.LayoutParams(0, context.dp(SEG_H_DP), 1f))
                }
            }, LinearLayout.LayoutParams(context.dp(CHIP_W_DP * 4 + 8 * 3), LinearLayout.LayoutParams.WRAP_CONTENT))
            theme.bind { background = rounded(context, LauncherTheme.DOCK_BOTTOM, PANEL_RADIUS_DP, LauncherTheme.DOCK_RIM) }
        }
        private val popup = PopupWindow(panel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = context.dpF(12f)
            setOnDismissListener {
                main.removeCallbacks(autoClose)
                main.removeCallbacks(refresh)
            }
        }
        private val autoClose = Runnable { dismiss() }
        private val revert = Runnable { pending.clear(); renderKeys(); refreshNow() }
        private val refresh = Runnable { refreshNow() }

        fun toggle(anchor: View) {
            if (popup.isShowing) { dismiss(); return }
            if (activity.isFinishing || activity.isDestroyed) return
            pending.clear()
            renderKeys()
            renderFan()
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = panel.measuredWidth
            val h = panel.measuredHeight
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val x = loc[0] + anchor.width / 2 - w / 2
            val y = loc[1] - h - context.dp(POPUP_GAP_DP)
            popup.width = w
            popup.height = h
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            bumpAutoClose()
            refreshNow()
        }

        fun dismiss() {
            if (popup.isShowing) popup.dismiss()
        }

        private fun bumpAutoClose() {
            main.removeCallbacks(autoClose)
            main.postDelayed(autoClose, AUTO_CLOSE_MS)
        }

        private fun refreshNow() {
            if (!popup.isShowing) return
            io.runSafely {
                val r = ComfortReader.readClimateKeys()
                main.post {
                    if (!popup.isShowing) return@post
                    keys = r.keys
                    connected = r.connected
                    renderKeys()
                }
            }
        }

        private fun shown(key: ClimateKey): Boolean? = pending[key] ?: ClimateKeysMath.current(keys, key).takeIf { connected }

        fun renderKeys() {
            chips.forEach { (key, chip) ->
                val on = shown(key)
                if (key == ClimateKey.RECIRC) chip.setLabel(if (on == false) R.string.launcher_keys_fresh else R.string.launcher_keys_recirc)
                chip.bind(on)
            }
        }

        fun renderFan() {
            val l = climate.shownFanLevel() ?: 0
            segments.forEachIndexed { i, v -> v.background = rounded(context, if (i < l) LauncherTheme.ACCENT else LauncherTheme.DOCK_TRACK, SEG_RADIUS_DP) }
        }

        private fun toggleKey(key: ClimateKey) {
            bumpAutoClose()
            val current = shown(key)
            // Okunamıyorsa (test sürümü dışında) tahmin etme.
            if (current == null && !LauncherEdition.READ_ONLY) return
            val target = !(current ?: false)
            pending[key] = target
            val enabled = ComfortWriter.climateKeysEnabled
            if (enabled) io.runSafely { ComfortWriter.setClimateKey(key, target) }
            renderKeys()
            main.removeCallbacks(revert)
            main.postDelayed(revert, if (enabled) CONFIRM_MS else REVERT_MS)
            if (enabled) main.postDelayed(refresh, REFRESH_AFTER_MS)
            else Toast.makeText(context, if (LauncherEdition.READ_ONLY) R.string.launcher_read_only_comfort else R.string.launcher_comfort_pending, Toast.LENGTH_SHORT).show()
        }

        private fun setFan(level: Int) {
            bumpAutoClose()
            climate.rememberOnFan(level)
            climate.commitFanLevel(level)
        }

        private inner class PopChip(iconRes: Int, private var labelRes: Int) {
            private val iconView = icon(context, iconRes, CHIP_ICON_DP)
            private val label = text(context, CHIP_SP, medium = true).apply { gravity = Gravity.CENTER; setText(labelRes) }
            private var on: Boolean? = null
            val view: LinearLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                addView(iconView)
                addView(gap(context, 4, vertical = true))
                addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { toggleKey(chips.entries.first { it.value === this@PopChip }.key) }
                pressFeedback()
            }

            fun setLabel(res: Int) {
                labelRes = res
                label.setText(res)
            }

            fun bind(state: Boolean?) {
                on = state
                val active = state == true
                view.background = rounded(context, if (active) LauncherTheme.ACCENT else LauncherTheme.DOCK_TRACK, CHIP_RADIUS_DP)
                val fg = if (active) LauncherTheme.ON_ACCENT else LauncherTheme.DOCK_TEXT
                iconView.setTint(fg)
                label.setTextColor(fg)
                view.alpha = if (state == null && !LauncherEdition.READ_ONLY) 0.5f else 1f
                view.contentDescription = context.getString(labelRes) + ", " + context.getString(if (active) R.string.launcher_comfort_on else R.string.launcher_comfort_off)
            }
        }
    }

    private companion object {
        const val DASH = "—"
        const val TEMP_SP = 40f
        const val FAN_SP = 30f
        const val FAN_NUMBER_DP = 38
        const val FAN_ICON_DP = 30
        const val DIAL_DP = 66
        const val DIAL_STROKE_DP = 4f

        /** Sürücü hücresi 248 genişlik: [koltuk 56][boşluk 6][alan 180][boşluk 6]; yolcuda ters. */
        const val SEAT_ZONE_DP = 56
        const val SEAT_BOX_DP = 62
        const val SEAT_DP = 48
        const val PIPS_W_DP = 30
        const val PIPS_H_DP = 4
        const val FIELD_W_DP = 180
        const val FIELD_H_DP = 62
        const val FIELD_GAP_DP = 6
        const val FIELD_RADIUS_DP = 18f
        const val CHEVRON_DP = 24
        const val CHEVRON_ZONE_DP = 32
        const val CHEVRON_ALPHA = 0.55f
        const val ADAPT_MS = 220L

        /** Yatay sürükleme: her 22dp = 1 adım (0,5 °C); alan 180dp'de tüm aralık ~3 hareket. */
        const val DRAG_DP_PER_STEP = 22f

        const val PANEL_PAD_DP = 16
        const val PANEL_RADIUS_DP = 24f
        const val CHIP_W_DP = 116
        const val CHIP_H_DP = 92
        const val CHIP_ICON_DP = 32
        const val CHIP_SP = 22f
        const val CHIP_RADIUS_DP = 18f
        const val SEG_H_DP = 44
        const val SEG_RADIUS_DP = 8f
        const val POPUP_GAP_DP = 14
        const val AUTO_CLOSE_MS = 9_000L
        const val REVERT_MS = 1_500L
        const val CONFIRM_MS = 4_000L
        const val REFRESH_AFTER_MS = 800L

        const val ACTION_INCREASE = 0x1C01
        const val ACTION_DECREASE = 0x1C02

        fun coldColor(): Int = if (LauncherTheme.night) 0xFF3F8CFF.toInt() else 0xFF2563EB.toInt()
        fun hotColor(): Int = if (LauncherTheme.night) 0xFFFF5A2E.toInt() else 0xFFE04512.toInt()
    }
}
