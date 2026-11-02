package com.mapcontrol.launcher

import android.content.Context
import com.mapcontrol.R

/**
 * Aracın açık gövde parçaları için tek doğruluk kaynağı: kapılar, bagaj, kaput, tavan ve (biliniyorsa) camlar. Sahne'deki
 * araç görseli, sahne uyarı satırı, kilit hapı ve şeritteki Bagaj öğesi aynı değeri okur; böylece biri "açık" derken
 * öteki "kapalı" diyemez. [VehicleHeroCard] her okumada günceller ([OpeningsBus]).
 */
internal data class Openings(
    val doorFl: Boolean = false,
    val doorFr: Boolean = false,
    val doorRl: Boolean = false,
    val doorRr: Boolean = false,
    val trunk: Boolean = false,
    val hood: Boolean = false,
    val sunroof: Boolean = false,
    /** Açık camlar (yalnızca cam sinyali okunabiliyorsa). */
    val windows: List<BodyPart> = emptyList(),
) {
    val doors: Int get() = listOf(doorFl, doorFr, doorRl, doorRr).count { it }
    val anyDoor: Boolean get() = doors > 0
    val count: Int get() = doors + listOf(trunk, hood, sunroof).count { it } + windows.size
    val any: Boolean get() = count > 0

    /** Sahne aracında açık çizilecek parçalar. */
    fun carParts(): Set<CarPart> = CarPart.fromSignals(doorFl, doorFr, doorRl, doorRr, trunk, hood)

    companion object {
        val NONE = Openings()
    }
}

/** Açıklık cümlesinin sözcükleri (kaynaklardan; JVM sınamasında sabit). */
internal interface OpeningWords {
    fun door(part: BodyPart): String
    fun doors(n: Int): String
    fun trunk(): String
    fun hood(): String
    fun sunroof(): String
    fun window(part: BodyPart): String
    fun windows(n: Int): String
    fun one(a: String): String
    fun two(a: String, b: String): String
    fun many(total: Int, list: String): String
    /** Cümle içinde ikinci ve sonraki sözcük (Türkçe küçük harf). */
    fun lower(s: String): String
}

/**
 * Açık parçaları adlandıran tek satır (saf, JVM'de sınanır): "Sol ön kapı açık", "Sol ön kapı ve bagaj açık", üç ve
 * fazlasında "3 yer açık: 2 kapı, bagaj". Birden çok kapı "2 kapı", birden çok cam "2 cam" olarak kısalır.
 */
internal object OpeningsText {
    fun phrase(o: Openings, w: OpeningWords): String? {
        if (!o.any) return null
        val doorParts = listOfNotNull(
            BodyPart.DOOR_LF.takeIf { o.doorFl }, BodyPart.DOOR_RF.takeIf { o.doorFr },
            BodyPart.DOOR_LR.takeIf { o.doorRl }, BodyPart.DOOR_RR.takeIf { o.doorRr },
        )
        val items = buildList {
            when (doorParts.size) {
                0 -> Unit
                1 -> add(w.door(doorParts[0]))
                else -> add(w.doors(doorParts.size))
            }
            if (o.trunk) add(w.trunk())
            if (o.hood) add(w.hood())
            if (o.sunroof) add(w.sunroof())
            when (o.windows.size) {
                0 -> Unit
                1 -> add(w.window(o.windows[0]))
                else -> add(w.windows(o.windows.size))
            }
        }
        return when (items.size) {
            1 -> w.one(items[0])
            2 -> w.two(items[0], w.lower(items[1]))
            else -> w.many(o.count, items.joinToString(", ") { w.lower(it) })
        }
    }
}

/** Kaynaklardan sözcükler. */
internal class ResourceOpeningWords(private val context: Context) : OpeningWords {
    private val locale = context.resources.configuration.locales[0]
    override fun door(part: BodyPart) = context.getString(R.string.launcher_open_door, context.getString(part.label))
    override fun doors(n: Int) = context.getString(R.string.launcher_open_doors_n, n)
    override fun trunk() = context.getString(R.string.launcher_trunk)
    override fun hood() = context.getString(R.string.launcher_body_hood)
    override fun sunroof() = context.getString(R.string.launcher_sunroof_part)
    override fun window(part: BodyPart) = context.getString(part.label)
    override fun windows(n: Int) = context.getString(R.string.launcher_open_windows_n, n)
    override fun one(a: String) = context.getString(R.string.launcher_part_open, a)
    override fun two(a: String, b: String) = context.getString(R.string.launcher_open_two, a, b)
    override fun many(total: Int, list: String) = context.getString(R.string.launcher_open_many, total, list)
    override fun lower(s: String) = if (s.firstOrNull()?.isDigit() == true) s else s.lowercase(locale)
}

/** Son açıklık durumu ve dinleyicileri (ana iş parçacığı). null: araç gövdesi henüz okunamadı. */
internal object OpeningsBus {
    var current: Openings? = null
        private set
    private val listeners = LinkedHashSet<(Openings?) -> Unit>()

    fun publish(o: Openings?) {
        if (o == current) return
        current = o
        listeners.toList().forEach { it(o) }
    }

    fun add(l: (Openings?) -> Unit) { listeners += l }
    fun remove(l: (Openings?) -> Unit) { listeners -= l }
}
