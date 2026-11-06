package com.mapcontrol.launcher

import androidx.annotation.StringRes
import com.mapcontrol.R
import java.util.Calendar
import java.util.TimeZone

/**
 * Telefon arayüzünün saf kararları (JVM testli, [PhoneUiLogicTest]): panelin hangi hâli göstereceği, alt çubuktaki telefon
 * düğmesinin ne yapacağı, arama şeridinin kipi, süre ve göreli zaman biçimi, favori sütun sayısı. İsim ve numara yalnızca
 * ekrana gider; burada da günlüğe yazılmaz.
 */
internal object PhoneUiLogic {

    /** Telefon panelinin gövdesi: bağlı telefon yok · izin gerekli · yükleniyor · boş · içerik (favoriler + son aramalar). */
    enum class PanelState { NO_PHONE, PERMISSION, LOADING, EMPTY, CONTENT }

    /** Alt çubuk telefon düğmesi: hiçbir şey (panel zaten ekranda) · telefon sayfası · OEM telefon uygulaması. */
    enum class PhoneButtonAction { NONE, SHEET, OEM_APP }

    /** Arama şeridi kipi. */
    enum class BannerMode { HIDDEN, INCOMING, DIALING, ACTIVE, ENDED }

    /** Göreli zaman (metin kaynağı UI'da seçilir): şimdi · n dk · n sa · dün · gün + ay (0 tabanlı). */
    sealed class RelTime {
        object Now : RelTime()
        data class Minutes(val n: Int) : RelTime()
        data class Hours(val n: Int) : RelTime()
        object Yesterday : RelTime()
        data class Date(val day: Int, val monthIndex: Int) : RelTime()
    }

    /** Son arama satırı: gösterilecek ad (yoksa numara), aranacak numara, cevapsız mı. */
    data class RecentRow(val type: RecentType, val label: String, val number: String, val timeMs: Long) {
        val missed: Boolean get() = type == RecentType.MISSED
        override fun toString(): String = "RecentRow(type=$type)"
    }

    /** Bir favori hücresinin en küçük genişliği (dp): 72dp kare avatar + yanlarda pay; dokunma alanı ≥ 76dp kalır. */
    const val FAVORITE_CELL_MIN_DP = 96
    const val FAVORITE_MAX = 6
    /** Dar panelde bile en az bu kadar sütun (440dp panelde 4 sütun sığar; daha darsa hücre küçülmez, sütun azalır). */
    const val FAVORITE_MIN = 3

    fun panelState(s: PhoneSnapshot): PanelState = when {
        !s.connected -> PanelState.NO_PHONE
        s.permissionNeeded -> PanelState.PERMISSION
        !s.loaded -> PanelState.LOADING
        s.favorites.isEmpty() && s.recents.isEmpty() -> PanelState.EMPTY
        else -> PanelState.CONTENT
    }

    /**
     * Alt çubuk telefon dokunuşu: telefon paneli ekrandaysa bir şey yapılmaz (aynı içerik zaten görünür); telefon bağlı
     * değilse sayfa ("Telefon bağlı değil" + Bluetooth düğmesi); bağlı ama arama yolu yoksa OEM telefon uygulaması; yoksa sayfa.
     */
    fun phoneButtonAction(panelOnScreen: Boolean, connected: Boolean, canDial: Boolean): PhoneButtonAction = when {
        panelOnScreen -> PhoneButtonAction.NONE
        !connected -> PhoneButtonAction.SHEET
        !canDial -> PhoneButtonAction.OEM_APP
        else -> PhoneButtonAction.SHEET
    }

    fun bannerMode(call: CallState): BannerMode = when (call.kind) {
        CallKind.NONE -> BannerMode.HIDDEN
        CallKind.INCOMING -> BannerMode.INCOMING
        CallKind.DIALING -> BannerMode.DIALING
        CallKind.ACTIVE -> BannerMode.ACTIVE
        CallKind.ENDED -> BannerMode.ENDED
    }

    /** Konuşma süresi: "0:05", "1:23", "12:00", "1:02:03" (eksi süre 0 sayılır). */
    fun formatDuration(elapsedMs: Long): String {
        val total = (elapsedMs.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    /**
     * Arama zamanının göreli hâli: 1 dk'dan yeni (ya da saat ileri kaymış) "şimdi"; 1 saatten yeni dakika; aynı takvim günü
     * saat; bir önceki gün "dün"; daha eskisi gün + ay.
     */
    fun relativeTime(nowMs: Long, timeMs: Long, zone: TimeZone = TimeZone.getDefault()): RelTime {
        val diff = nowMs - timeMs
        if (diff < 60_000L) return RelTime.Now
        if (diff < 3_600_000L) return RelTime.Minutes((diff / 60_000L).toInt())
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val then = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        if (sameDay(now, then)) return RelTime.Hours((diff / 3_600_000L).toInt())
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(yesterday, then)) return RelTime.Yesterday
        return RelTime.Date(then.get(Calendar.DAY_OF_MONTH), then.get(Calendar.MONTH))
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** Favori satırında kaç hücre: her hücre ≥ [FAVORITE_CELL_MIN_DP]; en az [FAVORITE_MIN], en çok [FAVORITE_MAX]. */
    fun favoriteColumns(widthDp: Int): Int = (widthDp / FAVORITE_CELL_MIN_DP).coerceIn(FAVORITE_MIN, FAVORITE_MAX)

    /** Son aramalar satırları (arka uç sıralı ve sıkıştırılmış verir): en çok [max]; ad yoksa ya da boşsa numara gösterilir. */
    fun recentRows(recents: List<PhoneRecent>, max: Int = 5): List<RecentRow> =
        recents.take(max).map { r -> RecentRow(r.type, r.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.number, r.number, r.timeMs) }

    /**
     * Uzun basışla sabitlenebilir mi: yalnızca rehber kimlikleri. Yalnız geçmişten gelen kişilerin kimliği numarayı içerir
     * ("n:…"); sabitleme ayara yazıldığı için bunlar sabitlenmez (ayara numara yazılmaz).
     */
    fun canPin(id: String): Boolean = id.isNotBlank() && !id.startsWith("n:")

    /** Sabitlemeyi aç/kapat: listede varsa çıkar, yoksa sona ekle. */
    fun togglePin(pinned: List<String>, id: String): List<String> = if (id in pinned) pinned - id else pinned + id

    /** Hata metni. */
    @StringRes
    fun errorRes(error: PhoneError): Int = when (error) {
        PhoneError.NO_PHONE -> R.string.launcher_call_no_phone
        PhoneError.NOT_SUPPORTED -> R.string.launcher_call_not_supported
        PhoneError.FAILED -> R.string.launcher_call_failed
        PhoneError.ASSISTANT_UNAVAILABLE -> R.string.launcher_phone_assistant_failed
        PhoneError.PERMISSION -> R.string.launcher_phone_permission
    }
}
