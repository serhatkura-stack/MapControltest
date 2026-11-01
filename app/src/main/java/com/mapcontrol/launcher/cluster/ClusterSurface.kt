package com.mapcontrol.launcher.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.util.AppLaunchHelper

/**
 * Katman A: kadran (ekran 2) üzerinde tek, salt-okur, animasyonsuz kart penceresi.
 * Yalnızca WindowManager.addView; VDBus/CarInfo'ya yazmaz. Ana iş parçacığından kullanılır.
 * Kart yalnızca içerik DEĞİŞİNCE yeniden çizilir (zamanlayıcı/animasyon yok).
 */
class ClusterSurface(context: Context) {

    /** Kart türleri. Eşitlik (data class) değişim denetimi için kullanılır. */
    sealed class Card {
        data class Media(val title: String, val artist: String, val source: String) : Card()
        /** rangeKm / fuelPercent: bilinmiyorsa -1. */
        data class Vehicle(val rangeKm: Int, val fuelPercent: Int, val label: String, val unit: String) : Card()
        data class Maneuver(val text: String, val distance: String) : Card()
        data class Message(val text: String) : Card()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var gridView: View? = null
    private var cardLp: WindowManager.LayoutParams? = null
    private var attachedDisplayId = -1
    private var scale = 1f

    private var wanted = false
    private var pending: Card? = null
    private var shown: Card? = null
    private var lastRenderUptime = 0L
    private var renderScheduled = false

    private var overrideX: Int? = null
    private var overrideY: Int? = null
    private var gridOn = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.i(TAG, "display added id=$displayId")
            handler.post { reattach() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.i(TAG, "display removed id=$displayId")
            handler.post { if (displayId == attachedDisplayId) detachView(); reattach() }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplayId) {
                Log.i(TAG, "display changed id=$displayId ${describe(displayManager?.getDisplay(displayId))}")
            }
        }
    }

    fun show(card: Card?) {
        pending = card
        if (!wanted) {
            wanted = true
            displayManager?.registerDisplayListener(displayListener, handler)
            attachIfNeeded()
        }
        scheduleRender()
    }

    /** Hata ayıklama: konumu canlı kaydır (piksel, pencere sol-üst). null = varsayılan. */
    fun setPositionOverride(x: Int?, y: Int?) {
        overrideX = x
        overrideY = y
        Log.i(TAG, "position override x=$x y=$y")
        val lp = cardLp ?: return
        val v = root ?: return
        applyPosition(lp)
        try {
            windowManager?.updateViewLayout(v, lp)
            Log.i(TAG, "position applied pos=${lp.x},${lp.y}")
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed: $e", e)
        }
    }

    /** Hata ayıklama: kalibrasyon ızgarasını aç/kapat. */
    fun setGrid(on: Boolean) {
        gridOn = on
        if (!wanted) return
        if (on) attachGrid() else detachGrid()
    }

    fun hide() {
        if (!wanted) return
        wanted = false
        handler.removeCallbacksAndMessages(null)
        renderScheduled = false
        displayManager?.unregisterDisplayListener(displayListener)
        detachGrid()
        detachView()
        shown = null
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        val wait = (MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastRenderUptime)).coerceAtLeast(0L)
        renderScheduled = true
        handler.postDelayed({
            renderScheduled = false
            render()
        }, wait)
    }

    private fun render() {
        if (!wanted) return
        val card = pending ?: return
        if (card == shown && root != null) return
        attachIfNeeded()
        val r = root ?: return
        Log.i(TAG, "content update: $card")
        r.removeAllViews()
        r.addView(buildCard(r.context, card), FrameLayout.LayoutParams(-1, -1))
        shown = card
        lastRenderUptime = SystemClock.uptimeMillis()
    }

    private fun reattach() {
        if (!wanted) return
        detachGrid()
        detachView()
        attachIfNeeded()
        shown = null
        scheduleRender()
    }

    private fun applyPosition(lp: WindowManager.LayoutParams) {
        lp.x = overrideX ?: (SAFE_X * scale).toInt()
        lp.y = overrideY ?: (SAFE_Y * scale).toInt()
    }

    private fun findDisplay(): Display? {
        val displayId = AppLaunchHelper.getClusterDisplayId(appContext)
        val all = displayManager?.displays?.joinToString { "${it.displayId}:${it.name}" }
        Log.i(TAG, "cluster display id=$displayId all=[$all]")
        val display = displayManager?.getDisplay(displayId)
        if (display == null || displayId == Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "cluster display not found (id=$displayId)")
            return null
        }
        return display
    }

    private fun attachIfNeeded() {
        if (root != null) return
        try {
            val display = findDisplay() ?: return
            val displayId = display.displayId
            val size = Point().also { display.getRealSize(it) }
            Log.i(TAG, "display found ${describe(display)}")

            val ctx = appContext.createDisplayContext(display)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sx = size.x / REF_W.toFloat()
            val sy = size.y / REF_H.toFloat()
            scale = sx.coerceAtMost(sy)
            val frame = FrameLayout(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val lp = WindowManager.LayoutParams(
                (SAFE_W * sx).toInt(),
                (SAFE_H * sy).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                OVERLAY_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "ClusterCard"
            }
            applyPosition(lp)
            Log.i(TAG, "addView display=$displayId pos=${lp.x},${lp.y} size=${lp.width}x${lp.height} flags=0x${Integer.toHexString(lp.flags)} type=${lp.type}")
            wm.addView(frame, lp)
            windowManager = wm
            root = frame
            cardLp = lp
            attachedDisplayId = displayId
            Log.i(TAG, "attach OK display=$displayId token=${frame.windowToken} attached=${frame.isAttachedToWindow}")
            handler.postDelayed({ logVisibility(frame, "card") }, 1000L)
            if (gridOn) attachGrid()
        } catch (e: Throwable) {
            Log.e(TAG, "attach FAILED: ${e.javaClass.name}: ${e.message}", e)
            detachView()
        }
    }

    private fun logVisibility(v: View, what: String) {
        val r = Rect()
        val vis = v.getGlobalVisibleRect(r)
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        Log.i(
            TAG,
            "visibility[$what] +1s isShown=${v.isShown} globalVisible=$vis rect=$r onScreen=${loc[0]},${loc[1]} " +
                "size=${v.width}x${v.height} attached=${v.isAttachedToWindow} token=${v.windowToken} " +
                "winVisibility=${v.windowVisibility} display=${describe(v.display)}",
        )
    }

    private fun describe(d: Display?): String {
        if (d == null) return "null"
        val p = Point().also { d.getRealSize(it) }
        return "id=${d.displayId} name=${d.name} size=${p.x}x${p.y} state=${d.state} flags=0x${Integer.toHexString(d.flags)} " +
            "valid=${d.isValid} rate=${d.refreshRate}"
    }

    private fun detachView() {
        val view = root
        val wm = windowManager
        root = null
        cardLp = null
        windowManager = null
        if (view != null && wm != null) {
            try {
                wm.removeViewImmediate(view)
                Log.i(TAG, "detached display=$attachedDisplayId")
            } catch (e: Exception) {
                Log.e(TAG, "detach failed: ${e.message}", e)
            }
        }
        attachedDisplayId = -1
    }

    // ---- Kalibrasyon ızgarası ----

    private fun attachGrid() {
        if (gridView != null) return
        try {
            val display = findDisplay() ?: return
            val size = Point().also { display.getRealSize(it) }
            val ctx = appContext.createDisplayContext(display)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = GridView(ctx, size.x, size.y, scale.coerceAtLeast(0.5f))
            val lp = WindowManager.LayoutParams(
                size.x, size.y,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                OVERLAY_FLAGS or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 0
                title = "ClusterGrid"
            }
            wm.addView(v, lp)
            gridView = v
            gridWm = wm
            Log.i(TAG, "grid attached ${size.x}x${size.y}")
            handler.postDelayed({ logVisibility(v, "grid") }, 1000L)
        } catch (e: Throwable) {
            Log.e(TAG, "grid attach FAILED: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    private var gridWm: WindowManager? = null

    private fun detachGrid() {
        val v = gridView ?: return
        gridView = null
        try {
            gridWm?.removeViewImmediate(v)
            Log.i(TAG, "grid detached")
        } catch (e: Exception) {
            Log.e(TAG, "grid detach failed: ${e.message}", e)
        }
        gridWm = null
    }

    private class GridView(ctx: Context, private val w: Int, private val h: Int, private val sc: Float) : View(ctx) {
        private val line = Paint().apply { color = Color.argb(150, 255, 255, 255); strokeWidth = 1f }
        private val major = Paint().apply { color = Color.argb(230, 255, 214, 0); strokeWidth = 2f }
        private val safe = Paint().apply {
            color = Color.argb(255, 0, 230, 118); strokeWidth = 3f; style = Paint.Style.STROKE
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 20f * sc; setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(c: Canvas) {
            var x = 0
            while (x <= w) {
                val m = x % 480 == 0
                c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), if (m) major else line)
                c.drawText("$x", x + 4f, 24f * sc, text)
                c.drawText("$x", x + 4f, h - 8f, text)
                x += GRID_STEP
            }
            var y = 0
            while (y <= h) {
                val m = y % 360 == 0
                c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), if (m) major else line)
                c.drawText("$y", 4f, y + 22f * sc, text)
                c.drawText("$y", w - 70f * sc, y + 22f * sc, text)
                y += GRID_STEP
            }
            c.drawRect(RectF(SAFE_X.toFloat(), SAFE_Y.toFloat(), (SAFE_X + SAFE_W).toFloat(), (SAFE_Y + SAFE_H).toFloat()), safe)
            c.drawText("SAFE 480,145 - 1440,470", SAFE_X + 10f, SAFE_Y + SAFE_H - 10f, text)
            c.drawText("${w}x${h}", w / 2f - 40f, 70f * sc, text)
        }
    }

    // ---- Kart görünümü ----

    private fun px(v: Float) = (v * scale).toInt()

    private fun tv(ctx: Context, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) =
        TextView(ctx).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            letterSpacing = spacing
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private fun buildCard(ctx: Context, card: Card): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = px(40f)
            setPadding(pad, px(24f), pad, px(24f))
            background = GradientDrawable().apply {
                setColor(COLOR_BG)
                cornerRadius = px(24f).toFloat()
                setStroke(1, COLOR_BORDER)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun add(v: View, topDp: Float = 0f) =
            box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(topDp) })

        when (card) {
            is Card.Media -> {
                add(tv(ctx, 24f, COLOR_MUTED, spacing = 0.14f).apply { text = card.source.uppercase() })
                add(tv(ctx, 64f, COLOR_TEXT, bold = true).apply { text = card.title }, 14f)
                if (card.artist.isNotEmpty()) {
                    add(tv(ctx, 40f, COLOR_SOFT).apply { text = card.artist }, 10f)
                }
            }
            is Card.Vehicle -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                left.addView(tv(ctx, 24f, COLOR_MUTED, spacing = 0.14f).apply { text = card.label.uppercase() })
                left.addView(
                    tv(ctx, 96f, COLOR_TEXT, bold = true).apply {
                        text = if (card.rangeKm >= 0) "${card.rangeKm}" else "-"
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6f) },
                )
                row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                val unit = tv(ctx, 40f, COLOR_SOFT).apply { text = card.unit }
                row.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM; bottomMargin = px(14f) })
                add(row)
                val fuelRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val bar = FuelBar(ctx, card.fuelPercent, scale)
                fuelRow.addView(bar, LinearLayout.LayoutParams(0, px(10f), 1f))
                fuelRow.addView(
                    tv(ctx, 36f, COLOR_TEXT, bold = true).apply {
                        text = if (card.fuelPercent >= 0) "${card.fuelPercent}%" else "-"
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(24f) },
                )
                add(fuelRow, 22f)
            }
            is Card.Maneuver -> {
                add(tv(ctx, 24f, COLOR_MUTED, spacing = 0.14f).apply { text = card.distance })
                add(tv(ctx, 64f, COLOR_TEXT, bold = true).apply { text = card.text }, 14f)
            }
            is Card.Message -> {
                add(tv(ctx, 44f, COLOR_SOFT).apply { text = card.text; gravity = Gravity.CENTER })
            }
        }
        return box
    }

    /** İnce yakıt çubuğu; animasyonsuz, yalnızca onDraw. */
    private class FuelBar(ctx: Context, private val percent: Int, private val sc: Float) : View(ctx) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (percent in 0..15) Color.rgb(255, 168, 92) else Color.rgb(120, 200, 255)
        }
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            if (percent > 0) {
                val w = (width * percent.coerceAtMost(100) / 100f).coerceAtLeast(height.toFloat())
                c.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fill)
            }
        }
    }

    companion object {
        const val TAG = "ClusterCard"
        private const val MIN_INTERVAL_MS = 1000L
        private const val REF_W = 1920
        private const val REF_H = 720
        private const val GRID_STEP = 120
        // Güvenli bölge (1920x720): x 480-1440, y 145-470
        private const val SAFE_X = 480
        private const val SAFE_Y = 145
        private const val SAFE_W = 960
        private const val SAFE_H = 325
        private val OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        private val COLOR_BG = Color.argb(224, 16, 19, 25)
        private val COLOR_BORDER = Color.argb(46, 255, 255, 255)
        private val COLOR_TEXT = Color.rgb(244, 246, 250)
        private val COLOR_SOFT = Color.rgb(196, 202, 212)
        private val COLOR_MUTED = Color.rgb(136, 144, 158)
    }
}
