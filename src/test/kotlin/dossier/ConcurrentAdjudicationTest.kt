package dossier

import dossier.engine.AdjudicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentAdjudicationTest {

    @Test
    fun twoEditorsAdjudicatingFromStaleVersionConflict() {
        val (svc, _) = newHarness()
        svc.importer.import("main", SampleData.caffeine)
        val gen = svc.generator.generate("main")
        val headAfterRun = gen.versionId
        val view = svc.query.versionView("main")
        val run = view.runs.single { it.id == gen.runId }
        val candA = run.candidates[0]
        val candB = run.candidates.getOrElse(1) { run.candidates[0] }

        // 两个编辑者都基于同一个旧版本 headAfterRun
        svc.adjudicator.adjudicate("main", candA.id, "alice", "accept", "alice ok", headAfterRun)

        // bob 仍用旧版本号裁定 → 必须收到 STALE_VERSION
        val ex = try {
            svc.adjudicator.adjudicate("main", candB.id, "bob", "reject", "bob stale", headAfterRun)
            null
        } catch (e: AdjudicationException) {
            e
        }
        assertTrue(ex != null && ex.reason.startsWith("STALE_VERSION"))
        assertEquals(emptyList(), ex!!.sharedPeakIds)

        // bob 刷新到新头版本后可以正常裁定
        val newHead = svc.store.headVersion("main")
        svc.adjudicator.adjudicate("main", candB.id, "bob", "reject", "bob refreshed", newHead)
        val history = svc.adjudicator.decisionHistory("main")
        assertEquals(2, history.size)
        assertTrue(history.map { it.editor }.containsAll(listOf("alice", "bob")))
    }
}
