package dossier

import dossier.model.CalibrationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationRollbackTest {

    @Test
    fun calibrationRevisionAndRollbackRestoreCalibratedMz() {
        val (svc, _) = newHarness()
        svc.importer.import("main", "195.0871,120000,120.5,pos,101")
        val before = svc.query.versionView("main").peaks.single()
        assertEquals(before.rawMz, before.calibratedMz, 1e-12)

        // 修订：测量值整体偏高 0.002，校准应把 195.0871 拉低
        svc.peakOps.reviseCalibration("main", listOf(
            CalibrationPoint(100.0, 99.998),
            CalibrationPoint(200.0, 199.998),
        ))
        val after = svc.query.versionView("main").peaks.single()
        assertTrue(after.calibratedMz < before.rawMz)
        assertEquals(0.0, after.rawMz - before.rawMz, 1e-12)

        // 回滚到 v1 配置（空校准点 = 恒等变换）
        svc.peakOps.rollbackCalibration("main", 1)
        val rolled = svc.query.versionView("main").peaks.single()
        assertEquals(before.rawMz, rolled.calibratedMz, 1e-9)
    }

    @Test
    fun branchCalibrationDoesNotLeakIntoMain() {
        val (svc, _) = newHarness()
        svc.importer.import("main", "195.0871,120000,120.5,pos,101")
        val headMain = svc.store.headVersion("main")
        val branch = svc.store.createBranch("exp", headMain)
        svc.peakOps.reviseCalibration(branch.id, listOf(CalibrationPoint(100.0, 100.01), CalibrationPoint(200.0, 200.01)))
        val expPeak = svc.query.versionView(branch.id).peaks.single()
        val mainPeak = svc.query.versionView("main").peaks.single()
        assertTrue(expPeak.calibratedMz > mainPeak.calibratedMz)
        assertEquals(mainPeak.rawMz, mainPeak.calibratedMz, 1e-12)
    }
}
