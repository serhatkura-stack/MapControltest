package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
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
 * Sahne'nin ortası (yuva değil, sabit): ışıklı zeminde araç görseli; sol üstte menzil ve yakıt
 * çubuğu, sağ üstte kilit hapı (dokununca kilit kutucuğuyla aynı yol). Araç sol bilgi sütununun sağında, sığdığı kadar
 * büyük ([StageFit]); sütunun altında araç sağlığı ve hava kalitesi yalnızca bir şey olunca görünür ([StageCorners]).
 * Açık kapı / bagaj / cam / kaput, lastik, yakıt kapağı, düşük yakıt ve yaklaşan bakım aracın altındaki bildirim şeridinde
 * küçük kapsüllerle görünür ([StageNotices], [NoticeRow]); araç kartının gösterdiği hiçbir bilgi kaybolmaz.
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
    /** Araç ve açılan parçaları (kapı/bagaj/kaput animasyonu); görünüm gövdenin dışına taşan açık parçaları da kapsar. */
    private val car = CarStageView(activity).apply {
        contentDescription = activity.getString(R.string.launcher_sahne_car)
        // Kapı hareketi sürerken her kare yeniden çizilir; arkadaki statik zemin (8 tam alan gradyan) bu sürede donanım
        // katmanından tek doku olarak basılır, hareket bitince katman bırakılır.
        onAnimatingChanged = { on -> floor.setLayerType(if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null) }
        // Dokununca araç detayı (lastik basınçları, kapı/cam durumu, bakım, sağlık, hava). Uzun basma yok; düzenleme
        // modunda sahne dokunuşu zaten keser ([locked]).
        isClickable = true
        isLongClickable = false
        setOnClickListener { openDetail() }
        pressFeedback()
    }
    /** Açık araç detayı sayfası (yoksa null); yeni veriyle yerinde tazelenir. */
    private var detail: CarDetailSheet? = null
    /** İlk özet animasyonsuz uygulanır (ekran açıldığında kapı zaten açıksa açık çizilir). */
    private var carSeeded = false
    private var carW = 0
    /** Aracın gövde kutusu (sahne pikseli; açılan parçalar hariç), [StageFit] ile; ölçü değişmedikçe yeniden hesaplanmaz. */
    private var carBox = StageFit.Box(0f, 0f, 0f, 0f)
    private var fitKey: List<Int> = emptyList()

    // --- Menzil ---
    // Sahibinin isteği: taslaktaki 84'ten küçük menzil bloğu (76 / 24 / 18, yakıt çubuğu 110), araç ortada dengelensin.
    private val rangeNumber = sahneText(activity, RANGE_SP, Sahne.NUMERAL, 300, -0.04f)
    private val rangeUnit = sahneText(activity, 24f, Sahne.INK_2, 500)
    private val rangeLabel = sahneText(activity, 20f, Sahne.INK_3, 400).apply { setText(R.string.launcher_range_label) }
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

    // --- Bildirim şeridi (aracın altında): açık kapı/kaput/bagaj/cam/tavan, lastik, yakıt kapağı, düşük yakıt, bakım ---
    private val alertRow = NoticeRow(activity, tappable = { true }, onTap = { onNotice(it) })
    /** Araç parkta mı (vites P ya da el freni): açık bagaj / cam kapsülleri o zaman belirgin. */
    private var parked = false
    /** onVisible ile onHidden arası: park yoklaması yalnız görünürken (ve pencereye bağlıyken) döner. */
    private var visibleNow = false
    /** Bekleyen tek park yoklaması var mı (tek zincir; sık gelen yolculuk güncellemeleri zamanlayıcıyı ertelemez). */
    private var parkPollPending = false
    private val parkPoll = object : Runnable {
        override fun run() {
            parkPollPending = false
            val now = readParked()
            if (now != parked) {
                parked = now
                bindNotices() // zinciri kendisi sürdürür
                return
            }
            schedulePark()
        }
    }

    // --- Notlar ---
    private val healthNote = Note(activity, R.drawable.ic_sahne_shield, end = false)
    private val airNote = Note(activity, R.drawable.ic_sahne_wind, end = false)
    private var openings: Openings? = null
    private var lastHealth: StripState? = null
    /** Köşe notlarının kararı için yolculuk verisi (bakım km, akü, motor suyu, PM2.5); ortak okuyucudan, ek yoklama yok. */
    private var trip = TripData()
    private val tripListener = TripSource.Listener {
        if (it == trip) return@Listener
        trip = it
        bindHealth(lastHealth)
        bindAir()
        bindNotices()
        refreshDetail()
    }

    // --- Veri yok ---
    /** Araç verisi gelmeden: menzil yerine sabit iskelet çubuklar ve aracın altında tek mesaj (köşe değerleri gizli). */
    private val skeleton = Skeleton(activity)
    private val waitNote = sahneText(activity, 22f, Sahne.INK_3, 400).apply {
        setText(R.string.launcher_stage_waiting)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private var waiting = false

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
        addView(skeleton)
        addView(waitNote)
        vehicle.onSummary = { bindVehicle(it) }
        lock.onStripChanged = { bindLock(lock.stripState()) }
        health.onStripChanged = { bindHealth(health.stripState()) }
        air.onStripChanged = { bindAir() }
        bindVehicle(vehicle.summary)
        bindLock(lock.stripState())
        bindHealth(health.stripState())
        bindAir()
        SahneFonts.apply(rangeNumber, 300, -0.04f)
        rangeNumber.includeFontPadding = false
    }

    override val view: View get() = this

    override fun onVisible() {
        sources.forEach { it.onVisible() }
        TripSource.add(activity, tripListener)
        visibleNow = true
        parked = readParked()
        bindNotices()
    }

    /** onVisible pencereye bağlanmadan gelmiş olabilir: bağlanınca park yoklaması (gerekiyorsa) başlar. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibleNow) {
            parked = readParked()
            bindNotices()
        }
    }

    override fun onDetachedFromWindow() {
        stopPark()
        super.onDetachedFromWindow()
    }

    override fun onHidden() {
        sources.forEach { it.onHidden() }
        TripSource.remove(tripListener)
        visibleNow = false
        stopPark()
    }

    override fun release() {
        detail?.dialog?.dismiss()
        detail = null
        vehicle.onSummary = null
        TripSource.remove(tripListener)
        visibleNow = false
        stopPark()
        sources.forEach { it.release() }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = locked

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = locked

    private fun bindVehicle(s: VehicleHeroCard.Summary?) {
        val range = s?.rangeKm
        rangeNumber.setTextSmooth(range?.toString() ?: activity.getString(R.string.launcher_unavailable), animate = false)
        rangeNumber.setTextColor(if (range != null) Sahne.NUMERAL else Sahne.INK_3)
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
        val o = s?.openings
        car.setOpen(o?.carParts() ?: s?.openParts.orEmpty(), animate = carSeeded)
        car.setGlass(o?.windows.orEmpty(), o?.sunroof == true, animate = carSeeded)
        if (s != null) carSeeded = true
        floor.alert = o?.any == true
        openings = o
        waiting = s == null || (s.rangeKm == null && s.fuel == null && s.locked == null && o == null)
        rangeBlock.visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        skeleton.visibility = if (waiting) View.VISIBLE else View.GONE
        waitNote.visibility = if (waiting) View.VISIBLE else View.GONE
        bindAir()
        bindHealth(lastHealth)
        bindNotices()
        refreshDetail()
        bindLock(lock.stripState())
        requestLayout()
    }

    /**
     * Sağlık notu; veri yokken gizli. Not normalde gizli: yalnızca bakım yakın, akü / motor suyu uyarısı ya da lastik
     * uyarısı varken çıkar ([StageCorners.healthShown]). Açık parçalar aracın altındaki bildirim şeridinde adlandırılır.
     */
    private fun bindHealth(st: StripState?) {
        lastHealth = st
        val s = vehicle.summary
        val notes = StageCorners.healthNotes(trip.batteryV, trip.coolantC, trip.serviceKm, trip.serviceDays, s?.tireAlert == true)
        healthNote.visibility = if (!waiting && notes.isNotEmpty()) View.VISIBLE else View.GONE
        if (notes.isNotEmpty()) healthNote.bind(healthState(st, notes, s?.tireLow))
    }

    /**
     * Sağlık notunun içeriği: başlık sağlık kutucuğununki, değer notu açan en önemli sorun (düşük yakıt hariç; o kendi
     * kapsülünde), alt satır ölçümü ve varsa "+N" diğer sorun sayısı.
     */
    private fun healthState(st: StripState?, notes: List<StageCorners.HealthNote>, tireLow: TireLow?): StripState {
        val first = notes.first()
        val value = activity.getString(when (first) {
            StageCorners.HealthNote.ENGINE_HOT -> R.string.launcher_health_hot
            StageCorners.HealthNote.BATTERY_LOW -> R.string.launcher_health_battery_low
            StageCorners.HealthNote.BATTERY_HIGH -> R.string.launcher_health_battery_high
            StageCorners.HealthNote.TIRE -> R.string.launcher_tires_warning_short
            StageCorners.HealthNote.SERVICE_OVERDUE -> R.string.launcher_health_service_overdue
            StageCorners.HealthNote.SERVICE_NEAR -> R.string.launcher_health_service_soon
        })
        val measure = when (first) {
            StageCorners.HealthNote.ENGINE_HOT -> trip.coolantC?.let { activity.getString(R.string.launcher_temp_c, it) }
            StageCorners.HealthNote.BATTERY_LOW, StageCorners.HealthNote.BATTERY_HIGH -> trip.batteryV?.let {
                activity.getString(R.string.launcher_health_volt_only, String.format(java.util.Locale.getDefault(), "%,.1f", it))
            }
            StageCorners.HealthNote.TIRE -> tireLow?.let { l ->
                listOfNotNull(l.wheel?.let { activity.getString(TIRE_WHEELS[it]) },
                    l.bar?.let { activity.getString(R.string.launcher_sahne_psi, TireDecode.psi(it)) }).joinToString(" ").ifEmpty { null }
            }
            StageCorners.HealthNote.SERVICE_OVERDUE, StageCorners.HealthNote.SERVICE_NEAR -> serviceText()
        }
        return StripState(
            title = st?.title ?: activity.getString(R.string.launcher_widget_health),
            value = value,
            icon = R.drawable.ic_mdi_alert,
            tone = StripTone.ALERT,
            detail = listOfNotNull(measure, (notes.size - 1).takeIf { it > 0 }?.let { "+$it" }).joinToString(" · ").ifEmpty { null },
        )
    }

    /** Bakıma kalan: "800 km", yalnız gün biliniyorsa "22 gün". */
    private fun serviceText(): String? = trip.serviceKm?.let {
        activity.getString(R.string.launcher_km_value, java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(it))
    } ?: trip.serviceDays?.let { activity.getString(R.string.launcher_days_value, it) }

    /** Bildirim şeridi: kaynaklar ve sıra [StageNotices]; veri beklenirken gizli. */
    private fun bindNotices() {
        val s = vehicle.summary
        val list = if (waiting || s == null) emptyList() else StageNotices.build(
            openings = s.openings, fuelCapOpen = s.fuelCapOpen, tireAlert = s.tireAlert, lowFuel = s.lowFuel,
            serviceNear = StageCorners.serviceNear(trip.serviceKm, trip.serviceDays), parked = parked,
        )
        alertRow.bind(list.map { noticeItem(it, s?.tireLow) })
        // Park durumu yalnızca belirgin tonu etkileyen bir kapsül varken izlenir (vites / el freni okuması arka planda).
        schedulePark()
    }

    /** Gerekiyorsa tek bir gecikmeli yoklama kurar (zaten bekleyen varsa dokunmaz); gerekmiyorsa bekleyeni kaldırır. */
    private fun schedulePark() {
        if (!needsPark()) {
            stopPark()
            return
        }
        if (parkPollPending) return
        parkPollPending = true
        postDelayed(parkPoll, PARK_POLL_MS)
    }

    private fun stopPark() {
        removeCallbacks(parkPoll)
        parkPollPending = false
    }

    private fun needsPark(): Boolean = visibleNow && isAttachedToWindow && vehicle.summary?.openings.let { o ->
        o != null && (o.trunk || o.sunroof || o.windows.isNotEmpty())
    }

    /** Vites P ya da el freni (son okunan değer; okuma arka planda tazelenir). Demo: `launcher_demo_moving true` değilse park. */
    private fun readParked(): Boolean = if (LauncherDemo.enabled) LauncherDemo.moving != true else DriveSignals.parked()

    private fun noticeItem(n: Notice, tireLow: TireLow?): NoticeItem {
        fun open(name: String) = activity.getString(R.string.launcher_part_open, name)
        val warn = if (n.strong) NoticeTone.STRONG else NoticeTone.WARN
        return when (n.kind) {
            NoticeKind.DOOR -> NoticeItem(n, open(activity.getString(R.string.launcher_open_door, activity.getString(n.part!!.label))),
                R.drawable.ic_mdi_car_door, warn)
            NoticeKind.HOOD -> NoticeItem(n, open(activity.getString(R.string.launcher_body_hood)), R.drawable.ic_sahne_car, warn)
            NoticeKind.TRUNK -> NoticeItem(n, open(activity.getString(R.string.launcher_trunk)), R.drawable.ic_sahne_trunk, warn)
            NoticeKind.TIRE -> NoticeItem(n,
                tireLow?.wheel?.let { activity.getString(R.string.launcher_notice_tire_wheel, activity.getString(TIRE_WHEELS[it])) }
                    ?: activity.getString(R.string.launcher_tires_warning),
                R.drawable.ic_mdi_alert, warn)
            NoticeKind.WINDOW -> NoticeItem(n, open(activity.getString(n.part!!.label)), R.drawable.ic_sahne_wind, warn)
            NoticeKind.SUNROOF -> NoticeItem(n, open(activity.getString(R.string.launcher_sunroof_part)), R.drawable.ic_mdi_car_sunroof, warn)
            NoticeKind.FUEL_CAP -> NoticeItem(n, open(activity.getString(R.string.launcher_body_fuel_cap)), R.drawable.ic_sahne_fuel, warn)
            NoticeKind.FUEL_LOW -> NoticeItem(n, activity.getString(R.string.launcher_notice_fuel), R.drawable.ic_sahne_fuel, warn, action = true)
            NoticeKind.SERVICE -> {
                val km = trip.serviceKm
                val text = if (HealthMath.serviceOverdue(km, trip.serviceDays) || km == null) activity.getString(R.string.launcher_health_service_overdue)
                else activity.getString(R.string.launcher_health_service_in, serviceText())
                NoticeItem(n, text, R.drawable.ic_mdi_wrench, NoticeTone.INFO)
            }
        }
    }

    /** Kapsüle dokunuş: düşük yakıtta en yakın istasyon araması (Yandex, yoksa genel harita); diğerlerinde araç detayı. */
    private fun onNotice(n: Notice?) {
        if (n?.kind != NoticeKind.FUEL_LOW) {
            openDetail()
            return
        }
        for (uri in listOf(NearestStation.yandexUri(), NearestStation.geoUri())) {
            try {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // Sıradaki adres denenir.
            } catch (e: SecurityException) {
                // Sıradaki adres denenir.
            }
        }
        android.widget.Toast.makeText(activity, R.string.launcher_notice_no_maps, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Araç detayı: tek sayfa; düzenleme modunda ya da etkinlik kapanırken açılmaz. */
    private fun openDetail() {
        if (locked || detail != null || activity.isFinishing || activity.isDestroyed) return
        val sheet = CarDetailSheet(activity)
        detail = sheet
        refreshDetail()
        sheet.show { detail = null }
    }

    private fun refreshDetail() {
        val sheet = detail ?: return
        val s = vehicle.summary
        val extra = if (LauncherDemo.enabled) ReadOnlyDemo.extra() else ExtraSource.last
        sheet.bind(CarDetail.model(s?.openings, extra, trip, s?.lowFuel), s?.openParts.orEmpty())
    }

    /** Hava notu yalnızca kabin içi PM2.5 kötüyken ([StageCorners.airShown]); o zaman uyarı renginde. */
    private fun bindAir() {
        val shown = !waiting && StageCorners.airShown(trip.pm25In)
        airNote.visibility = if (shown) View.VISIBLE else View.GONE
        airNote.bind(air.stripState()?.let { if (shown) it.copy(tone = StripTone.ALERT) else it })
    }

    /**
     * Kilit hapı: kilit dinleyicisinden; bir kapı açıksa kilitli gösterilmez (açık kapıyla "Kilitli" çelişir). Henüz okunmadıysa
     * nötr "Bekleniyor".
     */
    private fun bindLock(s: StripState?) {
        val known = s != null && s.tone != StripTone.MUTED
        val doorOpen = openings?.anyDoor == true
        val unlocked = doorOpen || s?.icon == R.drawable.ic_mdi_lock_open_variant
        lockText.text = when {
            doorOpen -> activity.getString(R.string.launcher_unlocked)
            known -> s!!.value
            else -> activity.getString(R.string.launcher_lock_waiting)
        }
        val live = known || doorOpen
        lockIcon.setImageResource(if (unlocked) R.drawable.ic_sahne_lock_open else R.drawable.ic_sahne_lock)
        lockIcon.imageTintList = ColorStateList.valueOf(if (!live) Sahne.INK_3 else Sahne.ICE)
        lockText.setTextColor(if (!live) Sahne.INK_3 else Sahne.INK)
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
        floor.measure(exactly(w), exactly(h))
        val loose = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST)
        val looseH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        rangeBlock.measure(loose, looseH)
        lockChip.measure(MeasureSpec.makeMeasureSpec(w / 2, MeasureSpec.AT_MOST), exactly(context.dp(LauncherTheme.TOUCH_MIN_DP)))
        fitCar(w, h)
        carW = carBox.width.toInt()
        car.measure(exactly(CarStageView.widthFor(carW)), exactly(CarStageView.heightFor(carW)))
        floor.setCar(carBox)
        // Notlar aracın solundaki sütunda (menzilin altında): genişlik aracın sol kenarına kadar.
        val side = (SIDE * w / DESIGN_W).toInt()
        val noteW = MeasureSpec.makeMeasureSpec(columnWidth(w), MeasureSpec.AT_MOST)
        healthNote.measure(noteW, looseH)
        airNote.measure(noteW, looseH)
        // Şerit aracın genişliği kadar (biraz taşabilir), sahneden taşmaz; görünen notun sağ kenarına da binmez (şerit
        // aracın ortasına hizalı olduğundan iki yana eşit daralır).
        val noteRight = side + maxOf(
            if (healthNote.visibility == View.VISIBLE) healthNote.measuredWidth else 0,
            if (airNote.visibility == View.VISIBLE) airNote.measuredWidth else 0,
        )
        val carMid = (carBox.left + carBox.right) / 2f
        var rowW = minOf(w - 2 * side, carBox.width.toInt() + context.dp(40))
        if (noteRight > side) rowW = minOf(rowW, (2 * (carMid - noteRight - context.dpF(FIT_GAP_DP))).toInt().coerceAtLeast(0))
        alertRow.measure(MeasureSpec.makeMeasureSpec(rowW, MeasureSpec.AT_MOST), looseH)
        skeleton.measure(exactly(context.dp(Skeleton.W_DP)), exactly(context.dp(Skeleton.H_DP)))
        waitNote.measure(MeasureSpec.makeMeasureSpec((w * 0.8f).toInt(), MeasureSpec.AT_MOST), looseH)
    }

    /**
     * Aracın kutusu: üst engeller menzil satırları (sabit pay: rakam satırı en az [RANGE_ROW_RESERVE_DP], yakıt satırı
     * [FUEL_ROW_RESERVE_DP]; değer değişince araç zıplamasın) ve kilit hapı; alt sınır bildirim şeridi payı ([ROW_BOTTOM_DP] +
     * [CAPSULE_H_DP], kapsül aracın alt kenarına [CAPSULE_OVERLAP_DP] biner). Genişlik [CAR_W] ile [CAR_W_MAX] arası (%20).
     */
    /** Sol bilgi sütununun genişliği: sahne kenarından aracın gövdesine kadar (arada boşluk). */
    private fun columnWidth(w: Int): Int =
        maxOf(context.dp(LEFT_COLUMN_DP), carBox.left.toInt() - (SIDE * w / DESIGN_W).toInt() - context.dp(FIT_GAP_DP.toInt()))

    private fun fitCar(w: Int, h: Int) {
        val k = w / DESIGN_W
        val side = (SIDE * k).toInt()
        // Ölçüm sırasında görünümün genişliği henüz atanmamış olabilir: y() yerine ölçülen genişlikle.
        fun yk(designY: Float) = ((designY - TOP_OFFSET) * k).toInt()
        val rangeTop = yk(RANGE_TOP)
        val row0 = rangeBlock.getChildAt(0)
        val row1 = rangeBlock.getChildAt(1)
        val lp0 = row0.layoutParams as LinearLayout.LayoutParams
        val lp1 = row1.layoutParams as LinearLayout.LayoutParams
        val row0Top = rangeTop + lp0.topMargin
        val row0Bottom = row0Top + row0.measuredHeight
        val row1Top = row0Bottom + lp0.bottomMargin + lp1.topMargin
        val row1Bottom = row1Top + row1.measuredHeight
        val row0Right = side + maxOf(row0.measuredWidth, context.dp(RANGE_ROW_RESERVE_DP))
        val row1Right = side + maxOf(row1.measuredWidth, context.dp(FUEL_ROW_RESERVE_DP))
        val lockTop = yk(LOCK_TOP)
        val chipLeft = w - side - lockChip.measuredWidth
        val key = listOf(w, h, row0Bottom, row1Bottom, row0Right, row1Right, chipLeft, lockTop + lockChip.measuredHeight)
        if (key == fitKey) return
        fitKey = key
        // Araç sol bilgi sütununun ([LEFT_COLUMN_DP]) sağında: sağlık / hava notları ve açıklık satırı aracın üstüne binmez.
        carBox = StageFit.car(
            areaLeft = (side + context.dp(LEFT_COLUMN_DP)).toFloat(), areaRight = (w - side).toFloat(),
            minTop = context.dpF(8f),
            maxBottom = (h - context.dp(ROW_BOTTOM_DP + CAPSULE_H_DP - CAPSULE_OVERLAP_DP)).toFloat(),
            maxW = CAR_W_MAX * k, minW = CAR_W * k, aspect = CAR_ASPECT, gap = context.dpF(FIT_GAP_DP),
            above = listOf(
                StageFit.Box(side.toFloat(), row0Top.toFloat(), row0Right.toFloat(), row0Bottom.toFloat()),
                StageFit.Box(side.toFloat(), row1Top.toFloat(), row1Right.toFloat(), row1Bottom.toFloat()),
                StageFit.Box(chipLeft.toFloat(), lockTop.toFloat(), (w - side).toFloat(), (lockTop + lockChip.measuredHeight).toFloat()),
            ),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val k = s()
        floor.layout(0, 0, w, h)
        val carL = carBox.left.toInt() - CarStageView.carLeft(carW)
        val carT = carBox.top.toInt() - CarStageView.carTop(carW)
        car.layout(carL, carT, carL + car.measuredWidth, carT + car.measuredHeight)
        val side = (SIDE * k).toInt()
        rangeBlock.layout(side, y(RANGE_TOP), side + rangeBlock.measuredWidth, y(RANGE_TOP) + rangeBlock.measuredHeight)
        val lockTop = y(LOCK_TOP)
        lockChip.layout(w - side - lockChip.measuredWidth, lockTop, w - side, lockTop + lockChip.measuredHeight)
        // Bildirimler aracın hemen altında, aracın ortasına hizalı (sahne kenarlarına taşmadan); alt kenarı geçmez.
        val bottom = h - (NOTE_BOTTOM * k).toInt()
        val aTop = minOf(carBox.bottom.toInt() - context.dp(CAPSULE_OVERLAP_DP), h - context.dp(ROW_BOTTOM_DP) - alertRow.measuredHeight)
        val aLeft = ((carBox.left + carBox.right) / 2f - alertRow.measuredWidth / 2f).toInt()
            .coerceIn(side, maxOf(side, w - side - alertRow.measuredWidth))
        alertRow.layout(aLeft, aTop, aLeft + alertRow.measuredWidth, aTop + alertRow.measuredHeight)
        // Sol sütun alttan yukarı: hava notu en altta, üstünde sağlık notu.
        val airShown = airNote.visibility == View.VISIBLE
        airNote.layout(side, bottom - airNote.measuredHeight, side + airNote.measuredWidth, bottom)
        val upper = if (airShown) bottom - airNote.measuredHeight - context.dp(NOTE_GAP_DP) else bottom
        healthNote.layout(side, upper - healthNote.measuredHeight, side + healthNote.measuredWidth, upper)
        skeleton.layout(side, y(RANGE_TOP) + context.dp(8), side + skeleton.measuredWidth, y(RANGE_TOP) + context.dp(8) + skeleton.measuredHeight)
        val wl = (w - waitNote.measuredWidth) / 2
        waitNote.layout(wl, bottom - waitNote.measuredHeight, wl + waitNote.measuredWidth, bottom)
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
        private val title = sahneText(context, 20f, Sahne.INK_3, 400)
        private val value = sahneText(context, 26f, Sahne.INK, 500)
        private val dot = View(context)
        private val sub = sahneText(context, 20f, Sahne.INK_3, 400)

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


    /** Veri gelmeden menzil yerinde iki sabit iskelet çubuk (parıltı döngüsü yok): gece #1A1E25, gündüz #E6E4E0. */
    private class Skeleton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = GONE }

        override fun onDraw(canvas: Canvas) {
            paint.color = if (LauncherTheme.night) 0xFF1A1E25.toInt() else 0xFFE6E4E0.toInt()
            val r = context.dpF(6f)
            rect.set(0f, 0f, context.dpF(200f), context.dpF(44f))
            canvas.drawRoundRect(rect, r, r, paint)
            rect.set(0f, context.dpF(60f), context.dpF(130f), context.dpF(72f))
            canvas.drawRoundRect(rect, r, r, paint)
        }

        companion object {
            const val W_DP = 200
            const val H_DP = 72
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
     * geniş yumuşak gölge ve sıkı temas gölgesi; gece ön farların ve stop lambalarının
     * zemindeki yansıması. Gradyanlar yalnızca boyut değişince kurulur; görünüm statiktir.
     */
    private class FloorView(context: Context) : View(context) {
        var alert = false
        /** Aracın gövde kutusu: zemin ışığı ve gölgeler araçla birlikte taşınır ve büyür. */
        private var carBox: StageFit.Box? = null

        fun setCar(box: StageFit.Box) {
            if (box == carBox) return
            carBox = box
            builtW = -1
            invalidate()
        }

        private val cone = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
        private val poolOuter = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val contact = Paint(Paint.ANTI_ALIAS_FLAG)
        private val head = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tail = Paint(Paint.ANTI_ALIAS_FLAG)
        private var builtW = -1
        private var builtH = -1

        private fun build(w: Int, h: Int) {
            builtW = w
            builtH = h
            // Taslak ölçüleri 510 birimlik aracın (sol 225, üst 146) çevresinde; hepsi aracın gerçek kutusuna oranlanır.
            val box = carBox ?: StageFit.Box(CAR_X_DESIGN * w / DESIGN_W, 0f, (CAR_X_DESIGN + CAR_W) * w / DESIGN_W, CAR_W * CAR_ASPECT * w / DESIGN_W)
            val k = box.width / CAR_W
            fun xx(v: Float) = box.left + (v - CAR_X_DESIGN) * k
            fun yy(v: Float) = box.top + (v - CAR_Y_DESIGN) * k
            val cx = xx(480f)
            cone.shader = SahneGround.ellipse(cx, yy(40f + 216f), 350f * k, 180f * k, Sahne.CONE, 1f)
            pool.shader = SahneGround.ellipse(cx, yy(270f), 400f * k, 150f * k, Sahne.FLOOR_INNER, 0.72f)
            poolOuter.shader = SahneGround.ellipse(cx, yy(270f), 560f * k, 210f * k, Sahne.FLOOR_OUTER, if (LauncherTheme.night) 0.72f else 0.74f)
            shadow.shader = SahneGround.ellipse(cx, yy(292f), 290f * k, 78f * k, Sahne.CAR_SHADOW, 1f)
            contact.shader = SahneGround.ellipse(cx, yy(270f), 250f * k, 58f * k, Sahne.CAR_CONTACT, 1f)
            // Burun sağda: farlar sağda (soğuk beyaz), stoplar solda (kırmızı); yalnızca gece.
            head.shader = if (Sahne.HEAD_GLOW == 0) null else SahneGround.ellipse(xx(760f), yy(268f), 150f * k, 70f * k, Sahne.HEAD_GLOW, 1f)
            tail.shader = if (Sahne.TAIL_GLOW == 0) null else SahneGround.ellipse(xx(212f), yy(268f), 70f * k, 56f * k, Sahne.TAIL_GLOW, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (w != builtW || h != builtH) build(w, h)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cone)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), poolOuter)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), pool)
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
        /** Zemin çiziminin taslaktaki araç kutusu (sol, üst; 510 birim genişlik): ışık ve gölge bunlara göre tanımlı. */
        const val CAR_X_DESIGN = 225f
        const val CAR_Y_DESIGN = 146f
        /** Araç genişliği (tasarım birimi): en az eski boy, sığarsa %20 büyük (4 Kasım kararı). */
        const val CAR_W = 510f
        const val CAR_W_MAX = 612f
        /** Araç kutusu üst engellerden bu kadar uzak durur. */
        const val FIT_GAP_DP = 10f
        /** Aracın solunda ayrılan bilgi sütunu (menzil altı: sağlık / hava notları); araç bunun sağında durur. */
        const val LEFT_COLUMN_DP = 220
        const val NOTE_GAP_DP = 16
        /** Açık bagaj / cam varken park durumunun yeniden okunma aralığı. */
        const val PARK_POLL_MS = 2_000L
        /** Menzil satırları için sabit pay (dp): "540 km menzil" ve yakıt çubuğu + "%100". */
        const val RANGE_ROW_RESERVE_DP = 260
        const val FUEL_ROW_RESERVE_DP = 210
        /** Aracın altındaki bildirim şeridi payı: kapsül yüksekliği, alt boşluk ve araca binme. */
        const val CAPSULE_H_DP = 34
        const val ROW_BOTTOM_DP = 10
        const val CAPSULE_OVERLAP_DP = 12
        const val CAR_ASPECT = CarAnimSpec.BASE_H / CarAnimSpec.BASE_W
        const val SIDE = 30f
        const val RANGE_TOP = 22f
        const val LOCK_TOP = 26f
        const val NOTE_BOTTOM = 14f
        /** 84sp rakamın doğal satırı ile taslaktaki .9 satır yüksekliği arasındaki yarı fark. */
        const val RANGE_TRIM_DP = 18
        const val RANGE_SP = 76f
        val TIRE_WHEELS = listOf(R.string.launcher_tire_fl, R.string.launcher_tire_fr, R.string.launcher_tire_rl, R.string.launcher_tire_rr)
    }
}
