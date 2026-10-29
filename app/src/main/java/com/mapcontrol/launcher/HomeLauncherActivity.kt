package com.mapcontrol.launcher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.mapcontrol.R
import com.mapcontrol.vehicle.VehicleMetricsRepository

/**
 * Yeni ana ekran (HOME). MapControl'ün geri kalanına "Ayarlar" düğmesinden girilir.
 *
 * Yerleşim (yukarıdan aşağı): üst şerit 72dp (düzenleme modunda düzenleme çubuğu) · düzenlenebilir
 * widget ızgarası · klima ve uygulama kartı 120dp. Tüm widget'lar yalnızca ekran görünürken çalışır.
 */
class HomeLauncherActivity : Activity(), LauncherGrid.Listener {

    private val store by lazy { LayoutStore(this) }
    private val metrics by lazy { VehicleMetricsRepository(this) }
    private val settings by lazy { LauncherSettings(this, onEditHome = ::enterEditMode) }
    private val metricsListener = VehicleMetricsRepository.Listener { }

    private lateinit var header: HeaderBar
    private lateinit var editBar: View
    private lateinit var grid: LauncherGrid
    private lateinit var fixedWidgets: List<LauncherWidget>

    private var layout: List<PlacedWidget> = emptyList()
    private val gridWidgets = LinkedHashMap<String, SizedWidget>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherDemo.applyFrom(this, intent)
        LauncherProbe.applyFrom(this, intent)
        LauncherTheme.applyFrom(resources.configuration, ThemeMode.read(this))
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        hideSystemBars()

        header = HeaderBar(this, onSettings = ::openSettings)
        val apps = AppDock(this)
        val climate = ClimateBar(this, apps.view)
        fixedWidgets = listOf(header, apps, climate)
        editBar = buildEditBar()
        grid = LauncherGrid(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LauncherTheme.GROUND)
            setPadding(dp(24), dp(20), dp(24), dp(20))

            addView(FrameLayout(context).apply {
                addView(header.view)
                addView(editBar)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.HEADER_HEIGHT_DP)))
            addView(gap(context, 16, vertical = true))
            addView(grid, weightLp(1f, vertical = true))
            addView(gap(context, 16, vertical = true))
            addView(climate.view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.CLIMATE_HEIGHT_DP)))
        }
        setContentView(root)
        applyLayout(store.load(), persist = false)
    }

    override fun onStart() {
        super.onStart()
        started = true
        metrics.addListener(metricsListener)
        fixedWidgets.forEach { it.onVisible() }
        gridWidgets.values.forEach { it.onVisible() }
    }

    override fun onStop() {
        started = false
        exitEditMode()
        metrics.removeListener(metricsListener)
        fixedWidgets.forEach { it.onHidden() }
        gridWidgets.values.forEach { it.onHidden() }
        super.onStop()
    }

    override fun onDestroy() {
        fixedWidgets.forEach { it.release() }
        gridWidgets.values.forEach { it.release() }
        metrics.release()
        settings.release()
        super.onDestroy()
    }

    /** Ana ekrandayken geri tuşu yalnızca düzenleme modundan çıkar. */
    @Deprecated("Activity.onBackPressed, API 33 öncesi araçta geçerli yol")
    override fun onBackPressed() {
        if (grid.editMode) exitEditMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- Izgara ---

    override fun onLayoutChanged(layout: List<PlacedWidget>) = applyLayout(layout)

    override fun onLongPressEmpty() = enterEditMode()

    /**
     * Yeni düzeni uygular: kaldırılan widget'lar durdurulup bırakılır, yenileri üretilir,
     * hepsine yeni boyutu bildirilir. Mevcut widget'lar yeniden yaratılmaz.
     */
    private fun applyLayout(newLayout: List<PlacedWidget>, persist: Boolean = true) {
        val ids = newLayout.map { it.id }.toSet()
        gridWidgets.keys.filter { it !in ids }.forEach { id ->
            gridWidgets.remove(id)?.let { w ->
                if (started) w.onHidden()
                w.release()
            }
        }
        newLayout.forEach { placed ->
            val widget = gridWidgets[placed.id] ?: WidgetFactory.create(this, placed).also { created ->
                gridWidgets[placed.id] = created
                created.applySpan(placed.span)
                if (started) created.onVisible()
            }
            widget.applySpan(placed.span)
        }
        layout = newLayout
        grid.setLayout(newLayout, gridWidgets.mapValues { it.value.view })
        if (persist) store.save(newLayout)
    }

    // --- Düzenleme modu ---

    /** Düzenleme yalnızca araç dururken açılır; sürüş sırasında ekranla uğraşılmasın. */
    private fun enterEditMode() {
        if (grid.editMode) return
        if (metrics.currentSnapshot().preferredSpeed() > 0) {
            Toast.makeText(this, R.string.launcher_parked_only, Toast.LENGTH_SHORT).show()
            return
        }
        grid.editMode = true
        header.view.visibility = View.INVISIBLE
        editBar.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        if (!grid.editMode) return
        grid.editMode = false
        editBar.visibility = View.GONE
        header.view.visibility = View.VISIBLE
    }

    private fun buildEditBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, LauncherTheme.SECTION_SP, bold = true).apply { setText(R.string.launcher_edit_title) })
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(R.string.launcher_edit_hint) })
        })
        addView(View(context), weightLp(1f))
        addView(barButton(R.drawable.ic_mdi_plus, R.string.launcher_edit_add, LauncherTheme.CARD) { showAddDialog() })
        addView(gap(context, 16))
        addView(barButton(R.drawable.ic_mdi_restart, R.string.launcher_edit_reset, LauncherTheme.CARD) { confirmReset() })
        addView(gap(context, 16))
        addView(barButton(R.drawable.ic_mdi_check, R.string.launcher_edit_done, LauncherTheme.ACCENT) { exitEditMode() })
    }

    private fun barButton(iconRes: Int, labelRes: Int, background: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        this.background = rounded(context, background)
        setPadding(dp(24), 0, dp(24), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(LauncherTheme.TOUCH_MIN_DP))
        addView(icon(context, iconRes, 28))
        addView(gap(context, 12))
        addView(text(context, LauncherTheme.BODY_SP, medium = true).apply { setText(labelRes) })
        contentDescription = getString(labelRes)
        isClickable = true
        setOnClickListener { onClick() }
        pressFeedback()
    }

    /**
     * "Widget ekle" paneli: beş sütunlu kutucuklar, her birinde ikon, ad ve boyutlar.
     * Izgarada yer kalmayan türler soluk ve "Yer yok" der; dokunmak hiçbir şey eklemez.
     */
    private fun showAddDialog() {
        showLauncherSheet(this, R.string.launcher_edit_add, ADD_SHEET_WIDTH_DP) { d ->
            WidgetType.values().toList().chunked(ADD_COLUMNS).forEachIndexed { r, rowTypes ->
                if (r > 0) addView(gap(context, 12, vertical = true))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (c in 0 until ADD_COLUMNS) {
                        if (c > 0) addView(gap(context, 12))
                        val type = rowTypes.getOrNull(c)
                        val lp = LinearLayout.LayoutParams(0, dp(ADD_TILE_HEIGHT_DP), 1f)
                        if (type == null) addView(View(context), lp) else addView(addTile(type) { d.dismiss() }, lp)
                    }
                }, sheetFullWidth())
            }
        }
    }

    private fun addTile(type: WidgetType, dismiss: () -> Unit): View {
        val spot = spotFor(type)
        val fits = spot != null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, LauncherTheme.CONTROL, 16f)
            val p = dp(16)
            setPadding(dp(20), p, dp(20), p)
            addView(icon(context, type.icon, 32, if (fits) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY))
            addView(gap(context, 8, vertical = true))
            addView(text(context, LauncherTheme.BODY_SP, if (fits) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY, medium = true).apply {
                setText(type.label)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(gap(context, 4, vertical = true))
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                text = if (fits) type.sizes.joinToString("  ·  ") else getString(R.string.launcher_edit_no_room)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            alpha = if (fits) 1f else NO_ROOM_ALPHA
            contentDescription = getString(type.label)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (spot != null) {
                    dismiss()
                    applyLayout(layout + spot)
                }
            }
            pressFeedback()
        }
    }

    /** Türün ızgarada sığdığı ilk yer; önce varsayılan (ilk) boyut, sığmazsa sıradakiler. */
    private fun spotFor(type: WidgetType): PlacedWidget? {
        val base = PlacedWidget("${type.key}_${System.currentTimeMillis()}", type, 0, 0, type.sizes.first())
        return type.sizes.asSequence().mapNotNull { LayoutStore.findSpot(layout, base.copy(span = it)) }.firstOrNull()
    }

    private fun confirmReset() {
        showLauncherSheet(this, R.string.launcher_edit_reset, RESET_SHEET_WIDTH_DP) { d ->
            addView(text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 3).apply {
                setText(R.string.launcher_edit_reset_confirm)
            }, sheetFullWidth())
            addView(gap(context, 32, vertical = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(sheetButton(R.string.launcher_nav_dialog_cancel, primary = false) { d.dismiss() }, weightLp(1f))
                addView(gap(context, 12))
                addView(sheetButton(R.string.launcher_edit_reset, primary = true) {
                    d.dismiss()
                    store.reset()
                    applyLayout(defaultLayout())
                }, weightLp(1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.TOUCH_MIN_DP)))
        }
    }

    private fun sheetButton(labelRes: Int, primary: Boolean, onClick: () -> Unit): View =
        text(this, LauncherTheme.BODY_SP, if (primary) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
            setText(labelRes)
            gravity = Gravity.CENTER
            background = rounded(context, if (primary) LauncherTheme.ACCENT else LauncherTheme.CONTROL, 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    // --- Diğer ---

    private fun openSettings() {
        settings.show()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars())
            // Gündüz görünümünde gezinme çubuğu simgeleri açık zeminde görünür kalsın.
            it.setSystemBarsAppearance(
                if (LauncherTheme.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } ?: run {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                if (LauncherTheme.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private companion object {
        const val ADD_COLUMNS = 5
        const val ADD_SHEET_WIDTH_DP = 1280
        const val ADD_TILE_HEIGHT_DP = 136
        const val RESET_SHEET_WIDTH_DP = 640
        const val NO_ROOM_ALPHA = 0.45f
    }
}
