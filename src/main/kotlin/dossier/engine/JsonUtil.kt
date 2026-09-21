package dossier.engine

import dossier.model.CalibrationPoint
import kotlinx.serialization.json.*

object JsonUtil {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    fun stringField(s: String, name: String): String = parse(s).getValue(name).jsonPrimitive.content

    fun optStringField(s: String, name: String): String? =
        parse(s)[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    fun doubleListField(s: String, name: String): List<Double> =
        parse(s)[name]!!.jsonArray.map { it.jsonPrimitive.double }

    fun stringListField(s: String, name: String): List<String> =
        parse(s)[name]!!.jsonArray.map { it.jsonPrimitive.content }

    fun calibrationPoints(s: String): List<CalibrationPoint> =
        parse(s)["points"]?.jsonArray?.map {
            val o = it.jsonObject
            CalibrationPoint(o.getValue("measuredMz").jsonPrimitive.double, o.getValue("theoreticalMz").jsonPrimitive.double)
        } ?: emptyList()

    fun calibrationPointsFromConfig(s: String): List<CalibrationPoint> = calibrationPoints(s)
}
