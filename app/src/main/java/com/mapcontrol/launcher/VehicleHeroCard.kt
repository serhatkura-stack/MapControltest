package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
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
import java.util.concurrent.Executor

/**
 * Yakıt, onda birlik yüzde (694 = %69,4); her zaman onda bir, tek kaynak VehicleMetricsSnapshot.fuelTenths.
 * Bağlı değilse ya da geçersizse null.
 */
internal fun VehicleMetricsSnapshot.fuelTenthsOrNull(): Int? {
    if (!connected) return null
    return VehicleMetricsSnapshot.fuelTenths(fuelPercent).takeIf { it >= 0 }
}

/** Yakıtın yuvarlanmış tam yüzdesi (0..100); bağlı değilse ya da geçersizse null. */
internal fun VehicleMetricsSnapshot.fuelPercentOrNull(): Int? =
    if (!connected) null else VehicleMetricsSnapshot.fuelPercentRounded(fuelPercent).takeIf { it >= 0 }

/**
 * Araç kartı: "aracım iyi mi?" sorusunun tek bakışlık cevabı. Sağda markasız kuşbakışı araç şeması (kahraman), solda büyük menzil,
 * ince yakıt çubuğu ve altta tek sakin durum satırı (kilit). Bir sorun varsa (açık kapı/bagaj/cam tavan, lastik uyarısı)
 * kilit satırının yerini tek bir uyarı rengi satırı alır; sorunun yeri şemada ALERT renginde görünür. Uyarı dolu kırmızı zeminde beyaz kalın yazıyla gösterilir.
 *
 * Yalnızca okur: kilit durumu dinleyiciden, kapılar ve menzil anlık görüntüden, lastik bayrağı 10 sn'de bir.
 */
class VehicleHeroCard(private val context: Context) : SizedWidget, Themeable {

    private val appContext = context.applicationContext
    private val metrics = VehicleMetricsRepository.getInstance(appContext)
    private val io: Executor = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())

    private var snapshot: VehicleMetricsSnapshot = metrics.currentSnapshot()
    private var locked: Boolean? = null
    private var tireWarning: Boolean? = null
    private var startup: List<LauncherStartup.Deferred> = emptyList()
    private var lowFuel = false
    /** Gösterilen tam yüzde; değer 0,1'lik oynadıkça zıplamasın diye histerezisle tutulur. */
    private var shownFuel: Int? = null
    private val tripListener = TripSource.Listener {
        lowFuel = it.lowFuel == true
        render()
    }
    /** Cam ve kaput için salt-okur tur ([ExtraSource]); gelmezse uyarı çıkmaz. */
    private var extra = ExtraData()
    private val extraListener = ExtraSource.Listener {
        if (it == extra) return@Listener
        extra = it
        render()
    }

    private val rangeText = text(context, LauncherTheme.TIME_SP, medium = true)
    /** Büyük sayının "kalan yakıt menzili" olduğunu söyleyen küçük ikincil etiket. */
    private val rangeLabel = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(R.string.launcher_range_label) }
    private val fuelText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, medium = true)
    private val fuelBar = FuelBar(context)
    /** Araç şeması: markasız kuşbakışı SUV; açık kapı/bagaj/cam tavan ve lastik uyarısı yerinde gösterilir. */
    private val carBox = CarSchematicView(context)

    private val statusIcon = icon(context, R.drawable.ic_mdi_lock, LauncherTheme.ICON_S_DP, LauncherTheme.TEXT_SECONDARY)
    private val statusText = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /** Sakin durum: kilit satırı. Sorun varsa gizlenir, yerini [alertRow] alır. */
    private val calmRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusIcon)
        addView(gap(context, LauncherTheme.GAP_S_DP))
        addView(statusText, wrapLp())
    }
    private val doorsPill = AlertPill()
    private val tiresPill = AlertPill()
    private val alertRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(doorsPill.root, wrapLp())
        addView(gap(context, LauncherTheme.GAP_S_DP))
        addView(tiresPill.root, wrapLp())
    }

    override val view: View = card(context, LinearLayout.HORIZONTAL, paddingDp = LauncherTheme.CARD_PAD_DP).apply {
        // Sol: menzil + yakıt çubuğu üstte, durum altta. Sağ: araç görseli kartın tüm yüksekliğinde.
        // İçerikle boyutlanan satırlar açıkça WRAP_CONTENT, yoksa 1px'lik boşluğa göre daralırlar.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = true
                addView(rangeText, wrapLp())
                addView(gap(context, LauncherTheme.GAP_S_DP))
                addView(rangeLabel, wrapLp())
            }, wrapLp())
            addView(gap(context, LauncherTheme.GAP_SM_DP, vertical = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(fuelBar, LinearLayout.LayoutParams(0, context.dp(FUEL_BAR_DP), 1f))
                addView(gap(context, LauncherTheme.GAP_SM_DP))
                addView(fuelText, wrapLp())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(calmRow, wrapLp())
            addView(alertRow, wrapLp())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(gap(context, LauncherTheme.GAP_S_DP))
        // Genişliği görselin kendisi yükseklikten türetir; sağa yaslı, kartın tüm iç yüksekliğinde.
        addView(carBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            fitRange(v)
            if (r - l != or_ - ol) {
                // Çok dar kartta (2×2) görsel sığmaz; menzil ve durum yeter. Eşik kartın genişliğine göredir.
                carBox.visibility = if (r - l >= v.context.dp(MIN_WIDTH_FOR_CAR_DP)) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Kart içeriğin altında kalırsa en alttaki durum satırı kırpılır. Menzil yazısı esnek olan tek parça:
     * sığmıyorsa küçültülür, böylece ikonlu durum satırı her zaman görünür.
     */
    private fun fitRange(card: View) {
        val avail = card.height - card.paddingTop - card.paddingBottom
        if (avail <= 0) return
        // Menzil dışındaki sabit parçalar: boşluk, yakıt satırı ve alttaki durum satırı.
        val fixed = context.dp(LauncherTheme.GAP_SM_DP + STATUS_HEIGHT_DP + LauncherTheme.GAP_S_DP) +
            maxOf(fuelText.lineHeight, context.dp(FUEL_BAR_DP))
        // Boyutlar yazıya dokunmadan ölçülür; yalnızca değişince uygulanır, yoksa her yerleşim yenisini tetikler.
        val paint = android.text.TextPaint(rangeText.paint)
        val size = RANGE_SIZES_SP.firstOrNull { sp ->
            paint.textSize = sp * context.resources.displayMetrics.scaledDensity
            fixed + paint.getFontMetricsInt(null) <= avail
        } ?: RANGE_SIZES_SP.last()
        val px = size * context.resources.displayMetrics.scaledDensity
        if (Math.abs(rangeText.textSize - px) > 0.5f) {
            rangeText.post { rangeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size) }
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
                    val warning = flag?.let { it > 0 }
                    if (warning == tireWarning) return@post
                    tireWarning = warning
                    render()
                }
            }
            main.postDelayed(this, TIRE_POLL_MS)
        }
    }

    override fun applySpan(span: Span) = render()

    /**
     * Tema değişti: kart yüzeyi yeniden verilir; menzil, yakıt, durum satırı, uyarı hapları ve şema [render] ile yeni
     * renklerle yeniden atanır (özet önbelleği sıfırlanır; çizim boyaları zaten çizimde okunur).
     */
    override fun applyTheme() {
        view.background = cardSurface(context)
        fuelBar.invalidate()
        rangeLabel.setTextColor(LauncherTheme.TEXT_SECONDARY)
        lastUi = null
        render()
    }

    override fun onVisible() {
        snapshot = if (LauncherDemo.enabled) LauncherDemo.vehicle() else metrics.currentSnapshot()
        if (LauncherDemo.enabled) locked = LauncherDemo.LOCKED
        render()
        metrics.addListener(metricsListener)
        controls().addListener(lockListener)
        main.removeCallbacks(tirePoll)
        startup.forEach { it.cancel() }
        startup = listOf(
            LauncherStartup.later { main.post(tirePoll) },
            LauncherStartup.later { TripSource.add(context, tripListener) },
            LauncherStartup.later { ExtraSource.add(context, extraListener) },
        )
    }

    override fun onHidden() {
        metrics.removeListener(metricsListener)
        controls().removeListener(lockListener)
        startup.forEach { it.cancel() }
        TripSource.remove(tripListener)
        ExtraSource.remove(extraListener)
        main.removeCallbacksAndMessages(null)
    }

    override fun release() {
        // Ortak örnek: release() çağrılmaz, yalnızca dinleyici çıkar.
        metrics.removeListener(metricsListener)
    }

    private fun readTireFlag(): Int? = if (LauncherDemo.enabled) (if (LauncherDemo.TIRE_WARNING) 1 else 0) else try {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) null
        else proxy.getItemValues(VDEventCarInfo.MODULE_READONLY_INFO, ReadOnlyID.ID_TIRE_PRESSURE_WARNING)?.firstOrNull()
    } catch (e: RuntimeException) {
        null
    }

    /** Kartın çizdiği her şeyin değişmez özeti; eşit ise yeniden çizim yapılmaz ([render]). */
    private data class Ui(
        /** Geçerli menzil (km); null = "veri yok". */
        val range: Int?,
        /** Gösterilen yakıt yüzdesi (histerezisli); null = bilinmiyor. */
        val fuel: Int?,
        val lowFuel: Boolean,
        /** Açık gövde parçalarının ad kaynakları, uyarı sırasıyla. */
        val open: List<Int>,
        val knowsBody: Boolean,
        val tireWarning: Boolean?,
        val car: CarSchematicView.State,
        val locked: Boolean?,
    )

    private var lastUi: Ui? = null

    private fun render() {
        val s = snapshot
        val range = s.rangeKm.takeIf { s.connected && VehicleMetricsSnapshot.isValid(it) && it >= 0 }
        val tenths = s.fuelTenthsOrNull()
        // Tam yüzde ancak onda bir değer gösterilenden 0,6 uzaklaşınca değişir; düşük yakıtta anlık izlenir.
        val fuel = if (tenths == null) null else {
            val shown = shownFuel
            if (shown == null || lowFuel || Math.abs(tenths - shown * 10) >= FUEL_HYSTERESIS_TENTHS) (tenths + 5) / 10 else shown
        }
        shownFuel = fuel

        // Gövde durumu istisna bazlı: açık kapı/bagaj/cam tavan listelenir, hepsi kapalıysa sakin.
        val knowsSunroof = s.connected && s.hasSunroof()
        val knowsDoors = s.connected && (s.hasDoorLf() || s.hasDoorRf() || s.hasDoorLr() || s.hasDoorRr() || s.hasTrunk() || knowsSunroof)
        // Cam ve kaput ayrı salt-okur turdan gelir ("Cam açık", "Kaput açık"); birden çok cam tek parça sayılır.
        val knowsExtra = extra.connected && extra.bodyKnown
        val open = buildList {
            if (knowsDoors) {
                if (s.isDoorLfOpen()) add(R.string.launcher_door_lf)
                if (s.isDoorRfOpen()) add(R.string.launcher_door_rf)
                if (s.isDoorLrOpen()) add(R.string.launcher_door_lr)
                if (s.isDoorRrOpen()) add(R.string.launcher_door_rr)
                if (s.isTrunkOpen()) add(R.string.launcher_trunk)
                if (knowsSunroof && s.sunroofOpenOrNull() == true) add(R.string.launcher_sunroof_part)
            }
            if (knowsExtra && extra.anyWindowOpen()) add(R.string.launcher_window_part)
            if (knowsExtra && extra.hoodOpen()) add(R.string.launcher_body_hood)
        }
        val tireAlert = tireWarning == true
        val ui = Ui(
            range = range, fuel = fuel, lowFuel = tenths != null && lowFuel,
            open = open, knowsBody = knowsDoors || knowsExtra, tireWarning = tireWarning,
            car = CarSchematicView.State(
                doorLf = knowsDoors && s.isDoorLfOpen(), doorRf = knowsDoors && s.isDoorRfOpen(),
                doorLr = knowsDoors && s.isDoorLrOpen(), doorRr = knowsDoors && s.isDoorRrOpen(),
                trunk = knowsDoors && s.isTrunkOpen(),
                sunroof = knowsSunroof && s.sunroofOpenOrNull() == true,
                tireWarning = tireAlert,
                locked = locked,
            ),
            locked = locked,
        )
        val prev = lastUi
        if (ui == prev) return
        lastUi = ui

        if (prev == null || prev.range != range) {
            if (range != null) {
                rangeText.setTextColor(LauncherTheme.TEXT)
                rangeText.setTextSmooth(unitStyled(context.getString(R.string.launcher_range_value, range)), animate = false)
            } else {
                rangeText.setText(R.string.launcher_unavailable)
                rangeText.setTextColor(LauncherTheme.TEXT_SECONDARY)
            }
        }
        if (prev == null || prev.fuel != fuel || prev.lowFuel != ui.lowFuel) {
            fuelText.text = if (fuel != null) context.getString(R.string.launcher_percent, fuel) else context.getString(R.string.launcher_unavailable)
            fuelText.setTextColor(if (ui.lowFuel) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
            fuelText.contentDescription = when {
                fuel != null && lowFuel -> context.getString(R.string.launcher_fuel_low, fuel)
                fuel != null -> context.getString(R.string.launcher_fuel_short, fuel)
                else -> context.getString(R.string.launcher_waiting_data)
            }
            fuelBar.set(fuel, lowFuel)
        }

        val alertChanged = prev == null || prev.open != open || prev.tireWarning != tireWarning
        if (alertChanged) {
            if (open.isEmpty()) doorsPill.hide() else doorsPill.show(
                R.drawable.ic_mdi_car_door,
                if (open.size == 1) context.getString(R.string.launcher_part_open, context.getString(open[0]))
                else context.getString(R.string.launcher_doors_open_count, open.size),
            )
            if (!tireAlert) tiresPill.hide() else tiresPill.show(
                R.drawable.ic_mdi_alert,
                // Yanında kapı uyarısı da varsa dar kartta (4×4) sığması için kısa metin.
                context.getString(if (open.isNotEmpty()) R.string.launcher_tires_warning_short else R.string.launcher_tires_warning),
            )
            val hasAlert = open.isNotEmpty() || tireAlert
            alertRow.visibility = if (hasAlert) View.VISIBLE else View.GONE
            calmRow.visibility = if (hasAlert) View.GONE else View.VISIBLE
        }
        // Şema yalnızca kendi durumu değişince boyanır (setState eşitse hiçbir şey yapmaz); ilk kurulumda tema okunur.
        carBox.setState(ui.car)
        if (prev == null) carBox.refreshTheme()

        // Sakin satır: kilit durumu; kilit açıksa da sessiz kalır (uyarı rengi yok).
        if (prev == null || prev.locked != locked) when (locked) {
            true -> bindCalm(R.drawable.ic_mdi_lock, context.getString(R.string.launcher_locked))
            false -> bindCalm(R.drawable.ic_mdi_lock_open_variant, context.getString(R.string.launcher_unlocked))
            null -> bindCalm(R.drawable.ic_mdi_lock, context.getString(R.string.launcher_unavailable))
        }
        // Ekran okuyucu için tek cümlelik özet: kilit, biliniyorsa kapılar ve lastikler.
        val spoken = buildList {
            add(if (locked == null) context.getString(R.string.launcher_waiting_data) else statusText.text.toString())
            if (ui.knowsBody && open.isEmpty()) add(context.getString(R.string.launcher_doors_closed))
            if (tireWarning == false) add(context.getString(R.string.launcher_tires_ok))
        }.joinToString(", ")
        calmRow.contentDescription = spoken
    }

    private fun bindCalm(iconRes: Int, label: String) {
        statusIcon.setImageResource(iconRes)
        statusIcon.imageTintList = ColorStateList.valueOf(LauncherTheme.TEXT_SECONDARY)
        statusText.text = label
        statusText.setTextColor(LauncherTheme.TEXT_SECONDARY)
    }

    /** Uyarı satırı: ALERT_SURFACE zeminli, ALERT renkli ikon + kısa metin. */
    private inner class AlertPill {
        private val iconView: ImageView = icon(context, R.drawable.ic_mdi_alert, LauncherTheme.ICON_S_DP, LauncherTheme.ON_ACCENT)
        private val label: TextView = text(context, LauncherTheme.BODY_SP, LauncherTheme.ON_ACCENT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(STATUS_HEIGHT_DP)
            val p = context.dp(LauncherTheme.GAP_SM_DP)
            setPadding(p, 0, p, 0)
            visibility = View.GONE
            addView(iconView)
            addView(gap(context, LauncherTheme.GAP_S_DP))
            addView(label, wrapLp())
        }

        /** Dolu uyarı zemini, beyaz ikon ve kalın yazı; zemin beyazla >= 4,5:1 olacak kadar koyulaştırılır. */
        fun show(iconRes: Int, value: String) {
            val wasHidden = root.visibility != View.VISIBLE
            iconView.setImageResource(iconRes)
            iconView.imageTintList = ColorStateList.valueOf(LauncherTheme.ON_ACCENT)
            label.text = value
            label.setTextColor(LauncherTheme.ON_ACCENT)
            root.background = rounded(context, DriveAccent.buttonFill(LauncherTheme.ALERT), 22f)
            root.contentDescription = value
            root.visibility = View.VISIBLE
            // Belirdiğinde tek seferlik yumuşak giriş; döngü yok.
            if (wasHidden) {
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            }
        }

        fun hide() {
            root.animate().cancel()
            root.alpha = 1f
            root.visibility = View.GONE
        }
    }

    /**
     * İnce yuvarlak yakıt çubuğu: iz CONTROL, dolgu ACCENT (düşük yakıtta ALERT). Yalnızca veri değişince
     * yeniden çizilir; animasyon yok.
     */
    private class FuelBar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var percent: Int? = null
        private var low = false

        fun set(percent: Int?, low: Boolean) {
            if (this.percent == percent && this.low == low) return
            this.percent = percent
            this.low = low
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2f
            paint.color = LauncherTheme.CONTROL
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, r, r, paint)
            val p = percent ?: return
            // Çok düşük değerde bile yuvarlak uç görünsün diye en az çubuk yüksekliği kadar dolgu.
            val w = maxOf(h, width * p.coerceIn(0, 100) / 100f)
            paint.color = if (low) LauncherTheme.ALERT else LauncherTheme.ACCENT
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    private companion object {
        /** Kart bu genişliğin (dp) altına inerse görsel gizlenir (2×2 gibi dar boyutlar). */
        const val MIN_WIDTH_FOR_CAR_DP = 360
        const val STATUS_HEIGHT_DP = 44
        const val FUEL_BAR_DP = 12
        const val FADE_IN_MS = 250L
        const val FUEL_HYSTERESIS_TENTHS = 6
        /** Menzil yazısı boyutları, büyükten küçüğe; kart alçaldıkça ilk sığan seçilir. */
        val RANGE_SIZES_SP = listOf(LauncherTheme.TIME_SP, 44f, 36f)
        const val TIRE_POLL_MS = 10_000L
    }
}
