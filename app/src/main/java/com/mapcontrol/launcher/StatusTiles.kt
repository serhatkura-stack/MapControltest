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
    protected var snapshot: VehicleMetricsSnapshot = current()
        private set
    private val listener = VehicleMetricsRepository.Listener {
        if (LauncherDemo.enabled) return@Listener
        snapshot = it
        bind()
    }

    private fun current() = if (LauncherDemo.enabled) LauncherDemo.vehicle() else metrics.currentSnapshot()

    override fun onVisible() {
        snapshot = current()
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
}

/** Sürüş modu: Eko, Normal, Spor ve arazi modları. İkon rengi aracın mod rengini alır. */
internal class DriveModeTile(context: Context) :
    MetricsTile(context, R.drawable.ic_mdi_car_sports, R.string.launcher_widget_drive_mode) {

    override fun bind() {
        val mode = snapshot.driveMode
        val label = if (snapshot.connected && snapshot.hasDriveMode()) modeLabel(mode) else null
        if (label == null) {
            setIcon(R.drawable.ic_mdi_car_sports, LauncherTheme.TEXT_SECONDARY)
            showMissing()
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
        const val MODE_OFFROAD = 6
        const val MODE_SAND = 5
    }
}
