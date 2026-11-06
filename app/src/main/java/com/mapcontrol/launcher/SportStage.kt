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
 * Bu sürecin spor sürüş verisi: en yüksek hız ve en iyi 0–100 ([SportSession]); kadranlar buradan okur.
 *
 * Süreç düzeyinde beslenir: [start] araç deposuna kalıcı bir dinleyici ekler, ana ekran görünmezken de (sürücü çoğu zaman
 * harita uygulamasındadır) her örnek işlenir. Yalnız okuma; örnek başına iş birkaç karşılaştırma. Sahne kapanınca ([stop])
 * dinleyici çıkar. Yalnız ana iş parçacığından kullanılır (depo dinleyicileri ana iş parçacığında çağırır).
 */
internal object SportSessionStore {
    val session = SportSession()
    private var repo: VehicleMetricsRepository? = null
    private val listener = VehicleMetricsRepository.Listener { snap -> feed(snap) }

    fun start(context: Context) {
        if (repo != null) return
        val r = VehicleMetricsRepository.getInstance(context.applicationContext)
        repo = r
        r.addListener(listener)
    }

    fun stop() {
        repo?.removeListener(listener)
        repo = null
    }

    private fun feed(snap: VehicleMetricsSnapshot) {
        val now = SystemClock.elapsedRealtime()
        // Servis yok (kontak kapanınca düşebilir): kapalı sayılır, yeni sürüş sayacı böylece işler.
        if (!snap.connected) { session.update(now, null, 0); return }
        // Gösterge hızı (aracın kadranıyla aynı; bkz. [SportSession]).
        val speed = when {
            VehicleMetricsSnapshot.isValid(snap.displaySpeed) || VehicleMetricsSnapshot.isValid(snap.actualSpeed) -> snap.preferredSpeed()
            else -> null
        }
        val raw = repo?.getCombined(ReadOnlyID.ID_ENGINE_RPM) ?: return
        val rpm = if (VehicleMetricsSnapshot.isValid(raw)) SportMath.rpm(raw) else null
        session.update(now, speed, rpm)
    }
}

/** Demo spor değerleri (`--ez launcher_demo_sport true`): ekran görüntüleri için sabit ve gerçekçi. */
internal object SportDemo {
    const val RPM = 3_400
    const val PEAK_RPM = 5_200
    const val GEAR_STATE = 5
    const val GEAR_NUMBER = 4
    const val INSTANT_L100 = 9.8
    const val ZERO100_MS = 9_600L
    const val PASS80_MS = 6_400L
    const val MAX_KMH = 142
}

/**
 * Spor modunun orta alanı (kronograf): solda yuvarlak medya modülü, ortada 0–8 devir kadranı (tepe işaretiyle) ve vites
 * penceresi, iki yanda dört küçük ve sakin alt kadran (seçilebilir, [SportDials]; varsayılan 0–100, G, Anlık, Maks),
 * sağda burnu yukarı araç modülü (açık kapı/bagaj, stop parıltısı).
 *
 * Düzenleme modunda küçük kadrana uzun basınca seçici açılır ([SportDialPicker]); seçim kalıcıdır ([SportDialPrefs]).
 *
 * Performans: bütün durağan çizim ([SportArt.render]) boyut ve seçim başına bir kez arka planda bitmap'e çizilir; çizimde
 * yalnızca o bitmap basılır. Canlı olanlar: ibreler (ayrı görünümler, donanım katmanında döndürülür), metinler
 * (değişince), G noktası (öteleme), medya halkası (1 Hz). Değerler en çok 10 Hz güncellenir, saat ibreleri saniyede bir;
 * sensör ~15 Hz ve yalnız G kadranı seçili ve spor görünürken dinlenir.
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
    /** G-metre sunulur mu: sensör var ve demo "sensör yok" demiyor. */
    private val hasG: Boolean = SportDialPrefs.hasG(activity)
    /** Küçük kadran seçimi (yuva sırasıyla). */
    private var dials: List<SportDial> = SportDialPrefs.load(activity)

    private var geometry: SportGeometry? = null
    private var art: Bitmap? = null
    /** [art]'ın çizildiği yerleşim (boyut + seçim). */
    private var artGeometry: SportGeometry? = null
    private var rendering = false
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())

    // --- Canlı parçalar ---
    private val needle = NeedleView(activity)
    private val peak = PeakView(activity)
    private val gear = sahneText(activity, 40f, SportInk.INK, 600, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    private val slots = ArrayList<Slot>()
    private val media = SportMediaDial(activity)
    private val car = CarStageView(activity).apply {
        contentDescription = activity.getString(R.string.launcher_sahne_car)
        isClickable = true
        setOnClickListener { onCarTap() }
        // Araç kadranı daha aydınlık (üstten ışık): gövde donanım katmanında bir kez parlatılır (renk matrisi), kare başına iş yok.
        setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(CAR_LIFT, CAR_LIFT, CAR_LIFT, 1f) })
        })
    }
    private val carLight = CarLightView(activity)
    private val glows = ArrayList<GlowView>()
    /** Geçişte solup beliren canlı görünümler (yalnız geçişte okunur). */
    private val live: List<View> get() = listOf(gear, peak, media, car, carLight) + slots.flatMap { it.liveViews }

    private var visibleNow = false
    private var sweeping = false
    private var editing = false
    private var picker: SportDialPicker? = null
    private var shownRpm = 0f
    // Tek ibre animatörü (10 Hz takipte her seferinde yenisi kurulmaz): başlangıç/hedef alanlardan okunur.
    private var needleFrom = 0f
    private var needleTo = 0f
    private var needleEnd: (() -> Unit)? = null
    private var needleCancelled = false
    private val needleAnim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener {
            shownRpm = needleFrom + (needleTo - needleFrom) * (it.animatedValue as Float)
            needle.rotation = SportMath.needleDeg(shownRpm)
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) { needleCancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                val end = needleEnd
                needleEnd = null
                if (!needleCancelled) end?.invoke()
            }
        })
    }
    private val gFilter = GFilter(linear = linearSensor != null)
    private val lastText = HashMap<View, String>()
    private var shownPeak = -1
    private var shownResults = -1
    private var lastClockSec = -1L
    private var lastGearState: Int? = null
    private val cal: java.util.Calendar = java.util.Calendar.getInstance()

    /** Tek bir küçük kadranın canlı görünümleri ([kind]'a göre değer + birim, G noktası ya da saat ibreleri). */
    private inner class Slot(val index: Int, val kind: SportDial) {
        var dial: SportGeometry.Dial? = null
        val value: TextView? = if (kind == SportDial.G || kind == SportDial.CLOCK) null
            else sahneText(activity, 46f, SportInk.INK_2, 300, -0.02f, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        val unit: TextView? = if (kind == SportDial.G || kind == SportDial.CLOCK) null
            else sahneText(activity, 15f, SportInk.INK_3, 500, 0.02f, dayBump = false).apply {
                gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 1
                text = SportGeometry.unit(kind)
            }
        val gDot: View? = if (kind != SportDial.G) null else View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(SportInk.RED_HOT) }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val gValue: TextView? = if (kind != SportDial.G) null
            else sahneText(activity, 28f, SportInk.INK_2, 300, dayBump = false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        val hour: SportClockHand? = if (kind == SportDial.CLOCK) SportClockHand(activity, SportClockHand.Kind.HOUR) else null
        val minute: SportClockHand? = if (kind == SportDial.CLOCK) SportClockHand(activity, SportClockHand.Kind.MINUTE) else null
        val second: SportClockHand? = if (kind == SportDial.CLOCK) SportClockHand(activity, SportClockHand.Kind.SECOND) else null
        /** Düzenlemede: kadranın çevresinde ince halka ve uzun basma alanı. */
        val mark = SportEditRing(activity).apply { visibility = GONE }
        val touch = View(activity).apply {
            visibility = GONE
            contentDescription = activity.getString(R.string.launcher_sport_dial_change, SportGeometry.pickerName(kind))
            isLongClickable = true
            setOnLongClickListener { if (editing) openPicker(index); true }
            // Kısa dokunuş da bir şey yapmasın ama alttaki araç/medya dokunuşu almasın.
            setOnClickListener { }
        }
        val liveViews: List<View> = listOfNotNull(value, unit, gDot, gValue, hour, minute, second)
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(media)
        addView(car)
        addView(carLight)
        addView(gear)
        addView(peak)
        addView(needle)
        buildSlots()
        // Yazı tipleri ana iş parçacığında bir kez yüklenir (arka plan çizimi önbellekten alır).
        listOf(300, 400, 500, 600).forEach { SahneFonts.get(activity, it) }
    }

    /** Küçük kadran görünümlerini seçime göre kurar (yalnız seçim değişince). Değerler ibrelerin altında, düzenleme üstte. */
    private fun buildSlots() {
        slots.forEach { s ->
            (s.liveViews + s.mark + s.touch).forEach { v ->
                v.animate().cancel()
                removeView(v)
                lastText.remove(v)
            }
        }
        slots.clear()
        dials.forEachIndexed { i, k -> slots += Slot(i, k) }
        val at = indexOfChild(gear)
        slots.flatMap { it.liveViews }.forEachIndexed { j, v -> addView(v, at + j) }
        slots.forEach { s -> addView(s.mark); addView(s.touch) }
        slots.forEach { s ->
            val on = if (editing) VISIBLE else GONE
            s.mark.visibility = on
            s.touch.visibility = on
        }
        shownResults = -1
        lastClockSec = -1L
        geometry?.let { g -> slots.forEach { it.dial = g.subs[it.index] } }
    }

    // --- Yaşam döngüsü ---

    /** Spor ekranı görünür oldu: veri, sensör ve medya başlar. */
    fun onVisible() {
        if (visibleNow) return
        visibleNow = true
        lastGearState = null
        media.onVisible()
        TripSource.add(activity, tripListener)
        registerSensor()
        main.removeCallbacks(tick)
        main.post(tick)
        if (LauncherDemo.enabled && LauncherDemo.sportSummary) {
            LauncherDemo.sportSummary = false
            main.postDelayed({ if (visibleNow) SportSummaryCard.show(activity, SportSummaryCard.current(activity)) }, DEMO_SUMMARY_MS)
        }
    }

    fun onHidden() {
        if (!visibleNow) return
        visibleNow = false
        media.onHidden()
        TripSource.remove(tripListener)
        sensors?.unregisterListener(sensorListener)
        main.removeCallbacks(tick)
        main.removeCallbacks(gPost)
        main.removeCallbacks(unflash)
        // Bekleyen G güncellemesi iptal edildi: bayrak da sıfırlanır, yoksa geri gelince nokta donar.
        gPending = false
        picker?.dismiss()
    }

    /** Spor kapandı: büyük bitmap bırakılır (4–6 MB), yeniden açılınca arka planda yeniden çizilir. */
    fun release() {
        onHidden()
        needleEnd = null
        needleAnim.cancel()
        glows.forEach { it.animate().cancel() }
        art = null
        artGeometry = null
        released = true
        readyWaiters.clear()
        SportGroundImage.release()
        invalidate()
    }

    fun setCar(parts: Set<CarPart>, windows: Collection<BodyPart>, sunroof: Boolean, animate: Boolean) {
        car.setOpen(parts, animate)
        car.setGlass(windows, sunroof, animate)
    }

    // --- Düzenleme ve seçim ---

    /** Düzenleme modu (spor açıkken): küçük kadranlar halkayla işaretlenir, uzun basınca seçici açılır. */
    fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        val v = if (on) VISIBLE else GONE
        slots.forEach { it.mark.visibility = v; it.touch.visibility = v }
        if (!on) picker?.dismiss()
    }

    private fun openPicker(slot: Int) {
        if (!editing || picker != null) return
        val d = geometry?.subs?.getOrNull(slot) ?: return
        picker = SportDialPicker.show(activity, this, d, dials, SportDials.offered(hasG), onPick = { chosen ->
            applyDials(SportDials.choose(dials, slot, chosen))
        }, onDismiss = { picker = null })
    }

    /** Yeni seçim: kaydedilir, görünümler kurulur, durağan çizim arka planda yenilenir (eski çizim o ana dek kalır). */
    private fun applyDials(next: List<SportDial>) {
        if (next == dials) return
        val gBefore = SportDial.G in dials
        dials = next
        SportDialPrefs.save(activity, next)
        buildSlots()
        // Yeni değerler yeni çizimle birlikte belirir (eski etiketlerin üstünde görünmesin).
        slots.forEach { s -> s.liveViews.forEach { it.alpha = 0f } }
        waitingArt = true
        if (visibleNow && gBefore != (SportDial.G in dials)) {
            sensors?.unregisterListener(sensorListener)
            registerSensor()
        }
        requestLayout()
        refresh()
    }

    /** Seçim değişti ve yeni durağan çizim bekleniyor: hazır olunca küçük kadran değerleri belirir. */
    private var waitingArt = false

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
        val now = SystemClock.elapsedRealtime()
        val rpm: Int?
        val gearText: String
        val instant: Double?
        val best: Long?
        val maxKmh: Int?
        val peakRpm: Int?
        val pass: Long?
        val last: Long?
        val lastAt: Long
        val lastBest: Boolean
        val results: Int
        val ready: Boolean
        val range: Int?
        val s = SportSessionStore.session
        if (demo) {
            rpm = SportDemo.RPM
            gearText = SportMath.gearText(SportDemo.GEAR_STATE, SportDemo.GEAR_NUMBER)
            instant = SportDemo.INSTANT_L100
            best = SportDemo.ZERO100_MS
            maxKmh = SportDemo.MAX_KMH
            peakRpm = SportDemo.PEAK_RPM
            pass = SportDemo.PASS80_MS
            last = null
            lastAt = 0L
            lastBest = false
            results = 0
            ready = trip.speedKmh == 0
            range = if (needsRange()) LauncherDemo.vehicle().rangeKm else null
        } else {
            val snap = metrics.currentSnapshot()
            val raw = metrics.getCombined(ReadOnlyID.ID_ENGINE_RPM)
            rpm = if (snap.connected && VehicleMetricsSnapshot.isValid(raw)) SportMath.rpm(raw) else null
            val gs = snap.gearSignal.takeIf { snap.connected && VehicleMetricsSnapshot.isValid(it) }
            gearText = SportMath.gearText(gs)
            watchPark(gs)
            // Anlık tüketim yalnızca hareket halinde geçerli gelir (dururken 0xFFFF).
            instant = trip.instantL100
            best = s.best0to100Ms
            maxKmh = s.maxSpeedKmh
            peakRpm = s.peakRpm
            pass = s.best80to120Ms
            last = s.last0to100Ms
            lastAt = s.lastResultAt
            lastBest = s.lastWasBest
            results = s.results
            ready = s.stopped && !s.timing
            range = snap.rangeKm.takeIf { snap.connected && VehicleMetricsSnapshot.isValid(it) && it >= 0 }
        }
        setText(gear, gearText)
        for (i in slots.indices) {
            val slot = slots[i]
            val v = slot.value ?: if (slot.kind == SportDial.CLOCK) { updateClock(slot); continue } else continue
            when (slot.kind) {
                SportDial.ZERO100 -> {
                    val recent = last != null && lastAt > 0L && now - lastAt < RESULT_SHOW_MS
                    setText(v, SportFormat.seconds(if (recent) last else best) ?: DASH)
                    val status = when {
                        recent && lastBest -> STATUS_BEST
                        ready -> STATUS_READY
                        else -> SportGeometry.unit(SportDial.ZERO100)
                    }
                    slot.unit?.let { u ->
                        if (lastText[u] != status) u.setTextColor(if (status === STATUS_BEST || status === STATUS_READY) SportInk.RED_HOT else SportInk.INK_3)
                        setText(u, status)
                    }
                    if (results != shownResults) {
                        if (shownResults >= 0 && results > shownResults) flash(v)
                        shownResults = results
                    }
                }
                SportDial.INSTANT -> setText(v, SportFormat.l100(instant) ?: DASH)
                SportDial.MAX -> setText(v, maxKmh?.toString() ?: DASH)
                SportDial.COOLANT -> setText(v, trip.coolantC?.toString() ?: DASH)
                SportDial.AVG_SPEED -> setText(v, trip.avgSpeedKmh?.toString() ?: DASH)
                SportDial.TRIP_TIME -> {
                    val min = TripTotals.drivingMinutes(trip.drivingRunMin, trip.drivingResetMin, trip.drivingAltMin, trip.engineOnMin)
                    setText(v, SportFormat.duration(min) ?: DASH)
                    slot.unit?.let { setText(it, SportFormat.durationUnit(min)) }
                }
                SportDial.RANGE -> setText(v, range?.toString() ?: DASH)
                SportDial.PASS80 -> setText(v, SportFormat.seconds(pass) ?: DASH)
                SportDial.G, SportDial.CLOCK -> Unit
            }
        }
        bindPeak(peakRpm)
        if (!sweeping) moveNeedle((rpm ?: 0).toFloat(), NEEDLE_MS)
    }

    private fun needsRange(): Boolean = slots.any { it.kind == SportDial.RANGE }

    /** Spor açıkken vites P'ye alındı (sürüşten sonra): sürüş özeti kısa süre gösterilir. */
    private fun watchPark(gs: Int?) {
        val before = lastGearState
        if (gs != null) lastGearState = gs
        if (before != null && before != PARK && gs == PARK && SportSessionStore.session.maxSpeedKmh != null)
            SportSummaryCard.show(activity, SportSummaryCard.current(activity))
    }

    /** Analog saat: ibreler saniyede bir döner (yalnız görünüm dönmesi). */
    private fun updateClock(slot: Slot) {
        val wall = System.currentTimeMillis()
        val sec = wall / 1000L
        if (sec == lastClockSec) return
        if (lastClockSec < 0 || sec % 60L == 0L) cal.timeZone = java.util.TimeZone.getDefault()
        lastClockSec = sec
        cal.timeInMillis = wall
        val h = cal.get(java.util.Calendar.HOUR)
        val m = cal.get(java.util.Calendar.MINUTE)
        val s = cal.get(java.util.Calendar.SECOND)
        slot.hour?.rotation = (h + m / 60f) * 30f
        slot.minute?.rotation = (m + s / 60f) * 6f
        slot.second?.rotation = s * 6f
    }

    private fun bindPeak(rpm: Int?) {
        val q = ((rpm ?: 0) / PEAK_STEP) * PEAK_STEP
        if (q == shownPeak) return
        shownPeak = q
        peak.visibility = if (q > 0) VISIBLE else INVISIBLE
        peak.rotation = SportMath.needleDeg(q.toFloat())
    }

    /** Yeni 0–100 sonucu: değer kısa süre kırmızı-turuncu parlar ve hafifçe büyüyüp yerine oturur. */
    private fun flash(v: TextView) {
        main.removeCallbacks(unflash)
        flashing = v
        v.setTextColor(SportInk.RED_HOT)
        v.animate().cancel()
        v.animate().scaleX(FLASH_SCALE).scaleY(FLASH_SCALE).setDuration(FLASH_UP_MS).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(FLASH_DOWN_MS).start()
        }.start()
        main.postDelayed(unflash, FLASH_MS)
    }

    private var flashing: TextView? = null
    private val unflash = Runnable {
        flashing?.let { it.setTextColor(SportInk.INK_2); it.scaleX = 1f; it.scaleY = 1f }
        flashing = null
    }

    private fun setText(v: TextView, s: String) {
        if (lastText[v] == s) return
        lastText[v] = s
        v.text = s
    }

    private fun moveNeedle(rpm: Float, ms: Long, delay: Long = 0, interp: android.animation.TimeInterpolator = DECEL, end: (() -> Unit)? = null) {
        if (abs(rpm - shownRpm) < 10f && end == null && !needleAnim.isStarted) return
        // Süren hareketin bitiş işi düşer (yerine yenisi geldi).
        needleEnd = null
        needleAnim.cancel()
        needleCancelled = false
        needleFrom = shownRpm
        needleTo = rpm
        needleEnd = end
        needleAnim.duration = ms
        needleAnim.startDelay = delay
        needleAnim.interpolator = interp
        needleAnim.start()
    }

    private fun snapNeedle(rpm: Float) {
        needleEnd = null
        needleAnim.cancel()
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
        if (!hasG || SportDial.G !in dials) return
        val s = linearSensor ?: accelSensor ?: return
        gFilter.reset()
        sensors?.registerListener(sensorListener, s, GFilter.SAMPLING_US, main)
    }

    private fun bindG() {
        val slot = slots.firstOrNull { it.kind == SportDial.G } ?: return
        val d = slot.dial ?: return
        val dot = slot.gDot ?: return
        val range = d.r * SportGeometry.G_RANGE
        // Tam piksele yuvarlanır: sabit duruşta süzgecin küçük kıpırtısı yeni kare çizdirmez.
        val tx = Math.round(GFilter.clampUnit(gFilter.lateral) * range).toFloat()
        val ty = Math.round(-GFilter.clampUnit(gFilter.longitudinal) * range).toFloat()
        if (dot.translationX != tx) dot.translationX = tx
        if (dot.translationY != ty) dot.translationY = ty
        slot.gValue?.let { setText(it, SportFormat.g(gFilter.magnitude)) }
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
     * Toplam [SportTransition.SWEEP_TOTAL_MS] (karartma kalktıktan sonra). Yalnızca donanım katmanında alfa/dönme.
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
                v.animate().alpha(1f).setStartDelay(SportTransition.GLOW_DELAY_MS + i * SportTransition.GLOW_STAGGER_MS)
                    .setDuration(SportTransition.GLOW_UP_MS).withEndAction {
                    v.animate().alpha(0f).setStartDelay(0).setDuration(SportTransition.GLOW_DOWN_MS).withEndAction { v.visibility = View.GONE }.start()
                }.start()
            }
        }
        // Gerçek spor araç ibresi gibi ağır: yavaş çıkış, tepede kısa bekleme, gerçek devire yumuşak iniş.
        // İniş bitene dek canlı devir ibreyi sürmez (yoklama inişi kesmesin); sonra canlı değere bağlanır.
        moveNeedle(SportMath.RPM_MAX.toFloat(), SportTransition.SWEEP_UP_MS, delay = SportTransition.SWEEP_DELAY_MS, interp = ACCEL_DECEL) {
            moveNeedle(targetRpm(), SportTransition.SWEEP_DOWN_MS, delay = SportTransition.SWEEP_HOLD_MS, interp = DECEL_SOFT) {
                sweeping = false
                refresh()
            }
        }
        live.forEach { v ->
            v.animate().alpha(1f).setStartDelay(SportTransition.LIVE_DELAY_MS).setDuration(SportTransition.LIVE_FADE_MS)
                .apply { if (v === media) withLayer() }.start()
        }
    }

    /** Çıkış başı: değerler söner, ibre sakince sıfıra iner ([OUTRO_MS]). */
    fun playOutro() {
        sweeping = true
        picker?.dismiss()
        live.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setStartDelay(0).setDuration(OUTRO_FADE_MS).apply { if (v === media) withLayer() }.start()
        }
        moveNeedle(0f, OUTRO_MS, interp = ACCEL_DECEL) {}
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

    /**
     * Bitmap hazır olunca bir kez çağrılır (hazırsa hemen). Çizim [ART_TRIES] denemede de olmazsa (bellek yetmedi)
     * bekleyenler bırakılır ve çağrılmaz: spor ekranı boş kadranlarla açılmaz, Normal kalır.
     */
    fun whenReady(block: () -> Unit) {
        if (art != null) { block(); return }
        if (released) return
        readyWaiters += block
        ensureArt()
    }

    /** Geçişten önce (karar süzgeci sayarken) durağan çizimi arka planda başlatır. */
    fun prepare() {
        ensureArt()
    }

    private val readyWaiters = ArrayList<() -> Unit>()
    private var released = false
    private var artFailures = 0

    private fun ensureArt() {
        val g = geometry ?: return
        if (art != null && g.sameAs(artGeometry)) return
        if (rendering || released || artFailures >= ART_TRIES) return
        rendering = true
        val ctx = activity.applicationContext
        io.execute {
            // Kök zemin görseli de burada (arka planda) bir kez çözülür; geçişte ana iş parçacığı beklemez.
            SportGroundImage.load(ctx)
            val bmp = try { SportArt.render(ctx, g) } catch (t: Throwable) { null }
            main.post {
                rendering = false
                if (released) return@post
                if (bmp == null) {
                    artFailures++
                    if (artFailures < ART_TRIES) main.postDelayed({ ensureArt() }, ART_RETRY_MS)
                    else readyWaiters.clear()
                    return@post
                }
                // Bu arada boyut ya da seçim değiştiyse yeniden çizilir (eski çizim o ana dek ekranda kalır).
                if (!g.sameAs(geometry)) { ensureArt(); return@post }
                artFailures = 0
                art = bmp
                artGeometry = g
                invalidate()
                if (waitingArt) {
                    waitingArt = false
                    if (!sweeping) slots.forEach { s -> s.liveViews.forEach { it.animate().alpha(1f).setStartDelay(0).setDuration(SLOT_FADE_MS).start() } }
                }
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
        val g = geometry?.takeIf { it.w == w && it.h == h && it.dials == dials } ?: SportGeometry(w, h, dials).also {
            geometry = it
            slots.forEach { s -> s.dial = it.subs[s.index] }
            if (glows.isNotEmpty()) ensureGlows(it)
            ensureArt()
        }
        val m = g.media
        val mr = SportGeometry.mediaContentRadius(m.r, activity.dpF(MEDIA_NATURAL_R_DP))
        media.measure(exactly((mr * 2).toInt()), exactly((mr * 2).toInt()))
        media.scaleX = m.r / mr
        media.scaleY = m.r / mr
        val carLen = g.carLength().toInt()
        car.measure(exactly(CarStageView.widthFor(carLen)), exactly(CarStageView.heightFor(carLen)))
        val nr = (g.rpmFace * NeedleView.LENGTH * 2).toInt() + 8
        needle.measure(exactly(nr), exactly(nr))
        val pr = (g.rpmFace * 2).toInt()
        peak.measure(exactly(pr), exactly(pr))
        val win = g.gearWindow()
        gear.setTextSize(TypedValue.COMPLEX_UNIT_PX, win.height() * 0.72f)
        gear.measure(exactly(win.width().toInt()), exactly(win.height().toInt()))
        val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        slots.forEach { s ->
            val d = s.dial ?: return@forEach
            s.value?.let { v ->
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.r * 0.46f)
                v.measure(exactly((d.r * 1.6f).toInt()), wrap)
            }
            s.unit?.let { u ->
                u.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.r * 0.16f)
                u.measure(exactly((d.r * 1.3f).toInt()), wrap)
            }
            s.gDot?.let { val ds = (d.r * 0.15f).toInt(); it.measure(exactly(ds), exactly(ds)) }
            s.gValue?.let { v ->
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX, d.r * SportGeometry.G_VALUE)
                v.measure(exactly((d.r * 1.0f).toInt()), wrap)
            }
            val rf = g.subFace(d)
            s.hour?.measure(exactly((rf * 2).toInt()), exactly((rf * 2).toInt()))
            s.minute?.measure(exactly((rf * 2).toInt()), exactly((rf * 2).toInt()))
            val sr = (rf * SportGeometry.CLOCK_SEC_R * 2).toInt()
            s.second?.measure(exactly(sr), exactly(sr))
            val ts = (d.r * 2).toInt()
            s.touch.measure(exactly(ts), exactly(ts))
            val ms = (d.r * 2 + SportEditRing.padPx(activity) * 2).toInt()
            s.mark.measure(exactly(ms), exactly(ms))
        }
        val lr = (g.car.r * 2).toInt()
        carLight.measure(exactly(lr), exactly(lr))
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
        media.pivotX = media.measuredWidth / 2f
        media.pivotY = media.measuredHeight / 2f
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
        centerAt(peak, g.rpm.cx, g.rpm.cy)
        peak.pivotX = peak.measuredWidth / 2f
        peak.pivotY = peak.measuredHeight / 2f
        val win = g.gearWindow()
        centerAt(gear, win.centerX(), win.centerY())
        slots.forEach { s ->
            val d = s.dial ?: return@forEach
            s.value?.let { centerAt(it, d.cx, d.cy + d.r * 0.02f) }
            s.unit?.let { centerAt(it, d.cx, d.cy + d.r * 0.42f) }
            s.gDot?.let { centerAt(it, d.cx, d.cy) }
            s.gValue?.let { centerAt(it, d.cx, d.cy + d.r * SportGeometry.G_VALUE_Y) }
            listOfNotNull(s.hour, s.minute).forEach { h ->
                centerAt(h, d.cx, d.cy)
                h.pivotX = h.measuredWidth / 2f
                h.pivotY = h.measuredHeight / 2f
            }
            s.second?.let { h ->
                centerAt(h, d.cx, d.cy + g.subFace(d) * SportGeometry.CLOCK_SEC_Y)
                h.pivotX = h.measuredWidth / 2f
                h.pivotY = h.measuredHeight / 2f
            }
            centerAt(s.touch, d.cx, d.cy)
            centerAt(s.mark, d.cx, d.cy)
        }
        centerAt(carLight, g.car.cx, g.car.cy)
        glows.forEach { centerAt(it, it.dial.cx, it.dial.cy) }
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(maxOf(0, px), MeasureSpec.EXACTLY)

    companion object {
        private const val DASH = "—"
        private const val STATUS_READY = "Hazır"
        private const val STATUS_BEST = "En iyi"
        private const val PARK = 1
        const val TICK_MS = 100L
        /** Medya kadranı içeriğinin tasarım yarıçapı (Tiggo 7'deki kadran). */
        const val MEDIA_NATURAL_R_DP = 210f
        const val NEEDLE_MS = 110L
        const val G_UI_MS = 100L
        /** Yeni 0–100 sonucu bu süre (en iyi olmasa da) kadranda kalır; sonra sürüşün en iyisi. */
        const val RESULT_SHOW_MS = 10_000L
        const val FLASH_MS = 1_400L
        const val FLASH_UP_MS = 160L
        const val FLASH_DOWN_MS = 420L
        const val FLASH_SCALE = 1.12f
        /** Tepe işareti 50 dev/dk adımla güncellenir (küçük kıpırtı yeniden çizdirmez). */
        const val PEAK_STEP = 50
        const val SLOT_FADE_MS = 180L
        const val DEMO_SUMMARY_MS = 2_500L

        // Giriş zamanlaması (karartma kalktıktan sonra): çerçeve parıltısı, ibre süpürmesi, değerler.
        const val OUTRO_FADE_MS = 300L
        const val OUTRO_MS = 480L

        /** Durağan çizim deneme sayısı ve aralığı (bellek yetmezse). */
        const val ART_TRIES = 3
        const val ART_RETRY_MS = 1_000L
        /** Araç gövdesi parlaklık çarpanı (üstten ışık). */
        const val CAR_LIFT = 1.3f

        private val DECEL = DecelerateInterpolator()
        private val DECEL_SOFT = DecelerateInterpolator(1.5f)
        private val ACCEL_DECEL = AccelerateDecelerateInterpolator()

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
    private val source = sahneText(activity, 17f, SportInk.INK_2, 500, dayBump = false).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val title = sahneText(activity, TITLE_SP, SportInk.INK, 600, dayBump = false).apply {
        gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
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
        bindSource(src)
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

    private var sourcePkg: String? = null
    private var sourceIcon: android.graphics.drawable.Drawable? = null

    /** Kaynak: küçük uygulama ikonu + adı (ikon arka planda çözülür, yazının hemen solunda ImageSpan olarak). */
    private fun bindSource(pkg: String?) {
        if (pkg != sourcePkg) {
            sourcePkg = pkg
            sourceIcon = null
            if (pkg != null) SportStageView.io.execute {
                val d = try { com.mapcontrol.util.AppIconHelper.load(activity, pkg) } catch (e: Exception) { null }
                handler.post {
                    if (sourcePkg != pkg || d == null) return@post
                    sourceIcon = d
                    showSource()
                }
            }
        }
        showSource()
    }

    private fun showSource() {
        val pkg = sourcePkg
        if (pkg == null) { source.text = ""; return }
        val label = appLabel(pkg)
        val icon = sourceIcon
        if (icon == null) { source.text = label; return }
        val size = (source.textSize * 1.05f).toInt()
        icon.setBounds(0, 0, size, size)
        source.text = android.text.SpannableStringBuilder("\u00A0\u00A0").append(label).apply {
            setSpan(android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
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
        cover.measure(ex(r * COVER), ex(r * COVER))
        note.measure(ex(r * 0.18f), ex(r * 0.18f))
        source.measure(ex(r * 1.1f), wrap)
        val tw = (r * 1.34f).toInt()
        if (tw != titleWidth) { titleWidth = tw; fitTitle() }
        title.measure(ex(r * 1.34f), MeasureSpec.makeMeasureSpec((r * 0.34f).toInt(), MeasureSpec.AT_MOST))
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
        at(cover, r, r - r * 0.50f)
        at(note, r, r - r * 0.50f)
        at(source, r, r - r * 0.17f)
        at(title, r, r + r * 0.03f)
        at(artist, r, r + r * 0.235f)
        // Düğmeler biraz yukarıda; yan düğmeler ilerleme halkasına değmez: dış kenarları halkanın iç kenarından en az
        // [RING_CLEAR_DP] içeride kalana dek önce aradaki boşluk daralır, gerekirse sıra yukarı çıkar.
        val ringInner = r * SportGeometry.PROGRESS_R - r * SportGeometry.PROGRESS_W * 1.15f / 2f - activity.dpF(RING_CLEAR_DP)
        val sr = prev.measuredWidth / 2f
        var dy = r * BUTTONS_Y
        var gap = activity.dpF(10f)
        fun reach() = Math.hypot((play.measuredWidth / 2f + gap + sr).toDouble(), dy.toDouble()).toFloat() + sr
        while (reach() > ringInner && gap > activity.dpF(2f)) gap -= 1f
        while (reach() > ringInner && dy > r * 0.36f) dy -= 1f
        val by = r + dy
        at(play, r, by)
        at(prev, r - play.measuredWidth / 2f - gap - sr, by)
        at(next, r + play.measuredWidth / 2f + gap + sr, by)
    }

    companion object {
        const val TITLE_SP = 26f
        const val TITLE_MIN_SP = 18f
        /** Kapak çapı ve düğme sırasının yüksekliği (dış yarıçapa oran); düğmelerle halka arası en az boşluk. */
        const val COVER = 0.56f
        const val BUTTONS_Y = 0.47f
        const val RING_CLEAR_DP = 8f
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
        paint.strokeWidth = r * SportGeometry.PROGRESS_W * 1.15f
        rect.set(r - rr, r - rr, r + rr, r + rr)
        canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
    }
}
