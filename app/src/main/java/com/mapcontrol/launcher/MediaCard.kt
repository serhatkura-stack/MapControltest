package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState

/**
 * Medya kartı: solda kare kapak, sağda kaynak, başlık, sanatçı, ilerleme ve kontroller.
 * Denetleyici çok sık haber verir; dinleyici yalnızca değişeni bağlar, ilerleme kendi 1 Hz sayacıyla akar.
 *
 * İki yerleşimi vardır ve ikisi de bir kez kurulur: yükseklik 2 satır ve üstüyse tam yerleşim,
 * 1 satırsa (2×1) kapaksız, ilerlemesiz kompakt yatay yerleşim. [applySpan] ikisi arasında
 * kartı yeniden yaratmadan görünürlük değiştirir; kontrol satırı tek örnektir ve taşınır.
 */
class MediaCard(private val context: Context) : SizedWidget {

    private enum class Mode { NO_ACCESS, IDLE, SESSION }

    private val controller = LauncherMediaController(context)
    private val handler = Handler(Looper.getMainLooper())

    // --- Kapak ---
    private val artworkImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val placeholderIcon = icon(context, R.drawable.ic_mdi_music_note, 40, LauncherTheme.TEXT_SECONDARY)
    private val artworkBox = FrameLayout(context).apply {
        background = oval(LauncherTheme.CONTROL).let { rounded(context, LauncherTheme.CONTROL) }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, context.dpF(LauncherTheme.CARD_RADIUS_DP))
            }
        }
        clipToOutline = true
        addView(artworkImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(placeholderIcon, FrameLayout.LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER))
    }
    private val artworkGap = gap(context, 32)

    // --- Metinler ---
    private val sourceLabel = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY)
    private val titleView = text(context, LauncherTheme.TITLE_SP, bold = true)
    private val artistView = text(context, 28f, LauncherTheme.TEXT_SECONDARY)

    // --- İlerleme ---
    private val progressFill = View(context).apply {
        background = rounded(context, LauncherTheme.ACCENT, 4f)
        // Genişlik sabit, ilerleme scaleX ile çizilir: yerleşim turu gerektirmez.
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        pivotX = 0f
        scaleX = 0f
    }
    private val progressTrack = FrameLayout(context).apply {
        background = rounded(context, LauncherTheme.TRACK, 4f)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(8))
        addView(progressFill)
    }
    private val elapsedView = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY)
    private val totalView = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY)
    private val timesGap = gap(context, 8, vertical = true)
    private val timesRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(elapsedView)
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(totalView)
    }
    private val progressBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(progressTrack)
        addView(timesGap)
        addView(timesRow)
    }

    // --- Kontroller ---
    private val prevButton = iconButton(
        context, R.drawable.ic_mdi_skip_previous, context.getString(R.string.launcher_media_prev),
        sizeDp = LauncherTheme.MEDIA_BUTTON_DP, circle = true,
    ) { controller.skipToPrevious() }
    private val playButton = iconButton(
        context, R.drawable.ic_mdi_play, context.getString(R.string.launcher_media_play),
        sizeDp = LauncherTheme.PLAY_BUTTON_DP, background = LauncherTheme.ACCENT, tint = LauncherTheme.ON_ACCENT, circle = true,
    ) { controller.playPause() }
    private val nextButton = iconButton(
        context, R.drawable.ic_mdi_skip_next, context.getString(R.string.launcher_media_next),
        sizeDp = LauncherTheme.MEDIA_BUTTON_DP, circle = true,
    ) { controller.skipToNext() }
    private val playIcon = playButton.getChildAt(0) as ImageView
    private val controlsGap1 = gap(context, 24)
    private val controlsGap2 = gap(context, 24)
    private val controlsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(prevButton)
        addView(controlsGap1)
        addView(playButton)
        addView(controlsGap2)
        addView(nextButton)
    }

    // --- İzin yok durumu ---
    // Tek seferlik kurulum adımı: ana ekranın en baskın öğesi olmasın diye vurgu değil, tonal düğme.
    private val needAccessText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 4).apply {
        setText(R.string.launcher_media_need_access)
    }
    private val grantButton = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT, medium = true).apply {
        setText(R.string.launcher_media_grant)
        gravity = Gravity.CENTER
        background = rounded(context, LauncherTheme.CONTROL, 16f)
        val h = context.dp(40)
        setPadding(h, 0, h, 0)
        minWidth = context.dp(160)
        isClickable = true
        isFocusable = true
        setOnClickListener { controller.openNotificationAccessSettings() }
        pressFeedback()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(LauncherTheme.TOUCH_MIN_DP))
    }
    private val accessGap = gap(context, 24, vertical = true)
    private val accessBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(needAccessText)
        addView(accessGap)
        addView(grantButton)
    }

    // --- Kompakt (2×1) yerleşim: solda başlık + sanatçı, sağda kontroller ---
    private val compactTitle = text(context, COMPACT_TITLE_SP, bold = true)
    private val compactArtist = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
    private val compactBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(compactTitle)
            addView(gap(context, 4, vertical = true))
            addView(compactArtist)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(gap(context, 24))
    }

    private val sourceGap = gap(context, 4, vertical = true)
    private val artistGap = gap(context, 4, vertical = true)
    private val mainBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sourceLabel)
        addView(sourceGap)
        addView(titleView)
        addView(artistGap)
        addView(artistView)
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(progressBox)
        addView(gap(context, 16, vertical = true))
        addView(controlsRow)
    }

    override val view: View = card(context, LinearLayout.HORIZONTAL).apply {
        addView(artworkBox, LinearLayout.LayoutParams(0, 0))
        addView(artworkGap)
        addView(FrameLayout(context).apply {
            addView(mainBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(compactBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(accessBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, weightLp(1f))
        addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            applySizes(r - l, b - t - v.paddingTop - v.paddingBottom)
        }
    }

    // --- Son bağlanan durum (gereksiz yeniden bağlamayı önlemek için) ---
    private var listening = false
    private var mode: Mode? = null
    private var lastSource: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var shownArtwork: Bitmap? = null
    private var shownGeneration = -1
    private var shownPlaying: Boolean? = null
    private var lastArtworkSize = -1
    private var lastCompact: Boolean? = null
    private var lastPlayDp = -1
    private var lastTiny: Boolean? = null
    private var lastShort: Boolean? = null
    private var layoutCompact = false

    // --- İlerleme durumu ---
    private var basePositionMs = 0L
    private var baseRealtimeMs = 0L
    private var durationMs = 0L
    private var playing = false
    private var lastElapsedSec = -1L
    private var lastTotalSec = -1L
    private var lastFillPermille = -1

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (playing && mode == Mode.SESSION && !layoutCompact) handler.postDelayed(this, 1000L)
        }
    }

    private val listener = LauncherMediaController.Listener { state -> if (!LauncherDemo.enabled) bind(state) }

    override fun onVisible() {
        if (!listening) {
            controller.addListener(listener)
            listening = true
        }
        controller.start()
        bind(if (LauncherDemo.enabled) LauncherDemo.media() else controller.currentState())
    }

    override fun onHidden() {
        if (listening) {
            controller.removeListener(listener)
            listening = false
        }
        controller.stop()
        handler.removeCallbacks(tick)
    }

    override fun release() = onHidden()

    /**
     * Izgara boyutu değişince çağrılır (onVisible'dan önce ve sonra tekrar). Yükseklik 1 satırsa kompakt
     * yerleşime, aksi halde tam yerleşime geçer; yalnızca yerleşim türü değişince çalışır.
     */
    override fun applySpan(span: Span) {
        val compact = span.h < 2
        if (compact == layoutCompact) return
        layoutCompact = compact

        // Kontrol satırı tek örnek: bulunduğu kapsayıcıdan alınıp yenisine eklenir.
        (controlsRow.parent as? ViewGroup)?.removeView(controlsRow)
        if (compact) {
            compactBox.addView(controlsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            mainBox.addView(controlsRow)
        }
        setGapWidth(controlsGap1, if (compact) 12 else 24)
        setGapWidth(controlsGap2, if (compact) 12 else 24)
        val pad = context.dp(if (compact) COMPACT_PADDING_DP else LauncherTheme.PADDING_DP)
        view.setPadding(pad, pad, pad, pad)

        // İzin yok durumu: kompaktta metin ve düğme yan yana, metin en çok 2 satır.
        accessBox.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        needAccessText.maxLines = if (compact) 2 else 4
        needAccessText.layoutParams = if (compact) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        accessGap.layoutParams = if (compact) {
            LinearLayout.LayoutParams(context.dp(24), 1)
        } else {
            LinearLayout.LayoutParams(1, context.dp(24))
        }
        updateContainers()

        lastFillPermille = -1
        if (compact) {
            // Kapak, ilerleme ve sayaç yok; düğmeler sabit 72dp.
            handler.removeCallbacks(tick)
            artworkBox.visibility = View.GONE
            artworkGap.visibility = View.GONE
            lastArtworkSize = -1
            lastPlayDp = -1
            for (b in arrayOf(prevButton, playButton, nextButton)) resize(b, LauncherTheme.TOUCH_MIN_DP)
        } else {
            lastArtworkSize = -1
            lastCompact = null
            lastPlayDp = -1
            if (view.width > 0) applySizes(view.width, view.height - view.paddingTop - view.paddingBottom)
            if (listening && mode == Mode.SESSION) {
                updateProgress()
                handler.removeCallbacks(tick)
                if (playing) handler.postDelayed(tick, 1000L)
            }
        }
    }

    private fun setGapWidth(gap: View, dp: Int) {
        val lp = gap.layoutParams
        lp.width = context.dp(dp)
        gap.layoutParams = lp
    }

    /** Hangi kapsayıcının görüneceğini belirler: izin yok, tam ya da kompakt. */
    private fun updateContainers() {
        val noAccess = mode == Mode.NO_ACCESS
        accessBox.visibility = if (noAccess) View.VISIBLE else View.GONE
        mainBox.visibility = if (noAccess || layoutCompact) View.GONE else View.VISIBLE
        compactBox.visibility = if (noAccess || !layoutCompact) View.GONE else View.VISIBLE
    }

    /** Kart yüksekliğine göre kapak boyutu, genişliğe göre başlık boyutu. Yalnızca değişince dokunur. */
    private fun applySizes(width: Int, contentHeight: Int) {
        if (layoutCompact) return
        val density = context.resources.displayMetrics.density
        val showArtwork = contentHeight >= context.dp(180)
        val size = if (showArtwork) minOf(contentHeight, context.dp(300)) else 0
        if (size != lastArtworkSize) {
            lastArtworkSize = size
            val lp = artworkBox.layoutParams as LinearLayout.LayoutParams
            lp.width = size
            lp.height = size
            artworkBox.layoutParams = lp
            val vis = if (showArtwork) View.VISIBLE else View.GONE
            artworkBox.visibility = vis
            artworkGap.visibility = vis
            val iconSize = (size * 0.4f).toInt()
            val ilp = placeholderIcon.layoutParams
            ilp.width = iconSize
            ilp.height = iconSize
            placeholderIcon.layoutParams = ilp
        }
        val compact = width / density < 700f
        if (compact != lastCompact) {
            lastCompact = compact
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 40f else LauncherTheme.TITLE_SP)
        }
        // Çok kısa kartta (ör. 3×2) kaynak ve sanatçı satırları, daha kısada süre satırı gizlenir.
        val short = contentHeight < context.dp(SHORT_HEIGHT_DP)
        val tiny = contentHeight < context.dp(TINY_HEIGHT_DP)
        if (short != lastShort || tiny != lastTiny) {
            lastShort = short
            lastTiny = tiny
            val vis = if (short) View.GONE else View.VISIBLE
            sourceLabel.visibility = vis
            sourceGap.visibility = vis
            artistView.visibility = vis
            artistGap.visibility = vis
            val timesVis = if (tiny) View.GONE else View.VISIBLE
            timesRow.visibility = timesVis
            timesGap.visibility = timesVis
        }
        // Düğmeler kalan yüksekliğe sığacak kadar küçülür (en çok tam boy, en az 56dp); kontroller kesilmez.
        var reserved = titleView.lineHeight + context.dp(16) + context.dp(8)
        if (!tiny) reserved += context.dp(8) + elapsedView.lineHeight
        if (!short) reserved += sourceLabel.lineHeight + artistView.lineHeight + context.dp(8)
        val playPx = (contentHeight - reserved).coerceIn(context.dp(MIN_PLAY_DP), context.dp(LauncherTheme.PLAY_BUTTON_DP))
        val playDp = (playPx / density).toInt()
        val sideDp = minOf((playDp * 0.85f).toInt(), LauncherTheme.MEDIA_BUTTON_DP)
        if (playDp != lastPlayDp) {
            lastPlayDp = playDp
            resize(playButton, playDp)
            resize(prevButton, sideDp)
            resize(nextButton, sideDp)
        }
    }

    private fun resize(button: View, sizeDp: Int) {
        val lp = button.layoutParams
        lp.width = context.dp(sizeDp)
        lp.height = context.dp(sizeDp)
        button.layoutParams = lp
    }

    /** Denetleyiciden gelen durum: yalnızca değişen kısımlar bağlanır. */
    private fun bind(state: MediaState) {
        val newMode = when {
            !state.notificationAccessGranted -> Mode.NO_ACCESS
            !state.sessionAvailable -> Mode.IDLE
            else -> Mode.SESSION
        }
        val modeChanged = newMode != mode
        if (modeChanged) {
            mode = newMode
            updateContainers()
            progressBox.visibility = if (newMode == Mode.SESSION) View.VISIBLE else View.INVISIBLE
            setControlsEnabled(newMode == Mode.SESSION)
        }
        if (newMode == Mode.NO_ACCESS) {
            bindArtwork(null)
            handler.removeCallbacks(tick)
            return
        }

        val source = if (newMode == Mode.SESSION) state.source else null
        val title = if (newMode == Mode.SESSION) state.title else context.getString(R.string.launcher_media_idle)
        val artist = if (newMode == Mode.SESSION) state.artist else context.getString(R.string.launcher_media_idle_hint)
        val textChanged = modeChanged || source != lastSource || title != lastTitle || artist != lastArtist
        if (textChanged) {
            lastSource = source
            lastTitle = title
            lastArtist = artist
            sourceLabel.text = if (source.isNullOrEmpty()) "" else appLabel(source)
            titleView.text = title ?: ""
            artistView.text = artist ?: ""
            compactTitle.text = title ?: ""
            compactArtist.text = artist ?: ""
        }
        bindArtwork(if (newMode == Mode.SESSION) state.artwork else null)

        if (newMode != Mode.SESSION) {
            playing = false
            setPlaying(false)
            handler.removeCallbacks(tick)
            return
        }

        // İlerleme: yalnızca konumu sakla; çizimi sayaç yapar.
        val playingChanged = state.playing != playing
        playing = state.playing
        basePositionMs = state.positionMs
        baseRealtimeMs = SystemClock.elapsedRealtime()
        if (state.durationMs != durationMs) {
            durationMs = state.durationMs
            lastTotalSec = -1L
            lastFillPermille = -1
        }
        setPlaying(state.playing)
        if (textChanged || playingChanged) {
            if (!layoutCompact) updateProgress()
            handler.removeCallbacks(tick)
            if (playing && !layoutCompact) handler.postDelayed(tick, 1000L)
        } else if (!playing && !layoutCompact) {
            updateProgress()
        }
    }

    /** Kapak değişmediyse (referans ya da generationId) hiçbir şey yapma. */
    private fun bindArtwork(bitmap: Bitmap?) {
        if (bitmap === shownArtwork) return
        if (bitmap != null && bitmap.generationId == shownGeneration) {
            shownArtwork = bitmap
            return
        }
        shownArtwork = bitmap
        if (bitmap == null) {
            shownGeneration = -1
            artworkImage.visibility = View.GONE
            artworkImage.setImageDrawable(null)
            placeholderIcon.visibility = View.VISIBLE
        } else {
            shownGeneration = bitmap.generationId
            artworkImage.setImageBitmap(bitmap)
            artworkImage.visibility = View.VISIBLE
            placeholderIcon.visibility = View.GONE
        }
    }

    /** Oynat/duraklat ikonu yalnızca durum gerçekten değişince çapraz geçişle değişir. */
    private fun setPlaying(isPlaying: Boolean) {
        val previous = shownPlaying
        if (previous == isPlaying) return
        shownPlaying = isPlaying
        val res = if (isPlaying) R.drawable.ic_mdi_pause else R.drawable.ic_mdi_play
        playButton.contentDescription = context.getString(
            if (isPlaying) R.string.launcher_media_pause else R.string.launcher_media_play,
        )
        if (previous == null) {
            playIcon.setImageResource(res)
            return
        }
        val half = LauncherTheme.CROSSFADE_MS / 2
        playIcon.animate().cancel()
        playIcon.animate().alpha(0f).setDuration(half).withEndAction {
            playIcon.setImageResource(res)
            playIcon.animate().alpha(1f).setDuration(half).start()
        }.start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        for (b in arrayOf(prevButton, playButton, nextButton)) {
            b.isEnabled = enabled
            b.alpha = alpha
        }
    }

    /** Son bilinen konumdan ekstrapolasyonla çubuğu ve geçen süreyi günceller; yalnızca değişince yazar. */
    private fun updateProgress() {
        var pos = basePositionMs
        if (playing) pos += SystemClock.elapsedRealtime() - baseRealtimeMs
        if (pos < 0L) pos = 0L
        if (durationMs > 0L && pos > durationMs) pos = durationMs

        val sec = pos / 1000L
        if (sec != lastElapsedSec) {
            lastElapsedSec = sec
            elapsedView.text = formatTime(sec)
        }
        val totalSec = durationMs / 1000L
        if (totalSec != lastTotalSec) {
            lastTotalSec = totalSec
            totalView.text = if (durationMs > 0L) formatTime(totalSec) else ""
        }
        val permille = if (durationMs > 0L) (1000L * pos / durationMs).toInt() else 0
        if (permille != lastFillPermille) {
            lastFillPermille = permille
            progressFill.scaleX = permille / 1000f
        }
    }

    private fun formatTime(totalSec: Long): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (s < 10) "$m:0$s" else "$m:$s"
    }

    /** Paket adını uygulama etiketine çevirir; bulunamazsa paket adı kalır. */
    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private companion object {
        /** Bu yüksekliğin altında kaynak ve sanatçı satırları gizlenir. */
        const val SHORT_HEIGHT_DP = 250
        /** Bu yüksekliğin altında süre satırı da gizlenir; düğmeler en az MIN_PLAY_DP olur. */
        const val TINY_HEIGHT_DP = 190
        const val MIN_PLAY_DP = 56

        /** Kompakt (2×1) yerleşimde başlık boyutu ve kart iç boşluğu. */
        const val COMPACT_TITLE_SP = 28f
        const val COMPACT_PADDING_DP = 24
    }
}
