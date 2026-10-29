package com.mapcontrol.launcher

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mapcontrol.R

/**
 * "Nereye?" kartı: adres arama alanı ile Ev / İş kısayolları.
 * Dokunma, tercih edilen navigasyon uygulamasını açar (Yandex Navi, yoksa Google Maps).
 * Zamanlayıcı ya da dinleyici yok; kısayol metinleri yalnızca görünür olunca tazelenir.
 *
 * İki yerleşim bir kez kurulur ve [applySpan] ile görünürlük değiştirilerek geçilir:
 * h >= 2 için tam yerleşim, h == 1 (2×1) için tek satırlık sıkışık yerleşim.
 */
class NavigationCard(private val context: Context) : SizedWidget {

    private val store = NavShortcutStore(context)

    private val title = text(context, LauncherTheme.SECTION_SP, bold = true).apply {
        setText(R.string.launcher_nav_title)
    }
    private val titleGap = gap(context, 24, vertical = true)

    private val homeAddress = addressText()
    private val workAddress = addressText()

    private val shortcutRow = LinearLayout(context)

    /** Son hedef satırları (3×2 ve üstü); kayıt yoksa boş ve görünmez kalır. */
    private val recentsBox = LinearLayout(context)
    private var recents: List<NavRecent> = emptyList()
    private var recentRowsFit = 0

    /** Tam yerleşim: başlık, arama alanı, boşluk, altta Ev / İş düğmeleri. */
    private val fullLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(titleGap)
        addView(searchField())
        addView(recentsBox.apply { orientation = LinearLayout.VERTICAL })
        // Kısayollar kartın altına yaslanır; medya kartındaki kontrollerle aynı hizada durur.
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(gap(context, 16, vertical = true))
        addView(shortcutRow.apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(LauncherTheme.MEDIA_BUTTON_DP),
            )
            addView(shortcutButton(NavPlace.HOME, R.drawable.ic_mdi_home, R.string.launcher_nav_home, homeAddress), weightLp(1f))
            addView(gap(context, 16))
            addView(shortcutButton(NavPlace.WORK, R.drawable.ic_mdi_briefcase, R.string.launcher_nav_work, workAddress), weightLp(1f))
        })
    }

    /** Sıkışık yerleşim (2×1): başlıksız, tek satırda üç eşit düğme (arama, Ev, İş); adres metni yok. */
    private val compactLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        val h = LinearLayout.LayoutParams(0, context.dp(LauncherTheme.TOUCH_MIN_DP), 1f)
        addView(compactSearchButton(), LinearLayout.LayoutParams(h))
        addView(gap(context, COMPACT_GAP_DP))
        addView(shortcutButton(NavPlace.HOME, R.drawable.ic_mdi_home, R.string.launcher_nav_home, null), LinearLayout.LayoutParams(h))
        addView(gap(context, COMPACT_GAP_DP))
        addView(shortcutButton(NavPlace.WORK, R.drawable.ic_mdi_briefcase, R.string.launcher_nav_work, null), LinearLayout.LayoutParams(h))
    }

    override val view: View = card(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(fullLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        ))
        addView(compactLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { fitHeight(v.height) }
        }
    }

    init {
        bindAddresses()
        bindRecents()
    }

    /**
     * Izgara boyutuna göre yerleşimi seçer: h == 1 ise sıkışık, aksi halde tam.
     * Görünümler yeniden kurulmaz; yalnızca görünürlük ve kart iç boşluğu değişir.
     */
    override fun applySpan(span: Span) {
        val compact = span.h <= 1
        fullLayout.visibility = if (compact) View.GONE else View.VISIBLE
        compactLayout.visibility = if (compact) View.VISIBLE else View.GONE
        // 150dp'lik tek hücreye 72dp'lik düğme sığsın diye sıkışık kartta iç boşluk azalır.
        val p = context.dp(if (compact) COMPACT_PADDING_DP else LauncherTheme.PADDING_DP)
        view.setPadding(p, p, p, p)
    }

    /** Kart kısaysa başlığı gizler, çok kısaysa kısayol düğmelerini küçültür; arama alanı kalır. */
    private fun fitHeight(heightPx: Int) {
        val visibility = if (heightPx < context.dp(COMPACT_HEIGHT_DP)) View.GONE else View.VISIBLE
        if (title.visibility != visibility) {
            title.visibility = visibility
            titleGap.visibility = visibility
        }
        // Sıkışık (tek satır) kartta düğmeler iç yüksekliğe sığacak kadar küçülür; alt kenardan kesilmesin.
        if (compactLayout.visibility == View.VISIBLE) {
            val inner = heightPx - view.paddingTop - view.paddingBottom
            val h = minOf(context.dp(LauncherTheme.TOUCH_MIN_DP), inner).coerceAtLeast(context.dp(48))
            for (i in 0 until compactLayout.childCount step 2) {
                val child = compactLayout.getChildAt(i)
                val clp = child.layoutParams
                if (clp.height != h) {
                    clp.height = h
                    child.layoutParams = clp
                }
            }
        }
        val buttonDp = if (heightPx < context.dp(TIGHT_HEIGHT_DP)) LauncherTheme.TOUCH_MIN_DP else LauncherTheme.MEDIA_BUTTON_DP
        val lp = shortcutRow.layoutParams
        if (lp != null && lp.height != context.dp(buttonDp)) {
            lp.height = context.dp(buttonDp)
            shortcutRow.layoutParams = lp
        }
        // Son hedef satırları: arama ile kısayollar arasında kalan boşluğa sığdığı kadar.
        val titleBlock = if (title.visibility == View.VISIBLE) title.height + titleGap.height else 0
        val fixed = view.paddingTop + view.paddingBottom + titleBlock +
            context.dp(LauncherTheme.TOUCH_MIN_DP) + context.dp(16 + 16) + context.dp(buttonDp)
        val rowPx = context.dp(RECENT_ROW_DP + RECENT_GAP_DP)
        val fit = ((heightPx - fixed) / rowPx).coerceIn(0, NavShortcutStore.MAX_RECENTS)
        if (fit != recentRowsFit) {
            recentRowsFit = fit
            showRecentRows()
        }
    }

    private fun addressText(): TextView = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY)

    /** 72dp'lik arama satırı; dokununca navigasyon uygulaması aramaya açılır. */
    private fun searchField(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(context, LauncherTheme.GROUND, 20f)
        val h = context.dp(24)
        setPadding(h, 0, h, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(LauncherTheme.TOUCH_MIN_DP),
        )
        addView(icon(context, R.drawable.ic_mdi_magnify, 28, LauncherTheme.TEXT_SECONDARY))
        addView(gap(context, 16))
        addView(text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY).apply {
            setText(R.string.launcher_nav_search_hint)
        })
        contentDescription = context.getString(R.string.launcher_nav_search_hint)
        isClickable = true
        isFocusable = true
        setOnClickListener { openSearch() }
        pressFeedback()
    }

    /** Sıkışık arama düğmesi: ikon ve kısaltılmış ipucu, tek satır; dokununca aramaya açılır. */
    private fun compactSearchButton(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(context, LauncherTheme.CONTROL, 20f)
        val p = context.dp(COMPACT_BUTTON_PAD_DP)
        setPadding(p, 0, p, 0)
        addView(icon(context, R.drawable.ic_mdi_magnify, 24, LauncherTheme.TEXT_SECONDARY))
        addView(gap(context, 8))
        addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
            setText(R.string.launcher_nav_search_short)
        })
        contentDescription = context.getString(R.string.launcher_nav_search_hint)
        isClickable = true
        isFocusable = true
        setOnClickListener { openSearch() }
        pressFeedback()
    }

    /**
     * Ev / İş düğmesi: kısa dokunma yönlendirir (adres yoksa düzenler), uzun basma düzenler.
     * [address] null ise sıkışık biçim: yalnızca ikon ve ad, ortalanmış.
     */
    private fun shortcutButton(kind: NavPlace, iconRes: Int, nameRes: Int, address: TextView?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (address == null) Gravity.CENTER else Gravity.CENTER_VERTICAL
            background = rounded(context, LauncherTheme.CONTROL, 20f)
            val p = context.dp(if (address == null) COMPACT_BUTTON_PAD_DP else 24)
            setPadding(p, 0, p, 0)
            if (address == null) {
                addView(icon(context, iconRes, 24))
                addView(gap(context, 8))
                addView(text(context, LauncherTheme.LABEL_SP, medium = true).apply { setText(nameRes) })
            } else {
                addView(icon(context, iconRes, 32))
                addView(gap(context, 16))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(context, LauncherTheme.BODY_SP, medium = true).apply { setText(nameRes) })
                    addView(address)
                }, weightLp(1f).apply { width = 0; height = LinearLayout.LayoutParams.WRAP_CONTENT })
            }
            contentDescription = context.getString(nameRes)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val saved = store.get(kind)
                if (saved.isEmpty()) showEditDialog(kind) else navigateTo(saved)
            }
            setOnLongClickListener {
                showEditDialog(kind)
                true
            }
            pressFeedback()
        }

    /** Kayıtlı adresleri düğme metinlerine yazar; boşsa "ayarlanmadı" yazısı çıkar. */
    private fun bindAddresses() {
        bind(homeAddress, store.get(NavPlace.HOME))
        bind(workAddress, store.get(NavPlace.WORK))
    }

    /** Son hedefleri okuyup satırları yeniden kurar (en çok 3 görünüm, dinleyici/iş parçacığı yok). */
    private fun bindRecents() {
        val latest = store.recents()
        if (latest == recents && recentsBox.childCount == latest.size) return
        recents = latest
        recentsBox.removeAllViews()
        for (r in latest) {
            recentsBox.addView(gap(context, RECENT_GAP_DP, vertical = true))
            recentsBox.addView(recentRow(r))
        }
        showRecentRows()
    }

    /** Sığan satırları gösterir; her satır kendi aralığıyla birlikte (gap + satır) çiftidir. */
    private fun showRecentRows() {
        for (i in 0 until recentsBox.childCount) {
            recentsBox.getChildAt(i).visibility = if (i / 2 < recentRowsFit) View.VISIBLE else View.GONE
        }
    }

    /** Büyük dokunma satırı: ikon + tek satır ad; dokununca aynı navigasyon yeniden başlar. */
    private fun recentRow(r: NavRecent): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(context, LauncherTheme.CONTROL, 20f)
        val p = context.dp(24)
        setPadding(p, 0, p, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(RECENT_ROW_DP))
        addView(icon(context, R.drawable.ic_mdi_map_marker, 32, LauncherTheme.TEXT_SECONDARY))
        addView(gap(context, 16))
        addView(text(context, LauncherTheme.BODY_SP, medium = true).apply { text = r.label }, weightLp(1f).apply { height = LinearLayout.LayoutParams.WRAP_CONTENT })
        contentDescription = r.label
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(r.uri))
            if (r.pkg.isNotEmpty()) intent.setPackage(r.pkg)
            launch(intent)
        }
        pressFeedback()
    }

    private fun bind(target: TextView, address: String) {
        if (address.isEmpty()) target.setText(R.string.launcher_nav_not_set) else target.text = address
    }

    /** Adres düzenleme penceresi; boş kayıt adresi siler. */
    private fun showEditDialog(kind: NavPlace) {
        val input = EditText(context).apply {
            setHint(R.string.launcher_nav_dialog_hint)
            setText(store.get(kind))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val titleRes = if (kind == NavPlace.HOME) R.string.launcher_nav_dialog_home else R.string.launcher_nav_dialog_work
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.launcher_nav_dialog_save) { _, _ ->
                store.set(kind, input.text.toString())
                bindAddresses()
            }
            .setNegativeButton(R.string.launcher_nav_dialog_cancel, null)
            .show()
    }

    /** Arama alanı: Yandex Navi'yi açar, yoksa Google Maps aramasını. */
    private fun openSearch() {
        val intent = when {
            isInstalled(YANDEX) -> context.packageManager.getLaunchIntentForPackage(YANDEX)
            isInstalled(GOOGLE_MAPS) -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).setPackage(GOOGLE_MAPS)
            else -> null
        }
        launch(intent)
    }

    /** Adrese rota başlatır. */
    private fun navigateTo(address: String) {
        val encoded = Uri.encode(address)
        val intent = when {
            isInstalled(YANDEX) -> Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://map_search?text=$encoded")).setPackage(YANDEX)
            isInstalled(GOOGLE_MAPS) -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).setPackage(GOOGLE_MAPS)
            else -> null
        }
        launch(intent)
        if (intent != null) {
            store.addRecent(address, intent.dataString.orEmpty(), intent.`package`.orEmpty())
            bindRecents()
        }
    }

    private fun launch(intent: Intent?) {
        if (intent == null) {
            noApp()
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            noApp()
        }
    }

    private fun isInstalled(pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun noApp() = Toast.makeText(context, R.string.launcher_nav_no_app, Toast.LENGTH_LONG).show()

    override fun onVisible() {
        bindAddresses()
        bindRecents()
    }

    override fun onHidden() {
        // Çalışan bir şey yok.
    }

    private companion object {
        const val YANDEX = "ru.yandex.yandexnavi"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        const val COMPACT_HEIGHT_DP = 330
        const val TIGHT_HEIGHT_DP = 230
        const val COMPACT_PADDING_DP = 16
        const val COMPACT_GAP_DP = 12
        const val COMPACT_BUTTON_PAD_DP = 12
        const val RECENT_ROW_DP = 76
        const val RECENT_GAP_DP = 12
    }
}
