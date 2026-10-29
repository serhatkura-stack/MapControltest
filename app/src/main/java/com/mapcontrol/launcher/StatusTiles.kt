package com.mapcontrol.launcher

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R
import com.mapcontrol.vehicle.DriveModeStyle
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

/**
 * Yalnızca durum gösteren araç kutucukları: veriyi [VehicleMetricsRepository] anlık görüntüsünden okur,
 * araca hiçbir komut göndermez. Sinyal gelmezse "Okunamıyor" der, tahmin yürütmez.
 */
internal abstract class MetricsTile(
    context: Context,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
) : VehicleTile(context, iconRes, labelRes) {

    private val metrics = VehicleMetricsRepository(context)
    protected var snapshot: VehicleMetricsSnapshot = metrics.currentSnapshot()
        private set
    private val listener = VehicleMetricsRepository.Listener {
        snapshot = it
        bind()
    }

    override fun onVisible() {
        snapshot = metrics.currentSnapshot()
        bind()
        metrics.addListener(listener)
    }

    override fun onHidden() {
        metrics.removeListener(listener)
    }

    override fun release() {
        metrics.release()
    }

    override fun bindDetail() = bind()

    /** Değeri ve (geniş boyutta) ikinci satırı [snapshot]'tan yeniden kurar. */
    protected abstract fun bind()

    protected fun showUnavailable() {
        setValue(R.string.launcher_unavailable, LauncherTheme.TEXT_SECONDARY)
        showDetail(null)
    }
}

/** Sürüş modu: Eko, Normal, Spor ve arazi modları. İkon rengi aracın mod rengini alır. */
internal class DriveModeTile(context: Context) :
    MetricsTile(context, R.drawable.ic_mdi_car_sports, R.string.launcher_widget_drive_mode) {

    override fun bind() {
        val mode = snapshot.driveMode
        val label = if (snapshot.connected && snapshot.hasDriveMode()) modeLabel(mode) else null
        if (label == null) {
            setIcon(R.drawable.ic_mdi_car_sports, LauncherTheme.TEXT_SECONDARY)
            showUnavailable()
            return
        }
        val tint = if (DriveModeStyle.isThemed(mode)) DriveModeStyle.accentColor(context, mode) else LauncherTheme.TEXT_SECONDARY
        setIcon(R.drawable.ic_mdi_car_sports, tint)
        setValue(label, LauncherTheme.TEXT)
        showDetail(null)
    }

    private fun modeLabel(mode: Int): String? = when (mode) {
        DriveModeStyle.ECO -> R.string.launcher_drive_eco
        DriveModeStyle.NORMAL -> R.string.launcher_drive_normal
        DriveModeStyle.SPORT -> R.string.launcher_drive_sport
        MODE_SNOW -> R.string.launcher_drive_snow
        MODE_MUD -> R.string.launcher_drive_mud
        MODE_OFFROAD -> R.string.launcher_drive_offroad
        MODE_SAND -> R.string.launcher_drive_sand
        else -> null
    }?.let(context::getString)

    private companion object {
        // DriveModeStyle.shortLabel ile aynı araç değerleri.
        const val MODE_SNOW = 3
        const val MODE_MUD = 4
        const val MODE_OFFROAD = 5
        const val MODE_SAND = 7
    }
}

/** Kapılar ve bagaj: hepsi kapalıysa "Kapalı"; açık olan varsa uyarı rengi ve geniş boyutta hangileri. */
internal class DoorsTile(context: Context) :
    MetricsTile(context, R.drawable.ic_mdi_car_door, R.string.launcher_widget_doors) {

    override fun bind() {
        val s = snapshot
        val known = s.hasDoorLf() || s.hasDoorRf() || s.hasDoorLr() || s.hasDoorRr() || s.hasTrunk()
        if (!s.connected || !known) {
            setIcon(R.drawable.ic_mdi_car_door, LauncherTheme.TEXT_SECONDARY)
            showUnavailable()
            return
        }
        val open = buildList {
            if (s.isDoorLfOpen()) add(R.string.launcher_door_lf)
            if (s.isDoorRfOpen()) add(R.string.launcher_door_rf)
            if (s.isDoorLrOpen()) add(R.string.launcher_door_lr)
            if (s.isDoorRrOpen()) add(R.string.launcher_door_rr)
            if (s.isTrunkOpen()) add(R.string.launcher_trunk)
        }
        if (open.isEmpty()) {
            setIcon(R.drawable.ic_mdi_car_door, LauncherTheme.TEXT_SECONDARY)
            setValue(R.string.launcher_closed, LauncherTheme.TEXT)
            showDetail(null)
        } else {
            setIcon(R.drawable.ic_mdi_car_door, LauncherTheme.ALERT)
            setValue(R.string.launcher_open, LauncherTheme.ALERT)
            showDetail(open.joinToString(", ") { context.getString(it) })
        }
    }
}

/** Cam tavan: açık / kapalı / hareket halinde; geniş boyutta perde ve yağmur bilgisi. Tavansız araçta "Yok". */
internal class SunroofTile(context: Context) :
    MetricsTile(context, R.drawable.ic_mdi_car_sunroof, R.string.launcher_widget_sunroof) {

    override fun bind() {
        val s = snapshot
        if (!s.connected) {
            setIcon(R.drawable.ic_mdi_car_sunroof, LauncherTheme.TEXT_SECONDARY)
            showUnavailable()
            return
        }
        if (!s.hasSunroof()) {
            setIcon(R.drawable.ic_mdi_car_sunroof, LauncherTheme.TEXT_SECONDARY)
            setValue(R.string.launcher_none, LauncherTheme.TEXT_SECONDARY)
            showDetail(null)
            return
        }
        val open = s.sunroofOpenOrNull()
        val raining = s.isRaining()
        // Yağmur yağarken açık tavan tek gerçek uyarı.
        val alert = open == true && raining
        setIcon(R.drawable.ic_mdi_car_sunroof, if (alert) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        when (open) {
            true -> setValue(R.string.launcher_open, if (alert) LauncherTheme.ALERT else LauncherTheme.TEXT)
            false -> setValue(R.string.launcher_closed, LauncherTheme.TEXT)
            null -> setValue(R.string.launcher_moving, LauncherTheme.TEXT)
        }
        val detail = buildList {
            if (s.isSunshadeOpen()) add(context.getString(R.string.launcher_shade_open))
            if (raining) add(context.getString(R.string.launcher_raining))
        }.joinToString(" · ")
        showDetail(detail.ifEmpty { null }, if (alert) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
    }
}
