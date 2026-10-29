package com.mapcontrol.launcher.cluster

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.util.AppLaunchHelper

/**
 * Katman A: kadran (ekran 2) üzerinde tek, salt-okur, animasyonsuz kart penceresi.
 * Yalnızca WindowManager.addView; VDBus/CarInfo'ya yazmaz. Ana iş parçacığından kullanılır.
 */
class ClusterSurface(context: Context) {

    /** Kartın çizeceği içerik. Eşitlik (data class) değişim denetimi için kullanılır. */
    data class Content(val title: String, val lines: List<String>)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var titleView: TextView? = null
    private var lineViews: List<TextView> = emptyList()
    private var attachedDisplayId = -1

    private var wanted = false
    private var pending: Content? = null
    private var shown: Content? = null
    private var lastRenderUptime = 0L
    private var renderScheduled = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.i(TAG, "display added id=$displayId")
            handler.post { reattach() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.i(TAG, "display removed id=$displayId")
            handler.post { if (displayId == attachedDisplayId) detachView() ; reattach() }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplayId) handler.post { reattach() }
        }
    }

    fun show(content: Content?) {
        pending = content
        if (!wanted) {
            wanted = true
            displayManager?.registerDisplayListener(displayListener, handler)
            attachIfNeeded()
        }
        scheduleRender()
    }

    fun hide() {
        if (!wanted) return
        wanted = false
        handler.removeCallbacksAndMessages(null)
        renderScheduled = false
        displayManager?.unregisterDisplayListener(displayListener)
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
        val content = pending ?: return
        if (content == shown && root != null) return
        attachIfNeeded()
        val title = titleView ?: return
        title.text = content.title
        lineViews.forEachIndexed { i, tv -> tv.text = content.lines.getOrNull(i).orEmpty() }
        shown = content
        lastRenderUptime = SystemClock.uptimeMillis()
    }

    private fun reattach() {
        if (!wanted) return
        detachView()
        attachIfNeeded()
        shown = null
        scheduleRender()
    }

    private fun attachIfNeeded() {
        if (root != null) return
        try {
            val displayId = AppLaunchHelper.getClusterDisplayId(appContext)
            val display: Display? = displayManager?.getDisplay(displayId)
            if (display == null || displayId == Display.DEFAULT_DISPLAY) {
                Log.w(TAG, "cluster display not found (id=$displayId)")
                return
            }
            val size = android.graphics.Point().also { display.getRealSize(it) }
            Log.i(TAG, "display found id=$displayId size=${size.x}x${size.y}")

            val ctx = appContext.createDisplayContext(display)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sx = size.x / REF_W.toFloat()
            val sy = size.y / REF_H.toFloat()
            val view = buildView(ctx, sx.coerceAtMost(sy))
            val lp = WindowManager.LayoutParams(
                (SAFE_W * sx).toInt(),
                (SAFE_H * sy).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (SAFE_X * sx).toInt()
                y = (SAFE_Y * sy).toInt()
                title = "ClusterCard"
            }
            wm.addView(view, lp)
            windowManager = wm
            root = view
            attachedDisplayId = displayId
            Log.i(TAG, "attached display=$displayId pos=${lp.x},${lp.y} size=${lp.width}x${lp.height}")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed: ${e.message}", e)
            detachView()
        }
    }

    private fun detachView() {
        val view = root
        val wm = windowManager
        root = null
        titleView = null
        lineViews = emptyList()
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

    private fun buildView(ctx: Context, scale: Float): LinearLayout {
        fun sp(v: Float) = v * scale
        val pad = (28 * scale).toInt()
        val title = TextView(ctx).apply {
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(30f))
            letterSpacing = 0.06f
            maxLines = 1
        }
        val lines = List(MAX_LINES) { i ->
            TextView(ctx).apply {
                setTextColor(if (i == 0) COLOR_TEXT else COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(if (i == 0) 64f else 44f))
                typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(COLOR_BG)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(title)
        lines.forEach {
            box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * scale).toInt() })
        }
        titleView = title
        lineViews = lines
        return box
    }

    companion object {
        const val TAG = "ClusterCard"
        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_LINES = 2
        private const val REF_W = 1920
        private const val REF_H = 720
        // Güvenli bölge (1920x720): x 480-1440, y 145-470
        private const val SAFE_X = 480
        private const val SAFE_Y = 145
        private const val SAFE_W = 960
        private const val SAFE_H = 325
        private val COLOR_BG = Color.rgb(12, 14, 18)
        private val COLOR_TEXT = Color.rgb(240, 242, 246)
        private val COLOR_MUTED = Color.rgb(150, 158, 170)
    }
}
