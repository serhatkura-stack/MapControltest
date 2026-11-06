package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.mapcontrol.R
import com.mapcontrol.util.AppLaunchHelper
import java.text.DateFormatSymbols

/**
 * Sahne Telefon paneli (4×4): üstte küçük "TELEFON" başlığı ve bağlı telefonun adı, tam genişlikte "Sesli asistan" düğmesi
 * (bağlı telefonun Siri / Google asistanı), favori kişiler (baş harfli yuvarlak kare avatar; dokun: ara, basılı tut:
 * sabitle) ve son aramalar (cevapsızlar kırmızı; dokun: geri ara). Veri yalnızca [PhoneSource]'tan, olay güdümlü; görünmezken
 * dinleme yok. Bölümler yalnızca verisi değişince yeniden kurulur; tek zamanlayıcı göreli saat yazılarının dakikalık
 * tazelemesidir (yazı değişmedikçe çizim yok).
 *
 * [sheet]: alt çubuktaki telefon düğmesinin açtığı sayfadaki aynı görünüm (sayfanın kendi başlığı olduğu için "TELEFON"
 * etiketi gizli); [onDialed] arama başlayınca sayfayı kapatır.
 *
 * GİZLİLİK: isim ve numaralar yalnızca ekrana yazılır; günlüğe hiçbir şey yazılmaz.
 */
internal class PhonePanel(
    private val activity: Activity,
    private val sheet: Boolean = false,
    private val onDialed: (() -> Unit)? = null,
) : SizedWidget, PhoneListener {

    private val context: Context = activity
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var snap: PhoneSnapshot? = null
    private var state: PhoneUiLogic.PanelState? = null
    private var permBlocked = false
    private var columns = PhoneUiLogic.favoriteColumns(if (sheet) SHEET_WIDTH_GUESS_DP else PANEL_WIDTH_GUESS_DP)
    private var shownFavorites: List<PhoneContact>? = null
    private var shownRecents: List<PhoneRecent>? = null
    /** Son aramalardaki zaman yazıları ve zamanları (dakikalık tazeleme için). */
    private val timeLabels = ArrayList<Pair<TextView, Long>>()

    // --- Başlık ---
    private val headIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_sahne_phone)
        imageTintList = ColorStateList.valueOf(Sahne.INK_3)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val headLabel = sahneText(context, LABEL_SP, Sahne.INK_3, 600, 0.12f).apply { setText(R.string.launcher_phone_label) }
    private val deviceText = sahneText(context, DEVICE_SP, Sahne.INK_2, 500).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = if (sheet) Gravity.START else Gravity.END
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (!sheet) {
            addView(headIcon, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
            addView(headLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(10) })
        }
        addView(deviceText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (!sheet) marginStart = context.dp(16) })
    }

    // --- Sesli asistan ---
    private val assistantSub = sahneText(context, ASSIST_SUB_SP, Sahne.CTA_INK, 400).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0.72f
    }
    private val assistant = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ctaBackground()
        setPadding(context.dp(24), 0, context.dp(20), 0)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_sahne_assistant)
            imageTintList = ColorStateList.valueOf(Sahne.CTA_INK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sahneText(context, ASSIST_SP, Sahne.CTA_INK, 600).apply {
                setText(R.string.launcher_phone_assistant)
                maxLines = 1
            })
            addView(assistantSub)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(18) })
        contentDescription = context.getString(R.string.launcher_phone_assistant)
        isClickable = true
        isFocusable = true
        setOnClickListener { startAssistant() }
        pressFeedback()
    }

    // --- Favoriler ve son aramalar ---
    private val favLabel = sectionLabel(R.string.launcher_phone_favorites)
    /** Sayfada "Favoriler" altında: sabitlemenin nasıl yapıldığı (uzun basış görünmez bir hareket). */
    private val pinHint = sahneText(context, PIN_HINT_SP, Sahne.INK_3, 400).apply {
        setText(R.string.launcher_phone_pin_hint)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0.8f
    }
    private val favRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol) return@addOnLayoutChangeListener
            val c = PhoneUiLogic.favoriteColumns(Math.round((r - l) / v.resources.displayMetrics.density))
            if (c == columns) return@addOnLayoutChangeListener
            columns = c
            // Yerleşim geçişinin içinde görünüm eklenmez: bir sonraki turda yeniden kurulur.
            v.post { shownFavorites?.let { bindFavorites(it) } }
        }
    }
    private val recentsLabel = sectionLabel(R.string.launcher_phone_recents)
    private val recentsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Panelde "Favoriler" başlığı yok (avatarlar kendini anlatır, yer dar); sayfada var.
        if (sheet) {
            addView(favLabel, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(pinHint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(2); bottomMargin = context.dp(8) })
        }
        addView(favRow, LinearLayout.LayoutParams(MATCH, context.dp(FAV_CELL_H_DP)))
        addView(recentsLabel, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(16) })
        addView(recentsBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(2) })
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    // --- Boş / bağlı değil / izin / yükleniyor ---
    private val msgTitle = sahneText(context, MSG_SP, Sahne.INK_2, 500).apply { gravity = Gravity.CENTER; maxLines = 2 }
    private val msgSub = sahneText(context, MSG_SUB_SP, Sahne.INK_3, 400).apply { gravity = Gravity.CENTER; maxLines = 2 }
    private val msgButtonText = sahneText(context, BUTTON_SP, Sahne.CTA_INK, 600)
    private var msgAction: (() -> Unit)? = null
    private val msgButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ctaBackground()
        minimumWidth = context.dp(MSG_BUTTON_MIN_W_DP)
        setPadding(context.dp(28), 0, context.dp(28), 0)
        addView(msgButtonText)
        isClickable = true
        isFocusable = true
        setOnClickListener { msgAction?.invoke() }
        pressFeedback()
    }
    private val message = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(msgTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(msgSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(6) })
        addView(msgButton, LinearLayout.LayoutParams(WRAP, context.dp(TOUCH_DP)).apply { topMargin = context.dp(18) })
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
    }

    private val body = FrameLayout(context).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(message, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (!sheet) setPadding(context.dp(24), context.dp(22), context.dp(24), context.dp(18))
        addView(header, LinearLayout.LayoutParams(MATCH, context.dp(HEADER_DP)))
        addView(assistant, LinearLayout.LayoutParams(MATCH, context.dp(if (sheet) ASSIST_SHEET_DP else ASSIST_PANEL_DP)).apply {
            topMargin = context.dp(if (sheet) 8 else 12)
        })
        addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = context.dp(14) })
    }

    /**
     * Göreli zaman yazıları dakika sınırında (yalnız görünürken ve zaman yazılı satır varken); yazı değişmedikçe çizim
     * olmaz.
     */
    private val minuteTick = Runnable {
        refreshTimes()
        scheduleMinuteTick()
    }

    private fun scheduleMinuteTick() {
        main.removeCallbacks(minuteTick)
        if (!visible || timeLabels.isEmpty() || state != PhoneUiLogic.PanelState.CONTENT) return
        main.postDelayed(minuteTick, MINUTE_MS - (System.currentTimeMillis() % MINUTE_MS))
    }

    init { bind(PhoneSnapshot.EMPTY) }

    override val view: View get() = root
    override fun applySpan(span: Span) = Unit

    override fun onVisible() {
        if (visible) return
        visible = true
        PhoneSource.start(activity, this)
        PhoneSource.addListener(this)
        refreshTimes()
        scheduleMinuteTick()
    }

    override fun onHidden() {
        if (!visible) return
        visible = false
        main.removeCallbacks(minuteTick)
        PhoneSource.removeListener(this)
        PhoneSource.stop(this)
    }

    override fun release() = onHidden()

    override fun onPhoneChanged(snapshot: PhoneSnapshot) {
        if (snapshot == snap) return
        bind(snapshot)
    }

    override fun onPhoneError(error: PhoneError) {
        if (!visible || !root.isAttachedToWindow) return
        PhoneToast.show(context, PhoneUiLogic.errorRes(error))
    }

    // --- Bağlama ---

    private fun bind(s: PhoneSnapshot) {
        snap = s
        val device = s.deviceName?.trim()?.takeIf { s.connected && it.isNotEmpty() }
        // Bağlı değilken başlık sağı boş: aynı bilgi gövdede ("Bağlı telefon yok") yazılı, iki kez yazılmaz.
        setTextIfChanged(deviceText, device.orEmpty())
        // Sayfada başlık satırı yalnız telefon adını taşır: ad yoksa satır kalkar (sayfanın kendi "Telefon" başlığı var).
        if (sheet) header.visibility = if (device == null) View.GONE else View.VISIBLE
        setTextIfChanged(assistantSub, device.orEmpty())
        assistantSub.visibility = if (device == null) View.GONE else View.VISIBLE
        // Bağlı telefon yokken asistan çubuğu kalkar: gövdedeki tek Bluetooth düğmesi panelin tek eylemi olur.
        // Bağlı ama asistan yolu yoksa soluk ve dokunulamaz.
        val canAssist = s.connected && s.canAssistant
        assistant.visibility = if (s.connected) View.VISIBLE else View.GONE
        assistant.alpha = if (canAssist) 1f else DISABLED_ALPHA
        assistant.isEnabled = canAssist
        assistant.contentDescription = listOfNotNull(context.getString(R.string.launcher_phone_assistant), device).joinToString(", ")

        val st = PhoneUiLogic.panelState(s)
        if (st != state || (st == PhoneUiLogic.PanelState.PERMISSION && s.permissionBlocked != permBlocked)) {
            state = st
            permBlocked = s.permissionBlocked
            applyState(st)
        }
        if (st == PhoneUiLogic.PanelState.CONTENT) {
            if (s.favorites != shownFavorites) bindFavorites(s.favorites)
            if (s.recents != shownRecents) bindRecents(s.recents)
        }
    }

    private fun applyState(st: PhoneUiLogic.PanelState) {
        val content = st == PhoneUiLogic.PanelState.CONTENT
        scroll.visibility = if (content) View.VISIBLE else View.GONE
        message.visibility = if (content) View.GONE else View.VISIBLE
        if (content) return
        // Gövde gizlenince bir sonraki içerik hâlinde baştan kurulur; zaman yazısı kalmaz.
        shownFavorites = null
        shownRecents = null
        timeLabels.clear()
        main.removeCallbacks(minuteTick)
        when (st) {
            PhoneUiLogic.PanelState.NO_PHONE -> showMessage(R.string.launcher_phone_not_connected, R.string.launcher_phone_not_connected_sub,
                R.string.launcher_phone_bluetooth) { openOemBluetooth(context) }
            // İzin reddedildi ve sistem yeniden sormuyor: istek düğmesi boşa döner, uygulama ayarları açılır.
            PhoneUiLogic.PanelState.PERMISSION -> if (permBlocked) {
                showMessage(R.string.launcher_phone_permission, R.string.launcher_phone_permission_settings_sub, R.string.launcher_phone_open_settings) {
                    openAppSettings()
                }
            } else {
                showMessage(R.string.launcher_phone_permission, 0, R.string.launcher_phone_grant) { PhoneSource.requestPermissions(activity) }
            }
            PhoneUiLogic.PanelState.LOADING -> showMessage(R.string.launcher_phone_loading, 0, 0, null)
            PhoneUiLogic.PanelState.EMPTY -> showMessage(R.string.launcher_phone_empty, R.string.launcher_phone_empty_sub, 0, null)
            PhoneUiLogic.PanelState.CONTENT -> Unit
        }
    }

    private fun showMessage(@StringRes title: Int, @StringRes sub: Int, @StringRes button: Int, action: (() -> Unit)?) {
        msgTitle.setText(title)
        msgSub.visibility = if (sub == 0) View.GONE else View.VISIBLE
        if (sub != 0) msgSub.setText(sub)
        msgButton.visibility = if (button == 0) View.GONE else View.VISIBLE
        if (button != 0) {
            msgButtonText.setText(button)
            msgButton.contentDescription = context.getString(button)
        }
        msgAction = action
        message.contentDescription = listOfNotNull(context.getString(title), if (sub != 0) context.getString(sub) else null).joinToString(", ")
    }

    /** Favori hücreleri: en çok [columns] kişi; eksik hücreler boş kalır ki genişlikler eşit olsun. */
    private fun bindFavorites(list: List<PhoneContact>) {
        shownFavorites = list
        favRow.removeAllViews()
        val shown = list.take(columns)
        shown.forEach { c -> favRow.addView(favoriteCell(c), LinearLayout.LayoutParams(0, MATCH, 1f)) }
        if (shown.isNotEmpty()) repeat(columns - shown.size) { favRow.addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f)) }
        val has = shown.isNotEmpty()
        favRow.visibility = if (has) View.VISIBLE else View.GONE
        favLabel.visibility = favRow.visibility
        pinHint.visibility = favRow.visibility
        // Favori yoksa son aramalar başlığı üstte boşluksuz başlar.
        (recentsLabel.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(if (has) 16 else 0)
    }

    private fun favoriteCell(c: PhoneContact): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            background = rounded(context, Sahne.SOFT_FILL, AVATAR_RADIUS_DP, Sahne.HAIR_2)
            addView(sahneText(context, INITIALS_SP, Sahne.INK, 500).apply {
                text = c.initials
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }, LinearLayout.LayoutParams(context.dp(AVATAR_DP), context.dp(AVATAR_DP)))
        addView(sahneText(context, NAME_SP, Sahne.INK_2, 500).apply {
            text = c.name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8); marginStart = context.dp(4); marginEnd = context.dp(4) })
        contentDescription = context.getString(R.string.launcher_phone_call_cd, c.name)
        isClickable = true
        isFocusable = true
        setOnClickListener { dial(c.number) }
        if (PhoneUiLogic.canPin(c.id)) setOnLongClickListener { togglePin(c); true }
        pressFeedback()
    }

    private fun bindRecents(list: List<PhoneRecent>) {
        shownRecents = list
        recentsBox.removeAllViews()
        timeLabels.clear()
        val rows = PhoneUiLogic.recentRows(list)
        rows.forEachIndexed { i, r ->
            if (i > 0) recentsBox.addView(View(context).apply { setBackgroundColor(Sahne.HAIR) }, LinearLayout.LayoutParams(MATCH, context.dp(1)))
            recentsBox.addView(recentRow(r), LinearLayout.LayoutParams(MATCH, context.dp(TOUCH_DP)))
        }
        recentsLabel.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        scheduleMinuteTick()
    }

    private fun recentRow(r: PhoneUiLogic.RecentRow): View {
        val tint = if (r.missed) Sahne.CALL_MISSED else Sahne.INK_3
        val time = sahneText(context, TIME_SP, Sahne.INK_3, 400).apply {
            maxLines = 1
            text = timeText(r.timeMs)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (r.timeMs > 0L) timeLabels += time to r.timeMs
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(when (r.type) {
                    RecentType.MISSED -> R.drawable.ic_sahne_call_missed
                    RecentType.INCOMING -> R.drawable.ic_sahne_call_in
                    RecentType.OUTGOING -> R.drawable.ic_sahne_call_out
                })
                imageTintList = ColorStateList.valueOf(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(sahneText(context, ROW_SP, if (r.missed) Sahne.CALL_MISSED else Sahne.INK, 500).apply {
                text = r.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(16) })
            addView(time, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(12) })
            contentDescription = context.getString(R.string.launcher_phone_call_cd, r.label) + ", " + time.text
            isClickable = true
            isFocusable = true
            setOnClickListener { dial(r.number) }
            pressFeedback()
        }
    }

    private fun refreshTimes() = timeLabels.forEach { (tv, t) -> setTextIfChanged(tv, timeText(t)) }

    /** Zamanı bilinmeyen kayıt (tarih çözülemedi, 0) için yazı yok. */
    private fun timeText(timeMs: Long): String = if (timeMs <= 0L) "" else when (val rel = PhoneUiLogic.relativeTime(System.currentTimeMillis(), timeMs)) {
        PhoneUiLogic.RelTime.Now -> context.getString(R.string.launcher_time_now)
        is PhoneUiLogic.RelTime.Minutes -> context.getString(R.string.launcher_time_minutes, rel.n)
        is PhoneUiLogic.RelTime.Hours -> context.getString(R.string.launcher_time_hours, rel.n)
        PhoneUiLogic.RelTime.Yesterday -> context.getString(R.string.launcher_time_yesterday)
        is PhoneUiLogic.RelTime.Date -> {
            val locale = context.resources.configuration.locales[0]
            val month = DateFormatSymbols.getInstance(locale).shortMonths.getOrNull(rel.monthIndex).orEmpty().trimEnd('.')
            "${rel.day} $month"
        }
    }

    // --- Eylemler (yalnız kullanıcı dokunuşuyla) ---

    /** Başarısızlık nedeni [onPhoneError] ile tek uyarı olarak gelir (burada ikinci uyarı gösterilmez). */
    private fun startAssistant() {
        PhoneSource.startVoiceAssistant()
    }

    /**
     * Önce karar verilir: bağlı telefon var ama arama yolu yoksa doğrudan OEM telefon uygulaması (hata uyarısı yok).
     * Aksi halde arama denenir; başarısızlık nedeni [onPhoneError] ile tek uyarı olarak gelir.
     */
    private fun dial(number: String) {
        val s = PhoneSource.snapshot()
        if (s.connected && !s.canDial) {
            openOemPhoneApp(context)
            return
        }
        if (PhoneSource.dial(number)) onDialed?.invoke()
    }

    /** Uygulama ayrıntıları (izinler); ayar ekranı yoksa kısa uyarı. */
    private fun openAppSettings() {
        try {
            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            PhoneToast.show(context, R.string.launcher_call_not_supported)
        }
    }

    /** Uzun basış: sabitle / sabitlemeyi kaldır (yalnız kimlik yazılır); bellekteki veri yeniden sıralanır. */
    private fun togglePin(c: PhoneContact) {
        val pinned = PhonePrefs.pinnedIds(context)
        val wasPinned = c.id in pinned
        PhonePrefs.setPinnedIds(context, PhoneUiLogic.togglePin(pinned, c.id))
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        PhoneToast.show(context, if (wasPinned) R.string.launcher_phone_unpinned else R.string.launcher_phone_pinned)
        PhoneSource.reloadFavorites()
    }

    // --- Parçalar ---

    private fun sectionLabel(@StringRes res: Int): TextView = sahneText(context, SECTION_SP, Sahne.INK_3, 600).apply {
        setText(res)
        maxLines = 1
    }

    private fun ctaBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Sahne.CTA_TOP, Sahne.CTA_BOTTOM),
    ).apply {
        cornerRadius = context.dpF(Sahne.SQUARE_RADIUS_DP)
        if (Sahne.CTA_RING != 0) setStroke(context.dp(1), Sahne.CTA_RING)
    }

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val HEADER_DP = 40
        const val ASSIST_PANEL_DP = 84
        const val ASSIST_SHEET_DP = 96
        const val TOUCH_DP = 76
        const val FAV_CELL_H_DP = 112
        const val AVATAR_DP = 72
        const val AVATAR_RADIUS_DP = 22f
        const val MSG_BUTTON_MIN_W_DP = 220
        /** Ölçülmeden önceki sütun tahmini: panel içi ~392dp, sayfa içi ~700dp. */
        const val PANEL_WIDTH_GUESS_DP = 392
        const val SHEET_WIDTH_GUESS_DP = 700
        const val LABEL_SP = 16f
        const val DEVICE_SP = 18f
        const val ASSIST_SP = 22f
        const val ASSIST_SUB_SP = 17f
        const val SECTION_SP = 16f
        const val INITIALS_SP = 26f
        const val NAME_SP = 16f
        const val ROW_SP = 20f
        const val TIME_SP = 17f
        const val PIN_HINT_SP = 15f
        const val MSG_SP = 22f
        const val MSG_SUB_SP = 18f
        const val BUTTON_SP = 20f
        const val DISABLED_ALPHA = 0.4f
        const val MINUTE_MS = 60_000L
    }
}

/** Yalnızca metin gerçekten değişince yazar (gereksiz yerleşim/çizim olmasın). */
internal fun setTextIfChanged(tv: TextView, value: CharSequence) {
    if (tv.text?.toString() != value.toString()) tv.text = value
}

/** OEM Bluetooth telefon uygulamasını varsayılan ekranda açar (arka planda; kurulu değilse bir şey yapmaz). */
internal fun openOemPhoneApp(context: Context) {
    LauncherIo.serial().runSafely { AppLaunchHelper.launchAppOnDisplay(context, OEM_PHONE_PACKAGE, Display.DEFAULT_DISPLAY, 0) }
}

/**
 * Telefon uyarıları tek bir Toast'tan: panel, sayfa ve arama şeridi aynı hatayı aynı anda dinlediği için aynı metin 1,5 sn
 * içinde bir kez gösterilir; yenisi eskisinin yerini alır.
 */
internal object PhoneToast {
    private var toast: Toast? = null
    private var lastRes = 0
    private var lastAt = 0L

    fun show(context: Context, @StringRes res: Int) {
        val now = SystemClock.uptimeMillis()
        if (res == lastRes && now - lastAt < DEDUP_MS) return
        lastRes = res
        lastAt = now
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, res, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** Son [ms] içinde (herhangi bir) telefon uyarısı gösterildi mi. */
    fun shownWithin(ms: Long): Boolean = lastAt != 0L && SystemClock.uptimeMillis() - lastAt < ms

    private const val DEDUP_MS = 1_500L
}
