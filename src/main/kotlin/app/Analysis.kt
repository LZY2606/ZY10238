package app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class AnalysisService(private val database: Database) {
    fun analyze(request: AnalysisRequest): AnalysisResponse {
        require(request.trait in TRAITS) { "未知性状" }
        require(request.targetDay in 0..60) { "目标日必须在 0 到 60 之间" }
        val designVersionId = database.designVersionId()
        val preprocessingVersionId = request.preprocessingVersionId ?: database.latestPreprocessingVersion()
        val plants = database.plants().filter { it.id !in request.excludedPlantIds }
        val plantById = plants.associateBy { it.id }
        val rawEffective = database.effectiveObservations().filter { it.observation.plantId in plantById }
        val boundaries = database.boundaries().associateBy { it.batch }
        val offsets = estimateOffsets(plants, rawEffective, boundaries, request.trait)
        val offsetByBatch = offsets.filter { it.status == "IDENTIFIED" }.associate { it.batch to (it.offset ?: 0.0) }
        val effective = rawEffective.map { row ->
            val batch = plantById[row.observation.plantId]?.batch
            val shift = if (row.observation.cameraEra == "new") offsets.firstOrNull { it.batch == batch && it.status == "IDENTIFIED" }?.offset else null
            if (shift == null) row else row.copy(
                adjustedLeafArea = row.observation.leafArea - shift,
                adjustedHeight = row.observation.height - shift,
                adjustedCanopyWidth = row.observation.canopyWidth - shift,
            )
        }
        val curves = plants.map { fitPlant(it, effective, request.trait) }
        val cells = estimateCells(plants, curves, request.targetDay)
        val interactions = estimateInteractions(cells)
        val coverage = coverage(plants, rawEffective, request.trait, plantById)
        val extrapolation = curves.associate { curve ->
            curve.plantId to if (request.targetDay > curve.supportMaxDay) {
                (curve.supportMaxDay + 1)..request.targetDay
            } else {
                emptyList()
            }
        }.mapValues { it.value.toList() }
        val notes = buildNotes(offsets, cells, preprocessingVersionId)
        return AnalysisResponse(
            designVersionId = designVersionId,
            preprocessingVersionId = preprocessingVersionId,
            targetDay = request.targetDay,
            trait = request.trait,
            excludedPlantIds = request.excludedPlantIds.sorted(),
            offsets = offsets,
            curves = curves,
            cells = cells,
            interactions = interactions,
            coverage = coverage,
            adjustedObservations = effective,
            extrapolation = extrapolation.mapValues { it.value.toList() },
            notes = notes,
        )
    }

    private fun estimateOffsets(
        plants: List<Plant>,
        rows: List<EffectiveObservation>,
        boundaries: Map<String, Boundary>,
        trait: String,
    ): List<OffsetEstimate> {
        return plants.map { it.batch }.distinct().sorted().map { batch ->
            val boundary = boundaries[batch]
            val referenceIds = plants.filter { it.batch == batch && it.reference }.map { it.id }.toSet()
            if (boundary == null || !boundary.confirmed) {
                return@map OffsetEstimate(
                    batch = batch,
                    trait = trait,
                    status = "UNCONFIRMED",
                    referencePlantCount = referenceIds.size,
                    reason = "相机更换边界尚未确认，保留不可识别",
                )
            }
            val pairs = referenceIds.flatMap { plantId ->
                val atBoundary = rows.filter {
                    it.observation.plantId == plantId &&
                        it.observation.day == boundary.boundaryDay - 1 &&
                        it.quality != "bad"
                }
                val old = atBoundary.filter { it.observation.cameraEra == "old" }.map { value(it, trait) }
                val newer = atBoundary.filter { it.observation.cameraEra == "new" }.map { value(it, trait) }
                old.flatMap { oldValue -> newer.map { newValue -> newValue - oldValue } }
            }
            val distinctPlants = referenceIds.count { plantId ->
                rows.any {
                    it.observation.plantId == plantId &&
                        it.observation.day == boundary.boundaryDay - 1 &&
                        it.observation.cameraEra == "old" &&
                        it.quality != "bad"
                } && rows.any {
                    it.observation.plantId == plantId &&
                        it.observation.day == boundary.boundaryDay - 1 &&
                        it.observation.cameraEra == "new" &&
                        it.quality != "bad"
                }
            }
            if (distinctPlants < 2 || pairs.size < 2) {
                OffsetEstimate(
                    batch = batch,
                    trait = trait,
                    status = "NOT_IDENTIFIED",
                    referencePlantCount = distinctPlants,
                    pairCount = pairs.size,
                    reason = "边界前同日仅有少于 2 株重叠参照植株，偏移不可识别",
                )
            } else {
                val mean = pairs.average()
                val se = if (pairs.size == 1) 0.0 else pairs.standardDeviation() / sqrt(pairs.size.toDouble())
                OffsetEstimate(
                    batch = batch,
                    trait = trait,
                    status = "IDENTIFIED",
                    offset = round4(mean),
                    standardError = round4(se),
                    ci95Low = round4(mean - 1.96 * se),
                    ci95High = round4(mean + 1.96 * se),
                    referencePlantCount = distinctPlants,
                    pairCount = pairs.size,
                    reason = "基于同日重叠参照植株的新机减旧机差值",
                )
            }
        }
    }

    private fun fitPlant(plant: Plant, rows: List<EffectiveObservation>, trait: String): PlantCurve {
        val points = rows
            .filter { it.observation.plantId == plant.id && it.quality != "bad" }
            .map { Point(it.observation.day.toDouble(), value(it, trait)) }
            .sortedBy { it.x }
        require(points.size >= 3) { "植株 ${plant.id} 的有效观测少于 3 条，无法拟合 $trait" }
        val candidates = listOf(fitSaturated(points), fitSegmented(points), fitMonotoneSpline(points))
        val selectedName = candidates.minBy { it.selectionScore }.model
        val withSelected = candidates.map { it.copy(selected = it.model == selectedName) }
        val selected = withSelected.first { it.selected }
        val minDay = points.minOf { it.x.toInt() }
        val maxDay = max(points.maxOf { it.x.toInt() }, 14)
        val fitted = (minDay..maxDay).associateWith { day ->
            round4(predict(selected.model, selected.parameters, day.toDouble()))
        }
        return PlantCurve(plant.id, trait, withSelected.size, withSelected, fitted, points.minOf { it.x.toInt() }, points.maxOf { it.x.toInt() })
    }

    private fun fitSaturated(points: List<Point>): CandidateFit {
        var best: CandidateFit? = null
        var bestK = 0.01
        var bestB = 0.0
        var bestA = 1.0
        for (index in 0 until 220) {
            val k = exp(ln(0.015) + (ln(1.2) - ln(0.015)) * index / 219.0)
            val design = points.map { doubleArrayOf(1.0, 1.0 - exp(-k * it.x)) }
            val coefficients = leastSquares(design, points.map { it.y })
            val b = coefficients[0]
            val a = coefficients[1]
            if (a <= 0) continue
            val prediction = points.map { b + a * (1.0 - exp(-k * it.x)) }
            val sse = sse(points, prediction)
            if (best == null || sse < best.sse) {
                bestK = k; bestB = b; bestA = a
                best = candidate("saturated", mapOf("b" to round4(b), "asymptote" to round4(a), "k" to round6(k)), sse, points.size, 3)
            }
        }
        return best ?: candidate("saturated", mapOf("b" to 0.0, "asymptote" to 1.0, "k" to 0.1), 0.0, points.size, 3)
    }

    private fun fitSegmented(points: List<Point>): CandidateFit {
        var best: CandidateFit? = null
        val candidates = points.map { it.x }.distinct().filter { it > points.minOf { p -> p.x } && it < points.maxOf { p -> p.x } }
        for (breakpoint in candidates) {
            val design = points.map {
                doubleArrayOf(1.0, min(it.x, breakpoint), max(0.0, it.x - breakpoint))
            }
            val coefficients = leastSquares(design, points.map { it.y })
            val prediction = design.map { row -> row.indices.sumOf { index -> row[index] * coefficients[index] } }
            val sse = sse(points, prediction)
            val slope1 = coefficients[1]
            val slope2 = coefficients[1] + coefficients[2]
            if (slope1 >= -1e-7 && slope2 >= -1e-7 && (best == null || sse < best.sse)) {
                best = candidate(
                    "segmented_linear",
                    mapOf("intercept" to round4(coefficients[0]), "slope1" to round4(slope1), "slope2" to round4(slope2), "breakpoint" to round4(breakpoint)),
                    sse, points.size, 4
                )
            }
        }
        return best ?: fitLinearPlateau(points)
    }

    private fun fitLinearPlateau(points: List<Point>): CandidateFit {
        val design = points.map { doubleArrayOf(1.0, it.x) }
        val coefficients = leastSquares(design, points.map { it.y })
        val prediction = points.map { coefficients[0] + coefficients[1] * it.x }
        return candidate(
            "segmented_linear",
            mapOf("intercept" to round4(coefficients[0]), "slope1" to round4(max(0.0, coefficients[1])), "slope2" to 0.0, "breakpoint" to round4(points.maxOf { it.x })),
            sse(points, prediction), points.size, 3
        )
    }

    private fun fitMonotoneSpline(points: List<Point>): CandidateFit {
        val minDay = points.minOf { it.x }
        val maxDay = points.maxOf { it.x }
        val knotDays = if (maxDay - minDay >= 10) {
            listOf(minDay, minDay + (maxDay - minDay) * 0.25, minDay + (maxDay - minDay) * 0.5, minDay + (maxDay - minDay) * 0.75, maxDay)
        } else {
            points.map { it.x }.distinct().sorted()
        }
        val grouped = knotDays.distinct().map { knotDay ->
            val near = points.filter {
                if (knotDay == knotDays.first()) it.x <= knotDays[1]
                else if (knotDay == knotDays.last()) it.x >= knotDays[knotDays.lastIndex - 1]
                else it.x >= knotDay - (maxDay - minDay) * 0.125 && it.x <= knotDay + (maxDay - minDay) * 0.125
            }
            Point(knotDay, near.map { it.y }.average())
        }
        val isotonic = isotonicMonotone(grouped.distinctBy { it.x })
        val prediction = points.map { point -> pchip(isotonic, point.x) }
        val sse = sse(points, prediction)
        return candidate(
            "monotone_spline",
            isotonic.associate { "day_${it.x.toInt()}" to round4(it.y) },
            sse, points.size, isotonic.size
        )
    }

    private fun estimateCells(plants: List<Plant>, curves: List<PlantCurve>, targetDay: Int): List<CellStat> {
        val byId = curves.associateBy { it.plantId }
        return Fixture.genotypes().flatMap { genotype ->
            Fixture.treatments().map { treatment ->
                val members = plants.filter { it.genotype == genotype && it.treatment == treatment }
                if (members.isEmpty()) {
                    CellStat(genotype, treatment, emptyList(), 0, designGap = true)
                } else {
                    val selected = members.mapNotNull { byId[it.id] }.map { curve ->
                        curve.candidates.first { it.selected }.let { predict(it.model, it.parameters, targetDay.toDouble()) }
                    }
                    val batches = members.map { it.batch }.distinct().sorted()
                    CellStat(
                        genotype = genotype,
                        treatment = treatment,
                        batches = batches,
                        plantCount = members.size,
                        mean = round4(selected.average()),
                        standardError = round4(if (selected.size < 2) 0.0 else selected.standardDeviation() / sqrt(selected.size.toDouble())),
                        singleBatch = batches.size < 2,
                    )
                }
            }
        }
    }

    private fun estimateInteractions(cells: List<CellStat>): List<InteractionEstimate> {
        val g1 = "G1"; val g2 = "G2"; val control = "C"
        return Fixture.treatments().filter { it != control }.map { treatment ->
            val needed = listOf(g1 to control, g1 to treatment, g2 to control, g2 to treatment).map { (genotype, trt) ->
                cells.first { it.genotype == genotype && it.treatment == trt }
            }
            val missing = needed.filter { it.designGap }
            if (missing.isNotEmpty()) {
                InteractionEstimate(
                    treatment = treatment,
                    control = control,
                    genotypeA = g1,
                    genotypeB = g2,
                    status = "DESIGN_GAP",
                    reason = "缺失设计单元格 ${missing.joinToString(",") { "${it.genotype}×${it.treatment}" }}，不输出互作用点估计",
                )
            } else {
                val (g1c, g1t, g2c, g2t) = needed
                val estimate = (g1t.mean!! - g1c.mean!!) - (g2t.mean!! - g2c.mean!!)
                val se = sqrt(needed.sumOf { (it.standardError ?: 0.0).pow(2) })
                val singleBatch = needed.any { it.singleBatch }
                InteractionEstimate(
                    treatment = treatment,
                    control = control,
                    genotypeA = g1,
                    genotypeB = g2,
                    status = if (singleBatch) "ESTIMATED_SINGLE_BATCH" else "ESTIMATED",
                    estimate = round4(estimate),
                    standardError = round4(se),
                    ci95Low = round4(estimate - 1.96 * se),
                    ci95High = round4(estimate + 1.96 * se),
                    reason = if (singleBatch) "有点估计，但至少一个组合只在单批次出现，批次不可差分" else "四个设计单元格均有重复",
                )
            }
        }
    }

    private fun coverage(
        plants: List<Plant>,
        rows: List<EffectiveObservation>,
        trait: String,
        plantById: Map<String, Plant>,
    ): List<Coverage> = plants.map { it.batch }.distinct().sorted().map { batch ->
        val batchRows = rows.filter { plantById[it.observation.plantId]?.batch == batch }
        Coverage(
            batch = batch,
            trait = trait,
            rawRows = batchRows.size,
            acceptedRows = batchRows.count { it.quality != "bad" },
            rejectedRows = batchRows.count { it.quality == "bad" },
            plants = batchRows.map { it.observation.plantId }.distinct().size,
            minDay = batchRows.map { it.observation.day }.minOrNull(),
            maxDay = batchRows.map { it.observation.day }.maxOrNull(),
        )
    }

    private fun buildNotes(
        offsets: List<OffsetEstimate>,
        cells: List<CellStat>,
        preprocessingVersionId: Int,
    ): List<String> {
        val notes = mutableListOf("分析固定设计版本 1 与预处理版本 $preprocessingVersionId")
        offsets.filter { it.status != "IDENTIFIED" }.forEach { notes += "批次 ${it.batch} 的换机偏移：${it.reason}" }
        cells.filter { it.designGap }.forEach { notes += "设计空缺：${it.genotype}×${it.treatment} 没有植株" }
        cells.filter { it.singleBatch && !it.designGap }.forEach { notes += "组合 ${it.genotype}×${it.treatment} 仅在批次 ${it.batches.joinToString()} 出现" }
        return notes
    }

    private fun candidate(model: String, parameters: Map<String, Double>, sse: Double, n: Int, p: Int): CandidateFit {
        val safeSse = max(sse, 1e-10)
        val aic = n * ln(safeSse / n) + 2.0 * p
        return CandidateFit(model, parameters, round4(sse), round2(aic), round2(aic), false)
    }

    private fun sse(points: List<Point>, predicted: List<Double>): Double =
        points.indices.sumOf { (points[it].y - predicted[it]).pow(2) }

    private fun predict(model: String, parameters: Map<String, Double>, day: Double): Double = when (model) {
        "saturated" -> {
            val b = parameters.getValue("b"); val a = parameters.getValue("asymptote"); val k = parameters.getValue("k")
            b + a * (1.0 - exp(-k * day))
        }
        "segmented_linear" -> {
            val b = parameters.getValue("intercept"); val s1 = parameters.getValue("slope1")
            val s2 = parameters.getValue("slope2"); val bp = parameters.getValue("breakpoint")
            b + s1 * min(day, bp) + s2 * max(0.0, day - bp)
        }
        "monotone_spline" -> {
            val knots = parameters.entries
                .filter { it.key.startsWith("day_") }
                .map { Point(it.key.removePrefix("day_").toDouble(), it.value) }
                .sortedBy { it.x }
            pchip(knots, day)
        }
        else -> error("未知模型 $model")
    }

    private fun pchip(knots: List<Point>, x: Double): Double {
        if (x <= knots.first().x) return knots.first().y
        if (x >= knots.last().x) return knots.last().y
        val index = knots.indexOfLast { it.x <= x }
        if (knots[index].x == x) return knots[index].y
        val left = knots[index]
        val right = knots[index + 1]
        val h = right.x - left.x
        val t = (x - left.x) / h
        val mLeft = if (index == 0) (right.y - left.y) / h else secantSlope(knots[index - 1], left, right)
        val mRight = if (index + 1 == knots.lastIndex) (right.y - left.y) / h else secantSlope(left, right, knots[index + 2])
        val h00 = 2 * t * t * t - 3 * t * t + 1
        val h10 = t * t * t - 2 * t * t + t
        val h01 = -2 * t * t * t + 3 * t * t
        val h11 = t * t * t - t * t
        return h00 * left.y + h10 * h * mLeft + h01 * right.y + h11 * h * mRight
    }

    private fun secantSlope(previous: Point, current: Point, next: Point): Double {
        val left = (current.y - previous.y) / (current.x - previous.x)
        val right = (next.y - current.y) / (next.x - current.x)
        if (left * right <= 0) return 0.0
        return 2.0 * left * right / (left + right)
    }

    private fun isotonicMonotone(points: List<Point>): List<Point> {
        data class Block(var mean: Double, var weight: Double, val indices: MutableList<Int>)
        val blocks = mutableListOf<Block>()
        points.forEachIndexed { index, point ->
            blocks += Block(point.y, 1.0, mutableListOf(index))
            while (blocks.size >= 2 && blocks[blocks.size - 2].mean > blocks[blocks.size - 1].mean) {
                val right = blocks.removeLast()
                val left = blocks.removeLast()
                val mergedWeight = left.weight + right.weight
                val mergedMean = (left.mean * left.weight + right.mean * right.weight) / mergedWeight
                blocks += Block(mergedMean, mergedWeight, (left.indices + right.indices).toMutableList())
            }
        }
        val values = DoubleArray(points.size)
        blocks.forEach { block -> block.indices.forEach { values[it] = block.mean } }
        return points.mapIndexed { index, point -> Point(point.x, values[index]) }
    }

    private fun leastSquares(design: List<DoubleArray>, y: List<Double>): DoubleArray {
        val p = design.first().size
        val xtx = Array(p) { DoubleArray(p) }
        val xty = DoubleArray(p)
        design.forEachIndexed { rowIndex, row ->
            for (i in 0 until p) {
                xty[i] += row[i] * y[rowIndex]
                for (j in 0 until p) xtx[i][j] += row[i] * row[j]
            }
        }
        return solveLinear(xtx, xty)
    }

    private fun solveLinear(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val a = Array(n) { i -> matrix[i] + rhs[i] }
        for (column in 0 until n) {
            val pivot = (column until n).maxBy { abs(a[it][column]) }
            a[column] = a[pivot].also { a[pivot] = a[column] }
            val divisor = a[column][column]
            for (j in column..n) a[column][j] /= divisor
            for (row in 0 until n) if (row != column) {
                val factor = a[row][column]
                for (j in column..n) a[row][j] -= factor * a[column][j]
            }
        }
        return DoubleArray(n) { a[it][n] }
    }

    private fun value(row: EffectiveObservation, trait: String): Double = when (trait) {
        TRAIT_LEAF_AREA -> row.adjustedLeafArea
        TRAIT_HEIGHT -> row.adjustedHeight
        TRAIT_CANOPY_WIDTH -> row.adjustedCanopyWidth
        else -> error("未知性状")
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
    private fun round4(value: Double): Double = round(value * 10000.0) / 10000.0
    private fun round6(value: Double): Double = round(value * 1000000.0) / 1000000.0

    private data class Point(val x: Double, val y: Double)
    private fun List<Double>.standardDeviation(): Double {
        val mean = average()
        return sqrt(sumOf { (it - mean).pow(2) } / (size - 1.0))
    }
}
