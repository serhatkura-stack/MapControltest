package com.mapcontrol.launcher

import android.content.res.Configuration

/**
 * Yeni launcher'ın tasarım değerleri (gündüz ve gece görünümü).
 * Tek vurgu rengi var: [ACCENT] yalnızca birincil/aktif öğede, [ALERT] yalnızca uyarıda (her zaman ikonla).
 */
object LauncherTheme {
    /** Gündüz/gece seçimi; [applyFrom] ile ayarlanır. Okunmadan önce etkinlik başında çağrılmalı. */
    @JvmStatic
    var night: Boolean = true

    private const val NIGHT_GROUND = 0xFF0F1115.toInt()
    private const val DAY_GROUND = 0xFFECEEF2.toInt()
    private const val NIGHT_CARD = 0xFF1A1D23.toInt()
    private const val DAY_CARD = 0xFFFFFFFF.toInt()
    private const val NIGHT_CONTROL = 0xFF22262E.toInt()
    private const val DAY_CONTROL = 0xFFE3E6EB.toInt()
    private const val NIGHT_TRACK = 0xFF2A2F38.toInt()
    private const val DAY_TRACK = 0xFFCDD2DA.toInt()
    private const val NIGHT_ACCENT = 0xFF3B82F6.toInt()
    private const val DAY_ACCENT = 0xFF2563EB.toInt()
    private const val NIGHT_ALERT = 0xFFEF4444.toInt()
    private const val DAY_ALERT = 0xFFDC2626.toInt()
    private const val NIGHT_TEXT = 0xFFE5E7EB.toInt()
    private const val DAY_TEXT = 0xFF111827.toInt()
    private const val NIGHT_TEXT_SECONDARY = 0xFF9CA3AF.toInt()
    private const val DAY_TEXT_SECONDARY = 0xFF4B5563.toInt()
    private const val NIGHT_ALERT_SURFACE = 0xFF3A1F22.toInt()
    private const val DAY_ALERT_SURFACE = 0xFFFDE8E8.toInt()
    // Düzenleme örtüsü opak (kart rengi): düzenlerken yalnızca widget adı, boyutlar ve kaldır düğmesi görünür.
    private const val NIGHT_EDIT_SCRIM = NIGHT_CARD
    private const val DAY_EDIT_SCRIM = DAY_CARD

    val GROUND: Int get() = if (night) NIGHT_GROUND else DAY_GROUND
    val CARD: Int get() = if (night) NIGHT_CARD else DAY_CARD
    val CONTROL: Int get() = if (night) NIGHT_CONTROL else DAY_CONTROL
    val TRACK: Int get() = if (night) NIGHT_TRACK else DAY_TRACK
    val ACCENT: Int get() = if (night) NIGHT_ACCENT else DAY_ACCENT
    val ALERT: Int get() = if (night) NIGHT_ALERT else DAY_ALERT
    val TEXT: Int get() = if (night) NIGHT_TEXT else DAY_TEXT
    val TEXT_SECONDARY: Int get() = if (night) NIGHT_TEXT_SECONDARY else DAY_TEXT_SECONDARY

    /** Vurgu rengi üstündeki yazı/ikon; iki görünümde de beyaz. */
    val ON_ACCENT: Int get() = 0xFFFFFFFF.toInt()

    /** Uyarı durumundaki kart zemini (ör. lastik uyarısı). */
    val ALERT_SURFACE: Int get() = if (night) NIGHT_ALERT_SURFACE else DAY_ALERT_SURFACE

    /** Düzenleme modunda widget içeriğinin üstüne gelen örtü; içerik etiketlerle çakışmasın diye çok opak. */
    val EDIT_SCRIM: Int get() = if (night) NIGHT_EDIT_SCRIM else DAY_EDIT_SCRIM

    /** Sistemin gündüz/gece durumunu okur. Bilinmeyen durum gece sayılır (araçta güvenli varsayılan). */
    fun applyFrom(config: Configuration) {
        night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
    }

    const val CARD_RADIUS_DP = 20f
    const val GAP_DP = 24
    const val PADDING_DP = 32

    /** Sürüşte kullanılan dokunma hedefleri en az bu boyutta. */
    const val TOUCH_MIN_DP = 72
    const val MEDIA_BUTTON_DP = 88
    const val PLAY_BUTTON_DP = 104

    const val HEADER_HEIGHT_DP = 72
    const val STATUS_HEIGHT_DP = 40
    const val CLIMATE_HEIGHT_DP = 120

    /** Saat, sıcaklık gibi rakamlar sp cinsinden; etiketler küçük, rakamlar büyük. */
    const val TIME_SP = 56f
    const val TITLE_SP = 52f
    const val TEMP_SP = 44f
    const val SECTION_SP = 36f
    const val BODY_SP = 24f
    const val LABEL_SP = 18f

    const val PRESS_SCALE = 0.98f
    const val PRESS_DURATION_MS = 120L
    const val CROSSFADE_MS = 150L
}
