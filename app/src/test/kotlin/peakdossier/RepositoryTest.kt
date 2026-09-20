package peakdossier

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import peakdossier.db.Database
import peakdossier.db.Repository
import peakdossier.db.Seed
import peakdossier.domain.*
import java.nio.file.Path

class RepositoryTest {

    @TempDir lateinit var dir: Path
    lateinit var db: Database
    lateinit var repo: Repository

    @BeforeEach fun setup() {
        db = Database(dir.resolve("test.db"))
        repo = Repository(db)
        Seed.ensureSeeded(repo)
    }
    @AfterEach fun tearDown() { db.close() }

    private fun findCand(version: Long, compound: String): CandidateView =
        repo.buildView(version).candidates.first { it.compoundName == compound }

    @Test
    fun `batch partial failure isolates rows`() {
        val head = db.headVersion()
        val csv = """
            mz,intensity,scan,polarity
            300.1,5000,6.1,+
            nope,1,6,+
            301.1,abc,6.1,+
        """.trimIndent()
        val (vid, result) = repo.ingest(csv, "partial.csv", head, "tester")
        assertEquals(1, result.accepted)
        assertEquals(2, result.quarantined.size)
        val errs = repo.importErrors(result.importId)
        assertEquals(listOf(3, 4), errs.map { it.lineNo })
        // 其余扫描照常进入
        val view = repo.buildView(vid)
        assertTrue(view.peaks.any { it.rawMz == 300.1 })
    }

    @Test
    fun `calibration revision and rollback restores old model on a new branch`() {
        val head = db.headVersion()
        val revised = db.insertCalibrationVersion(
            head,
            Engine.fitCalibration(listOf(
                CalibrationPoint(195.08805, 195.0871, "a"),
                CalibrationPoint(207.1500, 207.1489, "b"),
            )),
            "偏移校准", "tester",
        )
        val viewRev = repo.buildView(revised)
        assertNotEquals(0.0, viewRev.calibration.intercept)

        val rolled = db.rollbackCalibration(revised, 2, "tester")
        val viewBack = repo.buildView(rolled)
        assertEquals(1.0, viewBack.calibration.slope, 1e-12)
        assertEquals(0.0, viewBack.calibration.intercept, 1e-12)
        // 回滚产生新版本而不是改写历史
        assertNotEquals(2L, rolled)
        // 旧版本仍可查看（保留修订后的模型）
        assertNotEquals(viewBack.calibration.intercept, repo.buildView(revised).calibration.intercept)
    }

    @Test
    fun `two editors adjudicating from stale version hit version conflict`() {
        val base = db.headVersion()
        val caff = findCand(base, "咖啡因")
        // 编辑者 A 基于 head 接受咖啡因 => 产生新版本，head 前移
        val afterA = repo.adjudicate(base, caff.candidateId, "accept", "alice", "ok")
        assertEquals(afterA, db.headVersion())
        // 编辑者 B 仍基于旧 base 试图接受异构体（它与 A 接受的咖啡因共享峰）
        val isomer = findCand(base, "咖啡因共洗脱异构体")
        val ex = assertThrows(Repository.VersionConflictException::class.java) {
            repo.adjudicate(base, isomer.candidateId, "accept", "bob", "stale")
        }
        assertTrue(ex.message!!.contains("过期"))
    }

    @Test
    fun `accepting conflicting candidate on fresh head is rejected by peak occupancy`() {
        // A 接受咖啡因后，B 刷新到新 head，再接受异构体 => 规则拒绝（峰占用）
        var head = db.headVersion()
        val caff = findCand(head, "咖啡因")
        head = repo.adjudicate(head, caff.candidateId, "accept", "alice", "ok")
        val isomer = findCand(head, "咖啡因共洗脱异构体")
        val ex = assertThrows(Repository.RuleRejectException::class.java) {
            repo.adjudicate(head, isomer.candidateId, "accept", "bob", "same peaks")
        }
        assertTrue(ex.reasons.any { it.contains("峰占用冲突") })
        // 被拒绝后不产生新版本（事务回滚）
        assertEquals(head, db.headVersion())
    }

    @Test
    fun `annotations only affect descendant branch`() {
        val head = db.headVersion()
        val anchor = "P+_mz195.087550_rt5.0200"
        val v2 = repo.addAnnotation(head, PeakAnnotation(AnnotationType.CONTAMINANT, anchor, "柱流失"), "tester")
        // 旧版本视图：峰仍然有效且候选存在
        val oldView = repo.buildView(head)
        assertTrue(oldView.peaks.first { it.peakKey == anchor }.contaminant.not())
        // 新分支：峰被标污染，咖啡因候选消失
        val newView = repo.buildView(v2)
        assertTrue(newView.peaks.first { it.peakKey == anchor }.contaminant)
        assertTrue(newView.candidates.none { it.compoundName == "咖啡因" })
    }

    @Test
    fun `rules upgrade preserves old candidates and compare attributes causes`() {
        val old = db.headVersion()
        val rules = defaultRules().copy(version = 2, ppmTolerance = 1.5) // 收紧到 1.5 ppm（咖啡因演示峰偏差约 2.3 ppm）
        val newRulesV = db.insertRulesVersion(old, rules, "规则升级 v2", "tester")
        val oldView = repo.buildView(old)
        val newView = repo.buildView(newRulesV)
        // 演示峰偏差约 2.3 ppm：收紧后咖啡因候选消失
        assertTrue(oldView.candidates.any { it.compoundName == "咖啡因" })
        assertTrue(newView.candidates.none { it.compoundName == "咖啡因" })
        val report = Compare.compare(oldView, newView, emptySet(), emptySet())
        assertTrue(report.rulesChanged)
        assertFalse(report.observationChanged)
        assertFalse(report.calibrationChanged)
        assertTrue(report.diffs.any { it.causes.contains("RULES") && it.kind == "DISAPPEARED" })
    }

    @Test
    fun `failed adjudication commits no half records`() {
        val head = db.headVersion()
        val before = db.listVersions().size
        val adjBefore = db.conn.createStatement()
            .executeQuery("SELECT COUNT(*) FROM adjudication").use { it.next(); it.getInt(1) }
        val isomer = findCand(head, "咖啡因共洗脱异构体")
        // 先让 A 接受咖啡因（成功）
        repo.adjudicate(head, findCand(head, "咖啡因").candidateId, "accept", "alice", "ok")
        // 再用旧 head 触发异常
        assertThrows(Exception::class.java) {
            repo.adjudicate(head, isomer.candidateId, "accept", "bob", "boom")
        }
        // 只有 A 的一次成功提交；失败没有留下版本/裁定
        assertEquals(before + 1, db.listVersions().size)
        val adjAfter = db.conn.createStatement()
            .executeQuery("SELECT COUNT(*) FROM adjudication").use { it.next(); it.getInt(1) }
        assertEquals(adjBefore + 1, adjAfter)
    }
}
