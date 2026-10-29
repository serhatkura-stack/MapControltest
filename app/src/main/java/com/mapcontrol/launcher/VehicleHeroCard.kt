package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot
import com.mapcontrol.vehicle.VehicleQuickControls
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Araç kartı: "aracım iyi mi?" sorusunun tek bakışlık cevabı. Solda büyük menzil ve yakıt, sağda araç silüeti,
 * altta kilit · kapılar · lastikler. Her şey yolundaysa sakin; bir sorun varsa yalnızca o parça uyarı renginde.
 *
 * Yalnızca okur: kilit durumu dinleyiciden, kapılar ve menzil anlık görüntüden, lastik bayrağı 10 sn'de bir.
 */
class VehicleHeroCard(private val context: Context) : SizedWidget {

    private val appContext = context.applicationContext
    private val metrics = VehicleMetricsRepository(context)
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var snapshot: VehicleMetricsSnapshot = metrics.currentSnapshot()
    private var locked: Boolean? = null
    private var tireWarning: Boolean? = null

    private val rangeText = text(context, LauncherTheme.TIME_SP, medium = true)
    private val fuelText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
    /**
     * Araç görseli: proje sahibinin ürettiği Tiggo 7 görseli, arka planı ayrılmış, 720 px'lik tek PNG.
     * Sabit görsel: kare başına çizim ya da 3B motor yok.
     */
    private val carImage = ImageView(context).apply {
        setImageResource(R.drawable.launcher_car)
        // Satırın yüksekliğine sığar, sağ alta yaslanır; tekerler kesilmez.
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val lockChip = StatusChip()
    private val doorsChip = StatusChip()
    private val tiresChip = StatusChip()

    override val view: View = card(context, LinearLayout.HORIZONTAL).apply {
        // Sol: menzil üstte, durumlar altta. Sağ: araç kartın tüm yüksekliğinde.
        // İçerikle boyutlanan satırlar açıkça WRAP_CONTENT, yoksa 1px'lik boşluğa göre daralırlar.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(context, R.drawable.ic_mdi_gas_station, 28, LauncherTheme.TEXT_SECONDARY))
                addView(gap(context, 12))
                addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                    setText(R.string.launcher_widget_range)
                })
            }, wrapLp())
            addView(gap(context, 8, vertical = true))
            addView(rangeText, wrapLp())
            addView(gap(context, 4, vertical = true))
            addView(fuelText, wrapLp())
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(lockChip.root, wrapLp())
                addView(gap(context, 8))
                addView(doorsChip.root, wrapLp())
                addView(gap(context, 8))
                addView(tiresChip.root, wrapLp())
            }, wrapLp())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(gap(context, 16))
        addView(carImage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, CAR_WEIGHT))
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l != or_ - ol) {
                // Dar kartta (2×2) silüet sığmaz; menzil ve durum yeter.
                carImage.visibility = if (r - l >= v.context.dp(MIN_WIDTH_FOR_CAR_DP)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun wrapLp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private val metricsListener = VehicleMetricsRepository.Listener {
        if (LauncherDemo.enabled) return@Listener
        snapshot = it
        render()
    }

    private fun controls() = VehicleQuickControls.getInstance(appContext)

    private val lockListener = VehicleQuickControls.Listener { isLocked, connected ->
        if (LauncherDemo.enabled) return@Listener
        main.post {
            locked = if (connected) isLocked else null
            render()
        }
    }

    private val tirePoll = object : Runnable {
        override fun run() {
            io.runSafely {
                val flag = readTireFlag()
                main.post {
                    tireWarning = flag?.let { it > 0 }
                    render()
                }
            }
            main.postDelayed(this, TIRE_POLL_MS)
        }
    }

    override fun applySpan(span: Span) = render()

    override fun onVisible() {
        snapshot = if (LauncherDemo.enabled) LauncherDemo.vehicle() else metrics.currentSnapshot()
        if (LauncherDemo.enabled) locked = LauncherDemo.LOCKED
        render()
        metrics.addListener(metricsListener)
        controls().addListener(lockListener)
        main.removeCallbacks(tirePoll)
        main.post(tirePoll)
    }

    override fun onHidden() {
        metrics.removeListener(metricsListener)
        controls().removeListener(lockListener)
        main.removeCallbacksAndMessages(null)
    }

    override fun release() {
        metrics.release()
        io.shutdownNow()
    }

    private fun readTireFlag(): Int? = if (LauncherDemo.enabled) (if (LauncherDemo.TIRE_WARNING) 1 else 0) else try {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) null
        else proxy.getItemValues(VDEventCarInfo.MODULE_READONLY_INFO, ReadOnlyID.ID_TIRE_PRESSURE_WARNING)?.firstOrNull()
    } catch (e: RuntimeException) {
        null
    }

    private fun render() {
        val s = snapshot
        val range = s.rangeKm
        if (s.connected && VehicleMetricsSnapshot.isValid(range) && range >= 0) {
            rangeText.text = context.getString(R.string.launcher_range_value, range)
            rangeText.setTextColor(LauncherTheme.TEXT)
        } else {
            rangeText.setText(R.string.launcher_unavailable)
            rangeText.setTextColor(LauncherTheme.TEXT_SECONDARY)
        }
        val fuel = s.fuelPercent
        fuelText.text = if (s.connected && VehicleMetricsSnapshot.isValid(fuel) && fuel >= 0) {
            context.getString(R.string.launcher_fuel_short, fuel)
        } else {
            context.getString(R.string.launcher_waiting_data)
        }

        when (locked) {
            true -> lockChip.bind(R.drawable.ic_mdi_lock, context.getString(R.string.launcher_locked))
            false -> lockChip.bind(R.drawable.ic_mdi_lock_open_variant, context.getString(R.string.launcher_unlocked))
            null -> lockChip.bindMissing(R.drawable.ic_mdi_lock)
        }

        val knowsDoors = s.connected && (s.hasDoorLf() || s.hasDoorRf() || s.hasDoorLr() || s.hasDoorRr() || s.hasTrunk())
        if (!knowsDoors) {
            doorsChip.bindMissing(R.drawable.ic_mdi_car_door)
        } else {
            val open = buildList {
                if (s.isDoorLfOpen()) add(R.string.launcher_door_lf)
                if (s.isDoorRfOpen()) add(R.string.launcher_door_rf)
                if (s.isDoorLrOpen()) add(R.string.launcher_door_lr)
                if (s.isDoorRrOpen()) add(R.string.launcher_door_rr)
                if (s.isTrunkOpen()) add(R.string.launcher_trunk)
            }
            when {
                open.isEmpty() -> doorsChip.bind(R.drawable.ic_mdi_car_door, context.getString(R.string.launcher_closed),
                    context.getString(R.string.launcher_doors_closed))
                open.size == 1 -> doorsChip.bind(R.drawable.ic_mdi_car_door,
                    context.getString(R.string.launcher_part_open, context.getString(open[0])), alert = true)
                else -> doorsChip.bind(R.drawable.ic_mdi_car_door,
                    context.getString(R.string.launcher_doors_open_count, open.size), alert = true)
            }
        }

        when (tireWarning) {
            true -> tiresChip.bind(R.drawable.ic_mdi_alert, context.getString(R.string.launcher_tires_check), alert = true)
            false -> tiresChip.bind(R.drawable.ic_mdi_tire, context.getString(R.string.launcher_normal),
                context.getString(R.string.launcher_tires_ok))
            null -> tiresChip.bindMissing(R.drawable.ic_mdi_tire)
        }
    }

    /** Alt satırdaki tek durum: ikon + kısa metin. Uyarıda ikon ve metin ALERT, zemin ALERT_SURFACE. */
    private inner class StatusChip {
        private val iconView: ImageView = icon(context, R.drawable.ic_mdi_lock, 24, LauncherTheme.TEXT_SECONDARY)
        private val label: TextView = text(context, LauncherTheme.LABEL_SP, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(CHIP_HEIGHT_DP)
            val p = context.dp(12)
            setPadding(p, 0, p, 0)
            addView(iconView)
            addView(gap(context, 10))
            addView(label, wrapLp())
        }

        /** [spoken]: ikonsuz okunduğunda anlamlı uzun metin (ekran okuyucu); yoksa [value]. */
        fun bind(iconRes: Int, value: String, spoken: String = value, alert: Boolean = false) {
            iconView.setImageResource(iconRes)
            iconView.imageTintList = ColorStateList.valueOf(if (alert) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
            label.text = value
            label.setTextColor(if (alert) LauncherTheme.ALERT else LauncherTheme.TEXT)
            root.background = rounded(context, if (alert) LauncherTheme.ALERT_SURFACE else LauncherTheme.CONTROL, 16f)
            root.contentDescription = spoken
        }

        fun bindMissing(iconRes: Int) {
            bind(iconRes, context.getString(R.string.launcher_unavailable))
            label.setTextColor(LauncherTheme.TEXT_SECONDARY)
            root.contentDescription = context.getString(R.string.launcher_waiting_data)
        }
    }

    private companion object {
        const val MIN_WIDTH_FOR_CAR_DP = 760
        const val CHIP_HEIGHT_DP = 48
        /** Sol sütuna göre araç görselinin payı; 3×2'de görsel kartın yüksekliğini doldurur. */
        const val CAR_WEIGHT = 0.8f
        const val TIRE_POLL_MS = 10_000L
    }
}
