package dossier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StableIdentityTest {

    @Test
    fun sameMzAcrossImportsKeepsStablePeakIdentity() {
        val (svc, _) = newHarness()
        val first = svc.importer.import("main", """
            195.0871,120000,120.5,pos,101
            195.0873,110000,121.0,pos,105
        """.trimIndent())
        // 两行 m/z 相差 ~1ppm，在 2ppm 身份分箱内合并为 1 个稳定峰（同批合并不计跨导入合并）
        assertEquals(1, first.newPeakCount)
        assertEquals(0, first.mergedPeakCount)

        val before = svc.query.versionView("main").peaks.map { it.id }.toSet()

        val second = svc.importer.import("main", """
            195.0872,90000,122.0,pos,109
        """.trimIndent())
        // 再次导入同一 m/z 身份：0 新峰、1 合并
        assertEquals(0, second.newPeakCount)
        assertEquals(1, second.mergedPeakCount)

        val after = svc.query.versionView("main").peaks.map { it.id }.toSet()
        assertEquals(before, after)
    }

    @Test
    fun polaritySeparatesIdentityEvenAtSameMz() {
        val (svc, _) = newHarness()
        svc.importer.import("main", """
            200.0000,1000,10,pos,1
            200.0000,1000,10,neg,2
        """.trimIndent())
        val peaks = svc.query.versionView("main").peaks
        assertEquals(2, peaks.size)
        assertTrue(peaks.map { it.polarity }.distinct().size == 2)
    }
}
