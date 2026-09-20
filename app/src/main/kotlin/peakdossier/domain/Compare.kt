package peakdossier.domain

import kotlinx.serialization.Serializable

@Serializable
data class CandidateDiff(
    val candidateId: String,
    val compoundName: String,
    val adduct: String,
    val kind: String, // APPEARED / DISAPPEARED / SCORE_CHANGED / PEAKS_CHANGED / SAME
    val scoreOld: Double?,
    val scoreNew: Double?,
    val usedPeaksOld: List<String>,
    val usedPeaksNew: List<String>,
    val causes: List<String>, // OBSERVATION / CALIBRATION / RULES
)

@Serializable
data class CompareReport(
    val oldVersion: Long,
    val newVersion: Long,
    val observationChanged: Boolean,
    val calibrationChanged: Boolean,
    val rulesChanged: Boolean,
    val diffs: List<CandidateDiff>,
)

object Compare {

    /**
     * 比较两个视图上的候选。变化归因：
     * - OBSERVATION：沿谱系的 INGEST/ANNOTATION 版本不同（观测峰或污染/拆分变了）；
     * - CALIBRATION：生效校准版本不同；
     * - RULES：生效规则版本不同。
     */
    fun compare(
        old: RepositoryLikeView,
        new: RepositoryLikeView,
        oldChainTypes: Set<String>,
        newChainTypes: Set<String>,
    ): CompareReport {
        val obsChanged = chainObsChanged(old, new)
        val calChanged = old.calibrationVersion != new.calibrationVersion ||
                old.calibration.slope != new.calibration.slope ||
                old.calibration.intercept != new.calibration.intercept
        val rulesChanged = old.rules.version != new.rules.version
        val causes = buildList {
            if (obsChanged) add("OBSERVATION")
            if (calChanged) add("CALIBRATION")
            if (rulesChanged) add("RULES")
        }

        val oldById = old.candidates.associateBy { identity(it) }
        val newById = new.candidates.associateBy { identity(it) }
        val diffs = mutableListOf<CandidateDiff>()

        for ((id, n) in newById) {
            val o = oldById[id]
            if (o == null) {
                diffs += CandidateDiff(
                    id, n.compoundName, n.adduct, "APPEARED", null, n.score,
                    emptyList(), n.usedPeakKeys, causes,
                )
                continue
            }
            val peaksChanged = o.usedPeakKeys.toSet() != n.usedPeakKeys.toSet()
            val scoreChanged = o.score != n.score
            val kind = when {
                peaksChanged -> "PEAKS_CHANGED"
                scoreChanged -> "SCORE_CHANGED"
                else -> "SAME"
            }
            if (kind != "SAME") {
                val localCauses = causes.toMutableList()
                if (peaksChanged && !obsChanged && !calChanged && rulesChanged) Unit
                diffs += CandidateDiff(
                    id, n.compoundName, n.adduct, kind, o.score, n.score,
                    o.usedPeakKeys, n.usedPeakKeys, localCauses,
                )
            }
        }
        for ((id, o) in oldById) {
            if (id !in newById) {
                diffs += CandidateDiff(
                    id, o.compoundName, o.adduct, "DISAPPEARED", o.score, null,
                    o.usedPeakKeys, emptyList(), causes,
                )
            }
        }
        return CompareReport(
            old.versionId, new.versionId, obsChanged, calChanged, rulesChanged,
            diffs.sortedWith(compareByDescending<CandidateDiff> { it.kind != "SAME" }.thenBy { it.candidateId }),
        )
    }

    /** 候选身份与规则/校准无关：目标+加合物+单同位素峰键。 */
    private fun identity(c: CandidateView): String =
        "${c.compoundId}|${c.adduct}|z${c.charge}|${c.monoisotopicPeak}"

    private fun chainObsChanged(old: RepositoryLikeView, new: RepositoryLikeView): Boolean =
        old.observationSignature != new.observationSignature
}

/** 比较所需视图投影。 */
interface RepositoryLikeView {
    val versionId: Long
    val candidates: List<CandidateView>
    val calibration: CalibrationModel
    val calibrationVersion: Int
    val rules: RuleConfig
    val observationSignature: String
}
