package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot
import com.mapcontrol.vehicle.VehicleQuickControls
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** VehicleMetricsCache.INVALID ile aynı: değer okunmadı. */
private const val INVALID = Int.MIN_VALUE

/** Lastik uyarısı olan kutucuğun koyu kırmızı zemini. */

/**
 * Araç kutucuklarının ortak iskeleti: üstte ikon + etiket, ortada esnek boşluk, altta büyük değer.
 * 1 sütunluk boyutta değer 32sp, daha genişte 44sp ve (varsa) ikinci bir satır gösterilir.
 */
internal abstract class VehicleTile(
    protected val context: Context,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
) : SizedWidget {

    protected val headerIcon: ImageView = icon(context, iconRes, 28, LauncherTheme.TEXT_SECONDARY)
    protected val valueText: TextView = text(context, VALUE_SMALL_SP, medium = true)
    protected val detailText: TextView = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY).apply {
        visibility = View.GONE
    }

    protected val root: LinearLayout = card(context, paddingDp = 24).apply {
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(headerIcon)
            addView(gap(context, 12))
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(labelRes) })
        }
        addView(header)
        addView(View(context), weightLp(1f, vertical = true))
        addView(valueText)
        addView(detailText)
    }

    override val view: View get() = root

    protected var wide = false
        private set

    override fun applySpan(span: Span) {
        wide = span.w >= 2
        valueText.textSize = if (wide) VALUE_LARGE_SP else VALUE_SMALL_SP
        bindDetail()
    }

    /** İkinci satırı günceller; boşsa satır gizlenir, yalnızca geniş boyutta gösterilir. */
    protected fun showDetail(detail: CharSequence?, color: Int = LauncherTheme.TEXT_SECONDARY) {
        detailText.setTextColor(color)
        detailText.text = detail
        detailText.visibility = if (wide && !detail.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    /** Boyut değişince ikinci satırı mevcut duruma göre yeniden kurar. */
    protected abstract fun bindDetail()

    protected fun setIcon(@DrawableRes res: Int, tint: Int) {
        headerIcon.setImageResource(res)
        headerIcon.imageTintList = ColorStateList.valueOf(tint)
    }

    protected fun setValue(@StringRes res: Int, color: Int) = setValue(context.getString(res), color)

    /**
     * Veri yok: değer yerine soluk bir çizgi, geniş boyutta nedeni. Kutucuk "bozuk" gibi bağırmaz;
     * ekran okuyucu çizgi yerine nedeni okur.
     */
    protected fun showMissing(@StringRes reason: Int = R.string.launcher_waiting_data) {
        setValue(R.string.launcher_unavailable, LauncherTheme.TEXT_SECONDARY)
        showDetail(context.getString(reason))
        root.contentDescription = context.getString(reason)
    }

    protected fun setValue(value: CharSequence, color: Int) {
        valueText.text = value
        valueText.setTextColor(color)
        root.contentDescription = value
    }

    private companion object {
        const val VALUE_SMALL_SP = 32f
        const val VALUE_LARGE_SP = 44f
    }
}

/** Menzil kutucuğu: kalan menzil (km); geniş boyutta yakıt yüzdesi de görünür. */
internal class RangeTile(context: Context) : VehicleTile(context, R.drawable.ic_mdi_gas_station, R.string.launcher_widget_range) {

    private val metrics = VehicleMetricsRepository(context)
    private var snapshot: VehicleMetricsSnapshot = metrics.currentSnapshot()
    private val listener = VehicleMetricsRepository.Listener { bind(it) }

    override fun onVisible() {
        bind(metrics.currentSnapshot())
        metrics.addListener(listener)
    }

    override fun onHidden() {
        metrics.removeListener(listener)
    }

    override fun release() {
        metrics.release()
    }

    override fun bindDetail() = bindFuel()

    private fun bind(next: VehicleMetricsSnapshot) {
        snapshot = next
        val range = next.rangeKm
        if (!next.connected || range == INVALID || range < 0) {
            showMissing()
            return
        }
        setValue(context.getString(R.string.launcher_range_value, range), LauncherTheme.TEXT)
        bindFuel()
    }

    private fun bindFuel() {
        if (snapshot.rangeKm == INVALID || snapshot.rangeKm < 0 || !snapshot.connected) return
        val fuel = snapshot.fuelPercent
        val show = snapshot.connected && fuel != INVALID && fuel >= 0
        showDetail(if (show) context.getString(R.string.launcher_fuel_short, fuel) else null)
    }
}

/** Merkezi kilit kutucuğu: durumu gösterir, dokununca kilidi açar/kapatır. */
internal class LockTile(context: Context) : VehicleTile(context, R.drawable.ic_mdi_lock, R.string.launcher_widget_lock) {

    private val appContext = context.applicationContext

    /**
     * Her seferinde yeniden alınır: MapControl'ün ana ekranı kapanırken bu paylaşılan nesneyi release() ediyor;
     * saklanan eski örnek kapalı executor'la çökme yaratırdı.
     */
    private fun controls(): VehicleQuickControls = VehicleQuickControls.getInstance(appContext)
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** null: bağlı değil ya da durum bilinmiyor. */
    private var locked: Boolean? = null

    private val listener = VehicleQuickControls.Listener { isLocked, connected ->
        locked = if (connected) isLocked else null
        main.post { bind() }
    }

    init {
        root.isClickable = true
        root.isFocusable = true
        root.setOnClickListener { toggle() }
        root.pressFeedback()
    }

    override fun onVisible() {
        // Durum yalnızca dinleyiciden gelir: ana thread'de bağlantı kurmaya çalışmayız ve
        // yükleme bitmeden yanlış "kilit açık" göstermeyiz.
        locked = null
        bind()
        controls().addListener(listener)
    }

    override fun onHidden() {
        controls().removeListener(listener)
        main.removeCallbacksAndMessages(null)
    }

    override fun release() {
        io.shutdownNow()
    }

    override fun bindDetail() = showDetail(null)

    private fun bind() {
        when (locked) {
            true -> {
                setIcon(R.drawable.ic_mdi_lock, LauncherTheme.TEXT_SECONDARY)
                setValue(R.string.launcher_locked, LauncherTheme.TEXT)
            }
            false -> {
                setIcon(R.drawable.ic_mdi_lock_open_variant, LauncherTheme.TEXT_SECONDARY)
                setValue(R.string.launcher_unlocked, LauncherTheme.TEXT)
            }
            null -> {
                setIcon(R.drawable.ic_mdi_lock, LauncherTheme.TEXT_SECONDARY)
                showMissing()
            }
        }
        root.isEnabled = locked != null
    }

    /** Kilit komutu senkron binder çağrısı; ana thread'i bloklamasın diye arka planda gönderilir. */
    private fun toggle() {
        val current = locked ?: return
        if (LauncherEdition.READ_ONLY) {
            Toast.makeText(appContext, R.string.launcher_read_only, Toast.LENGTH_SHORT).show()
            return
        }
        io.runSafely {
            try {
                LauncherEdition.setCentralLock(appContext, !current)
            } catch (e: RuntimeException) {
                // Komut gitmediyse dinleyici gerçek durumu göstermeye devam eder.
            }
        }
    }
}

/**
 * Lastik kutucuğu: araç yalnızca genel bir uyarı bayrağı verir (ReadOnlyID 95), tekerlek başına basınç yok.
 * Uyarı kırmızı zemin ve ikonla görünür, sürücü dokunana kadar kalır. Okunamazsa hiçbir şey iddia edilmez.
 */
internal class TireTile(context: Context) : VehicleTile(context, R.drawable.ic_mdi_tire, R.string.launcher_widget_tires) {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var warning: Boolean? = null
    private var acknowledged = false

    /** Lastik bayrağı nadiren değişir; 10 sn'de bir, arka planda okunur. */
    private val poll = object : Runnable {
        override fun run() {
            io.runSafely {
                val value = readFlag()
                main.post { bind(value) }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    init {
        root.isClickable = true
        root.isFocusable = true
        root.setOnClickListener { acknowledge() }
        root.pressFeedback()
    }

    override fun onVisible() {
        main.removeCallbacks(poll)
        main.post(poll)
    }

    override fun onHidden() {
        main.removeCallbacks(poll)
    }

    override fun release() {
        main.removeCallbacks(poll)
        io.shutdownNow()
    }

    override fun bindDetail() = render()

    private fun readFlag(): Int? = try {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) null
        else proxy.getItemValues(VDEventCarInfo.MODULE_READONLY_INFO, ReadOnlyID.ID_TIRE_PRESSURE_WARNING)
            ?.firstOrNull()
    } catch (e: RuntimeException) {
        null
    }

    private fun bind(value: Int?) {
        val next = value?.let { it > 0 }
        if (next != warning) {
            warning = next
            acknowledged = false
        }
        render()
    }

    private fun acknowledge() {
        if (warning == true && !acknowledged) {
            acknowledged = true
            render()
        }
    }

    private fun render() {
        val active = warning == true && !acknowledged
        root.background = rounded(context, if (active) LauncherTheme.ALERT_SURFACE else LauncherTheme.CARD)
        when {
            active -> {
                setIcon(R.drawable.ic_mdi_alert, LauncherTheme.ALERT)
                setValue(R.string.launcher_tires_check, LauncherTheme.ALERT)
                showDetail(context.getString(R.string.launcher_tires_warning), LauncherTheme.TEXT_SECONDARY)
            }
            // Uyarı görüldü: bayrak hâlâ açık, "tamam" denmez.
            warning == true -> {
                setIcon(R.drawable.ic_mdi_tire, LauncherTheme.TEXT_SECONDARY)
                setValue(R.string.launcher_tires_check, LauncherTheme.TEXT)
                showDetail(context.getString(R.string.launcher_tires_warning))
            }
            warning == false -> {
                setIcon(R.drawable.ic_mdi_tire, LauncherTheme.TEXT_SECONDARY)
                setValue(R.string.launcher_tires_ok, LauncherTheme.TEXT)
                showDetail(context.getString(R.string.launcher_tires_ok))
            }
            else -> {
                setIcon(R.drawable.ic_mdi_tire, LauncherTheme.TEXT_SECONDARY)
                showMissing()
            }
        }
    }

    private companion object {
        const val POLL_MS = 10_000L
    }
}
