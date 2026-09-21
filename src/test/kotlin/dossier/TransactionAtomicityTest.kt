package dossier

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionAtomicityTest {

    @Test
    fun noHalfCommittedVersionsWhenTransactionFails() {
        val (svc, dir) = newHarness()
        svc.importer.import("main", "195.0871,120000,120.5,pos,101")
        val versionsBefore = countRows(dir, "versions")
        val rawBefore = countRows(dir, "raw_rows")
        assertTrue(versionsBefore >= 2)

        // 用不存在的分支触发追加版本失败；同一事务里的任何写入都必须回滚
        val threw = try {
            svc.importer.import("does-not-exist", "196.0,10,1,pos,2")
            false
        } catch (t: Throwable) {
            true
        }
        assertTrue(threw)
        assertEquals(versionsBefore, countRows(dir, "versions"))
        assertEquals(rawBefore, countRows(dir, "raw_rows"))
    }

    private fun countRows(dir: java.nio.file.Path, table: String): Int {
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("test.sqlite")}").use { c ->
            c.createStatement().use { s ->
                val rs = s.executeQuery("SELECT COUNT(*) FROM $table")
                rs.next(); return rs.getInt(1)
            }
        }
    }
}
