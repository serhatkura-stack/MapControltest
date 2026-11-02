package com.mapcontrol.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R

/**
 * Sahne düzeninin orta alanı (başlık ile alt çubuk arası): sol panel yuvası · sabit araç sahnesi · sağ panel yuvası,
 * altta tek cam şeritte [STRIP_SLOTS] yuva. Yuvalardaki widget'lar yalnızca ekran görünürken çalışır.
 *
 * Düzenleme modunda her yuvanın üstünde "Değiştir" örtüsü vardır; dokununca o yuvaya uyan türleri gösteren seçici açılır
 * (panel ya da şerit biçimi, ekranda olanlar ve Sürüş'te Park'a özel olanlar soluk). "Kaldır" yuvayı boşaltır (sessiz "+").
 * Değişiklikler hemen etkin moda ait [SahneStore]'a yazılır; Serbest ızgaranın kayıtlarına dokunulmaz.
 */
@SuppressLint("ViewConstructor")
class SahneScreen(
    private val activity: Activity,
    /** Açılan sayfayı etkinliğe bildirir (boşta kararları ve kapatma için); kapanınca null. */
    private val onSheet: (Dialog?) -> Unit,
    /** Düzenleme işlemi şu an yapılabilir mi (araç dururken); değilse kendi uyarısını gösterir ve false döner. */
    private val canEdit: () -> Boolean,
) : ViewGroup(activity) {

    private val parkStore = SahneStore(activity, LayoutMode.PARK)
    private val driveStore = SahneStore(activity, LayoutMode.DRIVE)
    private var mode = LayoutMode.PARK
    private val store get() = if (mode == LayoutMode.DRIVE) driveStore else parkStore

    /** Kayıtlı düzen (Sürüş'te gizlenen Park'a özel yuvalar dahil) ve ekranda gösterilen. */
    private var stored: SahneLayout = parkStore.load()
    private var shown: SahneLayout = stored

    private val stage = SahneStage(activity)
    private val leftPanel = GlassPanel(activity, glow = false)
    private val rightPanel = GlassPanel(activity, glow = false)
    private val strip = StripBar()

    /** Yuvalardaki çalışan widget'lar (görünürlük ve bırakma için). */
    private val hosted = LinkedHashMap<SahneSlotRef, List<LauncherWidget>>()
    private val overlays = LinkedHashMap<SahneSlotRef, SlotOverlay>()
    private var started = false

    var editMode = false
        set(value) {
            if (field == value) return
            field = value
            stage.locked = value
            overlays.values.forEach { it.visibility = if (value) View.VISIBLE else View.GONE }
            refreshEmpty()
        }

    init {
        clipChildren = false
        clipToPadding = false
        addView(stage)
        addView(leftPanel)
        addView(rightPanel)
        addView(strip)
        (0..1).forEach { addOverlay(SahneSlotRef(SahneSlotKind.PANEL, it)) }
        (0 until STRIP_SLOTS).forEach { addOverlay(SahneSlotRef(SahneSlotKind.STRIP, it)) }
        rebuildAll()
    }

    // --- Yaşam döngüsü ---

    fun onVisible() {
        started = true
        stage.onVisible()
        hosted.values.flatten().forEach { it.onVisible() }
    }

    fun onHidden() {
        started = false
        stage.onHidden()
        hosted.values.flatten().forEach { it.onHidden() }
    }

    fun release() {
        stage.release()
        hosted.values.flatten().forEach { it.release() }
        hosted.clear()
    }

    /** Park/Sürüş değişti: o modun kayıtlı düzeni kurulur. */
    fun setMode(to: LayoutMode) {
        if (to == mode) return
        mode = to
        stored = store.load()
        rebuildAll()
    }

    /** Etkin modun düzeni varsayılana döner. */
    fun resetToDefaults() {
        store.reset()
        stored = store.defaults()
        rebuildAll()
    }

    private fun rebuildAll() {
        shown = SahneSlots.visibleForMode(stored, mode)
        hosted.keys.toList().forEach { dropHosted(it) }
        build(SahneSlotRef(SahneSlotKind.PANEL, 0))
        build(SahneSlotRef(SahneSlotKind.PANEL, 1))
        strip.clearSlots()
        (0 until STRIP_SLOTS).forEach { build(SahneSlotRef(SahneSlotKind.STRIP, it)) }
        strip.syncDividers()
        refreshEmpty()
        requestLayout()
    }

    private fun dropHosted(ref: SahneSlotRef) {
        hosted.remove(ref)?.forEach { w ->
            if (started) w.onHidden()
            w.release()
        }
    }

    /** [ref] yuvasının widget'ını kurar ve yerine koyar (eskisi durdurulup bırakılır). */
    private fun build(ref: SahneSlotRef) {
        dropHosted(ref)
        val slot = SahneSlots.get(shown, ref)
        val widgets: List<LauncherWidget>
        when (ref.kind) {
            SahneSlotKind.PANEL -> {
                val panel = if (ref.index == 0) leftPanel else rightPanel
                if (slot == null) {
                    panel.removeAllViews()
                    widgets = emptyList()
                } else {
                    val w = SahnePanelFactory.create(activity, slot)
                    panel.setGlow(slot.type == WidgetType.MEDIA)
                    panel.host(w.view, SahnePanelFactory.panelPaddingDp(slot.type), SahneArt.panelBackground(activity, slot.type))
                    widgets = listOf(w)
                }
            }
            SahneSlotKind.STRIP -> {
                val (view, ws) = if (slot == null) View(activity) to emptyList() else stripView(slot)
                strip.setSlot(ref.index, view, slot)
                widgets = ws
            }
        }
        if (widgets.isNotEmpty()) {
            hosted[ref] = widgets
            if (started) widgets.forEach { it.onVisible() }
        }
    }

    /** Şerit öğesi: APP iki uygulamalık çift; diğerleri kendi kutucuğunun [StripSource] özetiyle. */
    private fun stripView(slot: SahneSlot): Pair<View, List<LauncherWidget>> {
        if (slot.type == WidgetType.APP) {
            val pkgs = SahneSlots.appPackages(slot.extra)
            val tiles = listOf(
                AppShortcutTile(activity, pkgs[0], slot.id + "_a"),
                AppShortcutTile(activity, pkgs[1], slot.id + "_b"),
            ).onEach { it.applySpan(Span(2, 2)) }
            return AppPairStripView(activity, tiles) to tiles
        }
        val span = SahneForm.stripSpan(slot.type)
        val widget = WidgetFactory.create(activity, PlacedWidget(slot.id, slot.type, 0, 0, span, slot.extra)).also { it.applySpan(span) }
        val source = widget as? StripSource
        val fallback = StripState(activity.getString(slot.type.label), "", slot.type.icon)
        val item = StripItemView(activity,
            onTap = { part -> if (source != null) source.stripTap(part) else widget.view.performClick() },
            onLong = { source?.stripLongPress() ?: false },
            type = slot.type,
        )
        if (source != null) source.onStripChanged = { item.bind(source.stripState() ?: fallback) }
        item.bind(source?.stripState() ?: fallback)
        return item to listOf(widget)
    }

    // --- Düzenleme ---

    private fun addOverlay(ref: SahneSlotRef) {
        val o = SlotOverlay(ref)
        o.visibility = View.GONE
        overlays[ref] = o
        addView(o)
    }

    /** Boş yuvalar: düzenlemede "+" örtüsü, normalde soluk boş cam (panel) ya da boşluk (şerit). */
    private fun refreshEmpty() {
        overlays.forEach { (ref, o) -> o.bind(SahneSlots.get(shown, ref) != null) }
        leftPanel.alpha = if (shown.left == null && !editMode) EMPTY_ALPHA else 1f
        rightPanel.alpha = if (shown.right == null && !editMode) EMPTY_ALPHA else 1f
    }

    private fun place(ref: SahneSlotRef, type: WidgetType) {
        val slot = SahneSlot(SahneSlots.newId(stored, type), type,
            if (type == WidgetType.APP) SahneSlots.appExtra(AppDock.installedDefaults(activity, DEFAULT_APP_SLOTS)) else null)
        apply(ref, slot)
    }

    private fun apply(ref: SahneSlotRef, slot: SahneSlot?) {
        stored = SahneSlots.set(stored, ref, slot)
        store.save(stored)
        shown = SahneSlots.visibleForMode(stored, mode)
        build(ref)
        if (ref.kind == SahneSlotKind.STRIP) strip.syncDividers()
        refreshEmpty()
        requestLayout()
    }

    /** Yuva seçici: yuvaya uyan türler, 5 sütun; yerleştirilemeyenler soluk ve nedeni yazılı. Dolu yuvada "Kaldır". */
    private fun openPicker(ref: SahneSlotRef) {
        if (!canEdit() || activity.isFinishing || activity.isDestroyed) return
        val current = SahneSlots.get(stored, ref)
        val types = WidgetType.values().filter { SahneForm.fits(it, ref.kind) }
        val title = if (ref.kind == SahneSlotKind.PANEL) R.string.launcher_sahne_pick_panel else R.string.launcher_sahne_pick_strip
        val d = showLauncherSheet(activity, title, PICKER_WIDTH_DP) { dialog ->
            if (current != null) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                        text = context.getString(R.string.launcher_sahne_in_slot, context.getString(current.type.label))
                    }, weightLp(1f).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT })
                    addView(text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT, medium = true).apply {
                        setText(R.string.launcher_edit_remove)
                        gravity = Gravity.CENTER
                        background = rounded(context, LauncherTheme.CONTROL, 16f)
                        setPadding(context.dp(28), 0, context.dp(28), 0)
                        isClickable = true
                        setOnClickListener { dialog.dismiss(); apply(ref, null) }
                        pressFeedback()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(LauncherTheme.TOUCH_MIN_DP)))
                }, sheetFullWidth())
                addView(gap(context, 16, vertical = true))
            }
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            types.chunked(PICKER_COLUMNS).forEachIndexed { r, row ->
                if (r > 0) grid.addView(gap(context, 12, vertical = true))
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (c in 0 until PICKER_COLUMNS) {
                        if (c > 0) addView(gap(context, 12))
                        val type = row.getOrNull(c)
                        val lp = LinearLayout.LayoutParams(0, context.dp(PICKER_TILE_DP), 1f)
                        if (type == null) addView(View(context), lp) else addView(pickerTile(ref, type, current) { dialog.dismiss() }, lp)
                    }
                }, sheetFullWidth())
            }
            addView(android.widget.ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(grid, sheetFullWidth())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(PICKER_LIST_DP)))
        }
        d.setOnDismissListener { onSheet(null) }
        onSheet(d)
    }

    private fun pickerTile(ref: SahneSlotRef, type: WidgetType, current: SahneSlot?, dismiss: () -> Unit): View {
        val parkBlocked = !isAllowedIn(type, mode)
        val here = current?.type == type
        val ok = !here && SahneForm.canPlace(stored, ref, type, mode)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, LauncherTheme.CONTROL, 16f, if (here) LauncherTheme.ACCENT else 0)
            setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(14))
            addView(icon(context, type.icon, 32, if (ok || here) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY))
            addView(gap(context, 8, vertical = true))
            addView(text(context, LauncherTheme.BODY_SP, if (ok || here) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY, medium = true).apply {
                setAutoSizeTextTypeUniformWithConfiguration(16, LauncherTheme.BODY_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setText(type.label)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(gap(context, 4, vertical = true))
            addView(text(context, LauncherTheme.LABEL_SP - 4f, LauncherTheme.TEXT_SECONDARY).apply {
                text = when {
                    here -> context.getString(R.string.launcher_sahne_in_this_slot)
                    parkBlocked -> context.getString(R.string.launcher_edit_park_only)
                    !ok -> context.getString(R.string.launcher_edit_on_screen)
                    ref.kind == SahneSlotKind.PANEL && !SahneForm.hasLargeForm(type) && type !in SahnePanelFactory.VALUE_PANEL_TYPES ->
                        context.getString(R.string.launcher_sahne_enlarged)
                    else -> ""
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            alpha = if (ok || here) 1f else DISABLED_ALPHA
            contentDescription = context.getString(type.label)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!ok) return@setOnClickListener
                dismiss()
                place(ref, type)
            }
            pressFeedback()
        }
    }

    /**
     * Düzenleme örtüsü: yuvanın üstünde kesikli buz mavisi çerçeve ve ortada "Değiştir" hapı; boş yuvada yalnızca
     * sessiz bir "+". Dokunuş (her yerinde) seçiciyi açar; altındaki widget dokunuş almaz.
     */
    private inner class SlotOverlay(private val ref: SahneSlotRef) : FrameLayout(activity) {
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(1.5f)
            pathEffect = DashPathEffect(floatArrayOf(context.dpF(8f), context.dpF(6f)), 0f)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val chipIcon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val chipText = sahneText(context, 18f, Sahne.INK, 600)
        private val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(18), context.dp(10), context.dp(20), context.dp(10))
            addView(chipIcon, LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
            addView(chipText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
        }

        init {
            setWillNotDraw(false)
            clipChildren = false
            addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            isClickable = true
            isFocusable = true
            setOnClickListener { openPicker(ref) }
        }

        fun bind(filled: Boolean) {
            frame.color = Sahne.ICE
            fill.color = if (filled) (Sahne.GROUND and 0x00FFFFFF) or 0x8C000000.toInt() else 0
            if (filled) {
                chipIcon.setImageResource(R.drawable.ic_sahne_pen)
                chipText.setText(R.string.launcher_sahne_change)
                chipText.visibility = View.VISIBLE
                chip.background = GlassDrawable(context, Sahne.PILL_RADIUS_DP, shadow = 0.4f)
                contentDescription = context.getString(R.string.launcher_sahne_change)
            } else {
                chipIcon.setImageResource(R.drawable.ic_sahne_plus)
                chipText.visibility = View.GONE
                chip.background = null
                contentDescription = context.getString(R.string.launcher_sahne_add_here)
            }
            chipIcon.imageTintList = ColorStateList.valueOf(if (filled) Sahne.ICE else Sahne.INK_3)
            (chipIcon.layoutParams as LinearLayout.LayoutParams).apply {
                val s = context.dp(if (filled) 22 else 34)
                width = s; height = s
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val inset = context.dpF(2f)
            rect.set(inset, inset, width - inset, height - inset)
            val r = context.dpF(if (ref.kind == SahneSlotKind.PANEL) Sahne.PANEL_RADIUS_DP else 18f)
            if (fill.color != 0) canvas.drawRoundRect(rect, r, r, fill)
            canvas.drawRoundRect(rect, r, r, frame)
        }
    }

    // --- Şerit kabı ---

    /** Tek cam şerit: yuvalar yan yana (genişlikleri türlerine göre), aralarında ince çizgi. */
    private inner class StripBar : LinearLayout(activity) {
        private val slots = arrayOfNulls<View>(STRIP_SLOTS)
        private val types = arrayOfNulls<WidgetType>(STRIP_SLOTS)

        init {
            orientation = HORIZONTAL
            background = GlassDrawable(context, Sahne.STRIP_RADIUS_DP)
            setPadding(context.dp(10), 0, context.dp(10), 0)
            clipChildren = false
        }

        fun clearSlots() {
            removeAllViews()
            slots.fill(null)
            types.fill(null)
        }

        fun setSlot(index: Int, view: View, slot: SahneSlot?) {
            slots[index] = view
            types[index] = slot?.type
        }

        /** Yuvaları ve aralarındaki çizgileri yeniden dizer. */
        fun syncDividers() {
            removeAllViews()
            slots.forEachIndexed { i, v ->
                val view = v ?: View(context).also { slots[i] = it }
                (view.parent as? ViewGroup)?.removeView(view)
                if (i > 0) addView(View(context).apply { setBackgroundColor(Sahne.HAIR_2) },
                    LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT).apply { topMargin = context.dp(22); bottomMargin = context.dp(22) })
                addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, stripWeight(types[i])))
            }
        }

        fun slotView(index: Int): View? = slots[index]
    }

    /** Şerit yuvaları eşit genişlikte (6 eşit pay), her tür ve düzenleme kipinde aynı. */
    private fun stripWeight(@Suppress("UNUSED_PARAMETER") type: WidgetType?): Float = 1f

    // --- Yerleşim (taslak 1872 genişlik: panel 440, boşluk 16, sahne 960; şerit 92, aralık 12) ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val k = w / DESIGN_W
        val stripH = context.dp(STRIP_H_DP)
        val gapV = context.dp(GAP_V_DP)
        val bandH = h - stripH - gapV
        val panelW = (PANEL_W * k).toInt()
        val gapH = (GAP_H * k).toInt()
        val stageW = w - 2 * panelW - 2 * gapH
        leftPanel.measure(exactly(panelW), exactly(bandH))
        rightPanel.measure(exactly(panelW), exactly(bandH))
        stage.measure(exactly(stageW), exactly(bandH + gapV))
        strip.measure(exactly(w), exactly(stripH))
        overlays.forEach { (ref, o) ->
            if (ref.kind == SahneSlotKind.PANEL) o.measure(exactly(panelW), exactly(bandH))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val stripH = context.dp(STRIP_H_DP)
        val gapV = context.dp(GAP_V_DP)
        val bandH = h - stripH - gapV
        val panelW = leftPanel.measuredWidth
        leftPanel.layout(0, 0, panelW, bandH)
        rightPanel.layout(w - panelW, 0, w, bandH)
        stage.layout(panelW + (w - 2 * panelW - stage.measuredWidth) / 2, 0, w - panelW - (w - 2 * panelW - stage.measuredWidth) / 2, bandH + gapV)
        strip.layout(0, h - stripH, w, h)
        overlays.forEach { (ref, o) ->
            if (ref.kind == SahneSlotKind.PANEL) {
                val p = if (ref.index == 0) leftPanel else rightPanel
                o.layout(p.left, p.top, p.right, p.bottom)
            } else {
                val v = strip.slotView(ref.index)
                if (v == null || v.width == 0) {
                    o.measure(exactly(0), exactly(0)); o.layout(0, 0, 0, 0)
                } else {
                    val left = strip.left + v.left
                    val top = strip.top + context.dp(8)
                    val bottom = strip.bottom - context.dp(8)
                    o.measure(exactly(v.width), exactly(bottom - top))
                    o.layout(left, top, left + v.width, bottom)
                }
            }
        }
    }

    /** Düzenlemede yuva örtüleri dokunuşu alır; sahne zaten kilitli. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(maxOf(0, px), MeasureSpec.EXACTLY)

    private companion object {
        const val DESIGN_W = 1872f
        const val PANEL_W = 440f
        const val GAP_H = 16f
        const val STRIP_H_DP = 92
        const val GAP_V_DP = 12
        const val EMPTY_ALPHA = 0.45f
        const val DISABLED_ALPHA = 0.45f
        const val PICKER_COLUMNS = 5
        const val PICKER_WIDTH_DP = 1280
        const val PICKER_TILE_DP = 136
        const val PICKER_LIST_DP = 400
    }
}
