package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Koltuk ısıtma/soğutma parıltısının ortak kuralları. Çizimler tamamen kodla, kendi oranlarımızla yapılır
 * (araç üreticisinin görselleri kullanılmaz). Renk: ısıtma [LauncherTheme.HEAT] turuncu, soğutma [LauncherTheme.COOL] camgöbeği.
 */
internal object SeatGlow {

    /** Seviye (kesirli olabilir; animasyon sırasında) → çekirdek parıltı opaklığı 0..1. Kapalı = 0 (parıltı yok). */
    fun core(level: Float): Float = lerp(level, 0f, 0.38f, 0.64f, 0.92f)

    /** Seviye → dış hale opaklığı (koltuğun dışına taşan yumuşak ışık). */
    fun halo(level: Float): Float = lerp(level, 0f, 0.07f, 0.13f, 0.20f)

    /** Seviye → parıltı yarıçapı çarpanı: yüksek seviye koltuğu daha çok kaplar. */
    fun reach(level: Float): Float = lerp(level, 0.55f, 0.72f, 0.88f, 1.05f)

    /** Seviye → nokta dokusu opaklığı. */
    fun dots(level: Float): Float = lerp(level, 0f, 0.20f, 0.34f, 0.50f)

    private fun lerp(level: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val l = level.coerceIn(0f, 3f)
        return when {
            l <= 1f -> a + (b - a) * l
            l <= 2f -> b + (c - b) * (l - 1f)
            else -> c + (d - c) * (l - 2f)
        }
    }

    fun withAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    /** Seviye değişimini 240 ms'de yumuşatan küçük yardımcı; ilk değer ve görünmeyen görünüm hemen atlar. */
    class LevelAnim(private val view: View, private val onValue: () -> Unit) {
        var value = 0f
            private set
        private var primed = false
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.4f)
        }
        private var from = 0f
        private var to = 0f

        init {
            anim.addUpdateListener {
                value = from + (to - from) * (it.animatedValue as Float)
                onValue()
            }
        }

        fun set(target: Float) {
            if (primed && target == to) return
            val animate = primed && view.isAttachedToWindow && view.width > 0
            primed = true
            anim.cancel()
            to = target
            if (!animate) {
                value = target
                onValue()
                return
            }
            from = value
            anim.start()
        }

        fun cancel() {
            if (anim.isRunning) { anim.cancel(); value = to }
        }
    }

    /** Nokta dokusu için 1 birimlik sabit ızgara noktaları (birim uzayında [0,w]x[0,h], adım [step]); bir kez kurulur. */
    fun dotGrid(w: Float, h: Float, step: Float): FloatArray {
        val xs = (w / step).toInt() + 1
        val ys = (h / step).toInt() + 1
        val out = FloatArray(xs * ys * 2)
        var i = 0
        for (y in 0 until ys) for (x in 0 until xs) {
            // Her ikinci satır yarım adım kayar: göz yormayan, tekstil benzeri doku.
            out[i++] = x * step + if (y % 2 == 1) step / 2f else 0f
            out[i++] = y * step
        }
        return out
    }
}

/**
 * Yandan görünüşlü koltuk silüeti (yüz sağa) ve gövdesinin üstünde yumuşak radyal parıltı. Kapalıyken parıltı yoktur;
 * seviye 1..3 yoğunluğu ve kapladığı alanı artırır. Birim uzayı 100×100'dür; yollar bir kez kurulur.
 */
internal class SeatSideView(context: Context, private val vent: Boolean) : View(context) {

    private val level = SeatGlow.LevelAnim(this) { invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val body = Path()
    private val headrest = RectF(17f, 4f, 37f, 19f)
    private val rail = RectF(36f, 86f, 80f, 91f)
    private val stem = RectF(54f, 74f, 62f, 87f)
    private val dots = SeatGlow.dotGrid(100f, 100f, 6f)
    private val centerX = 44f
    private val centerY = 60f
    private var scale = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        buildBody()
    }

    private fun buildBody() {
        // Sırtlık: arkaya yatık kalın yuvarlak çubuk; minder: yatay yuvarlak dikdörtgen. İkisinin birleşimi tek gövde yolu.
        val back = Path().apply { moveTo(40f, 64f); lineTo(29f, 28f) }
        val strokeP = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 21f }
        val backFill = Path()
        strokeP.getFillPath(back, backFill)
        val cushion = Path().apply { addRoundRect(RectF(26f, 58f, 90f, 77f), 9f, 9f, Path.Direction.CW) }
        body.op(backFill, cushion, Path.Op.UNION)
    }

    /** Seviye 0..3; [animate] kapalıysa hemen atlar. */
    fun setLevel(l: Int) = level.set(l.coerceIn(0, 3).toFloat())

    fun refreshTheme() = invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w, h) / 100f
    }

    override fun onDetachedFromWindow() {
        level.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val l = level.value
        val tint = if (vent) LauncherTheme.COOL else LauncherTheme.HEAT
        val idle = LauncherTheme.TEXT_SECONDARY
        val unit = 1f / scale * resources.displayMetrics.density // 1 dp, birim uzayında
        canvas.save()
        canvas.translate((width - 100f * scale) / 2f, (height - 100f * scale) / 2f)
        canvas.scale(scale, scale)

        // Dış hale: gövdeyi aşan çok yumuşak ışık.
        val haloA = SeatGlow.halo(l)
        if (haloA > 0.004f) {
            glow.shader = RadialGradient(centerX, centerY, 36f * SeatGlow.reach(l), SeatGlow.withAlpha(tint, haloA * 2.6f), SeatGlow.withAlpha(tint, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 100f, 100f, glow)
        }

        // Taban: ray ve gövde altı sade bir iz.
        fill.color = SeatGlow.withAlpha(idle, 0.22f)
        canvas.drawRoundRect(rail, 2.5f, 2.5f, fill)
        canvas.drawRoundRect(stem, 2f, 2f, fill)

        // Koltuk gövdesi.
        fill.color = SeatGlow.withAlpha(idle, 0.16f)
        canvas.drawPath(body, fill)
        canvas.drawRoundRect(headrest, 5f, 5f, fill)

        // Çekirdek parıltı, gövdeye kırpılır.
        val coreA = SeatGlow.core(l)
        if (coreA > 0.004f) {
            canvas.save()
            canvas.clipPath(body)
            // Taban ton: koltuk gövdesi seviyeyle renk alır; üstüne merkezden yayılan parıltı biner.
            fill.color = SeatGlow.withAlpha(tint, coreA * 0.38f)
            canvas.drawPath(body, fill)
            glow.shader = RadialGradient(
                centerX, centerY, 60f * SeatGlow.reach(l),
                intArrayOf(SeatGlow.withAlpha(tint, coreA), SeatGlow.withAlpha(tint, coreA * 0.7f), SeatGlow.withAlpha(tint, 0f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, 100f, 100f, glow)
            // İnce nokta dokusu (tekstil/delik hissi); yalnızca parıltının olduğu yerde belirgin.
            val dotA = SeatGlow.dots(l)
            dotPaint.strokeWidth = 1.7f
            dotPaint.color = SeatGlow.withAlpha(0xFFFFFFFF.toInt(), dotA * (if (LauncherTheme.night) 0.55f else 1f))
            canvas.drawPoints(dots, dotPaint)
            canvas.restore()
            // Başlık da ısınır.
            fill.color = SeatGlow.withAlpha(tint, coreA * 0.55f)
            canvas.drawRoundRect(headrest, 5f, 5f, fill)
        }

        // Dış hat.
        line.strokeWidth = 1.6f * unit.coerceAtLeast(0.5f)
        line.color = if (l > 0.05f) SeatGlow.withAlpha(tint, 0.55f + 0.1f * l) else SeatGlow.withAlpha(idle, 0.55f)
        canvas.drawPath(body, line)
        canvas.drawRoundRect(headrest, 5f, 5f, line)
        canvas.restore()
    }
}
