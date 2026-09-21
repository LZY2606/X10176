package dossier.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class Polarity { POSITIVE, NEGATIVE }

@Serializable
data class Target(
    val name: String,
    val formula: String,
    val neutralMass: Double,
)

/** 加合物规则。z>0 为正模式，z<0 为负模式；massDeltaDa 为加合/丢失的净质量。 */
@Serializable
data class AdductRule(
    val code: String,
    val massDeltaDa: Double,
    val z: Int,
)

@Serializable
data class CandidateRules(
    val ppmTolerance: Double,
    val absoluteToleranceDa: Double,
    val isotopeSpacingTolerancePpm: Double,
    val maxCharge: Int,
    val isotopeCount: Int,
    val minIsotopeRatio: Double,
    val rtWindowSeconds: Double,
    val positiveAdducts: List<AdductRule>,
    val negativeAdducts: List<AdductRule>,
    val targets: List<Target>,
)

@Serializable
data class BaselineParams(
    val noiseIntensity: Double,
    val minPeakIntensity: Double,
)

@Serializable
data class CalibrationPoint(
    val measuredMz: Double,
    val theoreticalMz: Double,
)

@Serializable
data class AnalysisConfig(
    val candidateRules: CandidateRules,
    val baseline: BaselineParams,
    val calibrationPoints: List<CalibrationPoint> = emptyList(),
    val mzIdentityBinPpm: Double = 2.0,
) {
    /** 一阶线性质量校准：mz_corrected = a * mz_measured + b（最小二乘；无点则恒等）。 */
    fun calibrationTransform(): Pair<Double, Double> = dossier.chem.Chemistry.linearFit(calibrationPoints)
}

@Serializable
data class ObservedPeak(
    val id: String,
    val rawMz: Double,
    val calibratedMz: Double,
    val intensity: Double,
    val rtSeconds: Double,
    val polarity: Polarity,
    val scans: List<Int>,
    val status: String,
    val note: String? = null,
)

@Serializable
data class IsotopeMatch(
    val order: Int,
    val expectedMz: Double,
    val observedPeakId: String? = null,
    val observedMz: Double? = null,
    val ppmError: Double? = null,
    val daError: Double? = null,
    val expectedSpacingDa: Double,
    val observedSpacingDa: Double? = null,
    val spacingPpmError: Double? = null,
    val expectedRatio: Double,
    val observedRatio: Double? = null,
    val present: Boolean,
)

@Serializable
data class CandidateExplanation(
    val target: String,
    val adductCode: String,
    val charge: Int,
    val polarity: Polarity,
    val monoisotopicExpectedMz: Double,
    val monoisotopicPeakId: String,
    val monoisotopicPpmError: Double,
    val monoisotopicDaError: Double,
    val rtWindowSeconds: Double,
    val rtSeconds: Double,
    val isotopes: List<IsotopeMatch>,
    val usedPeakIds: List<String>,
    val rulesApplied: List<String>,
    val configVersion: Int,
    val score: Double,
)

@Serializable
data class CandidateRecord(
    val id: String,
    val runId: String,
    val explanation: CandidateExplanation,
)

@Serializable
data class RunRecord(
    val id: String,
    val branchId: String,
    val versionId: String,
    val configVersion: Int,
    val createdAt: String,
    val candidates: List<CandidateRecord>,
)

@Serializable
data class PeakOccupancy(
    val candidateId: String,
    val target: String,
    val adductCode: String,
    val peakIds: List<String>,
)

@Serializable
data class DecisionRecord(
    val id: String,
    val branchId: String,
    val versionId: String,
    val candidateId: String,
    val runId: String,
    val editor: String,
    val action: String,
    val rationale: String,
    val baseVersionId: String,
    val status: String,
    val conflictSharedPeakIds: List<String> = emptyList(),
    val conflictReason: String? = null,
    val createdAt: String,
)

@Serializable
data class ConflictItem(
    val candidateId: String,
    val editor: String,
    val reason: String,
    val sharedPeakIds: List<String>,
    val baseVersionId: String,
)

@Serializable
data class VersionView(
    val branchId: String,
    val versionId: String,
    val seq: Int,
    val parentVersionId: String?,
    val eventType: String,
    val editor: String,
    val createdAt: String,
    val configVersion: Int,
    val peaks: List<ObservedPeak>,
    val runs: List<RunRecord>,
    val decisions: List<DecisionRecord>,
    val occupancies: List<PeakOccupancy>,
    val conflicts: List<ConflictItem>,
)

@Serializable
data class VersionComparison(
    val fromVersionId: String,
    val toVersionId: String,
    val changeClasses: List<String>,
    val observationChanged: Boolean,
    val calibrationChanged: Boolean,
    val rulesChanged: Boolean,
    val details: List<String>,
    val onlyInFrom: List<String>,
    val onlyInTo: List<String>,
    val changedCandidates: List<String>,
)

@Serializable
data class ImportRowError(val line: Int, val rawLine: String, val error: String)

@Serializable
data class ImportResult(
    val importId: String,
    val acceptedRows: Int,
    val quarantinedRows: List<ImportRowError>,
    val newPeakCount: Int,
    val mergedPeakCount: Int,
    val contentDigest: String,
    val branchId: String,
    val versionId: String,
)

@Serializable
data class EventAck(
    val branchId: String,
    val versionId: String,
    val seq: Int,
)

@Serializable
data class RunResponse(
    val run: RunRecord,
    val version: EventAck,
)

