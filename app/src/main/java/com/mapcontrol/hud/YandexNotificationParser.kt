package com.mapcontrol.hud

import java.util.Locale

/*
 * Yandex Haritalar navigasyon BİLDİRİMİNDEN (arka plandayken zengin) manevra çözümü. SAF kod: Android'e dokunmaz.
 * Girdi: android.title (sonraki manevraya mesafe), android.text (talimat YA DA sokak adı) ve RemoteViews'tan
 * okunabilen ikon kaynak adı (örn. notification_left_sdl; okunamazsa null). Öncelik: ikon adı > Türkçe/İngilizce/Rusça
 * metin kalıbı > bilinmiyor (null, HUD'a ikon 0; asla tahmin edilmez).
 */

/** Yandex `Action` listesine karşılık gelen, HUD'a çevrilebilen manevra türleri. */
enum class Maneuver {
    STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN,
    ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, DESTINATION,
}

/** Bildirimden çıkan durum. [maneuver] bilinmiyorsa null; [roundaboutExit] yalnız döner kavşak manevralarında. */
data class NavUpdate(
    val distanceM: Long,
    val street: String?,
    val maneuver: Maneuver?,
    val roundaboutExit: Int?,
)

/** Manevra -> HUD (Amap) ikon numarası. HUD'da doğrulanmış değerler. */
object HudManeuverIcons {
    const val UNKNOWN = 0
    const val ROUNDABOUT_EXIT_BASE = 28
    const val MAX_EXIT = 12

    fun hudIcon(m: Maneuver?, roundaboutExit: Int?): Int {
        if (m == null) return UNKNOWN
        val roundabout = m == Maneuver.ROUNDABOUT_ENTER || m == Maneuver.ROUNDABOUT_EXIT
        if (roundabout && roundaboutExit != null && roundaboutExit in 1..MAX_EXIT) {
            return ROUNDABOUT_EXIT_BASE + roundaboutExit
        }
        return when (m) {
            Maneuver.LEFT -> 2
            Maneuver.RIGHT -> 3
            Maneuver.SLIGHT_LEFT -> 4
            Maneuver.SLIGHT_RIGHT -> 5
            Maneuver.SHARP_LEFT -> 6
            Maneuver.SHARP_RIGHT -> 7
            Maneuver.UTURN -> 8
            Maneuver.STRAIGHT -> 9
            Maneuver.ROUNDABOUT_ENTER -> 11
            Maneuver.DESTINATION -> 15
            Maneuver.ROUNDABOUT_EXIT -> UNKNOWN // çıkış numarası yoksa tahmin yok
        }
    }
}

/**
 * Durumlu çözümleyici: Yandex `android.text` alanında talimat ile sokak adını dönüşümlü gösterir; son sokak ve son
 * manevra ayrı hatırlanır. Tek iş parçacığından çağrılmalıdır (çağıran senkronlar).
 */
class YandexNotificationParser {

    private var lastStreet: String? = null
    private var lastManeuver: Maneuver? = null
    private var lastExit: Int? = null
    private var lastDistance: Long? = null
    private var lastMs = 0L

    fun reset() {
        lastStreet = null
        lastManeuver = null
        lastExit = null
        lastDistance = null
    }

    /** Mesafe okunamıyorsa (örn. "Navigator başlatıldı" yer tutucusu) null: veri yok. */
    fun parse(title: String?, text: String?, iconName: String?, nowMs: Long = 0L): NavUpdate? {
        val dist = parseDistance(title) ?: return null
        if (lastDistance != null && nowMs - lastMs > MEMORY_MS) reset()

        val instr = classifyText(text)
        val iconManeuver = maneuverFromIconName(iconName)
        val street = if (instr == null) streetOrNull(text) else null
        if (street != null) lastStreet = street

        var maneuver = iconManeuver ?: instr?.maneuver
        var exit = instr?.exit
        if (maneuver != null) {
            if (exit == null && isRoundabout(maneuver) && lastManeuver?.let { isRoundabout(it) } == true) exit = lastExit
            lastManeuver = maneuver
            lastExit = exit
        } else {
            // Yeni manevraya geçildiyse (mesafe belirgin arttı) eski manevra geçersiz.
            val prev = lastDistance
            if (prev != null && dist > prev + NEW_MANEUVER_JUMP_M) {
                lastManeuver = null
                lastExit = null
            }
            maneuver = lastManeuver
            exit = lastExit
        }
        lastDistance = dist
        lastMs = nowMs
        return NavUpdate(dist, lastStreet, maneuver, if (maneuver != null && isRoundabout(maneuver)) exit else null)
    }

    private class Instr(val maneuver: Maneuver?, val exit: Int?)

    private fun isRoundabout(m: Maneuver) = m == Maneuver.ROUNDABOUT_ENTER || m == Maneuver.ROUNDABOUT_EXIT

    companion object {
        /** Bu süreden uzun veri gelmediyse hatırlanan sokak/manevra unutulur. */
        const val MEMORY_MS = 60_000L
        const val NEW_MANEUVER_JUMP_M = 50L

        private val PLACEHOLDERS = listOf("navigator başlatıldı", "navigator started", "навигатор запущен")

        private val DIST_LOOSE = Regex("""(\d+(?:[.,]\d+)?)\s*(km|kilometre|километр\p{L}*|км|metre|meters?|метр\p{L}*|m|м)\b""", RegexOption.IGNORE_CASE)

        /** "250 m", "1,2 km", "1.2 km", "800 м"; bulunamazsa null. Sıkı çözümleyici, olmazsa gevşek arama. */
        fun parseDistance(title: String?): Long? {
            if (title.isNullOrBlank()) return null
            HudDistanceParser.parseText(title)?.let { return it }
            val m = DIST_LOOSE.find(title.replace('\u00A0', ' ').replace('\u202F', ' ')) ?: return null
            return HudDistanceParser.parseText(m.groupValues[1] + " " + m.groupValues[2])
        }

        /** Yandex ikon kaynak adı (notification_left_sdl ...) -> manevra; tanınmayan (feribot, bilinmeyen) null. */
        fun maneuverFromIconName(name: String?): Maneuver? {
            if (name.isNullOrBlank()) return null
            val n = name.lowercase(Locale.ROOT).removePrefix("notification_").removeSuffix("_sdl")
            return when (n) {
                "straight" -> Maneuver.STRAIGHT
                "slight_left", "fork_left", "exit_left" -> Maneuver.SLIGHT_LEFT
                "slight_right", "fork_right", "exit_right" -> Maneuver.SLIGHT_RIGHT
                "left" -> Maneuver.LEFT
                "right" -> Maneuver.RIGHT
                "hard_left" -> Maneuver.SHARP_LEFT
                "hard_right" -> Maneuver.SHARP_RIGHT
                "uturn_left", "uturn_right" -> Maneuver.UTURN
                "enter_roundabout" -> Maneuver.ROUNDABOUT_ENTER
                "leave_roundabout" -> Maneuver.ROUNDABOUT_EXIT
                "finish" -> Maneuver.DESTINATION
                else -> null
            }
        }

        private val EXIT_RE = Regex("""(\d{1,2})\s*(?:\.|st|nd|rd|th|-?[а-я]{1,2})?\s*(?:çıkı[şs]|exit|съезд)""")
        private val SLIGHT_TR_RE = Regex("""(sol|sağ)dan\s+(devam|gidin|ilerleyin|çıkın|çıkış)""")

        /** Küçük harf adayları: Türkçe yereli (ı/İ doğru) ve ROOT (İngilizce "slight"in i'si bozulmasın). */
        private fun candidates(text: String): List<String> {
            val tr = text.lowercase(Locale("tr"))
            val root = text.lowercase(Locale.ROOT).replace("i̇", "i")
            return if (tr == root) listOf(tr) else listOf(tr, root)
        }

        private fun has(c: List<String>, vararg subs: String) = c.any { s -> subs.any { it in s } }

        private fun classify(text: String): Instr? {
            val c = candidates(text.trim())
            if (has(c, "varış", "hedef", "destination", "you have arrived", "arrive", "прибы", "финиш", "конечн")) {
                return Instr(Maneuver.DESTINATION, null)
            }
            if (has(c, "u dönüşü", "u-dönüşü", "geri dön", "u-turn", "u turn", "uturn", "разворот", "разверн")) {
                return Instr(Maneuver.UTURN, null)
            }
            val exitNo = c.firstNotNullOfOrNull { EXIT_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val roundaboutWord = has(c, "döner kavşa", "roundabout", "traffic circle", "rotary", "кольц", "круг")
            if (roundaboutWord || exitNo != null) {
                return when {
                    exitNo != null -> Instr(Maneuver.ROUNDABOUT_EXIT, exitNo)
                    has(c, "girin", " gir", "enter", "въед") -> Instr(Maneuver.ROUNDABOUT_ENTER, null)
                    has(c, "çıkın", "çıkış", "leave", "exit", "съезд") -> Instr(Maneuver.ROUNDABOUT_EXIT, null)
                    else -> Instr(null, null) // kavşaktan söz ediyor ama yönü belirsiz: talimat, sokak değil
                }
            }
            if (has(c, "keskin sol", "sharp left", "резко налево")) return Instr(Maneuver.SHARP_LEFT, null)
            if (has(c, "keskin sağ", "sharp right", "резко направо")) return Instr(Maneuver.SHARP_RIGHT, null)
            val slightTr = c.firstNotNullOfOrNull { SLIGHT_TR_RE.find(it)?.groupValues?.get(1) }
            if (slightTr == "sol" || has(c, "hafif sol", "slight left", "bear left", "keep left", "левее")) {
                return Instr(Maneuver.SLIGHT_LEFT, null)
            }
            if (slightTr == "sağ" || has(c, "hafif sağ", "slight right", "bear right", "keep right", "правее")) {
                return Instr(Maneuver.SLIGHT_RIGHT, null)
            }
            if (has(c, "sola dön", "turn left", "налево")) return Instr(Maneuver.LEFT, null)
            if (has(c, "sağa dön", "turn right", "направо")) return Instr(Maneuver.RIGHT, null)
            if (has(c, "düz", "straight", "прямо") || c.any { it.trim().trimEnd('.', '!') in setOf("devam edin", "continue") }) {
                return Instr(Maneuver.STRAIGHT, null)
            }
            if (has(c, "feribot", "ferry", "паром")) return Instr(null, null)
            return null
        }

        private fun classifyText(text: String?): Instr? =
            if (text.isNullOrBlank()) null else classify(text)

        private fun streetOrNull(text: String?): String? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val low = t.lowercase(Locale("tr"))
            if (PLACEHOLDERS.any { low.startsWith(it) }) return null
            return t
        }
    }
}
