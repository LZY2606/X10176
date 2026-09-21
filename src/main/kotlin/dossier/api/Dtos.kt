package dossier.api

import dossier.model.CalibrationPoint
import kotlinx.serialization.Serializable

@Serializable
data class ImportRequest(val branchId: String = "main", val content: String, val editor: String = "analyst")

@Serializable
data class BranchRequest(val name: String, val fromVersionId: String, val editor: String = "analyst")

@Serializable
data class ContaminantRequest(val branchId: String = "main", val peakId: String, val note: String = "", val editor: String = "analyst")

@Serializable
data class SplitPart(val rawMz: Double, val rtSeconds: Double, val intensity: Double)

@Serializable
data class SplitRequest(
    val branchId: String = "main",
    val peakId: String,
    val parts: List<SplitPart>,
    val editor: String = "analyst",
)

@Serializable
data class CalibrationRequest(
    val branchId: String = "main",
    val points: List<CalibrationPoint>,
    val editor: String = "analyst",
)

@Serializable
data class RollbackRequest(
    val branchId: String = "main",
    val targetConfigVersion: Int,
    val editor: String = "analyst",
)

@Serializable
data class ConfigUpgradeRequest(
    val branchId: String = "main",
    val config: dossier.model.AnalysisConfig,
    val editor: String = "analyst",
)

@Serializable
data class RunRequest(val branchId: String = "main", val baseVersionId: String? = null, val editor: String = "analyst")

@Serializable
data class AdjudicationRequest(
    val branchId: String = "main",
    val candidateId: String,
    val editor: String = "analyst",
    val action: String,
    val rationale: String = "",
    val baseVersionId: String,
)

@Serializable
data class CompareRequest(val fromVersionId: String, val toVersionId: String)
