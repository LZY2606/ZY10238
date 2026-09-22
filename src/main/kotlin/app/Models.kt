package app

import kotlinx.serialization.Serializable

const val TRAIT_LEAF_AREA = "leaf_area"
const val TRAIT_HEIGHT = "height"
const val TRAIT_CANOPY_WIDTH = "canopy_width"
val TRAITS = listOf(TRAIT_LEAF_AREA, TRAIT_HEIGHT, TRAIT_CANOPY_WIDTH)

@Serializable
data class Plant(
    val id: String,
    val batch: String,
    val position: String,
    val genotype: String,
    val treatment: String,
    val reference: Boolean,
)

@Serializable
data class Observation(
    val id: String,
    val plantId: String,
    val day: Int,
    val replicate: String,
    val cameraEra: String,
    val leafArea: Double,
    val height: Double,
    val canopyWidth: Double,
    val rawQuality: String,
)

@Serializable
data class EffectiveObservation(
    val observation: Observation,
    val quality: String,
    val qualitySource: String,
    val adjustedLeafArea: Double,
    val adjustedHeight: Double,
    val adjustedCanopyWidth: Double,
)

@Serializable
data class Boundary(
    val batch: String,
    val boundaryDay: Int,
    val confirmed: Boolean,
    val confirmedAt: String? = null,
)

@Serializable
data class DataChecksum(
    val designChecksum: String,
    val rawChecksum: String,
    val plantCount: Int,
    val rawObservationCount: Int,
)

@Serializable
data class CandidateFit(
    val model: String,
    val parameters: Map<String, Double>,
    val sse: Double,
    val aic: Double,
    val selectionScore: Double,
    val selected: Boolean,
)

@Serializable
data class PlantCurve(
    val plantId: String,
    val trait: String,
    val candidateCount: Int,
    val candidates: List<CandidateFit>,
    val fittedValues: Map<Int, Double>,
    val supportMinDay: Int,
    val supportMaxDay: Int,
)

@Serializable
data class OffsetEstimate(
    val batch: String,
    val trait: String,
    val status: String,
    val offset: Double? = null,
    val standardError: Double? = null,
    val ci95Low: Double? = null,
    val ci95High: Double? = null,
    val referencePlantCount: Int = 0,
    val pairCount: Int = 0,
    val reason: String,
)

@Serializable
data class CellStat(
    val genotype: String,
    val treatment: String,
    val batches: List<String>,
    val plantCount: Int,
    val mean: Double? = null,
    val standardError: Double? = null,
    val designGap: Boolean = false,
    val singleBatch: Boolean = false,
)

@Serializable
data class InteractionEstimate(
    val treatment: String,
    val control: String,
    val genotypeA: String,
    val genotypeB: String,
    val status: String,
    val estimate: Double? = null,
    val standardError: Double? = null,
    val ci95Low: Double? = null,
    val ci95High: Double? = null,
    val reason: String,
)

@Serializable
data class Coverage(
    val batch: String,
    val trait: String,
    val rawRows: Int,
    val acceptedRows: Int,
    val rejectedRows: Int,
    val plants: Int,
    val minDay: Int?,
    val maxDay: Int?,
)

@Serializable
data class AnalysisResponse(
    val runId: Long? = null,
    val designVersionId: Int,
    val preprocessingVersionId: Int,
    val targetDay: Int = 14,
    val trait: String,
    val excludedPlantIds: List<String>,
    val offsets: List<OffsetEstimate>,
    val curves: List<PlantCurve>,
    val cells: List<CellStat>,
    val interactions: List<InteractionEstimate>,
    val coverage: List<Coverage>,
    val adjustedObservations: List<EffectiveObservation>,
    val extrapolation: Map<String, List<Int>>,
    val notes: List<String>,
)

@Serializable
data class AnalysisRequest(
    val trait: String = TRAIT_LEAF_AREA,
    val targetDay: Int = 16,
    val preprocessingVersionId: Int? = null,
    val excludedPlantIds: Set<String> = emptySet(),
    val persist: Boolean = true,
)

@Serializable
data class QualityRequest(
    val quality: String,
    val reason: String = "",
)

@Serializable
data class BoundaryRequest(
    val boundaryDay: Int,
)

@Serializable
data class ReimportResponse(
    val checksum: DataChecksum,
    val message: String,
)

@Serializable
data class OperationLog(
    val id: Long,
    val createdAt: String,
    val action: String,
    val detail: String,
)

@Serializable
data class RunSummary(
    val id: Long,
    val createdAt: String,
    val designVersionId: Int,
    val preprocessingVersionId: Int,
    val trait: String,
    val targetDay: Int,
    val excludedPlantIds: List<String>,
    val result: AnalysisResponse,
)

@Serializable
data class Bootstrap(
    val plants: List<Plant>,
    val observations: List<Observation>,
    val effectiveObservations: List<EffectiveObservation>,
    val boundaries: List<Boundary>,
    val designVersionId: Int,
    val preprocessingVersionId: Int,
    val checksum: DataChecksum,
    val runs: List<RunSummary>,
)

@Serializable
data class DesignSnapshot(
    val plants: List<Plant>,
    val observations: List<Observation>,
)
