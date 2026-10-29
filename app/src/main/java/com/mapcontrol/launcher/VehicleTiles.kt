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
import java.util.concurrent.Executor

/** VehicleMetricsCache.INVALID ile aynı: değer okunmadı. */
private const val INVALID = Int.MIN_VALUE

/**
 * Araç kutucuklarının ortak iskeleti. 125dp yüksekliğe dikey dizilim sığmadığı için:
 * 1 sütunluk boyutta solda ikon + etiket, altında değer (detay yok);
 * geniş boyutta aynı sütun solda kalır, detay sağda dikey ortalı 24sp olarak görünür.
 */
internal abstract class VehicleTile(
    protected val context: Context,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
) : SizedWidget {

    protected val headerIcon: ImageView = icon(context, iconRes, 28, LauncherTheme.TEXT_SECONDARY)
    protected val valueText: TextView = text(context, VALUE_SMALL_SP, medium = true)
    protected val detailText: TextView = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 2).apply {
        visibility = View.GONE
        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
    }

    /** Sol sütun: başlık + değer. */
    private val mainColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(headerIcon)
            addView(gap(context, 12))
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(labelRes) })
        })
        addView(gap(context, 4, vertical = true))
        addView(valueText)
    }

    protected val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(mainColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(detailText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0f).apply {
            marginStart = context.dp(16)
        })
    }

    override val view: View get() = root

    protected var wide = false
        private set

    override fun applySpan(span: Span) {
        wide = span.w >= WIDE_MIN_COLS
        valueText.textSize = if (wide) VALUE_WIDE_SP else VALUE_SMALL_SP
        // Geniş: sütunlar ağırlıkla bölüşür (WRAP_CONTENT ile sol sütun 1px'e çöküyordu); detay yoksa sol sütun tüm kartı alır.
        mainColumn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        detailText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (wide) 1.3f else 0f).apply {
            marginStart = context.dp(16)
        }
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
        valueText.setTextColor(color)
        valueText.setTextSmooth(unitStyled(value))
        root.contentDescription = value
    }

    private companion object {
        const val VALUE_SMALL_SP = 32f
        /** Başlık ile birlikte 125dp kartın iç yüksekliğine sığan en büyük değer. */
        const val VALUE_WIDE_SP = 40f
        /** 125dp kartta 20dp kenar boşluğu: 85dp iç yükseklik. */
        const val PADDING_DP = 20
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
    private val io: Executor = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())

    /** null: bağlı değil ya da durum bilinmiyor. */
    private var locked: Boolean? = null

    private val listener = VehicleQuickControls.Listener { isLocked, connected ->
        if (LauncherDemo.enabled) return@Listener
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
        locked = if (LauncherDemo.enabled) LauncherDemo.LOCKED else null
        bind()
        controls().addListener(listener)
    }

    override fun onHidden() {
        controls().removeListener(listener)
        main.removeCallbacksAndMessages(null)
    }

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz; ayrılacak bir kaynak yok. */
    override fun release() = Unit

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
