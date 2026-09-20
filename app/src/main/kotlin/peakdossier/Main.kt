package peakdossier

import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import peakdossier.db.Database
import peakdossier.db.Repository
import peakdossier.db.Seed
import peakdossier.web.configureApi
import java.nio.file.Path

data class CliArgs(val host: String = "127.0.0.1", val port: Int = 5236, val db: String = "data/peakdossier.db")

fun parseArgs(argv: Array<String>): CliArgs {
    var host = "127.0.0.1"
    var port = 5236
    var db = "data/peakdossier.db"
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--host" -> host = argv[++i]
            "--port" -> port = argv[++i].toInt()
            "--db" -> db = argv[++i]
            else -> throw IllegalArgumentException("未知参数: ${argv[i]}")
        }
        i++
    }
    return CliArgs(host, port, db)
}

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val database = Database(Path.of(args.db))
    val repo = Repository(database)
    Seed.ensureSeeded(repo)

    embeddedServer(Netty, host = args.host, port = args.port) {
        configureApi(repo)
        routing {
            get("/") {
                call.respondRedirect("/index.html", permanent = false)
            }
            get("/{static...}") {
                val rel = call.parameters.getAll("static")?.joinToString("/") ?: "index.html"
                val resourcePath = if (rel.contains("..")) null else "web/$rel"
                val stream = resourcePath?.let { object {}.javaClass.classLoader.getResourceAsStream(it) }
                if (stream != null) {
                    val contentType = when {
                        rel.endsWith(".html") -> ContentType.Text.Html
                        rel.endsWith(".js") -> ContentType.Application.JavaScript
                        rel.endsWith(".css") -> ContentType.Text.CSS
                        else -> ContentType.Application.OctetStream
                    }
                    call.respondOutputStream(contentType, HttpStatusCode.OK) {
                        stream.use { it.copyTo(this) }
                    }
                } else if (!rel.startsWith("api/")) {
                    val index = object {}.javaClass.classLoader.getResourceAsStream("web/index.html")
                    if (index != null) {
                        call.respondOutputStream(ContentType.Text.Html, HttpStatusCode.OK) {
                            index.use { it.copyTo(this) }
                        }
                    } else call.respond(HttpStatusCode.NotFound, "not found")
                } else call.respond(HttpStatusCode.NotFound, "not found")
            }
        }
    }.start(wait = true)
}
