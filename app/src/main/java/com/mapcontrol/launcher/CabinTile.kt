package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R

/** Kabindeki bir koltuğun ısıtma/soğutma seviyesi (0..3); [known] false ise veri yok, parıltı çizilmez. */
internal data class CabinSeatLevels(val heat: Int = 0, val vent: Int = 0, val known: Boolean = false)

/**
 * Yukarıdan görünüşlü kabin çizimi (burun yukarıda, direksiyon solda): iki ön koltuk, arka bank, direksiyon ve ön cam.
 * Ön koltukların ısıtma (turuncu) ve soğutma (camgöbeği) seviyesi koltuk üstünde yumuşak parıltı olarak görünür;
 * arka koltukta veri kaynağı olmadığı için hep nötr kalır. Tamamen kodla çizilir, birim uzayı 100×146.
 */
internal class CabinView(context: Context) : View(context) {

    private val driverHeat = SeatGlow.LevelAnim(this) { invalidate() }
    private val driverVent = SeatGlow.LevelAnim(this) { invalidate() }
    private val passengerHeat = SeatGlow.LevelAnim(this) { invalidate() }
    private val passengerVent = SeatGlow.LevelAnim(this) { invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f
    }

    private val shell = Path()
    private val windshield = Path()
    private val rearGlass = Path()
    private val dash = RectF(15f, 31f, 85f, 37f)
    private val dots = SeatGlow.dotGrid(100f, 146f, 5.5f)

    /** Ön koltuk (yüzü yukarı): minder önde, sırtlık ve başlık arkasında. Merkez x'e göre kurulur. */
    private fun frontSeat(cx: Float, y0: Float) = Path().apply {
        addRoundRect(RectF(cx - 11.5f, y0, cx + 11.5f, y0 + 22f), 7f, 7f, Path.Direction.CW)
        addRoundRect(RectF(cx - 12.5f, y0 + 24.5f, cx + 12.5f, y0 + 37f), 6f, 6f, Path.Direction.CW)
    }
    private val headrests = arrayOf(
        RectF(30f - 6f, SEAT_Y + 39f, 30f + 6f, SEAT_Y + 44f), RectF(70f - 6f, SEAT_Y + 39f, 70f + 6f, SEAT_Y + 44f),
    )
    private val seats = arrayOf(frontSeat(30f, SEAT_Y), frontSeat(70f, SEAT_Y))
    private val rear = Path().apply {
        addRoundRect(RectF(15f, REAR_Y, 85f, REAR_Y + 14f), 7f, 7f, Path.Direction.CW)
        addRoundRect(RectF(14f, REAR_Y + 16.5f, 86f, REAR_Y + 26.5f), 6f, 6f, Path.Direction.CW)
    }
    private val rearSeams = Path().apply {
        moveTo(39f, REAR_Y + 2f); lineTo(39f, REAR_Y + 24.5f)
        moveTo(61f, REAR_Y + 2f); lineTo(61f, REAR_Y + 24.5f)
    }
    private var scale = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        shell.addRoundRect(RectF(6f, 3f, 94f, 143f), 28f, 28f, Path.Direction.CW)
        windshield.apply {
            moveTo(17f, 25f); quadTo(50f, 10f, 83f, 25f); lineTo(80f, 12f + 6f)
            quadTo(50f, 4f, 20f, 18f); close()
        }
        rearGlass.apply {
            moveTo(26f, 135f); quadTo(50f, 140f, 74f, 135f); lineTo(72f, 131f)
            quadTo(50f, 135f, 28f, 131f); close()
        }
    }

    fun setSeats(driver: CabinSeatLevels, passenger: CabinSeatLevels) {
        driverHeat.set(if (driver.known) driver.heat.toFloat() else 0f)
        driverVent.set(if (driver.known) driver.vent.toFloat() else 0f)
        passengerHeat.set(if (passenger.known) passenger.heat.toFloat() else 0f)
        passengerVent.set(if (passenger.known) passenger.vent.toFloat() else 0f)
    }

    fun refreshTheme() = invalidate()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val aspect = 100f / 146f
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        var h = if (hMode == MeasureSpec.UNSPECIFIED) (resources.displayMetrics.density * 110).toInt() else hSize
        var w = (h * aspect).toInt()
        if (wMode != MeasureSpec.UNSPECIFIED && w > wSize) {
            w = wSize
            h = if (hMode == MeasureSpec.EXACTLY) hSize else (w / aspect).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w / 100f, h / 146f)
    }

    override fun onDetachedFromWindow() {
        driverHeat.cancel(); driverVent.cancel(); passengerHeat.cancel(); passengerVent.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val idle = LauncherTheme.TEXT_SECONDARY
        val unit = resources.displayMetrics.density / scale
        canvas.save()
        canvas.translate((width - 100f * scale) / 2f, (height - 146f * scale) / 2f)
        canvas.scale(scale, scale)

        // Kabuk ve camlar.
        fill.color = SeatGlow.withAlpha(idle, 0.07f)
        canvas.drawPath(shell, fill)
        fill.color = SeatGlow.withAlpha(idle, 0.16f)
        canvas.drawPath(windshield, fill)
        canvas.drawPath(rearGlass, fill)
        line.strokeWidth = 1.6f * unit
        line.color = SeatGlow.withAlpha(idle, 0.5f)
        canvas.drawPath(shell, line)
        line.strokeWidth = 1.2f * unit
        line.color = SeatGlow.withAlpha(idle, 0.4f)
        canvas.drawPath(windshield, line)
        canvas.drawPath(rearGlass, line)

        // Gösterge paneli ve direksiyon (solda).
        fill.color = SeatGlow.withAlpha(idle, 0.2f)
        canvas.drawRoundRect(dash, 3f, 3f, fill)
        line.strokeWidth = 2.6f * unit
        line.color = SeatGlow.withAlpha(idle, 0.75f)
        canvas.drawCircle(30f, 44f, 8f, line)
        line.strokeWidth = 1.6f * unit
        canvas.drawLine(22f, 44f, 38f, 44f, line) // yatay kol
        canvas.drawLine(30f, 44f, 30f, 50f, line)      // alt kol

        // Arka bank: veri yok, nötr.
        fill.color = SeatGlow.withAlpha(idle, 0.16f)
        canvas.drawPath(rear, fill)
        line.strokeWidth = 1.3f * unit
        line.color = SeatGlow.withAlpha(idle, 0.45f)
        canvas.drawPath(rear, line)
        line.color = SeatGlow.withAlpha(idle, 0.3f)
        canvas.drawPath(rearSeams, line)

        drawSeat(canvas, 0, driverHeat.value, driverVent.value, unit)
        drawSeat(canvas, 1, passengerHeat.value, passengerVent.value, unit)
        canvas.restore()
    }

    private fun drawSeat(canvas: Canvas, i: Int, heat: Float, vent: Float, unit: Float) {
        val idle = LauncherTheme.TEXT_SECONDARY
        val cx = if (i == 0) 30f else 70f
        val seat = seats[i]
        val head = headrests[i]
        fill.color = SeatGlow.withAlpha(idle, 0.2f)
        canvas.drawPath(seat, fill)
        canvas.drawRoundRect(head, 3f, 3f, fill)

        val hot = SeatGlow.core(heat)
        val cold = SeatGlow.core(vent)
        if (hot > 0.004f || cold > 0.004f) {
            canvas.save()
            canvas.clipPath(seat)
            val rim = LauncherTheme.night
            fill.color = SeatGlow.withAlpha(if (hot >= cold) LauncherTheme.HEAT else LauncherTheme.COOL, maxOf(hot, cold) * 0.35f)
            canvas.drawPath(seat, fill)
            if (hot > 0.004f) {
                // Isıtma minderi (ön) ve sırtlığın altını ısıtır.
                paintGlow(canvas, cx, SEAT_Y + 11f, 24f * SeatGlow.reach(heat), LauncherTheme.HEAT, hot)
            }
            if (cold > 0.004f) {
                // Soğutma sırtlıkta ve minderin arka yarısında yoğunlaşır.
                paintGlow(canvas, cx, SEAT_Y + 31f, 24f * SeatGlow.reach(vent), LauncherTheme.COOL, cold)
            }
            val dotA = maxOf(SeatGlow.dots(heat), SeatGlow.dots(vent))
            dotPaint.color = SeatGlow.withAlpha(0xFFFFFFFF.toInt(), dotA * (if (rim) 0.55f else 1f))
            canvas.save()
            canvas.translate(cx - 50f, 0f)
            canvas.drawPoints(dots, dotPaint)
            canvas.restore()
            canvas.restore()
            // Başlık, baskın renge boyanır.
            fill.color = SeatGlow.withAlpha(if (hot >= cold) LauncherTheme.HEAT else LauncherTheme.COOL, maxOf(hot, cold) * 0.6f)
            canvas.drawRoundRect(head, 3f, 3f, fill)
        }
        val tint = when {
            hot <= 0.004f && cold <= 0.004f -> null
            hot >= cold -> LauncherTheme.HEAT
            else -> LauncherTheme.COOL
        }
        line.strokeWidth = 1.4f * unit
        line.color = if (tint != null) SeatGlow.withAlpha(tint, 0.7f) else SeatGlow.withAlpha(idle, 0.55f)
        canvas.drawPath(seat, line)
        canvas.drawRoundRect(head, 3f, 3f, line)
    }

    private fun paintGlow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        glow.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(SeatGlow.withAlpha(color, a), SeatGlow.withAlpha(color, a * 0.55f), SeatGlow.withAlpha(color, 0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(cx - r, cy - r, cx + r, cy + r, glow)
    }

    private companion object {
        const val SEAT_Y = 54f
        const val REAR_Y = 101f
    }
}

/**
 * "Kabin" widget'ı: kabinin üstten çizimi ve koltukların ısıtma/soğutma durumu. Salt okur (dokunuş bir şey açmaz).
 * Veri kaynağı koltuk kutucuklarıyla aynıdır ([SeatHeatModel], ısıtma ve soğutma ayrı); okunamazsa sakin "—".
 * 2x2'de çizim solda, durum satırları sağda; 4 satır ve üstünde dikey (çizim üstte, satırlar altta).
 */
internal class CabinTile(private val context: Context) : SizedWidget, Themeable {

    private val theme = ThemeBindings()

    private var heat = SeatHeatState()
    private var vent = SeatHeatState()
    private val heatModel = SeatHeatModel(context, vent = false) { heat = it; bind() }
    private val ventModel = SeatHeatModel(context, vent = true) { vent = it; bind() }

    private val cabin = CabinView(context)
    private val driverRow = Row(R.string.launcher_cabin_driver)
    private val passengerRow = Row(R.string.launcher_cabin_passenger)

    private val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(driverRow.root)
        addView(gap(context, 8, vertical = true))
        addView(passengerRow.root)
    }

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        theme.bind { background = cardSurface(context) }
        addView(cabin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        addView(gap(context, 14))
        addView(texts, weightLp(1f))
    }

    override val view: View get() = root

    override fun applyTheme() {
        theme.reapply()
        cabin.refreshTheme()
        bind()
    }

    /** Dikey yerleşim: yüksek kutucukta çizim büyük, satırlar altında. */
    override fun applySpan(span: Span) {
        val tall = span.h >= TALL_ROWS
        root.orientation = if (tall) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        cabin.layoutParams = if (tall) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        texts.layoutParams = if (tall) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        } else weightLp(1f)
        texts.gravity = if (tall) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        driverRow.center(tall)
        passengerRow.center(tall)
        root.requestLayout()
    }

    override fun onVisible() {
        heatModel.onVisible()
        ventModel.onVisible()
    }

    override fun onHidden() {
        heatModel.onHidden()
        ventModel.onHidden()
    }

    override fun release() = Unit

    private fun levels(driver: Boolean): CabinSeatLevels {
        val known = heat.connected || vent.connected
        val h = (if (driver) heat.driver else heat.passenger) ?: 0
        val v = (if (driver) vent.driver else vent.passenger) ?: 0
        return CabinSeatLevels(h, v, known)
    }

    private fun bind() {
        val d = levels(true)
        val p = levels(false)
        cabin.setSeats(d, p)
        driverRow.bind(d)
        passengerRow.bind(p)
        root.contentDescription = context.getString(R.string.launcher_widget_cabin) + ", " +
            context.getString(R.string.launcher_cabin_driver) + " " + driverRow.summary + ", " +
            context.getString(R.string.launcher_cabin_passenger) + " " + passengerRow.summary
    }

    /** Bir koltuk satırı: küçük ad ve altında renkli durum ("Isıtma 3", "Soğutma 2", "Kapalı"). */
    private inner class Row(labelRes: Int) {
        private val label: TextView = text(context, ROW_LABEL_SP).apply {
            setText(labelRes)
            theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
        }
        private val value: TextView = text(context, ROW_VALUE_SP, medium = true)
        var summary: String = ""
            private set

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(value)
        }

        /** Dikey yerleşimde ad ve değer ortalanır. */
        fun center(on: Boolean) {
            val g = if (on) Gravity.CENTER_HORIZONTAL else Gravity.START
            label.gravity = g
            value.gravity = g
        }

        fun bind(s: CabinSeatLevels) {
            val hot = s.heat > 0
            val cold = s.vent > 0
            summary = when {
                !s.known -> context.getString(R.string.launcher_unavailable)
                hot && cold -> context.getString(R.string.launcher_cabin_both, s.heat, s.vent)
                hot -> context.getString(R.string.launcher_cabin_heat, s.heat)
                cold -> context.getString(R.string.launcher_cabin_vent, s.vent)
                else -> context.getString(R.string.launcher_seat_off)
            }
            value.text = if (hot && cold) android.text.SpannableString(summary).apply {
                // "Isı 3 · Soğ. 3": ilk yarı turuncu, ikinci yarı camgöbeği.
                val cut = summary.indexOf('·')
                if (cut > 0) {
                    setSpan(android.text.style.ForegroundColorSpan(LauncherTheme.HEAT), 0, cut, 0)
                    setSpan(android.text.style.ForegroundColorSpan(LauncherTheme.COOL), cut + 1, summary.length, 0)
                }
            } else summary
            value.setTextColor(
                when {
                    !s.known -> LauncherTheme.TEXT_SECONDARY
                    hot -> LauncherTheme.HEAT
                    cold -> LauncherTheme.COOL
                    else -> LauncherTheme.TEXT
                },
            )
        }
    }

    private companion object {
        const val PADDING_DP = 20
        const val ROW_LABEL_SP = 18f
        const val ROW_VALUE_SP = 24f
        const val TALL_ROWS = 4
    }
}
