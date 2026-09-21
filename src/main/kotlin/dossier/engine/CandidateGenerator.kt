package dossier.engine

import dossier.db.Database
import dossier.chem.Chemistry
import dossier.model.*
import kotlin.math.abs

data class GeneratedRun(
    val runId: String,
    val versionId: String,
    val candidates: List<CandidateRecord>,
) {
    val id: String get() = runId
}

class CandidateGenerator(private val db: Database, private val store: Store) {

    /**
     * 在指定版本上按当前版本所引用的规则配置生成候选。规则升级后产生的是新 run，
     * 旧 run 与旧解释仍按其 configVersion 保留、可查看。
     */
    fun generate(branchId: String, baseVersionId: String = store.headVersion(branchId)): GeneratedRun {
        val state = store.getVersion(baseVersionId)
        val config = store.config(state.configVersion)
        val rules = config.candidateRules
        val runId = Store.newId("run")
        val now = java.time.Instant.now().toString()
        val records = mutableListOf<CandidateRecord>()

        val activePeaks = state.peaks.values.filter { it.peak.status != "contaminant" }

        for (target in rules.targets) {
            for (adduct in rules.positiveAdducts + rules.negativeAdducts) {
                if (abs(adduct.z) > rules.maxCharge) continue
                val polarity = if (adduct.z > 0) Polarity.POSITIVE else Polarity.NEGATIVE
                val expectedM0 = Chemistry.monoisotopicMz(target.neutralMass, adduct)

                for (anchor in activePeaks.filter { it.peak.polarity == polarity }) {
                    val m0 = anchor.peak.calibratedMz
                    if (!Chemistry.withinTolerance(m0, expectedM0, rules.ppmTolerance, rules.absoluteToleranceDa)) continue

                    val isotopeMatches = mutableListOf<IsotopeMatch>()
                    val usedPeakIds = mutableSetOf(anchor.peak.id)
                    val claimedByThisCandidate = mutableSetOf(anchor.peak.id)
                    var presentIsotopes = 0
                    var isotopeScore = 0.0

                    for (order in 1..rules.isotopeCount) {
                        val spacing = Chemistry.isotopeSpacing(order, abs(adduct.z))
                        val expectedMz = expectedM0 + spacing
                        val expectedRatio = Chemistry.expectedIsotopeRatio(
                            target.formula, order, target.neutralMass
                        )
                        val candidates = activePeaks
                            .filter { it.peak.polarity == polarity && it.peak.id !in claimedByThisCandidate }
                            .filter { peak ->
                                val rtOk = abs(peak.peak.rtSeconds - anchor.peak.rtSeconds) <= rules.rtWindowSeconds
                                val mzOk = Chemistry.withinTolerance(
                                    peak.peak.calibratedMz, expectedMz,
                                    rules.ppmTolerance, rules.absoluteToleranceDa
                                )
                                rtOk && mzOk
                            }
                        val expectedIntensity = anchor.peak.intensity * expectedRatio
                        // 缺失判定阈值：基线噪声或预期强度不足时，缺失不算冲突
                        val best = candidates
                            .filter { it.peak.intensity >= config.baseline.noiseIntensity }
                            .minByOrNull {
                                val d = it.peak.calibratedMz - m0
                                abs((d - spacing) / spacing)
                            }
                        if (best != null) {
                            val obsSpacing = best.peak.calibratedMz - m0
                            val spacingPpm = (obsSpacing - spacing) / spacing * 1e6
                            // 只有间距也合格才把该峰算作已观测同位素；否则视为缺失，不占用该峰
                            if (abs(spacingPpm) <= rules.isotopeSpacingTolerancePpm) {
                                val obsRatio = best.peak.intensity / anchor.peak.intensity
                                val match = IsotopeMatch(
                                    order = order,
                                    expectedMz = expectedMz,
                                    observedPeakId = best.peak.id,
                                    observedMz = best.peak.calibratedMz,
                                    ppmError = Chemistry.ppmError(best.peak.calibratedMz, expectedMz),
                                    daError = best.peak.calibratedMz - expectedMz,
                                    expectedSpacingDa = spacing,
                                    observedSpacingDa = obsSpacing,
                                    spacingPpmError = spacingPpm,
                                    expectedRatio = expectedRatio,
                                    observedRatio = obsRatio,
                                    present = true,
                                )
                                isotopeMatches += match
                                claimedByThisCandidate += best.peak.id
                                usedPeakIds += best.peak.id
                                presentIsotopes++
                                isotopeScore += 1.0 - (abs(match.ppmError!!) / rules.ppmTolerance).coerceIn(0.0, 1.0) * 0.2
                                val ratioPenalty = if (expectedIntensity > 0)
                                    (abs(obsRatio - expectedRatio) / expectedRatio).coerceIn(0.0, 1.0) * 0.1 else 0.0
                                isotopeScore -= ratioPenalty
                            } else {
                                isotopeMatches += IsotopeMatch(
                                    order = order,
                                    expectedMz = expectedMz,
                                    observedPeakId = null,
                                    expectedSpacingDa = spacing,
                                    observedSpacingDa = obsSpacing,
                                    spacingPpmError = spacingPpm,
                                    expectedRatio = expectedRatio,
                                    present = false,
                                )
                            }
                        } else {
                            // 缺失的低强度同位素：只记录为 missing，不占用观测峰，不与冲突混同
                            isotopeMatches += IsotopeMatch(
                                order = order,
                                expectedMz = expectedMz,
                                observedPeakId = null,
                                expectedSpacingDa = spacing,
                                expectedRatio = expectedRatio,
                                present = false,
                            )
                        }
                    }

                    val ppmErr = Chemistry.ppmError(m0, expectedM0)
                    val daErr = m0 - expectedM0
                    val m0Score = 1.0 - (abs(ppmErr) / rules.ppmTolerance).coerceIn(0.0, 1.0) * 0.5
                    val total = (m0Score + isotopeScore).coerceAtLeast(0.0)
                    val rulesApplied = listOf(
                        "ppmTolerance<=${rules.ppmTolerance}",
                        "absoluteToleranceDa<=${rules.absoluteToleranceDa}",
                        "isotopeSpacingTolerancePpm<=${rules.isotopeSpacingTolerancePpm}",
                        "rtWindowSeconds<=${rules.rtWindowSeconds}",
                        "polarity=${polarity.name}",
                        "charge=${abs(adduct.z)}",
                        "baseline.noiseIntensity=${config.baseline.noiseIntensity}",
                        "baseline.minPeakIntensity=${config.baseline.minPeakIntensity}",
                    )
                    val explanation = CandidateExplanation(
                        target = target.name,
                        adductCode = adduct.code,
                        charge = abs(adduct.z),
                        polarity = polarity,
                        monoisotopicExpectedMz = expectedM0,
                        monoisotopicPeakId = anchor.peak.id,
                        monoisotopicPpmError = ppmErr,
                        monoisotopicDaError = daErr,
                        rtWindowSeconds = rules.rtWindowSeconds,
                        rtSeconds = anchor.peak.rtSeconds,
                        isotopes = isotopeMatches,
                        usedPeakIds = usedPeakIds.toList(),
                        rulesApplied = rulesApplied,
                        configVersion = state.configVersion,
                        score = total,
                    )
                    records += CandidateRecord(Store.newId("cand"), runId, explanation)
                }
            }
        }

        return db.tx { conn ->
            conn.prepareStatement(
                "INSERT INTO runs(id,branch_id,version_id,config_version,created_at) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, runId); ps.setString(2, branchId); ps.setString(3, baseVersionId)
                ps.setInt(4, state.configVersion); ps.setString(5, now); ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO candidates(id,run_id,branch_id,version_id,config_version,explanation_json," +
                        "peak_ids_json,target,monoisotopic_peak_id,polarity,charge,rt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (rec in records) {
                    val e = rec.explanation
                    ps.setString(1, rec.id); ps.setString(2, runId); ps.setString(3, branchId)
                    ps.setString(4, baseVersionId); ps.setInt(5, state.configVersion)
                    ps.setString(6, Database.json.encodeToString(CandidateExplanation.serializer(), e))
                    ps.setString(7, Database.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), e.usedPeakIds))
                    ps.setString(8, e.target); ps.setString(9, e.monoisotopicPeakId)
                    ps.setString(10, e.polarity.name); ps.setInt(11, e.charge)
                    ps.setDouble(12, e.rtSeconds)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val payload = """{"runId":"$runId","configVersion":${state.configVersion},"candidateCount":${records.size}}"""
            val newVersionId = store.appendVersionOn(conn, branchId, "RUN", "analyst", payload, state.configVersion)
            GeneratedRun(runId, newVersionId, records)
        }
    }
}
