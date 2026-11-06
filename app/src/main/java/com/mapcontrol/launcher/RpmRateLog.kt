package com.mapcontrol.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * YALNIZCA DEBUG: spor hızlı okuyucusunun ([SportFastReader]) motor devri (ReadOnly 62) güncelleme hızını ölçer.
 *
 *   adb shell am start -n <paket>/com.mapcontrol.launcher.HomeLauncherActivity --ez launcher_rpm_log true
 *   adb logcat -s RpmRate
 *
 * Açıkken ham 62 değerinin her değişimi bir önceki değişimden bu yana geçen ms ve kaynağıyla (push = CarInfo
 * aboneliği, poll = getOnce) loglanır; 5 sn'de bir özet (değişim/sn, en kısa/en uzun aralık, push olay sayısı).
 * `false` kapatır. Demo kipinden bağımsızdır (gerçek araç verisi ölçülür); debuggable olmayan derlemede açılmaz.
 */
internal object RpmRateLog {
    const val EXTRA = "launcher_rpm_log"
    const val TAG = "RpmRate"
    const val SUMMARY_MS = 5_000L

    @Volatile
    var enabled = false
        private set

    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        enabled = debuggable && intent.getBooleanExtra(EXTRA, false)
        android.util.Log.i(TAG, if (enabled) "ölçüm açık (spor ekranı görünürken)" else "ölçüm kapalı")
    }

    /** Değişim satırı; [gapMs] < 0 ise ilk örnek. */
    fun changeLine(raw: Int, rpm: Int, gapMs: Long, src: RpmSource): String =
        if (gapMs < 0) "raw=$raw rpm=$rpm gap=- src=${src.key}" else "raw=$raw rpm=$rpm gap=${gapMs}ms src=${src.key}"

    fun summaryLine(s: RpmRateSummary): String {
        val rate = String.format(java.util.Locale.US, "%.2f", s.changesPerSec)
        val gaps = if (s.minGapMs < 0) "min=- max=-" else "min=${s.minGapMs}ms max=${s.maxGapMs}ms"
        return "özet ${s.windowMs}ms: değişim=${s.changes} (${rate}/sn) $gaps " +
            "push=${s.pushChanges} poll=${s.pollChanges} pushOlay=${s.pushEvents} abonelik=${if (s.subscribed) "var" else "yok"}"
    }
}

internal enum class RpmSource(val key: String) { PUSH("push"), POLL("poll") }

internal data class RpmRateSummary(
    val windowMs: Long,
    val changes: Int,
    val minGapMs: Long,
    val maxGapMs: Long,
    val pushChanges: Int,
    val pollChanges: Int,
    val pushEvents: Int,
    val subscribed: Boolean,
) {
    val changesPerSec: Double get() = if (windowMs <= 0) 0.0 else changes * 1000.0 / windowMs
}

/**
 * Devir değişim istatistiği (saf mantık, birim testli). Push (binder) ve poll (yürütücü) iş parçacıklarından
 * çağrılabilir; hepsi kilitli.
 */
internal class RpmRateStats {
    private var lastRaw: Int? = null
    private var lastChangeMs = -1L
    private var windowStart = -1L
    private var changes = 0
    private var minGap = -1L
    private var maxGap = -1L
    private var pushChanges = 0
    private var pollChanges = 0
    private var pushEvents = 0

    /**
     * Yeni ham değer. Değişmediyse null; değiştiyse önceki değişimden bu yana ms (ilk örnekte -1).
     * İlk örnek değişim sayılmaz (pencere ondan başlar).
     */
    @Synchronized
    fun onSample(nowMs: Long, raw: Int, src: RpmSource): Long? {
        if (windowStart < 0) windowStart = nowMs
        if (src == RpmSource.PUSH) pushEvents++
        val prev = lastRaw
        if (prev == raw) return null
        lastRaw = raw
        if (prev == null) { lastChangeMs = nowMs; return -1L }
        val gap = nowMs - lastChangeMs
        lastChangeMs = nowMs
        changes++
        if (src == RpmSource.PUSH) pushChanges++ else pollChanges++
        if (minGap < 0 || gap < minGap) minGap = gap
        if (gap > maxGap) maxGap = gap
        return gap
    }

    /** Pencereyi kapatır ve sıfırlar (son değer ve son değişim zamanı korunur). Hiç örnek yoksa null. */
    @Synchronized
    fun drain(nowMs: Long, subscribed: Boolean): RpmRateSummary? {
        if (windowStart < 0) return null
        val s = RpmRateSummary(nowMs - windowStart, changes, minGap, maxGap, pushChanges, pollChanges, pushEvents, subscribed)
        windowStart = nowMs
        changes = 0; minGap = -1; maxGap = -1; pushChanges = 0; pollChanges = 0; pushEvents = 0
        return s
    }

    @Synchronized
    fun reset() {
        lastRaw = null; lastChangeMs = -1; windowStart = -1
        changes = 0; minGap = -1; maxGap = -1; pushChanges = 0; pollChanges = 0; pushEvents = 0
    }
}
