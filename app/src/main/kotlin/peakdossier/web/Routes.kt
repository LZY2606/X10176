package peakdossier.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import peakdossier.db.Repository
import peakdossier.domain.*

@Serializable data class IngestRequest(val filename: String? = null, val content: String, val parent: Long? = null, val editor: String = "analyst")
@Serializable data class AnnotationRequest(val peakKey: String, val type: String, val reason: String = "", val replacementMz: Double? = null, val replacementIntensity: Double? = null, val parent: Long? = null, val editor: String = "analyst")
@Serializable data class CalibrationRequest(val points: List<CalibrationPoint>, val parent: Long? = null, val editor: String = "analyst", val label: String? = null)
@Serializable data class RollbackRequest(val sourceVersion: Long, val parent: Long? = null, val editor: String = "analyst")
@Serializable data class BaselineRequest(val noiseFloor: Double, val relativeThreshold: Double, val parent: Long? = null, val editor: String = "analyst")
@Serializable data class RulesRequest(val rules: RuleConfig, val parent: Long? = null, val editor: String = "analyst")
@Serializable data class AdjudicateRequest(val baseVersion: Long, val candidateId: String, val decision: String, val editor: String = "analyst", val reason: String = "")
@Serializable data class ApiError(val error: String, val message: String? = null, val reasons: List<String>? = null)
@Serializable data class RowError(val lineNo: Int, val raw: String, val error: String)
@Serializable data class IngestResponse(val versionId: Long, val importId: Long, val accepted: Int, val newPeaks: Int, val quarantined: List<RowError>)
@Serializable data class HistoryDto(val versionId: Long, val candidateId: String, val candidateHash: String, val decision: String, val editor: String, val reason: String, val createdAt: String)
@Serializable data class VersionDto(val id: Long, val parentId: Long? = null, val type: String, val label: String, val editor: String, val createdAt: String, val importId: Long? = null)
@Serializable data class CompareRequest(val oldVersion: Long, val newVersion: Long)

fun Application.configureApi(repo: Repository) {
    install(ContentNegotiation) { json(repo.db.json) }
    install(StatusPages) {
        exception<Repository.VersionConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("VERSION_CONFLICT", cause.message))
        }
        exception<Repository.RuleRejectException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ApiError("RULE_VIOLATION", reasons = cause.reasons))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", cause.message))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("BAD_STATE", cause.message))
        }
    }

    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok", "app" to "谱峰案卷")) }

        get("/api/versions") {
            val importByVersion = repo.importByVersion()
            call.respond(repo.db.listVersions().map { v ->
                VersionDto(v.id, v.parentId, v.type.name, v.label, v.editor, v.createdAt, importByVersion[v.id])
            })
        }

        get("/api/head") { call.respond(mapOf("head" to repo.db.headVersion())) }

        post("/api/ingest") {
            val req = call.receive<IngestRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val (vid, result) = repo.ingest(req.content, req.filename ?: "upload.csv", parent, req.editor)
            call.respond(
                HttpStatusCode.Created,
                IngestResponse(vid, result.importId, result.accepted, result.newPeaks,
                    result.quarantined.map { RowError(it.lineNo, it.raw, it.error) }),
            )
        }

        get("/api/imports/{id}/errors") {
            val id = call.parameters["id"]!!.toLong()
            call.respond(repo.importErrors(id).map { RowError(it.lineNo, it.raw, it.error) })
        }

        get("/api/view/{version}") {
            val version = call.parameters["version"]!!.toLong()
            call.respond(viewResponse(repo, version))
        }

        post("/api/annotations") {
            val req = call.receive<AnnotationRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val type = AnnotationType.valueOf(req.type.uppercase())
            val annotation = PeakAnnotation(type, req.peakKey, req.reason, req.replacementMz, req.replacementIntensity)
            val vid = repo.addAnnotation(parent, annotation, req.editor)
            call.respond(HttpStatusCode.Created, mapOf("versionId" to vid))
        }

        post("/api/calibration") {
            val req = call.receive<CalibrationRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val model = Engine.fitCalibration(req.points)
            val vid = repo.db.insertCalibrationVersion(parent, model, req.label ?: "校准修订（${req.points.size}点，RMS ${"%.2f".format(model.rmsPpm)} ppm）", req.editor)
            call.respond(HttpStatusCode.Created, CalibrationResult(vid, model.slope, model.intercept, model.rmsPpm))
        }

        post("/api/calibration/rollback") {
            val req = call.receive<RollbackRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val vid = repo.db.rollbackCalibration(parent, req.sourceVersion, req.editor)
            call.respond(HttpStatusCode.Created, mapOf("versionId" to vid))
        }

        post("/api/baseline") {
            val req = call.receive<BaselineRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val vid = repo.db.insertBaselineVersion(parent, BaselineParams(req.noiseFloor, req.relativeThreshold), "基线修订", req.editor)
            call.respond(HttpStatusCode.Created, mapOf("versionId" to vid))
        }

        post("/api/rules") {
            val req = call.receive<RulesRequest>()
            val parent = req.parent ?: repo.db.headVersion()
            val vid = repo.db.insertRulesVersion(parent, req.rules, "规则升级 v${req.rules.version}", req.editor)
            call.respond(HttpStatusCode.Created, mapOf("versionId" to vid))
        }

        get("/api/rules/{version}") {
            val version = call.parameters["version"]!!.toLong()
            val rules = repo.db.latestRules(repo.db.lineage(version))
                ?: throw IllegalStateException("该版本没有规则配置")
            call.respond(rules.first)
        }

        post("/api/adjudicate") {
            val req = call.receive<AdjudicateRequest>()
            val vid = repo.adjudicate(req.baseVersion, req.candidateId, req.decision, req.editor, req.reason)
            call.respond(HttpStatusCode.Created, mapOf("versionId" to vid))
        }

        get("/api/history/{version}") {
            val version = call.parameters["version"]!!.toLong()
            call.respond(repo.adjudicateHistory(version).map {
                HistoryDto(it.versionId, it.candidateId, it.candidateHash, it.decision, it.editor, it.reason, it.createdAt)
            })
        }

        post("/api/compare") {
            val req = call.receive<CompareRequest>()
            val old = repo.buildView(req.oldVersion)
            val new = repo.buildView(req.newVersion)
            call.respond(Compare.compare(old, new, emptySet(), emptySet()))
        }
    }
}

@Serializable
data class CalibrationResult(val versionId: Long, val slope: Double, val intercept: Double, val rmsPpm: Double)

@Serializable
data class CalibrationDto(val slope: Double, val intercept: Double, val rmsPpm: Double, val configVersion: Int, val points: List<CalibrationPoint>)

@Serializable
data class PeakDto(val peakKey: String, val mz: Double, val rawMz: Double, val intensity: Double, val scan: Double, val polarity: String, val contaminant: Boolean)

@Serializable
data class ConflictDto(val candidateA: String, val candidateB: String, val compoundA: String, val compoundB: String, val sharedPeakKeys: List<String>)

@Serializable
data class ViewResponse(
    val versionId: Long,
    val calibration: CalibrationDto,
    val baseline: BaselineParams,
    val rulesVersion: Int,
    val peaks: List<PeakDto>,
    val candidates: List<CandidateView>,
    val conflicts: List<ConflictDto>,
)

private fun viewResponse(repo: Repository, version: Long): ViewResponse {
    val view = repo.buildView(version)
    return ViewResponse(
        versionId = view.versionId,
        calibration = CalibrationDto(
            view.calibration.slope, view.calibration.intercept, view.calibration.rmsPpm,
            view.calibrationVersion, view.calibration.points,
        ),
        baseline = view.baseline,
        rulesVersion = view.rules.version,
        peaks = view.peaks.map {
            PeakDto(it.peakKey, it.mz, it.rawMz, it.intensity, it.scan, it.polarity.name, it.contaminant)
        },
        candidates = view.candidates,
        conflicts = view.conflicts.map {
            ConflictDto(it.candidateA, it.candidateB, it.compounds.first, it.compounds.second, it.minimalSharedSet)
        },
    )
}
