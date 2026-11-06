package com.mapcontrol.launcher

import java.text.Collator
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Telefon arka ucunun saf mantığı (Android çağrısı yok; JVM testli: PhoneLogicTest).
 * GİZLİLİK: buradaki hiçbir işlev isim/numara günlüğe yazmaz.
 */

/** VDBus VDBTDialInfo'nun test edilebilir özeti (Parcel gerektirmez). [state] = VDValueBT.DialStatus. */
internal data class DialInfoLite(val state: Int, val outGoing: Boolean = false)

/** Arama durumu eşlemeleri (VDBus, HFP istemci, arama geçmişi). */
internal object PhoneStateMap {
    // VDValueBT.DialStatus ile BluetoothHeadsetClientCall.CALL_STATE_* aynı sayılardır.
    const val ACTIVE = 0
    const val HELD = 1
    const val DIALING = 2
    const val ALERTING = 3
    const val INCOMING = 4
    const val WAITING = 5
    const val HELD_BY_RESPONSE_AND_HOLD = 6
    const val TERMINATED = 7
    const val MISSED = 8

    private fun category(state: Int): CallKind? = when (state) {
        INCOMING, WAITING -> CallKind.INCOMING
        ACTIVE, HELD, HELD_BY_RESPONSE_AND_HOLD -> CallKind.ACTIVE
        DIALING, ALERTING -> CallKind.DIALING
        TERMINATED, MISSED -> CallKind.ENDED
        else -> null
    }

    private val PRIORITY = listOf(CallKind.INCOMING, CallKind.ACTIVE, CallKind.DIALING, CallKind.ENDED)

    /** Birden çok arama varsa öncelik: gelen/bekleyen > aktif/beklemede > aranıyor > bitti; boş liste NONE. */
    fun fromVdDialInfos(list: List<DialInfoLite>): CallKind {
        val kinds = list.mapNotNull { category(it.state) }.toSet()
        return PRIORITY.firstOrNull { it in kinds } ?: CallKind.NONE
    }

    /** [fromVdDialInfos] sonucunu üreten ilk kaydın sırası (isim/numara bundan alınır); yoksa -1. */
    fun relevantIndex(list: List<DialInfoLite>): Int {
        val kind = fromVdDialInfos(list)
        if (kind == CallKind.NONE) return -1
        return list.indexOfFirst { category(it.state) == kind }
    }

    /** BluetoothHeadsetClientCall.CALL_STATE_* tek arama eşlemesi. */
    fun fromHfpClientCallState(state: Int): CallKind = when (state) {
        ACTIVE, HELD, HELD_BY_RESPONSE_AND_HOLD -> CallKind.ACTIVE
        DIALING, ALERTING -> CallKind.DIALING
        INCOMING, WAITING -> CallKind.INCOMING
        TERMINATED -> CallKind.ENDED
        else -> CallKind.NONE
    }

    /** CallLog.Calls.TYPE: 1 gelen, 2 giden, 3 cevapsız, 5 reddedilen (cevapsız sayılır); sesli mesaj vb. null. */
    fun recentType(callLogType: Int): RecentType? = when (callLogType) {
        1 -> RecentType.INCOMING
        2 -> RecentType.OUTGOING
        3, 5 -> RecentType.MISSED
        else -> null
    }

    /** VDValueBT.RecordType: 1 cevapsız, 2 gelen (kabul), 3 giden. */
    fun recentTypeFromVd(recordType: Int): RecentType? = when (recordType) {
        1 -> RecentType.MISSED
        2 -> RecentType.INCOMING
        3 -> RecentType.OUTGOING
        else -> null
    }

    /**
     * Arka uçtan gelen ham türü ekranda gösterilecek türe çevirir: görüşmeden doğrudan boşa (liste boşaldı) geçişte
     * kısa "bitti" gösterilir; boştayken gelen eski "bitti" kayıtları yok sayılır (bitti yeniden açılmaz).
     */
    fun effectiveKind(prev: CallKind, raw: CallKind): CallKind = when {
        raw == CallKind.NONE && (prev == CallKind.INCOMING || prev == CallKind.DIALING || prev == CallKind.ACTIVE) -> CallKind.ENDED
        raw == CallKind.NONE && prev == CallKind.ENDED -> CallKind.ENDED
        raw == CallKind.ENDED && prev == CallKind.NONE -> CallKind.NONE
        else -> raw
    }

    /**
     * Yeni arama durumu: ACTIVE'e ilk girişte sayaç başlar ([nowElapsed]), ACTIVE sürerken korunur. Konuşma sırasında
     * bekleyen arama gelirse (ACTIVE → INCOMING) sayaç başlangıcı saklanır; bekleyen reddedilip konuşmaya dönülünce
     * sayaç 0:00'dan başlamaz. Diğer durumlarda 0. Sessiz yalnızca görüşme sürerken korunur.
     *
     * İsim/numara yalnızca AYNI görüşmede eksik gelirse öncekinden alınır: önceki durum gelen/aranıyor/konuşma ve
     * görüşme ileri gidiyor (gelen/aranıyor → konuşma, herhangi biri → bitti, aynı durum) ve yeni numara yok ya da
     * öncekiyle aynı. "Bitti" sonrası gelen yeni arama ve konuşma sırasında bekleyen arama yeni görüşmedir: gizli
     * numaralı arayan önceki kişinin adıyla gösterilmez.
     */
    fun nextCall(prev: CallState, kind: CallKind, name: String?, number: String?, nowElapsed: Long): CallState {
        val forward = when (prev.kind) {
            CallKind.INCOMING -> kind == CallKind.INCOMING || kind == CallKind.ACTIVE || kind == CallKind.ENDED
            CallKind.DIALING -> kind == CallKind.DIALING || kind == CallKind.ACTIVE || kind == CallKind.ENDED
            CallKind.ACTIVE -> kind == CallKind.ACTIVE || kind == CallKind.ENDED
            else -> false
        }
        val sameNumber = number == null || prev.number == null ||
            FavoritesRanker.normalizeNumber(number) == FavoritesRanker.normalizeNumber(prev.number)
        val sameCall = forward && sameNumber
        val started = when {
            kind == CallKind.ACTIVE && (prev.kind == CallKind.ACTIVE || prev.kind == CallKind.INCOMING) && prev.startedAtMs != 0L -> prev.startedAtMs
            kind == CallKind.ACTIVE -> nowElapsed
            kind == CallKind.INCOMING && prev.kind == CallKind.ACTIVE -> prev.startedAtMs
            kind == CallKind.INCOMING && prev.kind == CallKind.INCOMING -> prev.startedAtMs
            else -> 0L
        }
        val inCall = kind == CallKind.INCOMING || kind == CallKind.DIALING || kind == CallKind.ACTIVE
        return CallState(
            kind = kind,
            name = name ?: if (sameCall) prev.name else null,
            number = number ?: if (sameCall) prev.number else null,
            startedAtMs = started,
            muted = inCall && prev.muted,
        )
    }

    private val VD_FORMATS = listOf("yyyyMMdd'T'HHmmss", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
    /** Yalnız rakamdan oluşan sıkışık tarihler (Desay/PBAP): 14 hane tarih-saat, 8 hane yalnız tarih. */
    private val COMPACT_FORMATS = mapOf(14 to "yyyyMMddHHmmss", 8 to "yyyyMMdd")

    /**
     * VDBTRecord tarih metni → duvar saati ms. Biçim araçta doğrulanmadı: 14/8 haneli sıkışık tarih ("20261105143000",
     * "20261105"), 9–13 haneli sayıysa ms (ya da sn), değilse PBAP "20261105T143000" ve yaygın tarih biçimleri
     * denenir. Çözülemezse null.
     */
    fun parseVdDateTime(raw: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val s = raw?.trim()?.removeSuffix("Z").orEmpty()
        if (s.isEmpty()) return null
        val digits = s.all { it.isDigit() }
        if (digits) COMPACT_FORMATS[s.length]?.let { f -> return parseWith(f, s, zone) }
        if (digits && s.length in 9..13) {
            val v = s.toLongOrNull() ?: return null
            return if (s.length >= 12) v else v * 1000L
        }
        for (f in VD_FORMATS) {
            val fmt = SimpleDateFormat(f, Locale.US).apply { timeZone = zone; isLenient = false }
            try {
                return fmt.parse(s)?.time ?: continue
            } catch (e: ParseException) {
                // sıradaki biçim
            }
        }
        return null
    }

    private fun parseWith(format: String, s: String, zone: TimeZone): Long? = try {
        SimpleDateFormat(format, Locale.US).apply { timeZone = zone; isLenient = false }.parse(s)?.time
    } catch (e: ParseException) {
        null
    }
}

/** Favori sıralaması: sabitlenenler → yıldızlılar (Türkçe alfabe) → az ise son 30 günün en sık aranan numaraları. */
internal object FavoritesRanker {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WINDOW_MS = 30 * DAY_MS
    private const val FILL_BELOW = 4

    /** Rakamlar tutulur; ülke/alan öneki farkı ("+90", "00", "0") için son 10 rakam alınır. */
    fun normalizeNumber(n: String): String {
        val d = n.filter { it in '0'..'9' }
        return if (d.length > 10) d.takeLast(10) else d
    }

    fun rank(
        contacts: List<PhoneContact>,
        recents: List<PhoneRecent>,
        pinnedIds: List<String>,
        max: Int = 6,
        nowMs: Long,
    ): List<PhoneContact> {
        val out = ArrayList<PhoneContact>()
        val usedIds = HashSet<String>()
        val usedNumbers = HashSet<String>()
        val byNumber = HashMap<String, PhoneContact>()
        for (c in contacts) {
            val k = normalizeNumber(c.number)
            if (k.isNotEmpty() && k !in byNumber) byNumber[k] = c
        }

        fun add(c: PhoneContact): Boolean {
            if (out.size >= max) return false
            val k = normalizeNumber(c.number)
            if (c.id in usedIds || (k.isNotEmpty() && k in usedNumbers)) return false
            out.add(c)
            usedIds.add(c.id)
            if (k.isNotEmpty()) usedNumbers.add(k)
            return true
        }

        fun fromRecent(r: PhoneRecent): PhoneContact {
            val k = normalizeNumber(r.number)
            return byNumber[k] ?: PhoneContact(
                id = "n:$k",
                name = r.name?.takeIf { it.isNotBlank() } ?: r.number.trim(),
                number = r.number,
                starred = false,
            )
        }

        // 1) Sabitlenenler, sabitlenme sırasıyla (kaynakta artık yoksa atlanır).
        for (id in pinnedIds) {
            val c = contacts.firstOrNull { it.id == id }
                ?: if (id.startsWith("n:")) recents.firstOrNull { "n:" + normalizeNumber(it.number) == id }?.let { fromRecent(it) } else null
            if (c != null) add(c)
        }
        // 2) Yıldızlılar, Türkçe alfabe sırası.
        val collator = Collator.getInstance(Locale("tr", "TR"))
        contacts.filter { it.starred }.sortedWith { a, b -> collator.compare(a.name, b.name) }.forEach { add(it) }
        // 3) Az ise sıklık: giden/gelen 1, cevapsız 0,5; eşitlikte en yeni önce. 30 günden eskiler sayılmaz.
        if (out.size < FILL_BELOW) {
            class Score(val recent: PhoneRecent, var score: Double, var last: Long)
            val scores = LinkedHashMap<String, Score>()
            for (r in recents) {
                if (nowMs - r.timeMs > WINDOW_MS) continue
                val k = normalizeNumber(r.number)
                if (k.isEmpty()) continue
                val w = if (r.type == RecentType.MISSED) 0.5 else 1.0
                val s = scores[k]
                if (s == null) {
                    scores[k] = Score(r, w, r.timeMs)
                } else {
                    s.score += w
                    if (r.timeMs > s.last) {
                        s.last = r.timeMs
                    }
                }
            }
            scores.values.sortedWith(compareByDescending<Score> { it.score }.thenByDescending { it.last })
                .forEach { add(fromRecent(it.recent)) }
        }
        return out
    }
}

/** Son aramalar listesi: en yeni önce; 10 dk içinde art arda aynı numara+tür tek satır olur. */
internal object RecentsShaper {
    private const val COLLAPSE_MS = 10L * 60 * 1000

    fun shape(rows: List<PhoneRecent>, limit: Int = 5): List<PhoneRecent> {
        val out = ArrayList<PhoneRecent>()
        for (r in rows.sortedByDescending { it.timeMs }) {
            val last = out.lastOrNull()
            if (last != null && last.type == r.type &&
                FavoritesRanker.normalizeNumber(last.number) == FavoritesRanker.normalizeNumber(r.number) &&
                last.timeMs - r.timeMs <= COLLAPSE_MS
            ) {
                // Aynı kişiye art arda (ör. tekrar tekrar cevapsız): en yeni satır kalır, isim yoksa eskisinden alınır.
                if (last.name == null && r.name != null) out[out.size - 1] = last.copy(name = r.name)
                continue
            }
            if (out.size >= limit) break
            out.add(r)
        }
        return out
    }
}

/** Telefon eylemleri (yedek sırası için). */
internal enum class PhoneAction { DIAL, ANSWER, REJECT, HANG_UP }

/** Yeteneklere göre arka uç seçimi (sıra: demo → VDBus → Telecom → HFP istemci). */
internal object PhoneBackendChooser {
    data class Caps(
        val demo: Boolean = false,
        val vdbusBt: Boolean = false,
        val telecomHfpAccount: Boolean = false,
        val hfpClientProxy: Boolean = false,
        val contactsPermission: Boolean = false,
        val vdbusData: Boolean = false,
    )

    /**
     * Eylemin deneneceği arka uçlar, sırayla (ilki başarısızsa bir sonraki bir kez denenir). Telecom yalnızca arama
     * başlatabilir: cevaplama InCallService ister, kapatma ANSWER_PHONE_CALLS ister (istenmiyor).
     */
    fun fallbackOrder(c: Caps, action: PhoneAction): List<String> {
        if (c.demo) return listOf(PhoneBackendName.DEMO)
        val out = ArrayList<String>(3)
        if (c.vdbusBt) out.add(PhoneBackendName.VDBUS)
        if (action == PhoneAction.DIAL && c.telecomHfpAccount) out.add(PhoneBackendName.TELECOM)
        if (c.hfpClientProxy) out.add(PhoneBackendName.HFP_CLIENT)
        return out
    }

    fun callBackend(c: Caps): String = fallbackOrder(c, PhoneAction.DIAL).firstOrNull() ?: PhoneBackendName.NONE

    fun answerBackend(c: Caps): String = fallbackOrder(c, PhoneAction.ANSWER).firstOrNull() ?: PhoneBackendName.NONE

    fun dataBackend(c: Caps): String = when {
        c.demo -> PhoneBackendName.DEMO
        c.contactsPermission -> PhoneBackendName.CONTACTS_PROVIDER
        c.vdbusData -> PhoneBackendName.VDBUS
        else -> PhoneBackendName.NONE
    }

    fun assistantBackend(c: Caps): String = when {
        c.demo -> PhoneBackendName.DEMO
        c.hfpClientProxy -> PhoneBackendName.HFP_CLIENT
        else -> PhoneBackendName.NONE
    }

    /** Arama durumunu dinleyen kaynak: VDBus varsa o, yoksa HFP istemci yayını. */
    fun stateBackend(c: Caps): String = when {
        c.demo -> PhoneBackendName.DEMO
        c.vdbusBt -> PhoneBackendName.VDBUS
        c.hfpClientProxy -> PhoneBackendName.HFP_CLIENT
        else -> PhoneBackendName.NONE
    }

    fun permissionNeeded(c: Caps): Boolean = !c.demo && !c.contactsPermission && !c.vdbusData
}
