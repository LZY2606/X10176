package dossier.engine

import dossier.db.Database
import dossier.model.*

class AdjudicationException(
    val reason: String,
    val sharedPeakIds: List<String> = emptyList(),
) : Exception(reason)

data class AdjudicationResult(
    val decision: DecisionRecord,
    val versionId: String,
)

class Adjudicator(private val db: Database, private val store: Store) {

    data class StoredCandidate(
        val id: String, val runId: String, val versionId: String,
        val explanation: CandidateExplanation,
    )

    private fun loadCandidatesForVersion(conn: java.sql.Connection, versionId: String): List<StoredCandidate> {
        val out = mutableListOf<StoredCandidate>()
        conn.prepareStatement("SELECT * FROM candidates WHERE version_id=?").use { ps ->
            ps.setString(1, versionId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out += StoredCandidate(
                    rs.getString("id"), rs.getString("run_id"), rs.getString("version_id"),
                    Database.json.decodeFromString(CandidateExplanation.serializer(), rs.getString("explanation_json"))
                )
            }
        }
        return out
    }

    private fun loadCandidate(conn: java.sql.Connection, candidateId: String): StoredCandidate {
        conn.prepareStatement("SELECT * FROM candidates WHERE id=?").use { ps ->
            ps.setString(1, candidateId)
            val rs = ps.executeQuery()
            require(rs.next()) { "候选不存在: $candidateId" }
            return StoredCandidate(
                rs.getString("id"), rs.getString("run_id"), rs.getString("version_id"),
                Database.json.decodeFromString(CandidateExplanation.serializer(), rs.getString("explanation_json"))
            )
        }
    }

    /**
     * 裁定候选。检查顺序：
     * 1) 基础版本必须是分支当前头（两个编辑者基于旧版本裁定时，后者收到 stale-version 冲突）；
     * 2) 峰当前仍存在、极性一致、电荷与加合物一致、保留时间窗未漂移；
     * 3) 峰未被同一分支上已接受的候选占用；冲突时给出最小共享峰集合。
     * 缺失的低强度同位素不作为冲突（explanation 中 present=false 且无 observedPeakId）。
     */
    fun adjudicate(
        branchId: String,
        candidateId: String,
        editor: String,
        action: String,
        rationale: String,
        baseVersionId: String,
    ): AdjudicationResult = db.tx { conn ->
        require(action in setOf("accept", "reject")) { "action 必须是 accept 或 reject" }

        val headVersionId = store.headVersionOn(conn, branchId)
        if (baseVersionId != headVersionId) {
            throw AdjudicationException(
                "STALE_VERSION: 基础版本 $baseVersionId 已过期，当前头为 $headVersionId",
                emptyList()
            )
        }

        val candidate = loadCandidate(conn, candidateId)
        val state = store.materialize(conn, headVersionId)
        val config = store.config(state.configVersion)
        val exp = candidate.explanation

        // 2) 峰/极性/电荷/保留时间窗检查
        for (peakId in exp.usedPeakIds) {
            val peak = state.peaks[peakId]?.peak
                ?: throw AdjudicationException("PEAK_MISSING: 峰 $peakId 在当前版本不存在", listOf(peakId))
            if (peak.status == "contaminant") {
                throw AdjudicationException("PEAK_CONTAMINANT: 峰 $peakId 已标记为污染", listOf(peakId))
            }
            if (peak.polarity != exp.polarity) {
                throw AdjudicationException(
                    "POLARITY_MISMATCH: 峰 $peakId 极性 ${peak.polarity} 与候选 ${exp.polarity} 不一致",
                    listOf(peakId)
                )
            }
            if (kotlin.math.abs(peak.rtSeconds - exp.rtSeconds) > exp.rtWindowSeconds) {
                throw AdjudicationException(
                    "RT_WINDOW_DRIFT: 峰 $peakId 保留时间漂移超出窗口 ${exp.rtWindowSeconds}s",
                    listOf(peakId)
                )
            }
        }
        if (exp.charge !in 1..config.candidateRules.maxCharge) {
            throw AdjudicationException("CHARGE_MISMATCH: 电荷 ${exp.charge} 超出规则允许范围")
        }

        // 3) 与已接受候选的峰占用冲突（最小共享峰集合 = 两个 usedPeakIds 的交集）
        val accepted = conn.prepareStatement(
            "SELECT candidate_id FROM decisions WHERE branch_id=? AND status='accepted' AND action='accept'"
        ).use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            val ids = mutableListOf<String>()
            while (rs.next()) ids += rs.getString(1)
            ids
        }
        for (otherId in accepted) {
            if (otherId == candidateId) continue
            val other = loadCandidate(conn, otherId)
            val overlap = other.explanation.usedPeakIds.intersect(exp.usedPeakIds.toSet())
            if (overlap.isNotEmpty()) {
                throw AdjudicationException(
                    "PEAK_OCCUPANCY: 与已接受候选 ${other.explanation.target}/${other.explanation.adductCode} 争用峰",
                    overlap.sorted()
                )
            }
        }

        val decisionId = Store.newId("dec")
        val now = java.time.Instant.now().toString()
        val status = when (action) {
            "accept" -> "accepted"
            "reject" -> "rejected"
            else -> error("unreachable")
        }
        val payload = buildString {
            append("{\"decisionId\":\"$decisionId\",\"candidateId\":\"$candidateId\",\"action\":\"$action\",")
            append("\"baseVersionId\":\"$baseVersionId\",\"editor\":\"$editor\",\"rationale\":")
            append(Database.json.encodeToString(kotlinx.serialization.serializer<String>(), rationale))
            append("}")
        }
        val newVersionId = store.appendVersionOn(conn, branchId, "DECISION", editor, payload, state.configVersion)

        val record = DecisionRecord(
            id = decisionId,
            branchId = branchId,
            versionId = newVersionId,
            candidateId = candidateId,
            runId = candidate.runId,
            editor = editor,
            action = action,
            rationale = rationale,
            baseVersionId = baseVersionId,
            status = status,
            createdAt = now,
        )
        conn.prepareStatement(
            "INSERT INTO decisions(id,branch_id,version_id,candidate_id,run_id,editor,action,rationale," +
                    "base_version_id,status,conflict_peak_ids_json,conflict_reason,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL,?)"
        ).use { ps ->
            ps.setString(1, record.id); ps.setString(2, branchId); ps.setString(3, newVersionId)
            ps.setString(4, candidateId); ps.setString(5, candidate.runId); ps.setString(6, editor)
            ps.setString(7, action); ps.setString(8, rationale); ps.setString(9, baseVersionId)
            ps.setString(10, status); ps.setString(11, now); ps.executeUpdate()
        }
        AdjudicationResult(record, newVersionId)
    }

    /** 冲突队列：重新评估所有尚未裁定的候选，列出当前阻碍接受的原因与最小共享峰集合。 */
    fun conflictQueue(branchId: String): List<ConflictItem> = db.readOnly { conn ->
        val headVersionId = store.headVersionOn(conn, branchId)
        val state = store.materialize(conn, headVersionId)
        val acceptedIds = conn.prepareStatement(
            "SELECT candidate_id FROM decisions WHERE branch_id=? AND status='accepted'"
        ).use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery(); val ids = mutableListOf<String>()
            while (rs.next()) ids += rs.getString(1); ids
        }
        val acceptedCandidates = acceptedIds.map { loadCandidate(conn, it) }
        val items = mutableListOf<ConflictItem>()
        for (cand in loadCandidatesForVersion(conn, headVersionId)) {
            if (cand.id in acceptedIds) continue
            val exp = cand.explanation
            for (other in acceptedCandidates) {
                val overlap = other.explanation.usedPeakIds.intersect(exp.usedPeakIds.toSet())
                if (overlap.isNotEmpty()) {
                    items += ConflictItem(
                        candidateId = cand.id,
                        editor = "",
                        reason = "PEAK_OCCUPANCY: 与已接受候选 ${other.explanation.target}/${other.explanation.adductCode} 争用峰",
                        sharedPeakIds = overlap.sorted(),
                        baseVersionId = headVersionId,
                    )
                    break
                }
            }
            for (peakId in exp.usedPeakIds) {
                val peak = state.peaks[peakId]?.peak
                if (peak == null || peak.status == "contaminant") {
                    items += ConflictItem(cand.id, "", "PEAK_UNAVAILABLE: 峰 $peakId 不存在或为污染", listOf(peakId), headVersionId)
                }
            }
        }
        items
    }

    fun decisionHistory(branchId: String): List<DecisionRecord> = db.readOnly { conn ->
        conn.prepareStatement("SELECT * FROM decisions WHERE branch_id=? ORDER BY created_at").use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            val out = mutableListOf<DecisionRecord>()
            while (rs.next()) {
                out += DecisionRecord(
                    rs.getString("id"), rs.getString("branch_id"), rs.getString("version_id"),
                    rs.getString("candidate_id"), rs.getString("run_id"), rs.getString("editor"),
                    rs.getString("action"), rs.getString("rationale"), rs.getString("base_version_id"),
                    rs.getString("status"),
                    rs.getString("conflict_peak_ids_json")?.let {
                        Database.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), it)
                    } ?: emptyList(),
                    rs.getString("conflict_reason"),
                    rs.getString("created_at"),
                )
            }
            out
        }
    }
}
