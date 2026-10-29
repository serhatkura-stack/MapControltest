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
 * Normal modda widget'lar kendi dokunmalarını alır. Düzenleme modunda her widget'ın üstüne bir katman
 * gelir: sürükleyince hücreye oturur, köşedeki düğme kaldırır, alttaki boyut seçenekleri yeniden boyutlandırır.
 * Izgara düzeni kendisi saklamaz; her değişikliği [Listener.onLayoutChanged] ile bildirir.
 */
@SuppressLint("ViewConstructor")
class LauncherGrid(context: Context, private val listener: Listener) : ViewGroup(context) {

    interface Listener {
        fun onLayoutChanged(layout: List<PlacedWidget>)
        fun onLongPressEmpty()
    }

    private val gapPx = context.dp(16)
    private var layout: List<PlacedWidget> = emptyList()
    private val slots = LinkedHashMap<String, Slot>()

    var editMode = false
        set(value) {
            field = value
            slots.values.forEach { it.overlay.visibility = if (value) View.VISIBLE else View.GONE }
            invalidate()
        }

    private val emptyCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(2f)
        color = LauncherTheme.TRACK
    }
    private val cellRect = RectF()

    init {
        setWillNotDraw(false)
        isClickable = true
        setOnLongClickListener {
            listener.onLongPressEmpty()
            true
        }
    }

    /** Düzeni ve her widget'ın görünümünü uygular. Var olan görünümler yeniden yaratılmaz. */
    fun setLayout(newLayout: List<PlacedWidget>, views: Map<String, View>) {
        layout = newLayout
        val ids = newLayout.map { it.id }.toSet()
        slots.keys.filter { it !in ids }.forEach { id -> slots.remove(id)?.let { removeView(it.frame) } }
        newLayout.forEach { placed ->
            val slot = slots.getOrPut(placed.id) {
                Slot(placed, views.getValue(placed.id)).also { addView(it.frame) }
            }
            slot.placed = placed
            slot.bindOverlay()
        }
        requestLayout()
        invalidate()
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
        slots.values.forEach { slot ->
            val sw = (cw * slot.placed.span.w + gapPx * (slot.placed.span.w - 1)).roundToInt()
            val sh = (ch * slot.placed.span.h + gapPx * (slot.placed.span.h - 1)).roundToInt()
            slot.frame.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        slots.values.forEach { slot ->
            val left = cellLeft(slot.placed.col).roundToInt()
            val top = cellTop(slot.placed.row).roundToInt()
            slot.frame.layout(left, top, left + slot.frame.measuredWidth, top + slot.frame.measuredHeight)
        }
    }

    /** Düzenleme modunda boş hücreler ince bir çerçeveyle gösterilir; nereye bırakılabileceği görünür. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!editMode) return
        val radius = context.dpF(LauncherTheme.CARD_RADIUS_DP)
        val inset = emptyCellPaint.strokeWidth / 2
        for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
            if (layout.any { col in it.col until it.col + it.span.w && row in it.row until it.row + it.span.h }) continue
            cellRect.set(cellLeft(col) + inset, cellTop(row) + inset, cellLeft(col) + cellWidth() - inset, cellTop(row) + cellHeight() - inset)
            canvas.drawRoundRect(cellRect, radius, radius, emptyCellPaint)
        }
    }

    // --- Düzenleme işlemleri ---

    private fun commit(updated: List<PlacedWidget>) {
        if (LayoutStore.isValid(updated)) listener.onLayoutChanged(updated)
    }

    private fun remove(id: String) = commit(layout.filter { it.id != id })

    private fun resize(placed: PlacedWidget, span: Span) {
        val target = LayoutStore.findSpot(layout, placed.copy(span = span))
        if (target == null) {
            Toast.makeText(context, R.string.launcher_edit_no_space, Toast.LENGTH_SHORT).show()
            return
        }
        commit(layout.map { if (it.id == placed.id) target else it })
    }

    private fun dropAt(slot: Slot, dx: Float, dy: Float) {
        val col = ((slot.frame.left + dx - paddingLeft) / (cellWidth() + gapPx)).roundToInt()
        val row = ((slot.frame.top + dy - paddingTop) / (cellHeight() + gapPx)).roundToInt()
        val candidate = slot.placed.copy(col = col, row = row)
        if (candidate != slot.placed && LayoutStore.canPlace(layout, candidate)) {
            slot.frame.translationX = 0f
            slot.frame.translationY = 0f
            commit(layout.map { if (it.id == candidate.id) candidate else it })
        } else {
            slot.frame.animate().translationX(0f).translationY(0f).setDuration(LauncherTheme.CROSSFADE_MS).start()
        }
    }

    /** Bir widget'ı ve onun düzenleme katmanını taşıyan çerçeve. */
    private inner class Slot(var placed: PlacedWidget, content: View) {
        val frame = FrameLayout(context)
        val overlay = FrameLayout(context).apply {
            visibility = if (editMode) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(LauncherTheme.EDIT_SCRIM)
                setStroke(context.dp(2), LauncherTheme.ACCENT)
                cornerRadius = context.dpF(LauncherTheme.CARD_RADIUS_DP)
            }
            isClickable = true
        }
        private val sizeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        private val label = text(context, LauncherTheme.LABEL_SP, medium = true).apply {
            // İçerik perdenin altında göründüğü için ad kendi zemininde durur.
            background = rounded(context, LauncherTheme.CONTROL, 12f)
            val h = context.dp(14)
            val v = context.dp(8)
            setPadding(h, v, h, v)
        }

        init {
            (content.parent as? ViewGroup)?.removeView(content)
            frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            frame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val pad = context.dp(12)
            overlay.addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
            ).apply { setMargins(pad, pad, 0, 0) })
            overlay.addView(iconButton(
                context, R.drawable.ic_mdi_close, context.getString(R.string.launcher_edit_remove),
                sizeDp = LauncherTheme.TOUCH_MIN_DP, background = LauncherTheme.CONTROL, circle = true,
            ) { remove(placed.id) }, FrameLayout.LayoutParams(
                context.dp(LauncherTheme.TOUCH_MIN_DP), context.dp(LauncherTheme.TOUCH_MIN_DP), Gravity.TOP or Gravity.END
            ).apply { setMargins(0, pad, pad, 0) })
            overlay.addView(sizeRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
            ).apply { setMargins(pad, 0, 0, pad) })
            attachDrag()
        }

        fun bindOverlay() {
            label.setText(placed.type.label)
            sizeRow.removeAllViews()
            if (placed.type.sizes.size < 2) return
            placed.type.sizes.forEachIndexed { i, span ->
                if (i > 0) sizeRow.addView(gap(context, 8))
                val selected = span == placed.span
                sizeRow.addView(text(context, LauncherTheme.LABEL_SP, if (selected) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
                    text = span.toString()
                    gravity = Gravity.CENTER
                    background = rounded(context, if (selected) LauncherTheme.ACCENT else LauncherTheme.CONTROL, 14f)
                    layoutParams = LinearLayout.LayoutParams(context.dp(SIZE_CHIP_W_DP), context.dp(SIZE_CHIP_H_DP))
                    contentDescription = span.toString()
                    isClickable = true
                    setOnClickListener { if (!selected) resize(placed, span) }
                    pressFeedback()
                })
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachDrag() {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var dragging = false
            overlay.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; dragging = false
                        frame.bringToFront()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            frame.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
                        }
                        if (dragging) {
                            frame.translationX = dx
                            frame.translationY = dy
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        frame.animate().scaleX(1f).scaleY(1f).setDuration(LauncherTheme.PRESS_DURATION_MS).start()
                        if (dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                            dropAt(this, frame.translationX, frame.translationY)
                        } else if (dragging) {
                            // İptal edilen hareket (ör. sistem hareketi) widget'ı yerine geri koyar.
                            frame.translationX = 0f
                            frame.translationY = 0f
                        }
                        dragging = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private companion object {
        const val DRAG_SCALE = 1.03f
        const val SIZE_CHIP_W_DP = 72
        const val SIZE_CHIP_H_DP = 48
    }
}
