package dossier.chem

import dossier.model.AdductRule
import dossier.model.CalibrationPoint
import dossier.model.Polarity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

object Chemistry {
    private const val BOUND_EPS_PPM = 1e-9
    private const val BOUND_EPS_DA = 1e-12
    const val ELECTRON_MASS_DA: Double = 0.00054858
    const val C13_C12_DELTA_DA: Double = 1.003355
    const val PROTON_MASS_DA: Double = 1.007276
    const val SODIUM_MASS_DA: Double = 22.989218
    const val POTASSIUM_MASS_DA: Double = 38.963158
    const val AMMONIUM_MASS_DA: Double = 18.033823
    const val CHLORIDE_DELTA_DA: Double = 34.969402
    const val FORMATE_DELTA_DA: Double = 44.998203
    const val ACETATE_DELTA_DA: Double = 59.013853

    fun ppmError(observed: Double, theoretical: Double): Double =
        (observed - theoretical) / theoretical * 1.0e6

    /** 候选必须同时满足 ppm 与绝对误差边界（两个条件都成立才算在容差内）。 */
    fun withinTolerance(observed: Double, theoretical: Double, ppm: Double, absDa: Double): Boolean =
        abs(ppmError(observed, theoretical)) <= ppm + BOUND_EPS_PPM &&
            abs(observed - theoretical) <= absDa + BOUND_EPS_DA

    /** 一阶最小二乘拟合 y = a*x + b；点数不足时返回恒等变换 (1, 0)。 */
    fun linearFit(points: List<CalibrationPoint>): Pair<Double, Double> {
        if (points.isEmpty()) return 1.0 to 0.0
        if (points.size == 1) {
            val p = points[0]
            return 1.0 to (p.theoreticalMz - p.measuredMz)
        }
        val n = points.size.toDouble()
        val sx = points.sumOf { it.measuredMz }
        val sy = points.sumOf { it.theoreticalMz }
        val sxx = points.sumOf { it.measuredMz * it.measuredMz }
        val sxy = points.sumOf { it.measuredMz * it.theoreticalMz }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-12) return 1.0 to (sy / n - sx / n)
        val a = (n * sxy - sx * sy) / denom
        val b = (sy - a * sx) / n
        return a to b
    }

    fun applyCalibration(mz: Double, transform: Pair<Double, Double>): Double =
        transform.first * mz + transform.second

    /** m/z 身份分箱：相同极性且相对差在 binPpm 内的观测峰视为同一稳定峰身份。 */
    fun mzBinIndex(mz: Double, binPpm: Double): Long =
        (1.0 / (binPpm * 1e-6)).toLong().let { binWidthUnits ->
            (mz / (mz * binPpm * 1e-6)).toLong()
        }

    fun closeIdentity(a: Double, b: Double, binPpm: Double): Boolean =
        abs(a - b) / maxOf(a, b) * 1e6 <= binPpm

    /** 中性质量加合后的单同位素 m/z。 */
    fun monoisotopicMz(neutralMass: Double, adduct: AdductRule): Double {
        val z = abs(adduct.z)
        return (neutralMass + adduct.massDeltaDa - adduct.z * ELECTRON_MASS_DA) / z
    }

    /** 第 order 个同位素峰相对 M0 的理论间距（Da），order=0 时为 0。 */
    fun isotopeSpacing(order: Int, charge: Int): Double =
        if (order == 0) 0.0 else C13_C12_DELTA_DA * order / abs(charge)

    /**
     * 简化的碳同位素相对丰度模型：等效碳数 nC 的泊松近似
     * (nC*p)^k/k!，p=1.07%。化学式解析 C 数量；无法解析时按质量估算。
     */
    fun expectedIsotopeRatio(formula: String, order: Int, neutralMass: Double): Double {
        if (order == 0) return 1.0
        val carbons = (parseCarbonCount(formula) ?: (neutralMass / 12.0 * 0.55)).toDouble()
        val lambda = carbons * 0.0107
        var logTerm = order * ln(lambda)
        for (k in 1..order) logTerm -= ln(k.toDouble())
        return exp(logTerm)
    }

    fun parseCarbonCount(formula: String): Int? {
        // 按「元素符号 + 可选数字」分词，避免 C13 的贪婪回溯被元素边界干扰
        val tokenRegex = Regex("([A-Z][A-Z]?)(\\d*)")
        for (match in tokenRegex.findAll(formula.uppercase())) {
            if (match.groupValues[1] == "C") {
                val n = match.groupValues[2]
                return if (n.isEmpty()) 1 else n.toIntOrNull()
            }
        }
        return null
    }

    fun defaultConfig(): dossier.model.AnalysisConfig = dossier.model.AnalysisConfig(
        candidateRules = dossier.model.CandidateRules(
            ppmTolerance = 5.0,
            absoluteToleranceDa = 0.005,
            isotopeSpacingTolerancePpm = 10.0,
            maxCharge = 2,
            isotopeCount = 3,
            minIsotopeRatio = 0.02,
            rtWindowSeconds = 30.0,
            positiveAdducts = listOf(
                AdductRule("[M+H]+", PROTON_MASS_DA, 1),
                AdductRule("[M+Na]+", SODIUM_MASS_DA, 1),
                AdductRule("[M+K]+", POTASSIUM_MASS_DA, 1),
                AdductRule("[M+NH4]+", AMMONIUM_MASS_DA, 1),
                AdductRule("[M+2H]2+", 2.0 * PROTON_MASS_DA, 2),
            ),
            negativeAdducts = listOf(
                AdductRule("[M-H]-", -PROTON_MASS_DA, -1),
                AdductRule("[M+Cl]-", CHLORIDE_DELTA_DA, -1),
                AdductRule("[M+HCOO]-", FORMATE_DELTA_DA, -1),
                AdductRule("[M+CH3COO]-", ACETATE_DELTA_DA, -1),
            ),
            targets = listOf(
                dossier.model.Target("咖啡因", "C8H10N4O2", 194.0804),
                dossier.model.Target("葡萄糖", "C6H12O6", 180.0634),
                dossier.model.Target("布洛芬", "C13H18O2", 206.1307),
            ),
        ),
        baseline = dossier.model.BaselineParams(
            noiseIntensity = 100.0,
            minPeakIntensity = 500.0,
        ),
        calibrationPoints = emptyList(),
        mzIdentityBinPpm = 2.0,
    )
}
