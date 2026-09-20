package peakdossier.db

import peakdossier.domain.*
import java.sql.Statement
import java.security.MessageDigest

class Repository(val db: Database) {

    private val conn get() = db.conn
    private val json get() = db.json

    // ---------- 导入：部分失败隔离，整批一个事务 ----------

    fun ingest(content: String, filename: String, parent: Long, editor: String): Pair<Long, IngestResult> {
        val parsed = CsvIngest.parse(content)
        return db.tx {
            val importStmt = conn.prepareStatement(
                "INSERT INTO import_run(filename,imported_at,accepted_count,quarantined_count) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            )
            importStmt.setString(1, filename)
            importStmt.setString(2, db.now())
            importStmt.setInt(3, parsed.peaks.size)
            importStmt.setInt(4, parsed.errors.size)
            importStmt.executeUpdate()
            val importId = importStmt.generatedKeys.use { it.next(); it.getLong(1) }

            val errStmt = conn.prepareStatement(
                "INSERT INTO scan_row(import_id,line_no,raw_line,error) VALUES(?,?,?,?)"
            )
            for (e in parsed.errors) {
                errStmt.setLong(1, importId); errStmt.setInt(2, e.lineNo)
                errStmt.setString(3, e.raw); errStmt.setString(4, e.error)
                errStmt.executeUpdate()
            }

            val peakStmt = conn.prepareStatement(
                """INSERT INTO peak(peak_key,polarity,mz,scan,intensity,first_import_id,first_seen_at)
                   VALUES(?,?,?,?,?,?,?)
                   ON CONFLICT(peak_key) DO UPDATE SET intensity=excluded.intensity"""
            )
            var newPeaks = 0
            val exists = conn.prepareStatement("SELECT 1 FROM peak WHERE peak_key=?")
            for (p in parsed.peaks) {
                val key = p.stableIdentity()
                exists.setString(1, key)
                val had = exists.executeQuery().use { it.next() }
                if (!had) newPeaks++
                peakStmt.setString(1, key)
                peakStmt.setString(2, p.polarity.name)
                peakStmt.setString(3, "%.6f".format(p.mz))
                peakStmt.setString(4, "%.4f".format(p.scan))
                peakStmt.setDouble(5, p.intensity)
                peakStmt.setLong(6, importId)
                peakStmt.setString(7, db.now())
                peakStmt.executeUpdate()
            }

            val vid = db.insertVersion(parent, VersionType.INGEST, "导入 $filename (+${parsed.peaks.size}峰/${parsed.errors.size}行隔离)", editor)
            conn.prepareStatement("INSERT INTO ingest_version(version_id,import_id) VALUES(?,?)").apply {
                setLong(1, vid); setLong(2, importId)
            }.executeUpdate()

            vid to IngestResult(importId, parsed.peaks.size, newPeaks, parsed.errors)
        }
    }

    fun importByVersion(): Map<Long, Long> =
        conn.createStatement().executeQuery("SELECT version_id,import_id FROM ingest_version").use { rs ->
            buildMap { while (rs.next()) put(rs.getLong(1), rs.getLong(2)) }
        }

    fun importErrors(importId: Long): List<IngestRow> =
        conn.prepareStatement("SELECT line_no,raw_line,error FROM scan_row WHERE import_id=? ORDER BY line_no")
            .apply { setLong(1, importId) }.executeQuery().use { rs ->
                buildList { while (rs.next()) add(IngestRow(rs.getInt(1), rs.getString(2), rs.getString(3))) }
            }

    // ---------- 谱系内的观测峰 ----------

    private fun ingestImports(chain: List<Long>): Set<Long> {
        if (chain.isEmpty()) return emptySet()
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement("SELECT import_id FROM ingest_version WHERE version_id IN ($set)")
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        return st.executeQuery().use { rs ->
            buildSet { while (rs.next()) add(rs.getLong(1)) }
        }
    }

    data class StoredPeak(
        val key: String, val polarity: Polarity, val mz: Double,
        val scan: Double, val intensity: Double, val importId: Long,
    )

    private fun allPeaksForImports(importIds: Set<Long>): List<StoredPeak> {
        if (importIds.isEmpty()) return emptyList()
        val set = importIds.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT peak_key,polarity,CAST(mz AS REAL),CAST(scan AS REAL),intensity,first_import_id FROM peak WHERE first_import_id IN ($set)"
        )
        importIds.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        return st.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    StoredPeak(rs.getString(1), Polarity.valueOf(rs.getString(2)),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6))
                )
            }
        }
    }

    private fun annotationsInChain(chain: List<Long>): List<PeakAnnotation> {
        if (chain.isEmpty()) return emptyList()
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT type,peak_key,reason,replacement_mz,replacement_intensity FROM peak_annotation WHERE version_id IN ($set) ORDER BY id"
        )
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        return st.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    PeakAnnotation(
                        AnnotationType.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getObject(4)?.let { (it as Double) },
                        rs.getObject(5)?.let { it as Double },
                    )
                )
            }
        }
    }

    fun addAnnotation(parent: Long, annotation: PeakAnnotation, editor: String): Long = db.tx {
        val label = when (annotation.type) {
            AnnotationType.CONTAMINANT -> "峰 ${annotation.peakKey} 标记为污染"
            AnnotationType.SPLIT -> "峰 ${annotation.peakKey} 拆分过饱和峰"
        }
        val vid = db.insertVersion(parent, VersionType.ANNOTATION, label, editor)
        conn.prepareStatement(
            "INSERT INTO peak_annotation(version_id,type,peak_key,reason,replacement_mz,replacement_intensity) VALUES(?,?,?,?,?,?)"
        ).apply {
            setLong(1, vid); setString(2, annotation.type.name); setString(3, annotation.peakKey)
            setString(4, annotation.reason)
            if (annotation.replacementMz != null) setDouble(5, annotation.replacementMz) else setNull(5, java.sql.Types.REAL)
            if (annotation.replacementIntensity != null) setDouble(6, annotation.replacementIntensity) else setNull(6, java.sql.Types.REAL)
        }.executeUpdate()
        vid
    }

    // ---------- 视图：观测 + 校准 + 基线 + 规则 + 已有裁定 ----------

    data class View(
        override val versionId: Long,
        val peaks: List<Engine.ViewPeak>,
        override val calibration: CalibrationModel,
        override val calibrationVersion: Int,
        val baseline: BaselineParams,
        override val rules: RuleConfig,
        override val candidates: List<CandidateView>,
        val conflicts: List<Conflict>,
        override val observationSignature: String = "",
    ) : peakdossier.domain.RepositoryLikeView

    fun buildView(versionId: Long): View {
        val chain = db.lineage(versionId)
        val imports = ingestImports(chain)
        val stored = allPeaksForImports(imports)
        val annotations = annotationsInChain(chain)
        val calibrationPair = db.latestCalibration(chain)
            ?: throw IllegalStateException("版本谱系中缺少质量校准配置")
        val (calibration, calVersion) = calibrationPair
        val baseline = db.latestBaseline(chain)
            ?: throw IllegalStateException("版本谱系中缺少基线参数")
        val rulesPair = db.latestRules(chain)
            ?: throw IllegalStateException("版本谱系中缺少候选规则")
        val (rules, _) = rulesPair

        val contaminants = annotations.filter { it.type == AnnotationType.CONTAMINANT }.map { it.peakKey }.toSet()
        val splits = annotations.filter { it.type == AnnotationType.SPLIT }

        val viewPeaks = mutableListOf<Engine.ViewPeak>()
        for (sp in stored) {
            if (splits.any { it.peakKey == sp.key }) continue // 过饱和原峰被拆分替代
            val calMz = calibration.calibrate(sp.mz)
            viewPeaks += Engine.ViewPeak(
                peakKey = sp.key, mz = calMz, rawMz = sp.mz,
                intensity = sp.intensity, scan = sp.scan, polarity = sp.polarity,
            )
        }
        for (sp in stored) {
            val split = splits.firstOrNull { it.peakKey == sp.key } ?: continue
            val mz = split.replacementMz ?: sp.mz
            val key = "${sp.key}#split@%.6f".format(mz)
            viewPeaks += Engine.ViewPeak(
                peakKey = key, mz = calibration.calibrate(mz), rawMz = mz,
                intensity = split.replacementIntensity ?: 1.0,
                scan = sp.scan, polarity = sp.polarity,
            )
        }
        val marked = viewPeaks.map { vp ->
            if (vp.peakKey in contaminants) vp.copy(contaminant = true) else vp
        }.sortedBy { it.mz }

        var candidates = Engine.generateCandidates(marked, calibration, baseline, rules, calVersion)
        val adjudications = adjudicationsInChain(chain)
        val acceptedByHash = adjudications.filter { it.decision == "accept" }.map { it.candidateHash }.toSet()
        candidates = candidates.map { c ->
            val h = candidateHash(c)
            val adj = adjudications.firstOrNull { it.candidateHash == h }
            c.copy(
                status = when {
                    adj?.decision == "accept" -> "accepted"
                    adj?.decision == "reject" -> "rejected"
                    h in acceptedByHash -> "conflict"
                    else -> "proposed"
                },
                adjudicator = adj?.editor,
            )
        }
        val liveConflicts = Engine.detectConflicts(candidates.filter { it.status != "rejected" })
        val ingestIds = ingestImports(chain).sorted().joinToString(",")
        val annotationSig = annotations.joinToString(";") { "${it.type}:${it.peakKey}:${it.replacementMz}" }
        val obsSignature = "ingests=[$ingestIds]|annotations=[$annotationSig]"
        return View(versionId, marked, calibration, calVersion, baseline, rules, candidates, liveConflicts, obsSignature)
    }

    // ---------- 裁定 ----------

    fun candidateHash(c: CandidateView): String {
        val basis = listOf(
            c.compoundId, c.adduct, c.charge, c.polarity,
            "%.6f".format(c.mzTheoretical),
            c.usedPeakKeys.joinToString("|"),
            c.explanation.ruleVersion.toString(),
            c.explanation.calibrationVersion.toString(),
        ).joinToString("#")
        return MessageDigest.getInstance("SHA-256").digest(basis.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    data class StoredAdjudication(
        val candidateId: String, val candidateHash: String,
        val decision: String, val editor: String, val createdAt: String,
        val versionId: Long, val reason: String,
    )

    fun adjudicationsInChain(chain: List<Long>): List<StoredAdjudication> {
        if (chain.isEmpty()) return emptyList()
        val set = chain.joinToString(",") { "?" }
        val st = conn.prepareStatement(
            "SELECT candidate_id,candidate_hash,decision,editor,created_at,version_id,reason FROM adjudication WHERE version_id IN ($set) ORDER BY id"
        )
        chain.forEachIndexed { i, v -> st.setLong(i + 1, v) }
        return st.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    StoredAdjudication(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7))
                )
            }
        }
    }

    fun adjudicateHistory(versionId: Long): List<StoredAdjudication> =
        adjudicationsInChain(db.lineage(versionId))

    class VersionConflictException(message: String) : RuntimeException(message)
    class RuleRejectException(val reasons: List<String>) : RuntimeException(reasons.joinToString("；"))

    /**
     * 提交裁定：
     * 1) baseVersion 必须是当前分支头，否则两个编辑者基于旧版本裁定时返回 VERSION_CONFLICT；
     * 2) 峰占用 / 电荷 / 极性 / RT 窗口任一不过 => 拒绝（缺失同位素不算）；
     * 3) 全部在一个事务里落库。
     */
    fun adjudicate(
        baseVersion: Long,
        candidateId: String,
        decision: String,
        editor: String,
        reason: String,
    ): Long = db.tx {
        if (baseVersion != db.headVersion()) {
            throw VersionConflictException("基线版本 v$baseVersion 已过期，当前分支头是 v${db.headVersion()}（存在并发裁定）")
        }
        val view = buildView(baseVersion)
        val candidate = view.candidates.firstOrNull { it.candidateId == candidateId }
            ?: throw IllegalArgumentException("候选不存在: $candidateId")

        if (decision == "accept") {
            val targetSpec = view.rules.targets.first { it.compoundId == candidate.compoundId }
            val accepted = view.candidates.filter { it.status == "accepted" }
            val violations = Engine.adjudicateCheck(candidate, accepted, targetSpec)
            if (violations.isNotEmpty()) throw RuleRejectException(violations)
        }

        val hash = candidateHash(candidate)
        val label = "裁定 ${candidate.compoundName} ${candidate.adduct} => $decision"
        val vid = db.insertVersion(baseVersion, VersionType.ADJUDICATION, label, editor)
        conn.prepareStatement(
            "INSERT INTO adjudication(version_id,candidate_id,candidate_hash,decision,editor,reason,created_at) VALUES(?,?,?,?,?,?,?)"
        ).apply {
            setLong(1, vid); setString(2, candidateId); setString(3, hash)
            setString(4, decision); setString(5, editor); setString(6, reason); setString(7, db.now())
        }.executeUpdate()
        vid
    }
}
