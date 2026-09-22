package app

import kotlin.math.sqrt

object Offsets {

    private fun weight(quality: String): Double = when (quality) {
        "OK" -> 1.0
        "SUSPECT" -> 0.5
        else -> 0.0
    }

    /**
     * 相机偏移：仅使用在重叠时段被新旧相机重复拍摄的参照植株。
     * 同时点配对差的均值；没有可用配对则不可识别。
     */
    fun camera(
        observations: List<Observation>,
        plants: Map<String, Plant>,
        fixture: Fixture
    ): List<OffsetEstimate> {
        return fixture.traits.map { trait ->
            if (!trait.imaging) {
                return@map OffsetEstimate(
                    trait.id, false, null, null, 0,
                    "非影像性状（${trait.label}）不存在相机读数，偏移不适用", emptyList()
                )
            }
            val rows = observations.filter { it.traitId == trait.id }
            val pairs = mutableListOf<OffsetPair>()
            for (day in fixture.cameraOverlapDays) {
                for (p in plants.values.filter { it.reference }) {
                    val old = rows.filter {
                        it.plantId == p.plantId && it.day == day && it.camera == "OLD" && weight(it.quality) > 0
                    }
                    val new = rows.filter {
                        it.plantId == p.plantId && it.day == day && it.camera == "NEW" && weight(it.quality) > 0
                    }
                    if (old.isNotEmpty() && new.isNotEmpty()) {
                        val oldVal = old.weightedValue()
                        val newVal = new.weightedValue()
                        pairs += OffsetPair(
                            p.plantId, p.code, day, oldVal, newVal,
                            Generator.round2(newVal - oldVal)
                        )
                    }
                }
            }
            if (pairs.isEmpty()) {
                OffsetEstimate(
                    trait.id, false, null, null, 0,
                    "重叠时段（第${fixture.cameraOverlapDays.joinToString("/")}天）没有重叠参照植株读数，偏移不可识别",
                    emptyList()
                )
            } else {
                val mean = pairs.map { it.delta }.average()
                val sd = if (pairs.size > 1) sqrt(
                    pairs.sumOf { (it.delta - mean) * (it.delta - mean) } / (pairs.size - 1).toDouble()
                ) else 0.0
                OffsetEstimate(
                    trait.id, true, Generator.round2(mean),
                    Generator.round2(sd / sqrt(pairs.size.toDouble())),
                    pairs.size, "基于重叠参照植株的新旧相机同时点配对差", pairs
                )
            }
        }
    }

    private fun List<Observation>.weightedValue(): Double {
        var sw = 0.0; var s = 0.0
        for (o in this) { val w = weight(o.quality); sw += w; s += w * o.rawValue }
        return s / sw
    }

    /**
     * 批次（盘位置）偏移：以 B1 为锚点，在换机前旧相机窗口内，对每个共有
     * 基因型×处理组合按同一天取批次均值差，再跨天/组合平均，控制生长进程。
     */
    fun batch(
        observations: List<Observation>,
        plants: List<Plant>,
        fixture: Fixture
    ): List<BatchOffsetEstimate> {
        return fixture.traits.map { trait ->
            val before = fixture.days.filter { it <= fixture.cameraSwitchDay }
            val anchor = fixture.batches.first().id
            val other = fixture.batches.drop(1).map { it.id }.firstOrNull()
            if (other == null) {
                return@map BatchOffsetEstimate(trait.id, anchor, false, null, null, 0, "只有一个批次", emptyList())
            }
            val ids = plants.groupBy { Triple(it.genotype, it.treatment, it.batch) }
                .mapValues { e -> e.value.map { it.plantId }.toSet() }
            val shared = fixture.cells.filter { anchor in it.batches && other in it.batches }
            val deltas = mutableListOf<CellDelta>()
            val allDiff = mutableListOf<Double>()
            for (cell in shared) {
                val idA = ids[Triple(cell.genotype, cell.treatment, anchor)].orEmpty()
                val idB = ids[Triple(cell.genotype, cell.treatment, other)].orEmpty()
                val perDay = mutableListOf<Double>()
                for (day in before) {
                    val va = meanOnDay(observations, idA, trait.id, day, oldCameraOnly = trait.imaging)
                    val vb = meanOnDay(observations, idB, trait.id, day, oldCameraOnly = trait.imaging)
                    if (va != null && vb != null) {
                        perDay += vb - va
                        allDiff += vb - va
                    }
                }
                if (perDay.isNotEmpty()) {
                    deltas += CellDelta(cell.genotype, cell.treatment, Generator.round2(perDay.average()))
                }
            }
            if (allDiff.isEmpty()) {
                BatchOffsetEstimate(
                    trait.id, anchor, false, null, null, 0,
                    "换机前窗口没有跨批次共有的基因型×处理组合，批次偏移不可识别", emptyList()
                )
            } else {
                val mean = allDiff.average()
                val sd = if (allDiff.size > 1)
                    sqrt(allDiff.sumOf { (it - mean) * (it - mean) } / (allDiff.size - 1).toDouble()) else 0.0
                BatchOffsetEstimate(
                    trait.id, anchor, true, Generator.round2(mean),
                    Generator.round2(sd / sqrt(allDiff.size.toDouble())),
                    deltas.size, "锚点 $anchor，换机前旧相机窗口按天匹配共有组合", deltas
                )
            }
        }
    }

    private fun meanOnDay(
        observations: List<Observation>, ids: Set<String>,
        traitId: String, day: Int, oldCameraOnly: Boolean
    ): Double? {
        var sw = 0.0; var s = 0.0
        for (o in observations) {
            if (o.plantId !in ids || o.traitId != traitId || o.day != day) continue
            if (oldCameraOnly && o.camera != "OLD") continue
            val w = weight(o.quality)
            if (w <= 0) continue
            sw += w; s += w * o.rawValue
        }
        return if (sw > 0) s / sw else null
    }
}
