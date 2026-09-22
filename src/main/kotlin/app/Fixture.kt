package app

import kotlin.math.exp
import kotlin.math.round
import kotlin.random.Random

object Fixture {
    const val DESIGN_VERSION = 1
    const val BOUNDARY_DAY = 8
    private const val INITIAL_PREPROCESS_VERSION = 1

    private val batches = listOf("B1", "B2")
    private val genotypes = listOf("G1", "G2")
    private val treatments = listOf("C", "D", "W")
    private val treatmentNames = mapOf("C" to "对照", "D" to "干旱", "W" to "高水")

    fun plants(): List<Plant> {
        val result = mutableListOf<Plant>()
        fun add(batch: String, genotype: String, treatment: String, replicate: Int, reference: Boolean, position: Int) {
            result += Plant(
                id = "$batch-$genotype-$treatment-%02d".format(replicate),
                batch = batch,
                position = "%s-%02d".format(batch, position),
                genotype = genotype,
                treatment = treatment,
                reference = reference,
            )
        }
        for (batch in batches) {
            var position = 1
            for (genotype in genotypes) {
                for (treatment in listOf("C", "D")) {
                    for (replicate in 1..2) {
                        add(batch, genotype, treatment, replicate, replicate == 1 && treatment == "C", position++)
                    }
                }
            }
        }
        add("B1", "G1", "W", 1, false, 17)
        add("B1", "G1", "W", 2, false, 18)
        return result.sortedBy { it.id }
    }

    fun observations(plants: List<Plant>): List<Observation> {
        val days = listOf(0, 2, 4, 7, 8, 10, 12, 14)
        val result = mutableListOf<Observation>()
        for (plant in plants) {
            val random = Random(plant.id.hashCode().toLong().let { if (it < 0) -it else it })
            for (day in days) {
                val era = if (day < BOUNDARY_DAY) "old" else "new"
                result += observation(plant, day, "R1", era, trueValue(plant, day), random, false)
            }
            val duplicateBase = result.first { it.plantId == plant.id && it.day == 4 }
            result += duplicateBase.copy(
                id = "${plant.id}-04-R2-old",
                replicate = "R2",
                leafArea = round3(duplicateBase.leafArea * 1.025),
                height = round2(duplicateBase.height + 0.8),
                canopyWidth = round2(duplicateBase.canopyWidth + 0.5),
                rawQuality = if (plant.id == "B1-G1-C-01") "bad" else "questionable",
            )
            if (plant.reference) {
                val regularOld = result.first { it.plantId == plant.id && it.day == 7 && it.replicate == "R1" }
                result += regularOld.copy(
                    id = "${plant.id}-07-REF_NEW-new",
                    replicate = "REF_NEW",
                    cameraEra = "new",
                    leafArea = round3(regularOld.leafArea + cameraShift.first + if (plant.genotype == "G1") -0.4 else 0.4),
                    height = round2(regularOld.height + cameraShift.second + if (plant.genotype == "G1") -0.2 else 0.2),
                    canopyWidth = round2(regularOld.canopyWidth + cameraShift.third + if (plant.genotype == "G1") -0.15 else 0.15),
                    rawQuality = "good",
                )
            }
        }
        return result.sortedWith(compareBy({ it.plantId }, { it.day }, { it.replicate }))
    }

    private fun observation(
        plant: Plant,
        day: Int,
        replicate: String,
        era: String,
        base: Triple<Double, Double, Double>,
        random: Random,
        overlap: Boolean,
    ): Observation {
        val replicateJitter = if (replicate == "R1") 1.0 else 1.0 + random.nextDouble(-0.018, 0.018)
        val cameraShift = if (era == "new") cameraShift else DoubleTriple(0.0, 0.0, 0.0)
        val dayCode = "%02d".format(day)
        return Observation(
            id = "${plant.id}-$dayCode-$replicate-$era",
            plantId = plant.id,
            day = day,
            replicate = replicate,
            cameraEra = era,
            leafArea = round3(base.first * replicateJitter + cameraShift.first),
            height = round2(base.second * replicateJitter + cameraShift.second),
            canopyWidth = round2(base.third * replicateJitter + cameraShift.third),
            rawQuality = "good",
        )
    }

    private data class DoubleTriple(val first: Double, val second: Double, val third: Double)

    private val cameraShift = DoubleTriple(12.0, 6.0, 5.0)

    private fun trueValue(plant: Plant, day: Int): Triple<Double, Double, Double> {
        val individual = listOf(0.96, 1.04)[plant.id.takeLast(2).toIntOrNull()?.minus(1)?.coerceIn(0, 1) ?: 0]
        fun asymptote(metric: String): Double {
            val base = when (metric) {
                TRAIT_LEAF_AREA -> if (plant.genotype == "G1") 104.0 else 121.0
                TRAIT_HEIGHT -> if (plant.genotype == "G1") 42.0 else 51.0
                else -> if (plant.genotype == "G1") 34.0 else 40.0
            }
            val factor = when (plant.treatment) {
                "D" -> if (metric == TRAIT_LEAF_AREA) 0.72 else if (metric == TRAIT_HEIGHT) 0.82 else 0.78
                "W" -> if (metric == TRAIT_LEAF_AREA) 1.13 else if (metric == TRAIT_HEIGHT) 1.07 else 1.06
                else -> 1.0
            }
            return base * factor * individual
        }
        fun rate(metric: String) = when (metric) {
            TRAIT_LEAF_AREA -> if (plant.genotype == "G1") 0.185 else 0.205
            TRAIT_HEIGHT -> if (plant.genotype == "G1") 0.16 else 0.175
            else -> if (plant.genotype == "G1") 0.19 else 0.21
        }
        fun metric(metric: String): Double {
            val a = asymptote(metric)
            val k = rate(metric) * (if (plant.treatment == "D") 0.94 else 1.0)
            return a * (1.0 - exp(-k * (day + 0.25)))
        }
        return Triple(round3(metric(TRAIT_LEAF_AREA)), round2(metric(TRAIT_HEIGHT)), round2(metric(TRAIT_CANOPY_WIDTH)))
    }

    private fun round3(value: Double) = round(value * 1000.0) / 1000.0
    private fun round2(value: Double) = round(value * 100.0) / 100.0

    fun boundaries(): List<Boundary> = batches.map {
        Boundary(it, BOUNDARY_DAY, confirmed = false, confirmedAt = null)
    }

    fun treatmentName(treatment: String): String = treatmentNames[treatment] ?: treatment
    fun genotypes(): List<String> = genotypes
    fun treatments(): List<String> = treatments
    const val initialPreprocessingVersion: Int = INITIAL_PREPROCESS_VERSION
}
