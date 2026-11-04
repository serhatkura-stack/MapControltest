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
 * Hangi tür hangi yuvaya konabilir: iki seçilmiş liste (sahibin kararı). Şerit, tek dokunuşla iş gören küçük kontrollerdir;
 * panel, büyük alanı dolduran bilgi ve kontrollerdir. Listede olmayan tür Sahne seçicisinde hiç gösterilmez (araç kartı
 * sahnenin kendisidir); Serbest ızgara tüm kataloğu kullanmaya devam eder. Her listelenen tür en az birine sığar (SahneSlotsTest).
 */
object SahneForm {
    /**
     * Şerit (küçük) için izinli türler, seçicideki sırayla. Kapı kilidi yok: sahnenin sağ üstündeki "Kilitli" hapı aynı işi
     * görür (4 Kasım kararı; çift olmasın). Eski kayıtlardaki kilit yuvası [migrateLock] ile sürüş moduna döner.
     */
    val STRIP_TYPES: List<WidgetType> = listOf(
        WidgetType.SEAT_DRIVER, WidgetType.SEAT_PASSENGER, WidgetType.TRUNK, WidgetType.APP, WidgetType.REAR_SPEAKER,
        WidgetType.DRIVE_MODE, WidgetType.BREAK_REMINDER, WidgetType.SCREEN_OFF, WidgetType.PHONE_REMINDER,
        WidgetType.CONSUMPTION, WidgetType.WEB_PANEL, WidgetType.EQUALIZER, WidgetType.AUTOMATION, WidgetType.SPLIT_SCREEN,
        WidgetType.CLUSTER_MIRROR,
    )

    /**
     * Panel (büyük) için izinli türler, seçicideki sırayla: yalnız bu boyuta göre çizilmiş widget'lar (4 Kasım kararı §2;
     * Navigasyon §3'ten: Yandex bildiriminden sonraki dönüş).
     * Diğer türler Serbest ızgarada kalır; eski Sahne kayıtlarındaki yuvalar [SahneSlots.decode]'da yedeğe döner.
     */
    val PANEL_TYPES: List<WidgetType> = listOf(WidgetType.MEDIA, WidgetType.TRIP, WidgetType.CONSUMPTION, WidgetType.NAVIGATION)

    fun fitsPanel(type: WidgetType): Boolean = type in PANEL_TYPES
    fun fitsStrip(type: WidgetType): Boolean = type in STRIP_TYPES
    fun fits(type: WidgetType, kind: SahneSlotKind): Boolean = if (kind == SahneSlotKind.PANEL) fitsPanel(type) else fitsStrip(type)

    /** Seçicide [kind] yuvası için sunulan türler. */
    fun offered(kind: SahneSlotKind): List<WidgetType> = if (kind == SahneSlotKind.PANEL) PANEL_TYPES else STRIP_TYPES

    /** Türün panelde kendi büyük biçimi var mı (yoksa kutucuğu büyütülür). */
    fun hasLargeForm(type: WidgetType): Boolean = type.sizes.any { it.w >= 4 || it.h >= 4 }

    /** Panelde kullanılacak boyut: en büyük alanlı izinli boyut. */
    fun panelSpan(type: WidgetType): Span = type.sizes.maxByOrNull { it.w * it.h } ?: Span(2, 2)

    /** Şerit için kutucuğun kurulacağı boyut: ilk (varsayılan) boyut. */
    fun stripSpan(type: WidgetType): Span = type.sizes.first()

    /**
     * Seçicide [type] [ref] yuvasına konabilir mi: yuvaya sığmalı ve (APP hariç) düzende başka bir yuvada olmamalı ([ref]'teki
     * mevcut widget'ın yerine konabilir). Park ve Sürüş aynı kuralı kullanır: her widget sürüşte de çalışır.
     */
    fun canPlace(layout: SahneLayout, ref: SahneSlotRef, type: WidgetType): Boolean {
        if (!fits(type, ref.kind)) return false
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
     * Park varsayılanı: solda medya, sağda "Toplam" bölümlü yolculuk; şeritte iki uçta koltuklar (sol direksiyonlu kabine
     * göre sürücü solda, yolcu sağda), ortada duran araçta en çok kullanılan dört iş: bagaj (yük alıp verme), arka hoparlör
     * (arkada uyuyan çocukla beklerken), iki uygulama (yola çıkmadan müzik/harita) ve sürüş modu (kapı kilidi sahnenin
     * sağ üstündeki hapta; şeritte çift olmasın).
     */
    fun defaultPark(appPackages: List<String> = emptyList()): SahneLayout = SahneLayout(
        left = SahneSlot("media", WidgetType.MEDIA),
        right = SahneSlot("trip", WidgetType.TRIP),
        strip = listOf(
            SahneSlot("seat_driver", WidgetType.SEAT_DRIVER),
            SahneSlot("trunk", WidgetType.TRUNK),
            SahneSlot("rear_speaker", WidgetType.REAR_SPEAKER),
            SahneSlot("app_pair", WidgetType.APP, appExtra(appPackages)),
            SahneSlot("drive_mode", WidgetType.DRIVE_MODE),
            SahneSlot("seat_passenger", WidgetType.SEAT_PASSENGER),
        ),
    )

    /**
     * Sürüş varsayılanı: solda medya, sağda anlık tüketim (sürüşte akan tek büyük değer); şeritte sürücü koltuğu · arka
     * hoparlör · iki uygulama · mola hatırlatıcı · sürüş modu · yolcu koltuğu.
     */
    fun defaultDrive(appPackages: List<String> = emptyList()): SahneLayout = SahneLayout(
        left = SahneSlot("media", WidgetType.MEDIA),
        right = SahneSlot("consumption", WidgetType.CONSUMPTION),
        strip = listOf(
            SahneSlot("seat_driver", WidgetType.SEAT_DRIVER),
            SahneSlot("rear_speaker", WidgetType.REAR_SPEAKER),
            SahneSlot("app_pair", WidgetType.APP, appExtra(appPackages)),
            SahneSlot("break_reminder", WidgetType.BREAK_REMINDER),
            SahneSlot("drive_mode", WidgetType.DRIVE_MODE),
            SahneSlot("seat_passenger", WidgetType.SEAT_PASSENGER),
        ),
    )

    fun defaults(mode: LayoutMode, appPackages: List<String> = emptyList()): SahneLayout =
        if (mode == LayoutMode.DRIVE) defaultDrive(appPackages) else defaultPark(appPackages)

    /**
     * Okunan düzeni temizler: yuvaya sığmayan (seçkide olmayan) türler boşalır, şerit tam [STRIP_SLOTS] yuvaya tamamlanır/kırpılır,
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

    /**
     * Çözülemeyen kayıt null (çağıran varsayılana döner); bilinmeyen ya da kaldırılmış tür (perde, buğu) yalnızca o yuvayı
     * boşaltır. Ayrı ısıtma/soğutma koltukları o tarafın birleşik koltuğuna döner; ikisi de varsa ilki kalır ([sanitize]).
     */
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
        val raw = migrateLock(SahneLayout(slot(parts[1]), slot(parts[2]), strip))
        return fallbackPanels(raw, sanitize(raw))
    }

    /**
     * Artık panelde izinli olmayan türdeki (ör. sağlık, hava, koltuk) bir yan paneli yedeğe çevirir: o tarafın varsayılanı
     * (sol medya, sağ yolculuk) düzende başka yerde yoksa o, yoksa kullanılmayan diğer izinli panel türü, yoksa boş.
     * Yalnız o yuvanın kaydı dolu ve türü izinsizse çalışır; bilinmeyen/boş yuvalar boş kalır.
     */
    private fun fallbackPanels(raw: SahneLayout, clean: SahneLayout): SahneLayout {
        var out = clean
        listOf(0, 1).forEach { i ->
            val ref = SahneSlotRef(SahneSlotKind.PANEL, i)
            val was = get(raw, ref)
            if (was == null || SahneForm.fitsPanel(was.type) || get(out, ref) != null) return@forEach
            val preferred = if (i == 0) WidgetType.MEDIA else WidgetType.TRIP
            val used = out.types()
            val pick = (listOf(preferred) + SahneForm.PANEL_TYPES).firstOrNull { it !in used } ?: return@forEach
            out = set(out, ref, SahneSlot(newId(out, pick), pick))
        }
        return out
    }

    /**
     * Kapı kilidi artık şeritte yok (sahnedeki "Kilitli" hapı yeterli): eski kayıttaki ilk kilit yuvası, sürüş modu düzende
     * yoksa sürüş moduna döner, varsa boşalır; ikinci ve sonraki kilit yuvaları da boşalır. Kilit yoksa düzen aynen döner.
     */
    fun migrateLock(layout: SahneLayout): SahneLayout {
        if (layout.strip.none { it?.type == WidgetType.LOCK }) return layout
        var hasMode = layout.slots().any { it.type == WidgetType.DRIVE_MODE }
        var out = layout
        layout.strip.forEachIndexed { i, s ->
            if (s?.type != WidgetType.LOCK) return@forEachIndexed
            val ref = SahneSlotRef(SahneSlotKind.STRIP, i)
            val cleared = set(out, ref, null)
            out = if (hasMode) cleared else set(cleared, ref, SahneSlot(newId(cleared, WidgetType.DRIVE_MODE), WidgetType.DRIVE_MODE))
            hasMode = true
        }
        return out
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
