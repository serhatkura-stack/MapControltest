package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R

/**
 * Sahne yan panelindeki widget'ı kurar. Medya ve yolculuk taslaktaki özel düzenleriyle ([MediaCard] Sahne kipi,
 * [SahneTripPanel]); diğer her tür kendi kutucuğunun en büyük boyutuyla (büyük biçimi yoksa büyütülmüş kutucuk)
 * cam panelin içine konur, kart zemini kaldırılır ve renkler Sahne jetonlarından gelir.
 */
internal object SahnePanelFactory {
    fun create(activity: Activity, slot: SahneSlot): SizedWidget = when (slot.type) {
        WidgetType.MEDIA -> MediaCard(activity, sahne = true)
        WidgetType.TRIP -> SahneTripPanel(activity)
        in VALUE_PANEL_TYPES -> {
            val tile = WidgetFactory.create(activity, PlacedWidget(slot.id, slot.type, 0, 0, SahneForm.panelSpan(slot.type), slot.extra))
            tile.applySpan(SahneForm.panelSpan(slot.type))
            SahneValuePanel(activity, slot.type, tile)
        }
        else -> WidgetFactory.create(activity, PlacedWidget(slot.id, slot.type, 0, 0, SahneForm.panelSpan(slot.type), slot.extra)).also {
            it.applySpan(SahneForm.panelSpan(slot.type))
        }
    }

    /**
     * Büyük biçimi olmayan, yalnızca bilgi gösteren türler: panelde kutucuk büyütülmez, yolculuk paneliyle aynı dilde
     * başlık + büyük değer + alt satır olarak çizilir ([SahneValuePanel]; veri ve dokunuş kutucuğun kendisinden).
     */
    val VALUE_PANEL_TYPES = setOf(
        WidgetType.CONSUMPTION, WidgetType.BREAK_REMINDER, WidgetType.PHONE_REMINDER, WidgetType.DRIVE_MODE,
        WidgetType.HEALTH, WidgetType.AIR,
    )

    /** Özel Sahne düzeni olan türler kendi iç boşluğunu verir; diğerlerine panel bir pay ekler. */
    fun panelPaddingDp(type: WidgetType): Int = when (type) {
        WidgetType.MEDIA, WidgetType.TRIP -> 0
        in VALUE_PANEL_TYPES -> 0
        else -> 12
    }
}

/**
 * Cam panel kabı: [GlassDrawable] zemin, içindeki widget görünümü panel boyutunda. Medya panelinde sol üstte sıcak
 * bir renk ışıması vardır (taslak). Gölge taşabilsin diye ebeveyn kırpmaz.
 */
internal class GlassPanel(context: Context, glow: Boolean) : FrameLayout(context) {
    private var glowing: Boolean? = null

    init {
        setGlow(glow)
        clipChildren = false
        clipToPadding = false
    }

    /** Medya paneli sol üst ışıması açık/kapalı (yalnızca değişince zemin yeniden kurulur). */
    fun setGlow(glow: Boolean) {
        if (glow == glowing) return
        glowing = glow
        background = if (glow) android.graphics.drawable.LayerDrawable(arrayOf(GlassDrawable(context), MediaGlow(context))) else GlassDrawable(context)
    }

    /** [art]: değiştirilebilir panel zemini görseli ([SahneArt]); camın üstünde, içeriğin altında, köşelere kırpılı. */
    fun host(content: View, paddingDp: Int, art: android.graphics.drawable.Drawable? = null) {
        removeAllViews()
        (content.parent as? ViewGroup)?.removeView(content)
        if (art != null) addView(ImageView(context).apply {
            setImageDrawable(art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, v.width, v.height, context.dpF(Sahne.PANEL_RADIUS_DP))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            // Zemin görseli panelin tamamını kaplar (iç boşluk payı dışarı taşınarak).
            val p = -context.dp(paddingDp); setMargins(p, p, p, p)
        })
        // Kutucuğun kendi kart zemini kaldırılır: panelin camı görünsün.
        content.background = null
        content.elevation = 0f
        val p = context.dp(paddingDp)
        setPadding(p, p, p, p)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Medya paneli ışıması: (60, 40) merkezli 360 × 240 eliptik, panelin yuvarlak köşelerine kırpılı. */
    private class MediaGlow(private val context: Context) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        override fun onBoundsChange(b: android.graphics.Rect) {
            val d = context.resources.displayMetrics.density
            val color = if (LauncherTheme.night) 0x33B25C96 else 0x1AD0654A
            paint.shader = SahneGround.ellipse(b.left + 60f * d, b.top + 40f * d, 360f * d, 240f * d, color, 0.70f)
            rect.set(b)
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val r = context.dpF(Sahne.PANEL_RADIUS_DP)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/**
 * Sahne yolculuk paneli (taslak): başlık, büyük "sıfırlamadan beri" mesafe, iki sütunlu ortalama tüketim / ortalama
 * hız ve altında "Toplam" bölümü (toplam yol, toplam yakıt, sürüş süresi). Veri [TripSource]'tan, yolculuk
 * kutucuğuyla ([TripComputerTile]) aynı çevrimlerle; eksik satırlar düşer.
 */
internal class SahneTripPanel(private val context: Context) : SizedWidget, StripSource {

    private var data = TripData()
    private val listener = TripSource.Listener {
        if (it == data) return@Listener
        data = it
        bind()
    }

    private val headIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_sahne_route)
        imageTintList = ColorStateList.valueOf(Sahne.INK_2)
    }
    private val head = sahneText(context, SahneType.HEAD, Sahne.INK_2, 500).apply { setText(R.string.launcher_widget_trip) }
    private val big = sahneText(context, TRIP_HERO_SP, Sahne.INK, 300, -0.03f)
    private val bigUnit = sahneText(context, SahneType.HERO_UNIT.toFloat(), Sahne.INK_2, 400)
    private val sub = sahneText(context, SahneType.SUB, Sahne.INK_3, 400)
    private val stat1 = Stat()
    private val stat2 = Stat()
    private val statsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(stat1.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(View(context).apply { setBackgroundColor(Sahne.HAIR_2) }, LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(stat2.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(24) })
    }
    private val totals = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val totalsDivider = View(context).apply { setBackgroundColor(Sahne.HAIR_2) }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(30), context.dp(24), context.dp(30), context.dp(20))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headIcon, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(head, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(10) })
        })
        // Taslakta satır yüksekliği 1: doğal satırdan ~10dp kısa, üst ve alttan kırpılır.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(big, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(bigUnit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) })
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8 - 10); bottomMargin = -context.dp(10) })
        addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(6) })
        addView(statsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = context.dp(14) })
        addView(totalsDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(12) })
        addView(totals, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = context.dp(10) })
    }

    override val view: View get() = root

    override fun applySpan(span: Span) = Unit

    override fun onVisible() = TripSource.add(context, listener)

    override fun onHidden() = TripSource.remove(listener)

    override fun release() = TripSource.remove(listener)

    // --- Şerit ---
    override var onStripChanged: (() -> Unit)? = null
    private var stripNow: StripState? = null
    override fun stripState(): StripState? = stripNow
    override fun stripTap(part: Int) = Unit

    private fun one(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f", v)

    private fun bind() {
        val km = data.tripKm
        if (!data.connected || km == null) {
            big.text = context.getString(R.string.launcher_unavailable)
            big.setTextColor(Sahne.INK_3)
            bigUnit.visibility = View.GONE
            sub.setText(R.string.launcher_waiting_data)
            statsRow.visibility = View.GONE
            totals.visibility = View.GONE
            totalsDivider.visibility = View.GONE
            root.contentDescription = context.getString(R.string.launcher_waiting_data)
            stripNow = StripState(context.getString(R.string.launcher_widget_trip), context.getString(R.string.launcher_unavailable),
                R.drawable.ic_sahne_route, StripTone.MUTED)
            onStripChanged?.invoke()
            return
        }
        big.setTextSmooth(one(km), animate = false)
        big.setTextColor(Sahne.INK)
        bigUnit.text = context.getString(R.string.launcher_sahne_km)
        bigUnit.visibility = View.VISIBLE
        sub.setText(R.string.launcher_trip_since_reset)
        val stats = listOfNotNull(
            data.avgL100?.let { context.getString(R.string.launcher_trip_avg_cons_label) to context.getString(R.string.launcher_l100_value, one(it)) },
            data.avgSpeedKmh?.let { context.getString(R.string.launcher_trip_avg_speed_label) to context.getString(R.string.launcher_kmh_value, it) },
        )
        stat1.bind(stats.getOrNull(0))
        stat2.bind(stats.getOrNull(1))
        statsRow.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE
        val rows = totalsRows()
        totals.removeAllViews()
        if (rows.isNotEmpty()) {
            totals.addView(sahneText(context, 16f, Sahne.INK_2, 600).apply { setText(R.string.launcher_widget_trip_totals) })
            rows.forEach { (label, value) ->
                totals.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(sahneText(context, SahneType.ROW_LABEL, Sahne.INK_3, 400).apply { text = label }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(sahneText(context, SahneType.ROW_VALUE, Sahne.INK, 500).apply { text = value })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(28)))
            }
        }
        totals.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        totalsDivider.visibility = totals.visibility
        val distance = context.getString(R.string.launcher_km_value, one(km))
        root.contentDescription = (listOf(context.getString(R.string.launcher_trip_since_reset), distance) +
            stats.map { "${it.first} ${it.second}" } + rows.map { "${it.first} ${it.second}" }).joinToString(", ")
        stripNow = StripState(context.getString(R.string.launcher_widget_trip), distance, R.drawable.ic_sahne_route,
            detail = context.getString(R.string.launcher_trip_since_reset))
        onStripChanged?.invoke()
    }

    private fun totalsRows(): List<Pair<String, String>> {
        val d = data
        val time = TripTotals.drivingMinutes(d.drivingRunMin, d.drivingResetMin)
        return TripTotals.rows(d.totalKm, d.totalFuelL, time).map { (kind, value) ->
            when (kind) {
                TripTotals.Kind.ODOMETER -> context.getString(R.string.launcher_totals_odometer) to
                    context.getString(R.string.launcher_km_value, String.format(java.util.Locale.getDefault(), "%,d", value as Int))
                TripTotals.Kind.FUEL -> context.getString(R.string.launcher_totals_fuel_label) to
                    context.getString(R.string.launcher_liter_value, one(value as Double))
                TripTotals.Kind.TIME -> context.getString(R.string.launcher_totals_time_label) to (value as Int).let { min ->
                    if (min >= 60) context.getString(R.string.launcher_break_hm, min / 60, min % 60) else context.getString(R.string.launcher_break_m, min)
                }
            }
        }
    }

    /** Etiket üstte, değer altta; birim küçük ve ikincil tonda ("7,9 L/100 km"). */
    private inner class Stat {
        private val label = sahneText(context, 16f, Sahne.INK_3, 400)
        private val value = sahneText(context, 28f, Sahne.INK, 500, -0.01f)
        /** Taslaktaki gibi değerin solunda ince çizgi ve 24dp girinti. */
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(context).apply { setBackgroundColor(Sahne.HAIR_2) }, LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(value, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(23) })
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(2) })
        }

        fun bind(entry: Pair<String, String>?) {
            view.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
            if (entry == null) return
            label.text = entry.first
            value.text = withUnit(context, entry.second)
        }
    }

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        /** Yolculuk panelinde dört bölüm var: büyük değer ortak ölçeğin bir kademe altında ki "Toplam" sığsın. */
        const val TRIP_HERO_SP = 54f
    }
}

/**
 * Sahne panellerinin ortak tip ölçeği (sp): başlık, büyük değer (sayı / sözcük), alt satır, satır etiketi ve satır değeri.
 * Yolculuk, değer ve koltuk panelleri aynı ölçeği kullanır.
 */
internal object SahneType {
    const val HEAD = 20f
    const val HERO = 58f
    const val HERO_UNIT = 24
    const val WORD = 40f
    const val SUB = 17f
    const val ROW_LABEL = 17f
    const val ROW_VALUE = 20f
    const val ROW_H_DP = 30
}

/**
 * Bilgi kutucuğunun panel biçimi: ekrana konmayan kutucuğun [StripSource] özeti, yolculuk paneliyle aynı tipografiyle
 * (başlık, büyük ince değer, alt satır, varsa küçük çubuk geçmişi ya da ilerleme çubuğu, ek satırlar). Dokunuş kutucuğunkiyle
 * aynı ([StripSource.stripTap]).
 */
internal class SahneValuePanel(
    private val context: Context,
    private val type: WidgetType,
    private val tile: SizedWidget,
) : SizedWidget, StripSource {
    private val source = tile as? StripSource

    private val headIcon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val head = sahneText(context, SahneType.HEAD, Sahne.INK_2, 500)
    private val value = sahneText(context, SahneType.HERO, Sahne.INK, 300, -0.03f).apply { maxLines = 1 }
    private val detail = sahneText(context, SahneType.SUB, Sahne.INK_3, 400).apply { maxLines = 2 }
    private val chartLabel = sahneText(context, 15f, Sahne.INK_3, 400).apply { setText(R.string.launcher_consumption_recent) }
    private val chart = HistoryBars(context)
    private val chartBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(chartLabel)
        addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(CHART_DP)).apply { topMargin = context.dp(6) })
    }
    private val progress = ProgressLine(context)
    private val rowsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rowsDivider = View(context).apply { setBackgroundColor(Sahne.HAIR_2) }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(30), context.dp(24), context.dp(30), context.dp(22))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headIcon, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(10) })
        })
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
        addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(PROGRESS_DP)).apply { topMargin = context.dp(18) })
        addView(chartBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16) })
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(rowsDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)))
        addView(rowsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        isClickable = true
        setOnClickListener { source?.stripTap(0) ?: tile.view.performClick() }
    }

    init {
        source?.onStripChanged = { bind() }
        bind()
    }

    private fun bind() {
        val s = source?.stripState() ?: StripState(context.getString(type.label), "", type.icon)
        headIcon.setImageResource(sahneIconFor(s.icon))
        headIcon.imageTintList = ColorStateList.valueOf(if (s.tone == StripTone.ALERT) Sahne.AMBER else Sahne.INK_2)
        head.text = s.title
        // Sayısal değer büyük ve ince (birimi küçük); sözcük değer ("Araç duruyor") bir kademe küçük, tek parça.
        val numeric = s.value.any { it.isDigit() }
        value.text = if (numeric) withUnit(context, s.value, SahneType.HERO_UNIT) else s.value
        value.setTextColor(stripToneColor(s.tone, Sahne.INK).let { if (s.tone == StripTone.ALERT) Sahne.AMBER else it })
        val sub = s.panelDetail ?: s.detail
        detail.text = sub ?: ""
        detail.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        val hasChart = s.history.size >= 2
        chartBox.visibility = if (hasChart) View.VISIBLE else View.GONE
        if (hasChart) chart.setValues(s.history)
        val p = s.progress
        progress.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) progress.set(p, if (p >= 1f) Sahne.AMBER else Sahne.ICE)
        // Ek satırlar (sağlık: akü, motor, bakım; tüketim: ortalama; mola: kalan süre): yolculuk panelindeki "Toplam" satırlarıyla aynı dil.
        rowsBox.removeAllViews()
        s.rows.take(MAX_ROWS).forEachIndexed { i, (label, v) ->
            rowsBox.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(sahneText(context, SahneType.ROW_LABEL, Sahne.INK_3, 400).apply { text = label }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sahneText(context, SahneType.ROW_VALUE, if (i in s.alertRows) Sahne.AMBER else Sahne.INK, 500).apply { text = v })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(SahneType.ROW_H_DP)))
        }
        val hasRows = s.rows.isNotEmpty()
        rowsBox.visibility = if (hasRows) View.VISIBLE else View.GONE
        rowsDivider.visibility = rowsBox.visibility
        val size = if (numeric) SahneType.HERO else SahneType.WORD
        // Çok satırlı (sağlık) ya da grafikli panelde değer bir kademe küçülür ki her şey sığsın.
        value.textSize = when {
            s.rows.size > 2 -> minOf(size, ROWS_VALUE_SP)
            hasChart -> minOf(size, CHART_VALUE_SP)
            else -> size
        }
        root.contentDescription = listOfNotNull(s.title, s.value, s.detail).joinToString(", ")
        onStripChanged?.invoke()
    }

    override val view: View get() = root
    override fun applySpan(span: Span) = Unit
    override fun onVisible() = tile.onVisible()
    override fun onHidden() = tile.onHidden()
    override fun release() = tile.release()

    override var onStripChanged: (() -> Unit)? = null
    override fun stripState(): StripState? = source?.stripState()
    override fun stripTap(part: Int) { source?.stripTap(part) }

    private companion object {
        /** Ek satır çokken (sağlık) ve grafik varken değer küçülür ki her şey sığsın. */
        const val ROWS_VALUE_SP = 36f
        const val CHART_VALUE_SP = 48f
        const val MAX_ROWS = 4
        const val CHART_DP = 84
        const val PROGRESS_DP = 8
    }
}

/**
 * Küçük çubuk geçmişi (eskiden yeniye, soldan sağa): çubuklar en büyük değere göre ölçeklenir, en sondaki (şimdiki) vurgulu.
 * Eksen ve sayı yok; tek bakışta "son dakikalarda nasıl gitti" der. Çizimde ayrılma yok.
 */
internal class HistoryBars(context: Context) : View(context) {
    private var values: List<Float> = emptyList()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setValues(v: List<Float>) {
        if (v == values) return
        values = v
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val n = values.size
        if (n == 0 || width == 0) return
        val gap = context.dpF(4f)
        val barW = ((width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val r = minOf(barW / 2f, context.dpF(4f))
        values.forEachIndexed { i, v ->
            val h = (v / max).coerceIn(0.06f, 1f) * height
            val l = i * (barW + gap)
            rect.set(l, height - h, l + barW, height.toFloat())
            paint.color = if (i == n - 1) Sahne.ICE else Sahne.TRACK
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/** İnce ilerleme çubuğu: iz üstünde [set] ile verilen oran ve renk; uçlar yuvarlak. */
internal class ProgressLine(context: Context) : View(context) {
    private var fraction = 0f
    private var color = 0
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(f: Float, c: Int) {
        if (f == fraction && c == color) return
        fraction = f.coerceIn(0f, 1f)
        color = c
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = Sahne.TRACK
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction <= 0f) return
        rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
        paint.color = color
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/**
 * "7,9 L/100 km" → sayı olduğu gibi, birim [unitSp] boyunda ve ikincil renkte. Yalnızca rakamdan oluşan sözcükler büyük kalır,
 * harf içeren sözcükler birimdir: "1 sa 12 dk" → "1" ve "12" büyük, "sa" ve "dk" küçük.
 */
internal fun withUnit(context: Context, value: String, unitSp: Int = 16): CharSequence {
    if (value.indexOf(' ') < 0) return value
    val out = SpannableStringBuilder(value)
    var start = 0
    var first = true
    for (word in value.split(' ')) {
        val end = start + word.length
        val number = word.isNotEmpty() && word.all { it.isDigit() || it == ',' || it == '.' || it == '+' }
        if (!first && !number && word.isNotEmpty()) {
            // Birimi önündeki boşlukla birlikte küçült (boşluk da küçük kalsın).
            val from = start - 1
            out.setSpan(AbsoluteSizeSpan(unitSp, true), from, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Sahne.INK_2), from, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(SahneTypefaceSpan(SahneFonts.get(context, Sahne.weight(400))), from, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        first = false
        start = end + 1
    }
    return out
}

/** Yazı tipi aralığı (API 28+ TypefaceSpan(Typeface) yerine; minSdk 29'da da çalışır). */
internal class SahneTypefaceSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}
