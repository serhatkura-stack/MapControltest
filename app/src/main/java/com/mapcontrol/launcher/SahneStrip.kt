package com.mapcontrol.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.mapcontrol.R

/** Şerit öğesinin vurgu tonu: sıcaklık (turuncu), soğutma, açık (buz mavisi), uyarı, sessiz (veri yok). */
enum class StripTone { NORMAL, MUTED, ON, HEAT, COOL, ALERT }

/**
 * Şerit öğesinin içinde ayrı dokunulan küçük düğme (ör. buğu giderme Ön / Arka). [on] null = bilinmiyor.
 * [artKey] verilirse `launcher_art_<artKey>` görseli (varsa) ikonun yerine çizilir ([SahneArt]).
 */
data class StripPart(val label: String, val state: String, val on: Boolean?, @DrawableRes val icon: Int, val artKey: String? = null)

/**
 * Değiştirilebilir illüstrasyonlar: bir şerit/panel öğesinin görseli res/drawable altındaki `launcher_art_<anahtar>` adlı bir
 * kaynaktır (ör. raster `launcher_art_seat_heat_driver.webp`); kaynak yoksa ince çizgili vektör ikon kullanılır. Anahtar
 * widget türünün kaydedilen anahtarıdır ([WidgetType.key]); parçalar için ayrı anahtar ("defrost_front"), panel zemini
 * için "panel_<anahtar>". Kod değişmeden görsel eklenip çıkarılabilir; arama bir kez yapılıp önbelleğe alınır.
 */
object SahneArt {
    private val ids = HashMap<String, Int>()

    /** `launcher_art_<key>` kimliği; yoksa 0. */
    fun resId(context: android.content.Context, key: String): Int = ids.getOrPut(key) {
        @Suppress("DiscouragedApi")
        context.resources.getIdentifier("launcher_art_$key", "drawable", context.packageName)
    }

    fun drawable(context: android.content.Context, key: String?): Drawable? {
        if (key == null) return null
        val id = resId(context, key)
        return if (id == 0) null else context.getDrawable(id)
    }

    /** Şerit/panel öğesinin illüstrasyonu (varsa). */
    fun illustration(context: android.content.Context, type: WidgetType): Drawable? = drawable(context, type.key)

    /** Panel zemini görseli (varsa); camın üstüne, içeriğin altına serilir. */
    fun panelBackground(context: android.content.Context, type: WidgetType): Drawable? = drawable(context, "panel_" + type.key)
}

/**
 * Bir widget'ın Sahne şeridindeki (ve sahnedeki notlardaki) özeti. Widget kendi verisinden üretir; şerit yalnızca çizer.
 * [level]/[levels] seviye dilimleri (koltuk ısıtma), [parts] ayrı dokunulan küçük düğmeler, [iconDrawable] uygulama ikonu.
 */
data class StripState(
    val title: String,
    val value: String,
    @DrawableRes val icon: Int,
    val tone: StripTone = StripTone.NORMAL,
    val level: Int = 0,
    val levels: Int = 0,
    val detail: String? = null,
    val parts: List<StripPart> = emptyList(),
    val iconDrawable: Drawable? = null,
    /** Panel biçiminde değerin altında gösterilen ek satırlar (etiket, değer); [alertRows] uyarı renginde olanlar. */
    val rows: List<Pair<String, String>> = emptyList(),
    val alertRows: Set<Int> = emptySet(),
)

/**
 * Şerit biçimini destekleyen widget: durumunu [stripState] ile verir, değişince [onStripChanged]'i çağırır ve
 * dokunuşu kutucuktaki ile aynı yoldan ([stripTap]) işler; yazma kuralları (WidgetWritePolicy, park kapısı, doğrulama
 * bayrakları, onaylar) widget'ın kendi kodunda kalır. Desteklemeyen widget şeritte başlık + ikonla görünür ve
 * dokunuş kutucuğun kök görünümüne ([View.performClick]) gider.
 */
interface StripSource {
    fun stripState(): StripState?
    var onStripChanged: (() -> Unit)?
    /** [part]: [StripState.parts] içindeki düğme (yoksa 0). */
    fun stripTap(part: Int = 0)
    /** Uzun basış (ör. uygulama seçici); işlendiyse true. */
    fun stripLongPress(): Boolean = false
}

/** Widget renginden şerit tonu: uyarı, ikincil (veri yok) ve vurgu renkleri tanınır. */
internal fun stripToneFor(color: Int): StripTone = when (color) {
    LauncherTheme.ALERT -> StripTone.ALERT
    LauncherTheme.TEXT_SECONDARY -> StripTone.MUTED
    LauncherTheme.ACCENT -> StripTone.ON
    LauncherTheme.HEAT -> StripTone.HEAT
    LauncherTheme.COOL -> StripTone.COOL
    else -> StripTone.NORMAL
}

/** Tonun Sahne rengi; [NORMAL] için [fallback]. */
internal fun stripToneColor(tone: StripTone, fallback: Int): Int = when (tone) {
    StripTone.HEAT -> Sahne.AMBER
    StripTone.COOL -> Sahne.COOL
    StripTone.ON -> Sahne.ICE
    StripTone.ALERT -> Sahne.ALERT
    StripTone.MUTED -> Sahne.INK_3
    StripTone.NORMAL -> fallback
}

/**
 * Sahne şeridinin bir öğesi (taslaktaki ".seg"): ikon, küçük başlık, değer; seviyesi olan widget'ta sağda dilimler;
 * [StripState.parts] varsa başlığın yanında ayrı dokunulan küçük düğmeler. Yalnızca durum değişince yeniden bağlanır.
 */
@SuppressLint("ViewConstructor")
internal class StripItemView(
    context: Context,
    private val onTap: (Int) -> Unit,
    private val onLong: () -> Boolean,
    /** Değiştirilebilir illüstrasyon ([SahneArt]); varsa vektör ikonun yerine renklendirilmeden çizilir. */
    private val art: Drawable? = null,
) : LinearLayout(context) {

    private val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = text(context, TITLE_SP).apply { SahneFonts.apply(this, 400) }
    private val value = text(context, VALUE_SP).apply { SahneFonts.apply(this, 600) }
    private val texts = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
    }
    private val pips = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val partsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var last: StripState? = null
    private var iconIsApp = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(PAD_DP), 0, context.dp(PAD_DP), 0)
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
        addView(iconView, LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
        addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(16) })
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(pips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(partsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap(0) }
        setOnLongClickListener { onLong() }
        pressFeedback()
    }

    fun bind(s: StripState) {
        if (s == last) return
        val prev = last
        last = s
        val tint = stripToneColor(s.tone, Sahne.INK)
        val active = s.tone == StripTone.HEAT || s.tone == StripTone.COOL || s.tone == StripTone.ON || s.tone == StripTone.ALERT
        if (s.iconDrawable != null || art != null) {
            iconView.setImageDrawable(s.iconDrawable ?: art)
            iconView.imageTintList = null
            if (!iconIsApp) { iconIsApp = true; resizeIcon(APP_ICON_DP) }
        } else {
            if (prev?.icon != s.icon || prev.iconDrawable != null) iconView.setImageResource(sahneIconFor(s.icon))
            iconView.imageTintList = ColorStateList.valueOf(if (active) tint else Sahne.INK_2)
            if (iconIsApp) { iconIsApp = false; resizeIcon(ICON_DP) }
        }
        title.text = s.title
        title.setTextColor(Sahne.INK_3)
        title.visibility = if (s.title.isEmpty()) View.GONE else View.VISIBLE
        value.setTextSmooth(s.value)
        value.setTextColor(if (s.tone == StripTone.NORMAL) Sahne.INK else tint)
        value.visibility = if (s.value.isEmpty()) View.GONE else View.VISIBLE
        bindPips(s)
        bindParts(s)
        contentDescription = listOf(s.title, s.value, s.detail).filter { !it.isNullOrEmpty() }.joinToString(", ")
    }

    private fun resizeIcon(dp: Int) {
        iconView.layoutParams = (iconView.layoutParams as LayoutParams).apply { width = context.dp(dp); height = context.dp(dp) }
    }

    private fun bindPips(s: StripState) {
        if (s.levels <= 0 || s.parts.isNotEmpty()) {
            pips.visibility = View.GONE
            return
        }
        pips.visibility = View.VISIBLE
        if (pips.childCount != s.levels) {
            pips.removeAllViews()
            repeat(s.levels) { i ->
                pips.addView(View(context), LayoutParams(context.dp(PIP_W_DP), context.dp(PIP_H_DP)).apply { if (i > 0) marginStart = context.dp(5) })
            }
        }
        val heat = s.tone == StripTone.COOL
        for (i in 0 until s.levels) {
            val on = i < s.level
            pips.getChildAt(i).background = if (!on) rounded(context, Sahne.TRACK, PIP_H_DP / 2f) else GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (heat) intArrayOf(Sahne.COOL, Sahne.COOL) else intArrayOf(Sahne.AMBER_HI, Sahne.AMBER),
            ).apply { cornerRadius = context.dpF(PIP_H_DP / 2f) }
        }
    }

    private fun bindParts(s: StripState) {
        if (s.parts.isEmpty()) {
            partsRow.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            return
        }
        // Parçalı öğede (buğu giderme) büyük ikon yok: başlık solda, düğmeler kendi ikonlarıyla.
        iconView.visibility = View.GONE
        (texts.layoutParams as LayoutParams).marginStart = 0
        partsRow.visibility = View.VISIBLE
        if (partsRow.childCount != s.parts.size) {
            partsRow.removeAllViews()
            s.parts.forEachIndexed { i, _ ->
                partsRow.addView(MiniButton(context) { onTap(i) }, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(LauncherTheme.TOUCH_MIN_DP)).apply {
                    marginStart = context.dp(if (i == 0) 0 else 14)
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
        }
        s.parts.forEachIndexed { i, p -> (partsRow.getChildAt(i) as MiniButton).bind(p) }
    }

    /** Parça düğmesi (taslaktaki ".mini"): ikon + ad + durum; açıkken sıcak turuncu zemin ve çizgi. */
    private class MiniButton(context: Context, onClick: () -> Unit) : LinearLayout(context) {
        private val icon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val name = text(context, 18f).apply { SahneFonts.apply(this, 600) }
        private val state = text(context, 14f).apply { SahneFonts.apply(this, 400) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(18), 0, context.dp(18), 0)
            minimumWidth = context.dp(LauncherTheme.TOUCH_MIN_DP)
            addView(icon, LayoutParams(context.dp(26), context.dp(26)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(state, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(10) })
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

        fun bind(p: StripPart) {
            val on = p.on == true
            val art = SahneArt.drawable(context, p.artKey)
            if (art != null) {
                icon.setImageDrawable(art)
                icon.imageTintList = null
            } else {
                icon.setImageResource(sahneIconFor(p.icon))
                icon.imageTintList = ColorStateList.valueOf(if (on) Sahne.AMBER else Sahne.INK_2)
            }
            name.text = p.label
            name.setTextColor(if (on) Sahne.AMBER else if (p.on == null) Sahne.INK_3 else Sahne.INK)
            state.setTextSmooth(p.state)
            state.setTextColor(if (on) Sahne.AMBER_SUB else Sahne.INK_3)
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(18f)
                setColor(if (on) Sahne.AMBER_SOFT else Sahne.SOFT_FILL)
                setStroke(context.dp(1), if (on) Sahne.AMBER_LINE else Sahne.HAIR)
            }
            contentDescription = p.label + ", " + p.state
        }
    }

    companion object {
        const val PAD_DP = 22
        const val ICON_DP = 30
        const val APP_ICON_DP = 40
        const val TITLE_SP = 15f
        const val VALUE_SP = 21f
        const val PIP_W_DP = 22
        const val PIP_H_DP = 6
    }
}

/**
 * Şeritte iki uygulama kısayolu yan yana (taslakta YouTube Music · Maps). Her yarı bir [AppShortcutTile]'dır; seçim ve
 * açma onun kodundadır (uzun basış uygulama seçici). Burada yalnızca ikon + ad yatay çizilir.
 */
@SuppressLint("ViewConstructor")
internal class AppPairStripView(context: Context, private val tiles: List<AppShortcutTile>) : LinearLayout(context) {

    private val buttons = tiles.map { tile ->
        AppButton(context, onClick = { tile.stripTap(0) }, onLong = { tile.stripLongPress() })
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), 0, context.dp(12), 0)
        // Ad uzunluğu kadar (taslakta "YouTube Music" ve "Maps" yan yana); yer kalmazsa ad kısalır.
        buttons.forEachIndexed { i, b ->
            addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(LauncherTheme.TOUCH_MIN_DP), 1f).apply { if (i > 0) marginStart = context.dp(4) })
        }
        tiles.forEachIndexed { i, t ->
            t.onStripChanged = { t.stripState()?.let { buttons[i].bind(it) } }
            t.stripState()?.let { buttons[i].bind(it) }
        }
    }

    private class AppButton(context: Context, onClick: () -> Unit, onLong: () -> Boolean) : LinearLayout(context) {
        private val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val label = text(context, 18f).apply { SahneFonts.apply(this, 500) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(12), 0)
            addView(icon, LayoutParams(context.dp(40), context.dp(40)))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12) })
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnLongClickListener { onLong() }
            pressFeedback()
        }

        fun bind(s: StripState) {
            if (s.iconDrawable != null) {
                icon.setImageDrawable(s.iconDrawable)
                icon.imageTintList = null
                icon.background = null
                icon.setPadding(0, 0, 0, 0)
            } else {
                // Boş yuva: sakin artı.
                icon.setImageResource(s.icon)
                icon.imageTintList = ColorStateList.valueOf(Sahne.INK_3)
                val p = context.dp(8)
                icon.setPadding(p, p, p, p)
                icon.background = rounded(context, Sahne.SOFT_FILL, 12f, Sahne.HAIR)
            }
            label.text = s.value
            label.setTextColor(if (s.tone == StripTone.MUTED) Sahne.INK_3 else Sahne.INK)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            contentDescription = s.value
        }
    }
}

/** Başlık + değer satırının taslak boyutlarıyla Sahne'ye uygun TextView'i. */
internal fun sahneText(context: Context, sizeSp: Float, color: Int, weight: Int, letterSpacingEm: Float = 0f, dayBump: Boolean = true): TextView =
    text(context, sizeSp, color).apply { SahneFonts.apply(this, weight, letterSpacingEm, dayBump) }

/** Kutucukların MDI ikonlarının Sahne'deki ince çizgili karşılıkları (yoksa aynısı). */
@DrawableRes
internal fun sahneIconFor(@DrawableRes res: Int): Int = when (res) {
    R.drawable.ic_mdi_car_seat_heater, R.drawable.ic_mdi_car_seat_vent -> R.drawable.ic_sahne_seat
    R.drawable.ic_mdi_car_sunroof -> R.drawable.ic_sahne_roof
    R.drawable.ic_mdi_car_trunk -> R.drawable.ic_sahne_trunk
    R.drawable.ic_mdi_defrost_front -> R.drawable.ic_sahne_defrost_front
    R.drawable.ic_mdi_defrost_rear -> R.drawable.ic_sahne_defrost_rear
    R.drawable.ic_mdi_music_note -> R.drawable.ic_sahne_note
    R.drawable.ic_mdi_road_variant -> R.drawable.ic_sahne_route
    R.drawable.ic_mdi_lock -> R.drawable.ic_sahne_lock
    R.drawable.ic_mdi_lock_open_variant -> R.drawable.ic_sahne_lock_open
    R.drawable.ic_mdi_car -> R.drawable.ic_sahne_car
    R.drawable.ic_mdi_air_filter -> R.drawable.ic_sahne_wind
    R.drawable.ic_mdi_fan -> R.drawable.ic_sahne_fan
    else -> res
}
