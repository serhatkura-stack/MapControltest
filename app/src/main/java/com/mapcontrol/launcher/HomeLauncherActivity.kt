package com.mapcontrol.launcher

import android.app.Activity
import android.app.AlertDialog
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
    private val metricsListener = VehicleMetricsRepository.Listener { }

    private lateinit var header: HeaderBar
    private lateinit var editBar: View
    private lateinit var grid: LauncherGrid
    private lateinit var fixedWidgets: List<LauncherWidget>

    private var layout: List<PlacedWidget> = emptyList()
    private val gridWidgets = LinkedHashMap<String, SizedWidget>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherTheme.applyFrom(resources.configuration)
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        hideSystemBars()

        header = HeaderBar(this, onSettings = ::openSettings, onEdit = ::enterEditMode)
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

    private fun showAddDialog() {
        val types = WidgetType.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.launcher_edit_add)
            .setItems(types.map { getString(it.label) }.toTypedArray()) { _, which -> addWidget(types[which]) }
            .show()
    }

    private fun addWidget(type: WidgetType) {
        val base = PlacedWidget("${type.key}_${System.currentTimeMillis()}", type, 0, 0, type.sizes.first())
        // Önce türün varsayılan (ilk) boyutu denenir; sığmazsa sıradaki boyutlar.
        val spot = type.sizes.asSequence()
            .mapNotNull { LayoutStore.findSpot(layout, base.copy(span = it)) }
            .firstOrNull()
        if (spot == null) {
            Toast.makeText(this, R.string.launcher_edit_no_space, Toast.LENGTH_SHORT).show()
            return
        }
        applyLayout(layout + spot)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(R.string.launcher_edit_reset_confirm)
            .setPositiveButton(R.string.launcher_edit_reset) { _, _ ->
                store.reset()
                applyLayout(defaultLayout())
            }
            .setNegativeButton(R.string.launcher_nav_dialog_cancel, null)
            .show()
    }

    // --- Diğer ---

    private fun openSettings() {
        LauncherEdition.openSettings(this)
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
}
