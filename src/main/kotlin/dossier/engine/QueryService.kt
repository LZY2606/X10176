package dossier.engine

import dossier.db.Database
import dossier.model.*

class QueryService(private val db: Database, private val store: Store, private val adjudicator: Adjudicator) {

    private fun loadRuns(conn: java.sql.Connection, state: VersionState): List<RunRecord> {
        data class RunMeta(val id: String, val branchId: String, val versionId: String,
                           val configVersion: Int, val createdAt: String)
        val metas = mutableListOf<RunMeta>()
        for (runId in state.runIds) {
            conn.prepareStatement("SELECT * FROM runs WHERE id=?").use { ps ->
                ps.setString(1, runId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    metas += RunMeta(runId, rs.getString("branch_id"), rs.getString("version_id"),
                        rs.getInt("config_version"), rs.getString("created_at"))
                }
            }
        }
        return metas.map { meta ->
            val cands = mutableListOf<CandidateRecord>()
            conn.prepareStatement("SELECT * FROM candidates WHERE run_id=?").use { cps ->
                cps.setString(1, meta.id)
                val crs = cps.executeQuery()
                while (crs.next()) {
                    cands += CandidateRecord(
                        crs.getString("id"), meta.id,
                        Database.json.decodeFromString(CandidateExplanation.serializer(), crs.getString("explanation_json"))
                    )
                }
            }
            RunRecord(meta.id, meta.branchId, meta.versionId, meta.configVersion, meta.createdAt, cands)
        }
    }

    private fun loadDecisions(conn: java.sql.Connection, branchId: String): List<DecisionRecord> =
        adjudicator.decisionHistory(branchId)

    fun versionView(branchId: String, versionId: String? = null): VersionView = db.readOnly { conn ->
        val vid = versionId ?: store.headVersion(branchId)
        val editor: String
        conn.prepareStatement("SELECT editor FROM versions WHERE id=?").use { ps ->
            ps.setString(1, vid); val rs = ps.executeQuery()
            require(rs.next()) { "版本不存在" }
            editor = rs.getString(1)
        }
        val state = store.materialize(conn, vid)
        val runs = loadRuns(conn, state)
        val decisions = conn.prepareStatement(
            "SELECT * FROM decisions WHERE branch_id=? ORDER BY created_at"
        ).use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            val out = mutableListOf<DecisionRecord>()
            while (rs.next()) {
                out += DecisionRecord(
                    rs.getString("id"), rs.getString("branch_id"), rs.getString("version_id"),
                    rs.getString("candidate_id"), rs.getString("run_id"), rs.getString("editor"),
                    rs.getString("action"), rs.getString("rationale"), rs.getString("base_version_id"),
                    rs.getString("status"), emptyList(), rs.getString("conflict_reason"),
                    rs.getString("created_at"),
                )
            }
            out
        }
        val acceptedIds = decisions.filter { it.status == "accepted" }.map { it.candidateId }.toSet()
        val occ = mutableListOf<PeakOccupancy>()
        for (run in runs) for (cand in run.candidates) {
            if (cand.id in acceptedIds) {
                occ += PeakOccupancy(cand.id, cand.explanation.target, cand.explanation.adductCode,
                    cand.explanation.usedPeakIds)
            }
        }
        VersionView(
            branchId = branchId,
            versionId = vid,
            seq = state.seq,
            parentVersionId = state.parentVersionId,
            eventType = state.eventType,
            editor = editor,
            createdAt = state.createdAt,
            configVersion = state.configVersion,
            peaks = state.peaks.values.map { it.peak }.sortedBy { it.calibratedMz },
            runs = runs,
            decisions = decisions,
            occupancies = occ,
            conflicts = adjudicator.conflictQueue(branchId),
        )
    }

    fun versions(branchId: String): List<kotlinx.serialization.json.JsonObject> = db.readOnly { conn ->
        conn.prepareStatement("SELECT id,seq,parent_version_id,event_type,editor,created_at,config_version FROM versions WHERE branch_id=? ORDER BY seq").use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
            while (rs.next()) {
                out += kotlinx.serialization.json.buildJsonObject {
                    put("versionId", kotlinx.serialization.json.JsonPrimitive(rs.getString("id")))
                    put("seq", kotlinx.serialization.json.JsonPrimitive(rs.getInt("seq")))
                    rs.getString("parent_version_id")?.let { put("parentVersionId", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("eventType", kotlinx.serialization.json.JsonPrimitive(rs.getString("event_type")))
                    put("editor", kotlinx.serialization.json.JsonPrimitive(rs.getString("editor")))
                    put("createdAt", kotlinx.serialization.json.JsonPrimitive(rs.getString("created_at")))
                    put("configVersion", kotlinx.serialization.json.JsonPrimitive(rs.getInt("config_version")))
                }
            }
            out
        }
    }
}
