package peakdossier.domain

fun defaultRules(): RuleConfig = RuleConfig(
    version = 1,
    ppmTolerance = 10.0,
    absToleranceDa = 0.005,
    isotopeMinSpacing = 0.9,
    isotopeMaxSpacing = 1.1,
    minIsotopeIntensityRatio = 0.01,
    adducts = listOf(
        AdductSpec("[M+H]+", 1.007276, 1, "POSITIVE"),
        AdductSpec("[M+Na]+", 22.989218, 1, "POSITIVE"),
        AdductSpec("[M+2H]2+", 2.014552, 2, "POSITIVE"),
        AdductSpec("[M-H]-", -1.007276, -1, "NEGATIVE"),
        AdductSpec("[M+HCOO]-", 44.998201, -1, "NEGATIVE"),
    ),
    targets = listOf(
        TargetSpec("CAFF", "咖啡因", "C8H10N4O2", 194.080376, 4.5, 5.5, 3),
        TargetSpec("ISOMER", "咖啡因共洗脱异构体", "C8H10N4O2", 194.080376, 4.5, 5.5, 3),
        TargetSpec("ISOP", "异丙隆", "C12H18N2O", 206.141913, 4.6, 5.6, 2),
        TargetSpec("BENZ", "苯甲酸钠", "C7H5NaO2", 144.018724, 2.0, 3.0, 2), // [M+Na]+ 167.0074；甲酸加合 189.017 不在演示中
        TargetSpec("TRACE", "痕量胺候选", "C10H15NO", 165.11536, 7.0, 8.0, 2),
    ),
)

fun defaultBaseline() = BaselineParams(noiseFloor = 500.0, relativeThreshold = 0.01)

fun identityCalibration() = CalibrationModel(1.0, 0.0, 0.0, emptyList())

/**
 * 演示扫描：
 * - 咖啡因簇（M/M+1/M+2），与其共洗脱异构体争用同一组峰；
 * - 异丙隆、苯甲酸钠（+Na / -H）独立候选；
 * - 痕量胺只有单同位素峰，M+1 缺失（低强度缺失，不算峰冲突）；
 * - 最后两行为损坏扫描，进入隔离区。
 */
fun demoPeakCsv(): String = """mz,intensity,scan,polarity
195.08755,120000,5.02,+
196.09065,8600,5.02,+
197.08935,950,5.02,+
207.14890,95000,5.10,+
208.15210,6200,5.10,+
166.12230,42000,7.45,+
167.00740,20000,2.45,+
143.01150,60000,2.45,-
bad,1000,5.00,+
195.08755,400,-5.0,+
"""
