package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R

/**
 * Sahne'nin ortası (yuva değil, sabit): ışıklı zeminde araç görseli ve yumuşak halkalar; sol üstte menzil ve yakıt
 * çubuğu, sağ üstte kilit hapı (dokununca kilit kutucuğuyla aynı yol), sol altta araç sağlığı, sağ altta hava kalitesi.
 * Açık kapı / bagaj / cam / kaput ve lastik / yakıt kapağı uyarıları aracın üstünde turuncu haplarla görünür; araç
 * kartının gösterdiği hiçbir bilgi kaybolmaz.
 *
 * Veri, bugünkü widget'ların kendisinden gelir: ekrana konmayan bir [VehicleHeroCard] ([VehicleHeroCard.Summary]),
 * [LockTile], [HealthTile] ve [AirQualityTile] ([StripSource]); kaynaklar, yoklama hızları ve yazma kuralları aynıdır.
 * Tasarım ölçüleri 960 × 404'lük taslak sahnesindendir ve genişliğe oranlanır.
 */
class SahneStage(private val activity: Activity) : ViewGroup(activity), LauncherWidget {

    private val vehicle = VehicleHeroCard(activity).apply { applySpan(Span(4, 4)) }
    private val lock = LockTile(activity)
    private val health = HealthTile(activity).apply { applySpan(Span(2, 2)) }
    private val air = AirQualityTile(activity).apply { applySpan(Span(2, 2)) }
    private val sources: List<LauncherWidget> = listOf(vehicle, lock, health, air)

    private val floor = FloorView(activity)
    private val car = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(carBitmap(activity))
        contentDescription = activity.getString(R.string.launcher_sahne_car)
    }

    // --- Menzil ---
    // Sahibinin isteği: taslaktaki 84'ten küçük menzil bloğu (76 / 24 / 18, yakıt çubuğu 110), araç ortada dengelensin.
    private val rangeNumber = sahneText(activity, RANGE_SP, Sahne.INK, 300, -0.04f)
    private val rangeUnit = sahneText(activity, 24f, Sahne.INK_2, 500)
    private val rangeLabel = sahneText(activity, 18f, Sahne.INK_3, 400).apply { setText(R.string.launcher_range_label) }
    private val fuelIcon = sahneIcon(R.drawable.ic_sahne_fuel, Sahne.INK_3)
    private val gauge = FuelGauge(activity)
    private val fuelText = sahneText(activity, 20f, Sahne.INK, 500)
    private val rangeBlock = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        // CSS satır yüksekliği .9: büyük rakamın kutusu yazının doğal yüksekliğinden kısa; satır yukarı/aşağı kırpılır.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(rangeNumber, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(rangeUnit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(10) })
            addView(rangeLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(12) })
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -context.dp(RANGE_TRIM_DP); bottomMargin = -context.dp(RANGE_TRIM_DP) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fuelIcon, LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
            addView(gauge, LinearLayout.LayoutParams(context.dp(110), context.dp(6)).apply { marginStart = context.dp(12) })
            addView(fuelText, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(12) })
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
    }


    // --- Kilit hapı ---
    private val lockIcon = sahneIcon(R.drawable.ic_sahne_lock, Sahne.ICE)
    private val lockText = sahneText(activity, 22f, Sahne.INK, 600, dayBump = false)
    private val lockChip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(context, Sahne.PILL_RADIUS_DP, shadow = 0.6f)
        setPadding(context.dp(22), 0, context.dp(28), 0)
        addView(lockIcon, LinearLayout.LayoutParams(context.dp(26), context.dp(26)))
        addView(lockText, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(12) })
        isClickable = true
        isFocusable = true
        setOnClickListener { lock.stripTap(0) }
        pressFeedback()
    }

    // --- Uyarı hapları (açık gövde parçası, lastik/cam/yakıt kapağı) ---
    private val doorsChip = AlertChip(activity, R.drawable.ic_mdi_car_door)
    private val alertsChip = AlertChip(activity, R.drawable.ic_mdi_alert)
    private val alertRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(doorsChip)
        addView(alertsChip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(10) })
    }

    // --- Notlar ---
    private val healthNote = Note(activity, R.drawable.ic_sahne_shield, end = false)
    private val airNote = Note(activity, R.drawable.ic_sahne_wind, end = true)

    /** Düzenleme modunda sahne dokunulmaz (yalnızca yuvalar düzenlenir). */
    var locked = false

    init {
        clipChildren = false
        clipToPadding = false
        addView(floor)
        addView(car)
        addView(rangeBlock)
        addView(lockChip)
        addView(alertRow)
        addView(healthNote)
        addView(airNote)
        vehicle.onSummary = { bindVehicle(it) }
        lock.onStripChanged = { lock.stripState()?.let { bindLock(it) } }
        health.onStripChanged = { healthNote.bind(health.stripState()) }
        air.onStripChanged = { airNote.bind(air.stripState()) }
        bindVehicle(vehicle.summary)
        lock.stripState()?.let { bindLock(it) } ?: bindLock(null)
        healthNote.bind(health.stripState())
        airNote.bind(air.stripState())
        SahneFonts.apply(rangeNumber, 300, -0.04f)
        rangeNumber.includeFontPadding = false
    }

    override val view: View get() = this

    override fun onVisible() = sources.forEach { it.onVisible() }

    override fun onHidden() = sources.forEach { it.onHidden() }

    override fun release() {
        vehicle.onSummary = null
        sources.forEach { it.release() }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = locked

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = locked

    private fun bindVehicle(s: VehicleHeroCard.Summary?) {
        val range = s?.rangeKm
        rangeNumber.setTextSmooth(range?.toString() ?: activity.getString(R.string.launcher_unavailable), animate = false)
        rangeNumber.setTextColor(if (range != null) Sahne.INK else Sahne.INK_3)
        rangeUnit.text = activity.getString(R.string.launcher_sahne_km)
        rangeUnit.visibility = if (range != null) View.VISIBLE else View.GONE
        val fuel = s?.fuel
        gauge.set(fuel, s?.lowFuel == true)
        fuelText.text = if (fuel != null) activity.getString(R.string.launcher_percent, fuel) else activity.getString(R.string.launcher_unavailable)
        fuelText.setTextColor(if (s?.lowFuel == true) Sahne.AMBER else Sahne.INK)
        rangeBlock.contentDescription = listOfNotNull(
            range?.let { activity.getString(R.string.launcher_range_value, it) + " " + activity.getString(R.string.launcher_range_label) },
            fuel?.let { activity.getString(if (s.lowFuel) R.string.launcher_fuel_low else R.string.launcher_fuel_short, it) },
        ).joinToString(", ")
        doorsChip.bind(s?.doorsText)
        alertsChip.bind(s?.alertsText)
        alertRow.visibility = if (s?.doorsText != null || s?.alertsText != null) View.VISIBLE else View.GONE
        floor.alert = s?.doorsText != null
        requestLayout()
    }

    private fun bindLock(s: StripState?) {
        lockText.text = s?.value ?: activity.getString(R.string.launcher_unavailable)
        lockIcon.setImageResource(if (s?.icon == R.drawable.ic_mdi_lock_open_variant) R.drawable.ic_sahne_lock_open else R.drawable.ic_sahne_lock)
        lockIcon.imageTintList = ColorStateList.valueOf(if (s == null || s.tone == StripTone.MUTED) Sahne.INK_3 else Sahne.ICE)
        lockText.setTextColor(if (s == null || s.tone == StripTone.MUTED) Sahne.INK_3 else Sahne.INK)
        lockChip.contentDescription = activity.getString(R.string.launcher_widget_lock) + ", " + lockText.text
        requestLayout()
    }

    // --- Yerleşim (taslak 960 × 404, üstten 12 birim başlık boşluğu payı) ---

    private fun s(): Float = width / DESIGN_W

    private fun y(designY: Float): Int = ((designY - TOP_OFFSET) * s()).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val k = w / DESIGN_W
        floor.measure(exactly(w), exactly(h))
        val carW = (CAR_W * k).toInt()
        car.measure(exactly(carW), exactly((carW * CAR_ASPECT).toInt()))
        val loose = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST)
        val looseH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        rangeBlock.measure(loose, looseH)
        lockChip.measure(MeasureSpec.makeMeasureSpec(w / 2, MeasureSpec.AT_MOST), exactly(context.dp(LauncherTheme.TOUCH_MIN_DP)))
        alertRow.measure(MeasureSpec.makeMeasureSpec((w * 0.40f).toInt(), MeasureSpec.AT_MOST), looseH)
        val noteW = MeasureSpec.makeMeasureSpec((w * 0.36f).toInt(), MeasureSpec.AT_MOST)
        healthNote.measure(noteW, looseH)
        airNote.measure(noteW, looseH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val k = s()
        floor.layout(0, 0, w, h)
        val cx = (CAR_X * k).toInt()
        val cy = y(CAR_Y - CAR_LIFT)
        car.layout(cx, cy, cx + car.measuredWidth, cy + car.measuredHeight)
        val side = (SIDE * k).toInt()
        rangeBlock.layout(side, y(RANGE_TOP), side + rangeBlock.measuredWidth, y(RANGE_TOP) + rangeBlock.measuredHeight)
        val lockTop = y(LOCK_TOP)
        lockChip.layout(w - side - lockChip.measuredWidth, lockTop, w - side, lockTop + lockChip.measuredHeight)
        // Uyarılar aracın hemen altında, ortada (iki not arasında; araç yukarı alındığı için yer var).
        val bottom = h - (NOTE_BOTTOM * k).toInt()
        val aTop = maxOf(cy + car.measuredHeight - context.dp(14), bottom - alertRow.measuredHeight)
        val aLeft = (w - alertRow.measuredWidth) / 2
        alertRow.layout(aLeft, aTop, aLeft + alertRow.measuredWidth, aTop + alertRow.measuredHeight)
        healthNote.layout(side, bottom - healthNote.measuredHeight, side + healthNote.measuredWidth, bottom)
        airNote.layout(w - side - airNote.measuredWidth, bottom - airNote.measuredHeight, w - side, bottom)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    private fun sahneIcon(res: Int, tint: Int) = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Sağlık / hava notu: ikonlu küçük başlık, nokta + değer, alt satır. Sağdaki not sağa yaslı, nokta değerin sonunda. */
    private class Note(context: Context, iconRes: Int, private val end: Boolean) : LinearLayout(context) {
        private val icon = ImageView(context).apply { setImageResource(iconRes); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val title = sahneText(context, 16f, Sahne.INK_3, 400)
        private val value = sahneText(context, 22f, Sahne.INK, 600)
        private val dot = View(context)
        private val sub = sahneText(context, 17f, Sahne.INK_2, 400)

        init {
            orientation = VERTICAL
            gravity = if (end) Gravity.END else Gravity.START
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LayoutParams(context.dp(20), context.dp(20)))
                addView(title, LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) })
            }, LayoutParams(WRAP, WRAP))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val dotLp = LayoutParams(context.dp(18), context.dp(18))
                if (end) {
                    addView(value)
                    addView(dot, dotLp.apply { marginStart = context.dp(6) })
                } else {
                    addView(dot, dotLp.apply { marginEnd = context.dp(6) })
                    addView(value)
                }
            }, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
            addView(sub, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
        }

        fun bind(s: StripState?) {
            title.text = s?.title ?: ""
            icon.imageTintList = ColorStateList.valueOf(Sahne.INK_3)
            value.text = s?.value ?: ""
            val known = s != null && s.tone != StripTone.MUTED
            val color = when (s?.tone) {
                StripTone.ALERT -> Sahne.AMBER
                null, StripTone.MUTED -> Sahne.INK_3
                else -> Sahne.OK
            }
            value.setTextColor(if (s?.tone == StripTone.ALERT) Sahne.AMBER else if (known) Sahne.INK else Sahne.INK_3)
            // Nokta: 10dp dolu daire ve 4dp yarı saydam halka (taslaktaki box-shadow halkası).
            dot.background = android.graphics.drawable.LayerDrawable(arrayOf(
                oval(if (s?.tone == StripTone.ALERT) Sahne.AMBER_SOFT else Sahne.OK_RING),
                oval(color),
            )).apply { val i = context.dp(4); setLayerInset(1, i, i, i, i) }
            dot.visibility = if (known) View.VISIBLE else View.GONE
            sub.text = s?.detail ?: ""
            sub.visibility = if (s?.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
            contentDescription = listOfNotNull(s?.title, s?.value, s?.detail).joinToString(", ")
        }
    }

    /** Turuncu uyarı hapı: ikon + kısa metin; metin yoksa gizli. */
    private class AlertChip(context: Context, iconRes: Int) : LinearLayout(context) {
        private val icon = ImageView(context).apply { setImageResource(iconRes); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val label = sahneText(context, 18f, Sahne.AMBER, 600).apply { maxLines = 1 }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(20), context.dp(8))
            addView(icon, LayoutParams(context.dp(22), context.dp(22)))
            addView(label, LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) })
            visibility = GONE
        }

        fun bind(text: String?) {
            if (text == null) { visibility = GONE; return }
            label.text = text
            label.setTextColor(Sahne.AMBER)
            icon.imageTintList = ColorStateList.valueOf(Sahne.AMBER)
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(999f)
                setColor(Sahne.AMBER_SOFT)
                setStroke(context.dp(1), Sahne.AMBER_LINE)
            }
            contentDescription = text
            visibility = VISIBLE
        }
    }

    /** İnce yakıt çubuğu: iz ve buz mavisi gradyan dolgu; düşük yakıtta turuncu. Yalnızca değer değişince çizilir. */
    private class FuelGauge(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var percent: Int? = null
        private var low = false

        fun set(p: Int?, low: Boolean) {
            if (p == percent && low == this.low) return
            percent = p
            this.low = low
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2f
            paint.shader = null
            paint.color = Sahne.TRACK
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, r, r, paint)
            val p = percent ?: return
            val w = maxOf(h, width * p.coerceIn(0, 100) / 100f)
            paint.shader = if (low) null else LinearGradient(0f, 0f, w, 0f, Sahne.ICE_LO, Sahne.ICE, Shader.TileMode.CLAMP)
            paint.color = Sahne.AMBER
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    /**
     * Sahne zemini (bitmap değil, kodla): yukarıdan düşen ışık konisi, aracın altındaki iki katmanlı ışık havuzu,
     * iki ince eliptik halka, geniş yumuşak gölge ve sıkı temas gölgesi; gece ön farların ve stop lambalarının
     * zemindeki yansıması. Gradyanlar yalnızca boyut değişince kurulur; görünüm statiktir.
     */
    private class FloorView(context: Context) : View(context) {
            var alert = false
        private val cone = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
        private val poolOuter = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val contact = Paint(Paint.ANTI_ALIAS_FLAG)
        private val head = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tail = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val oval = RectF()
        private var builtW = -1
        private var builtH = -1

        private fun build(w: Int, h: Int) {
            builtW = w
            builtH = h
            val k = w / DESIGN_W
            // Zemin ışığı, halkalar ve gölgeler araçla birlikte yukarı taşınır.
            fun yy(v: Float) = (v - TOP_OFFSET - CAR_LIFT) * k
            val cx = 480f * k
            cone.shader = SahneGround.ellipse(cx, yy(40f + 216f), 350f * k, 180f * k, Sahne.CONE, 1f)
            pool.shader = SahneGround.ellipse(cx, yy(270f), 400f * k, 150f * k, Sahne.FLOOR_INNER, 0.72f)
            poolOuter.shader = SahneGround.ellipse(cx, yy(270f), 560f * k, 210f * k, Sahne.FLOOR_OUTER, if (LauncherTheme.night) 0.72f else 0.74f)
            shadow.shader = SahneGround.ellipse(cx, yy(292f), 290f * k, 78f * k, Sahne.CAR_SHADOW, 1f)
            contact.shader = SahneGround.ellipse(cx, yy(270f), 250f * k, 58f * k, Sahne.CAR_CONTACT, 1f)
            // Burun sağda: farlar sağda (soğuk beyaz), stoplar solda (kırmızı); yalnızca gece.
            head.shader = if (Sahne.HEAD_GLOW == 0) null else SahneGround.ellipse(760f * k, yy(268f), 150f * k, 70f * k, Sahne.HEAD_GLOW, 1f)
            tail.shader = if (Sahne.TAIL_GLOW == 0) null else SahneGround.ellipse(212f * k, yy(268f), 70f * k, 56f * k, Sahne.TAIL_GLOW, 1f)
            ring.strokeWidth = maxOf(1f, k)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (w != builtW || h != builtH) build(w, h)
            val k = w / DESIGN_W
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cone)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), poolOuter)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), pool)
            // Halkalar: taslakta 800 × 240 ve 660 × 196 (merkez aracın altında).
            ring.color = Sahne.RING_1
            oval.set(80f * k, (150f - TOP_OFFSET - CAR_LIFT) * k, 880f * k, (390f - TOP_OFFSET - CAR_LIFT) * k)
            canvas.drawOval(oval, ring)
            ring.color = Sahne.RING_2
            oval.set(150f * k, (172f - TOP_OFFSET - CAR_LIFT) * k, 810f * k, (368f - TOP_OFFSET - CAR_LIFT) * k)
            canvas.drawOval(oval, ring)
            if (head.shader != null) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), head)
            if (tail.shader != null) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tail)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadow)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), contact)
        }
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DESIGN_W = 960f
        /** Taslak sahnesi başlığın 12 birim altından başlar; bizde sahne doğrudan başlık boşluğunun altında. */
        const val TOP_OFFSET = 12f
        const val CAR_X = 225f
        const val CAR_Y = 146f
        const val CAR_W = 510f
        const val CAR_ASPECT = 559f / 1205f
        const val SIDE = 30f
        const val RANGE_TOP = 22f
        const val LOCK_TOP = 26f
        const val NOTE_BOTTOM = 14f
        /** 84sp rakamın doğal satırı ile taslaktaki .9 satır yüksekliği arasındaki yarı fark. */
        const val RANGE_TRIM_DP = 18
        const val RANGE_SP = 76f
        /** Araç taslaktaki yerinden bu kadar (tasarım birimi) yukarıda: menzil satırı ile alt notlar arasında ortalı. */
        const val CAR_LIFT = 30f

        /** Araç görseli süreç boyunca bir kez çözülür (her yeniden kurulumda değil). */
        @Volatile private var cached: Bitmap? = null

        fun carBitmap(context: Context): Bitmap? {
            cached?.let { return it }
            return try {
                BitmapFactory.decodeResource(context.resources, R.drawable.launcher_stage_car, BitmapFactory.Options().apply { inScaled = false })
                    .also { cached = it }
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}
