package com.mapcontrol.launcher

/**
 * Panoramik tavanın Sahne aracındaki durumu (6 Kasım fikri, DURUM-5kasim "tavan perdesi"): perde ve ön cam panelin gerçek
 * konumu araba çiziminde görünür (parça kendisi değişir, yazı / rozet yok).
 *
 * [shade] perdenin açıklığı: 0 kapalı (kumaş tavan döşemesi görünür) .. 1 tam açık (cam + hafif gökyüzü yansıması); kısmi
 * değerde öndeki pay açılmıştır (perde öne doğru değil arkaya toplanır). null = perde sinyali yok, taban görseli olduğu gibi.
 * [shadeMs] bu hedefe varış süresi: 0 = kısa yumuşak geçiş; > 0 = perde şu an hareket ediyor, kalan yolun tahmini süresi
 * (çizim doğrusal ve seyrek adımla izler, [CarAnimSpec.ROOF_STEP_MS]).
 * [glass] ön cam panelin açıklığı (0 kapalı .. 1 tam geri kaymış); [tilt] havalandırma (arka kenar kalkık).
 * [edgeAlert] tavan açıkken hızlı gidiliyor ya da yağmur var: açık kenar hafif kehribar yanar.
 */
data class RoofState(
    val shade: Float?,
    val shadeMs: Long = 0L,
    val glass: Float = 0f,
    val tilt: Boolean = false,
    val edgeAlert: Boolean = false,
) {
    /** Cam açık sayılır mı (kenar ışığı ve ice vurgusu için). */
    val glassOpen: Boolean get() = glass > RoofDecode.OPEN_EPS || tilt
}

/**
 * Ham CarSetting değerlerinin çözümü (saf, test edilir). Değerler araçta yalnızca kısmen doğrulandı; doğrulanmayanlar
 * savunmacı çözülür ve VehicleHeroCard her değişimde "Roof" etiketiyle ham değeri bir kez yazar (kalibrasyon için).
 */
object RoofDecode {

    // --- ID_CAR_SUNSHADE (CarSetting 10): araçta ölçüldü (2026-10-19 ve 10-30 probe günlükleri, KATALOG) ---
    const val SHADE_CLOSED = 1
    const val SHADE_OPENING = 2
    /** 3 ve 6: hareket yarıda durdu (10-30'da 2 → 3, 4 → 3; 10-19'da 2 → 6, 4 → 6 görüldü). */
    const val SHADE_STOPPED = 3
    const val SHADE_CLOSING = 4
    const val SHADE_OPEN = 5
    const val SHADE_STOPPED_2 = 6
    /** OEM sesli komutta "desteklenmiyor". */
    const val SHADE_UNSUPPORTED = 7

    // --- ID_CAR_SPF_POSI (CarSetting 6): yalnızca 1 (kapalı) ölçüldü; 12 = havalandırma OEM yazma kodundan ---
    const val POSI_CLOSED = 1
    const val POSI_TILT = 12
    /**
     * TAHMİN (araçta doğrulanacak): 2..11 on adımlı konum ((ham - 1) × %10); 13..100 doğrudan yüzde. Gerçek ölçek "Roof"
     * günlüğünden okunup burası düzeltilir.
     */
    const val POSI_STEP_MAX = 11
    const val POSI_PERCENT_MAX = 100

    /** Tam açılma / kapanma süresi (ms): 10-30 günlüğünden (2 → 5 ≈ 10,8 sn; kapanış yarıdan ≈ 8,7 sn → tam ≈ 13,5 sn). */
    const val SHADE_OPEN_MS = 11_000L
    const val SHADE_CLOSE_MS = 13_500L
    /** Geçmişi bilinmeyen "yarıda durdu" okuması (ör. açılışta ilk değer 6) için varsayılan açıklık. */
    const val SHADE_UNKNOWN_MID = 0.5f

    /** Kenar ışığı: bu hızda yanar, [EDGE_SPEED_OFF_KMH] altına inince söner (eşikte yanıp sönmesin). */
    const val EDGE_SPEED_ON_KMH = 90
    const val EDGE_SPEED_OFF_KMH = 80
    const val OPEN_EPS = 0.02f

    enum class Motion { CLOSED, OPEN, OPENING, CLOSING, STOPPED, PERCENT }

    class Shade(val motion: Motion, val fraction: Float = 0f) {
        override fun equals(other: Any?) = other is Shade && other.motion == motion && other.fraction == fraction
        override fun hashCode() = motion.hashCode() * 31 + fraction.hashCode()
        override fun toString() = "Shade($motion, $fraction)"
    }

    class Glass(val fraction: Float, val tilt: Boolean = false) {
        override fun equals(other: Any?) = other is Glass && other.fraction == fraction && other.tilt == tilt
        override fun hashCode() = fraction.hashCode() * 31 + tilt.hashCode()
        override fun toString() = "Glass($fraction, tilt=$tilt)"
    }

    /** Okunmuş ve anlamlı değer mi (null, geçersiz işaretçi, 0 = "desteklenmiyor / gelmedi" ve negatifler değil). */
    fun valid(raw: Int?): Boolean = raw != null && raw != Int.MIN_VALUE && raw > 0

    /** Perde; bilinmeyen (0, 7, > 100, geçersiz) null. 8..100 yüzde sayılır (bazı modellerde yüzde gelebilir). */
    fun shade(raw: Int?): Shade? {
        if (!valid(raw)) return null
        return when (raw!!) {
            SHADE_CLOSED -> Shade(Motion.CLOSED, 0f)
            SHADE_OPEN -> Shade(Motion.OPEN, 1f)
            SHADE_OPENING -> Shade(Motion.OPENING)
            SHADE_CLOSING -> Shade(Motion.CLOSING)
            SHADE_STOPPED, SHADE_STOPPED_2 -> Shade(Motion.STOPPED)
            SHADE_UNSUPPORTED -> null
            in 8..POSI_PERCENT_MAX -> Shade(Motion.PERCENT, raw / 100f)
            else -> null
        }
    }

    /**
     * Ön cam panel. Önce SPF_POSI (1 kapalı, 12 havalandırma, 2..11 adım, 13..100 yüzde); okunamazsa SPF_OPERATE
     * ([com.mapcontrol.vehicle.VehicleMetricsSnapshot.sunroofOpenOrNull] ile aynı: 3/6/7 açık, 2/5 kapalı, 4 hareket = null).
     */
    fun glass(posi: Int?, operate: Int?): Glass? {
        if (valid(posi)) {
            val p = posi!!
            when {
                p == POSI_CLOSED -> return Glass(0f)
                p == POSI_TILT -> return Glass(0f, tilt = true)
                p <= POSI_STEP_MAX -> return Glass((p - 1) / 10f)
                p <= POSI_PERCENT_MAX -> return Glass(p / 100f)
            }
        }
        if (!valid(operate)) return null
        return when (operate) {
            3, 6, 7 -> Glass(1f)
            2, 5 -> Glass(0f)
            else -> null
        }
    }

    /** Kenar ışığı (histerezisli): tavan açıkken hız eşiği ya da yağmur. [wasOn] önceki durum. */
    fun edgeAlert(open: Boolean, speedKmh: Int?, raining: Boolean, wasOn: Boolean): Boolean {
        if (!open) return false
        if (raining) return true
        val v = speedKmh ?: return false
        return if (wasOn) v >= EDGE_SPEED_OFF_KMH else v >= EDGE_SPEED_ON_KMH
    }
}

/**
 * Ham okumalardan [RoofState] (durumlu, saf; zaman dışarıdan verilir). Perdenin yüzde sinyali yok (yalnız durum), bu yüzden
 * kısmi konum hareketin başladığı andan geçen süreyle tahmin edilir: açılırken [RoofDecode.SHADE_OPEN_MS], kapanırken
 * [RoofDecode.SHADE_CLOSE_MS]; "yarıda durdu" o anki tahminde kalır. Aynı ham perde değeri tekrar gelince durum aynen kalır
 * (çizim yeniden başlamaz). Cam okunamadığı anlarda (SPF_OPERATE 4 = hareket) son bilinen cam konumu korunur.
 */
class RoofTracker(
    private val openMs: Long = RoofDecode.SHADE_OPEN_MS,
    private val closeMs: Long = RoofDecode.SHADE_CLOSE_MS,
) {
    private var hasShadeRaw = false
    private var shadeRaw: Int? = null
    private var est = 0f
    private var dir = 0
    private var t0 = 0L
    private var shadeTarget: Float? = null
    private var shadeMs = 0L
    private var glass: RoofDecode.Glass? = null
    private var alert = false

    /** Perdenin [now] anındaki tahmini açıklığı (hareket sürüyorsa geçen süreyle). */
    fun estimate(now: Long): Float = when (dir) {
        1 -> CarAnimSpec.clamp01(est + (now - t0).toFloat() / openMs)
        -1 -> CarAnimSpec.clamp01(est - (now - t0).toFloat() / closeMs)
        else -> est
    }

    fun update(sunshade: Int?, posi: Int?, operate: Int?, speedKmh: Int?, raining: Boolean, now: Long): RoofState? {
        if (!hasShadeRaw || sunshade != shadeRaw) {
            val first = !hasShadeRaw || !RoofDecode.valid(shadeRaw)
            hasShadeRaw = true
            shadeRaw = sunshade
            val cur = estimate(now)
            val s = RoofDecode.shade(sunshade)
            when (s?.motion) {
                null -> { dir = 0; shadeTarget = null; shadeMs = 0L }
                RoofDecode.Motion.CLOSED, RoofDecode.Motion.OPEN, RoofDecode.Motion.PERCENT -> {
                    dir = 0; est = s.fraction; shadeTarget = est; shadeMs = 0L
                }
                RoofDecode.Motion.OPENING -> {
                    est = if (first) 0f else cur; dir = 1; t0 = now
                    shadeTarget = 1f; shadeMs = ((1f - est) * openMs).toLong()
                }
                RoofDecode.Motion.CLOSING -> {
                    est = if (first) 1f else cur; dir = -1; t0 = now
                    shadeTarget = 0f; shadeMs = (est * closeMs).toLong()
                }
                RoofDecode.Motion.STOPPED -> {
                    est = if (first) RoofDecode.SHADE_UNKNOWN_MID else cur; dir = 0
                    shadeTarget = est; shadeMs = 0L
                }
            }
        }
        RoofDecode.glass(posi, operate)?.let { glass = it }
        val g = glass
        if (shadeTarget == null && g == null) {
            alert = false
            return null
        }
        val open = g != null && (g.fraction > RoofDecode.OPEN_EPS || g.tilt)
        alert = RoofDecode.edgeAlert(open, speedKmh, raining, alert)
        return RoofState(shadeTarget, shadeMs, g?.fraction ?: 0f, g?.tilt == true, alert)
    }
}

/**
 * Tek tavan büyüklüğünün çizimdeki hareketi (saf): [set] yeni hedef; süre 0 ise [CarAnimSpec.ROOF_EASE_MS]'lik yumuşak
 * geçiş, > 0 ise gerçek hareketi izleyen doğrusal ilerleme. Yarıda gelen hedef o anki değerden başlar.
 */
class RoofMotion {
    private var from = 0f
    var target = 0f
        private set
    private var start = 0L
    private var dur = 0L
    private var linear = false

    fun snap(v: Float) {
        from = v; target = v; dur = 0L
    }

    fun set(to: Float, motionMs: Long, now: Long) {
        from = at(now)
        target = to
        start = now
        linear = motionMs > 0
        dur = if (linear) motionMs else CarAnimSpec.ROOF_EASE_MS
        if (Math.abs(to - from) < 0.0005f) dur = 0L
    }

    fun done(now: Long): Boolean = dur <= 0L || now - start >= dur

    /** Hareket gerçek perdeyi izliyor (seyrek adım yeter). */
    fun tracking(now: Long): Boolean = linear && !done(now)

    fun at(now: Long): Float {
        if (done(now)) return target
        val u = CarAnimSpec.clamp01((now - start).toFloat() / dur)
        val f = if (linear) u else CarAnimSpec.smooth(u, 0f, 1f)
        return from + (target - from) * f
    }
}

/** Demo tavanı: `--es launcher_demo_roof "shade=0..100,glass=0..100[,tilt][,alert|speed=N|rain]"`; boş / bozuk = null. */
object RoofDemo {
    fun parse(raw: String?): RoofState? {
        if (raw.isNullOrBlank()) return null
        var shade: Float? = null
        var glass = 0f
        var tilt = false
        var alert = false
        var any = false
        for (part in raw.split(',')) {
            val kv = part.trim().lowercase(java.util.Locale.ROOT).split('=', limit = 2)
            val k = kv[0].trim()
            val v = kv.getOrNull(1)?.trim()
            val pct = v?.toFloatOrNull()?.let { CarAnimSpec.clamp01(it / 100f) }
            when (k) {
                "shade" -> if (pct != null) { shade = pct; any = true }
                "glass" -> if (pct != null) { glass = pct; any = true }
                "tilt" -> { tilt = v == null || v == "1" || v == "true"; any = true }
                "alert", "rain" -> alert = v == null || v == "1" || v == "true"
                "speed" -> alert = (v?.toIntOrNull() ?: 0) >= RoofDecode.EDGE_SPEED_ON_KMH
            }
        }
        if (!any) return null
        val open = glass > RoofDecode.OPEN_EPS || tilt
        return RoofState(shade, 0L, glass, tilt, alert && open)
    }
}
