package dossier

import dossier.api.AppServices
import dossier.api.startServer
import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5236
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            else -> i += 1
        }
    }
    val services = AppServices(Paths.get("data", "dossier.sqlite"))
    startServer(services, host, port)
}
