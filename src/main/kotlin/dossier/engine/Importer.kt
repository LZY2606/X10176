package dossier.engine

import dossier.db.Database
import dossier.model.ImportResult
import dossier.model.ImportRowError
import dossier.model.Polarity
import dossier.chem.Chemistry
import java.security.MessageDigest
import java.time.Instant

data class ParsedRow(
    val lineNo: Int,
    val mz: Double,
    val rt: Double,
    val intensity: Double,
    val polarity: Polarity,
    val scan: Int,
    val raw: String,
)

class Importer(private val db: Database, private val store: Store) {

    fun parse(content: String): Pair<List<ParsedRow>, List<ImportRowError>> {
        val ok = mutableListOf<ParsedRow>()
        val errors = mutableListOf<ImportRowError>()
        val lines = content.split("\n")
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            try {
                if (idx == 0 && looksHeader(trimmed)) return@forEachIndexed
                val parts = trimmed.split(",", "\t").map { it.trim() }
                require(parts.size >= 4) { "需要至少 mz,intensity,rt,polarity 4 列" }
                val mz = parts[0].toDouble()
                val intensity = parts[1].toDouble()
                val rt = parts[2].toDouble()
                val polarity = parsePolarity(parts[3])
                require(mz > 0.0) { "m/z 必须为正数" }
                require(intensity >= 0.0) { "强度不能为负" }
                require(rt >= 0.0) { "扫描时间不能为负" }
                val scan = parts.getOrNull(4)?.toIntOrNull() ?: lineNo
                ok += ParsedRow(lineNo, mz, rt, intensity, polarity, scan, trimmed)
            } catch (t: Throwable) {
                errors += ImportRowError(lineNo, trimmed, t.message ?: "解析失败")
            }
        }
        return ok to errors
    }

    private fun looksHeader(line: String): Boolean {
        val first = line.split(",", "\t").firstOrNull()?.trim()?.lowercase().orEmpty()
        return first in setOf("mz", "m/z", "mass")
    }

    private fun parsePolarity(s: String): Polarity = when (s.lowercase()) {
        "pos", "positive", "+", "1" -> Polarity.POSITIVE
        "neg", "negative", "-", "-1" -> Polarity.NEGATIVE
        else -> throw IllegalArgumentException("无法识别的极性: $s")
    }

    /**
     * 批量导入：损坏行被隔离，其余照常进入。整个导入与版本追加在同一个
     * SQLite 事务中提交；中断时已提交的峰版本不会出现半条记录。
     */
    fun import(branchId: String, content: String, editor: String = "analyst"): ImportResult {
        val (rows, errors) = parse(content)
        val importId = Store.newId("imp")
        val digest = sha256(rows.joinToString("|") { "${it.mz},${it.intensity},${it.rt},${it.polarity.name},${it.scan}" })

        return db.tx { conn ->
            val state = store.materialize(conn, store.headVersionOn(conn, branchId))
            val config = store.config(state.configVersion)
            val binPpm = config.mzIdentityBinPpm

            val insertRaw = conn.prepareStatement(
                "INSERT INTO raw_rows(import_id,line_no,content_digest,mz,rt,intensity,polarity,scan,peak_id,quarantined,error,raw_line) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,0,NULL,?)"
            )
            val insertQuar = conn.prepareStatement(
                "INSERT INTO raw_rows(import_id,line_no,content_digest,quarantined,error,raw_line) VALUES(?,?,?,1,?,?)"
            )
            val insertPeak = conn.prepareStatement(
                "INSERT INTO observed_peaks(id,polarity,identity_mz,first_version_id) VALUES(?,?,?,?)"
            )
            // SQLite JDBC 每连接只允许一个活动 ResultSet：先把已有峰身份全部取出
            data class KnownPeak(val id: String, val polarity: Polarity, val identityMz: Double)
            val known = mutableListOf<KnownPeak>()
            conn.createStatement().executeQuery("SELECT id,polarity,identity_mz FROM observed_peaks").use { krs ->
                while (krs.next()) known += KnownPeak(krs.getString(1), Polarity.valueOf(krs.getString(2)), krs.getDouble(3))
            }
            val knownPeakIdsBefore = known.map { it.id }.toMutableSet()

            var newCount = 0
            var mergedCount = 0
            val usedPeakIds = mutableSetOf<String>()

            for (row in rows) {
                var matchId = known.firstOrNull {
                    it.polarity == row.polarity && Chemistry.closeIdentity(it.identityMz, row.mz, binPpm)
                }?.id
                if (matchId == null) {
                    matchId = Store.newId("pk")
                    insertPeak.clearParameters()
                    insertPeak.setString(1, matchId); insertPeak.setString(2, row.polarity.name)
                    insertPeak.setDouble(3, row.mz); insertPeak.setString(4, "")
                    insertPeak.executeUpdate()
                    known += KnownPeak(matchId, row.polarity, row.mz)
                    if (matchId !in usedPeakIds) newCount++
                } else if (matchId in knownPeakIdsBefore && matchId !in usedPeakIds) {
                    // 命中导入前已存在的稳定峰身份
                    mergedCount++
                }
                // 命中本批次内刚建出的峰（同批合并）既不是新峰也不是跨导入合并
                usedPeakIds += matchId
                insertRaw.clearParameters()
                insertRaw.setString(1, importId); insertRaw.setInt(2, row.lineNo)
                insertRaw.setString(3, sha256(row.raw)); insertRaw.setDouble(4, row.mz)
                insertRaw.setDouble(5, row.rt); insertRaw.setDouble(6, row.intensity)
                insertRaw.setString(7, row.polarity.name); insertRaw.setInt(8, row.scan)
                insertRaw.setString(9, matchId); insertRaw.setString(10, row.raw)
                insertRaw.addBatch()
            }
            for (q in errors) {
                insertQuar.clearParameters()
                insertQuar.setString(1, importId); insertQuar.setInt(2, q.line)
                insertQuar.setString(3, sha256(q.rawLine)); insertQuar.setString(4, q.error)
                insertQuar.setString(5, q.rawLine)
                insertQuar.addBatch()
            }
            insertRaw.executeBatch()
            insertQuar.executeBatch()

            val payload = """{"importId":"$importId","contentDigest":"$digest","accepted":${rows.size},"quarantined":${errors.size}}"""
            val versionId = store.appendVersionOn(conn, branchId, "IMPORT", editor, payload)
            conn.prepareStatement("UPDATE observed_peaks SET first_version_id=? WHERE id IN (" +
                    "SELECT DISTINCT peak_id FROM raw_rows WHERE import_id=?)").use { ps ->
                ps.setString(1, versionId); ps.setString(2, importId); ps.executeUpdate()
            }
            ImportResult(
                importId = importId,
                acceptedRows = rows.size,
                quarantinedRows = errors,
                newPeakCount = newCount,
                mergedPeakCount = mergedCount,
                contentDigest = digest,
                branchId = branchId,
                versionId = versionId,
            )
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
