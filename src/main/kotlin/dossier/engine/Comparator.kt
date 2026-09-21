package dossier.engine

import dossier.db.Database
import dossier.model.CandidateExplanation
import dossier.model.VersionComparison

/**
 * 版本间结果比较，区分三类来源：
 * - 观测变化（IMPORT/SPLIT/CONTAMINANT 导致峰集合或属性不同）
 * - 校准变化（校准 m/z 变换不同，raw m/z 相同）
 * - 规则变化（版本引用的 configVersion 不同）
 */
class VersionComparatorEngine(private val db: Database, private val store: Store) {

    private data class CandidateSnap(
        val target: String,
        val adduct: String,
        val charge: Int,
        val polarity: String,
        val anchorPeakId: String,
        val m0: Double,
        val ppm: Double,
        val configVersion: Int,
        val usedPeaks: List<String>,
    )

    private fun snapsAt(versionId: String): List<CandidateSnap> {
        val state = store.getVersion(versionId)
        val out = mutableListOf<CandidateSnap>()
        for (runId in state.runIds.reversed().distinct()) {
            // 仅取该版本最新 run 的候选用于比较
        }
        db.readOnly { conn ->
            val runIds = state.runIds
            for (rid in runIds) {
                conn.prepareStatement("SELECT explanation_json FROM candidates WHERE run_id=?").use { ps ->
                    ps.setString(1, rid)
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val e = Database.json.decodeFromString(CandidateExplanation.serializer(), rs.getString(1))
                        out += CandidateSnap(e.target, e.adductCode, e.charge, e.polarity.name,
                            e.monoisotopicPeakId, e.monoisotopicExpectedMz, e.monoisotopicPpmError,
                            e.configVersion, e.usedPeakIds)
                    }
                }
            }
        }
        return out
    }

    fun compare(fromVersionId: String, toVersionId: String): VersionComparison {
        val fromState = store.getVersion(fromVersionId)
        val toState = store.getVersion(toVersionId)

        val rawFrom = fromState.peaks.values.map { it.peak.id to it.peak.rawMz }.toMap()
        val rawTo = toState.peaks.values.map { it.peak.id to it.peak.rawMz }.toMap()
        val observationChanged = rawFrom != rawTo ||
                fromState.peaks.values.map { it.peak.id to it.peak.status }.toMap() !=
                toState.peaks.values.map { it.peak.id to it.peak.status }.toMap()

        val calFrom = fromState.peaks.values.associate { it.peak.id to it.peak.calibratedMz }
        val calTo = toState.peaks.values.associate { it.peak.id to it.peak.calibratedMz }
        val calibrationChanged = rawFrom == rawTo && calFrom != calTo ||
                (!observationChanged && calFrom.keys == calTo.keys &&
                        calFrom.keys.any { kotlin.math.abs((calFrom[it] ?: 0.0) - (calTo[it] ?: 0.0)) > 1e-12 })

        val rulesChanged = fromState.configVersion != toState.configVersion

        val fromSnaps = snapsAt(fromVersionId)
        val toSnaps = snapsAt(toVersionId)
        val key = { s: CandidateSnap -> "${s.target}|${s.adduct}|${s.charge}|${s.polarity}|${s.anchorPeakId}" }
        val fromMap = fromSnaps.associateBy(key)
        val toMap = toSnaps.associateBy(key)
        val onlyInFrom = fromMap.keys - toMap.keys
        val onlyInTo = toMap.keys - fromMap.keys
        val changed = fromMap.keys.intersect(toMap.keys).filter {
            val a = fromMap.getValue(it); val b = toMap.getValue(it)
            a.configVersion != b.configVersion ||
                    kotlin.math.abs(a.ppm - b.ppm) > 1e-9 ||
                    a.usedPeaks != b.usedPeaks
        }

        val classes = buildList {
            if (observationChanged) add("OBSERVATION")
            if (calibrationChanged) add("CALIBRATION")
            if (rulesChanged) add("RULES")
            if (isEmpty()) add("UNCHANGED")
        }
        val details = buildList {
            if (observationChanged) add("观测峰集合或状态发生变化（导入/拆分/污染标记）")
            if (calibrationChanged) add("质量校准变换发生变化，原始 m/z 不变")
            if (rulesChanged) add("候选规则由配置版本 ${fromState.configVersion} 变为 ${toState.configVersion}")
        }
        return VersionComparison(
            fromVersionId = fromVersionId,
            toVersionId = toVersionId,
            changeClasses = classes,
            observationChanged = observationChanged,
            calibrationChanged = calibrationChanged,
            rulesChanged = rulesChanged,
            details = details,
            onlyInFrom = onlyInFrom.toList(),
            onlyInTo = onlyInTo.toList(),
            changedCandidates = changed,
        )
    }
}
