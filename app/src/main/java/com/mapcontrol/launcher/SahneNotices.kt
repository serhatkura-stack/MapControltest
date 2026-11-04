package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R

/** Kapsülün görünüşü: uyarı (turuncu çizgili), belirgin (dolu turuncu), bilgi (sakin çelik mavisi). */
internal enum class NoticeTone { WARN, STRONG, INFO }

/** Şeritte çizilecek bir kapsül: bildirim, metin, ikon ve ton. */
internal data class NoticeItem(val notice: Notice?, val text: String, val icon: Int, val tone: NoticeTone, val action: Boolean = false)

/**
 * Aracın altındaki bildirim şeridi: küçük Sahne kapsülleri, önem sırasıyla soldan sağa ([StageNotices]). Genişliğe ve
 * [StageNotices.MAX_VISIBLE]'a sığan kadarı görünür, kalanı tek "+N" kapsülünde toplanır. Kapsüle dokunmak [onTap]'e gider
 * ("+N" için bildirim null); [tappable] false olan kapsül dokunuş almaz.
 */
internal class NoticeRow(
    context: Context,
    private val tappable: (Notice?) -> Boolean,
    private val onTap: (Notice?) -> Unit,
) : ViewGroup(context) {

    private var items: List<NoticeItem> = emptyList()
    private val chips = mutableListOf<NoticeChip>()
    private val more = NoticeChip(context)
    private var shown = 0
    private var moreCount = 0
    private var moreStyled = -1

    init {
        clipChildren = false
        addView(more)
        if (tappable(null)) {
            more.setOnClickListener { onTap(null) }
            more.pressFeedback()
        }
        more.isClickable = tappable(null)
        visibility = GONE
    }

    fun bind(list: List<NoticeItem>) {
        if (list == items) return
        items = list
        while (chips.size < list.size) {
            val c = NoticeChip(context)
            chips += c
            addView(c, chips.size - 1)
        }
        chips.forEachIndexed { i, c ->
            val item = list.getOrNull(i)
            if (item == null) {
                c.visibility = GONE
                return@forEachIndexed
            }
            c.visibility = VISIBLE
            c.style(item)
            val tap = tappable(item.notice)
            if (tap) {
                c.setOnClickListener { onTap(item.notice) }
                c.pressFeedback()
            } else {
                c.setOnClickListener(null)
                c.setOnTouchListener(null)
            }
            c.isClickable = tap
            c.isFocusable = tap
        }
        visibility = if (list.isEmpty()) GONE else VISIBLE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val h = context.dp(CHIP_H_DP)
        val gap = context.dp(GAP_DP)
        val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val n = items.size
        for (i in 0 until n) chips[i].measure(free, exactH)
        // Sığan en çok kapsül: sondan bırakarak dene; fazlası varsa "+N" için yer ayrılır.
        var fit = minOf(n, StageNotices.MAX_VISIBLE)
        while (fit > 1) {
            val rest = n - fit
            var w = (0 until fit).sumOf { chips[it].measuredWidth } + gap * (fit - 1)
            if (rest > 0) w += gap + more.widthFor(rest)
            if (w <= maxW) break
            fit--
        }
        val (vis, extra) = StageNotices.visible(items, fit)
        shown = vis.size
        moreCount = extra
        // "+N" yalnızca sayı değişince yeniden boyanır (ölçüm sırasında gereksiz yeniden yerleşim olmasın).
        if (extra > 0 && extra != moreStyled) {
            moreStyled = extra
            more.style(NoticeItem(null, context.getString(R.string.launcher_notice_more, extra), 0, NoticeTone.INFO))
            more.contentDescription = context.getString(R.string.launcher_notice_more_desc, extra)
        }
        more.measure(free, exactH)
        var used = (0 until shown).sumOf { chips[it].measuredWidth } + gap * maxOf(0, shown - 1)
        if (extra > 0) used += gap + more.measuredWidth
        setMeasuredDimension(minOf(used, maxW), h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = context.dp(GAP_DP)
        var x = 0
        chips.forEachIndexed { i, c ->
            if (i < shown) {
                c.layout(x, 0, x + c.measuredWidth, c.measuredHeight)
                x += c.measuredWidth + gap
            } else c.layout(0, 0, 0, 0)
        }
        if (moreCount > 0) more.layout(x, 0, x + more.measuredWidth, more.measuredHeight) else more.layout(0, 0, 0, 0)
    }

    /** Tek kapsül: 34dp yükseklik, 18dp ikon, 17sp metin; eylemli kapsülde sonda küçük ok. */
    private class NoticeChip(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val label = sahneText(context, 17f, Sahne.AMBER, 500).apply { maxLines = 1 }
        private val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_sahne_chev_right)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(14), 0)
            addView(icon, LayoutParams(context.dp(18), context.dp(18)).apply { marginEnd = context.dp(8) })
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(chevron, LayoutParams(context.dp(16), context.dp(16)).apply { marginStart = context.dp(6) })
        }

        /** "+N" kapsülünün genişliği (metin ölçülerek; görünümü değiştirmez). */
        fun widthFor(n: Int): Int = paddingLeft + paddingRight + label.paint.measureText("+$n").toInt() + 1

        fun style(item: NoticeItem) {
            val (fg, layers) = when (item.tone) {
                NoticeTone.STRONG -> Sahne.GROUND to arrayOf(pill(Sahne.AMBER, 0))
                NoticeTone.WARN -> Sahne.AMBER to arrayOf(pill(Sahne.GROUND, 0), pill(Sahne.AMBER_SOFT, Sahne.AMBER_LINE))
                NoticeTone.INFO -> Sahne.INK_2 to arrayOf(pill(Sahne.GROUND, 0), pill(Sahne.SOFT_FILL, Sahne.STRIP_HAIR))
            }
            // Açık parçalar kapsülün altına uzanabilir: zemin renginde dolu katman okunurluğu korur.
            background = LayerDrawable(layers)
            label.text = item.text
            label.setTextColor(fg)
            SahneFonts.apply(label, if (item.tone == NoticeTone.STRONG) 600 else 500)
            icon.visibility = if (item.icon == 0) GONE else VISIBLE
            if (item.icon != 0) {
                icon.setImageResource(item.icon)
                icon.imageTintList = ColorStateList.valueOf(fg)
            }
            if (item.icon == 0) setPadding(context.dp(14), 0, context.dp(14), 0) else setPadding(context.dp(12), 0, context.dp(14), 0)
            chevron.visibility = if (item.action) VISIBLE else GONE
            chevron.imageTintList = ColorStateList.valueOf(fg)
            contentDescription = item.text
        }

        private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = context.dpF(999f)
            setColor(fill)
            if (stroke != 0) setStroke(context.dp(1), stroke)
        }
    }

    private companion object {
        const val CHIP_H_DP = 34
        const val GAP_DP = 10
    }
}
