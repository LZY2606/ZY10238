package app

import java.sql.Connection
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** 确定性 LCG，避免依赖 java.util.Random 跨版本差异，保证 fixture 重放逐位一致。 */
class Rng(seed: Long) {
    private var state: Long = seed
    fun nextDouble(): Double {
        state = (state * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
        return state.toDouble() / (Long.MAX_VALUE.toDouble() + 1.0)
    }
    fun gaussian(): Double {
        val u = nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
        val v = nextDouble()
        return sqrt(-2.0 * ln(u)) * cos2pi(v)
    }
    private fun cos2pi(v: Double): Double = kotlin.math.cos(2.0 * Math.PI * v)
}

object Generator {

    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    fun generate(conn: Connection, fixture: Fixture) {
        val rng = Rng(fixture.seed)
        val plants = mutableListOf<Plant>()
        var idx = 0
        for (cell in fixture.cells) {
            for (batchId in cell.batches) {
                for (k in 0 until cell.plantsPerBatch) {
                    plants += Plant(
                        "plant_${idx + 1}", "P-%03d".format(idx + 1), k,
                        cell.genotype, cell.treatment, batchId, false
                    )
                    idx++
                }
            }
        }
        for (ref in fixture.references) {
            plants += Plant(
                plantId = "ref_${ref.plantCode.lowercase()}",
                code = ref.plantCode,
                ordinal = -1,
                genotype = ref.genotype,
                treatment = ref.treatment,
                batch = ref.batch,
                reference = true
            )
        }

        conn.prepareStatement(
            """INSERT INTO plants(plant_id, code, ordinal, genotype, treatment, batch, reference_flag)
               VALUES (?,?,?,?,?,?,?)"""
        ).use { ps ->
            for (p in plants) {
                ps.setString(1, p.plantId)
                ps.setString(2, p.code)
                ps.setInt(3, p.ordinal)
                ps.setString(4, p.genotype)
                ps.setString(5, p.treatment)
                ps.setString(6, p.batch)
                ps.setInt(7, if (p.reference) 1 else 0)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        // 每株植物 × 性状的随机效应，按 plants 与 traits 的固定顺序抽取。
        val plantEffects: Map<Pair<String, String>, Double> = plants.flatMap { p ->
            fixture.traits.map { t -> (p.plantId to t.id) to rng.gaussian() * t.plantSigma }
        }.toMap()

        val obsPs = conn.prepareStatement(
            """INSERT INTO observations(plant_id, trait_id, day, raw_value, quality, camera, replicate)
               VALUES (?,?,?,?,?,?,?)"""
        )

        fun insert(
            p: Plant, t: TraitSpec, day: Int, value: Double,
            quality: String, camera: String, replicate: Int
        ) {
            obsPs.setString(1, p.plantId)
            obsPs.setString(2, t.id)
            obsPs.setInt(3, day)
            obsPs.setDouble(4, round2(value))
            obsPs.setString(5, quality)
            obsPs.setString(6, camera)
            obsPs.setInt(7, replicate)
            obsPs.executeUpdate()
        }

        for (p in plants) {
            for (t in fixture.traits) {
                val measureDays = if (t.imaging) fixture.days else massDaysFor(p, fixture)
                for (day in measureDays) {
                    val oldValue = rawValue(p, t, day, plantEffects, fixture, "OLD", rng)
                    if (t.imaging && p.reference && day in fixture.cameraOverlapDays) {
                        insert(p, t, day, oldValue, "OK", "OLD", 0)
                        val newValue = rawValue(p, t, day, plantEffects, fixture, "NEW", rng)
                        insert(p, t, day, newValue, "OK", "NEW", 0)
                    } else if (t.imaging && day > fixture.cameraSwitchDay) {
                        val newValue = rawValue(p, t, day, plantEffects, fixture, "NEW", rng)
                        insert(p, t, day, newValue, "OK", "NEW", 0)
                    } else {
                        insert(p, t, day, oldValue, "OK", if (t.imaging) "OLD" else "NA", 0)
                    }
                }
            }
        }

        // 注入：同一时点的重复影像（质量差异，禁止先平均）与人工疑点。
        for (inj in fixture.injections) {
            val target = plants.find { it.code == inj.plantCode }
                ?: plants[inj.plantIndex ?: error("injection needs target")]
            val t = fixture.traits.first { it.id == inj.trait }
            when (inj.kind) {
                "duplicate" -> {
                    val camera = if (inj.day > fixture.cameraSwitchDay) "NEW" else "OLD"
                    val base = rawValue(target, t, inj.day, plantEffects, fixture, camera, rng)
                    insert(target, t, inj.day, base + (inj.repeatOffset ?: 0.0),
                        inj.repeatQuality ?: "SUSPECT", camera, 1)
                }
                "outlier" -> {
                    val existing = findObservation(conn, target.plantId, t.id, inj.day, 0)
                    if (existing != null) {
                        val v = existing.rawValue + (inj.offset ?: 0.0)
                        updateObservation(conn, existing.observationId, v, inj.quality ?: "SUSPECT")
                    }
                }
            }
        }
        obsPs.close()
    }

    private fun massDaysFor(p: Plant, fixture: Fixture): List<Int> {
        if (p.reference || p.ordinal < 0) return emptyList()
        val step = maxOf(1, fixture.massEveryNthPlant)
        return if ((p.ordinal - fixture.massFirstIndex).mod(step) == 0)
            fixture.massDays else emptyList()
    }

    private fun rawValue(
        p: Plant, t: TraitSpec, day: Int,
        effects: Map<Pair<String, String>, Double>,
        fixture: Fixture, camera: String, rng: Rng
    ): Double {
        val asym0 = t.asymptote[p.genotype] ?: error("asymptote ${p.genotype}")
        val rate0 = t.rate[p.genotype] ?: error("rate ${p.genotype}")
        val stress = if (p.treatment == "STRESS") t.stressAsymFactor else 1.0
        val asym = asym0 * stress
        val growth = asym * (1.0 - exp(-rate0 * day))
        val batch = fixture.batches.first { it.id == p.batch }
        val camOffset = if (t.imaging && camera == "NEW") t.cameraOffset else 0.0
        val noise = rng.gaussian() * t.noiseSigma
        return t.base + growth + batch.trayOffset + camOffset +
            (effects[p.plantId to t.id] ?: 0.0) + noise
    }

    data class ObsRow(
        val observationId: Long, val plantId: String, val day: Int,
        val rawValue: Double, val replicate: Int
    )

    private fun findObservation(
        conn: Connection, plantId: String, traitId: String, day: Int, replicate: Int
    ): ObsRow? {
        conn.prepareStatement(
            """SELECT observation_id, plant_id, day, raw_value, replicate
               FROM observations WHERE plant_id=? AND trait_id=? AND day=? AND replicate=?"""
        ).use { ps ->
            ps.setString(1, plantId); ps.setString(2, traitId)
            ps.setInt(3, day); ps.setInt(4, replicate)
            ps.executeQuery().use { rs ->
                if (rs.next()) return ObsRow(
                    rs.getLong(1), rs.getString(2), rs.getInt(3),
                    rs.getDouble(4), rs.getInt(5)
                )
            }
        }
        return null
    }

    private fun updateObservation(conn: Connection, id: Long, value: Double, quality: String) {
        conn.prepareStatement("UPDATE observations SET raw_value=?, quality=? WHERE observation_id=?")
            .use { ps ->
                ps.setDouble(1, round2(value)); ps.setString(2, quality); ps.setLong(3, id)
                ps.executeUpdate()
            }
    }
}
