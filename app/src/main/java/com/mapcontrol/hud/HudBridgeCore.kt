package com.mapcontrol.hud

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Yandex -> HUD köprüsünün SAF çekirdeği: Android'e ve araca dokunmaz, JVM'de sınanır.
 * Parçalar: mesafe çözümleyici, Yandex alanlarından HUD durumu, beyaz liste, hız sınırlayıcı,
 * ikon kaynağı arayüzü ve "ne gönderilecek" kararını veren [HudBridgeEngine].
 * Araca yazan tek sınıf launchertest'teki HudBridgeSender'dır.
 */

/** Yandex okumasından çıkan, HUD'a gidecek durum. Alanlar yoksa null. */
data class HudState(
    val road: String?,
    val maneuverMeters: Int?,
    val totalMeters: Long?,
    /** Yandex'in o anki talimat metni ("Soldan devam edin"); sokak bilinmiyorsa yol adı yedeği. */
    val instruction: String? = null,
)

/** Manevra ikon numarasını (Amap numaralandırması) veren kaynak. Tanıyamazsa null döner; asla tahmin edilmez. */
fun interface ManeuverIconSource {
    fun currentIcon(): Int?
}

/** Varsayılan kaynak: henüz dönüş tipi tanınmıyor, hep null (HUD'a ikon 0 gider). */
object NoManeuverIcon : ManeuverIconSource {
    override fun currentIcon(): Int? = null
}

/** Yandex metin alanlarını metreye çevirir. Rusça ve Türkçe birimleri, ondalık virgülü/noktayı tanır. */
object HudDistanceParser {

    private val PATTERN = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([^\d\s.,]+)?\.?\s*$""")

    private val METER_UNITS = setOf("m", "м", "metre", "meter", "метр", "метров", "метра")
    private val KM_UNITS = setOf("km", "км", "kilometre", "kilometer", "километр", "километров", "километра")

    /** Manevra: sayı [distance], birim [unit]; birim boşsa sayının içinde aranır ("850 м"). Anlaşılmazsa null. */
    fun parseMeters(distance: String?, unit: String?): Long? {
        if (distance.isNullOrBlank()) return null
        val text = if (unit.isNullOrBlank()) distance else "$distance $unit"
        return parseText(text)
    }

    /** Tek metin: "850 m", "1,2 km", "1.2 км". Birimsiz ya da bilinmeyen birim null (tahmin yok). */
    fun parseText(raw: String?): Long? {
        if (raw == null) return null
        val m = PATTERN.matchEntire(raw.replace(' ', ' ').replace(' ', ' ').trim()) ?: return null
        val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase().trimEnd('.')
        return when {
            unit in METER_UNITS -> number.roundToLong()
            unit in KM_UNITS -> (number * 1000.0).roundToLong()
            else -> null
        }
    }
}

/** Yandex anlık görüntüsü alanlarından [HudState]. */
object HudStateMapper {
    /** Manevra da toplam mesafe de okunamıyorsa gösterecek bir şey yok: null. */
    fun map(maneuverDistance: String?, maneuverUnit: String?, nextStreet: String?, etaDistance: String?): HudState? {
        val man = HudDistanceParser.parseMeters(maneuverDistance, maneuverUnit)?.coerceIn(0L, 16_000_000L)?.toInt()
        val total = HudDistanceParser.parseText(etaDistance)
        if (man == null && total == null) return null
        val road = nextStreet?.trim()?.takeIf { it.isNotEmpty() }
        return HudState(road, man, total)
    }
}

/** Köprünün gönderebileceği alt komutlar: {3, 52, 48, 50, 53}. Gerisi (49/51 dahil) reddedilir. */
object HudBridgeWhitelist {
    val ALLOWED: Set<Int> = setOf(
        HudPayload.SUB_CLEAR, HudPayload.SUB_NAV_STARTED, HudPayload.SUB_ROAD_NAME,
        HudPayload.SUB_MANEUVER_ALT, HudPayload.SUB_TOTAL_ALT,
    )

    /** İzinliyse [HudPayload.validate]'ten geçmiş aynı diziyi döndürür; değilse IllegalArgumentException. */
    fun check(values: IntArray): IntArray {
        require(values.isNotEmpty() && values[0] in ALLOWED) {
            "HUD köprüsü bu alt komutu göndermez: ${values.firstOrNull()}"
        }
        return HudPayload.validate(values)
    }
}

/** Kayan pencere hız sınırlayıcı: her [windowMs] içinde en çok [max] gönderim. */
class HudRateLimiter(private val max: Int = 5, private val windowMs: Long = 1000L) {
    private val stamps = ArrayDeque<Long>()

    /** İzin varsa kaydeder ve true döner. */
    fun tryAcquire(nowMs: Long): Boolean {
        while (stamps.isNotEmpty() && nowMs - stamps.peekFirst() >= windowMs) stamps.pollFirst()
        if (stamps.size >= max) return false
        stamps.addLast(nowMs)
        return true
    }
}

/**
 * "Ne gönderilecek" kararı. HUD'da çalışan dizilimi taklit eder: temizle, sonra HER İKİ gönderim arası en az
 * [MIN_GAP_MS]: navStarted (52), yol adı (48), manevra (50), toplam (53). Yol adı ve toplam mesafe her zaman gönderilir
 * (yedekli). Eşikler: manevra >=10 m ya da ikon değişimi, toplam >=100 m, yol adı değişince. Değişmese de manevra
 * [MANEUVER_REFRESH_MS], yol+toplam [SLOW_REFRESH_MS] aralıkla tazelenir (durumu düşüren HUD toparlansın).
 */
class HudBridgeEngine(
    private val send: (IntArray) -> Boolean,
    private val iconSource: () -> Int?,
    private val limiter: HudRateLimiter = HudRateLimiter(),
    private val log: (String) -> Unit = {},
) {
    private var wanted: HudState? = null
    private var lastSeenMs = 0L
    private var clearPending = false
    private var clearedForStart = false
    private var started = false
    private var blockedUntilMs = 0L
    private var lastAnySendMs = NEVER

    private var sentRoad: String? = null
    private var sentIcon: Int? = null
    private var sentDist: Int? = null
    private var sentTotal: Long? = null
    private var lastRoadMs = NEVER
    private var lastManeuverSendMs = NEVER
    private var lastTotalMs = NEVER

    /** Yandex rehberlik verisi geldi. */
    fun onState(state: HudState, nowMs: Long) {
        wanted = state
        lastSeenMs = nowMs
        clearPending = false
        pump(nowMs)
    }

    /** Yandex penceresi var ama rehberlik bitti. */
    fun onGuidanceEnded(nowMs: Long) {
        wanted = null
        if (started || clearedForStart) clearPending = true
        pump(nowMs)
    }

    /** Düzenli çağrı: bekleyen gönderimleri dener, Yandex [GONE_MS]'den uzun süredir görünmüyorsa temizler. */
    fun tick(nowMs: Long) {
        if (wanted != null && nowMs - lastSeenMs > GONE_MS) {
            log("Yandex ${GONE_MS} ms görünmedi, HUD temizleniyor")
            wanted = null
            clearPending = true
        }
        pump(nowMs)
    }

    private fun trySend(values: IntArray, nowMs: Long): Boolean {
        if (nowMs < blockedUntilMs) return false
        if (nowMs - lastAnySendMs < MIN_GAP_MS) return false
        if (!limiter.tryAcquire(nowMs)) return false
        lastAnySendMs = nowMs
        val ok = send(values)
        if (!ok) blockedUntilMs = nowMs + FAIL_BACKOFF_MS
        return ok
    }

    private fun resetSent() {
        started = false
        clearedForStart = false
        sentRoad = null
        sentIcon = null
        sentDist = null
        sentTotal = null
        lastRoadMs = NEVER
        lastManeuverSendMs = NEVER
        lastTotalMs = NEVER
    }

    /** Yol adı: bilinen sokak, yoksa Yandex talimat metni, yoksa "Yandex". */
    private fun effectiveRoad(s: HudState): String =
        s.road?.takeIf { it.isNotBlank() } ?: s.instruction?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_ROAD

    private fun pump(nowMs: Long) {
        val s = wanted
        if (s == null) {
            if (clearPending && trySend(HudPayload.clear(), nowMs)) {
                clearPending = false
                resetSent()
            }
            return
        }
        if (!started) {
            if (!clearedForStart) {
                if (!trySend(HudPayload.clear(), nowMs)) return
                clearedForStart = true
                return
            }
            if (!trySend(HudPayload.navStarted(), nowMs)) return
            started = true
            return
        }
        val road = effectiveRoad(s)
        val dist = s.maneuverMeters
        // Toplam mesafe bilinmiyorsa manevra mesafesi yer tutucu (erişilebilirlik/ETA gerçek toplamı verene kadar).
        val total = s.totalMeters ?: dist?.toLong()

        // İlk dizilim sırayla: yol adı, manevra, toplam. Hiçbiri atlanamaz.
        if (sentRoad == null) {
            sendRoad(road, nowMs)
            return
        }
        if (dist != null && sentDist == null) {
            sendManeuver(dist, nowMs)
            return
        }
        if (total != null && sentTotal == null) {
            sendTotal(total, nowMs)
            return
        }

        // Kararlı durum: vadesi gelenlerden en eski gönderileni seç (açlık yok).
        var bestAge = -1L
        var best = 0
        fun consider(kind: Int, due: Boolean, lastMs: Long) {
            if (!due) return
            val age = nowMs - lastMs
            if (age > bestAge) { bestAge = age; best = kind }
        }
        consider(1, road != sentRoad || nowMs - lastRoadMs >= SLOW_REFRESH_MS, lastRoadMs)
        if (dist != null) {
            val prev = sentDist
            val icon = iconSource() ?: ICON_NONE
            val changed = prev == null || icon != sentIcon || abs(dist - prev) >= MANEUVER_STEP_M
            consider(2, changed || nowMs - lastManeuverSendMs >= MANEUVER_REFRESH_MS, lastManeuverSendMs)
        }
        if (total != null) {
            val prev = sentTotal
            val changed = prev == null || abs(total - prev) >= TOTAL_STEP_M
            consider(3, changed || nowMs - lastTotalMs >= SLOW_REFRESH_MS, lastTotalMs)
        }
        when (best) {
            1 -> sendRoad(road, nowMs)
            2 -> sendManeuver(dist!!, nowMs)
            3 -> sendTotal(total!!, nowMs)
        }
    }

    private fun sendRoad(road: String, nowMs: Long) {
        if (!trySend(HudPayload.roadName(road), nowMs)) return
        sentRoad = road
        lastRoadMs = nowMs
    }

    private fun sendManeuver(dist: Int, nowMs: Long) {
        val icon = iconSource() ?: ICON_NONE
        if (!trySend(HudPayload.maneuverAlt(icon, dist), nowMs)) return
        sentIcon = icon
        sentDist = dist
        lastManeuverSendMs = nowMs
    }

    private fun sendTotal(total: Long, nowMs: Long) {
        if (!trySend(HudPayload.totalDistanceAlt(total), nowMs)) return
        sentTotal = total
        lastTotalMs = nowMs
    }

    companion object {
        const val GONE_MS = 5000L
        const val MANEUVER_STEP_M = 10
        const val TOTAL_STEP_M = 100L
        /** Herhangi iki ardışık HUD gönderimi arası en az süre (HUD'da çalışan dizilim 400 ms idi). */
        const val MIN_GAP_MS = 400L
        const val MANEUVER_REFRESH_MS = 3000L
        const val SLOW_REFRESH_MS = 10_000L
        const val FAIL_BACKOFF_MS = 2000L
        const val FALLBACK_ROAD = "Yandex"
        private const val NEVER = Long.MIN_VALUE / 2
        /** Bilinmeyen/yok ikon. */
        const val ICON_NONE = 0
        /** Hata ayıklama: düz devam (Amap 9). */
        const val ICON_STRAIGHT = 9
    }
}

/** Erişilebilirlik hizmeti etkin mi: Settings.Secure.enabled_accessibility_services listesinde bileşen var mı. */
object HudAccessibilityCheck {
    fun isListed(enabledServices: String?, component: String): Boolean {
        if (enabledServices.isNullOrEmpty()) return false
        val short = component.replace("/.", "/")
        return enabledServices.split(':').any {
            val e = it.trim()
            e.equals(component, ignoreCase = true) || e.equals(short, ignoreCase = true)
        }
    }
}
