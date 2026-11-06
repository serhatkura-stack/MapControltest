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

    /**
     * Gerçek pencerenin (başlık payı dahil) en üst sınırı. "Tam" pencerede başlık çubuğu y 80–120'de, QNX üst şeridinin
     * içinde durur; tek katman o şeridi opak örter (delik başlığın altında başlar), başlık görünmez.
     */
    const val CAPTION_MIN_TOP = 80

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
        GENIS("genis", Box(510, 180, 1870, 515)),

        /**
         * Ölçülen güvenli alanın tamamı (varsayılan): 1380x480. Solda hız bloğunun (x 200–490) sağında, altta alt şeridin
         * (y 620) üstünde biter; üstte QNX simge sırasının hemen altından başlar, başlık çubuğu (y 80–120) şeridin içinde
         * katmanın altında kalır. Hız limiti tabelası (x≈750, y≈265) ve sağ alttaki geçici yazı haritanın üstünde süzülür
         * (bilerek); katman o köşeyi koyulaştırır.
         */
        TAM("tam", Box(500, 120, 1880, 600));

        companion object {
            fun of(key: String?): WindowSize = values().firstOrNull { it.key == key } ?: TAM
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
     * Görünür pencere QNX öğelerine çakışmıyor mu: sol hız bloğu (x<500), üst simge sırası (y<120), alt şerit (y>610)
     * ve ekran dışı yasak. Hız limiti tabelası ve sağ alttaki geçici yazı haritanın üstünde kalabilir (bilerek; katman
     * yazının köşesini koyulaştırır). En az 320x200.
     */
    fun isSafe(b: Box): Boolean {
        if (b.left < 500 || b.top < 120 || b.right > 1880 || b.bottom > 610) return false
        return b.width >= 320 && b.height >= 200
    }

    /**
     * Görünür pencere güvenli VE gerçek pencerenin başlık çubuğu ([launchBounds]) QNX üst şeridinin içinde kalıyor
     * (y ≥ [CAPTION_MIN_TOP]); orada tek katmanın opak kısmının altında görünmez.
     */
    fun isSafeWindow(hole: Box): Boolean = isSafe(hole) && launchBounds(hole).top >= CAPTION_MIN_TOP

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


    // --- Tek katman ---

    /** Kenar erime genişlikleri (px): harita bu mesafede zemine (siyah / gündüz grafit) erir. */
    const val FEATHER_LEFT = 90
    const val FEATHER_TOP = 70
    const val FEATHER_RIGHT = 90
    const val FEATHER_BOTTOM = 80

    /** Üst QNX sırasının altındaki ek koyulaşma: y [TOP_ROW_Y] .. [TOP_ROW_Y]+[TOP_ROW_FADE]. */
    const val TOP_ROW_Y = 120
    const val TOP_ROW_FADE = 110

    /** Sağ alt geçici yazının (x 1340–1790, y 530–590) koyu köşesi: yatay elips. */
    const val HINT_CX = 1590f
    const val HINT_CY = 600f
    const val HINT_RX = 380f
    const val HINT_RY = 140f

    /** Ufuk sisi: pencere üstünden [HAZE_FADE] px aşağıya kadar söner (üstte kenar erimesiyle karanlığa karışır). */
    const val HAZE_FADE = 190

    /** İç ışık: haritanın alt-ortası (araç imleci/rota genelde orada); yarıçaplar pencere boyunun oranı. */
    const val LIGHT_CY = 0.70f
    const val LIGHT_RX = 0.42f
    const val LIGHT_RY = 0.50f

    /** Işık kılavuzu kıl çizgisi: QNX üst sırasının hemen altı, ekran ortasında en parlak, uçlarda yok olur. */
    const val LINE_Y = 120f
    const val LINE_CX = 960f
    const val LINE_HALF = 620f
    const val LINE_WIDTH = 1.5f

    /** Açılışta siyahtan son katmana, stil değişiminde çapraz geçiş süreleri (tek seferlik). */
    const val OPEN_FADE_MS = 600L
    const val STYLE_FADE_MS = 400L

    /**
     * Tek katmanın rengi ve güçleri. Pencere dışı opak [base]; pencerenin içinde kenarlardan [base]'e erime, gece oval
     * vinyeti ([oval]), sağ alt yazı köşesi ([hint]), üst QNX sırasının altında koyulaşma ([topRow]). Güçler 0..1.
     * Katman bir pencere olduğundan haritayla yalnız alfa karışımı (SRC_OVER) yapılabilir:
     *  - [grade]/[gradeAlpha]: birleştirici renk tonu. MULTIPLY pencereler arasında yok; renkli koyu tonla SRC_OVER
     *    ona yakın sonuç verir (ör. gece mavi: kırmızı/sarı kanallar maviden fazla iner).
     *  - [light]/[lightAlpha]: iç ışık. Beyaz ile SRC_OVER, SCREEN ile birebir aynıdır (1-(1-m)(1-a)).
     *  - [haze]/[hazeAlpha]: ufuk sisi rengi ve en fazla örtmesi.
     *  - [line]/[lineAlpha]: ışık kılavuzu kıl çizgisinin rengi ve tepe alfası.
     */
    data class Veil(
        val base: Int, val oval: Float, val hint: Float, val topRow: Float,
        val grade: Int = 0, val gradeAlpha: Float = 0f,
        val light: Int = 0xFFFFFF, val lightAlpha: Float = 0f,
        val haze: Int = 0, val hazeAlpha: Float = 0f,
        val line: Int = 0xFFFFFF, val lineAlpha: Float = 0f,
    )

    /**
     * Gece/uydu: siyah zemin (gece QNX'in kendi oval vinyetiyle aynı dil), gece serin gece mavisi ton, derin mavi-siyah
     * sis, serin beyaz çizgi. Uydu: nötr (ton yok). Gündüz: açık grafit zemin, hafif sıcak ton, sıcak-nötr açık sis,
     * grafit çizgi; QNX gündüz bunun üstüne açık tül serip koyu yazı çizer.
     */
    fun veil(frame: Frame): Veil = when (frame) {
        Frame.GECE -> Veil(base = 0xFF000000.toInt(), oval = 0.55f, hint = 0.80f, topRow = 0.55f,
            grade = 0x0E1A3A, gradeAlpha = 0.10f, light = 0xFFFFFF, lightAlpha = 0.06f,
            haze = 0x0A1424, hazeAlpha = 0.55f, line = 0xDCE6F2, lineAlpha = 0.85f)
        Frame.UYDU -> Veil(base = 0xFF020406.toInt(), oval = 0.62f, hint = 0.82f, topRow = 0.60f,
            grade = 0x000000, gradeAlpha = 0f, light = 0xFFFFFF, lightAlpha = 0.05f,
            haze = 0x0C141C, hazeAlpha = 0.50f, line = 0xC4D2DE, lineAlpha = 0.75f)
        Frame.GUNDUZ -> Veil(base = 0xFF4A5058.toInt(), oval = 0.30f, hint = 0.55f, topRow = 0.35f,
            grade = 0xFFC890, gradeAlpha = 0.06f, light = 0xFFFFFF, lightAlpha = 0.05f,
            haze = 0xD6CEC2, hazeAlpha = 0.35f, line = 0x2A2E34, lineAlpha = 0.80f)
    }

    /** Ufuk sisinin (x, y) noktasındaki örtmesi: pencerenin üst kenarında tepe, [HAZE_FADE] px aşağıda sıfır. */
    fun hazeAlpha(y: Float, hole: Box, v: Veil): Float {
        if (y < hole.top || y >= hole.bottom) return 0f
        return v.hazeAlpha * (1f - smoothstep(0f, HAZE_FADE.toFloat(), y - hole.top))
    }

    /** İç ışığın örtmesi: alt-orta elipsin merkezinde [Veil.lightAlpha], elips kenarında sıfır. */
    fun lightAlpha(x: Float, y: Float, hole: Box, v: Veil): Float {
        if (x < hole.left || x >= hole.right || y < hole.top || y >= hole.bottom) return 0f
        val dx = (x - (hole.left + hole.right) / 2f) / (hole.width * LIGHT_RX)
        val dy = (y - (hole.top + hole.height * LIGHT_CY)) / (hole.height * LIGHT_RY)
        return v.lightAlpha * (1f - smoothstep(0f, 1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()))
    }

    /** Kıl çizginin x boyunca alfası: ortada [Veil.lineAlpha], [LINE_CX] ± [LINE_HALF] dışında sıfır. */
    fun lineAlpha(x: Float, v: Veil): Float {
        val t = 1f - smoothstep(0f, 1f, Math.abs(x - LINE_CX) / LINE_HALF)
        return v.lineAlpha * t * t
    }

    /** SRC_OVER biriktirici (premultiplied 0..1); piksel başına nesne ayırmamak için yeniden kullanılır. */
    class Mix {
        var r = 0f; var g = 0f; var b = 0f; var a = 0f
        fun reset() { r = 0f; g = 0f; b = 0f; a = 0f }
        fun over(rgb: Int, alpha: Float) {
            if (alpha <= 0f) return
            val k = 1f - alpha
            r = alpha * ((rgb shr 16) and 0xFF) / 255f + k * r
            g = alpha * ((rgb shr 8) and 0xFF) / 255f + k * g
            b = alpha * (rgb and 0xFF) / 255f + k * b
            a = alpha + k * a
        }
    }

    /** İki RGB'nin karışımı (t=0 → [a], t=1 → [b]). */
    fun mixRgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = Math.round(((a shr s) and 0xFF) * (1f - t) + ((b shr s) and 0xFF) * t)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Katmanın (x, y) pikseli: ton → iç ışık → ufuk sisi → zemin örtmesi sırasıyla SRC_OVER. [accent] (ambiyans
     * rengi, RGB) verilirse iç ışık beyazla yarı yarıya o renge döner. Dönüş: [Mix] içinde premultiplied sonuç.
     */
    fun overlay(x: Float, y: Float, hole: Box, v: Veil, accent: Int?, m: Mix): Mix {
        m.reset()
        val cover = veilCover(x, y, hole, v)
        if (cover < 1f) {
            m.over(v.grade, v.gradeAlpha)
            m.over(if (accent != null) mixRgb(v.light, accent, 0.5f) else v.light, lightAlpha(x, y, hole, v))
            m.over(v.haze, hazeAlpha(y, hole, v))
        }
        m.over(v.base and 0xFFFFFF, cover)
        return m
    }

    /** Ambiyans renk sırası → RGB: yalnız araçta doğrulanan sıralar (45 = (255, 66, 0), OEM tablosu). */
    private val AMBIENT_TABLE = mapOf(45 to 0xFF4200)

    /**
     * CS 11 ham değeri → okunur ambiyans rengi (RGB) ya da null. Kodlama belirsiz: 1..64 sıra (bilinen tablo), 0x10000
     * üstü doğrudan 0xRRGGBB kabul edilir. Çok koyu renk (göstergede okunmaz) yok sayılır.
     */
    fun ambientRgb(raw: Int?): Int? {
        val rgb = when {
            raw == null -> null
            raw in 1..64 -> AMBIENT_TABLE[raw]
            raw in 0x10000..0xFFFFFF -> raw
            else -> null
        } ?: return null
        val lum = 0.2126f * ((rgb shr 16) and 0xFF) + 0.7152f * ((rgb shr 8) and 0xFF) + 0.0722f * (rgb and 0xFF)
        val peak = maxOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        return if (peak >= 160 && lum >= 40f) rgb else null
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Katmanın (x, y) noktasındaki örtmesi 0..1 (1 = opak zemin, 0 = harita olduğu gibi). Pencere dışı (başlık
     * çubuğu dahil) her zaman 1. İçeride dört kenarın yumuşak çarpımı köşeleri kendiliğinden yuvarlar.
     */
    fun veilCover(x: Float, y: Float, hole: Box, v: Veil): Float {
        if (x < hole.left || x >= hole.right || y < hole.top || y >= hole.bottom) return 1f
        var see = smoothstep(0f, FEATHER_LEFT.toFloat(), x - hole.left) *
            smoothstep(0f, FEATHER_RIGHT.toFloat(), hole.right - x) *
            smoothstep(0f, FEATHER_TOP.toFloat(), y - hole.top) *
            smoothstep(0f, FEATHER_BOTTOM.toFloat(), hole.bottom - y)
        // Gece QNX'in oval vinyeti: merkez parlak elips, kenar/köşe koyu.
        val dx = (x - (hole.left + hole.right) / 2f) / (hole.width / 2f)
        val dy = (y - (hole.top + hole.bottom) / 2f) / (hole.height / 2f)
        see *= 1f - v.oval * smoothstep(0.45f, 1.15f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
        // Sağ alt yazı köşesi.
        val hx = (x - HINT_CX) / HINT_RX
        val hy = (y - HINT_CY) / HINT_RY
        see *= 1f - v.hint * (1f - smoothstep(0.5f, 1f, Math.sqrt((hx * hx + hy * hy).toDouble()).toFloat()))
        // Üst QNX sırası (saat, P, sıcaklık, (A) simgeleri) okunaklı kalsın.
        see *= 1f - v.topRow * (1f - smoothstep(TOP_ROW_Y.toFloat(), (TOP_ROW_Y + TOP_ROW_FADE).toFloat(), y))
        return (1f - see).coerceIn(0f, 1f)
    }
}
