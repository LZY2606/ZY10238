package app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class Pt(val x: Double, val y: Double, val w: Double)

object Fit {

    /** 普通或加权一元线性回归，返回 (截距, 斜率)。 */
    fun linear(points: List<Pt>): Pair<Double, Double> {
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (p in points) {
            sw += p.w; sx += p.w * p.x; sy += p.w * p.y
            sxx += p.w * p.x * p.x; sxy += p.w * p.x * p.y
        }
        val den = sw * sxx - sx * sx
        val slope = if (den == 0.0) 0.0 else (sw * sxy - sx * sy) / den
        val intercept = (sy - slope * sx) / sw
        return intercept to slope
    }

    data class LinResult(val intercept: Double, val slope: Double, val sse: Double)

    private fun linSse(points: List<Pt>): LinResult {
        val (a, b) = linear(points)
        var sse = 0.0
        for (p in points) {
            val r = p.y - (a + b * p.x)
            sse += p.w * r * r
        }
        return LinResult(a, b, sse)
    }

    /** 带截距非负约束的二元最小二乘，用于 A + B*phi 的饱和模型。 */
    private fun fit2(points: List<Pt>, phi: (Double) -> Double): DoubleArray {
        // 正规方程 X'WX beta = X'Wy
        val a = DoubleArray(4)
        val b = DoubleArray(2)
        for (p in points) {
            val p0 = 1.0; val p1 = phi(p.x)
            a[0] += p.w * p0 * p0; a[1] += p.w * p0 * p1
            a[3] += p.w * p1 * p1
            b[0] += p.w * p0 * p.y; b[1] += p.w * p1 * p.y
        }
        a[2] = a[1]
        return solve2(a, b)
    }

    private fun solve2(aIn: DoubleArray, bIn: DoubleArray): DoubleArray {
        val a = aIn.copyOf(); val b = bIn.copyOf()
        for (i in 0..1) {
            var piv = i
            for (k in (i + 1)..1) if (kotlin.math.abs(a[k * 2 + i]) > kotlin.math.abs(a[piv * 2 + i])) piv = k
            if (kotlin.math.abs(a[piv * 2 + i]) < 1e-12) continue
            if (piv != i) {
                for (c in 0..1) { val t = a[i * 2 + c]; a[i * 2 + c] = a[piv * 2 + c]; a[piv * 2 + c] = t }
                val t = b[i]; b[i] = b[piv]; b[piv] = t
            }
            for (r in 0..1) if (r != i) {
                val f = a[r * 2 + i] / a[i * 2 + i]
                for (c in i..1) a[r * 2 + c] -= f * a[i * 2 + c]
                b[r] -= f * b[i]
            }
        }
        val x = DoubleArray(2)
        x[0] = if (a[0] == 0.0) 0.0 else b[0] / a[0]
        x[1] = if (a[3] == 0.0) 0.0 else b[1] / a[3]
        return x
    }

    data class SatResult(val base: Double, val asym: Double, val rate: Double, val sse: Double)

    fun fitSaturated(points: List<Pt>): SatResult {
        if (points.size < 4) {
            val (a, b) = linear(points)
            return SatResult(max(0.0, a), max(0.0, b * 50.0), 0.02, Double.MAX_VALUE)
        }
        val rMin = ln(1.0 / 0.99) / max(1.0, points.maxOf { it.x })
        val rMax = ln(1.0 / 0.05) / max(1.0, points.minOf { it.x }.coerceAtLeast(1.0))
        var best: SatResult? = null
        var r = rMin.coerceAtMost(rMax)
        val rHi = rMax.coerceAtLeast(rMin)
        val steps = 60
        for (i in 0..steps) {
            val rate = if (steps == 0) r else r + (rHi - r) * i / steps
            val coef = fit2(points) { 1.0 - exp(-rate * it) }
            val base0 = coef[0]; val asym0 = coef[1]
            val (base, asym) = when {
                asym0 >= 0 -> max(0.0, base0) to asym0
                else -> 0.0 to max(0.0, (points.weightedMean { it.y }) )
            }
            var sse = 0.0
            for (p in points) {
                val pred = base + asym * (1.0 - exp(-rate * p.x))
                val e = p.y - pred
                sse += p.w * e * e
            }
            if (best == null || sse < best.sse) best = SatResult(base, asym, rate, sse)
        }
        return best!!
    }

    private inline fun List<Pt>.weightedMean(selector: (Pt) -> Double): Double {
        var sw = 0.0; var s = 0.0
        for (p in this) { sw += p.w; s += p.w * selector(p) }
        return s / sw
    }

    data class PieceResult(
        val intercept: Double, val slope1: Double, val knot: Double, val slope2: Double, val sse: Double
    )

    fun fitPiecewise(points: List<Pt>): PieceResult {
        if (points.size < 6) {
            val lin = linSse(points)
            return PieceResult(lin.intercept, lin.slope, points.map { it.x }.average(), lin.slope, lin.sse)
        }
        val xs = points.map { it.x }.distinct().sorted()
        var best: PieceResult? = null
        for (knot in xs.subList(1, xs.size - 1)) {
            val left = points.filter { it.x <= knot }
            val right = points.filter { it.x >= knot }
            if (left.size < 2 || right.size < 2) continue
            val (a, b1) = linear(left)
            val yk = a + b1 * knot
            var sx = 0.0; var sy = 0.0; var sw = 0.0; var sxx = 0.0; var sxy = 0.0
            for (p in right) {
                val ux = p.x - knot; val uy = p.y - yk
                sw += p.w; sx += p.w * ux; sy += p.w * uy
                sxx += p.w * ux * ux; sxy += p.w * ux * uy
            }
            val den = sw * sxx - sx * sx
            val b2 = if (den == 0.0) b1 else max(0.0, (sw * sxy - sx * sy) / den)
            var sse = 0.0
            for (p in points) {
                val pred = if (p.x <= knot) a + b1 * p.x else yk + b2 * (p.x - knot)
                val e = p.y - pred
                sse += p.w * e * e
            }
            if (best == null || sse < best.sse) best = PieceResult(a, b1, knot, b2, sse)
        }
        return best ?: linSse(points).let {
            PieceResult(it.intercept, it.slope, points.map { it.x }.average(), it.slope, it.sse)
        }
    }

    data class Level(val x: Double, val y: Double, val w: Double)

    /** 加权 PAVA 单调回归，形成单调阶梯（单调样条候选）。 */
    fun isotonic(points: List<Pt>): List<Level> {
        val levels = points.sortedBy { it.x }.map { Level(it.x, it.y, it.w) }.toMutableList()
        var i = 0
        while (i < levels.size - 1) {
            if (levels[i].y > levels[i + 1].y + 1e-9) {
                val a = levels[i]; val b = levels[i + 1]
                val w = a.w + b.w
                val y = (a.y * a.w + b.y * b.w) / w
                val merged = Level(b.x, y, w)
                levels[i] = merged
                levels.removeAt(i + 1)
                if (i > 0) i--
            } else i++
        }
        return levels
    }

    fun isotonicSse(points: List<Pt>): Pair<Double, List<Level>> {
        val levels = isotonic(points)
        fun predAt(x: Double): Double {
            if (x <= levels.first().x) return levels.first().y
            for (k in 1 until levels.size) if (x <= levels[k].x) return levels[k].y
            return levels.last().y
        }
        var sse = 0.0
        for (p in points) {
            val e = p.y - predAt(p.x)
            sse += p.w * e * e
        }
        return sse to levels
    }

    fun rmse(sse: Double, n: Int): Double = sqrt(sse / n.coerceAtLeast(1))

    fun bic(sse: Double, n: Int, k: Int): Double {
        val sigma2 = max(sse / n.coerceAtLeast(1), 1e-6)
        return n * ln(sigma2) + k * ln(n.toDouble())
    }
}
