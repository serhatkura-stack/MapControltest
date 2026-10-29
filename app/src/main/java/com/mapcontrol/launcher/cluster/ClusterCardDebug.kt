package com.mapcontrol.launcher.cluster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

/**
 * Katman A hata ayıklama girişi (salt-okur). Tetik: `--ez launcher_cluster_test true`
 * ve FLAG_DEBUGGABLE. `false` verilirse kartı kaldırır. SYSTEM_ALERT_WINDOW yoksa
 * yalnızca günlüğe yazar, izin istemez.
 *
 * Ek hata ayıklama ekstraları (yalnızca launcher_cluster_test mevcutken):
 *  `--ei launcher_cluster_x <px>` / `--ei launcher_cluster_y <px>`: pencere sol-üst konumu (canlı).
 *  `--ez launcher_cluster_grid true`: tüm ekranı kaplayan kalibrasyon ızgarası.
 */
object ClusterCardDebug {
    const val EXTRA = "launcher_cluster_test"
    const val EXTRA_X = "launcher_cluster_x"
    const val EXTRA_Y = "launcher_cluster_y"
    const val EXTRA_GRID = "launcher_cluster_grid"
    private const val TAG = ClusterSurface.TAG

    private val main = Handler(Looper.getMainLooper())
    private var surface: ClusterSurface? = null
    private var media: LauncherMediaController? = null
    private var metrics: VehicleMetricsRepository? = null
    private var mediaListener: LauncherMediaController.Listener? = null
    private var metricsListener: VehicleMetricsRepository.Listener? = null
    private var appContext: Context? = null
    private var mediaState: LauncherMediaController.MediaState? = null
    private var snapshot: VehicleMetricsSnapshot? = null
    private var maneuver: ClusterSurface.Card.Maneuver? = null

    @JvmStatic
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA)) return
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val ctx = activity.applicationContext
        main.post {
            if (!intent.getBooleanExtra(EXTRA, false)) {
                stop()
            } else if (!Settings.canDrawOverlays(ctx)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; doing nothing")
            } else {
                start(ctx)
                surface?.apply {
                    setPositionOverride(
                        if (intent.hasExtra(EXTRA_X)) intent.getIntExtra(EXTRA_X, 0) else null,
                        if (intent.hasExtra(EXTRA_Y)) intent.getIntExtra(EXTRA_Y, 0) else null,
                    )
                    setGrid(intent.getBooleanExtra(EXTRA_GRID, false))
                }
            }
        }
    }

    /** Sonraki manevra kancası. Henüz veri kaynağı yok; kullanılmıyor. null = kaldır. */
    @Suppress("unused")
    fun setManeuver(text: String?, distance: String = "") {
        maneuver = text?.takeIf { it.isNotBlank() }?.let { ClusterSurface.Card.Maneuver(it, distance) }
        main.post { push() }
    }

    private fun start(ctx: Context) {
        if (surface != null) return
        appContext = ctx
        val s = ClusterSurface(ctx)
        surface = s
        val m = LauncherMediaController(ctx)
        val ml = LauncherMediaController.Listener { st -> main.post { mediaState = st; push() } }
        m.addListener(ml)
        m.start()
        media = m
        mediaListener = ml
        mediaState = m.currentState()
        val r = VehicleMetricsRepository.getInstance(ctx)
        val rl = VehicleMetricsRepository.Listener { sn -> main.post { snapshot = sn; push() } }
        r.addListener(rl)
        metrics = r
        metricsListener = rl
        snapshot = r.currentSnapshot()
        Log.i(TAG, "cluster card test started")
        s.show(buildCard())
    }

    private fun stop() {
        val s = surface ?: return
        mediaListener?.let { media?.removeListener(it) }
        media?.stop()
        metricsListener?.let { metrics?.removeListener(it) }
        s.hide()
        surface = null; media = null; metrics = null
        mediaListener = null; metricsListener = null
        Log.i(TAG, "cluster card test stopped")
    }

    private fun push() {
        surface?.show(buildCard())
    }

    private fun appLabel(ctx: Context, pkg: String?): String {
        if (pkg.isNullOrBlank()) return ctx.getString(R.string.cluster_card_media)
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.')
        }
    }

    private fun buildCard(): ClusterSurface.Card {
        val ctx = appContext!!
        maneuver?.let { return it }
        val ms = mediaState
        val title = ms?.title?.takeIf { it.isNotBlank() }
        if (ms != null && ms.sessionAvailable && title != null) {
            return ClusterSurface.Card.Media(
                title,
                ms.artist?.takeIf { it.isNotBlank() }.orEmpty(),
                appLabel(ctx, ms.source),
            )
        }
        val sn = snapshot
        val fuel = sn?.let { VehicleMetricsSnapshot.fuelPercentRounded(it.fuelPercent) } ?: -1
        val range = sn?.rangeKm ?: -1
        val hasRange = range >= 0 && VehicleMetricsSnapshot.isValid(range)
        if (!hasRange && fuel < 0) {
            return ClusterSurface.Card.Message(ctx.getString(R.string.cluster_card_empty))
        }
        return ClusterSurface.Card.Vehicle(
            if (hasRange) range else -1,
            fuel,
            ctx.getString(R.string.cluster_card_range_label),
            ctx.getString(R.string.cluster_card_unit_km),
        )
    }
}
