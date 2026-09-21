package dossier.engine

import dossier.db.Database
import dossier.model.*
import dossier.chem.Chemistry
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class MaterializedPeak(
    val peak: ObservedPeak,
    val identityMz: Double,
)

data class VersionState(
    val versionId: String,
    val seq: Int,
    val parentVersionId: String?,
    val eventType: String,
    val editor: String,
    val createdAt: String,
    val configVersion: Int,
    val peaks: Map<String, MaterializedPeak>,
    val runIds: List<String>,
)

@kotlinx.serialization.Serializable
data class BranchInfo(
    val id: String,
    val name: String,
    val parentBranchId: String?,
    val forkedFromVersionId: String?,
    val headVersionId: String,
    val seq: Int,
    val createdAt: String,
)

class Store(private val db: Database) {

    fun initMainBranch() {
        db.tx { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM branches")
            rs.next()
            if (rs.getInt(1) == 0) {
                val now = Instant.now().toString()
                val vid = newId("v")
                conn.prepareStatement(
                    "INSERT INTO branches(id,name,parent_branch_id,forked_from_version_id,created_at) VALUES('main','main',NULL,NULL,?)"
                ).use { it.setString(1, now); it.executeUpdate() }
                conn.prepareStatement(
                    "INSERT INTO versions(id,branch_id,seq,parent_version_id,event_type,editor,payload,config_version,created_at) " +
                            "VALUES(?,'main',0,NULL,'ROOT','system','{}',1,?)"
                ).use {
                    it.setString(1, vid); it.setString(2, now); it.executeUpdate()
                }
            }
        }
    }

    fun listBranches(): List<BranchInfo> = db.readOnly { conn ->
        val result = mutableListOf<BranchInfo>()
        conn.createStatement().executeQuery(
            """SELECT b.*, v.id AS head_id, v.seq AS head_seq FROM branches b
               JOIN versions v ON v.id=(SELECT id FROM versions WHERE branch_id=b.id ORDER BY seq DESC LIMIT 1)"""
        ).use { rs ->
            while (rs.next()) {
                result += BranchInfo(
                    rs.getString("id"), rs.getString("name"),
                    rs.getString("parent_branch_id"), rs.getString("forked_from_version_id"),
                    rs.getString("head_id"), rs.getInt("head_seq"),
                    rs.getString("created_at")
                )
            }
        }
        result
    }

    fun headVersion(branchId: String): String = db.readOnly { conn -> headVersionOn(conn, branchId) }

    fun headVersionOn(conn: Connection, branchId: String): String =
        conn.prepareStatement("SELECT id FROM versions WHERE branch_id=? ORDER BY seq DESC LIMIT 1").use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            require(rs.next()) { "branch not found: $branchId" }
            rs.getString(1)
        }

    fun config(version: Int): AnalysisConfig = db.readOnly { conn ->
        conn.prepareStatement("SELECT config_json FROM configs WHERE version=?").use { ps ->
            ps.setInt(1, version)
            val rs = ps.executeQuery()
            require(rs.next()) { "config version not found: $version" }
            Database.json.decodeFromString(AnalysisConfig.serializer(), rs.getString(1))
        }
    }

    fun configVersionOf(versionId: String): Int = db.readOnly { conn ->
        conn.prepareStatement("SELECT config_version FROM versions WHERE id=?").use { ps ->
            ps.setString(1, versionId)
            val rs = ps.executeQuery()
            require(rs.next())
            rs.getInt(1)
        }
    }

    fun latestConfigVersion(): Int = db.readOnly { conn ->
        val rs = conn.createStatement().executeQuery("SELECT MAX(version) FROM configs")
        rs.next(); rs.getInt(1)
    }

    fun createConfig(config: AnalysisConfig, editor: String): Int = db.tx { conn -> createConfigOn(conn, config, editor) }

    fun createConfigOn(conn: Connection, config: AnalysisConfig, editor: String): Int {
        val newVersion = latestConfigVersionLocked(conn) + 1
        conn.prepareStatement(
            "INSERT INTO configs(version,config_json,editor,created_at,parent_version) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setInt(1, newVersion)
            ps.setString(2, Database.json.encodeToString(AnalysisConfig.serializer(), config))
            ps.setString(3, editor)
            ps.setString(4, Instant.now().toString())
            ps.setInt(5, newVersion - 1)
            ps.executeUpdate()
        }
        return newVersion
    }

    private fun latestConfigVersionLocked(conn: Connection): Int {
        val rs = conn.createStatement().executeQuery("SELECT MAX(version) FROM configs")
        rs.next(); return rs.getInt(1)
    }

    /** 追加一个版本事件，返回新版本号。整个写入在一个事务内：中断时不会出现半条记录。 */
    fun appendVersion(
        branchId: String,
        eventType: String,
        editor: String,
        payloadJson: String,
        configVersion: Int? = null,
    ): String = db.tx { conn ->
        appendVersionOn(conn, branchId, eventType, editor, payloadJson, configVersion)
    }

    /** 在调用方已开启的事务/连接上追加版本，保证与同一业务动作的其它写入一起原子提交。 */
    fun appendVersionOn(
        conn: Connection,
        branchId: String,
        eventType: String,
        editor: String,
        payloadJson: String,
        configVersion: Int? = null,
    ): String {
        val head = conn.prepareStatement(
            "SELECT id, seq, config_version FROM versions WHERE branch_id=? ORDER BY seq DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, branchId)
            val rs = ps.executeQuery()
            require(rs.next()) { "branch not found: $branchId" }
            Triple(rs.getString(1), rs.getInt(2), rs.getInt(3))
        }
        val newSeq = head.second + 1
        val newId = newId("v")
        val now = Instant.now().toString()
        conn.prepareStatement(
            "INSERT INTO versions(id,branch_id,seq,parent_version_id,event_type,editor,payload,config_version,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, newId); ps.setString(2, branchId); ps.setInt(3, newSeq)
            ps.setString(4, head.first); ps.setString(5, eventType); ps.setString(6, editor)
            ps.setString(7, payloadJson); ps.setInt(8, configVersion ?: head.third)
            ps.setString(9, now); ps.executeUpdate()
        }
        return newId
    }

    fun getVersion(versionId: String): VersionState = db.readOnly { conn -> materialize(conn, versionId) }

    /**
     * 沿 parent 链回放事件重建该版本状态。分支点状态会被物化到新版本，
     * 因此这些动作只影响从该版本继续的分支。
     */
    fun materialize(conn: Connection, versionId: String): VersionState {
        val chain = ArrayDeque<VersionRow>()
        var current: VersionRow? = loadVersionRow(conn, versionId)
        while (current != null) {
            chain.addFirst(current)
            current = current.parentVersionId?.let { loadVersionRow(conn, it) }
        }

        val peaks = LinkedHashMap<String, MaterializedPeak>()
        val runIds = mutableListOf<String>()
        var meta: VersionRow = chain.first()

        for (row in chain) {
            meta = row
            when (row.eventType) {
                "IMPORT" -> applyImport(conn, row, peaks)
                "MARK_CONTAMINANT" -> applyPeakStatus(row, peaks, "contaminant")
                "UNMARK_CONTAMINANT" -> applyPeakStatus(row, peaks, "active")
                "SPLIT_PEAK" -> applySplit(conn, row, peaks)
                "CALIBRATION_POINT" -> applyCalibration(conn, row, peaks)
                "ROLLBACK_CALIBRATION" -> applyCalibration(conn, row, peaks)
                "CONFIG_UPDATED" -> { /* 校准在回放时统一重算 */ }
                "RUN" -> runIds += JsonUtil.stringField(row.payload, "runId")
                "DECISION" -> { /* 不改变峰状态 */ }
                "ROOT" -> {}
            }
        }
        return VersionState(
            meta.id, meta.seq, meta.parentVersionId, meta.eventType,
            meta.editor, meta.createdAt, meta.configVersion, peaks, runIds
        )
    }

    private fun applyImport(conn: Connection, row: VersionRow, peaks: MutableMap<String, MaterializedPeak>) {
        val importId = JsonUtil.stringField(row.payload, "importId")
        data class Joined(
            val peakId: String, val identityMz: Double, val mz: Double, val rt: Double,
            val intensity: Double, val polarity: Polarity, val scan: Int,
        )
        val rows = mutableListOf<Joined>()
        conn.prepareStatement(
            "SELECT p.id, p.identity_mz, r.mz, r.rt, r.intensity, r.polarity, r.scan " +
                    "FROM raw_rows r JOIN observed_peaks p ON p.id=r.peak_id " +
                    "WHERE r.import_id=? AND r.quarantined=0"
        ).use { ps ->
            ps.setString(1, importId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                rows += Joined(
                    rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                    rs.getDouble(5), Polarity.valueOf(rs.getString(6)), rs.getInt(7),
                )
            }
        }
        val grouped = rows.groupBy { it.peakId }
        val config = config(row.configVersion)
        val transform = config.calibrationTransform()
        for ((pid, joined) in grouped) {
            val best = joined.maxBy { it.intensity }
            peaks[pid] = MaterializedPeak(
                ObservedPeak(
                    id = pid,
                    rawMz = best.mz,
                    calibratedMz = Chemistry.applyCalibration(best.mz, transform),
                    intensity = joined.sumOf { it.intensity },
                    rtSeconds = best.rt,
                    polarity = best.polarity,
                    scans = joined.map { it.scan }.distinct().sorted(),
                    status = "active"
                ),
                best.identityMz
            )
        }
    }

    private fun applyPeakStatus(row: VersionRow, peaks: MutableMap<String, MaterializedPeak>, status: String) {
        val peakId = JsonUtil.stringField(row.payload, "peakId")
        val note = JsonUtil.optStringField(row.payload, "note")
        peaks[peakId]?.let { mp ->
            peaks[peakId] = mp.copy(peak = mp.peak.copy(status = status, note = note ?: mp.peak.note))
        }
    }

    private fun applySplit(conn: Connection, row: VersionRow, peaks: MutableMap<String, MaterializedPeak>) {
        val oldPeakId = JsonUtil.stringField(row.payload, "peakId")
        val mzs = JsonUtil.doubleListField(row.payload, "newRawMzs")
        val rts = JsonUtil.doubleListField(row.payload, "newRts")
        val intensities = JsonUtil.doubleListField(row.payload, "newIntensities")
        val newIds = JsonUtil.stringListField(row.payload, "newPeakIds")
        val old = peaks.remove(oldPeakId) ?: return
        val config = config(row.configVersion)
        val transform = config.calibrationTransform()
        newIds.forEachIndexed { idx, nid ->
            peaks[nid] = MaterializedPeak(
                ObservedPeak(
                    id = nid,
                    rawMz = mzs[idx],
                    calibratedMz = Chemistry.applyCalibration(mzs[idx], transform),
                    intensity = intensities[idx],
                    rtSeconds = rts[idx],
                    polarity = old.peak.polarity,
                    scans = old.peak.scans,
                    status = "split"
                ),
                mzs[idx]
            )
        }
    }

    private fun applyCalibration(conn: Connection, row: VersionRow, peaks: MutableMap<String, MaterializedPeak>) {
        val points = JsonUtil.calibrationPoints(row.payload)
        val transform = Chemistry.linearFit(points)
        for ((id, mp) in peaks) {
            peaks[id] = mp.copy(peak = mp.peak.copy(calibratedMz = Chemistry.applyCalibration(mp.peak.rawMz, transform)))
        }
    }

    fun createBranch(name: String, fromVersionId: String): BranchInfo = db.tx { conn ->
        val base = loadVersionRow(conn, fromVersionId)
        val newId = newId("b")
        val now = Instant.now().toString()
        conn.prepareStatement(
            "INSERT INTO branches(id,name,parent_branch_id,forked_from_version_id,created_at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, newId); ps.setString(2, name)
            ps.setString(3, base.branchId); ps.setString(4, fromVersionId)
            ps.setString(5, now); ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO versions(id,branch_id,seq,parent_version_id,event_type,editor,payload,config_version,created_at) " +
                    "VALUES(?,? ,0,?,'FORK','system',? ,?,?)"
        ).use { ps ->
            val vid = newId("v")
            ps.setString(1, vid); ps.setString(2, newId); ps.setString(3, fromVersionId)
            ps.setString(4, "{\"forkedFrom\":\"$fromVersionId\"}")
            ps.setInt(5, base.configVersion); ps.setString(6, now)
            ps.executeUpdate()
            BranchInfo(newId, name, base.branchId, fromVersionId, vid, 0, now)
        }
    }

    private fun loadVersionRow(conn: Connection, id: String): VersionRow {
        conn.prepareStatement("SELECT * FROM versions WHERE id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            require(rs.next()) { "version not found: $id" }
            return VersionRow(
                rs.getString("id"), rs.getString("branch_id"), rs.getInt("seq"),
                rs.getString("parent_version_id"), rs.getString("event_type"),
                rs.getString("editor"), rs.getString("payload"),
                rs.getInt("config_version"), rs.getString("created_at")
            )
        }
    }

    companion object {
        fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"
    }
}

data class VersionRow(
    val id: String,
    val branchId: String,
    val seq: Int,
    val parentVersionId: String?,
    val eventType: String,
    val editor: String,
    val payload: String,
    val configVersion: Int,
    val createdAt: String,
)

private data class RawPick(val mz: Double, val rt: Double, val intensity: Double, val polarity: Polarity, val scan: Int)
