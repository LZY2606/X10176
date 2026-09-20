package peakdossier.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 谱峰案卷的纯计算内核：不碰数据库，便于单元测试。 */
object Engine {

    // ---------- 稳定身份 ----------

    /** 相同规范化 m/z / scan / 极性 => 相同身份；强度不参与。 */
    fun stablePeakKey(polarity: Polarity, mz: Double, scan: Double): String =
        "P${polarity.code}_mz%.6f_rt%.4f".format(mz, scan)

    // ---------- 质量误差 ----------

    /** ppm 误差（带符号）。 */
    fun ppmError(observed: Double, expected: Double): Double =
        (observed - expected) / expected * 1_000_000.0

    /** 同时满足绝对误差(Da)与相对误差(ppm)边界才算命中。 */
    fun withinTolerance(observed: Double, expected: Double, ppm: Double, absDa: Double): Boolean {
        val daErr = abs(observed - expected)
        val ppmErr = abs(ppmError(observed, expected))
        return daErr <= absDa && ppmErr <= ppm
    }

    // ---------- 加合物 / 同位素理论 ----------

    /** 加合后观测 m/z = (中性质量 + 加合质量 + z*电子质量) / |z| */
    fun adductMz(neutralMass: Double, adduct: AdductSpec): Double {
        val z = adduct.charge
        val electron = if (z > 0) -z * ELECTRON_MASS else z * ELECTRON_MASS
        return (neutralMass + adduct.mass + electron) / abs(z)
    }

    /** 第 i 个同位素峰的理论 m/z（相邻约 1.003355 Da，按电荷折算）。 */
    fun isotopeMz(monoisotopicMz: Double, charge: Int, index: Int): Double =
        monoisotopicMz + index * NEUTRON_MASS / abs(charge)

    // ---------- 经验式同位素丰度（简化模型，仅用于期望比例标记） ----------

    private val formulaRegex = Regex("([A-Z][a-z]?)(\\d*)")

    fun formulaComposition(formula: String): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        var consumed = 0
        for (m in formulaRegex.findAll(formula)) {
            if (m.range.first != consumed) break
            consumed = m.range.last + 1
            val el = m.groupValues[1]
            val n = m.groupValues[2].ifEmpty { "1" }.toInt()
            out[el] = (out[el] ?: 0) + n
        }
        require(consumed == formula.length) { "无法解析经验式: $formula" }
        return out
    }

    /** M+1 相对丰度（百分数），基于 C/N/H/O/S/Si 的常见同位素贡献。 */
    fun expectedIsotopeRatio(formula: String, index: Int): Double {
        if (index == 0) return 1.0
        val c = formulaComposition(formula)
        val nC = c["C"] ?: 0
        val nN = c["N"] ?: 0
        val nH = c["H"] ?: 0
        val nS = c["S"] ?: 0
        val nSi = c["Si"] ?: 0
        val p1 = nC * 1.07 + nN * 0.37 + nH * 0.015 + nS * 0.75 + nSi * 5.1
        return when (index) {
            1 -> p1 / 100.0
            2 -> {
                val p2 = nC * (nC - 1) / 2.0 * 0.01145 + (c["O"] ?: 0) * 0.205 + nS * 4.4 + nSi * 3.35
                (p2 + p1 * p1 / 200.0) / 100.0
            }
            else -> 0.0
        }
    }

    // ---------- 校准 ----------

    fun fitCalibration(points: List<CalibrationPoint>): CalibrationModel {
        require(points.isNotEmpty()) { "至少需要一个校准点" }
        val (slope, intercept) = when {
            points.size == 1 -> 1.0 to (points[0].referenceMz - points[0].measuredMz)
            else -> {
                val n = points.size
                val sx = points.sumOf { it.measuredMz }
                val sy = points.sumOf { it.referenceMz }
                val sxx = points.sumOf { it.measuredMz.pow(2) }
                val sxy = points.sumOf { it.measuredMz * it.referenceMz }
                val denom = n * sxx - sx * sx
                val b = (n * sxy - sx * sy) / denom
                val a = (sy - b * sx) / n
                b to a
            }
        }
        val rms = if (points.size >= 2) {
            sqrt(points.sumOf {
                val cal = slope * it.measuredMz + intercept
                ppmError(cal, it.referenceMz).pow(2)
            } / points.size)
        } else 0.0
        return CalibrationModel(slope, intercept, rms, points)
    }

    // ---------- 候选生成 ----------

    data class ViewPeak(
        val peakKey: String,
        val mz: Double,           // 校准后参与匹配的 m/z
        val rawMz: Double,
        val intensity: Double,
        val scan: Double,
        val polarity: Polarity,
        val contaminant: Boolean = false,
    )

    /**
     * 在一个 (观测, 校准, 基线, 规则) 视图上为全部目标生成候选。
     * 每个 (目标 × 加合物) 至多一个候选；污染峰与低于基线的峰不参与。
     */
    fun generateCandidates(
        peaks: List<ViewPeak>,
        calibration: CalibrationModel,
        baseline: BaselineParams,
        rules: RuleConfig,
        calibrationVersion: Int,
    ): List<CandidateView> {
        val usable = peaks.filter {
            !it.contaminant && it.intensity >= baseline.noiseFloor
        }
        val result = mutableListOf<CandidateView>()
        for (target in rules.targets) {
            for (adduct in rules.adducts) {
                val polarity = Polarity.valueOf(adduct.polarity.uppercase())
                if (!adduct.applies(polarity)) continue
                val theo0 = adductMz(target.neutralMass, adduct)
                val pool = usable.filter { it.polarity == polarity }
                val anchor = pool
                    .filter { it.scan in target.rtMin..target.rtMax }
                    .filter {
                        withinTolerance(it.mz, theo0, rules.ppmTolerance, rules.absToleranceDa)
                    }
                    .maxByOrNull { it.intensity }
                    ?: continue

                val z = abs(adduct.charge)
                val used = linkedSetOf(anchor.peakKey)
                val isoObs = mutableListOf<IsotopeObservation>()
                val anchorRatio = anchor.intensity.coerceAtLeast(1.0)
                val missing = mutableListOf<Int>()
                var prevObs = anchor.mz
                isoObs += IsotopeObservation(
                    index = 0, expectedMz = theo0, peakKey = anchor.peakKey,
                    observedMz = anchor.rawMz,
                    errorPpm = ppmError(anchor.mz, theo0),
                    errorDa = anchor.mz - theo0,
                    spacingDa = null, intensity = anchor.intensity,
                    expectedRatio = 1.0, observedRatio = 1.0,
                )

                for (i in 1..target.maxIsotopes) {
                    val theoI = isotopeMz(theo0, z, i)
                    val expectedRatio = expectedIsotopeRatio(target.formula, i)
                    val expectedIntensity = anchorRatio * expectedRatio
                    // 期望本身低于基线的同位素不计为“缺失”，只是未期望。
                    val belowDetection = expectedIntensity < baseline.noiseFloor
                    val hit = pool
                        .filter { it.peakKey !in used && it.scan in target.rtMin..target.rtMax }
                        .filter { withinTolerance(it.mz, theoI, rules.ppmTolerance, rules.absToleranceDa) }
                        .maxByOrNull { it.intensity }
                    if (hit != null) {
                        used += hit.peakKey
                        val spacing = (hit.mz - prevObs) * z
                        prevObs = hit.mz
                        isoObs += IsotopeObservation(
                            index = i, expectedMz = theoI, peakKey = hit.peakKey,
                            observedMz = hit.rawMz,
                            errorPpm = ppmError(hit.mz, theoI),
                            errorDa = hit.mz - theoI,
                            spacingDa = spacing,
                            intensity = hit.intensity,
                            expectedRatio = expectedRatio,
                            observedRatio = hit.intensity / anchorRatio,
                        )
                    } else {
                        isoObs += IsotopeObservation(
                            index = i, expectedMz = theoI,
                            expectedRatio = expectedRatio,
                            observedRatio = null, missing = true,
                        )
                        if (!belowDetection) missing += i
                    }
                }

                val score = scoreCandidate(anchor, theo0, isoObs, missing, rules)
                val id = "CAND|${target.compoundId}|${adduct.name}|z${adduct.charge}|${anchor.peakKey}"
                val notes = buildList {
                    add("单同位素峰 ${anchor.peakKey} 同时落在 ±${rules.ppmTolerance} ppm 与 ±${rules.absToleranceDa} Da 边界内")
                    add("保留时间 ${anchor.scan} 位于窗口 [${target.rtMin}, ${target.rtMax}]")
                    if (missing.isNotEmpty()) add("缺失低强度同位素位置: $missing（不视为峰争用冲突）")
                }
                val explanation = CandidateExplanation(
                    ruleVersion = rules.version,
                    calibrationVersion = calibrationVersion,
                    monoisotopicPeak = anchor.peakKey,
                    monoisotopicErrorPpm = ppmError(anchor.mz, theo0),
                    monoisotopicErrorDa = anchor.mz - theo0,
                    adduct = adduct.name, charge = adduct.charge,
                    polarity = polarity.name,
                    rtWindow = listOf(target.rtMin, target.rtMax),
                    observedRt = anchor.scan,
                    isotopes = isoObs,
                    usedPeakKeys = used.toList(),
                    missingIsotopes = missing,
                    score = score,
                    scoreNotes = notes,
                )
                result += CandidateView(
                    candidateId = id,
                    compoundId = target.compoundId,
                    compoundName = target.name,
                    adduct = adduct.name,
                    charge = adduct.charge,
                    polarity = polarity.name,
                    mzTheoretical = theo0,
                    rt = anchor.scan,
                    monoisotopicPeak = anchor.peakKey,
                    usedPeakKeys = used.toList(),
                    score = score,
                    explanation = explanation,
                )
            }
        }
        return result.sortedByDescending { it.score }
    }

    private fun scoreCandidate(
        anchor: ViewPeak,
        theo0: Double,
        isotopes: List<IsotopeObservation>,
        missing: List<Int>,
        rules: RuleConfig,
    ): Double {
        var score = 100.0
        score -= abs(ppmError(anchor.mz, theo0)) * 2.0
        for (iso in isotopes.drop(1)) {
            if (iso.missing) {
                score -= 4.0
            } else {
                val spacing = iso.spacingDa ?: continue
                if (spacing !in rules.isotopeMinSpacing..rules.isotopeMaxSpacing) score -= 8.0
                val ratioErr = abs((iso.observedRatio ?: 0.0) - iso.expectedRatio)
                score -= ratioErr * 10.0
                score -= abs(iso.errorPpm ?: 0.0) * 0.5
            }
        }
        return kotlin.math.round(score * 100.0) / 100.0
    }

    // ---------- 冲突 ----------

    /** 候选两两检查；冲突 = 至少共享一个被占用的峰。返回最小共享峰集合。 */
    fun detectConflicts(candidates: List<CandidateView>): List<Conflict> {
        val out = mutableListOf<Conflict>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                if (a.charge != b.charge) continue
                if (a.polarity != b.polarity) continue
                val shared = a.usedPeakKeys.intersect(b.usedPeakKeys.toSet()).sorted()
                if (shared.isNotEmpty()) {
                    out += Conflict(
                        candidateA = a.candidateId,
                        candidateB = b.candidateId,
                        compounds = a.compoundName to b.compoundName,
                        sharedPeakKeys = shared,
                    )
                }
            }
        }
        return out
    }

    /**
     * 裁定前校验。返回拒绝原因（为空表示通过）。
     * 缺失同位素不算冲突；仅真实共享峰 / 电荷 / 极性 / RT 窗口问题会拒绝。
     */
    fun adjudicateCheck(
        target: CandidateView,
        accepted: List<CandidateView>,
        targetSpec: TargetSpec,
    ): List<String> {
        val reasons = mutableListOf<String>()
        for (other in accepted) {
            if (other.candidateId == target.candidateId) continue
            val shared = target.usedPeakKeys.intersect(other.usedPeakKeys.toSet()).sorted()
            if (shared.isNotEmpty()) {
                reasons += "峰占用冲突：与已接受候选 ${other.compoundName}(${other.adduct}) 共享峰 $shared"
            }
            if (target.charge != other.charge) {
                reasons += "电荷数不一致：${target.charge} vs ${other.charge}"
            }
            if (target.polarity != other.polarity) {
                reasons += "极性不一致：${target.polarity} vs ${other.polarity}"
            }
        }
        val rt = target.rt
        if (rt == null || rt !in targetSpec.rtMin..targetSpec.rtMax) {
            reasons += "保留时间 $rt 超出窗口 [${targetSpec.rtMin}, ${targetSpec.rtMax}]"
        }
        return reasons
    }
}
