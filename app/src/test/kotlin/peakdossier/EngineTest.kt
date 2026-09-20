package peakdossier

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import peakdossier.domain.*

class EngineTest {

    private val rules = defaultRules()
    private val baseline = defaultBaseline()
    private val cal = identityCalibration()

    private fun pk(mz: Double, scan: Double, intensity: Double, pol: Polarity = Polarity.POSITIVE) =
        Engine.ViewPeak(Engine.stablePeakKey(pol, mz, scan), mz, mz, intensity, scan, pol)

    private fun candidates(vararg peaks: Engine.ViewPeak) =
        Engine.generateCandidates(peaks.toList(), cal, baseline, rules, 1)

    @Test
    fun `ppm and absolute error boundaries`() {
        val expected = 200.0
        // 9.5 ppm @200 = 0.0019 Da，在 10 ppm 与 0.005 Da 内
        assertTrue(Engine.withinTolerance(200.0019, expected, 10.0, 0.005))
        // +11 ppm 超过 ppm 边界，即使绝对误差很小
        assertFalse(Engine.withinTolerance(200.0022, expected, 10.0, 0.005))
        // 0.0049 Da 绝对误差虽 <0.005，但 ppm 超界
        assertFalse(Engine.withinTolerance(100.0049, 100.0, 10.0, 0.005))
        // 0.006 Da 绝对超界，即使 ppm 仍在容差内
        assertFalse(Engine.withinTolerance(50.006, 50.0, 200.0, 0.005))
    }

    @Test
    fun `stable identity ignores intensity and survives reingest`() {
        val a = RawPeak(195.08710, 120000.0, 5.02, Polarity.POSITIVE).stableIdentity()
        val b = RawPeak(195.08710, 1.0, 5.02, Polarity.POSITIVE).stableIdentity()
        assertEquals(a, b)
        assertNotEquals(a, RawPeak(195.08710, 120000.0, 5.02, Polarity.NEGATIVE).stableIdentity())
        assertEquals(
            Engine.stablePeakKey(Polarity.POSITIVE, 195.0871004, 5.02),
            Engine.stablePeakKey(Polarity.POSITIVE, 195.0870996, 5.02),
        )
    }

    @Test
    fun `shared peak conflict yields minimal shared set`() {
        val cands = candidates(
            pk(195.08755, 5.02, 120000.0),
            pk(196.09065, 5.02, 8600.0),
        )
        val names = cands.map { it.compoundName }.toSet()
        assertTrue("咖啡因" in names && "咖啡因共洗脱异构体" in names)
        val conflicts = Engine.detectConflicts(cands)
        assertEquals(1, conflicts.size)
        val shared = conflicts.single().minimalSharedSet
        assertTrue(shared.isNotEmpty())
        assertTrue(shared.all { it in cands.flatMap { c -> c.usedPeakKeys } })
        assertTrue(shared.any { it.contains("mz195.087550") })
    }

    @Test
    fun `missing low intensity isotope is not a conflict`() {
        val cands = candidates(pk(166.1223, 7.45, 42000.0))
        val trace = cands.single { it.compoundId == "TRACE" }
        // M+1 与 M+2 期望高于噪声但未观测 => 缺失；M+3 起低于检测限不计
        assertEquals(listOf(1, 2), trace.explanation.missingIsotopes)
        assertTrue(Engine.detectConflicts(cands).isEmpty())
        assertEquals(1, trace.usedPeakKeys.size)
    }

    @Test
    fun `calibration fit and corrected mz`() {
        val model = Engine.fitCalibration(listOf(
            CalibrationPoint(195.0876, 195.0871, "drift"),
            CalibrationPoint(207.1494, 207.1489, "drift"),
        ))
        assertEquals(1.0, model.slope, 1e-9)
        assertEquals(-0.0005, model.intercept, 1e-9)
        assertEquals(195.0871, model.calibrate(195.0876), 1e-9)
        assertEquals(0.0, model.rmsPpm, 1e-6)
    }

    @Test
    fun `adjudication checks peak occupancy charge polarity and rt window`() {
        val cands = candidates(
            pk(195.08755, 5.02, 120000.0),
            pk(196.09065, 5.02, 8600.0),
            pk(197.08935, 5.02, 950.0),
        )
        val caff = cands.firstOrNull { it.compoundName == "咖啡因" } ?: error("缺咖啡因: ${cands.map { it.compoundName }}")
        val target = rules.targets.first { it.compoundId == "CAFF" }
        assertTrue(Engine.adjudicateCheck(caff, emptyList(), target).isEmpty())

        val isomer = cands.firstOrNull { it.compoundName == "咖啡因共洗脱异构体" } ?: error("缺异构体: ${cands.map { it.compoundName }}")
        val violations = Engine.adjudicateCheck(isomer, listOf(caff), target)
        assertTrue(violations.any { it.contains("峰占用冲突") })
        assertTrue(violations.any { it.contains("P+_mz195.087550") })

        // RT 窗口越界
        val outOfWindow = caff.copy(rt = 99.0)
        assertTrue(Engine.adjudicateCheck(outOfWindow, emptyList(), target).any { it.contains("保留时间") })
        // 极性不一致：候选自身极性与已接受候选不同，且不共享峰（避免峰占用噪音）
        val otherPol = caff.copy(
            candidateId = caff.candidateId + "#pol",
            polarity = "NEGATIVE",
            usedPeakKeys = listOf("P-_mz195.000000_rt5.0200"),
            monoisotopicPeak = "P-_mz195.000000_rt5.0200",
        )
        val polReasons = Engine.adjudicateCheck(otherPol, listOf(caff), target)
        assertTrue(polReasons.any { it.contains("极性不一致") }, polReasons.toString())
        // 电荷不一致
        val otherZ = caff.copy(
            candidateId = caff.candidateId + "#z",
            charge = 2,
            usedPeakKeys = listOf("P+_mz100.000000_rt5.0200"),
            monoisotopicPeak = "P+_mz100.000000_rt5.0200",
        )
        val zReasons = Engine.adjudicateCheck(otherZ, listOf(caff), target)
        assertTrue(zReasons.any { it.contains("电荷数不一致") }, zReasons.toString())
    }

    @Test
    fun `csv ingest quarantines broken rows and keeps good ones`() {
        val csv = """
            mz,intensity,scan,polarity
            195.0871,120000,5.02,+
            bad,1,5,+
            200.0,100,-1,+
        """.trimIndent()
        val out = CsvIngest.parse(csv)
        assertEquals(1, out.peaks.size)
        assertEquals(2, out.errors.size)
        assertEquals(3, out.errors.first().lineNo)
    }

    @Test
    fun `isotope spacing follows neutron mass over charge`() {
        assertEquals(1.003355, Engine.isotopeMz(100.0, 1, 1) - 100.0, 1e-6)
        assertEquals(0.5016775, Engine.isotopeMz(100.0, 2, 1) - 100.0, 1e-6)
    }
}
