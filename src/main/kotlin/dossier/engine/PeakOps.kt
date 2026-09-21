package dossier.engine

import dossier.db.Database
import dossier.model.AnalysisConfig

/** 污染标记、过饱和峰拆分、校准点修订/回滚。所有动作只追加到指定分支的新版本上。 */
class PeakOps(private val db: Database, private val store: Store) {

    fun markContaminant(branchId: String, peakId: String, note: String, editor: String = "analyst"): String {
        val payload = """{"peakId":"$peakId","note":${quote(note)}}"""
        return store.appendVersion(branchId, "MARK_CONTAMINANT", editor, payload)
    }

    fun unmarkContaminant(branchId: String, peakId: String, editor: String = "analyst"): String =
        store.appendVersion(branchId, "UNMARK_CONTAMINANT", editor, """{"peakId":"$peakId"}""")

    /**
     * 拆分过饱和峰：用研究员给出的 m/z、rt、强度分量替换原峰，生成两个新的稳定峰身份。
     * 原峰在该版本起从状态中移除，只影响该分支的后续版本。
     */
    fun splitPeak(
        branchId: String,
        peakId: String,
        parts: List<Triple<Double, Double, Double>>,
        editor: String = "analyst",
    ): String {
        require(parts.size >= 2) { "拆分至少需要两个分量" }
        return db.tx { conn ->
            val state = store.materialize(conn, store.headVersion(branchId))
            val target = state.peaks[peakId] ?: error("峰不存在: $peakId")
            val newIds = parts.map { Store.newId("pk") }
            for ((idx, nid) in newIds.withIndex()) {
                val (mz, rt, intensity) = parts[idx]
                conn.prepareStatement(
                    "INSERT INTO observed_peaks(id,polarity,identity_mz,first_version_id) VALUES(?,?,?,?)"
                ).use { ps ->
                    ps.setString(1, nid); ps.setString(2, target.peak.polarity.name)
                    ps.setDouble(3, mz); ps.setString(4, ""); ps.executeUpdate()
                }
            }
            val mzs = parts.joinToString(",") { it.first.toString() }
            val rts = parts.joinToString(",") { it.second.toString() }
            val ints = parts.joinToString(",") { it.third.toString() }
            val ids = newIds.joinToString(",") { "\"$it\"" }
            val payload = """{"peakId":"$peakId","newPeakIds":[$ids],"newRawMzs":[$mzs],"newRts":[$rts],"newIntensities":[$ints]}"""
            val versionId = store.appendVersionOn(conn, branchId, "SPLIT_PEAK", editor, payload)
            conn.prepareStatement("UPDATE observed_peaks SET first_version_id=? WHERE id IN ($ids)").use {
                it.setString(1, versionId); it.executeUpdate()
            }
            versionId
        }
    }

    /** 修订校准点：替换配置中的点集，沿当前分支产生新版本并重算该版本所有校准 m/z。 */
    fun reviseCalibration(
        branchId: String,
        points: List<dossier.model.CalibrationPoint>,
        editor: String = "analyst",
    ): String {
        val pointsJson = points.joinToString(",") {
            """{"measuredMz":${it.measuredMz},"theoreticalMz":${it.theoreticalMz}}"""
        }
        return store.appendVersion(branchId, "CALIBRATION_POINT", editor, """{"points":[$pointsJson]}""")
    }

    /** 校准回滚：恢复到指定配置版本中的校准点集。 */
    fun rollbackCalibration(branchId: String, targetConfigVersion: Int, editor: String = "analyst"): String {
        val config: AnalysisConfig = store.config(targetConfigVersion)
        val pointsJson = config.calibrationPoints.joinToString(",") {
            """{"measuredMz":${it.measuredMz},"theoreticalMz":${it.theoreticalMz}}"""
        }
        return store.appendVersion(
            branchId, "ROLLBACK_CALIBRATION", editor,
            """{"points":[$pointsJson],"restoredFromConfigVersion":$targetConfigVersion}"""
        )
    }

    /** 规则（候选规则/基线参数）升级：创建新配置版本并在分支上挂一个 CONFIG_UPDATED 事件（同一事务）。 */
    fun upgradeConfig(branchId: String, config: AnalysisConfig, editor: String = "analyst"): Pair<Int, String> = db.tx { conn ->
        val newConfigVersion = store.createConfigOn(conn, config, editor)
        val versionId = store.appendVersionOn(
            conn, branchId, "CONFIG_UPDATED", editor,
            """{"configVersion":$newConfigVersion}""", newConfigVersion
        )
        newConfigVersion to versionId
    }

    private fun quote(s: String): String =
        Database.json.encodeToString(kotlinx.serialization.serializer<String>(), s)
}
