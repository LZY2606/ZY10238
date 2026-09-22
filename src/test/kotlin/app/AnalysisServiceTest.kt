package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisServiceTest {
    private fun newDb() = Database(":memory:")

    @Test
    fun estimatesCameraOffsetOnlyWithConfirmedOverlappingReferencePlants() {
        val db = newDb()
        val service = AnalysisService(db)
        db.boundaries().forEach { db.setBoundary(it.batch, Fixture.BOUNDARY_DAY) }

        val identified = service.analyze(AnalysisRequest(trait = TRAIT_LEAF_AREA))
        assertEquals(2, identified.offsets.size)
        assertTrue(identified.offsets.all { it.status == "IDENTIFIED" })
        identified.offsets.forEach {
            assertEquals(2, it.referencePlantCount)
            assertEquals(2, it.pairCount)
            assertNotNull(it.offset)
            assertEquals(12.0, it.offset!!, 0.05)
        }

        val excluded = db.plants().filter { it.batch == "B2" && it.reference }.map { it.id }.toSet()
        val removed = service.analyze(AnalysisRequest(trait = TRAIT_LEAF_AREA, excludedPlantIds = excluded))
        val b1 = removed.offsets.first { it.batch == "B1" }
        val b2 = removed.offsets.first { it.batch == "B2" }
        assertEquals("IDENTIFIED", b1.status)
        assertEquals("NOT_IDENTIFIED", b2.status)
        assertNull(b2.offset)
        assertTrue(b2.reason.contains("不可识别"))
    }

    @Test
    fun unconfirmedBoundaryRemainsNonIdentifiable() {
        val db = newDb()
        val result = AnalysisService(db).analyze(AnalysisRequest())
        assertTrue(result.offsets.all { it.status == "UNCONFIRMED" })
        assertTrue(result.offsets.all { it.offset == null })
    }

    @Test
    fun missingGenotypeTreatmentCellIsDesignGapWithoutInteractionEstimate() {
        val db = newDb()
        db.boundaries().forEach { db.setBoundary(it.batch, Fixture.BOUNDARY_DAY) }
        val result = AnalysisService(db).analyze(AnalysisRequest(trait = TRAIT_LEAF_AREA))
        val waterGap = result.cells.first { it.genotype == "G2" && it.treatment == "W" }
        assertTrue(waterGap.designGap)
        assertNull(waterGap.mean)
        val waterInteraction = result.interactions.first { it.treatment == "W" }
        assertEquals("DESIGN_GAP", waterInteraction.status)
        assertNull(waterInteraction.estimate)
        assertTrue(waterInteraction.reason.contains("G2×W"))

        val drought = result.interactions.first { it.treatment == "D" }
        assertNotNull(drought.estimate)
        assertTrue(drought.status == "ESTIMATED")
    }

    @Test
    fun repeatedSameDayObservationsKeepSeparateQualityRowsAndFitResiduals() {
        val db = newDb()
        val plantId = "B1-G1-C-01"
        val dayFour = db.observations().filter { it.plantId == plantId && it.day == 4 }
        assertEquals(2, dayFour.size)
        assertEquals(setOf("R1", "R2"), dayFour.map { it.replicate }.toSet())
        assertTrue(dayFour.any { it.rawQuality == "good" })
        assertTrue(dayFour.any { it.rawQuality == "bad" })

        val correctedId = dayFour.first { it.replicate == "R1" }.id
        val version = db.setQuality(correctedId, "bad", "图像边缘截断")
        assertEquals(2, version)
        val effective = db.effectiveObservations().filter { it.observation.plantId == plantId && it.observation.day == 4 }
        assertEquals(2, effective.size)
        assertTrue(effective.all { it.quality == "bad" })
        assertEquals(2, db.observations().count { it.plantId == plantId && it.day == 4 })
    }

    @Test
    fun keepsThreeCandidateModelsAndMarksExtrapolationBeyondObservedDay() {
        val db = newDb()
        db.boundaries().forEach { db.setBoundary(it.batch, Fixture.BOUNDARY_DAY) }
        val result = AnalysisService(db).analyze(AnalysisRequest(trait = TRAIT_HEIGHT, targetDay = 14))
        val curve = result.curves.first()
        assertEquals(3, curve.candidateCount)
        assertEquals(setOf("saturated", "segmented_linear", "monotone_spline"), curve.candidates.map { it.model }.toSet())
        assertEquals(1, curve.candidates.count { it.selected })
        assertEquals((0..14).toList(), curve.fittedValues.keys.sorted())
    }

    @Test
    fun reseedClearsOverridesAndRestoresFixedChecksums() {
        val db = newDb()
        val before = db.checksum()
        db.setQuality(db.observations().first().id, "bad", "test")
        assertEquals(2, db.latestPreprocessingVersion())
        val after = db.reseed("test clear and replay")
        assertEquals(before, after)
        assertEquals(Fixture.initialPreprocessingVersion, db.latestPreprocessingVersion())
        assertTrue(db.effectiveObservations().none { it.qualitySource == "corrected" })
        assertEquals(18, db.plants().size)
    }
}
