package peakdossier.db

import peakdossier.domain.*

object Seed {
    fun ensureSeeded(repo: Repository) {
        val db = repo.db
        db.tx {
            val count = db.conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM version").use { it.next(); it.getInt(1) }
            if (count > 0) return@tx

            // v1 ROOT（分支起点，无配置）
            val root = db.insertVersion(null, VersionType.ROOT, "案卷根版本", "system")
            // v2 校准 / v3 基线 / v4 规则，依次串联在 root 上
            val cal = db.insertCalibrationVersion(root, identityCalibration(), "初始质量校准（恒等）", "system")
            val base = db.insertBaselineVersion(cal, defaultBaseline(), "初始基线参数", "system")
            val rulesV = db.insertRulesVersion(base, defaultRules(), "初始候选规则 v1", "system")

            // v5 演示导入（含 2 行隔离扫描）
            repo.ingest(demoPeakCsv(), "demo-centroid.csv", rulesV, "system")
            Unit
        }
    }
}
