package com.mapcontrol.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes

/** Launcher widget'larının ortak sözleşmesi: görünürken çalışır, gizlenince her şeyi durdurur. */
interface LauncherWidget {
    val view: View

    /** Ekran görünür oldu (onStart). Dinleyiciler ve zamanlayıcılar burada başlar. */
    fun onVisible()

    /** Ekran görünmez oldu (onStop). Tüm güncellemeler burada durur. */
    fun onHidden()

    /** Activity yok ediliyor; kalıcı kaynakları bırak. */
    fun release() {}
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

fun rounded(
    context: Context,
    color: Int,
    radiusDp: Float = LauncherTheme.CARD_RADIUS_DP,
    strokeColor: Int = 0,
): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dpF(radiusDp)
        if (strokeColor != 0) setStroke(context.dp(LauncherTheme.BORDER_DP), strokeColor)
    }

/** Kart/şerit yüzeyi: gündüz 1dp kenarlı (açık zeminde ayrışsın), gece kenarsız. */
fun cardSurface(context: Context, color: Int = LauncherTheme.CARD, radiusDp: Float = LauncherTheme.CARD_RADIUS_DP) =
    rounded(context, color, radiusDp, LauncherTheme.CARD_BORDER)

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Kart kabuğu: koyu yüzey, 20dp köşe, iç boşluk. Gölge yok; ayrım gecede tondan, gündüz ince kenardan gelir. */
fun card(context: Context, orientation: Int = LinearLayout.VERTICAL, paddingDp: Int = LauncherTheme.PADDING_DP): LinearLayout =
    LinearLayout(context).apply {
        this.orientation = orientation
        background = cardSurface(context)
        val p = context.dp(paddingDp)
        setPadding(p, p, p, p)
    }

/** Rakamlar sabit genişlikte (tnum) olsun ki saat ve sıcaklık değişirken metin zıplamasın. */
fun text(
    context: Context,
    sizeSp: Float,
    color: Int = LauncherTheme.TEXT,
    bold: Boolean = false,
    medium: Boolean = false,
    maxLines: Int = 1,
): TextView = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = when {
        bold -> Typeface.create("sans-serif", Typeface.BOLD)
        medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    fontFeatureSettings = "tnum"
    includeFontPadding = false
    this.maxLines = maxLines
    ellipsize = android.text.TextUtils.TruncateAt.END
}

private val UNIT_RE = Regex("^(-?[0-9][0-9.,]*)\\s+(km/h|km|L/100 km|L/100|V|°C)$")

/**
 * "540 km" -> "540" normal boyutta + " km" küçük ve ikincil tonda. Eşleşmeyen metin olduğu gibi döner.
 * Yalnızca bir kez, değer değişince çağrılır; kare başı iş yok.
 */
fun unitStyled(value: CharSequence): CharSequence {
    val m = UNIT_RE.find(value) ?: return value
    val num = m.groupValues[1]
    val sp = android.text.SpannableStringBuilder(num).append(' ')
    val start = sp.length
    sp.append(m.groupValues[2])
    sp.setSpan(android.text.style.RelativeSizeSpan(0.6f), start - 1, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    sp.setSpan(android.text.style.ForegroundColorSpan(LauncherTheme.TEXT_SECONDARY), start - 1, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    return sp
}

/**
 * Metni yalnızca gerçekten değiştiyse yazar; ekranda görünen bir kutucukta kısa (150ms) alfa geçişiyle.
 * Aynı metinde hiçbir şey yapmaz (her yoklamada animasyon yok); yalnızca alfa, yerleşimi etkilemez.
 */
fun TextView.setTextSmooth(value: CharSequence) {
    if (text.toString() == value.toString()) {
        // Renk aralıkları değişmiş olabilir (tema); metin aynıysa yine de yeni aralıkları ata.
        text = value
        return
    }
    val visible = isAttachedToWindow && width > 0 && alpha == 1f && text.isNotEmpty()
    text = value
    if (visible) {
        animate().cancel()
        alpha = 0.35f
        animate().alpha(1f).setDuration(LauncherTheme.CROSSFADE_MS).start()
    }
}

fun icon(context: Context, @DrawableRes res: Int, sizeDp: Int, tint: Int = LauncherTheme.TEXT): ImageView =
    ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

/**
 * Yuvarlak ya da köşeli ikon düğmesi. Boyut sürüş için en az [LauncherTheme.TOUCH_MIN_DP].
 * [label] erişilebilirlik ve gerektiğinde ikonun altındaki metin içindir.
 */
fun iconButton(
    context: Context,
    @DrawableRes res: Int,
    label: String,
    sizeDp: Int = LauncherTheme.TOUCH_MIN_DP,
    background: Int = LauncherTheme.CONTROL,
    tint: Int = LauncherTheme.TEXT,
    circle: Boolean = false,
    iconDp: Int = (sizeDp * 0.45f).toInt(),
    onClick: () -> Unit,
): FrameLayout = FrameLayout(context).apply {
    this.background = if (circle) oval(background) else rounded(context, background)
    contentDescription = label
    isClickable = true
    isFocusable = true
    layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    addView(icon(context, res, iconDp, tint).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(iconDp), context.dp(iconDp), Gravity.CENTER)
    })
    setOnClickListener { onClick() }
    pressFeedback()
}

/** Basınca hafif küçülme (0.98, 120ms); tek ve sakin bir dokunma geri bildirimi. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFeedback() {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(LauncherTheme.PRESS_SCALE).scaleY(LauncherTheme.PRESS_SCALE)
                .setDuration(LauncherTheme.PRESS_DURATION_MS).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                .setDuration(LauncherTheme.PRESS_DURATION_MS).start()
        }
        false
    }
}

fun weightLp(weight: Float, vertical: Boolean = false): LinearLayout.LayoutParams =
    if (vertical) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)
    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)

fun gap(context: Context, dp: Int, vertical: Boolean = false): View = View(context).apply {
    layoutParams = if (vertical) LinearLayout.LayoutParams(1, context.dp(dp)) else LinearLayout.LayoutParams(context.dp(dp), 1)
}

/**
 * Arka plan işini güvenle çalıştırır: executor kapandıysa sessizce atlar, iş içindeki hata
 * (binder, PackageManager, ikon çözme) uygulamayı çökertmez, yalnızca loglanır.
 */
fun java.util.concurrent.ExecutorService.runSafely(block: () -> Unit) {
    if (isShutdown) return
    try {
        execute {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("MapControlLauncher", "Arka plan işi başarısız", e)
            }
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        // Widget kaldırılırken gelen geç iş; yapılacak bir şey yok.
    }
}
