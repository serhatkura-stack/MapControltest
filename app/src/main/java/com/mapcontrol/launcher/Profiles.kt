package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R

/**
 * Sürücü profilleri (1..[COUNT]). Her profilin kendi ana ekran düzeni, teması, uygulama yuvaları ve
 * navigasyon kısayolları vardır. Depolama: profil 1 mevcut tercih adlarını/anahtarlarını AYNEN kullanır
 * (taşıma yok, mevcut kullanıcı hiçbir şey kaybetmez); profil 2 ve 3 aynı adın sonuna "_p2"/"_p3" ekler.
 * Seçili profil numarası ayrı, profilden bağımsız bir tercih dosyasında tutulur.
 *
 * Araçtan otomatik seçim için bkz. [autoSelectFromVehicle] (henüz bağlı değil).
 */
object Profiles {
    const val COUNT = 3
    const val DEFAULT_ID = 1

    private const val PREFS = "launcher_profile"
    private const val KEY_CURRENT = "current"

    private val listeners = LinkedHashSet<(Int) -> Unit>()

    /** Geçersiz/bozuk numarayı 1..[COUNT] aralığına çeker; bilinmeyen değer profil 1 olur. */
    fun sanitize(id: Int): Int = if (id in 1..COUNT) id else DEFAULT_ID

    /** [base] tercih dosyası adı ya da anahtarı için profile özel ad. Profil 1 için [base] değişmez. */
    fun key(base: String, id: Int): String {
        val p = sanitize(id)
        return if (p == DEFAULT_ID) base else "${base}_p$p"
    }

    /** Geçerli profil için [key]. */
    fun key(context: Context, base: String): String = key(base, current(context))

    fun current(context: Context): Int =
        sanitize(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CURRENT, DEFAULT_ID))

    /** Profili değiştirir ve dinleyicilere haber verir; aynı profil seçiliyse hiçbir şey yapmaz. */
    fun select(context: Context, id: Int) {
        val p = sanitize(id)
        if (p == current(context)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CURRENT, p).apply()
        listeners.toList().forEach { it(p) }
    }

    fun addListener(l: (Int) -> Unit) { listeners.add(l) }

    fun removeListener(l: (Int) -> Unit) { listeners.remove(l) }

    /** Varsayılan ad ("Sürücü 1"); yeniden adlandırma sonra eklenecek. */
    fun displayName(context: Context, id: Int): String =
        context.getString(R.string.launcher_profile_default_name, sanitize(id))

    /**
     * YER TUTUCU, henüz bağlı DEĞİL ve bir şey okumaz. Araçtaki CarSetting/HUD `HUDID.ID_HUD_PERSONAL_ID`
     * (araçta 4 okundu) sürücü profilini belirleyebilir; eşleme ve davranış araç testi gerektirir.
     * Bağlanınca [personalId] -> profil numarası eşlemesi yapılıp [select] çağrılmalıdır.
     * @return seçilecek profil numarası; şimdilik her zaman null (otomatik seçim yok).
     */
    @Suppress("UNUSED_PARAMETER")
    fun autoSelectFromVehicle(personalId: Int): Int? = null
}

/** Profil seçici: büyük (≥76dp) satırlar, geçerli profil işaretli. Seçim [onSwitched] ile yeniden kurulumu tetikler. */
fun showProfileSheet(activity: Activity, onSwitched: () -> Unit): Dialog =
    showLauncherSheet(activity, R.string.launcher_profile) { d ->
        val current = Profiles.current(activity)
        for (id in 1..Profiles.COUNT) {
            if (id > 1) addView(gap(activity, 12, vertical = true))
            addView(profileRow(activity, id, id == current) {
                d.dismiss()
                if (id != current) {
                    Profiles.select(activity, id)
                    onSwitched()
                }
            }, sheetFullWidth())
        }
    }

/** Numaralı yuvarlak simge; seçili profilde vurgu rengi. */
fun profileAvatar(context: Context, id: Int, sizeDp: Int, selected: Boolean): TextView =
    text(context, LauncherTheme.BODY_SP, if (selected) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, bold = true).apply {
        setText(id.toString())
        gravity = Gravity.CENTER
        background = oval(if (selected) LauncherTheme.ACCENT else LauncherTheme.CONTROL)
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

private fun profileRow(activity: Activity, id: Int, selected: Boolean, onClick: () -> Unit): LinearLayout =
    LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(activity, LauncherTheme.CONTROL, 16f)
        minimumHeight = activity.dp(ROW_DP)
        val p = activity.dp(24)
        setPadding(p, activity.dp(8), p, activity.dp(8))
        addView(profileAvatar(activity, id, 48, selected))
        addView(gap(activity, 20))
        addView(text(activity, LauncherTheme.BODY_SP, medium = true).apply { text = Profiles.displayName(activity, id) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) addView(icon(activity, R.drawable.ic_mdi_check, 32, LauncherTheme.ACCENT))
        contentDescription = Profiles.displayName(activity, id)
        isSelected = selected
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        pressFeedback()
    }

private const val ROW_DP = 80
