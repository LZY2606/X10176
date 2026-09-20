package peakdossier.domain

/**
 * 谱峰文本导入：支持逗号/制表符/分号分隔，表头中英文别名。
 * 损坏的扫描行被隔离并返回逐行错误；其余行照常进入。
 */
object CsvIngest {

    private val mzAliases = setOf("mz", "m/z", "mass", "质量", "质荷比", "mzvalue")
    private val intAliases = setOf("intensity", "i", "abundance", "强度", "丰度", "响应")
    private val scanAliases = setOf("scan", "rt", "time", "scantime", "retentiontime", "扫描", "扫描时间", "保留时间", "时间")
    private val polAliases = setOf("polarity", "ionmode", "mode", "极性", "离子模式")

    data class Outcome(val peaks: List<RawPeak>, val errors: List<IngestRow>)

    fun parse(content: String): Outcome {
        val cleaned = content.replace("\uFEFF", "")
        val lines = cleaned.lines().filterIndexed { idx, line -> idx == 0 || line.isNotBlank() }
        require(lines.isNotEmpty()) { "文件为空" }
        val header = splitRow(lines[0]).map { normalizeHeader(it) }
        val iMz = findColumn(header, mzAliases)
        val iInt = findColumn(header, intAliases)
        val iScan = findColumn(header, scanAliases)
        val missing = buildList {
            if (iMz == null) add("m/z")
            if (iInt == null) add("强度")
            if (iScan == null) add("扫描时间")
        }
        require(missing.isEmpty()) { "表头缺少必需列: $missing；实际表头: ${lines[0]}" }
        val mzCol = iMz!!; val intCol = iInt!!; val scanCol = iScan!!
        val iPol = findColumn(header, polAliases)
        val peaks = mutableListOf<RawPeak>()
        val errors = mutableListOf<IngestRow>()
        for (idx in 1 until lines.size) {
            val raw = lines[idx]
            val lineNo = idx + 1
            if (raw.isBlank()) continue
            val cells = splitRow(raw)
            try {
                require(cells.size >= header.size) { "列数不足：期望 ${header.size}，实际 ${cells.size}" }
                val mz = cells[mzCol].trim().toDouble()
                val intensity = cells[intCol].trim().toDouble()
                val scan = cells[scanCol].trim().toDouble()
                require(mz > 0) { "m/z 必须为正数: $mz" }
                require(intensity >= 0) { "强度不能为负: $intensity" }
                require(scan >= 0) { "扫描时间不能为负: $scan" }
                val polarity = if (iPol != null) Polarity.parse(cells[iPol]) else Polarity.POSITIVE
                peaks += RawPeak(mz, intensity, scan, polarity)
            } catch (ex: Exception) {
                errors += IngestRow(lineNo, raw, ex.message ?: "无法解析")
            }
        }
        return Outcome(peaks, errors)
    }

    private fun findColumn(header: List<String>, aliases: Set<String>): Int? {
        val idx = header.indexOfFirst { it in aliases }
        return if (idx >= 0) idx else null
    }

    private fun normalizeHeader(h: String): String =
        h.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")

    private fun splitRow(line: String): List<String> {
        val sep = when {
            line.contains('\t') -> '\t'
            line.contains(';') -> ';'
            else -> ','
        }
        return line.split(sep)
    }
}
