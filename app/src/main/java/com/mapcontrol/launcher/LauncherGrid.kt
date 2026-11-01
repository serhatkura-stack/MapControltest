package com.mapcontrol.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.mapcontrol.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ana ekranın düzenlenebilir orta alanı: [GRID_COLS] × [GRID_ROWS] hücre.
 *
 * Normal modda widget'lar kendi dokunmalarını alır. Düzenleme modunda ızgara tüm dokunmaları kendisi alır:
 *  - boş hücreler yumuşak çerçeveli kutular olarak görünür (ızgara yapısı okunur);
 *  - bir widget'a dokunmak onu SEÇER (vurgu çerçevesi); yalnızca seçili widget'ın adı, boyut seçenekleri ve
 *    "Kaldır" eylemi, içeriğini örtmeyen yüzen bir panelde görünür (altında, üstünde ya da yanında);
 *  - seçili olmayan widget'larda yalnızca köşede küçük bir "×" bulunur;
 *  - sürüklerken hedef ayak izi hücrelere oturur (sığıyorsa vurgu, sığmıyorsa uyarı tonu);
 *  - boşluğa dokunmak seçimi kaldırır.
 * Izgara düzeni kendisi saklamaz; her değişikliği [Listener.onLayoutChanged] ile bildirir.
 */
@SuppressLint("ViewConstructor")
class LauncherGrid(context: Context, private val listener: Listener) : ViewGroup(context), Themeable {

    interface Listener {
        fun onLayoutChanged(layout: List<PlacedWidget>)
        fun onLongPressEmpty()
    }

    private val gapPx = context.dp(GRID_GAP_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var layout: List<PlacedWidget> = emptyList()
    private val slots = LinkedHashMap<String, Slot>()

    private var selectedId: String? = null
    private var dragSlot: Slot? = null
    private var dragCol = 0
    private var dragRow = 0
    private var dragFits = true

    var editMode = false
        set(value) {
            field = value
            selectedId = null
            dragSlot = null
            refreshPaints()
            slots.values.forEach { (it.content as? EditAware)?.setEditing(value) }
            updateJoins()
            syncChrome()
            requestLayout()
            invalidate()
        }

    // Çizim nesneleri bir kez hazırlanır; onDraw/dispatchDraw içinde ayırma yok.
    private val cellRadius = context.dpF(CELL_RADIUS_DP)
    private val cardRadius = context.dpF(LauncherTheme.CARD_RADIUS_DP)
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(1f) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(2f) }
    private val footFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val footStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(2f) }
    private val cellRects = Array(GRID_COLS * GRID_ROWS) { RectF() }
    private val occupied = BooleanArray(GRID_COLS * GRID_ROWS)
    private val tmpRect = RectF()

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        elevation = context.dpF(10f)
        isClickable = true
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        isClickable = true
        setOnLongClickListener {
            listener.onLongPressEmpty()
            true
        }
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun refreshPaints() {
        cellFill.color = (LauncherTheme.CONTROL and 0x00FFFFFF) or (CELL_FILL_ALPHA shl 24)
        cellStroke.color = LauncherTheme.TRACK
        outlinePaint.color = LauncherTheme.ACCENT
        panel.background = rounded(context, LauncherTheme.CARD, 20f, LauncherTheme.TRACK)
    }

    /** Tema değişti: hücre/anahat boyaları ve "×" düğmeleri yeni renklerle yenilenir (görünümler korunur). */
    override fun applyTheme() {
        refreshPaints()
        slots.values.forEach { it.restyle() }
        invalidate()
    }

    /**
     * Tema değişiminde yeniden kurulan widget görünümlerini yerlerine takar (kayıtlı düzen aynı kalır).
     * [views] yalnızca değişenleri içerir; olmayanlar olduğu gibi kalır.
     */
    fun swapContents(views: Map<String, View>) {
        views.forEach { (id, v) -> slots[id]?.replaceContent(v) }
        slots.values.forEach { slot ->
            (slot.content as? BlankAware)?.onBlankChanged = { updateJoins() }
            (slot.content as? EditAware)?.setEditing(editMode)
        }
        updateJoins()
        syncChrome()
        requestLayout()
        invalidate()
    }

    /** Dışarıdan (ör. "Widget ekle") bir widget'ı seçer. */
    fun select(id: String?) {
        if (!editMode) return
        selectedId = id?.takeIf { it in slots }
        syncChrome()
        requestLayout()
        invalidate()
    }

    /** Düzeni ve her widget'ın görünümünü uygular. Var olan görünümler yeniden yaratılmaz. */
    fun setLayout(newLayout: List<PlacedWidget>, views: Map<String, View>) {
        layout = newLayout
        val ids = newLayout.map { it.id }.toSet()
        slots.keys.filter { it !in ids }.forEach { id ->
            slots.remove(id)?.let { removeView(it.frame); removeView(it.remove) }
        }
        newLayout.forEach { placed ->
            val slot = slots.getOrPut(placed.id) {
                Slot(placed, views.getValue(placed.id)).also { addView(it.frame, 0); addView(it.remove) }
            }
            slot.placed = placed
            (slot.content as? BlankAware)?.onBlankChanged = { updateJoins() }
            (slot.content as? EditAware)?.setEditing(editMode)
        }
        if (selectedId !in slots) selectedId = null
        occupied.fill(false)
        newLayout.forEach { p ->
            for (r in p.row until p.row + p.span.h) for (c in p.col until p.col + p.span.w) {
                if (r in 0 until GRID_ROWS && c in 0 until GRID_COLS) occupied[r * GRID_COLS + c] = true
            }
        }
        updateJoins()
        syncChrome()
        requestLayout()
        invalidate()
    }

    /** Seçime göre: içerik soluklaşması, köşe "×" düğmeleri ve yüzen panel. */
    private fun syncChrome() {
        slots.values.forEach { slot ->
            val selected = slot.placed.id == selectedId
            val dimmed = editMode && selectedId != null && !selected
            val a = if (dimmed) DIM_ALPHA else 1f
            if (slot.content.alpha != a) slot.content.animate().alpha(a).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
            slot.remove.visibility = if (editMode && !selected && dragSlot == null) View.VISIBLE else View.GONE
        }
        val sel = selectedId?.let { slots[it] }
        if (editMode && sel != null && dragSlot == null) {
            buildPanel(sel.placed)
            panel.visibility = View.VISIBLE
        } else {
            panel.visibility = View.GONE
        }
    }

    /**
     * Komşu uygulama kutucuklarını tek şerit gibi gösterir: her APP kutucuğuna hangi kenarlarında APP komşusu
     * olduğu bildirilir (yatay, dikey, L biçimli kümeler kenar başına birleşir). Düzenleme modunda birleşme
     * kapalıdır: kutucuklar ayrı görünür, sürüklenip yeniden boyutlandırılabilir.
     */
    private fun updateJoins() {
        // Düzenleme dışında boş (görünmez) uygulama kutuları birleşmeye katılmaz; komşuları şerit ucu olarak yuvarlanır.
        val blankIds = if (editMode) emptySet() else slots.values.filter { (it.content as? BlankAware)?.isBlank == true }.map { it.placed.id }.toSet()
        val apps = layout.filter { it.type == WidgetType.APP && it.id !in blankIds }
        fun appAt(col: Int, row: Int) = apps.any { col in it.col until it.col + it.span.w && row in it.row until it.row + it.span.h }
        // Komşuluk ortak kenar parçasına bakar (boyutlar eşit olmak zorunda değil): kenar boyunca herhangi bir hücrede APP varsa birleşir.
        fun appAlongCols(row: Int, p: PlacedWidget) = (p.col until p.col + p.span.w).any { appAt(it, row) }
        fun appAlongRows(col: Int, p: PlacedWidget) = (p.row until p.row + p.span.h).any { appAt(col, it) }
        slots.values.forEach { slot ->
            val joinable = slot.content as? Joinable ?: return@forEach
            val p = slot.placed
            if (editMode || p.type != WidgetType.APP || p.id in blankIds) {
                joinable.setJoins(false, false, false, false)
                return@forEach
            }
            joinable.setJoins(
                left = appAlongRows(p.col - 1, p),
                top = appAlongCols(p.row - 1, p),
                right = appAlongRows(p.col + p.span.w, p),
                bottom = appAlongCols(p.row + p.span.h, p),
                diagonals = booleanArrayOf(
                    appAt(p.col - 1, p.row - 1), appAt(p.col + p.span.w, p.row - 1),
                    appAt(p.col + p.span.w, p.row + p.span.h), appAt(p.col - 1, p.row + p.span.h),
                ),
            )
        }
    }

    // --- Ölçü ve yerleşim ---

    private fun cellWidth(): Float = (width - paddingLeft - paddingRight - gapPx * (GRID_COLS - 1f)) / GRID_COLS
    private fun cellHeight(): Float = (height - paddingTop - paddingBottom - gapPx * (GRID_ROWS - 1f)) / GRID_ROWS

    private fun cellLeft(col: Int): Float = paddingLeft + col * (cellWidth() + gapPx)
    private fun cellTop(row: Int): Float = paddingTop + row * (cellHeight() + gapPx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight - gapPx * (GRID_COLS - 1f)) / GRID_COLS
        val ch = (h - paddingTop - paddingBottom - gapPx * (GRID_ROWS - 1f)) / GRID_ROWS
        val btn = MeasureSpec.makeMeasureSpec(context.dp(REMOVE_TOUCH_DP), MeasureSpec.EXACTLY)
        slots.values.forEach { slot ->
            val sw = (cw * slot.placed.span.w + gapPx * (slot.placed.span.w - 1)).roundToInt()
            val sh = (ch * slot.placed.span.h + gapPx * (slot.placed.span.h - 1)).roundToInt()
            slot.frame.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
            slot.remove.measure(btn, btn)
        }
        panel.measure(MeasureSpec.makeMeasureSpec(w - 2 * gapPx, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth()
        val ch = cellHeight()
        LauncherInfo.noteGrid(cw, ch)
        for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
            cellRects[row * GRID_COLS + col].set(cellLeft(col), cellTop(row), cellLeft(col) + cw, cellTop(row) + ch)
        }
        val btn = context.dp(REMOVE_TOUCH_DP)
        val inset = context.dp(REMOVE_INSET_DP)
        slots.values.forEach { slot ->
            val left = cellLeft(slot.placed.col).roundToInt()
            val top = cellTop(slot.placed.row).roundToInt()
            slot.frame.layout(left, top, left + slot.frame.measuredWidth, top + slot.frame.measuredHeight)
            // 56dp dokunma alanı köşeye oturur: görsel 40dp daire köşeyi aşar, merkeze girmez.
            val bx = (slot.frame.right - inset - btn / 2).coerceIn(0, width - btn)
            val by = (slot.frame.top + inset - btn / 2).coerceIn(0, height - btn)
            slot.remove.layout(bx, by, bx + btn, by + btn)
        }
        layoutPanel()
    }

    /** Paneli seçili widget'ın altına, yer yoksa üstüne, sonra yanına, olmadı içine yerleştirir; ekrana sıkıştırır. */
    private fun layoutPanel() {
        val sel = selectedId?.let { slots[it] }
        val pw = panel.measuredWidth
        val ph = panel.measuredHeight
        if (sel == null || panel.visibility == View.GONE) {
            panel.layout(0, 0, pw, ph)
            return
        }
        val f = sel.frame
        val m = gapPx
        var x = f.left
        var y: Int
        when {
            f.bottom + m + ph <= height -> y = f.bottom + m
            f.top - m - ph >= 0 -> y = f.top - m - ph
            f.right + m + pw <= width -> { x = f.right + m; y = f.top }
            f.left - m - pw >= 0 -> { x = f.left - m - pw; y = f.top }
            else -> { y = f.bottom - m - ph; x = f.left + m }
        }
        x = x.coerceIn(0, (width - pw).coerceAtLeast(0))
        y = y.coerceIn(0, (height - ph).coerceAtLeast(0))
        panel.layout(x, y, x + pw, y + ph)
    }

    // --- Çizim ---

    /** Boş hücreler: yumuşak yuvarlak kutular; 12×6 yapısı gündüz ve gece sakin kalır. Dolu hücrelerde katman yok. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!editMode) return
        val half = cellStroke.strokeWidth / 2
        for (i in cellRects.indices) {
            if (occupied[i]) continue
            val c = cellRects[i]
            tmpRect.set(c.left + half, c.top + half, c.right - half, c.bottom - half)
            canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, cellFill)
            canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, cellStroke)
        }
    }

    /** Çocukların üstüne: seçili widget'ın vurgu çerçevesi ya da sürüklerken hedef ayak izi. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        val drag = dragSlot
        if (drag != null) {
            val p = drag.placed
            val color = if (dragFits) LauncherTheme.ACCENT else LauncherTheme.ALERT
            footFill.color = (color and 0x00FFFFFF) or (FOOT_FILL_ALPHA shl 24)
            footStroke.color = color
            tmpRect.set(cellLeft(dragCol), cellTop(dragRow), cellLeft(dragCol + p.span.w - 1) + cellWidth(), cellTop(dragRow + p.span.h - 1) + cellHeight())
            canvas.drawRoundRect(tmpRect, cardRadius, cardRadius, footFill)
            canvas.drawRoundRect(tmpRect, cardRadius, cardRadius, footStroke)
            return
        }
        val sel = selectedId?.let { slots[it] } ?: return
        val half = outlinePaint.strokeWidth / 2
        val f = sel.frame
        tmpRect.set(f.left + half, f.top + half, f.right - half, f.bottom - half)
        canvas.drawRoundRect(tmpRect, cardRadius, cardRadius, outlinePaint)
    }

    // --- Dokunma (yalnızca düzenleme modunda) ---

    private var controlGesture = false
    private var hit: Slot? = null
    private var downX = 0f
    private var downY = 0f

    private fun overControl(x: Float, y: Float): Boolean {
        if (panel.visibility == View.VISIBLE && x >= panel.left && x < panel.right && y >= panel.top && y < panel.bottom) return true
        return slots.values.any { s -> s.remove.visibility == View.VISIBLE && x >= s.remove.left && x < s.remove.right && y >= s.remove.top && y < s.remove.bottom }
    }

    private fun slotAt(x: Float, y: Float): Slot? =
        slots.values.lastOrNull { x >= it.frame.left && x < it.frame.right && y >= it.frame.top && y < it.frame.bottom }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!editMode) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) controlGesture = overControl(e.x, e.y)
        return !controlGesture
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                hit = slotAt(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val s = hit ?: return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (dragSlot == null && (abs(dx) > slop || abs(dy) > slop)) startDrag(s)
                if (dragSlot === s) {
                    s.frame.translationX = dx
                    s.frame.translationY = dy
                    updateFootprint(s)
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = hit
                val drag = dragSlot
                if (drag != null) endDrag(drag, drop = true)
                else select(s?.placed?.id)
            }
            MotionEvent.ACTION_CANCEL -> dragSlot?.let { endDrag(it, drop = false) }
        }
        return true
    }

    private fun startDrag(s: Slot) {
        selectedId = s.placed.id
        dragSlot = s
        dragCol = s.placed.col
        dragRow = s.placed.row
        dragFits = true
        s.frame.translationZ = context.dpF(6f)
        s.frame.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
        syncChrome()
        invalidate()
    }

    private fun updateFootprint(s: Slot) {
        val p = s.placed
        val col = ((s.frame.left + s.frame.translationX - paddingLeft) / (cellWidth() + gapPx)).roundToInt().coerceIn(0, GRID_COLS - p.span.w)
        val row = ((s.frame.top + s.frame.translationY - paddingTop) / (cellHeight() + gapPx)).roundToInt().coerceIn(0, GRID_ROWS - p.span.h)
        if (col == dragCol && row == dragRow) return
        dragCol = col
        dragRow = row
        dragFits = LayoutStore.canPlace(layout, p.copy(col = col, row = row))
        invalidate()
    }

    private fun endDrag(s: Slot, drop: Boolean) {
        dragSlot = null
        s.frame.animate().scaleX(1f).scaleY(1f).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
        val candidate = s.placed.copy(col = dragCol, row = dragRow)
        if (drop && candidate != s.placed && dragFits) {
            s.frame.translationX = 0f
            s.frame.translationY = 0f
            s.frame.translationZ = 0f
            commit(layout.map { if (it.id == candidate.id) candidate else it })
        } else {
            // Sığmayan ya da iptal edilen hareket widget'ı yerine geri koyar.
            s.frame.animate().translationX(0f).translationY(0f).translationZ(0f).setDuration(LauncherTheme.CROSSFADE_MS).start()
        }
        syncChrome()
        requestLayout()
        invalidate()
    }

    // --- Düzenleme işlemleri ---

    private fun commit(updated: List<PlacedWidget>) {
        if (LayoutStore.isValid(updated)) listener.onLayoutChanged(updated)
    }

    private fun remove(id: String) = commit(layout.filter { it.id != id })

    private fun resize(placed: PlacedWidget, span: Span) {
        val target = placed.copy(span = span)
        if (!LayoutStore.canPlace(layout, target)) {
            Toast.makeText(context, R.string.launcher_edit_no_space, Toast.LENGTH_SHORT).show()
            return
        }
        commit(layout.map { if (it.id == placed.id) target else it })
    }

    /** Yüzen panel: ad, boyut seçenekleri (sığmayanlar soluk) ve kaldır. Yalnızca seçim değişince kurulur. */
    private fun buildPanel(placed: PlacedWidget) {
        panel.removeAllViews()
        val pad = context.dp(10)
        panel.setPadding(context.dp(16), pad, pad, pad)
        panel.addView(icon(context, placed.type.icon, 28, LauncherTheme.TEXT_SECONDARY))
        panel.addView(gap(context, 10))
        panel.addView(text(context, LauncherTheme.LABEL_SP, medium = true).apply { setText(placed.type.label) })
        if (placed.type.sizes.size > 1) {
            panel.addView(gap(context, 20))
            placed.type.sizes.sortedWith(compareBy({ it.w * it.h }, { it.w })).forEach { span ->
                val current = span == placed.span
                val fits = current || LayoutStore.canPlace(layout, placed.copy(span = span))
                panel.addView(gap(context, 8))
                panel.addView(text(context, LauncherTheme.LABEL_SP, if (current) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
                    text = span.toString()
                    gravity = Gravity.CENTER
                    background = rounded(context, if (current) LauncherTheme.ACCENT else LauncherTheme.CONTROL, 14f)
                    layoutParams = LinearLayout.LayoutParams(context.dp(SIZE_CHIP_W_DP), context.dp(CHROME_H_DP))
                    alpha = if (fits) 1f else DISABLED_ALPHA
                    contentDescription = span.toString()
                    isClickable = true
                    setOnClickListener { if (!current) resize(placed, span) }
                    pressFeedback()
                })
            }
        }
        panel.addView(gap(context, 16))
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(context, LauncherTheme.ALERT_SURFACE, 14f)
            setPadding(context.dp(16), 0, context.dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(CHROME_H_DP))
            addView(icon(context, R.drawable.ic_mdi_delete, 24, LauncherTheme.ALERT))
            addView(gap(context, 8))
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.ALERT, medium = true).apply { setText(R.string.launcher_edit_remove) })
            contentDescription = context.getString(R.string.launcher_edit_remove)
            isClickable = true
            setOnClickListener { remove(placed.id) }
            pressFeedback()
        })
    }

    /** Bir widget'ı ve köşe "×" düğmesini taşıyan çerçeve. */
    private inner class Slot(var placed: PlacedWidget, var content: View) {
        // Birleşen kutucukların yüzeyi çerçevenin dışına (boşluğa) taşar; kırpma kapalı.
        val frame = FrameLayout(context).apply { clipChildren = false }

        /** 56dp dokunma alanı, içinde 40dp görsel daire; ızgaranın doğrudan çocuğu, widget'ın üstünde. */
        val remove = FrameLayout(context).apply {
            visibility = View.GONE
            translationZ = context.dpF(3f)
            contentDescription = context.getString(R.string.launcher_edit_remove)
            isClickable = true
            addView(removeVisual(), FrameLayout.LayoutParams(context.dp(REMOVE_VISUAL_DP), context.dp(REMOVE_VISUAL_DP), Gravity.CENTER))
            setOnClickListener { remove(placed.id) }
            pressFeedback()
        }

        private fun removeVisual() = FrameLayout(context).apply {
            background = oval(LauncherTheme.CARD).also { (it as GradientDrawable).setStroke(context.dp(1), LauncherTheme.TRACK) }
            elevation = context.dpF(3f)
            addView(icon(context, R.drawable.ic_mdi_close, 20, LauncherTheme.TEXT).apply {
                layoutParams = FrameLayout.LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER)
            })
        }

        /** Tema değişince "×" düğmesinin daire ve ikonu yeni renkle yeniden kurulur. */
        fun restyle() {
            remove.removeAllViews()
            remove.addView(removeVisual(), FrameLayout.LayoutParams(context.dp(REMOVE_VISUAL_DP), context.dp(REMOVE_VISUAL_DP), Gravity.CENTER))
        }

        /** İçerik görünümünü (aynı çerçevede) yenisiyle değiştirir; solukluk durumu korunur. */
        fun replaceContent(v: View) {
            if (v === content) return
            val a = content.alpha
            frame.removeView(content)
            (v.parent as? ViewGroup)?.removeView(v)
            content = v
            v.alpha = a
            frame.addView(v, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        init {
            (content.parent as? ViewGroup)?.removeView(content)
            frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    companion object {
        /** Izgara hücreleri arası boşluk; birleşen kutucuklar bunu kapatır. */
        const val GRID_GAP_DP = 16
        private const val DRAG_SCALE = 1.03f
        private const val CELL_RADIUS_DP = 12f
        private const val CELL_FILL_ALPHA = 0x66
        private const val FOOT_FILL_ALPHA = 0x40
        private const val DIM_ALPHA = 0.7f
        private const val DISABLED_ALPHA = 0.35f
        private const val SIZE_CHIP_W_DP = 72
        private const val CHROME_H_DP = 56
        private const val REMOVE_TOUCH_DP = 56
        private const val REMOVE_VISUAL_DP = 40
        private const val REMOVE_INSET_DP = 6
    }
}
