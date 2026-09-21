package dossier

import dossier.api.dossierApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpStaleTest {
    @Test
    fun staleAdjudicationReturns409() = testApplication {
        val (services, _) = newHarness()
        application { dossierApp(services) }
        val client = createClient { }
        // import + run
        client.post("/api/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"branchId":"main","content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), SampleData.caffeine)}}""")
        }
        val runResp = client.post("/api/runs") {
            contentType(ContentType.Application.Json)
            setBody("""{"branchId":"main"}""")
        }
        val runJson = kotlinx.serialization.json.Json.parseToJsonElement(runResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val head = (runJson["version"] as kotlinx.serialization.json.JsonObject)["versionId"]!!.toString().trim('"')
        val cands = (runJson["run"] as kotlinx.serialization.json.JsonObject)["candidates"] as kotlinx.serialization.json.JsonArray
        val cid = ((cands[0] as kotlinx.serialization.json.JsonObject)["id"]!!).toString().trim('"')

        val first = client.post("/api/decisions") {
            contentType(ContentType.Application.Json)
            setBody("""{"branchId":"main","candidateId":"$cid","editor":"alice","action":"accept","rationale":"ok","baseVersionId":"$head"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post("/api/decisions") {
            contentType(ContentType.Application.Json)
            setBody("""{"branchId":"main","candidateId":"$cid","editor":"bob","action":"reject","rationale":"stale","baseVersionId":"$head"}""")
        }
        println("STATUS=${second.status} BODY=${second.bodyAsText()}")
        assertEquals(HttpStatusCode.Conflict, second.status)
    }
}
