package com.mapcontrol.launcher.cluster

/**
 * "Gösterge haritası (deneysel)" için saf hesaplar (JVM'de test edilir; android.graphics yok).
 * Tüm ölçüler gösterge ekranının (Tiggo 7, ekran 2: 1920x720 @160) gerçek pikselidir;
 * QNX öğelerinin yerleri katalog/gosterge-olculeri-6kasim.md'den.
 */
internal object ClusterMapMath {

    const val DESIGN_W = 1920
    const val DESIGN_H = 720

    /**
     * Serbest pencerenin başlık çubuğu (Android 11 freeform "caption": küçült/kapat düğmeleri) için pencereye
     * eklenen üst pay. Emülatörde @160 dpi ölçüm ~37 px; bu şerit çerçevenin opak kısmının altında kalır.
     */
    const val CAPTION_PX = 40

    /** Pencere köşe yarıçapı (maske), iç gölge genişliği. */
    const val CORNER_PX = 20f
    const val INNER_SHADOW_PX = 22

    /** Basit dikdörtgen (sol, üst, sağ, alt; sağ/alt hariç). */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        override fun toString() = "$left,$top,$right,$bottom"
    }

    /** Harita penceresinin boyu. */
    enum class WindowSize(val key: String, val hole: Box) {
        /**
         * Orta güvenli alan: 800x420, alt şerit (y 620) üstünde biter. Görünür pencere y 180'de başlar; başlık çubuğu
         * payıyla gerçek pencere y 140'ta: çerçeve süreç ölümüyle kalksa bile başlık QNX simgelerine (y≈140) girmez.
         */
        ORTA("orta", Box(510, 180, 1310, 600)),

        /** Sağdaki boşluğu da kullanan geniş şerit: sağ alt geçici yazı (y 530) üstünde biter. */
        GENIS("genis", Box(510, 180, 1870, 515));

        companion object {
            fun of(key: String?): WindowSize = values().firstOrNull { it.key == key } ?: ORTA
        }
    }

    /** Çerçeve stilleri: Yandex'in gece, gündüz ve uydu görünümleriyle uyumlu. */
    enum class Frame(val key: String) {
        GECE("gece"), GUNDUZ("gunduz"), UYDU("uydu");

        companion object {
            fun of(key: String?): Frame? = values().firstOrNull { it.key == key?.trim()?.lowercase() }
        }
    }

    /** Ayardaki seçim: Otomatik = launcher'ın gündüz/gece durumu. */
    enum class FrameChoice(val key: String, val fixed: Frame?) {
        OTOMATIK("otomatik", null), GECE("gece", Frame.GECE), GUNDUZ("gunduz", Frame.GUNDUZ), UYDU("uydu", Frame.UYDU);

        companion object {
            fun of(key: String?): FrameChoice = values().firstOrNull { it.key == key?.trim()?.lowercase() } ?: OTOMATIK
        }
    }

    fun resolveFrame(choice: FrameChoice, night: Boolean): Frame = choice.fixed ?: if (night) Frame.GECE else Frame.GUNDUZ

    /**
     * Pencere QNX öğelerine çakışmıyor mu: sol hız bloğu (x<500), üst simgeler (y<140), alt şerit (y>610) dışında;
     * orta güvenli alanı (x≤1320) aşarsa sağ alttaki geçici yazıya (y≥530) inmemeli. En az 320x200.
     */
    fun isSafe(b: Box): Boolean {
        if (b.left < 500 || b.top < 140 || b.right > 1880 || b.bottom > 610) return false
        if (b.right > 1320 && (b.bottom > 525 || b.top < 140)) return false
        return b.width >= 320 && b.height >= 200
    }

    /**
     * Görünür pencere VE gerçek pencere (üstte başlık payıyla, [launchBounds]) QNX alanına girmiyor mu. Çerçeve
     * penceresi süreçle birlikte ölürse harita penceresi kalır; onun da güvenli olması gerekir.
     */
    fun isSafeWindow(hole: Box): Boolean = isSafe(hole) && isSafe(launchBounds(hole))

    /** "l,t,r,b" (debug ekstrası) → kutu; biçim bozuksa null. */
    fun parseBox(text: String?): Box? {
        val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 4 || parts.any { it == null }) return null
        val (l, t, r, b) = parts.map { it!! }
        return if (r > l && b > t) Box(l, t, r, b) else null
    }

    /** Uygulamanın açılacağı sınırlar: görünür pencere + üstte başlık çubuğu payı (çerçevenin altında kalır). */
    fun launchBounds(hole: Box, sx: Float = 1f, sy: Float = 1f): Box =
        scale(Box(hole.left, hole.top - CAPTION_PX, hole.right, hole.bottom), sx, sy)

    fun scale(b: Box, sx: Float, sy: Float): Box =
        if (sx == 1f && sy == 1f) b
        else Box(Math.round(b.left * sx), Math.round(b.top * sy), Math.round(b.right * sx), Math.round(b.bottom * sy))

    const val YANDEX_NAVI = "ru.yandex.yandexnavi"
    const val YANDEX_MAPS = "ru.yandex.yandexmaps"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    /** Ayardaki uygulama seçimi. */
    enum class MapApp(val key: String, val pkg: String) {
        NAVI("navi", YANDEX_NAVI), MAPS("maps", YANDEX_MAPS);

        companion object {
            fun of(key: String?): MapApp = values().firstOrNull { it.key == key } ?: NAVI
        }
    }

    /**
     * Açılacak paket: seçilen Yandex kuruluysa o, değilse diğer Yandex; ikisi de yoksa yalnızca debug yapıda
     * (emülatör) Google Haritalar. Hiçbiri yoksa null.
     */
    fun choosePackage(preferred: MapApp, installed: (String) -> Boolean, debug: Boolean): String? {
        val order = listOf(preferred.pkg, if (preferred == MapApp.NAVI) YANDEX_MAPS else YANDEX_NAVI)
        order.firstOrNull(installed)?.let { return it }
        return if (debug && installed(GOOGLE_MAPS)) GOOGLE_MAPS else null
    }

    /**
     * Çerçevelenebilir hedef: yalnızca Yandex Navigator / Yandex Haritalar (debug yapıda emülatör yerine Google
     * Haritalar). Diğer her hedef eski tam ekran yoldan açılır (Google Maps'in araç gösterge etkinliği dahil).
     */
    fun canFrame(pkg: String?, debug: Boolean): Boolean =
        pkg == YANDEX_NAVI || pkg == YANDEX_MAPS || (debug && pkg == GOOGLE_MAPS)

    /** Açılıştan sonra harita görevinin denetimi: istenen boyutta mı, değil mi, bilinemiyor mu (dumpsys izni yok). */
    enum class TaskCheck { OK, WRONG, UNKNOWN }

    private val DISPLAY_RE = Regex("""^\s*Display #(\d+)""")
    private val BOUNDS_RE = Regex("""mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

    /**
     * `dumpsys activity activities` çıktısında [pkg]'nin en üstteki görevi [displayId] ekranında, serbest pencerede ve
     * [expected] sınırlarında (±[tol] px) mı. Çıktı okunamıyorsa (uygulama kimliğiyle dumpsys izni yok) ya da görev
     * henüz görünmüyorsa UNKNOWN. Sınır Rect(0, 0 - 0, 0) = ekranı dolduruyor (tam ekran) → WRONG.
     */
    fun checkTask(dump: String?, pkg: String, displayId: Int, expected: Box, tol: Int = 8): TaskCheck {
        if (dump == null || !dump.contains("Display #")) return TaskCheck.UNKNOWN
        var display = -1
        var inTarget = false
        var targetDisplay = -1
        var freeform = false
        for (line in dump.lineSequence()) {
            val dm = DISPLAY_RE.find(line)
            if (dm != null) {
                if (inTarget) return TaskCheck.UNKNOWN
                display = dm.groupValues[1].toInt()
                continue
            }
            val t = line.trimStart()
            if (t.startsWith("* Task{")) {
                if (inTarget) return TaskCheck.UNKNOWN // hedef görevin sınırı yoktu
                inTarget = t.contains(":$pkg ") || t.contains(":$pkg}")
                if (inTarget) { targetDisplay = display; freeform = t.contains("mode=freeform") }
                continue
            }
            if (!inTarget) continue
            val m = BOUNDS_RE.find(line) ?: continue
            val (l, tp, r, b) = m.groupValues.drop(1).map { it.toInt() }
            if (targetDisplay != displayId || !freeform) return TaskCheck.WRONG
            val ok = Math.abs(l - expected.left) <= tol && Math.abs(tp - expected.top) <= tol &&
                Math.abs(r - expected.right) <= tol && Math.abs(b - expected.bottom) <= tol
            return if (ok) TaskCheck.OK else TaskCheck.WRONG
        }
        return TaskCheck.UNKNOWN
    }

    /** Çerçevenin renkleri (ARGB). Kenarlar her stilde siyaha düşer: QNX yazıları okunaklı kalsın. */
    data class Palette(
        val base: Int,
        /** Pencerenin çevresindeki ışık (haritanın yanındaki en açık ton). */
        val aura: Int,
        val headerTop: Int,
        val headerBottom: Int,
        val plateTop: Int,
        /** Kenar ışıklı ince çizgiler (orta parlak, uçlar söner). */
        val hairline: Int,
        val bezelTop: Int,
        val bezelBottom: Int,
        val glow: Int,
        /** Tek sıcak vurgu: alt plakanın ortasında. */
        val accent: Int,
        /** İç gölgenin en koyu alfası (0..255). */
        val innerShadowAlpha: Int,
    )

    fun palette(frame: Frame): Palette = when (frame) {
        Frame.GECE -> Palette(
            base = 0xFF040506.toInt(), aura = 0xFF0E1115.toInt(),
            headerTop = 0xFF07080A.toInt(), headerBottom = 0xFF15181C.toInt(), plateTop = 0xFF101317.toInt(),
            hairline = 0xFFC4CDD8.toInt(), bezelTop = 0x8CD8DFE8.toInt(), bezelBottom = 0x26D8DFE8,
            glow = 0x2EBFCAD6, accent = 0xFFF2A33A.toInt(), innerShadowAlpha = 150,
        )
        Frame.GUNDUZ -> Palette(
            base = 0xFF0B0D0F.toInt(), aura = 0xFF2B3036.toInt(),
            headerTop = 0xFF0B0D0F.toInt(), headerBottom = 0xFF22262B.toInt(), plateTop = 0xFF1A1D21.toInt(),
            hairline = 0xFFE6EBF0.toInt(), bezelTop = 0xB8EEF2F6.toInt(), bezelBottom = 0x40C9D0D8,
            glow = 0x38E8EEF4, accent = 0xFFF0A040.toInt(), innerShadowAlpha = 120,
        )
        Frame.UYDU -> Palette(
            base = 0xFF050708.toInt(), aura = 0xFF131A20.toInt(),
            headerTop = 0xFF070A0C.toInt(), headerBottom = 0xFF151C22.toInt(), plateTop = 0xFF0F1519.toInt(),
            hairline = 0xFFAFC7D8.toInt(), bezelTop = 0x8CBCD0DE.toInt(), bezelBottom = 0x2696B4C8,
            glow = 0x2E8FB2CA, accent = 0xFFD8B27A.toInt(), innerShadowAlpha = 165,
        )
    }

    /** İç gölgenin i. halkasının alfası (kenarda en koyu, içeri doğru karesel söner). */
    fun innerShadowAlpha(maxAlpha: Int, ring: Int, rings: Int = INNER_SHADOW_PX): Int {
        if (ring < 0 || ring >= rings) return 0
        val t = 1f - ring.toFloat() / rings
        return Math.round(maxAlpha * t * t).coerceIn(0, 255)
    }
}
