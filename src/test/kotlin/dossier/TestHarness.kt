package dossier

import dossier.api.AppServices
import dossier.engine.Store
import java.nio.file.Files
import java.nio.file.Path

fun newHarness(): Pair<AppServices, Path> {
    val dir = Files.createTempDirectory("dossier-test")
    val dbPath = dir.resolve("test.sqlite")
    val services = AppServices(dbPath)
    return services to dir
}

object SampleData {
    // 咖啡因 [M+H]+ m/z 195.08765，带 M+1 / M+2 同位素；葡萄糖 [M+Na]+ 203.0526
    val caffeine = """
        195.0871,120000,120.5,pos,101
        196.0905,9600,120.5,pos,101
        197.0899,520,120.6,pos,101
        217.0690,88000,120.7,pos,102
        218.0724,7000,120.7,pos,102
        193.0510,70000,130.2,neg,201
        194.0543,5400,130.2,neg,201
        207.1379,40000,140.0,pos,301
        208.1412,3200,140.0,pos,301
    """.trimIndent()

    const val caffeineMH = 195.087651
}
