package dossier

import dossier.engine.AdjudicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConflictAndMissingTest {

    private fun setupWithRun(): TestEnv {
        val (svc, dir) = newHarness()
        svc.importer.import("main", SampleData.caffeine)
        val gen = svc.generator.generate("main")
        return TestEnv(svc, dir, gen.runId, gen.versionId)
    }

    data class TestEnv(
        val svc: dossier.api.AppServices,
        val dir: java.nio.file.Path,
        val runId: String,
        val versionId: String,
    )

    @Test
    fun sharedPeakConflictReportsMinimalSharedPeakSet() {
        val env = setupWithRun()
        // 升级规则：追加一个与咖啡因同质量的目标，制造同一锚点峰上的两个候选
        val cfg = svc(env).store.config(svc(env).store.latestConfigVersion())
        val crowded = cfg.copy(
            candidateRules = cfg.candidateRules.copy(
                targets = cfg.candidateRules.targets + dossier.model.Target(
                    "咖啡因内标", "C8H10N4O2", 194.0804
                )
            )
        )
        svc(env).peakOps.upgradeConfig("main", crowded, "qa")
        val gen2 = svc(env).generator.generate("main")
        val view = svc(env).query.versionView("main")
        val run = view.runs.first { it.id == gen2.runId }
        val candidates = run.candidates
        val anchorGroups = candidates.groupBy { it.explanation.monoisotopicPeakId }
        val crowdedAnchor = anchorGroups.entries.first { it.value.size >= 2 }
        val first = crowdedAnchor.value[0]
        val second = crowdedAnchor.value[1]
        val headAfterRun = svc(env).store.headVersion("main")

        svc(env).adjudicator.adjudicate("main", first.id, "alice", "accept", "ok", headAfterRun)

        val ex = try {
            val newHead = svc(env).store.headVersion("main")
            svc(env).adjudicator.adjudicate("main", second.id, "bob", "accept", "also", newHead)
            error("应当抛出占用冲突")
        } catch (e: AdjudicationException) {
            e
        }
        assertTrue(ex.reason.startsWith("PEAK_OCCUPANCY"))
        // 最小共享峰集合就是两个 usedPeakIds 的交集
        val expected = first.explanation.usedPeakIds.intersect(second.explanation.usedPeakIds.toSet()).sorted()
        assertEquals(expected, ex.sharedPeakIds)
        assertTrue(ex.sharedPeakIds.isNotEmpty())
    }

    @Test
    fun missingLowIntensityIsotopesDoNotCountAsConflict() {
        val env = setupWithRun()
        val view = svc(env).query.versionView("main")
        val run = view.runs.first { it.id == env.runId }
        // 至少存在一个候选，其同位素列表中有 present=false 的缺失项
        val candWithMissing = run.candidates.firstOrNull { c ->
            c.explanation.isotopes.any { !it.present && it.observedPeakId == null }
        }
        assertNotNull(candWithMissing, "应能找到带缺失同位素的候选")
        val missing = candWithMissing.explanation.isotopes.filter { !it.present }
        assertTrue(missing.all { it.observedPeakId == null })

        // 冲突队列不应因为缺失同位素而产生 PEAK_OCCUPANCY
        val head = svc(env).store.headVersion("main")
        svc(env).adjudicator.adjudicate("main", candWithMissing.id, "carol", "accept", "缺失同位素可接受", head)
        val conflicts = svc(env).adjudicator.conflictQueue("main")
        assertTrue(conflicts.none { it.candidateId == candWithMissing.id && it.reason.startsWith("PEAK_OCCUPANCY") })
    }

    private fun svc(env: TestEnv) = env.svc
}
