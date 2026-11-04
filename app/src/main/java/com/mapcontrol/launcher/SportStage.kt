package com.mapcontrol.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Bu sürecin spor sürüş verisi: en yüksek hız ve en iyi 0–100 ([SportSession]). Etkinliğin araç dinleyicisi her
 * örnekte besler (ekran açıkken); kadranlar buradan okur. Yalnız ana iş parçacığından kullanılır.
 */
internal object SportSessionStore {
    val session = SportSession()

    fun feed(snap: VehicleMetricsSnapshot, rpmRaw: Int) {
        if (!snap.connected) return
        val speed = when {
            VehicleMetricsSnapshot.isValid(snap.displaySpeed) || VehicleMetricsSnapshot.isValid(snap.actualSpeed) -> snap.preferredSpeed()
            else -> null
        }
        val rpm = if (VehicleMetricsSnapshot.isValid(rpmRaw)) SportMath.rpm(rpmRaw) else null
        session.update(SystemClock.elapsedRealtime(), speed, rpm)
    }
}

/** Demo spor değerleri (`--ez launcher_demo_sport true`): ekran görüntüleri için sabit ve gerçekçi. */
internal object SportDemo {
    const val RPM = 3_400
    const val GEAR_STATE = 5
    const val GEAR_NUMBER = 4
    const val INSTANT_L100 = 9.8
    const val ZERO100_MS = 9_600L
    const val MAX_KMH = 142
}

/**
 * Spor modunun orta alanı (kronograf): solda yuvarlak medya modülü, ortada 0–8 devir kadranı ve vites penceresi,
 * iki yanda küçük ve sakin alt kadranlar (0–100, G, Anlık, Maks; G-metre yalnız ivme sensörü varsa), sağda burnu yukarı
 * araç modülü (açık kapı/bagaj, stop parıltısı).
 *
 * Performans: bütün durağan çizim ([SportArt.render]) boyut başına bir kez arka planda bitmap'e çizilir; çizimde yalnızca
 * o bitmap basılır. Canlı olanlar: ibre (ayrı görünüm, donanım katmanında döndürülür), metinler (değişince), G noktası
 * (öteleme), medya halkası (1 Hz). Değerler en çok 10 Hz güncellenir; sensör ~15 Hz ve yalnız spor görünürken dinlenir.
 */
@SuppressLint("ViewConstructor")
internal class SportStageView(
    private val activity: Activity,
    /** Araç modülüne dokununca (araç detayı). */
    private val onCarTap: () -> Unit,
) : ViewGroup(activity) {

    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    /** G-metre gösterilir mi: sensör var ve demo "sensör yok" demiyor. */
    private val hasG: Boolean = !LauncherDemo.sportNoG && (linearSensor != null || accelSensor != null)

    private var geometry: SportGeometry? = null
    private var art: Bitmap? = null
    private var artKey = 0L
    private var rendering = false
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())

    // --- Canlı parçalar ---
    private val needle = NeedleView(activity)
    private val gear = sahneText(activity, 40f, SportInk.INK, 600, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    private val values = HashMap<SportGeometry.Kind, TextView>()
    private val gDot = View(activity).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(SportInk.RED_HOT) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val gValue = sahneText(activity, 15f, SportInk.INK_3, 400, dayBump = false).apply { gravity = Gravity.CENTER }
    private val innerInstant = sahneText(activity, 24f, SportInk.INK_2, 300, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    private val media = SportMediaDial(activity)
    private val car = CarStageView(activity).apply {
        contentDescription = activity.getString(R.string.launcher_sahne_car)
        isClickable = true
        setOnClickListener { onCarTap() }
    }
    private val glows = ArrayList<GlowView>()
    private val live: List<View> get() = listOfNotNull(gear, gValue.takeIf { hasG }, gDot.takeIf { hasG }, innerInstant.takeIf { !hasG }, media, car) + values.values

    private var visibleNow = false
    private var sweeping = false
    private var shownRpm = 0f
    private var needleAnim: ValueAnimator? = null
    private val gFilter = GFilter(linear = linearSensor != null)
    private var lastText = HashMap<View, String>()

    init {
        clipChildren = false
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val kinds = if (hasG) listOf(SportGeometry.Kind.ZERO100, SportGeometry.Kind.INSTANT, SportGeometry.Kind.MAX)
        else listOf(SportGeometry.Kind.ZERO100, SportGeometry.Kind.MAX)
        kinds.forEach { k ->
            values[k] = sahneText(activity, 46f, SportInk.INK_2, 300, -0.02f, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        }
        addView(media)
        addView(car)
        values.values.forEach { addView(it) }
        if (hasG) { addView(gValue); addView(gDot) }
        else addView(innerInstant)
        addView(gear)
        addView(needle)
        // Yazı tipleri ana iş parçacığında bir kez yüklenir (arka plan çizimi önbellekten alır).
        listOf(300, 400, 500, 600).forEach { SahneFonts.get(activity, it) }
    }

    // --- Yaşam döngüsü ---

    /** Spor ekranı görünür oldu: veri, sensör ve medya başlar. */
    fun onVisible() {
        if (visibleNow) return
        visibleNow = true
        media.onVisible()
        TripSource.add(activity, tripListener)
        registerSensor()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun onHidden() {
        if (!visibleNow) return
        visibleNow = false
        media.onHidden()
        TripSource.remove(tripListener)
        sensors?.unregisterListener(sensorListener)
        main.removeCallbacks(tick)
        main.removeCallbacks(gPost)
    }

    /** Spor kapandı: büyük bitmap bırakılır (4–6 MB), yeniden açılınca arka planda yeniden çizilir. */
    fun release() {
        onHidden()
        needleAnim?.cancel()
        glows.forEach { it.animate().cancel() }
        art = null
        artKey = 0
        invalidate()
    }

    fun setCar(parts: Set<CarPart>, windows: Collection<BodyPart>, sunroof: Boolean, animate: Boolean) {
        car.setOpen(parts, animate)
        car.setGlass(windows, sunroof, animate)
    }

    // --- Veri ---

    private var trip = TripData()
    private val tripListener = TripSource.Listener { trip = it }
    private val metrics by lazy { VehicleMetricsRepository.getInstance(activity.applicationContext) }

    private val tick = object : Runnable {
        override fun run() {
            if (!visibleNow) return
            refresh()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun refresh() {
        val demo = LauncherDemo.enabled
        val rpm: Int?
        val gearText: String
        val instant: Double?
        val best: Long?
        val maxKmh: Int?
        if (demo) {
            rpm = SportDemo.RPM
            gearText = SportMath.gearText(SportDemo.GEAR_STATE, SportDemo.GEAR_NUMBER)
            instant = SportDemo.INSTANT_L100
            best = SportDemo.ZERO100_MS
            maxKmh = SportDemo.MAX_KMH
        } else {
            val snap = metrics.currentSnapshot()
            val raw = metrics.getCombined(ReadOnlyID.ID_ENGINE_RPM)
            rpm = if (snap.connected && VehicleMetricsSnapshot.isValid(raw)) SportMath.rpm(raw) else null
            gearText = SportMath.gearText(snap.gearSignal.takeIf { snap.connected && VehicleMetricsSnapshot.isValid(it) })
            // Anlık tüketim yalnızca hareket halinde geçerli gelir (dururken 0xFFFF).
            instant = trip.instantL100
            val s = SportSessionStore.session
            best = s.best0to100Ms
            maxKmh = s.maxSpeedKmh
        }
        setText(gear, gearText)
        values[SportGeometry.Kind.ZERO100]?.let { setText(it, SportFormat.seconds(best) ?: DASH) }
        val inst = SportFormat.l100(instant) ?: DASH
        values[SportGeometry.Kind.INSTANT]?.let { setText(it, inst) }
        if (!hasG) setText(innerInstant, inst)
        values[SportGeometry.Kind.MAX]?.let { setText(it, maxKmh?.toString() ?: DASH) }
        if (!sweeping) moveNeedle((rpm ?: 0).toFloat(), NEEDLE_MS)
    }

    private fun setText(v: TextView, s: String) {
        if (lastText[v] == s) return
        lastText[v] = s
        v.text = s
    }

    private fun moveNeedle(rpm: Float, ms: Long, delay: Long = 0, interp: android.animation.TimeInterpolator = DecelerateInterpolator(), end: (() -> Unit)? = null) {
        if (abs(rpm - shownRpm) < 10f && end == null && needleAnim?.isRunning != true) return
        needleAnim?.cancel()
        val from = shownRpm
        needleAnim = ValueAnimator.ofFloat(from, rpm).apply {
            duration = ms
            startDelay = delay
            interpolator = interp
            addUpdateListener {
                shownRpm = it.animatedValue as Float
                needle.rotation = SportMath.needleDeg(shownRpm)
            }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun snapNeedle(rpm: Float) {
        needleAnim?.cancel()
        shownRpm = rpm
        needle.rotation = SportMath.needleDeg(rpm)
    }

    // --- G sensörü ---

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            gFilter.add(e.timestamp, e.values[0], e.values[1], e.values[2])
            if (!gPending) {
                gPending = true
                main.postDelayed(gPost, G_UI_MS)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private var gPending = false
    private val gPost = Runnable {
        gPending = false
        bindG()
    }

    private fun registerSensor() {
        if (!hasG) return
        val s = linearSensor ?: accelSensor ?: return
        gFilter.reset()
        sensors?.registerListener(sensorListener, s, GFilter.SAMPLING_US, main)
    }

    private fun bindG() {
        val g = geometry ?: return
        val d = g.sub(SportGeometry.Kind.G) ?: return
        val range = d.r * SportGeometry.G_RANGE
        // Tam piksele yuvarlanır: sabit duruşta süzgecin küçük kıpırtısı yeni kare çizdirmez.
        val tx = Math.round(GFilter.clampUnit(gFilter.lateral) * range).toFloat()
        val ty = Math.round(-GFilter.clampUnit(gFilter.longitudinal) * range).toFloat()
        if (gDot.translationX != tx) gDot.translationX = tx
        if (gDot.translationY != ty) gDot.translationY = ty
        setText(gValue, SportFormat.g(gFilter.magnitude))
    }

    // --- Geçiş ---

    /** Canlı değerler gizli, ibre sıfırda: geçişin başı (ekran kararıkken çağrılır). */
    fun prepareIntro() {
        live.forEach { it.animate().cancel(); it.alpha = 0f }
        snapNeedle(0f)
        sweeping = true
    }

    /**
     * Giriş: kadran çerçeveleri soldan sağa sırayla parlar, ibre bir kez sona süpürüp canlı değere iner, değerler belirir.
     * Toplam [INTRO_MS] (karartma kalktıktan sonra). Yalnızca donanım katmanında alfa/dönme.
     */
    fun playIntro() {
        val g = geometry
        if (g != null) {
            ensureGlows(g)
            glows.forEachIndexed { i, v ->
                v.animate().cancel()
                v.alpha = 0f
                v.visibility = View.VISIBLE
                // Tek çizim (gradyan halka): alfa doğrudan boyaya uygulanır, ara katman gerekmez.
                v.animate().alpha(1f).setStartDelay(GLOW_DELAY_MS + i * GLOW_STAGGER_MS).setDuration(GLOW_UP_MS).withEndAction {
                    v.animate().alpha(0f).setStartDelay(0).setDuration(GLOW_DOWN_MS).withEndAction { v.visibility = View.GONE }.start()
                }.start()
            }
        }
        moveNeedle(SportMath.RPM_MAX.toFloat(), SWEEP_UP_MS, delay = SWEEP_DELAY_MS, interp = AccelerateDecelerateInterpolator()) {
            sweeping = false
            refresh()
            moveNeedle(targetRpm(), SWEEP_DOWN_MS, interp = DecelerateInterpolator(1.5f))
        }
        live.forEach { v ->
            v.animate().alpha(1f).setStartDelay(LIVE_DELAY_MS).setDuration(LIVE_FADE_MS).apply { if (v === media || v === car) withLayer() }.start()
        }
    }

    /** Çıkış başı: değerler söner, ibre sakince sıfıra iner ([OUTRO_MS]). */
    fun playOutro() {
        sweeping = true
        live.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setStartDelay(0).setDuration(OUTRO_FADE_MS).apply { if (v === media || v === car) withLayer() }.start()
        }
        moveNeedle(0f, OUTRO_MS, interp = AccelerateDecelerateInterpolator()) {}
    }

    /** Animasyonsuz: her şey yerinde (animasyonlar kapalı ya da tema yenilemesi). */
    fun showInstant() {
        glows.forEach { it.animate().cancel(); it.visibility = View.GONE }
        live.forEach { it.animate().cancel(); it.alpha = 1f }
        sweeping = false
        refresh()
        snapNeedle(targetRpm())
    }

    private fun targetRpm(): Float =
        if (LauncherDemo.enabled) SportDemo.RPM.toFloat()
        else SportMath.rpm(metrics.getCombined(ReadOnlyID.ID_ENGINE_RPM).takeIf { VehicleMetricsSnapshot.isValid(it) })?.toFloat() ?: 0f

    private fun ensureGlows(g: SportGeometry) {
        if (glows.size == g.inOrder.size && glows.firstOrNull()?.dial?.let { it.cx == g.inOrder[0].cx } == true) return
        glows.forEach { removeView(it) }
        glows.clear()
        g.inOrder.forEach { d ->
            val v = GlowView(activity, d).apply { visibility = View.GONE }
            glows += v
            addView(v, 0)
        }
        requestLayout()
    }

    // --- Çizim ve yerleşim ---

    /** Durağan çizim hazır mı (boyut biliniyor ve bitmap çizildi). */
    val ready: Boolean get() = art != null

    /** Bitmap hazır olunca bir kez çağrılır (hazırsa hemen). */
    fun whenReady(block: () -> Unit) {
        if (art != null) { block(); return }
        readyWaiters += block
        ensureArt()
    }

    private val readyWaiters = ArrayList<() -> Unit>()

    private fun ensureArt() {
        val g = geometry ?: return
        val key = (g.w.toLong() shl 32) or g.h.toLong()
        if (artKey == key && art != null) return
        if (rendering) return
        rendering = true
        val ctx = activity.applicationContext
        io.execute {
            val bmp = try { SportArt.render(ctx, g) } catch (t: Throwable) { null }
            main.post {
                rendering = false
                if (bmp == null) return@post
                if (geometry?.let { it.w == g.w && it.h == g.h } != true) { ensureArt(); return@post }
                art = bmp
                artKey = key
                invalidate()
                val run = ArrayList(readyWaiters)
                readyWaiters.clear()
                run.forEach { it() }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        art?.let { canvas.drawBitmap(it, 0f, 0f, artPaint) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (w <= 0 || h <= 0) return
        val g = geometry?.takeIf { it.w == w && it.h == h } ?: SportGeometry(w, h, hasG).also {
            geometry = it
            if (glows.isNotEmpty()) ensureGlows(it)
            ensureArt()
        }
        val m = g.media
        media.measure(exactly((m.r * 2).toInt()), exactly((m.r * 2).toInt()))
        val carLen = g.carLength().toInt()
        car.measure(exactly(CarStageView.widthFor(carLen)), exactly(CarStageView.heightFor(carLen)))
        val nr = (g.rpmFace * NeedleView.LENGTH * 2).toInt() + 8
        needle.measure(exactly(nr), exactly(nr))
        val win = g.gearWindow()
        gear.setTextSize(TypedValue.COMPLEX_UNIT_PX, win.height() * 0.72f)
        gear.measure(exactly(win.width().toInt()), exactly(win.height().toInt()))
        values.forEach { (k, v) ->
            val d = g.sub(k) ?: return@forEach
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.r * 0.46f)
            v.measure(exactly((d.r * 1.6f).toInt()), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        g.sub(SportGeometry.Kind.G)?.let { d ->
            val ds = (d.r * 0.14f).toInt()
            gDot.measure(exactly(ds), exactly(ds))
            gValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.r * 0.14f)
            gValue.measure(exactly((d.r).toInt()), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        if (!hasG) {
            innerInstant.setTextSize(TypedValue.COMPLEX_UNIT_PX, g.rpmFace * 0.12f)
            innerInstant.measure(exactly((g.rpmFace * 0.36f).toInt()), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        glows.forEach { v ->
            val s = (v.dial.r * 2 + v.pad * 2).toInt()
            v.measure(exactly(s), exactly(s))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geometry ?: return
        fun centerAt(v: View, cx: Float, cy: Float) {
            val left = (cx - v.measuredWidth / 2f).toInt()
            val top = (cy - v.measuredHeight / 2f).toInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
        centerAt(media, g.media.cx, g.media.cy)
        // Araç: gövde kadranın ortasında, burun yukarı (görünüm -90° döner; eksen gövdenin ortası).
        val carLen = g.carLength().toInt()
        val bodyCx = CarStageView.carLeft(carLen) + carLen / 2f
        val bodyCy = CarStageView.carTop(carLen) + carLen * CarAnimSpec.BASE_H / CarAnimSpec.BASE_W / 2f
        val cl = (g.car.cx - bodyCx).toInt()
        val ct = (g.car.cy - bodyCy).toInt()
        car.layout(cl, ct, cl + car.measuredWidth, ct + car.measuredHeight)
        car.pivotX = bodyCx
        car.pivotY = bodyCy
        car.rotation = -90f
        centerAt(needle, g.rpm.cx, g.rpm.cy)
        needle.pivotX = needle.measuredWidth / 2f
        needle.pivotY = needle.measuredHeight / 2f
        needle.rotation = SportMath.needleDeg(shownRpm)
        val win = g.gearWindow()
        centerAt(gear, win.centerX(), win.centerY())
        values.forEach { (k, v) ->
            val d = g.sub(k) ?: return@forEach
            centerAt(v, d.cx, d.cy + d.r * 0.02f)
        }
        g.sub(SportGeometry.Kind.G)?.let { d ->
            centerAt(gDot, d.cx, d.cy)
            centerAt(gValue, d.cx, d.cy + d.r * 0.72f)
        }
        if (!hasG) centerAt(innerInstant, g.rpm.cx, g.innerInstantY())
        glows.forEach { centerAt(it, it.dial.cx, it.dial.cy) }
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(maxOf(0, px), MeasureSpec.EXACTLY)

    companion object {
        private const val DASH = "—"
        const val TICK_MS = 100L
        const val NEEDLE_MS = 110L
        const val G_UI_MS = 100L

        // Giriş zamanlaması (karartma kalktıktan sonra): çerçeve parıltısı, ibre süpürmesi, değerler.
        const val GLOW_DELAY_MS = 60L
        const val GLOW_STAGGER_MS = 70L
        const val GLOW_UP_MS = 160L
        const val GLOW_DOWN_MS = 360L
        const val SWEEP_DELAY_MS = 120L
        const val SWEEP_UP_MS = 380L
        const val SWEEP_DOWN_MS = 320L
        const val LIVE_DELAY_MS = 720L
        const val LIVE_FADE_MS = 230L
        const val OUTRO_FADE_MS = 300L
        const val OUTRO_MS = 480L

        /** Arka plan işleri (durağan çizim, kapak küçültme): tek iş parçacığı. */
        internal val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sport-art").apply { isDaemon = true } }
    }
}

/**
 * Devir ibresi: ince kırmızı-turuncu, kuyruklu; merkez göbeği de bu görünümde (dönmeden bağımsız). Bir kez çizilir,
 * donanım katmanında tutulur; dönme yalnız görünüm özelliğidir (yeniden çizim yok).
 */
internal class NeedleView(context: Context) : View(context) {
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = android.graphics.Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val len = w / 2f - 4f
        val tail = len * 0.16f
        val base = len * 0.032f
        path.reset()
        path.moveTo(cx - tail, cy - base * 0.8f)
        path.lineTo(cx + len, cy - base * 0.22f)
        path.lineTo(cx + len, cy + base * 0.22f)
        path.lineTo(cx - tail, cy + base * 0.8f)
        path.close()
        needle.shader = LinearGradient(cx - tail, 0f, cx + len, 0f, intArrayOf(0xFF7A2A0C.toInt(), SportInk.RED, SportInk.RED_HOT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        core.color = 0xB3FFD2B8.toInt()
        core.strokeWidth = maxOf(1f, base * 0.3f)
        val hr = len * 0.12f
        hub.shader = RadialGradient(cx, cy - hr * 0.3f, hr * 1.2f, intArrayOf(0xFF3C4047.toInt(), 0xFF111214.toInt(), 0xFF050506.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        ring.strokeWidth = maxOf(1.5f, hr * 0.12f)
        ring.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(SportInk.METAL_MID, SportInk.METAL_HI, SportInk.METAL_LO, SportInk.METAL_MID), null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val len = width / 2f - 4f
        canvas.drawPath(path, needle)
        canvas.drawLine(cx + len * 0.2f, cy, cx + len * 0.96f, cy, core)
        val hr = len * 0.12f
        canvas.drawCircle(cx, cy, hr, hub)
        canvas.drawCircle(cx, cy, hr - ring.strokeWidth / 2f, ring)
    }

    companion object {
        /** İbre boyu (devir yüz yarıçapına oran). */
        const val LENGTH = 0.86f
    }
}

/** Geçişte bir kadran çerçevesinin parıltısı (gradyan halka; bulanıklık yok). Yalnızca animasyon sürerken görünür. */
@SuppressLint("ViewConstructor")
internal class GlowView(context: Context, val dial: SportGeometry.Dial) : View(context) {
    val pad: Float = dial.r * 0.12f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        SportArt.glowRing(canvas, width / 2f, height / 2f, dial.r - dial.r * 0.035f, pad)
    }
}

/**
 * Yuvarlak medya modülü: dairesel kapak, ince kırmızı-turuncu ilerleme halkası, kaynak, başlık (iki satıra sığana dek
 * küçülür, [MediaCard] Sahne başlığıyla aynı yöntem), sanatçı; altta önceki / oynat / sonraki metal düğmeler (en az 64dp).
 * Medya denetleyicisine bağlı (yalnız görünürken dinler).
 */
internal class SportMediaDial(private val activity: Activity) : ViewGroup(activity) {
    private enum class Mode { NO_ACCESS, IDLE, SESSION }

    private val controller = LauncherMediaController(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var listening = false

    private val ring = ProgressRing(activity)
    private val cover = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
        }
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF15171A.toInt()) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val note = ImageView(activity).apply {
        setImageResource(R.drawable.ic_sahne_note)
        imageTintList = android.content.res.ColorStateList.valueOf(SportInk.INK_3)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val source = sahneText(activity, 16f, SportInk.INK_3, 500, dayBump = false).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val title = sahneText(activity, TITLE_SP, SportInk.INK, 600, dayBump = false).apply {
        gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
    private val artist = sahneText(activity, 18f, SportInk.INK_2, 400, dayBump = false).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val prev = knob(R.drawable.ic_sahne_prev, R.string.launcher_media_prev, false) { controller.skipToPrevious() }
    private val play = knob(R.drawable.ic_sahne_play, R.string.launcher_media_play, true) {
        when (mode) {
            Mode.SESSION -> controller.playPause()
            Mode.IDLE -> controller.launchLastSource()
            Mode.NO_ACCESS -> controller.openNotificationAccessSettings()
        }
    }
    private val next = knob(R.drawable.ic_sahne_next, R.string.launcher_media_next, false) { controller.skipToNext() }
    private val playIcon = play.getChildAt(0) as ImageView

    private var basePos = 0L
    private var baseAt = 0L
    private var duration = 0L
    private var playing: Boolean? = null
    private var shownArt: Bitmap? = null

    private val listener = LauncherMediaController.Listener { s -> if (!LauncherDemo.enabled) bind(s) }
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            if (playing == true && mode == Mode.SESSION) handler.postDelayed(this, 1_000L)
        }
    }

    init {
        clipChildren = false
        addView(ring)
        addView(cover)
        addView(note)
        addView(source)
        addView(title)
        addView(artist)
        addView(prev)
        addView(play)
        addView(next)
    }

    private fun knob(icon: Int, label: Int, main: Boolean, onClick: () -> Unit): FrameLayout = FrameLayout(activity).apply {
        background = SportKnobDrawable(activity, knurled = main, insetDp = 1f)
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) })
        contentDescription = activity.getString(label)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(SportInk.INK)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(activity.dp(if (main) 30 else 26), activity.dp(if (main) 30 else 26), Gravity.CENTER))
    }

    fun onVisible() {
        if (!listening) {
            controller.addListener(listener)
            listening = true
        }
        controller.start()
        bind(if (LauncherDemo.enabled) LauncherDemo.media() else controller.currentState())
    }

    fun onHidden() {
        if (listening) {
            controller.removeListener(listener)
            listening = false
        }
        controller.stop()
        handler.removeCallbacks(progressTick)
    }

    private fun bind(s: MediaState) {
        mode = when {
            !s.notificationAccessGranted -> Mode.NO_ACCESS
            !s.sessionAvailable -> Mode.IDLE
            else -> Mode.SESSION
        }
        val resume = if (mode == Mode.IDLE) controller.lastSourcePackage() else null
        val src = when (mode) { Mode.SESSION -> s.source; Mode.IDLE -> resume; Mode.NO_ACCESS -> null }
        source.text = src?.let { appLabel(it) } ?: ""
        title.text = when (mode) {
            Mode.SESSION -> s.title ?: ""
            Mode.IDLE -> activity.getString(R.string.launcher_media_idle_title)
            Mode.NO_ACCESS -> activity.getString(R.string.launcher_media_title_neutral)
        }
        artist.text = when (mode) {
            Mode.SESSION -> s.artist ?: ""
            Mode.IDLE -> activity.getString(if (resume != null) R.string.launcher_media_idle_resume else R.string.launcher_media_idle_hint)
            Mode.NO_ACCESS -> activity.getString(R.string.launcher_media_access_hint)
        }
        fitTitle()
        val session = mode == Mode.SESSION
        prev.visibility = if (session) View.VISIBLE else View.INVISIBLE
        next.visibility = prev.visibility
        play.isEnabled = session || resume != null || mode == Mode.NO_ACCESS
        play.alpha = if (play.isEnabled) 1f else 0.4f
        val art = if (session) s.artwork else null
        if (art !== shownArt) {
            shownArt = art
            val limit = cover.width.takeIf { it > 0 } ?: activity.dp(110)
            if (art == null || maxOf(art.width, art.height) <= limit * 3 / 2) cover.setImageBitmap(art)
            else {
                // Büyük kapak arka planda küçültülür (GPU'ya dev doku yüklenmesin).
                cover.setImageDrawable(null)
                SportStageView.io.execute {
                    val k = limit.toFloat() / maxOf(art.width, art.height)
                    val scaled = try { Bitmap.createScaledBitmap(art, maxOf(1, (art.width * k).toInt()), maxOf(1, (art.height * k).toInt()), true) } catch (e: RuntimeException) { null }
                    handler.post { if (shownArt === art) cover.setImageBitmap(scaled) }
                }
            }
        }
        note.visibility = if (art == null) View.VISIBLE else View.GONE
        val isPlaying = session && s.playing
        if (playing != isPlaying) {
            playing = isPlaying
            playIcon.setImageResource(if (isPlaying) R.drawable.ic_sahne_pause else R.drawable.ic_sahne_play)
            play.contentDescription = activity.getString(if (isPlaying) R.string.launcher_media_pause else R.string.launcher_media_play)
        }
        basePos = s.positionMs
        baseAt = SystemClock.elapsedRealtime()
        duration = if (session) s.durationMs else 0L
        handler.removeCallbacks(progressTick)
        if (LauncherDemo.enabled) updateProgress() else handler.post(progressTick)
    }

    private fun updateProgress() {
        var pos = basePos
        if (playing == true && !LauncherDemo.enabled) pos += SystemClock.elapsedRealtime() - baseAt
        ring.set(if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = activity.packageManager
        AppIdentity.label(activity, pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
    } catch (e: Exception) {
        pkg
    }

    /** Uzun başlık iki satıra kelime bölmeden sığana dek [TITLE_SP]'den [TITLE_MIN_SP]'ye küçülür. */
    private fun fitTitle() {
        val width = title.measuredWidth.takeIf { it > 0 } ?: titleWidth
        val text = title.text
        if (width <= 0 || text.isNullOrEmpty()) return
        val paint = TextPaint(title.paint)
        val dm = resources.displayMetrics
        var sp = TITLE_SP
        while (sp > TITLE_MIN_SP) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(title.includeFontPadding).build()
            if (l.lineCount <= 2 && (0 until l.lineCount - 1).none { splits(text, l.getLineEnd(it)) }) break
            sp -= 2f
        }
        if (title.textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)) title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun splits(t: CharSequence, end: Int) = end in 1 until t.length && !t[end - 1].isWhitespace() && !t[end].isWhitespace() && t[end - 1] != '-'

    private var titleWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
        val r = s / 2f
        fun ex(px: Float) = MeasureSpec.makeMeasureSpec(px.toInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
        val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        ring.measure(ex(s.toFloat()), ex(s.toFloat()))
        cover.measure(ex(r * 0.48f), ex(r * 0.48f))
        note.measure(ex(r * 0.18f), ex(r * 0.18f))
        source.measure(ex(r * 1.1f), wrap)
        val tw = (r * 1.34f).toInt()
        if (tw != titleWidth) { titleWidth = tw; fitTitle() }
        title.measure(ex(r * 1.34f), MeasureSpec.makeMeasureSpec((r * 0.4f).toInt(), MeasureSpec.AT_MOST))
        artist.measure(ex(r * 1.2f), wrap)
        val small = activity.dp(64).toFloat()
        val big = activity.dp(76).toFloat()
        prev.measure(ex(small), ex(small))
        next.measure(ex(small), ex(small))
        play.measure(ex(big), ex(big))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
        val s = width.toFloat()
        val r = s / 2f
        fun at(v: View, cx: Float, cy: Float) {
            val left = (cx - v.measuredWidth / 2f).toInt()
            val top = (cy - v.measuredHeight / 2f).toInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
        ring.layout(0, 0, ring.measuredWidth, ring.measuredHeight)
        at(cover, r, r - r * 0.53f)
        at(note, r, r - r * 0.53f)
        at(source, r, r - r * 0.205f)
        at(title, r, r + r * 0.03f)
        at(artist, r, r + r * 0.27f)
        val by = r + r * 0.56f
        val gap = activity.dp(16).toFloat()
        at(play, r, by)
        at(prev, r - play.measuredWidth / 2f - gap - prev.measuredWidth / 2f, by)
        at(next, r + play.measuredWidth / 2f + gap + next.measuredWidth / 2f, by)
    }

    companion object {
        const val TITLE_SP = 26f
        const val TITLE_MIN_SP = 18f
    }
}

/** Medya ilerleme halkası: üstten saat yönünde ince kırmızı-turuncu yay (iz durağan çizimde). Yalnız değişince çizilir. */
internal class ProgressRing(context: Context) : View(context) {
    private var fraction = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = SportInk.RED
    }
    private val rect = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(f: Float) {
        val q = (f * 720).toInt() / 720f
        if (q == fraction) return
        fraction = q
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (fraction <= 0f) return
        val r = width / 2f
        val rr = r * SportGeometry.PROGRESS_R
        paint.strokeWidth = r * SportGeometry.PROGRESS_W * 1.5f
        rect.set(r - rr, r - rr, r + rr, r + rr)
        canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
    }
}
