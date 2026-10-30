package com.mapcontrol.hud

/*
 * Bildirim (ana kaynak) ve erişilebilirlik (isteğe bağlı) verisini tek [HudState]'e birleştirir. SAF kod.
 * Bildirim: manevra mesafesi, sokak, ikon. Erişilebilirlik: toplam mesafe; bildirim bayatsa mesafe/sokak yedeği.
 * Tek iş parçacığından çağrılır (HudBridgeController'ın yürütücüsü).
 */
class HudNavMerger(
    private val notifFreshMs: Long = 5000L,
    private val a11yFreshMs: Long = 3000L,
) {
    private var notif: NavUpdate? = null
    private var notifMs = 0L
    private var a11y: HudState? = null
    private var a11yMs = 0L

    fun onNotification(u: NavUpdate, nowMs: Long) {
        notif = u
        notifMs = nowMs
    }

    fun clearNotification() {
        notif = null
    }

    /** null: erişilebilirlik rehberlik yok dedi. */
    fun onAccessibility(s: HudState?, nowMs: Long) {
        a11y = s
        a11yMs = nowMs
    }

    fun reset() {
        notif = null
        a11y = null
    }

    /** Birleşik durum; hiçbir şey yoksa (ya da yalnız yol adı varsa) null. Bayat kaynaklar düşer. */
    fun state(nowMs: Long): HudState? {
        if (notif != null && nowMs - notifMs > notifFreshMs) notif = null
        if (a11y != null && nowMs - a11yMs > a11yFreshMs) a11y = null
        val n = notif
        val a = a11y
        val man = n?.distanceM?.coerceIn(0L, 16_000_000L)?.toInt() ?: a?.maneuverMeters
        val total = a?.totalMeters
        if (man == null && total == null) return null
        return HudState(n?.street ?: a?.road, man, total)
    }

    /** Geçerli bildirimin HUD ikon numarası; bildirim yoksa/manevra bilinmiyorsa null (HUD'a 0 gider). */
    fun icon(): Int? {
        val n = notif ?: return null
        val icon = HudManeuverIcons.hudIcon(n.maneuver, n.roundaboutExit)
        return if (icon == HudManeuverIcons.UNKNOWN) null else icon
    }
}
