package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState
import kotlin.math.abs

/**
 * Stil B'nin mini oynatıcısı (OEM çubuğunda direksiyon simgesinin durduğu yerde): kapak küçük resmi, oynat/duraklat,
 * altında ince ilerleme çizgisi. Kapağa dokun = medya uygulamasını aç; kapakta yatay kaydır = önceki/sonraki parça.
 * Oturum yokken sönük "Müzik" durumu. Yalnızca görünürken dinler; ilerleme denetleyicinin saniyelik tikinden gelir.
 */
internal class BarMiniPlayer(private val context: Context) : Themeable {

    private val controller = LauncherMediaController(context)
    private val theme = ThemeBindings()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = LauncherIo.serial()
    private var listening = false
    private var playing: Boolean? = null
    private var session = false
    private var shownArtwork: Bitmap? = null
    private var shownGeneration = -1
    private var fraction = 0f

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, context.dpF(COVER_RADIUS_DP))
        }
    }

    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = outline
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val placeholder = icon(context, R.drawable.ic_mdi_music_note, PLACEHOLDER_DP).also { ic ->
        theme.bind { ic.setTint(LauncherTheme.DOCK_TEXT_SECONDARY) }
    }
    private val coverBox = FrameLayout(context).apply {
        theme.bind { background = rounded(context, LauncherTheme.DOCK_TRACK, COVER_RADIUS_DP) }
        clipToOutline = true
        outlineProvider = outline
        addView(placeholder, FrameLayout.LayoutParams(context.dp(PLACEHOLDER_DP), context.dp(PLACEHOLDER_DP), Gravity.CENTER))
        addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.launcher_bar_media_open)
        isClickable = true
        isFocusable = true
    }
    private val playIcon = icon(context, R.drawable.ic_mdi_play, PLAY_ICON_DP)
    private val playButton = FrameLayout(context).apply {
        addView(playIcon, FrameLayout.LayoutParams(context.dp(PLAY_ICON_DP), context.dp(PLAY_ICON_DP), Gravity.CENTER))
        isClickable = true
        isFocusable = true
        setOnClickListener { if (session) controller.playPause() }
        pressFeedback()
    }
    private val progress = Progress()

    val view: View = FrameLayout(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(coverBox, LinearLayout.LayoutParams(context.dp(COVER_DP), context.dp(COVER_DP)))
                addView(gap(context, 10))
                addView(playButton, LinearLayout.LayoutParams(context.dp(PLAY_DP), context.dp(PLAY_DP)))
            })
            addView(gap(context, 6, vertical = true))
            addView(progress, LinearLayout.LayoutParams(context.dp(COVER_DP + 10 + PLAY_DP), context.dp(PROGRESS_DP)))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    init {
        coverBox.setOnTouchListener { v, e -> coverTouch(v, e) }
        applyPlayLook()
    }

    private val listener = LauncherMediaController.Listener { state -> if (!LauncherDemo.enabled) bind(state) }

    fun onVisible() {
        if (!listening) { controller.addListener(listener); listening = true }
        controller.start()
        bind(if (LauncherDemo.enabled) LauncherDemo.media() else controller.currentState())
    }

    fun onHidden() {
        if (listening) { controller.removeListener(listener); listening = false }
        controller.stop()
    }

    override fun applyTheme() {
        theme.reapply()
        applyPlayLook()
        progress.invalidate()
    }

    private fun bind(s: MediaState) {
        session = s.sessionAvailable
        val isPlaying = s.sessionAvailable && s.playing
        if (isPlaying != playing) {
            playing = isPlaying
            playIcon.setImageResource(if (isPlaying) R.drawable.ic_mdi_pause else R.drawable.ic_mdi_play)
            playButton.contentDescription = context.getString(if (isPlaying) R.string.launcher_media_pause else R.string.launcher_media_play)
            applyPlayLook()
        }
        playButton.alpha = if (s.sessionAvailable) 1f else 0.45f
        val f = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        if (f != fraction) { fraction = f; progress.invalidate() }
        bindArtwork(if (s.sessionAvailable) s.artwork else null)
    }

    /** Oynarken vurgu dolgusu, duraklatılmışken sakin ton dolgusu. */
    private fun applyPlayLook() {
        val on = playing == true
        playButton.background = oval(if (on) LauncherTheme.ACCENT else LauncherTheme.DOCK_TRACK)
        playIcon.setTint(if (on) LauncherTheme.ON_ACCENT else LauncherTheme.DOCK_TEXT)
    }

    /** Kapak değişmediyse hiçbir şey yapma; yeni kapak arka planda küçük boyuta indirilir (ana iş parçacığı yorulmaz). */
    private fun bindArtwork(bitmap: Bitmap?) {
        if (bitmap === shownArtwork) return
        if (bitmap != null && bitmap.generationId == shownGeneration) { shownArtwork = bitmap; return }
        shownArtwork = bitmap
        if (bitmap == null) {
            shownGeneration = -1
            cover.setImageDrawable(null)
            cover.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            return
        }
        shownGeneration = bitmap.generationId
        io.runSafely {
            val px = context.dp(COVER_DP)
            val scaled = if (maxOf(bitmap.width, bitmap.height) <= px * 2) bitmap
            else Bitmap.createScaledBitmap(bitmap, px * 2, px * 2, true)
            main.post {
                if (shownArtwork !== bitmap) return@post
                cover.setImageBitmap(scaled)
                cover.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun coverTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiped = false; v.isPressed = true; v.parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                when {
                    abs(dx) > SWIPE_DP * context.resources.displayMetrics.density && abs(dx) > abs(dy) -> {
                        if (session) { if (dx < 0) controller.skipToNext() else controller.skipToPrevious() }
                    }
                    abs(dx) < slop && abs(dy) < slop -> controller.launchLastSource()
                }
            }
            MotionEvent.ACTION_CANCEL -> v.isPressed = false
        }
        return true
    }

    /** Kapak + düğme genişliğinde ince çizgi; dolu kısım vurgu renginde. */
    private inner class Progress : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = LauncherTheme.DOCK_TRACK
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            if (fraction > 0f) {
                paint.color = LauncherTheme.ACCENT
                rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    private companion object {
        const val COVER_DP = 54
        const val COVER_RADIUS_DP = 12f
        const val PLACEHOLDER_DP = 28
        const val PLAY_DP = 54
        const val PLAY_ICON_DP = 30
        const val PROGRESS_DP = 3
        const val SWIPE_DP = 40
    }
}

/**
 * Kapı ya da bagaj açıkken mini oynatıcının yerini alan durum çipi: kırmızı nabız noktası ve "Kapı açık" / "Bagaj açık".
 * Nabız yavaş ve yumuşak (1,4 sn), sistem animasyon ölçeği kapalıysa nokta sabit durur; gizlenince durur.
 */
internal class BarOpenAlert(private val context: Context) : Themeable {

    private val theme = ThemeBindings()
    private val dot = PulseDot()
    private val label = text(context, LABEL_SP, bold = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        theme.bind { setTextColor(LauncherTheme.ALERT) }
    }
    private var kind: Kind? = null
    private var visible = false
    private var pulse: ValueAnimator? = null
    private var phase = 0f

    enum class Kind(val label: Int) { DOOR(R.string.launcher_bar_door_open), TRUNK(R.string.launcher_bar_trunk_open) }

    val view: View = FrameLayout(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            theme.bind { background = rounded(context, LauncherTheme.ALERT_SURFACE, CHIP_RADIUS_DP, SeatGlow.withAlpha(LauncherTheme.ALERT, 0.55f)) }
            val p = context.dp(8)
            setPadding(p, 0, p, 0)
            addView(dot, LinearLayout.LayoutParams(context.dp(DOT_BOX_DP), context.dp(DOT_BOX_DP)))
            addView(gap(context, 4))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(CHIP_H_DP), Gravity.CENTER))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** Şu an gösteriliyor mu. */
    val showing: Boolean get() = kind != null

    fun setKind(k: Kind?) {
        if (k == kind) return
        kind = k
        if (k == null) {
            view.visibility = View.GONE
            stopPulse()
            return
        }
        label.setText(k.label)
        view.contentDescription = context.getString(k.label)
        view.visibility = View.VISIBLE
        startPulse()
    }

    fun onVisible() { visible = true; startPulse() }

    fun onHidden() { visible = false; stopPulse() }

    override fun applyTheme() {
        theme.reapply()
        dot.invalidate()
    }

    private fun startPulse() {
        if (!visible || kind == null || !ValueAnimator.areAnimatorsEnabled()) { phase = 0f; dot.invalidate(); return }
        if (pulse?.isRunning == true) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; dot.invalidate() }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        phase = 0f
        dot.invalidate()
    }

    /** Dolu nokta ve ondan yayılıp sönen halka. */
    private inner class PulseDot : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val core = context.dpF(5.5f)
            if (phase > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dpF(2f)
                paint.color = SeatGlow.withAlpha(LauncherTheme.ALERT, 0.65f * (1f - phase))
                canvas.drawCircle(cx, cy, core + (width / 2f - core) * phase, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = LauncherTheme.ALERT
            canvas.drawCircle(cx, cy, core, paint)
        }
    }

    private companion object {
        const val LABEL_SP = 19f
        const val CHIP_H_DP = 54
        const val CHIP_RADIUS_DP = 16f
        const val DOT_BOX_DP = 24
        const val PULSE_MS = 1_400L
    }
}
