package app

import kotlinx.serialization.Serializable

@Serializable
data class Fixture(
    val fixtureVersion: String,
    val seed: Long,
    val description: String,
    val days: List<Int>,
    val cameraSwitchDay: Int,
    val cameraOverlapDays: List<Int>,
    val batches: List<BatchSpec>,
    val genotypes: List<CodeLabel>,
    val treatments: List<CodeLabel>,
    val cells: List<CellSpec>,
    val references: List<ReferenceSpec>,
    val traits: List<TraitSpec>,
    val massDays: List<Int>,
    val massEveryNthPlant: Int,
    val massFirstIndex: Int,
    val injections: List<InjectionSpec> = emptyList()
)

@Serializable
data class BatchSpec(val id: String, val label: String, val trayOffset: Double)

@Serializable
data class CodeLabel(val id: String, val label: String)

@Serializable
data class CellSpec(
    val genotype: String,
    val treatment: String,
    val batches: List<String>,
    val plantsPerBatch: Int
)

@Serializable
data class ReferenceSpec(
    val plantCode: String,
    val genotype: String,
    val treatment: String,
    val batch: String
)

@Serializable
data class TraitSpec(
    val id: String,
    val label: String,
    val unit: String,
    val base: Double,
    val asymptote: Map<String, Double>,
    val rate: Map<String, Double>,
    val stressAsymFactor: Double,
    val plantSigma: Double,
    val noiseSigma: Double,
    val cameraOffset: Double,
    val imaging: Boolean
)

@Serializable
data class InjectionSpec(
    val kind: String,
    val plantIndex: Int? = null,
    val plantCode: String? = null,
    val day: Int,
    val trait: String,
    val repeatQuality: String? = null,
    val repeatOffset: Double? = null,
    val offset: Double? = null,
    val quality: String? = null
)

data class Plant(
    val plantId: String,
    val code: String,
    val ordinal: Int,
    val genotype: String,
    val treatment: String,
    val batch: String,
    val reference: Boolean
)

data class Observation(
    val observationId: Long,
    val plantId: String,
    val traitId: String,
    val day: Int,
    val rawValue: Double,
    val quality: String,
    val camera: String,
    val replicate: Int
)

@Serializable
data class ObservationDto(
    val observationId: Long,
    val plantId: String,
    val code: String,
    val genotype: String,
    val treatment: String,
    val batch: String,
    val reference: Boolean,
    val traitId: String,
    val traitLabel: String,
    val unit: String,
    val day: Int,
    val rawValue: Double,
    val correctedValue: Double?,
    val quality: String,
    val camera: String,
    val replicate: Int
)

@Serializable
data class DesignCell(
    val genotype: String,
    val treatment: String,
    val batches: List<String>,
    val plants: List<PlantSummary>
)

@Serializable
data class PlantSummary(
    val plantId: String,
    val code: String,
    val batch: String,
    val reference: Boolean
)

@Serializable
data class CoverageRow(
    val plantId: String,
    val code: String,
    val genotype: String,
    val treatment: String,
    val batch: String,
    val reference: Boolean,
    val traitId: String,
    val nTotal: Int,
    val nOk: Int,
    val nSuspect: Int,
    val nExcluded: Int,
    val minDay: Int?,
    val maxDay: Int?,
    val nDays: Int
)

@Serializable
data class OffsetPair(
    val plantId: String,
    val code: String,
    val day: Int,
    val oldValue: Double,
    val newValue: Double,
    val delta: Double
)

@Serializable
data class OffsetEstimate(
    val traitId: String,
    val identifiable: Boolean,
    val estimate: Double?,
    val se: Double?,
    val nPairs: Int,
    val reason: String,
    val pairs: List<OffsetPair>
)

@Serializable
data class BatchOffsetEstimate(
    val traitId: String,
    val anchorBatch: String,
    val identifiable: Boolean,
    val estimate: Double?,
    val se: Double?,
    val nSharedCells: Int,
    val reason: String,
    val cellDeltas: List<CellDelta>
)

@Serializable
data class CellDelta(
    val genotype: String,
    val treatment: String,
    val delta: Double
)

@Serializable
data class FitPoint(
    val day: Int,
    val value: Double,
    val se: Double,
    val extrapolation: Boolean
)

@Serializable
data class ModelFit(
    val model: String,
    val label: String,
    val params: Map<String, Double>,
    val rmse: Double,
    val bic: Double,
    val monotonic: Boolean,
    val nPoints: Int,
    val curve: List<FitPoint>
)

@Serializable
data class TraitFit(
    val traitId: String,
    val traitLabel: String,
    val unit: String,
    val eligible: Boolean,
    val reason: String,
    val recommended: String?,
    val rawAuc: Double?,
    val adjustedAuc: Double?,
    val models: List<ModelFit>
)

@Serializable
data class PlantFit(
    val plantId: String,
    val code: String,
    val genotype: String,
    val treatment: String,
    val batch: String,
    val reference: Boolean,
    val traits: List<TraitFit>
)

@Serializable
data class CellSummary(
    val genotype: String,
    val treatment: String,
    val batches: List<String>,
    val status: String,
    val reason: String,
    val plants: List<String>,
    val aucByTrait: Map<String, CellAuc>
)

@Serializable
data class CellAuc(
    val meanRawAuc: Double?,
    val meanAdjustedAuc: Double?,
    val se: Double?,
    val nPlants: Int
)

@Serializable
data class EffectEntry(
    val key: String,
    val kind: String,
    val genotype: String?,
    val treatment: String?,
    val traitId: String,
    val estimate: Double?,
    val se: Double?,
    val status: String,
    val reason: String
)

@Serializable
data class AnalysisMeta(
    val runId: Long?,
    val label: String,
    val createdAt: String,
    val designVersion: String,
    val fixtureVersion: String,
    val preprocessVersion: String,
    val cameraBoundaryConfirmed: Boolean,
    val cameraBoundaryDay: Int,
    val projectionHorizonDays: Int
)

@Serializable
data class AnalysisResult(
    val meta: AnalysisMeta,
    val cameraOffsets: List<OffsetEstimate>,
    val batchOffsets: List<BatchOffsetEstimate>,
    val cells: List<CellSummary>,
    val missingCells: List<DesignGap>,
    val effects: List<EffectEntry>,
    val coverage: List<CoverageRow>,
    val plants: List<PlantFit>,
    val warnings: List<String>
)

@Serializable
data class DesignGap(
    val genotype: String,
    val treatment: String,
    val reason: String
)

@Serializable
data class AnalysisRunSummary(
    val runId: Long,
    val label: String,
    val createdAt: String,
    val designVersion: String,
    val fixtureVersion: String,
    val preprocessVersion: String
)

@Serializable
data class ReplayBundle(
    val fixture: Fixture,
    val qualityCorrections: List<QualityCorrectionDto>,
    val boundary: BoundaryDto,
    val runs: List<AnalysisRunSummary>
)

@Serializable
data class QualityCorrectionDto(
    val observationId: Long,
    val previousQuality: String,
    val newQuality: String,
    val reason: String,
    val correctedAt: String
)

@Serializable
data class BoundaryDto(
    val switchDay: Int,
    val confirmed: Boolean,
    val confirmedAt: String?
)

@Serializable
data class Versions(
    val designVersion: String,
    val fixtureVersion: String,
    val preprocessVersion: String,
    val cameraBoundaryConfirmed: Boolean,
    val cameraBoundaryDay: Int
)
