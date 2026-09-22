package app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

object Engine {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private const val HORIZON = 32

    private fun weight(quality: String): Double = when (quality) {
        "OK" -> 1.0
        "SUSPECT" -> 0.5
        else -> 0.0
    }

    fun versions(db: Database, fixture: Fixture, fixtureText: String): Versions {
        val designHash = Database.sha256(
            fixture.cells.joinToString("|") { "${it.genotype}:${it.treatment}:${it.batches.joinToString(",")}" } +
                "|${fixture.batches.joinToString(",") { it.id }}"
        ).take(12)
        val fixtureHash = Database.sha256(fixtureText).take(12)
        val corrections = db.corrections()
        val boundary = db.boundary(fixture.cameraSwitchDay)
        val preBase = listOf(
            "obs=${db.observations().joinToString("/") { "${it.observationId}:${it.quality}" }}",
            "corr=${corrections.joinToString("/") { "${it.observationId}:${it.newQuality}" }}",
            "boundary=${boundary.switchDay}:${boundary.confirmed}"
        ).joinToString("#")
        return Versions(
            designVersion = "${fixture.fixtureVersion}-d-$designHash",
            fixtureVersion = "${fixture.fixtureVersion}-f-$fixtureHash",
            preprocessVersion = "p-${Database.sha256(preBase).take(12)}",
            cameraBoundaryConfirmed = boundary.confirmed,
            cameraBoundaryDay = boundary.switchDay
        )
    }

    fun analyze(
        db: Database,
        fixture: Fixture,
        label: String,
        runId: Long? = null
    ): AnalysisResult {
        val fixtureText = db.getMeta("fixture_text")
            ?: error("fixture text missing")
        val versions = versions(db, fixture, fixtureText)
        val plants = db.plants()
        val plantMap = plants.associateBy { it.plantId }
        val observations = db.observations()
        val warnings = mutableListOf<String>()

        val cameraEst = Offsets.camera(observations, plantMap, fixture)
        val batchEst = Offsets.batch(observations, plants, fixture)
        if (!versions.cameraBoundaryConfirmed) {
            warnings += "相机更换边界尚未确认：当前第${versions.cameraBoundaryDay}天为预置值，相机偏移不参与校正。"
        }
        for (e in cameraEst) {
            if (!e.identifiable && e.nPairs > 0) warnings += "性状 ${e.traitId}：${e.reason}"
            if (!e.identifiable && e.nPairs == 0 && e.reason.startsWith("重叠时段"))
                warnings += "性状 ${e.traitId}：${e.reason}"
        }
        for (e in batchEst) if (!e.identifiable) warnings += "性状 ${e.traitId}：${e.reason}"

        val camApply = if (versions.cameraBoundaryConfirmed)
            cameraEst.filter { it.identifiable }.associate { it.traitId to (it.estimate ?: 0.0) }
        else emptyMap()
        val batchApply = batchEst.filter { it.identifiable }
            .associate { it.traitId to (it.estimate ?: 0.0) }
        if (!versions.cameraBoundaryConfirmed) warnings +=
            "外推与个体曲线使用原始读数加批次校正（如可识别）；确认换机边界后再纳入相机偏移。"

        val coverage = coverage(plants, observations, fixture)
        val plantFits = plants.map { p -> fitPlant(p, observations, fixture, camApply, batchApply) }
        val cells = cellSummaries(fixture, plants, plantFits)
        val missing = missingCells(fixture)
        val effects = effectsTable(fixture, cells)

        return AnalysisResult(
            meta = AnalysisMeta(
                runId = runId,
                label = label,
                createdAt = Instant.now().toString(),
                designVersion = versions.designVersion,
                fixtureVersion = versions.fixtureVersion,
                preprocessVersion = versions.preprocessVersion,
                cameraBoundaryConfirmed = versions.cameraBoundaryConfirmed,
                cameraBoundaryDay = versions.cameraBoundaryDay,
                projectionHorizonDays = HORIZON
            ),
            cameraOffsets = cameraEst,
            batchOffsets = batchEst,
            cells = cells,
            missingCells = missing,
            effects = effects,
            coverage = coverage,
            plants = plantFits,
            warnings = warnings
        )
    }

    private fun coverage(
        plants: List<Plant>, obs: List<Observation>, fixture: Fixture
    ): List<CoverageRow> {
        val rows = mutableListOf<CoverageRow>()
        for (p in plants) for (t in fixture.traits) {
            val mine = obs.filter { it.plantId == p.plantId && it.traitId == t.id }
            if (mine.isEmpty()) continue
            rows += CoverageRow(
                plantId = p.plantId, code = p.code, genotype = p.genotype,
                treatment = p.treatment, batch = p.batch, reference = p.reference,
                traitId = t.id,
                nTotal = mine.size,
                nOk = mine.count { it.quality == "OK" },
                nSuspect = mine.count { it.quality == "SUSPECT" },
                nExcluded = mine.count { it.quality == "EXCLUDED" },
                minDay = mine.minOf { it.day },
                maxDay = mine.maxOf { it.day },
                nDays = mine.map { it.day }.distinct().size
            )
        }
        return rows
    }

    private fun adjusted(
        o: Observation,
        camApply: Map<String, Double>,
        batchApply: Map<String, Map<String, Double>>,
        batch: String,
        anchor: String
    ): Double {
        var v = o.rawValue
        if (o.camera == "NEW") camApply[o.traitId]?.let { v -= it }
        if (batch != anchor) batchApply[o.traitId]?.get(batch)?.let { v -= it }
        return v
    }

    private fun fitPlant(
        p: Plant, observations: List<Observation>, fixture: Fixture,
        camApply: Map<String, Double>, batchApplyFlat: Map<String, Double>
    ): PlantFit {
        val anchor = fixture.batches.first().id
        val otherBatch = fixture.batches.drop(1).firstOrNull()?.id
        val batchApply: Map<String, Map<String, Double>> = if (otherBatch == null) emptyMap()
        else fixture.traits.associate { t ->
            t.id to mapOf(otherBatch to (batchApplyFlat[t.id] ?: 0.0))
        }
        val traitFits = fixture.traits.map { trait ->
            val mine = observations.filter { it.plantId == p.plantId && it.traitId == trait.id }
            if (mine.isEmpty()) {
                return@map TraitFit(
                    trait.id, trait.label, trait.unit, false,
                    "该个体没有 ${trait.label} 观测（稀疏抽样）", null, null, null, emptyList()
                )
            }
            val eligible = mine.filter { weight(it.quality) > 0 }
            if (eligible.size < 4) {
                return@map TraitFit(
                    trait.id, trait.label, trait.unit, false,
                    "可用观测仅 ${eligible.size} 条（含质量修正后），不足以拟合曲线", null, null, null, emptyList()
                )
            }
            // 同一时点的重复观测保留为独立点，不先平均；SUSPECT 降权。
            val pts = eligible.map {
                Pt(
                    it.day.toDouble(),
                    adjusted(it, camApply, batchApply, p.batch, anchor),
                    weight(it.quality)
                )
            }
            val rawPts = eligible.map { Pt(it.day.toDouble(), it.rawValue, weight(it.quality)) }
            val sat = Fit.fitSaturated(pts)
            val piece = Fit.fitPiecewise(pts)
            val (isoSse, levels) = Fit.isotonicSse(pts)

            val satModel = buildModel(
                "saturated", "饱和生长（单分子）",
                mapOf("base" to round(sat.base), "asymptote" to round(sat.asym), "rate" to round(sat.rate)),
                sat.sse, 3, eligible.size
            ) { x -> sat.base + sat.asym * (1.0 - exp(-sat.rate * x)) }
            val pieceModel = buildModel(
                "piecewise", "分段线性（单断点）",
                mapOf(
                    "intercept" to round(piece.intercept), "slope1" to round(piece.slope1),
                    "knot" to round(piece.knot), "slope2" to round(piece.slope2)
                ),
                piece.sse, 4, eligible.size
            ) { x ->
                if (x <= piece.knot) piece.intercept + piece.slope1 * x
                else piece.intercept + piece.slope1 * piece.knot + piece.slope2 * (x - piece.knot)
            }
            fun isoPred(x: Double): Double {
                if (x <= levels.first().x) return levels.first().y
                for (k in 1 until levels.size) if (x <= levels[k].x) return levels[k].y
                return levels.last().y
            }
            val isoDf = levels.size.coerceAtMost((eligible.size - 2).coerceAtLeast(1))
            val isoModel = buildModel(
                "monotone_spline", "单调样条（PAVA 阶梯）",
                mapOf("levels" to levels.size.toDouble(), "df" to isoDf.toDouble()),
                isoSse, isoDf, eligible.size
            ) { isoPred(it) }

            val models = listOf(satModel, pieceModel, isoModel)
            val recommended = models.minByOrNull { it.bic }!!.model
            val rawAuc = auc(rawPts)
            val adjAuc = auc(pts)
            TraitFit(
                trait.id, trait.label, trait.unit, true,
                "基于 ${eligible.size} 条保留观测（含 ${eligible.count { it.quality == "SUSPECT" }} 条疑点降权）",
                recommended, round(rawAuc), round(adjAuc), models
            )
        }
        return PlantFit(
            p.plantId, p.code, p.genotype, p.treatment, p.batch, p.reference, traitFits
        )
    }

    private fun round(x: Double): Double = Generator.round2(x)

    private fun buildModel(
        model: String, label: String, params: Map<String, Double>,
        sse: Double, k: Int, n: Int, pred: (Double) -> Double
    ): ModelFit {
        val sigma = max(sqrt(sse / n.coerceAtLeast(1)), 1e-3)
        val points = (0..HORIZON step 2).map { day ->
            FitPoint(
                day = day,
                value = round(pred(day.toDouble())),
                se = round(sigma * uncertaintyFactor(day)),
                extrapolation = day > 28
            )
        }
        return ModelFit(
            model = model, label = label, params = params,
            rmse = round(Fit.rmse(sse, n)),
            bic = round(Fit.bic(sse, n, k)),
            monotonic = model != "piecewise" || isMonotone(params),
            nPoints = n,
            curve = points
        )
    }

    private fun isMonotone(params: Map<String, Double>): Boolean =
        (params["slope1"] ?: 0.0) >= -1e-6 && (params["slope2"] ?: 0.0) >= -1e-6

    private fun uncertaintyFactor(day: Int): Double =
        if (day <= 28) 1.0 else 1.0 + (day - 28) / 8.0

    private fun auc(points: List<Pt>): Double {
        val byDay = points.groupBy { it.x }
            .mapValues { (_, ps) -> ps.sumOf { it.w * it.y } / ps.sumOf { it.w } }
            .toSortedMap()
        val xs = byDay.keys.toList()
        var area = 0.0
        for (i in 0 until xs.size - 1) {
            area += (byDay[xs[i + 1]]!! + byDay[xs[i]]!!) / 2.0 * (xs[i + 1] - xs[i])
        }
        return area / 28.0
    }

    private fun cellSummaries(
        fixture: Fixture,
        plants: List<Plant>,
        fits: List<PlantFit>
    ): List<CellSummary> {
        val fitById = fits.associateBy { it.plantId }
        return fixture.cells.map { cell ->
            val members = plants.filter { it.genotype == cell.genotype && it.treatment == cell.treatment }
            val batches = members.map { it.batch }.distinct().sorted()
            val (status, reason) = when {
                batches.size < 2 -> "single_batch" to
                    "该基因型×处理组合仅出现在 ${batches.joinToString()}，无法与批次效应分离"
                else -> "cross_batch" to "跨 ${batches.joinToString()} 平衡"
            }
            val auc = fixture.traits.associate { t ->
                val vals = members.mapNotNull { m ->
                    fitById[m.plantId]?.traits?.firstOrNull { it.traitId == t.id }?.adjustedAuc
                }
                val mean = if (vals.isNotEmpty()) round(vals.average()) else null
                val se = if (vals.size > 1) {
                    val m = vals.average()
                    round(sqrt(vals.sumOf { (it - m) * (it - m) } / (vals.size * (vals.size - 1))))
                } else null
                t.id to CellAuc(
                    meanRawAuc = members.mapNotNull { m ->
                        fitById[m.plantId]?.traits?.firstOrNull { it.traitId == t.id }?.rawAuc
                    }.takeIf { it.isNotEmpty() }?.let { round(it.average()) },
                    meanAdjustedAuc = mean,
                    se = se,
                    nPlants = vals.size
                )
            }
            CellSummary(
                cell.genotype, cell.treatment, batches, status, reason,
                members.map { it.plantId }, auc
            )
        }
    }

    private fun missingCells(fixture: Fixture): List<DesignGap> {
        val gaps = mutableListOf<DesignGap>()
        for (g in fixture.genotypes) for (t in fixture.treatments) {
            val cell = fixture.cells.firstOrNull { it.genotype == g.id && it.treatment == t.id }
            if (cell == null) {
                gaps += DesignGap(g.id, t.id, "设计空缺：${g.label} × ${t.label} 完全没有观测，不产生点估计")
            }
        }
        return gaps
    }

    private fun cellMap(cells: List<CellSummary>) =
        cells.associateBy { it.genotype to it.treatment }

    private fun effectsTable(fixture: Fixture, cells: List<CellSummary>): List<EffectEntry> {
        val map = cellMap(cells)
        val out = mutableListOf<EffectEntry>()
        for (trait in fixture.traits) {
            // 处理效应：每个基因型 STRESS - CTRL。
            for (g in fixture.genotypes) {
                val key = "effect:${g.id}:CTRL->STRESS"
                val c = map[g.id to "CTRL"]; val s = map[g.id to "STRESS"]
                if (c == null || s == null) {
                    out += EffectEntry(key, "treatment", g.id, "STRESS", trait.id, null, null,
                        "design_gap", "基因型 $g 的对照或胁迫组合缺失，处理效应不可估计（设计空缺）")
                    continue
                }
                val ca = c.aucByTrait[trait.id]?.meanAdjustedAuc
                val sa = s.aucByTrait[trait.id]?.meanAdjustedAuc
                if (ca == null || sa == null) {
                    out += EffectEntry(key, "treatment", g.id, "STRESS", trait.id, null, null,
                        "insufficient_data", "性状 ${trait.label} 的拟合 AUC 不足")
                    continue
                }
                val se = combineSe(c.aucByTrait[trait.id]?.se, s.aucByTrait[trait.id]?.se)
                val note = if (c.status == "single_batch" || s.status == "single_batch")
                    "single_batch_confounded" else "ok"
                out += EffectEntry(
                    key, "treatment", g.id, "STRESS", trait.id,
                    round(sa - ca), se, note,
                    if (note == "ok") "胁迫相对对照的平均 AUC 差" else "至少一个组合仅单批次，处理效应与批次混淆，仅作描述"
                )
            }
            // 基因型×处理互作用（双差分），以 G1 为参考基因型。
            val reference = fixture.genotypes.first().id
            for (g in fixture.genotypes.drop(1)) {
                val key = "interaction:${g.id}-vs-${reference}"
                val rc = map[reference to "CTRL"]; val rs = map[reference to "STRESS"]
                val gc = map[g.id to "CTRL"]; val gs = map[g.id to "STRESS"]
                val needed = listOf(rc, rs, gc, gs)
                if (needed.any { it == null }) {
                    val missing = buildList {
                        if (rc == null) add("$reference/CTRL"); if (rs == null) add("$reference/STRESS")
                        if (gc == null) add("${g.id}/CTRL"); if (gs == null) add("${g.id}/STRESS")
                    }
                    out += EffectEntry(key, "interaction", g.id, null, trait.id, null, null,
                        "design_gap", "互作用需要 2×2 完整设计，缺失：${missing.joinToString()}，不输出点估计")
                    continue
                }
                val vals = needed.map { it!!.aucByTrait[trait.id]?.meanAdjustedAuc }
                if (vals.any { it == null }) {
                    out += EffectEntry(key, "interaction", g.id, null, trait.id, null, null,
                        "insufficient_data", "性状 ${trait.label} 的 AUC 不足")
                    continue
                }
                val (a, b, cc, d) = listOf(vals[0]!!, vals[1]!!, vals[2]!!, vals[3]!!)
                val se = combineSe(
                    combineSe(rc!!.aucByTrait[trait.id]?.se, rs!!.aucByTrait[trait.id]?.se),
                    combineSe(gc!!.aucByTrait[trait.id]?.se, gs!!.aucByTrait[trait.id]?.se)
                )
                val confounded = listOf(rc, rs, gc, gs).any { it!!.status == "single_batch" }
                out += EffectEntry(
                    key, "interaction", g.id, null, trait.id,
                    round((d - cc) - (b - a)), se,
                    if (confounded) "single_batch_confounded" else "ok",
                    if (confounded) "部分组合仅单批次，互作用与批次混淆，仅作描述"
                    else "双差分 AUC：(${g.id} 胁迫-对照)-($reference 胁迫-对照)"
                )
            }
        }
        return out
    }

    private fun combineSe(a: Double?, b: Double?): Double? {
        if (a == null && b == null) return null
        val va = (a ?: 0.0) * (a ?: 0.0)
        val vb = (b ?: 0.0) * (b ?: 0.0)
        return round(sqrt(va + vb))
    }

    fun toJson(result: AnalysisResult): String = json.encodeToString(result)
}
