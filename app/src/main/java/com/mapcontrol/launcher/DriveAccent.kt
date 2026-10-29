package com.mapcontrol.launcher

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Sürüş moduna göre launcher'ın vurgu rengi (salt-okur; araca hiçbir şey yazılmaz).
 * Mod ham değerleri aracınkiyle aynı: 0 Eko, 1 Normal, 2 Spor, 3 Kar, 4 Çamur, 5 Kum, 6 Arazi.
 * Yalnızca vurgu değişir; zemin, kart ve metin renkleri aynı kalır. Normal mod varsayılan vurguyu korur.
 *
 * Renkler beyaz yazıyla okunur kalacak şekilde seçildi: gündüz >= 4.5:1, gece >= 3:1 (büyük/kalın yazı).
 */
object DriveAccent {
    const val ECO = 0
    const val NORMAL = 1
    const val SPORT = 2
    const val SNOW = 3
    const val MUD = 4
    const val SAND = 5
    const val OFFROAD = 6

    /** Mod başına (gece, gündüz) vurgu; Normal listede yok = temanın kendi vurgusu. */
    private val ACCENTS: Map<Int, Pair<Int, Int>> = mapOf(
        ECO to (0xFF1F9D55.toInt() to 0xFF15803D.toInt()),      // sakin yeşil
        SPORT to (0xFFEA580C.toInt() to 0xFFC2410C.toInt()),    // sıcak kırmızı-turuncu
        SNOW to (0xFF0891B2.toInt() to 0xFF0E7490.toInt()),     // buz mavisi
        MUD to (0xFF9A6B43.toInt() to 0xFF7A4E2D.toInt()),      // toprak kahvesi
        SAND to (0xFFB7791F.toInt() to 0xFFA16207.toInt()),     // kum / kehribar
        OFFROAD to (0xFF6B7F23.toInt() to 0xFF556B1C.toInt()), // zeytin
    )

    /** Moda özgü vurgu; Normal ve bilinmeyen modda null (temanın varsayılanı kullanılır). */
    @JvmStatic
    fun accentFor(mode: Int, night: Boolean): Int? =
        ACCENTS[mode]?.let { if (night) it.first else it.second }

    /** Rozet gösterilecek mi: Normal ve bilinmeyen modda gösterilmez. */
    @JvmStatic
    fun hasBadge(mode: Int): Boolean = mode in ACCENTS

    @StringRes
    fun label(mode: Int): Int = when (mode) {
        ECO -> R.string.launcher_drive_eco
        SPORT -> R.string.launcher_drive_sport
        SNOW -> R.string.launcher_drive_snow
        MUD -> R.string.launcher_drive_mud
        SAND -> R.string.launcher_drive_sand
        OFFROAD -> R.string.launcher_drive_offroad
        else -> R.string.launcher_drive_normal
    }

    @DrawableRes
    fun icon(mode: Int): Int = when (mode) {
        ECO -> R.drawable.ic_mdi_leaf
        SPORT -> R.drawable.ic_mdi_car_sports
        SNOW -> R.drawable.ic_mdi_snowflake
        else -> R.drawable.ic_mdi_terrain
    }

    private const val PREFS_NAME = "launcher_settings"
    private const val KEY_ENABLED = "drive_mode_theme"

    /** Kullanıcı seçeneği (profil başına, varsayılan açık). */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(Profiles.key(context, KEY_ENABLED), true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(Profiles.key(context, KEY_ENABLED), on).apply()
    }
}

/**
 * Sürüş modu geçişi için saf bekleme (debounce): gözlenen mod [stableMs] boyunca değişmeden kalırsa ve
 * geçişe izin varsa yeni modu döndürür (yoksa null). Gözlem uygulanan moda dönerse ya da başka moda geçerse
 * sayaç sıfırlanır. [observed] null (veri yok) uygulanan modu korur.
 */
class DriveModePolicy(private val stableMs: Long) {
    private var applied = DriveAccent.NORMAL
    private var candidate: Int? = null
    private var since = 0L

    fun reset(current: Int) {
        applied = current
        candidate = null
    }

    fun update(now: Long, observed: Int?, canSwitch: Boolean): Int? {
        if (observed == null || observed == applied) {
            candidate = null
            return null
        }
        if (candidate != observed) {
            candidate = observed
            since = now
        }
        if (now - since < stableMs || !canSwitch) return null
        applied = observed
        candidate = null
        return observed
    }
}
