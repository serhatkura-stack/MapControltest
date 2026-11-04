package com.mapcontrol.launcher

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Spor modu kararı (saf, JVM'de sınanır): araç sürüş modu [DriveAccent.SPORT] kesintisiz [stableMs] gözlenirse açılır,
 * başka bir mod kesintisiz [stableMs] gözlenirse kapanır. Kısa titremeler (tek okuma) bir şey değiştirmez.
 * [observed] null (veri yok) uygulanan durumu korur ve sayacı sıfırlar. [canSwitch] false iken (düzenleme, açık sayfa)
 * karar ertelenir, sayaç işlemeye devam eder.
 */
class SportGate(private val stableMs: Long = STABLE_MS) {
    var active = false
        private set
    private var candidate: Boolean? = null
    private var since = 0L

    fun reset(on: Boolean) {
        active = on
        candidate = null
    }

    /** Yeni durum (değiştiyse), yoksa null. */
    fun update(now: Long, observedMode: Int?, canSwitch: Boolean): Boolean? {
        val want = observedMode?.let { it == DriveAccent.SPORT }
        if (want == null || want == active) {
            candidate = null
            return null
        }
        if (candidate != want) {
            candidate = want
            since = now
        }
        if (now - since < stableMs || !canSwitch) return null
        active = want
        candidate = null
        return want
    }

    companion object {
        const val STABLE_MS = 1_000L
    }
}

/** Spor kadranlarının saf çözümleri. */
object SportMath {
    /** Devir kadranı üst sınırı (×1000 ölçek 0–8) ve kırmızı bölgenin başlangıcı. */
    const val RPM_MAX = 8_000
    const val RED_ZONE_RPM = 6_500
    /** Kadran yayı: 0 sol altta (135°), 8 sağ altta; saat yönünde 270° (tuval açısı, 0° = sağ). */
    const val START_DEG = 135f
    const val SWEEP_DEG = 270f

    /**
     * ReadOnly ENGINE_RPM (62) ham değerinden dev/dk: ham × 4 (Tiggo 7'de gösterge fotoğrafıyla doğrulandı, 188 → 752).
     * Geçersiz (null, negatif, 0xFFFF gibi aralık dışı) değer null.
     */
    fun rpm(raw: Int?): Int? = raw?.takeIf { it in 0..RAW_MAX }?.let { it * com.mapcontrol.vehicle.VehicleMetricsSnapshot.RPM_FACTOR }

    private const val RAW_MAX = 3_000 // 12 000 dev/dk; üstü bozuk okuma

    /** İbre açısı (tuval derecesi): devir 0..[RPM_MAX] aralığına kırpılır. */
    fun needleDeg(rpm: Float): Float = START_DEG + SWEEP_DEG * (rpm.coerceIn(0f, RPM_MAX.toFloat()) / RPM_MAX)

    /**
     * Vites penceresi: ReadOnly GEARBOX_STATE (26) P=1, R=2, N=3, D=5 (araçta ölçüldü). Kademe numarası bu araçta
     * okunmuyor; biliniyorsa (demo) D'nin yanına yazılır ("D4"). Bilinmeyen durum "—".
     */
    fun gearText(state: Int?, gearNumber: Int? = null): String = when (state) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        5 -> if (gearNumber != null && gearNumber in 1..9) "D$gearNumber" else "D"
        else -> "—"
    }
}

/**
 * Bu sürüşün spor verisi (saf, JVM'de sınanır): en yüksek hız ve 0–100 km/sa süresi.
 *
 * 0–100: araç durmuşken (hız 0) hız 0'dan çıkınca sayaç başlar; 100 km/sa'ya ulaşınca durur (iki örnek arası doğrusal
 * aradeğerle). Hız yeniden 0'a düşer ya da [MAX_RUN_MS] geçerse ölçüm iptal olur. Sürüşün en iyisi tutulur.
 *
 * Yeni sürüş: motor kapalı (devir 0) ve hız 0 kesintisiz [NEW_DRIVE_OFF_MS] sürdükten sonra motor çalışınca ya da araç
 * hareket edince en yüksek hız ve en iyi 0–100 sıfırlanır (kontak kapatılıp açıldı sayılır). Süreç yeniden başlayınca da
 * sıfırdır.
 */
class SportSession {
    var maxSpeedKmh: Int? = null
        private set
    /** Bu sürüşün en iyi 0–100 süresi (ms). */
    var best0to100Ms: Long? = null
        private set
    /** Sürmekte olan 0–100 ölçümü var mı. */
    val timing: Boolean get() = runStart != null

    private var lastSpeed: Int? = null
    private var lastAt = 0L
    private var runStart: Long? = null
    private var offSince: Long? = null
    private var offLongEnough = false

    fun reset() {
        maxSpeedKmh = null
        best0to100Ms = null
        runStart = null
        lastSpeed = null
        offSince = null
        offLongEnough = false
    }

    /** Yeni örnek: [now] ms (monoton), hız km/sa (bilinmiyorsa null), devir (bilinmiyorsa null). Bir şey değiştiyse true. */
    fun update(now: Long, speedKmh: Int?, rpm: Int?): Boolean {
        val beforeMax = maxSpeedKmh
        val beforeBest = best0to100Ms
        // Kontak/yeni sürüş: motor kapalı ve duruyor.
        val off = rpm == 0 && (speedKmh == null || speedKmh == 0)
        if (off) {
            val s = offSince ?: now.also { offSince = it }
            if (now - s >= NEW_DRIVE_OFF_MS) offLongEnough = true
        } else {
            if (offLongEnough && ((rpm ?: 0) > 0 || (speedKmh ?: 0) > 0)) {
                maxSpeedKmh = null
                best0to100Ms = null
                runStart = null
            }
            offSince = null
            offLongEnough = false
        }
        if (speedKmh == null) {
            // Bilinmeyen hız ölçümü bozar: iptal.
            runStart = null
            lastSpeed = null
            return beforeMax != maxSpeedKmh || beforeBest != best0to100Ms
        }
        if (speedKmh > (maxSpeedKmh ?: 0)) maxSpeedKmh = speedKmh

        val prev = lastSpeed
        val prevAt = lastAt
        val start = runStart
        when {
            speedKmh == 0 -> runStart = null
            start == null && prev == 0 -> runStart = now
            start != null && now - start > MAX_RUN_MS -> runStart = null
            start != null && speedKmh >= TARGET_KMH -> {
                // Eşiği geçiş anı: önceki örnekle doğrusal aradeğer.
                val at = if (prev != null && prev < TARGET_KMH && speedKmh > prev)
                    prevAt + ((now - prevAt) * (TARGET_KMH - prev).toDouble() / (speedKmh - prev)).toLong()
                else now
                val run = at - start
                if (run in 1..MAX_RUN_MS && run < (best0to100Ms ?: Long.MAX_VALUE)) best0to100Ms = run
                runStart = null
            }
        }
        lastSpeed = speedKmh
        lastAt = now
        return beforeMax != maxSpeedKmh || beforeBest != best0to100Ms
    }

    companion object {
        const val TARGET_KMH = 100
        const val MAX_RUN_MS = 30_000L
        const val NEW_DRIVE_OFF_MS = 120_000L
    }
}

/**
 * G-metre süzgeci (saf, JVM'de sınanır). Girdi m/s² (cihaz ekseni x sağ, y yukarı, z ekrandan sürücüye doğru).
 *  - [linear] false (yalnız TYPE_ACCELEROMETER): yerçekimi yavaş bir alçak geçiren süzgeçle ([GRAVITY_TAU_S]) kestirilip
 *    çıkarılır; ekranın eğimi böylece sıfırlanır.
 *  - Sonuç [DISPLAY_TAU_S] ile yumuşatılır (titreşim ve yol pürüzü).
 * Çıktı g biriminde: [lateral] sağ +, [longitudinal] fren (ileri itiş) +. Eksen eşlemesi araçta doğrulanmadı.
 */
class GFilter(private val linear: Boolean) {
    private val gravity = FloatArray(3)
    private var seeded = false
    private var lastNs = 0L
    var lateral = 0f
        private set
    var longitudinal = 0f
        private set

    fun reset() {
        seeded = false
        lateral = 0f
        longitudinal = 0f
    }

    fun add(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (!seeded) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            lastNs = timestampNs
            seeded = true
            if (linear) push(x, z, 1f)
            return
        }
        val dt = ((timestampNs - lastNs) / 1e9f).coerceIn(0f, 0.5f)
        lastNs = timestampNs
        val lx: Float
        val lz: Float
        if (linear) {
            lx = x; lz = z
        } else {
            val a = alpha(dt, GRAVITY_TAU_S)
            gravity[0] += a * (x - gravity[0])
            gravity[1] += a * (y - gravity[1])
            gravity[2] += a * (z - gravity[2])
            lx = x - gravity[0]
            lz = z - gravity[2]
        }
        push(lx, lz, alpha(dt, DISPLAY_TAU_S))
    }

    private fun push(lx: Float, lz: Float, a: Float) {
        // Hissedilen kuvvet ivmenin tersi: sola dönüşte (ivme -x) nokta sağa; frende (ivme +z, geriye) nokta yukarı.
        val latG = lx / G
        val lonG = lz / G
        lateral += a * (latG - lateral)
        longitudinal += a * (lonG - longitudinal)
    }

    /** Toplam büyüklük (g). */
    val magnitude: Float get() = sqrt(lateral * lateral + longitudinal * longitudinal)

    companion object {
        const val G = 9.80665f
        const val GRAVITY_TAU_S = 3f
        const val DISPLAY_TAU_S = 0.25f
        /** Sensör en çok ~15 Hz. */
        const val SAMPLING_US = 66_666

        fun alpha(dt: Float, tau: Float): Float = if (dt <= 0f) 0f else (1f - exp(-dt / tau))

        /** Ekranda 1 g'yi aşan değer kenarda durur. */
        fun clampUnit(v: Float): Float = v.coerceIn(-1f, 1f)
    }
}

/** Spor kadranlarında gösterilecek değerlerin biçimi (Türkçe ondalık virgül). */
object SportFormat {
    fun seconds(ms: Long?): String? = ms?.let { tenths(it / 1000.0) }
    fun l100(v: Double?): String? = v?.let { tenths(it) }
    fun g(v: Float): String = tenths(v.toDouble())

    /** Onda bir hassasiyet, virgülle ("9,6"). */
    fun tenths(v: Double): String {
        val t = Math.round(v * 10).toLong()
        val sign = if (t < 0) "-" else ""
        val a = abs(t)
        return "$sign${a / 10},${a % 10}"
    }
}
