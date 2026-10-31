package com.mapcontrol.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.util.Random

/** Koltuk kartı görünümü: klasik (yazılı) ya da deneysel taş-lav. */
internal enum class SeatCardStyle(val key: String) {
    CLASSIC("classic"), LAVA("lava");

    companion object {
        /** "lava"/"classic" (büyük küçük harf önemsiz); tanınmazsa null. */
        fun parse(raw: String?): SeatCardStyle? = values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Taş-lav kartının saf (Android'siz) kısmı: çatlak geometrisi, lav/buz kaplama oranı, demo seviyesi ayrıştırma. */
internal object SeatStone {

    class Pt(val x: Float, val y: Float)

    /** Kırık çizgi; [width] 1.0 = ana çatlak, daha ince dallar < 1. */
    class Crack(val points: List<Pt>, val width: Float)

    class Blob(val x: Float, val y: Float, val r: Float)

    /** Seviyeye göre lav/buz kaplama hedefi (kart alanının oranı). */
    fun coverage(level: Int): Float = when (level) {
        1 -> 0.10f
        2 -> 0.25f
        3 -> 0.45f
        else -> 0f
    }

    /** Çatlak kalınlık çarpanı: seviye yükseldikçe çatlaklar genişler. */
    fun crackWidthScale(level: Int): Float = when (level) {
        1 -> 1.3f
        2 -> 1.8f
        3 -> 2.5f
        else -> 1f
    }

    /** Çatlak içi parlama opaklığı (0..1): kapalıda neredeyse görünmez. */
    fun crackGlowAlpha(level: Int): Float = when (level) {
        1 -> 0.80f
        2 -> 0.92f
        3 -> 1f
        else -> 0.16f
    }

    /** Aynı kutucuk türü/tarafı her zaman aynı çatlakları çizsin. */
    fun seed(vent: Boolean, driver: Boolean): Long = (if (vent) 1000L else 0L) + (if (driver) 17L else 29L)

    /** "3,0" → (sürücü 3, yolcu 0); bozuksa null. Seviyeler 0..3'e kırpılır. */
    fun parseLevels(raw: String?): Pair<Int, Int>? {
        val parts = raw?.split(',') ?: return null
        if (parts.size != 2) return null
        val d = parts[0].trim().toIntOrNull() ?: return null
        val p = parts[1].trim().toIntOrNull() ?: return null
        return d.coerceIn(0, 3) to p.coerceIn(0, 3)
    }

    /** Kenardan başlayan birkaç ana çatlak ve dalları; tohuma bağlı, her seferinde aynı. */
    fun cracks(seed: Long, w: Float, h: Float): List<Crack> {
        val rnd = Random(seed * 7919L + 13L)
        val unit = min(w, h)
        val step = unit * 0.075f
        val out = ArrayList<Crack>()
        val mains = 3 + rnd.nextInt(2)
        repeat(mains) { i ->
            // Kenar noktası: dört kenara dağıt, ilkini sol/üstten başlat ki kart ortası hep çatlasın.
            val side = (i + rnd.nextInt(2)) % 4
            val t = 0.15f + rnd.nextFloat() * 0.7f
            val (sx, sy) = when (side) {
                0 -> 0f to h * t
                1 -> w * t to 0f
                2 -> w to h * t
                else -> w * t to h
            }
            val toCenter = Math.atan2((h / 2 - sy).toDouble(), (w / 2 - sx).toDouble()).toFloat()
            walk(rnd, out, sx, sy, toCenter + (rnd.nextFloat() - 0.5f) * 0.9f, step, 14 + rnd.nextInt(5), 1f, w, h, 2)
        }
        return out
    }

    private fun walk(
        rnd: Random, out: MutableList<Crack>, x0: Float, y0: Float, dir0: Float, step: Float,
        steps: Int, width: Float, w: Float, h: Float, depth: Int,
    ) {
        val pts = ArrayList<Pt>()
        var x = x0; var y = y0; var dir = dir0
        pts += Pt(x, y)
        val branches = ArrayList<Triple<Float, Float, Float>>()
        for (i in 0 until steps) {
            dir += (rnd.nextFloat() - 0.5f) * 0.9f
            val len = step * (0.6f + rnd.nextFloat() * 0.8f)
            x += cos(dir.toDouble()).toFloat() * len
            y += sin(dir.toDouble()).toFloat() * len
            if (x < -step || x > w + step || y < -step || y > h + step) break
            pts += Pt(x, y)
            if (depth > 0 && i > 1 && rnd.nextFloat() < 0.28f) {
                val sign = if (rnd.nextBoolean()) 1f else -1f
                branches += Triple(x, y, dir + sign * (0.45f + rnd.nextFloat() * 0.7f))
            }
        }
        if (pts.size >= 2) out += Crack(pts, width)
        for ((bx, by, bd) in branches) {
            walk(rnd, out, bx, by, bd, step * 0.8f, 3 + rnd.nextInt(5), width * 0.6f, w, h, depth - 1)
        }
    }

    /** Yaklaşık kaplama oranı: ızgara örnekleriyle dairelerin birleşimi. */
    fun estimateCoverage(blobs: List<Blob>, w: Float, h: Float, grid: Int = 48): Float {
        if (blobs.isEmpty()) return 0f
        var hit = 0
        val gx = grid
        val gy = max(8, (grid * h / w).toInt())
        for (iy in 0 until gy) for (ix in 0 until gx) {
            val px = (ix + 0.5f) * w / gx
            val py = (iy + 0.5f) * h / gy
            if (blobs.any { hypot(px - it.x, py - it.y) <= it.r }) hit++
        }
        return hit.toFloat() / (gx * gy)
    }

    /** Lav/buz havuzları: çatlak boyunca, hedef kaplamaya ulaşana kadar daire eklenir. */
    fun blobs(level: Int, cracks: List<Crack>, seed: Long, w: Float, h: Float): List<Blob> {
        val target = coverage(level)
        if (target <= 0f || cracks.isEmpty()) return emptyList()
        val rnd = Random(seed * 31L + level)
        val unit = min(w, h)
        val rMax = unit * when (level) { 1 -> 0.07f; 2 -> 0.13f; else -> 0.20f }
        val rMin = rMax * 0.45f
        val all = cracks.flatMap { c -> c.points.map { it to c.width } }
        val out = ArrayList<Blob>()
        var tries = 0
        while (tries++ < 600) {
            val (p, wd) = all[rnd.nextInt(all.size)]
            val r = rMin + rnd.nextFloat() * (rMax - rMin) * (0.5f + 0.5f * wd)
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val off = r * 0.35f * rnd.nextFloat()
            out += Blob(p.x + cos(ang.toDouble()).toFloat() * off, p.y + sin(ang.toDouble()).toFloat() * off, r)
            if (tries % 3 == 0 && estimateCoverage(out, w, h) >= target) break
        }
        return out
    }
}
