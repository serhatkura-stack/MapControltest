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
 * Üç kademe ([tier]): dar (< 3 sütun) solda ikon + etiket, altında değer, detay yok;
 * orta (3 sütun) aynı sütun solda, kısa detay sağda; geniş (>= 4 sütun) değer daha büyük, tam detay ve
 * (varsa) segmentli bir gösterge. Yalnızca eldeki veriyi daha ayrıntılı gösterir, yeni veri uydurmaz.
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

    /** Geniş kademede detayın altında segmentli durum göstergesi (ör. hava kalitesi); yoksa gizli. */
    private val segmentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }
    private var segmentCount = 0

    /** Sağ sütun: detay + (geniş kademede) segmentli gösterge. */
    private val detailColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(detailText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(segmentRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(SEGMENT_H_DP)).apply {
            topMargin = context.dp(8)
        })
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
        addView(detailColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0f).apply {
            marginStart = context.dp(16)
        })
    }

    override val view: View get() = root

    /** 0 = dar (< 3 sütun), 1 = orta (3), 2 = geniş (>= 4). */
    protected var tier = 0
        private set

    /** Detay gösterilen boyut (orta ve geniş). */
    protected val wide: Boolean get() = tier >= 1

    override fun applySpan(span: Span) {
        tier = when {
            span.w >= WIDE_MIN_COLS -> 2
            span.w >= MEDIUM_MIN_COLS -> 1
            else -> 0
        }
        valueText.textSize = when (tier) {
            2 -> VALUE_WIDE_SP
            1 -> VALUE_MEDIUM_SP
            else -> VALUE_SMALL_SP
        }
        // Geniş: sütunlar ağırlıkla bölüşür (WRAP_CONTENT ile sol sütun 1px'e çöküyordu); detay yoksa sol sütun tüm kartı alır.
        mainColumn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        detailColumn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (wide) 1.3f else 0f).apply {
            marginStart = context.dp(16)
        }
        bindDetail()
    }

    /** İkinci satırı günceller; boşsa satır gizlenir, yalnızca orta ve geniş boyutta gösterilir. */
    protected fun showDetail(detail: CharSequence?, color: Int = LauncherTheme.TEXT_SECONDARY) {
        detailText.setTextColor(color)
        detailText.text = detail
        detailText.visibility = if (wide && !detail.isNullOrEmpty()) View.VISIBLE else View.GONE
        updateDetailColumn()
    }

    /**
     * Geniş kademede detayın altına [total] dilimli gösterge çizer; ilk [filled] dilim [color], gerisi iz rengi.
     * Dar/orta kademede ya da [total] 0 ise gizlenir. Dilimler yalnızca sayı değişince yeniden kurulur.
     */
    protected fun showSegments(filled: Int, total: Int, color: Int) {
        if (tier < 2 || total <= 0) {
            segmentRow.visibility = View.GONE
            updateDetailColumn()
            return
        }
        if (segmentCount != total) {
            segmentCount = total
            segmentRow.removeAllViews()
            repeat(total) { i ->
                segmentRow.addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = context.dp(SEGMENT_GAP_DP)
                })
            }
        }
        for (i in 0 until total) {
            segmentRow.getChildAt(i).background = rounded(context, if (i < filled) color else LauncherTheme.TRACK, SEGMENT_H_DP / 2f)
        }
        segmentRow.visibility = View.VISIBLE
        updateDetailColumn()
    }

    private fun updateDetailColumn() {
        detailColumn.visibility = if (detailText.visibility == View.VISIBLE || segmentRow.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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
        showSegments(0, 0, 0)
        root.contentDescription = context.getString(reason)
    }

    protected fun setValue(value: CharSequence, color: Int) {
        valueText.setTextColor(color)
        valueText.setTextSmooth(unitStyled(value))
        root.contentDescription = value
    }

    private companion object {
        const val VALUE_SMALL_SP = 32f
        const val VALUE_MEDIUM_SP = 36f
        /** Orta kademe (3 sütun) bu genişlikten başlar. */
        const val MEDIUM_MIN_COLS = 3
        const val SEGMENT_H_DP = 8
        const val SEGMENT_GAP_DP = 6
        /** Başlık ile birlikte 125dp kartın iç yüksekliğine sığan en büyük değer. */
        const val VALUE_WIDE_SP = 40f
        /** 125dp kartta 20dp kenar boşluğu: 85dp iç yükseklik. */
        const val PADDING_DP = 20
    }
}

/**
 * Merkezi kilit düğmesi (1×2, uygulama kutucuğuyla aynı ayak izi): yolcunun da kolayca uzanacağı net bir düğme.
 * Büyük, ortalanmış kilit ikonu; altında küçük durum etiketi ("Kilitli" / "Kilit açık"). Dokununca kilidi açar/kapatır.
 */
internal class LockTile(private val context: Context) : SizedWidget, Themeable {

    private val appContext = context.applicationContext

    /** Tema bağlı atamalar (kurucuda bir kez, [applyTheme]'de yeniden); bkz. [ThemeBindings]. */
    private val theme = ThemeBindings()

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

    private val lockIcon: ImageView = icon(context, R.drawable.ic_mdi_lock, ICON_DP, LauncherTheme.TEXT).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP))
    }
    private val stateText: TextView = text(context, STATE_SP).apply {
        gravity = android.view.Gravity.CENTER
        theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
    }

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, paddingDp = PADDING_DP).apply {
        gravity = android.view.Gravity.CENTER
        theme.bind { background = cardSurface(context) }
        addView(lockIcon)
        addView(gap(context, 8, vertical = true))
        addView(stateText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override val view: View get() = root

    /** Tek boyut var (1×2); yerleşim değişmez. */
    override fun applySpan(span: Span) = Unit

    /** Tema değişti: yüzey ve durum yazısı varsayılana döner, kilit ikonu/yazısı [bind] ile yeniden atanır. */
    override fun applyTheme() {
        theme.reapply()
        bind()
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

    private fun bind() {
        when (locked) {
            true -> show(R.drawable.ic_mdi_lock, LauncherTheme.TEXT, context.getString(R.string.launcher_locked))
            false -> show(R.drawable.ic_mdi_lock_open_variant, LauncherTheme.TEXT, context.getString(R.string.launcher_unlocked))
            null -> show(R.drawable.ic_mdi_lock, LauncherTheme.TEXT_SECONDARY, context.getString(R.string.launcher_unavailable))
        }
        // Test sürümünde veri yokken de dokunuş açıklama göstermeli.
        root.isEnabled = locked != null || LauncherEdition.READ_ONLY
    }

    private fun show(@DrawableRes res: Int, tint: Int, label: String) {
        lockIcon.setImageResource(res)
        lockIcon.imageTintList = ColorStateList.valueOf(tint)
        stateText.setTextSmooth(label)
        root.contentDescription = context.getString(R.string.launcher_widget_lock) + ", " + label
    }

    /** Kilit komutu senkron binder çağrısı; ana thread'i bloklamasın diye arka planda gönderilir. */
    private fun toggle() {
        if (LauncherEdition.READ_ONLY) {
            Toast.makeText(appContext, R.string.launcher_read_only_lock, Toast.LENGTH_LONG).show()
            return
        }
        val current = locked ?: return
        io.runSafely {
            try {
                LauncherEdition.setCentralLock(appContext, !current)
            } catch (e: RuntimeException) {
                // Komut gitmediyse dinleyici gerçek durumu göstermeye devam eder.
            }
        }
    }

    private companion object {
        const val ICON_DP = 64
        const val STATE_SP = 20f
        const val PADDING_DP = 6
    }
}
