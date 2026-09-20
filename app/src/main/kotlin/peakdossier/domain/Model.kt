package peakdossier.domain

import kotlinx.serialization.Serializable

const val ELECTRON_MASS = 0.00054858
const val NEUTRON_MASS = 1.003355

enum class Polarity(val code: String) {
    POSITIVE("+"), NEGATIVE("-");
    companion object {
        fun parse(raw: String): Polarity = when (raw.trim().lowercase()) {
            "+", "pos", "positive", "1", "p" -> POSITIVE
            "-", "neg", "negative", "-1", "n" -> NEGATIVE
            else -> throw IllegalArgumentException("未知极性: '$raw'")
        }
    }
}

/** 一条原始观测峰。stableIdentity 只由规范化后的 m/z、scan、极性决定，强度不影响身份。 */
@Serializable
data class RawPeak(
    val mz: Double,
    val intensity: Double,
    val scan: Double,
    val polarity: Polarity,
) {
    fun stableIdentity(): String =
        "P${polarity.code}_mz%.6f_rt%.4f".format(mz, scan)
}

data class IngestRow(
    val lineNo: Int,
    val raw: String,
    val error: String,
)

data class IngestResult(
    val importId: Long,
    val accepted: Int,
    val newPeaks: Int,
    val quarantined: List<IngestRow>,
)

@Serializable
data class AdductSpec(
    val name: String,
    val mass: Double,
    val charge: Int,
    val polarity: String,
) {
    fun applies(polarity: Polarity): Boolean =
        polarity == Polarity.valueOf(this.polarity.uppercase())
}

@Serializable
data class TargetSpec(
    val compoundId: String,
    val name: String,
    val formula: String,
    val neutralMass: Double,
    val rtMin: Double,
    val rtMax: Double,
    val maxIsotopes: Int = 3,
)

@Serializable
data class RuleConfig(
    val version: Int,
    val ppmTolerance: Double,
    val absToleranceDa: Double,
    val isotopeMinSpacing: Double = 0.7,
    val isotopeMaxSpacing: Double = 1.4,
    val minIsotopeIntensityRatio: Double = 0.01,
    val adducts: List<AdductSpec>,
    val targets: List<TargetSpec>,
)

@Serializable
data class CalibrationPoint(
    val measuredMz: Double,
    val referenceMz: Double,
    val label: String = "",
)

/** 一次校准：measured -> calibrated 的线性模型 m' = slope * m + intercept，外加 RMS 残差(ppm)。 */
@Serializable
data class CalibrationModel(
    val slope: Double,
    val intercept: Double,
    val rmsPpm: Double,
    val points: List<CalibrationPoint>,
) {
    fun calibrate(mz: Double): Double = slope * mz + intercept
}

@Serializable
data class BaselineParams(
    val noiseFloor: Double,
    val relativeThreshold: Double,
)

@Serializable
data class IsotopeObservation(
    val index: Int,
    val expectedMz: Double,
    val peakKey: String? = null,
    val observedMz: Double? = null,
    val errorPpm: Double? = null,
    val errorDa: Double? = null,
    val spacingDa: Double? = null,
    val intensity: Double? = null,
    val expectedRatio: Double,
    val observedRatio: Double? = null,
    val missing: Boolean = false,
)

@Serializable
data class CandidateExplanation(
    val ruleVersion: Int,
    val calibrationVersion: Int,
    val monoisotopicPeak: String?,
    val monoisotopicErrorPpm: Double?,
    val monoisotopicErrorDa: Double?,
    val adduct: String,
    val charge: Int,
    val polarity: String,
    val rtWindow: List<Double>,
    val observedRt: Double?,
    val isotopes: List<IsotopeObservation>,
    val usedPeakKeys: List<String>,
    val missingIsotopes: List<Int>,
    val score: Double,
    val scoreNotes: List<String>,
)

@Serializable
data class CandidateView(
    val candidateId: String,
    val compoundId: String,
    val compoundName: String,
    val adduct: String,
    val charge: Int,
    val polarity: String,
    val mzTheoretical: Double,
    val rt: Double?,
    val monoisotopicPeak: String?,
    val usedPeakKeys: List<String>,
    val score: Double,
    val explanation: CandidateExplanation,
    val status: String = "proposed",
    val adjudicator: String? = null,
)

enum class AnnotationType { CONTAMINANT, SPLIT }

data class PeakAnnotation(
    val type: AnnotationType,
    val peakKey: String,
    val reason: String,
    /** SPLIT 时由人工指定替代峰的 m/z（原峰视为饱和，新点参与匹配）。 */
    val replacementMz: Double? = null,
    val replacementIntensity: Double? = null,
)

enum class VersionType { ROOT, INGEST, CALIBRATION, BASELINE, RULES, ANNOTATION, ADJUDICATION }

data class DossierVersion(
    val id: Long,
    val parentId: Long?,
    val type: VersionType,
    val label: String,
    val editor: String,
    val createdAt: String,
    val payloadRef: String?,
)

data class Adjudication(
    val candidateId: String,
    val decision: String,
    val editor: String,
    val versionId: Long,
    val reason: String,
)

data class Conflict(
    val candidateA: String,
    val candidateB: String,
    val compounds: Pair<String, String>,
    val sharedPeakKeys: List<String>,
) {
    val minimalSharedSet: List<String> get() = sharedPeakKeys
}
