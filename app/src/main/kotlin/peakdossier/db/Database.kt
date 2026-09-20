package peakdossier.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import peakdossier.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

class Database(private val path: Path) {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    val conn: Connection = run {
        Files.createDirectories(path.toAbsolutePath().parent ?: Path.of("."))
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    }

    init {
        conn.createStatement().use { st ->
            Schema.SQL.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { st.execute(it) }
        }
    }

    fun close() = conn.close()

    private var txDepth = 0

    /** 所有写操作都包在单个事务中；支持重入。中断时回滚，绝不留下半条记录。 */
    fun <T> tx(action: () -> T): T {
        if (txDepth > 0) return action()
        txDepth = 1
        conn.autoCommit = false
        try {
            val out = action()
            conn.commit()
            return out
        } catch (t: Throwable) {
            try { conn.rollback() } catch (_: Exception) {}
            throw t
        } finally {
            txDepth = 0
            conn.autoCommit = true
        }
    }

    fun now(): String = Instant.now().toString()

    // ---------- 版本谱系 ----------

    fun insertVersion(parentId: Long?, type: VersionType, label: String, editor: String): Long {
        conn.prepareStatement(
            "INSERT INTO version(parent_id,type,label,editor,created_at) VALUES(?,?,?,?,?)"
        ).apply {
            if (parentId == null) setNull(1, java.sql.Types.INTEGER) else setLong(1, parentId)
            setString(2, type.name); setString(3, label); setString(4, editor); setString(5, now())
        }.use { it.executeUpdate() }
        return queryId("SELECT last_insert_rowid()")
    }

    fun versionExists(id: Long): Boolean =
        conn.prepareStatement("SELECT 1 FROM version WHERE id=?").apply { setLong(1, id) }
            .executeQuery().use { it.next() }

    fun headVersion(): Long = queryId("SELECT COALESCE(MAX(id),0) FROM version")

    fun lineage(versionId: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var cur: Long? = versionId
        while (cur != null) {
            chain += cur
            cur = conn.prepareStatement("SELECT parent_id FROM version WHERE id=?").apply {
                setLong(1, cur)
            }.executeQuery().use {
                if (!it.next()) null else { val v = it.getLong(1); if (it.wasNull()) null else v }
            }
        }
        return chain.reversed()
    }

    fun listVersions(): List<DossierVersion> =
        conn.createStatement().executeQuery(
            """SELECT v.id,v.parent_id,v.type,v.label,v.editor,v.created_at
              FROM version v ORDER BY v.id"""
        ).use { rs ->
            buildList {
                while (rs.next()) add(readVersion(rs))
            }
        }

    fun readVersion(rs: ResultSet): DossierVersion = DossierVersion(
        id = rs.getLong(1),
        parentId = rs.getLong(2).let { if (rs.wasNull()) null else it },
        type = VersionType.valueOf(rs.getString(3)),
        label = rs.getString(4),
        editor = rs.getString(5),
        createdAt = rs.getString(6),
        payloadRef = null,
    )

    fun getVersion(id: Long): DossierVersion? =
        conn.prepareStatement("SELECT id,parent_id,type,label,editor,created_at FROM version WHERE id=?")
            .apply { setLong(1, id) }.executeQuery().use {
                if (it.next()) readVersion(it) else null
            }

    private fun queryId(sql: String): Long =
        conn.createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }

    // ---------- 配置读取（沿谱系） ----------

    fun latestCalibration(chain: List<Long>): Pair<CalibrationModel, Int>? {
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT slope,intercept,rms_ppm,points_json,version_id FROM config_calibration WHERE version_id IN ($set) ORDER BY version_id DESC LIMIT 1"
        )
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        st.executeQuery().use { rs ->
            if (!rs.next()) return null
            val pts = json.decodeFromString<List<CalibrationPoint>>(rs.getString(4))
            return CalibrationModel(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), pts) to rs.getInt(5)
        }
    }

    fun latestBaseline(chain: List<Long>): BaselineParams? {
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT noise_floor,relative_threshold FROM config_baseline WHERE version_id IN ($set) ORDER BY version_id DESC LIMIT 1"
        )
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        st.executeQuery().use { rs ->
            if (!rs.next()) return null
            return BaselineParams(rs.getDouble(1), rs.getDouble(2))
        }
    }

    fun latestRules(chain: List<Long>): Pair<RuleConfig, Int>? {
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT rules_json,rules_version,version_id FROM config_rules WHERE version_id IN ($set) ORDER BY version_id DESC LIMIT 1"
        )
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        st.executeQuery().use { rs ->
            if (!rs.next()) return null
            return json.decodeFromString<RuleConfig>(rs.getString(1)) to rs.getInt(3)
        }
    }

    fun insertCalibrationVersion(parent: Long, model: CalibrationModel, label: String, editor: String): Long = tx {
        val vid = insertVersion(parent, VersionType.CALIBRATION, label, editor)
        conn.prepareStatement("INSERT INTO config_calibration(version_id,slope,intercept,rms_ppm,points_json) VALUES(?,?,?,?,?)")
            .apply {
                setLong(1, vid); setDouble(2, model.slope); setDouble(3, model.intercept)
                setDouble(4, model.rmsPpm); setString(5, json.encodeToString(model.points))
            }.executeUpdate()
        vid
    }

    fun insertBaselineVersion(parent: Long, params: BaselineParams, label: String, editor: String): Long = tx {
        val vid = insertVersion(parent, VersionType.BASELINE, label, editor)
        conn.prepareStatement("INSERT INTO config_baseline(version_id,noise_floor,relative_threshold) VALUES(?,?,?)")
            .apply {
                setLong(1, vid); setDouble(2, params.noiseFloor); setDouble(3, params.relativeThreshold)
            }.executeUpdate()
        vid
    }

    fun insertRulesVersion(parent: Long, rules: RuleConfig, label: String, editor: String): Long = tx {
        val vid = insertVersion(parent, VersionType.RULES, label, editor)
        conn.prepareStatement("INSERT INTO config_rules(version_id,rules_json,rules_version) VALUES(?,?,?)")
            .apply {
                setLong(1, vid); setString(2, json.encodeToString(rules)); setInt(3, rules.version)
            }.executeUpdate()
        vid
    }

    /** 校准回滚：从旧校准版本派生一个新版本（分支），内容指向旧模型。 */
    fun rollbackCalibration(parent: Long, sourceVersion: Long, editor: String): Long {
        val model = latestCalibration(listOf(sourceVersion))
            ?: throw IllegalArgumentException("版本 $sourceVersion 上没有校准可回滚")
        return insertCalibrationVersion(parent, model.first, "校准回滚到 v$sourceVersion", editor)
    }
}
