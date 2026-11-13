package com.anycam.app.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds a photo from a burst of sensor frames by putting every sample where it really was.
 *
 * A sensor pixel measures one colour. Developing a single frame has to guess the other two
 * from neighbours, which is where softness and false colour come from. But hands never hold
 * still: between the shots of a burst the picture moves by a pixel and a fraction, so a spot
 * that fell on a green pixel in one shot falls on a red or blue one in the next. Laid down at
 * their true positions (found to a fraction of a pixel by [Align]), the frames between them
 * measure all three colours almost everywhere. The photo is then made from measurements,
 * not guesses: sharper than any one frame, and cleaner, because several samples land near
 * every pixel. (The method of Wronski et al., "Handheld Multi-Frame Super-Resolution".)
 *
 * Where frames disagree with the reference beyond what noise and their fine detail explain
 * (something moved), they are left out; a single frame's developed colour fills in wherever
 * too few samples landed.
 */
object Stack {

    /** [notes]: how the merge went, frame by frame (kept with the photo, for looking into a result later). */
    class Result(val photo: Yuv, val reference: Int, val used: Int, val noise: Float, val notes: String = "")

    /** For experiments: the true shift (x, y in sensor pixels) of a frame, to use instead of the measured one. */
    internal var knownShift: ((Int) -> FloatArray?)? = null

    /**
     * [base]: the reference frame on its own, prepared by [RawMerge.merge] (it also tells which
     * frame is the reference via [referenceIndex]). [frames]: the whole burst, reference included.
     */
    fun develop(frames: List<RawFrame>, info: RawInfo, base: RawMerge.Merged, referenceIndex: Int, look: Look = Look.PLAIN): Result {
        val clock = LongArray(6)
        clock[0] = System.nanoTime()
        val w = base.w
        val h = base.h
        val same = frames.filter { it.w == w && it.h == h }
        val refIndex = referenceIndex.coerceIn(0, same.size - 1)
        val proxies = same.map { RawMerge.proxy(it, info) }
        val smalls = proxies.map { Align.small(it) }
        val others = same.indices.filter { it != refIndex }
        val fields = others.map { k -> Align.tiles(proxies[refIndex], proxies[k], Align.global(smalls[refIndex], smalls[k])) }
        val pw = proxies[refIndex].w
        val ph = proxies[refIndex].h
        val cols = fields.firstOrNull()?.cols ?: 1

        clock[1] = System.nanoTime()
        // ---- the same shifts, now to a fraction of a sensor pixel
        val top = info.white.toInt().coerceIn(1, 65535)
        val greyOf = FloatArray(top + 1) { v -> sqrt(((v - info.black.sum() / 4f) / (info.white - info.black.sum() / 4f).coerceAtLeast(1f)).coerceIn(0f, 1f)) * 255f }
        val exact = others.mapIndexed { n, k ->
            val known = knownShift?.invoke(k)
            if (known != null) FloatArray(fields[n].dx.size * 2) { known[it % 2] } else refine(same[refIndex], same[k], fields[n], greyOf, top)
        }

        clock[2] = System.nanoTime()
        // ---- how far apart two views of the same thing may be
        val costs = fields.flatMap { f -> f.cost.filter { it < Float.MAX_VALUE }.toList() }.sorted()
        val typical = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        val calm = if (costs.isEmpty()) 0f else costs[costs.size / 10]
        val tileLimit = minOf(typical * 2.5f + 2f, calm * 5f + 4f)
        // A frame that as a whole matches far worse than the best of them wasn't aligned at
        // all (or shows something else): it is left out everywhere.
        fun middle(f: Align.Field): Float = f.cost.filter { it < Float.MAX_VALUE }.sorted().let { if (it.isEmpty()) Float.MAX_VALUE else it[it.size / 2] }
        val middles = fields.map { middle(it) }
        val bestMiddle = middles.minOrNull() ?: 0f
        val frameOk = middles.map { it <= bestMiddle * 2.5f + 2f }
        val blackMean = info.black.sum() / 4f
        val range = (info.white - blackMean).coerceAtLeast(1f)
        // Noise of one sample on the alignment copy's curve, by that copy's brightness (0..255).
        val s = info.noiseS
        val o = info.noiseO
        val sampleNoise = FloatArray(256) { b ->
            if (s != null && o != null) {
                val x = ((b / 255f).pow(2)).coerceIn(0.002f, 1f)
                127.5f * sqrt((s + o / x).coerceAtLeast(0f))
            } else calm * 1.77f
        }

        // ---- which frames may be used where (one weight per filter group, 0..255)
        val soft = proxies.map { blur(it) }
        val refSoft = soft[refIndex]
        val trust = others.mapIndexed { n, k ->
            val f = fields[n]
            val out = ByteArray(pw * ph)
            val frameSoft = soft[k]
            if (Native.use) Native.trust(w, h, pw, ph, cols, Align.TILE, frameOk[n], tileLimit, refSoft.px, frameSoft.px, f.dx, f.dy, f.cost, exact[n],
                same[refIndex].px, same[k].px, greyOf, sampleNoise, out)
            else Parallel.rows(ph) { qy ->
                for (qx in 0 until pw) {
                    val tile = (qy / Align.TILE) * cols + qx / Align.TILE
                    if (!frameOk[n] || f.cost[tile] > tileLimit) continue
                    val fx = qx + f.dx[tile]
                    val fy = qy + f.dy[tile]
                    if (fx < 1 || fy < 1 || fx >= pw - 1 || fy >= ph - 1 || qx < 1 || qy < 1 || qx >= pw - 1 || qy >= ph - 1) continue
                    val i = qy * pw + qx
                    val r = refSoft.px[i].toInt() and 0xFF
                    val d = abs((frameSoft.px[fy * pw + fx].toInt() and 0xFF) - r)
                    // Allowed: noise (a soft group of nine is six times calmer than one sample),
                    // plus what a fraction of a pixel of misplacement does where the picture is steep.
                    val steep = abs((refSoft.px[i + 1].toInt() and 0xFF) - (refSoft.px[i - 1].toInt() and 0xFF)) +
                        abs((refSoft.px[i + pw].toInt() and 0xFF) - (refSoft.px[i - pw].toInt() and 0xFF))
                    val keep = 1.5f + 0.6f * sampleNoise[r] + 0.08f * steep
                    val coarse = if (d <= keep) 1f else if (d >= 2f * keep) 0f else (2f * keep - d) / keep
                    if (coarse == 0f) continue
                    // And exactly: the frame's brightness at the very spot the shift says this
                    // group lies (between its pixels), against the reference's. Fine detail that
                    // is truly lined up agrees to within noise; a frame that is a pixel or two
                    // off — which the soft comparison above lets through — does not.
                    val ex = qx * 2 + exact[n][2 * tile]
                    val ey = qy * 2 + exact[n][2 * tile + 1]
                    val ix = floor(ex).toInt()
                    val iy = floor(ey).toInt()
                    if (ix < 0 || iy < 0 || ix >= w - 2 || iy >= h - 2) continue
                    val ax = ex - ix
                    val ay = ey - iy
                    val fr = same[k]
                    val there = (grey4(fr, ix, iy, w, greyOf, top) * (1 - ax) + grey4(fr, ix + 1, iy, w, greyOf, top) * ax) * (1 - ay) +
                        (grey4(fr, ix, iy + 1, w, greyOf, top) * (1 - ax) + grey4(fr, ix + 1, iy + 1, w, greyOf, top) * ax) * ay
                    val here = grey4(same[refIndex], qx * 2, qy * 2, w, greyOf, top)
                    val keepExact = 1.5f + 1.5f * sampleNoise[r]
                    val de = abs(there - here)
                    val fine = if (de <= keepExact) 1f else if (de >= 2f * keepExact) 0f else (2f * keepExact - de) / keepExact
                    out[i] = (255f * coarse * fine).toInt().toByte()
                }
            }
            // One group's verdict is noisy: each takes the lowest of itself and its neighbours'
            // average, so a patch that mostly disagrees is left out as a whole.
            val calmed = ByteArray(pw * ph)
            Parallel.rows(ph) { qy ->
                if (qy < 1 || qy >= ph - 1) return@rows
                for (qx in 1 until pw - 1) {
                    val i = qy * pw + qx
                    var sum = 0
                    for (j in -1..1) for (m in -1..1) sum += out[i + j * pw + m].toInt() and 0xFF
                    calmed[i] = minOf(out[i].toInt() and 0xFF, sum / 9).toByte()
                }
            }
            calmed
        }

        clock[3] = System.nanoTime()
        // ---- how much fine detail the picture has, per filter group (0 = calm .. 3 = busy)
        // Where it is calm, samples from a little further away may be averaged in (less noise);
        // where it is busy, only those that landed almost on the pixel (no blur).
        val busy = ByteArray(pw * ph)
        val refGroups = proxies[refIndex]
        Parallel.rows(ph) { qy ->
            if (qy < 1 || qy >= ph - 1) return@rows
            for (qx in 1 until pw - 1) {
                val i = qy * pw + qx
                val r = refSoft.px[i].toInt() and 0xFF
                // The spread of brightness among a group and its neighbours, beyond what noise
                // alone would spread them (taken from the unsoftened copy: softening hides
                // exactly the fine detail this is looking for).
                var lo = 255
                var hi = 0
                for (j in -1..1) for (k in -1..1) {
                    val v = refGroups.px[i + j * pw + k].toInt() and 0xFF
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                val level = (hi - lo - 2f * sampleNoise[r]) / (0.5f * sampleNoise[r] + 2f)
                busy[i] = level.toInt().coerceIn(0, LEVELS - 1).toByte()
            }
        }
        // Sample weights by distance from the pixel, worked out once for every fraction of a
        // pixel (in eighths) and every level of detail: 3×3 neighbours each. Green carries the
        // detail and follows the level; red and blue are sparser and always use the wide one.
        val greenWeights = Array(LEVELS) { level -> kernels(GREEN_SIGMAS[level]) }
        val colourWeights = kernels(COLOUR_SIGMA)

        val colourAt = IntArray(4) { info.colour(it) }
        val besideIsRed = BooleanArray(4) { info.colour(it xor 1) == 0 }
        // The reference's green everywhere (measured or worked out), for what follows.
        val green = ShortArray(w * h)
        Parallel.rows(h) { y ->
            val rgb = FloatArray(3)
            for (x in 0 until w) {
                val pos = (y and 1) * 2 + (x and 1)
                Develop.pixel(base, x, y, colourAt[pos], besideIsRed[pos], rgb)
                green[y * w + x] = (rgb[1].coerceIn(0f, 1f) * 65535f).toInt().toShort()
            }
        }

        // Where each tile of each frame lands: the same for every pixel of the tile (whole
        // pixels, and which of the precomputed weight sets the fraction beyond them picks).
        val place = Array(fields.size) { n ->
            IntArray(cols * ((ph + Align.TILE - 1) / Align.TILE) * 3).also { out ->
                for (tile in 0 until out.size / 3) {
                    val ex = exact[n][2 * tile]
                    val ey = exact[n][2 * tile + 1]
                    val ox = floor(ex + 0.5f).toInt()
                    val oy = floor(ey + 0.5f).toInt()
                    out[3 * tile] = ox
                    out[3 * tile + 1] = oy
                    out[3 * tile + 2] = (((ey - oy + 0.5f) * STEPS).toInt().coerceIn(0, STEPS) * (STEPS + 1) + ((ex - ox + 0.5f) * STEPS).toInt().coerceIn(0, STEPS)) * 9
                }
            }
        }

        clock[4] = System.nanoTime()
        val outY = ByteArray(w * h)
        val outU = ByteArray(w * h / 4)
        val outV = ByteArray(w * h / 4)
        val ccm = info.ccm
        val a = look.colour
        val black = info.black
        val gain = info.wb
        val sh = info.shading
        val scale = 1f / range
        val ref = same[refIndex]
        var contributed = 0.0
        val lock = Any()

        if (Native.use) contributed = Native.build(w, h, pw, cols, Align.TILE, base.light, Array(fields.size) { same[others[it]].px },
            Array(fields.size) { trust[it] }, place, busy, green, greenWeights, colourWeights,
            colourAt, besideIsRed, black, scale, gain, sh?.cols ?: 0, sh?.rows ?: 0, sh?.gains, ccm, Develop.gammaTable, look.gain, a,
            FILL, BUSY_FILL, STEPS, outY, outU, outV)
        else Parallel.rows(h / 2) { cy ->
            val rgb = FloatArray(3)
            val sum = FloatArray(3)
            val weight = FloatArray(3)
            val lens = FloatArray(3) { 1f }
            var rowWeight = 0.0
            var shRow = 0
            var shAy = 0f
            if (sh != null) {
                val fy = (cy * 2 + 0.5f) / (h - 1f) * (sh.rows - 1)
                shRow = fy.toInt().coerceIn(0, sh.rows - 2)
                shAy = (fy - shRow).coerceIn(0f, 1f)
            }
            for (cx in 0 until w / 2) {
                val tile = (cy / Align.TILE) * cols + cx / Align.TILE
                if (sh != null) {
                    val fx = (cx * 2 + 0.5f) / (w - 1f) * (sh.cols - 1)
                    val x0 = fx.toInt().coerceIn(0, sh.cols - 2)
                    val ax = (fx - x0).coerceIn(0f, 1f)
                    val o0 = (shRow * sh.cols + x0) * 3
                    val o1 = o0 + sh.cols * 3
                    for (c in 0..2) {
                        lens[c] = (sh.gains[o0 + c] * (1 - ax) + sh.gains[o0 + 3 + c] * ax) * (1 - shAy) +
                            (sh.gains[o1 + c] * (1 - ax) + sh.gains[o1 + 3 + c] * ax) * shAy
                    }
                }
                var su = 0f
                var sv = 0f
                val level = busy[cy * pw + cx].toInt()
                // In busy parts the reference's own fine structure counts for more (it is exactly
                // placed); in calm parts the many samples do (they are cleaner).
                val fill = FILL + level * BUSY_FILL
                for (p in 0..3) {
                    val x = cx * 2 + p % 2
                    val y = cy * 2 + p / 2
                    // The reference on its own: its measured colour here, the other two worked out.
                    Develop.pixel(base, x, y, colourAt[p], besideIsRed[p], rgb)
                    val own = colourAt[p]
                    sum[0] = 0f; sum[1] = 0f; sum[2] = 0f
                    weight[0] = 0f; weight[1] = 0f; weight[2] = 0f
                    // Its own measurement counts in full. Red and blue are kept as their difference
                    // from green: that difference changes slowly across a picture, so it can be
                    // averaged over a wider reach without blurring anything (the detail is in green).
                    sum[own] = if (own == 1) rgb[1] else rgb[own] - rgb[1]
                    weight[own] = 1f
                    for (n in fields.indices) {
                        val t = trust[n][cy * pw + cx].toInt() and 0xFF
                        if (t == 0) continue
                        // Where this pixel is in the other frame: whole pixels and the fraction beyond.
                        val pl = place[n]
                        val ix = x + pl[3 * tile]
                        val iy = y + pl[3 * tile + 1]
                        if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) continue
                        val at = pl[3 * tile + 2]
                        val tight = greenWeights[level]
                        val wide = colourWeights
                        val fr = same[others[n]]
                        val tw = t / 255f
                        for (j in -1..1) {
                            val row = (iy + j) * w
                            val rowPos = ((iy + j) and 1) * 2
                            for (i in -1..1) {
                                val pos = rowPos + ((ix + i) and 1)
                                val c = colourAt[pos]
                                val k = (j + 1) * 3 + i + 1
                                val wt = tw * (if (c == 1) tight[at + k] else wide[at + k])
                                if (wt < 0.01f) continue
                                var v = ((fr.px[row + ix + i].toInt() and 0xFFFF) - black[pos]) * scale
                                if (v < 0f) v = 0f
                                v *= lens[c] * gain[c]
                                if (v > 1f) v = 1f
                                if (c != 1) {
                                    // Against the green at the spot of the picture where this sample sits.
                                    val gx = (x + i).coerceIn(0, w - 1)
                                    val gy = (y + j).coerceIn(0, h - 1)
                                    v -= (green[gy * w + gx].toInt() and 0xFFFF) / 65535f
                                }
                                sum[c] += v * wt
                                weight[c] += wt
                            }
                        }
                    }
                    rowWeight += weight[1]
                    // Measurements where enough landed; the reference's worked-out value fills the rest.
                    val g = (sum[1] + fill * rgb[1]) / (weight[1] + fill)
                    val r = (g + (sum[0] + FILL * (rgb[0] - rgb[1])) / (weight[0] + FILL)).coerceAtLeast(0f)
                    val b = (g + (sum[2] + FILL * (rgb[2] - rgb[1])) / (weight[2] + FILL)).coerceAtLeast(0f)
                    val lr = (ccm[0] * r + ccm[1] * g + ccm[2] * b).coerceIn(0f, 1f)
                    val lg = (ccm[3] * r + ccm[4] * g + ccm[5] * b).coerceIn(0f, 1f)
                    val lb = (ccm[6] * r + ccm[7] * g + ccm[8] * b).coerceIn(0f, 1f)
                    var dr = Develop.display(lr)
                    var dg = Develop.display(lg)
                    var db = Develop.display(lb)
                    val lift = look.gain[(0.299f * dr + 0.587f * dg + 0.114f * db).toInt().coerceIn(0, 255)]
                    dr *= lift
                    dg *= lift
                    db *= lift
                    val fr2 = (a[0] * dr + a[1] * dg + a[2] * db + a[3]).coerceIn(0f, 255f)
                    val fg2 = (a[4] * dr + a[5] * dg + a[6] * db + a[7]).coerceIn(0f, 255f)
                    val fb2 = (a[8] * dr + a[9] * dg + a[10] * db + a[11]).coerceIn(0f, 255f)
                    outY[y * w + x] = (0.299f * fr2 + 0.587f * fg2 + 0.114f * fb2 + 0.5f).toInt().coerceIn(0, 255).toByte()
                    su += -0.168736f * fr2 - 0.331264f * fg2 + 0.5f * fb2
                    sv += 0.5f * fr2 - 0.418688f * fg2 - 0.081312f * fb2
                }
                outU[cy * (w / 2) + cx] = (su / 4 + 128.5f).toInt().coerceIn(0, 255).toByte()
                outV[cy * (w / 2) + cx] = (sv / 4 + 128.5f).toInt().coerceIn(0, 255).toByte()
            }
            synchronized(lock) { contributed += rowWeight }
        }
        // Green samples' worth of light behind the average pixel, as "shots used" (one shot gives half a green sample per pixel).
        val perPixel = contributed / (w.toLong() * h)
        val used = Math.round(perPixel * 2).toInt().coerceIn(1, same.size)
        clock[5] = System.nanoTime()
        val ms = (1..5).joinToString("/") { ((clock[it] - clock[it - 1]) / 1_000_000).toString() }
        val notes = StringBuilder("stack ${w}x$h (align/refine/trust/prepare/build ms $ms) ref $refIndex of ${same.size}; cost typical ${"%.1f".format(typical)} calm ${"%.1f".format(calm)} limit ${"%.1f".format(tileLimit)}; " +
            "noise figures ${info.noiseS} ${info.noiseO} (sample noise mid ${"%.1f".format(sampleNoise[128])}); green per pixel ${"%.2f".format(perPixel)}; busy ${busy.count { it > 1 } * 100L / busy.size.coerceAtLeast(1)}%")
        for (n in fields.indices) {
            val xs = FloatArray(fields[n].dx.size) { exact[n][2 * it] }.also { it.sort() }
            val ys = FloatArray(fields[n].dx.size) { exact[n][2 * it + 1] }.also { it.sort() }
            val trusted = trust[n].sumOf { (it.toInt() and 0xFF).toLong() } * 100 / (255L * trust[n].size)
            notes.append("; f${others[n]}: shift ${"%.1f".format(xs[xs.size / 2])},${"%.1f".format(ys[ys.size / 2])} (spread ${"%.1f".format(xs[xs.size * 9 / 10] - xs[xs.size / 10])},${"%.1f".format(ys[ys.size * 9 / 10] - ys[ys.size / 10])}) " +
                "cost ${"%.1f".format(middles[n])} ${if (frameOk[n]) "ok" else "dropped"} trusted $trusted%")
        }
        return Result(Yuv(w, h, outY, outU, outV), refIndex, used, calm, notes.toString())
    }

    /**
     * Each tile's shift in sensor pixels, to a fraction of one (x then y per tile). [field] has
     * it to the nearest filter group; here the frames are compared at full size around that,
     * on brightness made from each pixel and its three neighbours (any such four have one of
     * every filter colour between them), and the best position is placed between whole pixels
     * from how the match falls off either side.
     */
    private fun refine(ref: RawFrame, frame: RawFrame, field: Align.Field, greyOf: FloatArray, top: Int): FloatArray {
        val w = ref.w
        val h = ref.h
        val size = Align.TILE * 2
        val out = FloatArray(field.dx.size * 2)
        if (Native.use) {
            Native.refine(w, h, field.cols, field.rows, size, field.dx, field.dy, field.cost, ref.px, frame.px, greyOf, CAP, out)
            return out
        }
        fun grey(f: RawFrame, x: Int, y: Int): Float {
            val o = y * w + x
            val v = ((f.px[o].toInt() and 0xFFFF) + (f.px[o + 1].toInt() and 0xFFFF) + (f.px[o + w].toInt() and 0xFFFF) + (f.px[o + w + 1].toInt() and 0xFFFF)) shr 2
            return greyOf[if (v > top) top else v]
        }
        Parallel.rows(field.rows) { r ->
            val grid = FloatArray(25)
            for (c in 0 until field.cols) {
                val tile = r * field.cols + c
                val bx = 2 * field.dx[tile]
                val by = 2 * field.dy[tile]
                out[2 * tile] = bx.toFloat()
                out[2 * tile + 1] = by.toFloat()
                if (field.cost[tile] == Float.MAX_VALUE) continue
                val x0 = c * size
                val y0 = r * size
                val x1 = minOf(w - 1, x0 + size)
                val y1 = minOf(h - 1, y0 + size)
                var best = Float.MAX_VALUE
                var bi = 0
                var bj = 0
                for (j in -2..2) for (i in -2..2) {
                    var sum = 0.0
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        val fy = y + by + j
                        if (fy in 0 until h - 1) {
                            var x = x0
                            while (x < x1) {
                                val fx = x + bx + i
                                if (fx in 0 until w - 1) {
                                    val d = grey(ref, x, y) - grey(frame, fx, fy)
                                    // Capped: something that moved (and differs hugely wherever it is put)
                                    // mustn't decide where the rest of the tile goes.
                                    sum += if (d > CAP || d < -CAP) CAP * CAP else d * d
                                    n++
                                }
                                x += 3
                            }
                        }
                        y += 3
                    }
                    val cost = if (n < 64) Float.MAX_VALUE else (sum / n).toFloat()
                    grid[(j + 2) * 5 + i + 2] = cost
                    if (cost < best) { best = cost; bi = i; bj = j }
                }
                if (best == Float.MAX_VALUE) continue
                fun between(lo: Float, hi: Float): Float {
                    if (lo == Float.MAX_VALUE || hi == Float.MAX_VALUE) return 0f
                    val curve = lo + hi - 2 * best
                    return if (curve <= 1e-4f) 0f else ((lo - hi) / (2 * curve)).coerceIn(-0.5f, 0.5f)
                }
                val at = (bj + 2) * 5 + bi + 2
                val sx = if (abs(bi) < 2) between(grid[at - 1], grid[at + 1]) else 0f
                val sy = if (abs(bj) < 2) between(grid[at - 5], grid[at + 5]) else 0f
                out[2 * tile] = bx + bi + sx
                out[2 * tile + 1] = by + bj + sy
            }
        }
        return out
    }

    /** Brightness of a pixel and its three neighbours (right, below, diagonal): one of every filter colour. */
    private fun grey4(f: RawFrame, x: Int, y: Int, w: Int, greyOf: FloatArray, top: Int): Float {
        val o = y * w + x
        val v = ((f.px[o].toInt() and 0xFFFF) + (f.px[o + 1].toInt() and 0xFFFF) + (f.px[o + w].toInt() and 0xFFFF) + (f.px[o + w + 1].toInt() and 0xFFFF)) shr 2
        return greyOf[if (v > top) top else v]
    }

    /** A plane averaged over each 3×3 neighbourhood (edges copied). */
    private fun blur(p: Align.Plane): Align.Plane {
        val out = p.px.copyOf()
        Parallel.rows(p.h) { y ->
            if (y < 1 || y >= p.h - 1) return@rows
            for (x in 1 until p.w - 1) {
                var sum = 0
                val i = y * p.w + x
                for (j in -1..1) for (k in -1..1) sum += p.px[i + j * p.w + k].toInt() and 0xFF
                out[i] = ((sum + 4) / 9).toByte()
            }
        }
        return Align.Plane(p.w, p.h, out)
    }

    /** Weights for a 3×3 neighbourhood, for every fraction of a pixel in [STEPS]ths each way (y, then x, then the nine). */
    private fun kernels(sigma: Float): FloatArray {
        val out = FloatArray((STEPS + 1) * (STEPS + 1) * 9)
        for (sy in 0..STEPS) for (sx in 0..STEPS) {
            val subX = sx / STEPS.toFloat() - 0.5f
            val subY = sy / STEPS.toFloat() - 0.5f
            for (j in -1..1) for (i in -1..1) {
                val dx = i - subX
                val dy = j - subY
                out[(sy * (STEPS + 1) + sx) * 9 + (j + 1) * 3 + i + 1] = exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
            }
        }
        return out
    }

    private const val CAP = 40f
    private const val STEPS = 8
    private const val LEVELS = 4
    /** How far green samples reach, from calm to busy parts of the picture (in pixels). */
    private val GREEN_SIGMAS = floatArrayOf(0.75f, 0.55f, 0.42f, 0.34f)
    private const val COLOUR_SIGMA = 0.8f
    /** How much the reference's worked-out value counts beside measurements (it rules where none landed). */
    private const val FILL = 0.15f
    private const val BUSY_FILL = 0.3f
}
