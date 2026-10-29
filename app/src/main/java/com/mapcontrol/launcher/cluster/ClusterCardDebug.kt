package com.mapcontrol.launcher.cluster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
 */
object ClusterCardDebug {
    const val EXTRA = "launcher_cluster_test"
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
            }
        }
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
        s.show(buildContent())
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
        surface?.show(buildContent())
    }

    private fun buildContent(): ClusterSurface.Content {
        val ctx = appContext!!
        val ms = mediaState
        val sn = snapshot
        val fuel = sn?.let { VehicleMetricsSnapshot.fuelPercentRounded(it.fuelPercent) } ?: -1
        val range = sn?.rangeKm ?: -1
        val hasRange = range >= 0 && VehicleMetricsSnapshot.isValid(range)
        val rangeLine = when {
            hasRange && fuel >= 0 -> ctx.getString(R.string.cluster_card_range_fuel, range, fuel)
            hasRange -> ctx.getString(R.string.cluster_card_range, range)
            fuel >= 0 -> ctx.getString(R.string.cluster_card_fuel, fuel)
            else -> ""
        }
        val title = ms?.title?.takeIf { it.isNotBlank() }
        return if (ms != null && ms.sessionAvailable && title != null) {
            val artist = ms.artist?.takeIf { it.isNotBlank() }.orEmpty()
            ClusterSurface.Content(
                ctx.getString(R.string.cluster_card_media),
                listOf(title, artist).let { if (artist.isEmpty() && rangeLine.isNotEmpty()) listOf(title, rangeLine) else it },
            )
        } else {
            ClusterSurface.Content(
                ctx.getString(R.string.cluster_card_vehicle),
                listOf(rangeLine.ifEmpty { ctx.getString(R.string.cluster_card_empty) }),
            )
        }
    }
}
