package com.mapcontrol.launcher

import android.content.Context

/**
 * Sahne düzeninin modeli (saf, JVM'de sınanır). Ortadaki araç sahnesi sabittir; düzenlenebilir olan iki yan panel
 * ([left], [right]) ve alt şeridin [STRIP_SLOTS] yuvasıdır. Boş yuva null'dır (düzenlemede sessiz bir "+").
 */
data class SahneSlot(
    val id: String,
    val type: WidgetType,
    /** Türe özel ayar: APP için "paketA+paketB" (şeritte iki uygulama). */
    val extra: String? = null,
)

data class SahneLayout(
    val left: SahneSlot?,
    val right: SahneSlot?,
    val strip: List<SahneSlot?>,
) {
    /** Tüm dolu yuvalar (sol, sağ, şerit sırasıyla). */
    fun slots(): List<SahneSlot> = listOfNotNull(left, right) + strip.filterNotNull()

    fun types(): Set<WidgetType> = slots().map { it.type }.toSet()
}

/** Yuva türü: yan panel ya da şerit öğesi. */
enum class SahneSlotKind { PANEL, STRIP }

/** Yuvanın adresi: panel için 0 = sol, 1 = sağ; şerit için 0..[STRIP_SLOTS]-1. */
data class SahneSlotRef(val kind: SahneSlotKind, val index: Int)

const val STRIP_SLOTS = 6

/**
 * Hangi tür hangi yuvaya konabilir. Panel: büyük boyutu olan her widget; büyük boyutu olmayanlar kutucuklarının
 * büyütülmüş hâliyle (uygulama kısayolu hariç, o yalnızca şeritte). Şerit: fotoğraf hariç hepsi (fotoğraf alan ister).
 * Her tür en az birine sığar (bkz. SahneSlotsTest).
 */
object SahneForm {
    fun fitsPanel(type: WidgetType): Boolean = type != WidgetType.APP
    fun fitsStrip(type: WidgetType): Boolean = type != WidgetType.PHOTO
    fun fits(type: WidgetType, kind: SahneSlotKind): Boolean = if (kind == SahneSlotKind.PANEL) fitsPanel(type) else fitsStrip(type)

    /** Türün panelde kendi büyük biçimi var mı (yoksa kutucuğu büyütülür). */
    fun hasLargeForm(type: WidgetType): Boolean = type.sizes.any { it.w >= 4 || it.h >= 4 }

    /** Panelde kullanılacak boyut: en büyük alanlı izinli boyut. */
    fun panelSpan(type: WidgetType): Span = type.sizes.maxByOrNull { it.w * it.h } ?: Span(2, 2)

    /** Şerit için kutucuğun kurulacağı boyut: ilk (varsayılan) boyut. */
    fun stripSpan(type: WidgetType): Span = type.sizes.first()

    /**
     * Seçicide [type] [ref] yuvasına konabilir mi: yuvaya sığmalı, düzen moduna izinli olmalı ve (APP hariç) düzende
     * başka bir yuvada olmamalı ([ref]'teki mevcut widget'ın yerine konabilir).
     */
    fun canPlace(layout: SahneLayout, ref: SahneSlotRef, type: WidgetType, mode: LayoutMode): Boolean {
        if (!fits(type, ref.kind) || !isAllowedIn(type, mode)) return false
        if (type == WidgetType.APP) return true
        val here = SahneSlots.get(layout, ref)
        return layout.slots().none { it.type == type && it !== here }
    }
}

object SahneSlots {

    fun get(layout: SahneLayout, ref: SahneSlotRef): SahneSlot? = when (ref.kind) {
        SahneSlotKind.PANEL -> if (ref.index == 0) layout.left else layout.right
        SahneSlotKind.STRIP -> layout.strip.getOrNull(ref.index)
    }

    /** [ref] yuvasına [slot] konmuş (null: boşaltılmış) yeni düzen. */
    fun set(layout: SahneLayout, ref: SahneSlotRef, slot: SahneSlot?): SahneLayout = when (ref.kind) {
        SahneSlotKind.PANEL -> if (ref.index == 0) layout.copy(left = slot) else layout.copy(right = slot)
        SahneSlotKind.STRIP -> layout.copy(strip = layout.strip.mapIndexed { i, s -> if (i == ref.index) slot else s })
    }

    /** İki uygulamalı şerit yuvasının varsayılan paketleri ("a+b"); kurulu uygulama yoksa null. */
    fun appExtra(packages: List<String>, offset: Int = 0): String? =
        packages.drop(offset).take(2).takeIf { it.isNotEmpty() }?.joinToString(APP_SEP)

    /** APP yuvasının paketleri (en çok iki). */
    fun appPackages(extra: String?): List<String?> {
        val parts = extra?.split(APP_SEP)?.map { it.trim().ifEmpty { null } } ?: emptyList()
        return listOf(parts.getOrNull(0), parts.getOrNull(1))
    }

    const val APP_SEP = "+"

    /**
     * Park varsayılanı (taslak): solda medya, sağda "Toplam" bölümlü yolculuk; şeritte sürücü koltuk ısıtma · tavan
     * perdesi · bagaj · buğu giderme · iki uygulama · yolcu koltuk ısıtma (sol direksiyonlu kabine göre).
     */
    fun defaultPark(appPackages: List<String> = emptyList()): SahneLayout = SahneLayout(
        left = SahneSlot("media", WidgetType.MEDIA),
        right = SahneSlot("trip", WidgetType.TRIP),
        strip = listOf(
            SahneSlot("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER),
            SahneSlot("sunshade", WidgetType.SUNSHADE),
            SahneSlot("trunk", WidgetType.TRUNK),
            SahneSlot("defrost", WidgetType.DEFROST),
            SahneSlot("app_pair", WidgetType.APP, appExtra(appPackages)),
            SahneSlot("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER),
        ),
    )

    /**
     * Sürüş varsayılanı: solda medya, sağda anlık tüketim (sürüşte akan tek büyük değer); şeritte sürücü koltuk ısıtma ·
     * uyku modu · buğu giderme · iki uygulama · mola hatırlatıcı · yolcu koltuk ısıtma. Park'a özel widget yok.
     */
    fun defaultDrive(appPackages: List<String> = emptyList()): SahneLayout = SahneLayout(
        left = SahneSlot("media", WidgetType.MEDIA),
        right = SahneSlot("consumption", WidgetType.CONSUMPTION),
        strip = listOf(
            SahneSlot("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER),
            SahneSlot("rear_speaker", WidgetType.REAR_SPEAKER),
            SahneSlot("defrost", WidgetType.DEFROST),
            SahneSlot("app_pair", WidgetType.APP, appExtra(appPackages)),
            SahneSlot("break_reminder", WidgetType.BREAK_REMINDER),
            SahneSlot("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER),
        ),
    )

    fun defaults(mode: LayoutMode, appPackages: List<String> = emptyList()): SahneLayout =
        if (mode == LayoutMode.DRIVE) defaultDrive(appPackages) else defaultPark(appPackages)

    /** Sürüş'te Park'a özel widget'lar çizilmez (yuva boş görünür; kayıt silinmez). */
    fun visibleForMode(layout: SahneLayout, mode: LayoutMode): SahneLayout {
        fun keep(s: SahneSlot?) = s?.takeIf { isAllowedIn(it.type, mode) }
        return SahneLayout(keep(layout.left), keep(layout.right), layout.strip.map { keep(it) })
    }

    /**
     * Okunan düzeni temizler: yuvaya sığmayan türler boşalır, şerit tam [STRIP_SLOTS] yuvaya tamamlanır/kırpılır,
     * tekil türlerin ikincisi ve yinelenen kimlikler atılır (ilk kalır).
     */
    fun sanitize(layout: SahneLayout): SahneLayout {
        val seenTypes = HashSet<WidgetType>()
        val seenIds = HashSet<String>()
        fun keep(s: SahneSlot?, kind: SahneSlotKind): SahneSlot? {
            if (s == null || !SahneForm.fits(s.type, kind)) return null
            if (!seenIds.add(s.id)) return null
            if (s.type != WidgetType.APP && !seenTypes.add(s.type)) return null
            return s
        }
        val left = keep(layout.left, SahneSlotKind.PANEL)
        val right = keep(layout.right, SahneSlotKind.PANEL)
        val strip = (0 until STRIP_SLOTS).map { keep(layout.strip.getOrNull(it), SahneSlotKind.STRIP) }
        return SahneLayout(left, right, strip)
    }

    // --- Kayıt biçimi ---
    // "v1|<sol>|<sağ>|<şerit1>,<şerit2>,..." ; yuva: boş = "", yoksa "tür~kimlik~ek". Paket adları ve kimlikler bu
    // ayraçları içermez (yalnızca harf, rakam, '.', '_'). Saf metin: JSON'a gerek yok, JVM'de sınanır.

    private const val VERSION = "v1"

    fun encode(layout: SahneLayout): String {
        fun slot(s: SahneSlot?): String = if (s == null) "" else listOf(s.type.key, s.id, s.extra ?: "").joinToString("~")
        return listOf(VERSION, slot(layout.left), slot(layout.right), layout.strip.joinToString(",") { slot(it) }).joinToString("|")
    }

    /** Çözülemeyen kayıt null (çağıran varsayılana döner); bilinmeyen tür yalnızca o yuvayı boşaltır. */
    fun decode(raw: String?): SahneLayout? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split("|")
        if (parts.size != 4 || parts[0] != VERSION) return null
        fun slot(s: String): SahneSlot? {
            if (s.isEmpty()) return null
            val f = s.split("~")
            val type = WidgetType.fromKey(f[0]) ?: return null
            val id = f.getOrNull(1)?.ifEmpty { null } ?: type.key
            return SahneSlot(id, type, f.getOrNull(2)?.ifEmpty { null })
        }
        val strip = parts[3].split(",").map { slot(it) }
        return sanitize(SahneLayout(slot(parts[1]), slot(parts[2]), strip))
    }

    /** Yeni yuva kimliği: tür anahtarı, gerekirse sıra eki (APP birden çok olabilir). */
    fun newId(layout: SahneLayout, type: WidgetType): String {
        val used = layout.slots().map { it.id }.toSet()
        if (type.key !in used) return type.key
        var i = 2
        while ("${type.key}_$i" in used) i++
        return "${type.key}_$i"
    }
}

/**
 * Sahne düzeninin deposu: profil ve araç ekranı başına ayrı dosya, Park ve Sürüş için ayrı anahtar. Serbest ızgaranın
 * kayıtlarına ([LayoutStore]) hiç dokunmaz.
 */
class SahneStore(
    context: Context,
    private val mode: LayoutMode,
    vehicle: VehicleProfile = VehicleProfile.compute(context),
) {
    private val appPackages = AppDock.installedDefaults(context, DEFAULT_APP_SLOTS)
    private val prefs = context.applicationContext.getSharedPreferences(prefsName(Profiles.current(context), vehicle), Context.MODE_PRIVATE)
    private val key = key(mode)

    fun load(): SahneLayout = SahneSlots.decode(prefs.getString(key, null)) ?: defaults()

    fun save(layout: SahneLayout) = prefs.edit().putString(key, SahneSlots.encode(layout)).apply()

    fun defaults(): SahneLayout = SahneSlots.defaults(mode, appPackages)

    fun reset() = prefs.edit().remove(key).apply()

    companion object {
        const val PREFS = "launcher_sahne"
        const val PARK = "sahne_park_v1"
        const val DRIVE = "sahne_drive_v1"

        fun key(mode: LayoutMode): String = if (mode == LayoutMode.DRIVE) DRIVE else PARK

        fun prefsName(profile: Int, vehicle: VehicleProfile = VehicleProfile.TIGGO7): String =
            Profiles.key(PREFS, profile) + if (vehicle == VehicleProfile.TIGGO8) "_t8" else ""
    }
}
