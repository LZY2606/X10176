package dossier.api

import dossier.db.Database
import dossier.engine.*
import dossier.model.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import java.nio.file.Path

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String, val conflict: Boolean = false, val sharedPeakIds: List<String> = emptyList(), val type: String? = null)

class AppServices(dbPath: Path) {
    val db = Database(dbPath)
    val store = Store(db).also { it.initMainBranch() }
    val importer = Importer(db, store)
    val generator = CandidateGenerator(db, store)
    val adjudicator = Adjudicator(db, store)
    val peakOps = PeakOps(db, store)
    val comparator = VersionComparatorEngine(db, store)
    val query = QueryService(db, store, adjudicator)
}

fun Application.dossierApp(services: AppServices) {
    install(ContentNegotiation) {
        json(Database.json)
    }
    install(CallLogging)
    install(StatusPages) {
        exception<AdjudicationException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.reason, true, cause.sharedPeakIds))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad state"))
        }
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "internal", type = cause::class.qualifiedName ?: "Throwable"))
        }
    }

    routing {
        staticResources("/", "web") {
            default("index.html")
        }

        get("/api/health") { call.respond(kotlinx.serialization.json.buildJsonObject { put("status", kotlinx.serialization.json.JsonPrimitive("ok")); put("app", kotlinx.serialization.json.JsonPrimitive("谱峰案卷")) }) }

        get("/api/branches") { call.respond(services.store.listBranches()) }
        post("/api/branches") {
            val req = call.receive<BranchRequest>()
            call.respond(HttpStatusCode.Created, services.store.createBranch(req.name, req.fromVersionId))
        }
        get("/api/branches/{branch}/versions") {
            call.respond(services.query.versions(call.parameters["branch"]!!))
        }
        get("/api/branches/{branch}/view") {
            val v = call.request.queryParameters["versionId"]
            call.respond(services.query.versionView(call.parameters["branch"]!!, v))
        }

        post("/api/import") {
            val req = call.receive<ImportRequest>()
            call.respond(HttpStatusCode.Created, services.importer.import(req.branchId, req.content, req.editor))
        }

        post("/api/peaks/contaminant") {
            val req = call.receive<ContaminantRequest>()
            val vid = services.peakOps.markContaminant(req.branchId, req.peakId, req.note, req.editor)
            call.respond(EventAck(req.branchId, vid, services.store.getVersion(vid).seq))
        }
        post("/api/peaks/unmark-contaminant") {
            val req = call.receive<ContaminantRequest>()
            val vid = services.peakOps.unmarkContaminant(req.branchId, req.peakId, req.editor)
            call.respond(EventAck(req.branchId, vid, services.store.getVersion(vid).seq))
        }
        post("/api/peaks/split") {
            val req = call.receive<SplitRequest>()
            val parts = req.parts.map { Triple(it.rawMz, it.rtSeconds, it.intensity) }
            val vid = services.peakOps.splitPeak(req.branchId, req.peakId, parts, req.editor)
            call.respond(EventAck(req.branchId, vid, services.store.getVersion(vid).seq))
        }

        post("/api/calibration") {
            val req = call.receive<CalibrationRequest>()
            val vid = services.peakOps.reviseCalibration(req.branchId, req.points, req.editor)
            call.respond(EventAck(req.branchId, vid, services.store.getVersion(vid).seq))
        }
        post("/api/calibration/rollback") {
            val req = call.receive<RollbackRequest>()
            val vid = services.peakOps.rollbackCalibration(req.branchId, req.targetConfigVersion, req.editor)
            call.respond(EventAck(req.branchId, vid, services.store.getVersion(vid).seq))
        }
        get("/api/config/{version}") {
            val v = call.parameters["version"]!!.toInt()
            call.respond(services.store.config(v))
        }
        get("/api/config/latest") {
            call.respond(services.store.config(services.store.latestConfigVersion()))
        }
        post("/api/config") {
            val req = call.receive<ConfigUpgradeRequest>()
            val (newVersion, vid) = services.peakOps.upgradeConfig(req.branchId, req.config, req.editor)
            call.respond(HttpStatusCode.Created, kotlinx.serialization.json.buildJsonObject {
                put("configVersion", kotlinx.serialization.json.JsonPrimitive(newVersion))
                put("branchId", kotlinx.serialization.json.JsonPrimitive(req.branchId))
                put("versionId", kotlinx.serialization.json.JsonPrimitive(vid))
                put("seq", kotlinx.serialization.json.JsonPrimitive(services.store.getVersion(vid).seq))
            })
        }

        post("/api/runs") {
            val req = call.receive<RunRequest>()
            val gen = services.generator.generate(req.branchId, req.baseVersionId ?: services.store.headVersion(req.branchId))
            val state = services.store.getVersion(gen.versionId)
            val run = services.query.versionView(req.branchId, gen.versionId).runs.last { it.id == gen.runId }
            call.respond(HttpStatusCode.Created, RunResponse(run, EventAck(req.branchId, gen.versionId, state.seq)))
        }

        post("/api/decisions") {
            val req = call.receive<AdjudicationRequest>()
            val result = services.adjudicator.adjudicate(
                req.branchId, req.candidateId, req.editor, req.action, req.rationale, req.baseVersionId
            )
            val seq = services.store.getVersion(result.versionId).seq
            call.respond(HttpStatusCode.Created, EventAck(req.branchId, result.versionId, seq))
        }
        get("/api/branches/{branch}/decisions") {
            call.respond(services.adjudicator.decisionHistory(call.parameters["branch"]!!))
        }
        get("/api/branches/{branch}/conflicts") {
            call.respond(services.adjudicator.conflictQueue(call.parameters["branch"]!!))
        }
        post("/api/compare") {
            val req = call.receive<CompareRequest>()
            call.respond(services.comparator.compare(req.fromVersionId, req.toVersionId))
        }
    }
}

fun startServer(services: AppServices, host: String, port: Int) {
    embeddedServer(Netty, host = host, port = port) {
        dossierApp(services)
    }.start(wait = true)
}
