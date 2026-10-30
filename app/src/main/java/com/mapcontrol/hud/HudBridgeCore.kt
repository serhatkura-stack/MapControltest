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
data class HudState(val road: String?, val maneuverMeters: Int?, val totalMeters: Long?)

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

/** "Ne gönderilecek" kararı. Eşikler: manevra >=10 m ya da ikon değişimi (en çok 2 Hz), toplam >=100 m, yol adı değişince. */
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

    private var sentRoad: String? = null
    private var sentIcon: Int? = null
    private var sentDist: Int? = null
    private var sentTotal: Long? = null
    private var lastManeuverSendMs = Long.MIN_VALUE / 2

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
        if (!limiter.tryAcquire(nowMs)) return false
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
    }

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
            }
            if (!trySend(HudPayload.navStarted(), nowMs)) return
            started = true
        }
        val road = s.road
        if (road != null && road != sentRoad) {
            if (!trySend(HudPayload.roadName(road), nowMs)) return
            sentRoad = road
        }
        val dist = s.maneuverMeters
        if (dist != null) {
            val icon = iconSource() ?: ICON_NONE
            val prevDist = sentDist
            val changed = prevDist == null || icon != sentIcon || abs(dist - prevDist) >= MANEUVER_STEP_M
            if (changed && nowMs - lastManeuverSendMs >= MANEUVER_MIN_GAP_MS) {
                if (!trySend(HudPayload.maneuverAlt(icon, dist), nowMs)) return
                sentIcon = icon
                sentDist = dist
                lastManeuverSendMs = nowMs
            }
        }
        val total = s.totalMeters
        if (total != null) {
            val prev = sentTotal
            if (prev == null || abs(total - prev) >= TOTAL_STEP_M) {
                if (!trySend(HudPayload.totalDistanceAlt(total), nowMs)) return
                sentTotal = total
            }
        }
    }

    companion object {
        const val GONE_MS = 5000L
        const val MANEUVER_STEP_M = 10
        const val TOTAL_STEP_M = 100L
        const val MANEUVER_MIN_GAP_MS = 500L
        const val FAIL_BACKOFF_MS = 2000L
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
