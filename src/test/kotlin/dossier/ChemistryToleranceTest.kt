package dossier

import dossier.chem.Chemistry
import dossier.model.CalibrationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChemistryToleranceTest {

    @Test
    fun ppmAndAbsoluteBoundariesMustBothHold() {
        // 两个边界必须同时满足。200.001：5.0ppm、0.001Da，在 (5ppm, 0.005Da) 内
        assertTrue(Chemistry.withinTolerance(200.001, 200.0, 5.0, 0.005))
        // 200.0011：5.5ppm 超出 ppm 边界，但 0.0011Da 仍在绝对边界内 → 拒绝
        assertFalse(Chemistry.withinTolerance(200.0011, 200.0, 5.0, 0.005))
        // 200.0049：24.5ppm 在 30ppm 内，但 0.0049Da 超出 0.004 绝对边界 → 拒绝
        assertFalse(Chemistry.withinTolerance(200.0049, 200.0, 30.0, 0.004))
        // 仅放宽 ppm 到 30 且绝对边界放到 0.005：两个条件同时成立 → 接受
        assertTrue(Chemistry.withinTolerance(200.0049, 200.0, 30.0, 0.005))
        // 恰好在 ppm 边界上（含等号）
        val exactly5ppm = 200.0 * (1 + 5.0 / 1e6)
        assertTrue(Chemistry.withinTolerance(exactly5ppm, 200.0, 5.0, 1.0))
    }

    @Test
    fun ppmErrorIsSignedAndScaled() {
        assertEquals(5.0, Chemistry.ppmError(200.001, 200.0), 1e-9)
        assertEquals(-5.0, Chemistry.ppmError(199.999, 200.0), 1e-9)
    }

    @Test
    fun isotopeSpacingScalesWithCharge() {
        assertEquals(1.003355, Chemistry.isotopeSpacing(1, 1), 1e-9)
        assertEquals(1.003355, Chemistry.isotopeSpacing(2, 2), 1e-9)
        assertEquals(2.00671, Chemistry.isotopeSpacing(2, 1), 1e-9)
        assertEquals(0.0, Chemistry.isotopeSpacing(0, 1), 0.0)
    }

    @Test
    fun linearFitHandlesEmptyOneAndMultiplePoints() {
        val (a0, b0) = Chemistry.linearFit(emptyList())
        assertEquals(1.0, a0); assertEquals(0.0, b0)

        val (a1, b1) = Chemistry.linearFit(listOf(CalibrationPoint(100.0, 100.002)))
        assertEquals(1.0, a1); assertEquals(0.002, b1, 1e-12)

        // y = 2x + 1
        val (a, b) = Chemistry.linearFit(listOf(
            CalibrationPoint(0.0, 1.0),
            CalibrationPoint(1.0, 3.0),
            CalibrationPoint(2.0, 5.0),
        ))
        assertEquals(2.0, a, 1e-12); assertEquals(1.0, b, 1e-12)
    }

    @Test
    fun carbonCountParsesFormula() {
        assertEquals(8, Chemistry.parseCarbonCount("C8H10N4O2"))
        assertEquals(13, Chemistry.parseCarbonCount("C13H18O2"))
    }
}
