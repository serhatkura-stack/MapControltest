package com.mapcontrol.launcher

import android.content.Context
import com.mapcontrol.R
import java.text.NumberFormat
import java.util.Locale

/**
 * Bakım: servise kalan km (büyük) ve gün (geniş boyutta detay). Yalnızca okur; değerler [TripSource] turundan gelir.
 *
 * ICMT_DISTANCE / ICMT_DAY araçta 2026-10-29'da okundu (ham [15,138] -> 3978 km, 155 gün);
 * gösterge menüsündeki bakım sayfasıyla henüz karşılaştırılmadı.
 * Sakin kalır; <= 1000 km ya da <= 30 gün kaldığında uyarı rengi ve simgesine geçer.
 */
internal class ServiceTile(context: Context) :
    TripTile(context, R.drawable.ic_mdi_wrench, R.string.launcher_widget_service) {

    override fun bind() {
        val km = data.serviceKm
        val days = data.serviceDays
        if (!data.connected || (km == null && days == null)) {
            showMissing()
            return
        }
        val soon = (km != null && km <= SOON_KM) || (days != null && days <= SOON_DAYS)
        val color = if (soon) LauncherTheme.ALERT else LauncherTheme.TEXT
        setIcon(if (soon) R.drawable.ic_mdi_alert else R.drawable.ic_mdi_wrench,
            if (soon) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        val dayText = days?.let { context.getString(R.string.launcher_days_value, it) }
        if (km != null) {
            val grouped = NumberFormat.getIntegerInstance(Locale.getDefault()).format(km)
            setValue(context.getString(R.string.launcher_km_value, grouped), color)
            showDetail(dayText, if (soon) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        } else {
            setValue(dayText.orEmpty(), color)
            showDetail(null)
        }
    }

    private companion object {
        const val SOON_KM = 1_000
        const val SOON_DAYS = 30
    }
}

/**
 * Hava kalitesi: kabin içi PM2.5'e göre kelime ("İyi" <= 35, "Orta" <= 75, "Kötü" > 75 µg/m³), geniş boyutta
 * sayılar. Dışarısı "Kötü" ve içerisi daha iyiyse yalnızca metin ipucu: "İç sirkülasyon önerilir" (araca bir şey gönderilmez).
 */
internal class AirQualityTile(context: Context) :
    TripTile(context, R.drawable.ic_mdi_air_filter, R.string.launcher_widget_air) {

    override fun bind() {
        val inside = data.pm25In
        val outside = data.pm25Out
        if (!data.connected || inside == null) {
            showMissing()
            return
        }
        val poor = grade(inside) == POOR
        setIcon(if (poor) R.drawable.ic_mdi_alert else R.drawable.ic_mdi_air_filter,
            if (poor) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        setValue(gradeLabel(inside), if (poor) LauncherTheme.ALERT else LauncherTheme.TEXT)
        val outPoor = outside != null && grade(outside) == POOR && outside > inside
        val lines = listOfNotNull(
            context.getString(R.string.launcher_air_in, inside),
            when {
                outside == null -> null
                outPoor -> context.getString(R.string.launcher_air_out_poor, outside)
                else -> context.getString(R.string.launcher_air_out, outside)
            },
            if (outPoor) context.getString(R.string.launcher_air_recirc_hint) else null,
        )
        // Detay en çok 2 satır: dış hava kötüyse içeri satırı ipucuna yer açar.
        showDetail((if (outPoor) lines.drop(1) else lines).joinToString("\n"))
    }

    private fun grade(pm: Int) = when {
        pm <= GOOD_MAX -> GOOD
        pm <= MODERATE_MAX -> MODERATE
        else -> POOR
    }

    private fun gradeLabel(pm: Int) = context.getString(when (grade(pm)) {
        GOOD -> R.string.launcher_air_good
        MODERATE -> R.string.launcher_air_moderate
        else -> R.string.launcher_air_poor
    })

    private companion object {
        const val GOOD_MAX = 35
        const val MODERATE_MAX = 75
        const val GOOD = 0
        const val MODERATE = 1
        const val POOR = 2
    }
}
