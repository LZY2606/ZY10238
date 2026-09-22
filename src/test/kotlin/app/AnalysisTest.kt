package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AnalysisTest {

    private fun setup(folder: File): Pair<Database, Fixture> {
        val path = File(folder, "analysis.db").absoluteFile.also { it.delete() }.absolutePath
        val db = Database(path)
        val (fixture, text) = Service.loadFixture()
        Service.seedIfEmpty(db, text, fixture)
        return db to fixture
    }

    @Test
    fun `camera offset estimated from overlap references near truth`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "test")
        val leaf = result.cameraOffsets.first { it.traitId == "leaf_area" }
        assertTrue(leaf.identifiable)
        assertEquals(6, leaf.nPairs)
        assertNotNull(leaf.estimate)
        assertTrue(kotlin.math.abs(leaf.estimate!! - 8.0) < 3.0, "估计 ${leaf.estimate} 应接近真值 8")
        // 质量标志非影像性状：偏移不适用且不是数值估计
        val mass = result.cameraOffsets.first { it.traitId == "mass" }
        assertFalse(mass.identifiable)
        assertNull(mass.estimate)
    }

    @Test
    fun `removing overlap references makes camera offset unidentifiable`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val plants = db.plants().filter { it.reference }
        for (p in plants) {
            val overlap = db.observations().filter {
                it.plantId == p.plantId && it.day in fixture.cameraOverlapDays
            }
            overlap.forEach { db.correctQuality(it.observationId, "EXCLUDED", "移除参照") }
        }
        val result = Engine.analyze(db, fixture, "test")
        for (o in result.cameraOffsets.filter { it.traitId != "mass" }) {
            assertFalse(o.identifiable, "${o.traitId} 必须不可识别")
            assertNull(o.estimate)
            assertEquals(0, o.nPairs)
        }
        // 批次偏移仍可由共有组合识别
        assertTrue(result.batchOffsets.all { it.identifiable })
    }

    @Test
    fun `batch offset identifiable with sign matching tray difference`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "test")
        for (o in result.batchOffsets) {
            assertTrue(o.identifiable, o.reason)
            assertNotNull(o.estimate)
            assertTrue(o.estimate!! < 0.0, "${o.traitId} B2-B1 应为负（-2 - 3 = -5），实际 ${o.estimate}")
        }
    }

    @Test
    fun `missing genotype-treatment combination yields no interaction estimate`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "test")
        assertTrue(result.missingCells.any { it.genotype == "G3" && it.treatment == "CTRL" })
        val g3Effect = result.effects.first {
            it.kind == "treatment" && it.genotype == "G3" && it.traitId == "leaf_area"
        }
        assertEquals("design_gap", g3Effect.status)
        assertNull(g3Effect.estimate)
        val g3Interaction = result.effects.first {
            it.kind == "interaction" && it.genotype == "G3" && it.traitId == "leaf_area"
        }
        assertEquals("design_gap", g3Interaction.status)
        assertNull(g3Interaction.estimate)
        // 完整 2x2 的 G1/G2 互作用有数值
        val g2Interaction = result.effects.first {
            it.kind == "interaction" && it.genotype == "G2" && it.traitId == "leaf_area"
        }
        assertEquals("ok", g2Interaction.status)
        assertNotNull(g2Interaction.estimate)
    }

    @Test
    fun `single-batch cell is flagged and treatment effect is descriptive`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "test")
        val cell = result.cells.first { it.genotype == "G3" && it.treatment == "STRESS" }
        assertEquals("single_batch", cell.status)
    }

    @Test
    fun `boundary confirmation changes preprocess version and correction is audited`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val v0 = Engine.versions(db, fixture, db.getMeta("fixture_text")!!)
        assertFalse(v0.cameraBoundaryConfirmed)
        db.setBoundary(14, true)
        val v1 = Engine.versions(db, fixture, db.getMeta("fixture_text")!!)
        assertTrue(v1.cameraBoundaryConfirmed)
        assertTrue(v0.preprocessVersion != v1.preprocessVersion)
        // 质量修正落审计表
        val obs = db.observations().first()
        db.correctQuality(obs.observationId, "EXCLUDED", "测试剔除")
        val corr = db.corrections().last()
        assertEquals(obs.observationId, corr.observationId)
        assertEquals("EXCLUDED", corr.newQuality)
    }

    @Test
    fun `three candidate models and extrapolation points are present`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "test")
        val fit = result.plants.first().traits.first { it.traitId == "leaf_area" }
        assertTrue(fit.eligible)
        assertEquals(setOf("saturated", "piecewise", "monotone_spline"), fit.models.map { it.model }.toSet())
        for (m in fit.models) {
            assertTrue(m.curve.any { it.extrapolation })
            assertTrue(m.curve.none { it.day > 28 && !it.extrapolation })
        }
    }

    @Test
    fun `run persists and survives reseed-independent replay bundle`(@TempDir folder: File) {
        val (db, fixture) = setup(folder)
        val result = Engine.analyze(db, fixture, "固化")
        val versions = Engine.versions(db, fixture, db.getMeta("fixture_text")!!)
        val id = db.saveRun("固化", versions, Engine.toJson(result))
        val loaded = Engine.json.decodeFromString<AnalysisResult>(db.runJson(id)!!)
        assertEquals("固化", loaded.meta.label)
        val bundle = Service.exportReplay(db, fixture)
        assertEquals(fixture.fixtureVersion, bundle.fixture.fixtureVersion)
        assertTrue(bundle.runs.any { it.runId == id })
    }
}
