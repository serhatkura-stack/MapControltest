package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.mapcontrol.R

/**
 * Markasız, kuşbakışı SUV şeması (burun sağda). Araç görseli yerine: kapı, bagaj, cam tavan ve lastik durumunu
 * doğrudan yerinde gösterir. Logo, ızgara ya da rozet yoktur.
 *
 * Çizim 230×(140) birimlik sabit bir uzayda yapılır; yollar yalnızca bir kez kurulur, boyut değişince yalnızca
 * tuval ölçeklenir. Animasyon yok: durum değişince bir kez yeniden çizilir. Renkler yalnızca [LauncherTheme]
 * belirteçlerinden okunur (gece/gündüz kendiliğinden).
 */
class CarSchematicView(context: Context) : View(context) {

    /** Çizilecek gövde durumu. Bilinmeyen (sinyal yok) kısımlar kapalı sayılır. */
    data class State(
        val doorLf: Boolean = false,
        val doorRf: Boolean = false,
        val doorLr: Boolean = false,
        val doorRr: Boolean = false,
        val trunk: Boolean = false,
        val sunroof: Boolean = false,
        val tireWarning: Boolean = false,
        /** true kilitli, false açık, null bilinmiyor (kilit simgesi çizilmez). */
        val locked: Boolean? = null,
    )

    private var state = State()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setState(next: State) {
        if (next == state) return
        state = next
        invalidate()
    }

    /** Tema (gece/gündüz, sürüş modu) değişince renkleri yeniden okutmak için. */
    fun refreshTheme() = invalidate()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Yollar birim uzayında bir kez kurulur.
    private val body = Path()
    private val windshield = Path()
    private val rearGlass = Path()
    private val roof = Path()
    private val roofRails = Path()
    private val sunroofRect = RectF(SUN_L, -SUN_H, SUN_R, SUN_H)
    private val wheelRects = arrayOf(
        wheel(FRONT_WHEEL_X, -1), wheel(FRONT_WHEEL_X, 1), wheel(REAR_WHEEL_X, -1), wheel(REAR_WHEEL_X, 1),
    )
    private val mirrors = arrayOf(
        RectF(MIRROR_X - 6f, -MIRROR_OUT, MIRROR_X + 6f, -BODY_HALF_W + 1f),
        RectF(MIRROR_X - 6f, BODY_HALF_W - 1f, MIRROR_X + 6f, MIRROR_OUT),
    )
    private val doorSeams = Path()
    private val trunkSeam = Path()
    private val hood = Path()
    private val trunkPanel = Path()
    // Kapı şeritleri: [ön kenar x, arka kenar x]; sol üst (y<0), sağ alt (y>0).
    private val doorStrips = arrayOf(
        RectF(B_PILLAR, -BODY_HALF_W, A_PILLAR, -ROOF_HALF_W),
        RectF(B_PILLAR, ROOF_HALF_W, A_PILLAR, BODY_HALF_W),
        RectF(C_PILLAR, -BODY_HALF_W, B_PILLAR, -ROOF_HALF_W),
        RectF(C_PILLAR, ROOF_HALF_W, B_PILLAR, BODY_HALF_W),
    )

    private val lockClosed: Drawable? = context.getDrawable(R.drawable.ic_mdi_lock)?.mutate()
    private val lockOpen: Drawable? = context.getDrawable(R.drawable.ic_mdi_lock_open_variant)?.mutate()

    private val swing = Path()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    init {
        buildPaths()
    }

    private fun wheel(cx: Float, side: Int): RectF {
        val cy = side * (BODY_HALF_W - 1f)
        return RectF(cx - WHEEL_L / 2, cy - WHEEL_T / 2, cx + WHEEL_L / 2, cy + WHEEL_T / 2)
    }

    private fun buildPaths() {
        val w = BODY_HALF_W
        body.apply {
            moveTo(-98f, -w); lineTo(78f, -w)
            cubicTo(100f, -w, 115f, -34f, 115f, -18f)
            lineTo(115f, 18f)
            cubicTo(115f, 34f, 100f, w, 78f, w)
            lineTo(-98f, w)
            cubicTo(-110f, w, -115f, 42f, -115f, 30f)
            lineTo(-115f, -30f)
            cubicTo(-115f, -42f, -110f, -w, -98f, -w)
            close()
        }
        // Ön cam: tabanı (x=62) geniş, tavana doğru hafif daralır.
        windshield.apply {
            moveTo(62f, -37f); lineTo(62f, 37f); lineTo(A_PILLAR + 2f, ROOF_HALF_W + 1f)
            lineTo(A_PILLAR + 2f, -ROOF_HALF_W - 1f); close()
        }
        rearGlass.apply {
            moveTo(-92f, -33f); lineTo(-92f, 33f); lineTo(C_PILLAR - 2f, ROOF_HALF_W)
            lineTo(C_PILLAR - 2f, -ROOF_HALF_W); close()
        }
        roof.addRoundRect(RectF(C_PILLAR, -ROOF_HALF_W, A_PILLAR, ROOF_HALF_W), 8f, 8f, Path.Direction.CW)
        // Tavan rayları: SUV izlenimi, çok ince.
        for (s in intArrayOf(-1, 1)) {
            roofRails.moveTo(C_PILLAR + 6f, s * (ROOF_HALF_W - 4f)); roofRails.lineTo(A_PILLAR - 6f, s * (ROOF_HALF_W - 4f))
        }
        for (x in floatArrayOf(A_PILLAR, B_PILLAR, C_PILLAR)) {
            doorSeams.moveTo(x, -BODY_HALF_W + 1f); doorSeams.lineTo(x, -ROOF_HALF_W)
            doorSeams.moveTo(x, BODY_HALF_W - 1f); doorSeams.lineTo(x, ROOF_HALF_W)
        }
        // Bagaj kapağı dikişi arka camın hemen arkasında hafif bombeli bir çizgi.
        // Kaput: iki hafif omuz çizgisi (ızgara/rozet yok).
        hood.apply {
            moveTo(66f, -33f); quadTo(90f, -30f, 104f, -16f)
            moveTo(66f, 33f); quadTo(90f, 30f, 104f, 16f)
        }
        trunkSeam.apply { moveTo(-92f, -33f); quadTo(-95f, 0f, -92f, 33f) }
        trunkPanel.apply { moveTo(-120f, -50f); lineTo(-92f, -50f); lineTo(-92f, -33f); quadTo(-95f, 0f, -92f, 33f); lineTo(-92f, 50f); lineTo(-120f, 50f); close() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val spanX = EXT_R - EXT_L
        val spanY = 2f * EXT_Y
        scale = minOf(w * FIT / spanX, h * FIT / spanY)
        offX = w / 2f - (EXT_L + EXT_R) / 2f * scale
        offY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val st = state
        val outline = LauncherTheme.TEXT_SECONDARY
        val alert = LauncherTheme.ALERT
        val dp = resources.displayMetrics.density / scale // 1 dp, birim uzayında
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)

        // Tekerlekler gövdenin altında; yalnızca dışa taşan kısımları görünür.
        for (r in wheelRects) {
            val warn = st.tireWarning
            fill.color = if (warn) alert else withAlpha(outline, 150)
            canvas.drawRoundRect(r, 4f, 4f, fill)
        }

        // Gövde: dolgu CONTROL, çizgi ikincil metin rengi (hafif).
        fill.color = LauncherTheme.CONTROL
        canvas.drawPath(body, fill)
        // Kapalı bagaj bölgesi sadece çizgiyle; açıkken ALERT dolgu gövde içinde kırpılır.
        if (st.trunk) {
            canvas.save()
            canvas.clipPath(body)
            fill.color = alert
            canvas.drawPath(trunkPanel, fill)
            canvas.restore()
        }
        for (i in doorStrips.indices) {
            if (!doorOpen(st, i)) continue
            canvas.save()
            canvas.clipPath(body)
            fill.color = alert
            canvas.drawRect(doorStrips[i], fill)
            canvas.restore()
        }

        // Camlar: ikincil rengin düşük opaklığı; roof CARD'a yakın bir ton açar.
        fill.color = withAlpha(outline, 45)
        canvas.drawPath(windshield, fill)
        canvas.drawPath(rearGlass, fill)
        fill.color = withAlpha(LauncherTheme.CARD, 150)
        canvas.drawPath(roof, fill)

        stroke.strokeWidth = 1.5f * dp
        stroke.color = outline
        canvas.drawPath(body, stroke)
        stroke.strokeWidth = 1.1f * dp
        stroke.color = withAlpha(outline, 200)
        canvas.drawPath(windshield, stroke)
        canvas.drawPath(rearGlass, stroke)
        stroke.color = withAlpha(outline, 130)
        canvas.drawPath(roof, stroke)
        canvas.drawPath(doorSeams, stroke)
        canvas.drawPath(trunkSeam, stroke)
        stroke.color = withAlpha(outline, 90)
        canvas.drawPath(roofRails, stroke)
        canvas.drawPath(hood, stroke)

        // Cam tavan.
        if (st.sunroof) {
            fill.color = alert
            canvas.drawRoundRect(sunroofRect, 5f, 5f, fill)
        } else {
            fill.color = withAlpha(outline, 55)
            canvas.drawRoundRect(sunroofRect, 5f, 5f, fill)
            stroke.color = withAlpha(outline, 150)
            canvas.drawRoundRect(sunroofRect, 5f, 5f, stroke)
        }

        // Aynalar.
        for (m in mirrors) {
            fill.color = LauncherTheme.CONTROL
            canvas.drawRoundRect(m, 3f, 3f, fill)
            stroke.color = withAlpha(outline, 200)
            canvas.drawRoundRect(m, 3f, 3f, stroke)
        }

        // Açık kapılar dışa doğru sallanmış kalın çizgi (menteşe ön kenarda).
        stroke.color = alert
        stroke.strokeWidth = 6f * dp
        for (i in doorStrips.indices) {
            if (doorOpen(st, i)) drawDoorSwing(canvas, doorStrips[i], i % 2 == 0)
        }
        // Açık bagaj: kapak arkaya doğru kalkmış (iki yan çizgi + arka kenar).
        if (st.trunk) {
            stroke.strokeWidth = 6f * dp
            swing.reset()
            swing.moveTo(-112f, -28f); swing.lineTo(-128f, -22f); swing.lineTo(-128f, 22f); swing.lineTo(-112f, 28f)
            canvas.drawPath(swing, stroke)
        }
        // Lastik uyarısında tekerlek çerçevesi.
        if (st.tireWarning) {
            stroke.strokeWidth = 1.4f * dp
            stroke.color = alert
            for (r in wheelRects) canvas.drawRoundRect(r, 4f, 4f, stroke)
        }
        canvas.restore()

        // Kilit simgesi: dönmez, tavanın ön yarısında; piksel boyutu ölçekle gelir.
        val locked = st.locked
        if (locked != null) {
            val d = if (locked) lockClosed else lockOpen
            if (d != null) {
                val cx = offX + LOCK_X * scale
                val half = LOCK_SIZE / 2f * scale
                d.setTint(outline)
                d.alpha = 220
                d.setBounds((cx - half).toInt(), (offY - half).toInt(), (cx + half).toInt(), (offY + half).toInt())
                d.draw(canvas)
            }
        }
    }

    /** İndeks: 0 sol ön, 1 sağ ön, 2 sol arka, 3 sağ arka. */
    private fun doorOpen(s: State, i: Int) = when (i) {
        0 -> s.doorLf; 1 -> s.doorRf; 2 -> s.doorLr; else -> s.doorRr
    }

    private fun drawDoorSwing(canvas: Canvas, strip: RectF, top: Boolean) {
        val side = if (top) -1f else 1f
        val hingeX = strip.right
        val hingeY = side * BODY_HALF_W
        val len = strip.width()
        val a = Math.toRadians(SWING_DEG.toDouble())
        val ex = hingeX - len * Math.cos(a).toFloat()
        val ey = hingeY + side * len * Math.sin(a).toFloat()
        swing.reset()
        swing.moveTo(hingeX, hingeY)
        swing.lineTo(ex, ey)
        canvas.drawPath(swing, stroke)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private companion object {
        const val BODY_HALF_W = 50f
        const val ROOF_HALF_W = 33f
        const val A_PILLAR = 36f
        const val B_PILLAR = -12f
        const val C_PILLAR = -68f
        const val FRONT_WHEEL_X = 72f
        const val REAR_WHEEL_X = -70f
        const val WHEEL_L = 34f
        const val WHEEL_T = 14f
        const val MIRROR_X = 44f
        const val MIRROR_OUT = 59f
        const val SUN_L = -58f
        const val SUN_R = -22f
        const val SUN_H = 19f
        const val LOCK_X = 7f
        const val LOCK_SIZE = 20f
        const val SWING_DEG = 28f
        // Birim uzayındaki en uç değerler (sallanan kapı ve kalkık bagaj dahil).
        const val EXT_L = -131f
        const val EXT_R = 117f
        const val EXT_Y = 72f
        const val FIT = 0.96f
    }
}
