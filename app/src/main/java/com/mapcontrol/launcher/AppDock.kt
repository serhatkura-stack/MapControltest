package com.mapcontrol.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R
import com.mapcontrol.util.AppIconHelper
import com.mapcontrol.util.AppLaunchHelper
import com.mapcontrol.util.ProjectionTargetApps

/**
 * Ana ekranın uygulama kısayol şeridi: sabit altı yuva.
 * Dolu yuvaya dokunmak uygulamayı açar; boş yuvaya dokunmak ya da dolu yuvaya uzun basmak seçiciyi açar.
 * Seçimler "launcher_apps" tercihlerinde "slot_<i>" anahtarıyla tutulur; ikon ve etiketler arka planda yüklenir.
 */
class AppDock(
    private val context: Context,
    /** Gösterilen yuva sayısı; alt çubukta altı. */
    private val slotCount: Int = SLOT_COUNT,
    private val slotWidthDp: Int = SLOT_WIDTH_DP,
) : LauncherWidget {

    /** Bir yuvada gösterilen uygulama: etiket ve ikon. */
    private class AppEntry(val label: String, val icon: Drawable)

    /** Yuvanın görsel parçaları; yalnızca ana iş parçacığında güncellenir. */
    private class Slot(val root: LinearLayout, val icon: ImageView, val label: TextView)

    private val iconRadius = context.dpF(ICON_RADIUS_DP)
    private val iconOutline = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(0, 0, v.width, v.height, iconRadius)
        }
    }

    private val prefs = context.getSharedPreferences(Profiles.key(PREFS_NAME, Profiles.current(context)), Context.MODE_PRIVATE)
    private val executor = LauncherIo.serial()

    /** Paket adı -> etiket ve ikon. Yalnızca ana iş parçacığında okunur ve yazılır. */
    private val cache = HashMap<String, AppEntry>()

    /** Yuvada şu an gösterilen dolu uygulamanın paketi; boş ya da bekleyen yuvalarda null. */
    private val current = arrayOfNulls<String>(slotCount)

    /** Boş yuvalar: artı yalnızca düzenleme modunda görünür, değilse yuva yerinde boş durur. */
    private val empty = BooleanArray(slotCount)
    private var editMode = false

    private val slots: List<Slot> = List(slotCount) { buildSlot(it) }

    /** Ayarlanmamış yuvaların paketleri: adaylardan kurulu olanlar, sırayla. */
    private val defaults: List<String> by lazy { installedDefaults(context, slotCount) }

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        slots.forEach { addView(it.root) }
    }

    /** Ekran görünür olunca yuvaları çizer; ikonlar önbellekten gelir, eksikler arka planda yüklenir. */
    override fun onVisible() = refresh()

    override fun onHidden() = dismissPicker()

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz; ayrılacak bir kaynak yok. */
    override fun release() = dismissPicker()

    private var pickerDialog: AlertDialog? = null

    private fun dismissPicker() {
        pickerDialog?.dismiss()
        pickerDialog = null
    }

    /** Düzenleme modunda boş yuvalar görünür ve seçilebilir olur; normalde gizlenir. */
    fun setEditMode(editing: Boolean) {
        if (editMode == editing) return
        editMode = editing
        for (i in 0 until slotCount) applyEmptyState(i)
    }

    /** Boş yuvanın artısını ve dokunmasını, düzenleme moduna göre açar ya da kapatır. */
    private fun applyEmptyState(i: Int) {
        val slot = slots[i]
        val show = !empty[i] || editMode
        // Düzenleme dışında boş yuva yer kaplamaz (GONE): dolu uygulamalar şeritte ortalanır.
        slot.root.visibility = if (show) View.VISIBLE else View.GONE
        slot.icon.visibility = View.VISIBLE
        slot.root.isClickable = show
        slot.root.isLongClickable = show
    }

    private fun buildSlot(index: Int): Slot {
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP))
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = iconOutline
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = text(context, DOCK_LABEL_SP).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            // Tüm adlar aynı boyutta; çok uzun olanlar küçülmek yerine "…" ile kısalır.
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                context.dp(slotWidthDp), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                // Aralık kenar boşluğu olarak: gizlenen yuvayla birlikte o da kaybolur.
                marginStart = context.dp(SLOT_GAP_DP / 2)
                marginEnd = context.dp(SLOT_GAP_DP / 2)
            }
            isClickable = true
            isFocusable = true
            addView(icon)
            addView(gap(context, 6, vertical = true))
            addView(label)
            setOnClickListener { onTap(index) }
            setOnLongClickListener {
                openPicker(index)
                true
            }
            pressFeedback()
        }
        return Slot(root, icon, label)
    }

    /** Kayıtlı seçimi okuyup yuvaları çizer; önbellekte olmayan paketleri arka planda çözer. */
    private fun refresh() {
        val packages = List(slotCount) { prefs.getString(slotKey(it), null) ?: defaults.getOrElse(it) { "" } }
        bindSlots(packages, cache)

        val snapshot = HashMap(cache)
        executor.runSafely {
            val resolved = packages.associateWith { pkg ->
                if (pkg.isEmpty()) null else (snapshot[pkg] ?: resolve(pkg))
            }
            view.post {
                resolved.forEach { (pkg, entry) ->
                    if (entry != null) {
                        cache[pkg] = entry
                    }
                }
                bindSlots(packages, resolved)
            }
        }
    }

    /**
     * Yuvaları çizer. Anahtar hiç yoksa yuva bekler (boş görünür, etiket yok);
     * anahtar varken değer null ise paket kurulu değildir ve yuva boş görünür.
     */
    private fun bindSlots(packages: List<String>, entries: Map<String, AppEntry?>) {
        packages.forEachIndexed { i, pkg ->
            val entry = entries[pkg]
            when {
                pkg.isEmpty() -> bindEmpty(i)
                !entries.containsKey(pkg) -> bindPending(i)
                entry != null -> bindFilled(i, pkg, entry)
                else -> bindEmpty(i)
            }
        }
    }

    private fun bindFilled(i: Int, pkg: String, entry: AppEntry) {
        val slot = slots[i]
        current[i] = pkg
        slot.icon.background = null
        slot.icon.imageTintList = null
        slot.icon.setPadding(0, 0, 0, 0)
        slot.icon.setImageDrawable(entry.icon)
        slot.label.text = entry.label
        slot.label.setTextColor(LauncherTheme.TEXT)
        slot.root.contentDescription = entry.label
        empty[i] = false
        applyEmptyState(i)
    }

    /** Boş yuva: CONTROL renkli yuvarlak kare içinde artı ikonu; etiket yok, şerit tekrar eden yazıyla dolmasın. */
    private fun bindEmpty(i: Int) {
        val slot = slots[i]
        current[i] = null
        empty[i] = true
        val pad = context.dp((ICON_DP - PLUS_DP) / 2)
        slot.icon.background = rounded(context, LauncherTheme.CONTROL, ICON_RADIUS_DP)
        slot.icon.imageTintList = ColorStateList.valueOf(LauncherTheme.TEXT_SECONDARY)
        slot.icon.setPadding(pad, pad, pad, pad)
        slot.icon.setImageResource(R.drawable.ic_mdi_plus)
        slot.label.text = ""
        slot.root.contentDescription = context.getString(R.string.launcher_app_empty)
        applyEmptyState(i)
    }

    /** Henüz çözülmemiş dolu yuva: ikon ve etiket gelene kadar boş bırakılır. */
    private fun bindPending(i: Int) {
        val slot = slots[i]
        current[i] = null
        empty[i] = false
        slot.icon.background = null
        slot.icon.setImageDrawable(null)
        slot.label.text = ""
        slot.root.contentDescription = null
        applyEmptyState(i)
    }

    private fun onTap(index: Int) {
        val pkg = current[index]
        if (pkg != null) launch(pkg) else openPicker(index)
    }

    /** Uygulamayı varsayılan ekranda açar. Java yardımcısı senkron olduğu için arka planda çalışır. */
    private fun launch(packageName: String) {
        executor.runSafely {
            AppLaunchHelper.launchAppOnDisplay(context, packageName, Display.DEFAULT_DISPLAY, 0)
        }
    }

    /** Başlatılabilir uygulamaları arka planda listeler; seçim yuvaya yazılır. */
    private fun openPicker(index: Int) {
        executor.runSafely {
            val rows = ProjectionTargetApps.loadAllLaunchableRows(context)
            view.post {
                if (rows.isEmpty()) return@post
                val host = context as? android.app.Activity
                if (host != null && (host.isFinishing || host.isDestroyed)) return@post
                dismissPicker()
                pickerDialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.launcher_app_pick_title)
                    .setItems(rows.map { it.label }.toTypedArray()) { _, which ->
                        assignSlot(index, rows[which].packageName)
                    }
                    .create().also { it.hideOemBarIfNeeded(context); it.show() }
            }
        }
    }

    private fun assignSlot(index: Int, packageName: String) {
        prefs.edit().putString(slotKey(index), packageName).apply()
        refresh()
    }

    /** Paketin etiketi ve ikonu; kurulu değilse null. Arka planda çağrılır. */
    private fun resolve(packageName: String): AppEntry? = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        val label = AppIdentity.label(context, packageName, pm.getApplicationLabel(info).toString())
        // Ikon dock boyutunda çözülür (gece: açık arka planlı adaptif ikonlar plakaya oturur).
        val plate = if (LauncherTheme.night) LauncherTheme.CONTROL else 0
        val icon = AppIdentity.overrideIcon(context, packageName, context.dp(ICON_DP))
            ?: AppIconHelper.loadSized(context, packageName, context.dp(ICON_DP), plate)
        AppEntry(label, icon)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun slotKey(index: Int) = "slot_$index"

    companion object {
        const val PREFS_NAME = "launcher_apps"

        const val SLOT_COUNT = 6
        const val SLOT_WIDTH_DP = 150
        const val DOCK_LABEL_SP = 20f
        /** Kurulu varsayılan uygulamalar (ilk [count] tanesi); ayarlanmamış yuvalar ve varsayılan düzen bunu kullanır. */
        fun installedDefaults(context: Context, count: Int): List<String> = DEFAULT_CANDIDATES.filter { pkg ->
            try {
                context.packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }.take(count)

        const val SLOT_GAP_DP = 8
        const val ICON_DP = 56
        const val PLUS_DP = 28
        const val ICON_RADIUS_DP = 16f

        /**
         * Yuvalar hiç ayarlanmamışken, bu sıradan kurulu olan ilk [SLOT_COUNT] uygulama kullanılır.
         * Sona aracın kendi uygulamaları eklendi: telefon uygulamaları yoksa şerit yine dolu görünür.
         */
        val DEFAULT_CANDIDATES = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.maps",
            "ru.yandex.yandexnavi",
            "com.waze",
            "com.desaysv.svaudioapp",
            "com.desaysv.bluetooth.phone",
            "com.desaysv.vehiclesetting",
            "com.desaysv.videoapp",
            "com.frostnav",
        )
    }
}
