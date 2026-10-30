package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.mapcontrol.R
import java.util.concurrent.Executor

/**
 * Konfor kutucuklarının ortak gövdesi: 3 sn'de bir araçtan okur, dokunuşta hedefi hemen gösterir ("iyimser"),
 * komut yalnızca [ComfortWriter] kapısı açıksa gider. Kapı kapalıyken (araç testi öncesi) ekran ~1,5 sn sonra aracın
 * gerçek durumuna döner ve bilgi mesajı gösterilir; klima kapsülüyle aynı davranış.
 */
internal abstract class ComfortTile(protected val context: Context) : SizedWidget {

    protected val main = Handler(Looper.getMainLooper())
    protected val io: Executor = LauncherIo.serial()
    protected var reading = ComfortReading()
        private set

    private var startup: LauncherStartup.Deferred? = null
    private var toast: Toast? = null

    private val poll = object : Runnable {
        override fun run() {
            refreshNow()
            main.postDelayed(this, POLL_MS)
        }
    }

    private val revert = Runnable { renderIfIdle() }

    /** Okumayı ve bekleyen (iyimser) hedefi ekrana yansıtır. */
    protected abstract fun render()

    /** Kullanıcı bir şey sürüklüyorsa çizimi atlar (örn. perde parmağı izlerken). */
    protected open fun busy(): Boolean = false

    /** Bekleyen iyimser hedefleri temizler (revert/okuma sonrası). */
    protected abstract fun clearPending()

    override fun onVisible() {
        render()
        startup?.cancel()
        startup = LauncherStartup.later { main.post(poll) }
    }

    override fun onHidden() {
        startup?.cancel()
        main.removeCallbacks(poll)
        main.removeCallbacks(revert)
        toast?.cancel()
        onStopped()
    }

    override fun release() = Unit

    protected open fun onStopped() = Unit

    private fun refreshNow() {
        io.runSafely {
            val read = ComfortReader.read()
            main.post {
                if (read == reading) return@post
                reading = read
                // Araçtan yeni değer geldi: iyimser hedef artık gereksiz.
                if (!busy()) { clearPending(); render() }
            }
        }
    }

    private fun renderIfIdle() {
        clearPending()
        if (!busy()) render()
    }

    /**
     * Bir değişiklik işlendi. Yazma açıksa aracın yeni değeri okunana kadar ([CONFIRM_MS]) hedef gösterilir;
     * kapalıysa ~[REVERT_MS] sonra araçtakine dönülür ve neden söylenir.
     */
    protected fun afterCommit(writeEnabled: Boolean) {
        render()
        main.removeCallbacks(revert)
        if (writeEnabled) {
            main.postDelayed(revert, CONFIRM_MS)
            return
        }
        main.postDelayed(revert, REVERT_MS)
        val msg = if (LauncherEdition.READ_ONLY) R.string.launcher_read_only_comfort else R.string.launcher_comfort_pending
        toast?.cancel()
        toast = Toast.makeText(context, msg, Toast.LENGTH_LONG).also { it.show() }
    }

    protected fun onColor(on: Boolean) = if (on) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT

    protected fun onFill() = DriveAccent.buttonFill(LauncherTheme.ACCENT)

    companion object {
        const val POLL_MS = 3_000L
        const val REVERT_MS = 1_500L
        const val CONFIRM_MS = 2_500L
    }
}

// ---------------------------------------------------------------------------------------------
// Tavan perdesi
// ---------------------------------------------------------------------------------------------

/**
 * Tavan perdesi: üstten bakışla çatı camı ve üzerini örten "perde". Aşağı sürükle = kapat (perde parmağı izler),
 * yukarı sürükle = aç. Dokun = araçtaki gibi adım (kapalı -> yarı -> tam açık, açıkken ters). Basılı sürükle = parmak
 * inikken hareket, bırakınca durur; bunun için durdurma değeri ([ComfortWriter.SUNSHADE_STOP_VALUE]) gerekir, o
 * bilinene kadar sürükleme dokunma adımına döner.
 */
internal class SunshadeTile(context: Context) : ComfortTile(context) {

    private val roof = RoofView(context)
    private val title = text(context, LABEL_SP, LauncherTheme.TEXT_SECONDARY)
    private val stateText = text(context, VALUE_SP, LauncherTheme.TEXT, medium = true)
    private val iconView: ImageView = icon(context, R.drawable.ic_mdi_car_sunroof, ICON_DP, LauncherTheme.TEXT_SECONDARY)

    private val runner = ComfortWriter.SunshadeRunner(main, io)

    /** İyimser hedef (açıklık 0..1) ve onun durumu; null = araçtaki gösterilir. */
    private var pendingOpen: Float? = null
    private var dragging = false

    /** Son adımın yönü (true açma); yarıdayken sıradaki adımın yönünü belirler. Bilinmiyorsa null. */
    private var lastOpening: Boolean? = null

    /** Üst satır, ortalı: ikon + başlık (yüksek kutu) ya da ikon + durum (alçak kutu). */
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val bottomGap = gap(context, 8, vertical = true)

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, PADDING_DP).apply {
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(gap(context, 8, vertical = true))
        addView(roof, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomGap)
        isClickable = true
        isFocusable = true
    }

    init {
        title.setText(R.string.launcher_widget_sunshade)
        installTouch()
    }

    override val view: View get() = root

    override fun applySpan(span: Span) {
        // Yüksek (2x4): üstte ikon + başlık, perde, altta ortalı büyük durum. Alçak (2x2): ikon + durum tek satırda, perde şerit.
        val tall = span.h >= TALL_ROWS
        header.removeAllViews()
        (stateText.parent as? android.view.ViewGroup)?.removeView(stateText)
        header.addView(iconView)
        header.addView(gap(context, 10))
        if (tall) {
            header.addView(title)
            root.addView(stateText, root.indexOfChild(bottomGap) + 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            stateText.gravity = Gravity.CENTER
        } else {
            header.addView(stateText)
        }
        bottomGap.visibility = if (tall) View.VISIBLE else View.GONE
        val pad = context.dp(if (tall) PADDING_DP else COMPACT_PADDING_DP)
        root.setPadding(pad, pad, pad, pad)
    }

    override fun busy() = dragging

    override fun clearPending() { pendingOpen = null }

    override fun onStopped() {
        runner.cancel()
        roof.cancelAnim()
    }

    override fun render() {
        val state = reading.sunshade
        val known = reading.connected && state != SunshadeState.UNKNOWN
        val pending = pendingOpen
        val target = pending ?: state.openFraction
        roof.known = known || pending != null
        if (target != null && !dragging) roof.animateTo(1f - target)
        val open = (target ?: 0f) >= 1f && known || pending == 1f
        val label = when {
            pending != null -> if (pending >= 1f) R.string.launcher_sunshade_opening else R.string.launcher_sunshade_closing
            !known -> R.string.launcher_unavailable
            else -> when (state) {
                SunshadeState.CLOSED -> R.string.launcher_sunshade_closed
                SunshadeState.OPENING -> R.string.launcher_sunshade_opening
                SunshadeState.CLOSING -> R.string.launcher_sunshade_closing
                SunshadeState.OPEN -> R.string.launcher_sunshade_open
                else -> R.string.launcher_sunshade_partial
            }
        }
        val str = context.getString(label)
        stateText.setTextSmooth(str)
        val tint = if (open) LauncherTheme.ACCENT else LauncherTheme.TEXT_SECONDARY
        iconView.imageTintList = ColorStateList.valueOf(tint)
        stateText.setTextColor(if (known || pending != null) (if (open) LauncherTheme.ACCENT else LauncherTheme.TEXT) else LauncherTheme.TEXT_SECONDARY)
        root.contentDescription = context.getString(R.string.launcher_widget_sunshade) + ", " + str
        root.isEnabled = known || LauncherEdition.READ_ONLY
    }

    // --- Dokunma / sürükleme ---

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var startCoverage = 0f
        var moved = false
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    moved = false
                    startCoverage = roof.coverage
                    v.animate().scaleX(LauncherTheme.PRESS_SCALE).scaleY(LauncherTheme.PRESS_SCALE).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!moved && Math.abs(dy) > slop) {
                        moved = true
                        dragging = true
                        roof.cancelAnim()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (moved) roof.setCoverage(ComfortMath.dragCoverage(startCoverage, dy, roof.height.toFloat()))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
                    val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                    if (moved) {
                        dragging = false
                        if (cancelled) render() else onDragEnd(startCoverage)
                    } else if (!cancelled) {
                        v.performClick()
                        onTap()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun onTap() {
        val state = reading.sunshade
        // Bilinmeyen durumda (okunamıyor) yalnızca test sürümünde açıklama göster; gerçekte komut yok.
        // Araçtaki düğme gibi: kapalı -> yarı -> tam açık, açıkken ters yön.
        val target = ComfortMath.tapStep(state, lastOpening) ?: if (LauncherEdition.READ_ONLY) 0.5f else return
        val cur = state.openFraction?.takeUnless { state.moving }
        lastOpening = if (cur != null) target > cur else target >= 0.5f
        commit(target, ComfortMath.stepPlan(cur, target))
    }

    private fun onDragEnd(startCoverage: Float) {
        val startOpen = 1f - startCoverage
        // Durdurma değeri bilinmiyor: sürükleme araçtaki adıma dönüşür (perde yerine döner, tek adım atılır).
        if (!ComfortWriter.partialPositionKnown) {
            roof.animateTo(startCoverage)
            onTap()
            return
        }
        val target = ComfortMath.releaseTarget(startOpen, roof.coverage, ComfortWriter.partialPositionKnown)
        // Başlangıçla aynı uca bırakıldıysa komut yok: perde yerine döner.
        if (target == startOpen && (target == 0f || target == 1f)) {
            roof.animateTo(1f - target)
            return
        }
        commit(target)
    }

    private fun commit(
        targetOpen: Float,
        steps: List<SunshadeStep> = ComfortMath.planSunshade(reading.sunshade.openFraction?.takeUnless { reading.sunshade.moving }, targetOpen),
    ) {
        pendingOpen = targetOpen
        roof.animateTo(1f - targetOpen)
        if (ComfortWriter.sunshadeEnabled) {
            runner.run(steps)
        }
        afterCommit(ComfortWriter.sunshadeEnabled)
    }

    // --- Çizim ---

    /**
     * Üstten stilize çatı camı: cam yüzeyi, üstten aşağı uzanan perde (kumaş çizgileriyle) ve alt kenarında tutamak.
     * [coverage] 0 = perde tamamen çekilmiş (açık), 1 = cam tamamen örtülü (kapalı).
     */
    private class RoofView(context: Context) : View(context) {
        var coverage = 1f
            private set
        var known = false
            set(v) { if (field != v) { field = v; invalidate() } }

        private var anim: ValueAnimator? = null
        private var firstSet = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val d = context.resources.displayMetrics.density

        fun setCoverage(c: Float) {
            coverage = c.coerceIn(0f, 1f)
            invalidate()
        }

        fun cancelAnim() { anim?.cancel(); anim = null }

        /** Yalnızca hedef değişince kısa bir geçiş; ilk gösterim anında oturur. */
        fun animateTo(target: Float) {
            if (firstSet || !isAttachedToWindow || width == 0) {
                firstSet = false
                cancelAnim()
                setCoverage(target)
                return
            }
            if (anim != null && target == animTarget) return
            if (anim == null && Math.abs(target - coverage) < 0.005f) return
            cancelAnim()
            animTarget = target
            anim = ValueAnimator.ofFloat(coverage, target).apply {
                duration = ANIM_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { setCoverage(it.animatedValue as Float) }
                start()
            }
        }

        private var animTarget = -1f

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = 14f * d
            val inset = 6f * d
            // Çerçeve.
            rect.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL
            paint.color = LauncherTheme.CONTROL
            canvas.drawRoundRect(rect, radius, radius, paint)
            // Cam.
            rect.set(inset, inset, w - inset, h - inset)
            val glassR = radius - 4f * d
            paint.color = ColorUtils.blendARGB(LauncherTheme.CARD, if (known) LauncherTheme.ACCENT else LauncherTheme.TEXT_SECONDARY, 0.30f)
            canvas.drawRoundRect(rect, glassR, glassR, paint)
            // Cam parlaması: iki ince çapraz şerit, camın cam olduğunu söyler.
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(rect, glassR, glassR, android.graphics.Path.Direction.CW) })
            paint.color = ColorUtils.setAlphaComponent(LauncherTheme.CARD, 0x40)
            val sheen = android.graphics.Path()
            val sw = rect.width() * 0.10f
            for (k in 0..1) {
                val x0 = rect.left + rect.width() * (0.55f + 0.2f * k)
                sheen.reset()
                sheen.moveTo(x0, rect.top); sheen.lineTo(x0 + sw, rect.top)
                sheen.lineTo(x0 + sw - rect.height() * 0.5f, rect.bottom); sheen.lineTo(x0 - rect.height() * 0.5f, rect.bottom)
                sheen.close()
                canvas.drawPath(sheen, paint)
            }
            canvas.restore()
            // Perde: camın üst kenarından coverage kadar aşağı.
            val curtainBottom = rect.top + rect.height() * coverage
            if (coverage > 0.001f) {
                canvas.save()
                val clip = android.graphics.Path().apply { addRoundRect(rect, glassR, glassR, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                paint.color = ColorUtils.blendARGB(LauncherTheme.CARD, LauncherTheme.TEXT_SECONDARY, 0.55f)
                canvas.drawRect(rect.left, rect.top, rect.right, curtainBottom, paint)
                // Kumaş kıvrımları: ince yatay çizgiler.
                paint.color = ColorUtils.setAlphaComponent(LauncherTheme.CARD, 0x55)
                var y = rect.top + 10f * d
                while (y < curtainBottom - 4f * d) {
                    canvas.drawRect(rect.left, y, rect.right, y + 1.5f * d, paint)
                    y += 12f * d
                }
                canvas.restore()
            }
            // Tutamak (perdenin ön kenarı); perde tamamen çekiliyken camın üst kenarında durur.
            paint.color = LauncherTheme.TEXT
            val hw = (rect.width() * 0.28f).coerceAtMost(80f * d)
            val cy = curtainBottom.coerceIn(rect.top + 5f * d, rect.bottom - 5f * d)
            val hr = RectF(rect.centerX() - hw / 2, cy - 3f * d, rect.centerX() + hw / 2, cy + 3f * d)
            canvas.drawRoundRect(hr, 3f * d, 3f * d, paint)
        }
    }

    private companion object {
        const val PADDING_DP = 20
        const val COMPACT_PADDING_DP = 14
        const val ICON_DP = 28
        const val LABEL_SP = 22f
        const val VALUE_SP = 28f
        const val TALL_ROWS = 4
        const val ANIM_MS = 700L
    }
}

// ---------------------------------------------------------------------------------------------
// Buğu giderme
// ---------------------------------------------------------------------------------------------

/** Ön cam buğu giderme ve arka cam ısıtma: iki açma/kapama düğmesi ("Ön", "Arka"); açıkken vurgu dolgulu. */
internal class DefrostTile(context: Context) : ComfortTile(context) {

    private var pendingFront: Boolean? = null
    private var pendingRear: Boolean? = null

    private val front = Chip(R.drawable.ic_mdi_defrost_front, R.string.launcher_comfort_front) { toggle(front = true) }
    private val rear = Chip(R.drawable.ic_mdi_defrost_rear, R.string.launcher_comfort_rear) { toggle(front = false) }

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, PADDING_DP).apply {
        gravity = Gravity.CENTER
        addView(front.view, weightLp(1f))
        addView(gap(context, GAP_DP))
        addView(rear.view, weightLp(1f))
    }

    override val view: View get() = root

    override fun applySpan(span: Span) = Unit

    override fun clearPending() { pendingFront = null; pendingRear = null }

    private fun frontOn(): Boolean? = pendingFront ?: reading.frontDefrost.takeIf { reading.connected }
    private fun rearOn(): Boolean? = pendingRear ?: reading.rearDefrost.takeIf { reading.connected }

    override fun render() {
        front.bind(frontOn())
        rear.bind(rearOn())
    }

    private fun toggle(front: Boolean) {
        val current = (if (front) frontOn() else rearOn())
        // Okunamıyorsa (test sürümü dışında) tahmin etme.
        if (current == null && !LauncherEdition.READ_ONLY) return
        val target = !(current ?: false)
        if (front) pendingFront = target else pendingRear = target
        if (ComfortWriter.defrostEnabled) io.runSafely { ComfortWriter.setDefrost(front, target) }
        afterCommit(ComfortWriter.defrostEnabled)
    }

    /** Büyük açma/kapama düğmesi: üstte ikon, altta ad ve durum. */
    private inner class Chip(@DrawableRes private val iconRes: Int, private val labelRes: Int, onClick: () -> Unit) {
        private val iconView = icon(context, iconRes, ICON_DP, LauncherTheme.TEXT)
        private val name = text(context, NAME_SP, LauncherTheme.TEXT, medium = true).apply { gravity = Gravity.CENTER }
        private val stateLabel = text(context, STATE_SP, LauncherTheme.TEXT_SECONDARY).apply { gravity = Gravity.CENTER }

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            addView(iconView, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
            addView(gap(context, 6, vertical = true))
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onClick() }
            pressFeedback()
        }

        init { name.setText(labelRes) }

        fun bind(on: Boolean?) {
            val known = on != null
            val active = on == true
            view.background = rounded(context, if (active) onFill() else LauncherTheme.CONTROL, CHIP_RADIUS_DP)
            iconView.imageTintList = ColorStateList.valueOf(if (active) LauncherTheme.ON_ACCENT else if (known) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
            name.setTextColor(if (active) LauncherTheme.ON_ACCENT else if (known) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
            stateLabel.setTextColor(if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT_SECONDARY)
            val state = context.getString(if (!known) R.string.launcher_unavailable else if (active) R.string.launcher_comfort_on else R.string.launcher_comfort_off)
            stateLabel.setTextSmooth(state)
            view.isEnabled = known || LauncherEdition.READ_ONLY
            view.contentDescription = context.getString(labelRes) + " " + context.getString(R.string.launcher_widget_defrost) + ", " + state
        }
    }

    private companion object {
        const val PADDING_DP = 14
        const val GAP_DP = 12
        const val ICON_DP = 36
        const val NAME_SP = 22f
        const val STATE_SP = 16f
        const val CHIP_RADIUS_DP = 16f
    }
}

// ---------------------------------------------------------------------------------------------
// Direksiyon ısıtma
// ---------------------------------------------------------------------------------------------

/** Direksiyon ısıtma: tek açma/kapama düğmesi; açıkken kutucuk vurgu dolgulu. */
internal class SteeringHeatTile(context: Context) : ComfortTile(context) {

    private var pendingOn: Boolean? = null

    private val iconView: ImageView = icon(context, R.drawable.ic_mdi_steering_heat, ICON_DP, LauncherTheme.TEXT)
    private val title = text(context, LABEL_SP, LauncherTheme.TEXT, medium = true).apply { gravity = Gravity.CENTER; maxLines = 2 }
    private val stateLabel = text(context, STATE_SP, LauncherTheme.TEXT_SECONDARY).apply { gravity = Gravity.CENTER }

    private val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = context.dp(PADDING_DP)
        setPadding(p, p, p, p)
        addView(iconView, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
        addView(gap(context, 6, vertical = true))
        addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(stateLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
        pressFeedback()
    }

    init { title.setText(R.string.launcher_widget_steering_heat) }

    override val view: View get() = root

    /** 1 sütunlu ince kutucukta yalnızca ikon ve durum; başlık gizlenir. */
    override fun applySpan(span: Span) {
        title.visibility = if (span.w >= 2) View.VISIBLE else View.GONE
    }

    override fun clearPending() { pendingOn = null }

    private fun isOn(): Boolean? = pendingOn ?: reading.steerHeat.takeIf { reading.connected }

    override fun render() {
        val on = isOn()
        val known = on != null
        val active = on == true
        root.background = cardSurface(context, if (active) onFill() else LauncherTheme.CARD)
        iconView.imageTintList = ColorStateList.valueOf(if (active) LauncherTheme.ON_ACCENT else if (known) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
        title.setTextColor(if (active) LauncherTheme.ON_ACCENT else if (known) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
        stateLabel.setTextColor(if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT_SECONDARY)
        val state = context.getString(if (!known) R.string.launcher_unavailable else if (active) R.string.launcher_comfort_on else R.string.launcher_comfort_off)
        stateLabel.setTextSmooth(state)
        root.isEnabled = known || LauncherEdition.READ_ONLY
        root.contentDescription = context.getString(R.string.launcher_widget_steering_heat) + ", " + state
    }

    private fun toggle() {
        val current = isOn()
        if (current == null && !LauncherEdition.READ_ONLY) return
        val target = !(current ?: false)
        pendingOn = target
        if (ComfortWriter.steerEnabled) io.runSafely { ComfortWriter.setSteerHeat(target) }
        afterCommit(ComfortWriter.steerEnabled)
    }

    private companion object {
        const val PADDING_DP = 10
        const val ICON_DP = 36
        const val LABEL_SP = 20f
        const val STATE_SP = 16f
    }
}
