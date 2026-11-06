package com.mapcontrol.launcher

/** Medya kartının iki yüzü: ön yüz medya, arka yüz telefon ([MediaPhoneFlip]). */
enum class CardFace { MEDIA, PHONE }

/**
 * Medya/telefon kartının saf kuralları (JVM'de sınanır): hangi yüz gösterilir, çevirme simgesi ne zaman görünür ve ışır,
 * kayıtlı yüz ne zaman değişir, yatay sürükleme ne zaman kartı çevirir.
 */
object MediaFlipLogic {

    /**
     * Gösterilecek yüz: bağlı telefon yokken hep medya; varken kayıtlı (son) yüz. [held]: bu görünürlükte telefon koptuğu için
     * kart medyada tutuluyor ([holdAfter]); kayıt değişmez.
     */
    fun shown(saved: CardFace, connected: Boolean, held: Boolean = false): CardFace =
        if (connected && !held) saved else CardFace.MEDIA

    /** Çevirme simgesi yalnız bağlı telefon varken görünür (arka yüzde gösterilecek bir şey yok). */
    fun flipAvailable(connected: Boolean): Boolean = connected

    /**
     * Bağlantı bildirimi sonrası "medyada tut" bayrağı (yalnız bellekte; kayıtlı yüz hiç değişmez): ekran açıkken daha önce
     * bağlı görülen telefon koparsa kart medyaya döner ve aynı görünürlükte telefon geri gelince kendiliğinden telefona
     * dönmez (kullanıcı o an medya kontrollerine dokunuyor olabilir). Bayrak ekran yeniden görünür olunca ya da kullanıcı
     * çevirince kalkar; böylece Bluetooth kopması, kapsama dışı ya da kontak kapanması kullanıcının son seçimini silmez.
     * İlk bildirim ([wasConnected] null: durum henüz bilinmiyor) bayrağı koymaz; açılışta telefon bağlanınca son yüz gelir.
     */
    fun holdAfter(held: Boolean, wasConnected: Boolean?, connected: Boolean): Boolean =
        held || (wasConnected == true && !connected)

    /** Çevirme simgesi ışır mı: kart telefon yüzündeyken arkada müzik çalıyorsa (yalnız simge yanar; rozet/nokta yok). */
    fun iconGlows(face: CardFace, playing: Boolean): Boolean = face == CardFace.PHONE && playing

    /** Dokununca gidilecek yüz. */
    fun other(face: CardFace): CardFace = if (face == CardFace.MEDIA) CardFace.PHONE else CardFace.MEDIA

    fun encode(face: CardFace): String = if (face == CardFace.PHONE) PHONE_KEY else MEDIA_KEY

    /** Bilinmeyen ya da boş kayıt medya. */
    fun decode(raw: String?): CardFace = if (raw == PHONE_KEY) CardFace.PHONE else CardFace.MEDIA

    private const val MEDIA_KEY = "media"
    private const val PHONE_KEY = "phone"

    /** Yatay sürüklemenin kararı. */
    enum class Swipe {
        /** Bu dokunuş kartı çevirmez (dikey, sürüklenen bir kontrolde başladı ya da çevirme kapalı). */
        IGNORE,
        /** Henüz karar yok (eşik altında). */
        WAIT,
        /** Yatay niyet belli: dokunuş karttan alınır (alttaki düğme basılı kalmaz), çevirme mesafesi bekleniyor. */
        CAPTURE,
        /** Çevir. */
        FLIP,
    }

    /**
     * Basıştan bu yana [dx]/[dy] (px) kaymış dokunuşun kararı. [startsOnDrag]: dokunuş ilerleme çubuğu gibi yatay sürüklenen bir
     * kontrolde başladı (kart hiç çevrilmez). [enabled]: çevirme açık (bağlı telefon var). Yatay niyet için yatay kayma
     * dikeyin [DOMINANCE] katından büyük olmalı; dikey kayma eşiği önce geçerse dokunuş bırakılır.
     */
    fun swipe(dx: Float, dy: Float, slopPx: Float, flipPx: Float, startsOnDrag: Boolean, enabled: Boolean): Swipe {
        if (!enabled || startsOnDrag) return Swipe.IGNORE
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        val horizontal = ax > ay * DOMINANCE
        if (!horizontal && ay > slopPx) return Swipe.IGNORE
        if (!horizontal) return Swipe.WAIT
        return when {
            ax >= flipPx -> Swipe.FLIP
            ax > slopPx -> Swipe.CAPTURE
            else -> Swipe.WAIT
        }
    }

    /**
     * Bir dokunuşun kaydırma izi: [swipe] kararını hareket boyunca hatırlar. Bir kez IGNORE (ör. önce dikey kaydı) ya da FLIP
     * olan dokunuş, bırakılana dek bir daha çevirmez/yakalamaz; önce dikey gidip sonra yataya dönen sürükleme kartı çevirmez.
     */
    class SwipeGesture(private val slopPx: Float, private val flipPx: Float) {
        private var blocked = false

        /** Yeni basış; [startsOnDrag]: ilerleme çubuğu gibi yatay sürüklenen bir kontrolde. */
        fun down(startsOnDrag: Boolean) {
            blocked = startsOnDrag
        }

        fun move(dx: Float, dy: Float, enabled: Boolean): Swipe {
            val s = swipe(dx, dy, slopPx, flipPx, blocked, enabled)
            if (s == Swipe.IGNORE || s == Swipe.FLIP) blocked = true
            return s
        }
    }

    /** Dönüş yönü: sola sürükleme -1, sağa +1 (dokunuşta -1: kart sola döner). */
    fun direction(dx: Float): Int = if (dx > 0f) 1 else -1

    /** (x, y) noktası [l, r) × [t, b) dikdörtgeninde mi. */
    fun hits(x: Float, y: Float, l: Int, t: Int, r: Int, b: Int): Boolean = r > l && b > t && x >= l && x < r && y >= t && y < b

    const val DOMINANCE = 1.5f
}
