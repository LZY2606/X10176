package dossier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchPartialFailureTest {

    @Test
    fun corruptRowsAreQuarantinedWhileGoodRowsStillEnter() {
        val (svc, _) = newHarness()
        val content = """
            mz,intensity,rt,polarity,scan
            195.0871,120000,120.5,pos,101
            not-a-number,10,1,pos,102
            196.0905,9600,120.5,pos,101
            200,1000,5,sideways,103
            197.0899,520,120.6,pos,101
            onlythree,columns,here
        """.trimIndent()
        val result = svc.importer.import("main", content)
        assertEquals(3, result.acceptedRows)
        assertEquals(3, result.quarantinedRows.size)
        assertEquals(3, result.newPeakCount)

        val errors = result.quarantinedRows
        assertEquals(listOf(3, 5, 7), errors.map { it.line })
        assertTrue(errors.all { it.rawLine.isNotBlank() })

        val peaks = svc.query.versionView("main").peaks
        // 三个好行 m/z 相差约 5ppm，超出 2ppm 身份分箱 → 3 个稳定峰
        assertEquals(3, peaks.size)
    }

    @Test
    fun parseSeparatesErrorsAndKeepsLineNumbers() {
        val (svc, _) = newHarness()
        val (rows, errs) = svc.importer.parse("100,1,1,pos\n\nnope\n100,1,1,neg\n-5,1,1,pos")
        assertEquals(2, rows.size)
        assertEquals(listOf(3, 5), errs.map { it.line })
    }
}
