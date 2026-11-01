package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mapcontrol.R
import java.util.Locale

/**
 * Lastik basıncı okuması (bar). [values]: dört tekerlek sırayla ön sol, ön sağ, arka sol, arka sağ (sıra varsayım) ya da tek
 * değer; okunamayan tekerlek null. [warning]: ReadOnly 95 `ID_TIRE_PRESSURE_WARNING` sıfırdan farklı; okunamadıysa null.
 */
internal data class TireReading(val values: List<Double?>, val warning: Boolean? = null) {
    val perWheel: Boolean get() = values.size == WHEELS
    val anyValue: Boolean get() = values.any { it != null }

    companion object {
        const val WHEELS = 4
    }
}

/**
 * Lastik basıncı sinyalinin (ReadOnly 140 `ID_TIRE_PRESSURE`) saf çözümü (JVM'de sınanır). ID yalnızca yeni Desay yazılımının
 * SDK'sında ve root dökümünde var; çözülmüş OEM uygulamalarının hiçbiri onu OKUMUYOR (araç içi lastik ekranı göstergede), bu yüzden
 * düzen ve birim KANITSIZ: yük 4 elemanlıysa tekerlek başına, 1 elemanlıysa tek değer sayılır; ham değer bar'a iki makul ölçekten
 * biriyle çevrilir ([bar]). Araçta ilk okuma çıktığında düzeltilecek; yanlış/boş her şey "—" olur, uydurma sayı gösterilmez.
 */
internal object TireDecode {

    /** Makul lastik basıncı aralığı (bar). Dışı geçersiz. */
    const val MIN_BAR = 0.5
    const val MAX_BAR = 7.0

    /** Bu değerin altı uyarı rengi (bar). */
    const val LOW_BAR = 1.8

    /**
     * Ham → bar. 50..700 kPa (ham/100); 5..49 ise 0,1 bar adımı (ham/10). 0, 0xFF/0xFFFF ve aralık dışı geçersiz (sensör yok).
     * İki ölçek örtüşmez (kPa tarafı >= 50).
     */
    fun bar(raw: Int): Double? {
        val v = when (raw) {
            in 50..700 -> raw / 100.0
            in 5..49 -> raw / 10.0
            else -> return null
        }
        return v.takeIf { it in MIN_BAR..MAX_BAR }
    }

    /** Okuma: ≥ 4 eleman → ilk dördü tekerlekler; 1 eleman → tek değer; başkası ya da hepsi geçersiz → null. */
    fun parse(raw: IntArray?, warningRaw: Int? = null): TireReading? {
        if (raw == null || raw.isEmpty()) return null
        val values = when {
            raw.size >= TireReading.WHEELS -> raw.take(TireReading.WHEELS).map(::bar)
            raw.size == 1 -> listOf(bar(raw[0]))
            else -> return null
        }
        if (values.all { it == null }) return null
        return TireReading(values, warningRaw?.let { it != 0 })
    }

    fun isLow(bar: Double?): Boolean = bar != null && bar < LOW_BAR
}

/**
 * Lastik basıncı: yukarıdan görünen küçük bir araç ve dört tekerleğin yanında bar değerleri. Tek değer okunursa araç gövdesinin
 * ortasında gösterilir. Yalnızca okur ([ExtraSource] yavaş katmanı, 20 sn). Eksik/olmayan sinyalde "Araç verisi bekleniyor".
 */
internal class TireTile(private val context: Context) : SizedWidget, Themeable {

    private val theme = ThemeBindings()
    private var data = ExtraData()

    private val listener = ExtraSource.Listener {
        data = it
        render()
    }

    private val chart = TireView(context)
    private val titleIcon = icon(context, R.drawable.ic_mdi_car, ICON_DP).also { v -> theme.bind { v.setTint(LauncherTheme.TEXT_SECONDARY) } }
    private val title = text(context, TITLE_SP).apply {
        setText(R.string.launcher_widget_tires)
        theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
    }
    private val unit = text(context, TITLE_SP).apply {
        gravity = Gravity.END
        theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
    }

    /** Okuma yokken ("Araç verisi bekleniyor"); okuma varken gizli. */
    private val caption = text(context, CAPTION_SP).apply {
        setText(R.string.launcher_waiting_data)
        visibility = View.GONE
        theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
    }

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, PADDING_DP).apply {
        theme.bind { background = cardSurface(context) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleIcon)
            addView(gap(context, 8))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(unit)
        })
        addView(caption)
        addView(gap(context, 6, vertical = true))
        addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init { theme.bind { chart.invalidate() } }

    override val view: View get() = root

    override fun applySpan(span: Span) = Unit

    override fun onVisible() {
        render()
        ExtraSource.add(context, listener)
    }

    override fun onHidden() = ExtraSource.remove(listener)

    override fun release() = ExtraSource.remove(listener)

    override fun applyTheme() {
        theme.reapply()
        render()
    }

    private fun render() {
        val t = data.tires.takeIf { data.connected }
        chart.setReading(t)
        caption.visibility = if (t == null) View.VISIBLE else View.GONE
        unit.text = if (t != null) context.getString(R.string.launcher_tires_unit) else ""
        val desc = if (t == null) context.getString(R.string.launcher_waiting_data)
        else t.values.mapIndexed { i, v -> (WHEEL_NAMES.getOrNull(i)?.let { context.getString(it) + " " } ?: "") + (v?.let { format(it) } ?: "—") }
            .joinToString(", ")
        root.contentDescription = context.getString(R.string.launcher_widget_tires) + ", " + desc
    }

    private companion object {
        const val PADDING_DP = 12
        const val ICON_DP = 24
        const val TITLE_SP = 18f
        const val CAPTION_SP = 16f
        val WHEEL_NAMES = listOf(R.string.launcher_tire_fl, R.string.launcher_tire_fr, R.string.launcher_tire_rl, R.string.launcher_tire_rr)

        fun format(bar: Double): String = String.format(Locale.getDefault(), "%.1f", bar)
    }

    /**
     * Çizim: gövde (köşeli dikdörtgen + ön/arka cam), dört tekerlek, yanlarında değerler. Okunamayan değer "—" ve sönük tekerlek;
     * düşük basınç ALERT renginde. Okuma yoksa değerler yerine yalnızca araç şeması çizilir. Renkler çizimde jetonlardan okunur.
     */
    private class TireView(context: Context) : View(context) {
        private var reading: TireReading? = null
        private val body = RectF()
        private val r = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(1.6f) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = context.dpF(VALUE_SP_DP * context.resources.configuration.fontScale)
            isFakeBoldText = false
        }

        fun setReading(next: TireReading?) {
            if (next == reading) return
            reading = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val bh = h
            val bw = minOf(h * 0.40f, w * 0.34f)
            body.set((w - bw) / 2f, 0f, (w + bw) / 2f, bh)
            val radius = bw * 0.28f
            fill.color = LauncherTheme.CONTROL
            canvas.drawRoundRect(body, radius, radius, fill)
            line.color = LauncherTheme.TEXT_SECONDARY
            line.alpha = 140
            canvas.drawRoundRect(body, radius, radius, line)
            // Ön ve arka cam.
            fill.color = LauncherTheme.TRACK
            r.set(body.left + bw * 0.16f, bh * 0.20f, body.right - bw * 0.16f, bh * 0.36f)
            canvas.drawRoundRect(r, bw * 0.08f, bw * 0.08f, fill)
            r.set(body.left + bw * 0.16f, bh * 0.68f, body.right - bw * 0.16f, bh * 0.80f)
            canvas.drawRoundRect(r, bw * 0.08f, bw * 0.08f, fill)

            val t = reading
            // Okuma yokken dört tekerlek de "—" ile çizilir.
            val per = t == null || t.perWheel
            val wt = bw * 0.17f
            val wl = bh * 0.20f
            val ys = floatArrayOf(bh * 0.24f, bh * 0.76f)
            for (row in 0..1) for (side in 0..1) {
                val idx = row * 2 + side
                val v = if (per) t?.values?.getOrNull(idx) else null
                val low = TireDecode.isLow(v) || (t?.warning == true && !per)
                val cx = if (side == 0) body.left - wt * 0.15f else body.right + wt * 0.15f
                r.set(cx - wt / 2f, ys[row] - wl / 2f, cx + wt / 2f, ys[row] + wl / 2f)
                fill.color = if (low) LauncherTheme.ALERT else if (v != null) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY
                fill.alpha = if (v != null) 255 else 140
                canvas.drawRoundRect(r, wt * 0.4f, wt * 0.4f, fill)
                if (!per) continue
                label.color = if (low) LauncherTheme.ALERT else if (v != null) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY
                val s = v?.let { format(it) } ?: "—"
                val gapPx = context.dpF(6f)
                val x = if (side == 0) r.left - gapPx - label.measureText(s) else r.right + gapPx
                canvas.drawText(s, x, ys[row] - (label.ascent() + label.descent()) / 2f, label)
            }
            // Tek değer: gövdenin ortasında.
            if (t != null && !per) {
                val v = t.values.firstOrNull()
                val s = v?.let { format(it) } ?: "—"
                val low = TireDecode.isLow(v)
                label.color = if (low) LauncherTheme.ALERT else LauncherTheme.TEXT
                canvas.drawText(s, w / 2f - label.measureText(s) / 2f, h / 2f - (label.ascent() + label.descent()) / 2f, label)
            }
        }

        private companion object {
            const val VALUE_SP_DP = 20f
        }
    }
}
