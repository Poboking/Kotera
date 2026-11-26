package com.eratw.port.modapi

/**
 * 作者面快照闸的纯文本判定层（解析 / 差异 / 提示）。
 *
 * 为什么单独一层：负控用例要在不落盘、不改 main 的前提下构造「坏快照 / 坏 KDoc」，
 * 判定写成纯函数才能连「红消息本身」一起断言，而不是靠真失败制造红。
 *
 * 两条版本正则与 `tools/modgen` 的 ModCheckApiSurface **逐字同源**，由
 * ModApiSurfaceVersionFormatTest 的哨兵用例做文本比对：只改一侧会让作者面文档与
 * check-mod 的版本检查口径分叉，所以必须两处同改。
 */
internal object ModApiSurfaceGate {

    /** 快照首行前缀；头部必须是「// surface v<当前 ModApi.VERSION>」。 */
    const val HEADER_PREFIX = "// surface v"

    /** 与 ModCheckApiSurface.VERSION_CONST 同源：取 const val VERSION: Int = <n> 的数字。 */
    const val VERSION_CONST_PATTERN = """\bconst\s+val\s+VERSION\s*(?::\s*Int\s*)?=\s*(\d+)"""

    /** 与 ModCheckApiSurface.VERSION_ENTRY 同源：KDoc「当前取值」表的 `` `<n>` = `` 段。 */
    const val VERSION_ENTRY_PATTERN = """^`(\d+)`\s*="""

    private const val CURRENT_VALUES_HEADING = "## 当前取值"
    private const val MAX_DIFF_LINES = 60
    private const val MISSING_HEADER = "缺头部/头部格式坏"
    private const val MISMATCH_HINT =
        "作者面反射结果与入库快照不一致（- = 快照有而反射面没有，+ = 反射面新增）。\n" +
            "处理顺序：① 确认签名变更是有意的 ② bump ModApi.VERSION ③ 补 ModApi.kt 的 KDoc「" +
            CURRENT_VALUES_HEADING + "」段 ④ -Deratw.refreshApiSurface=true 刷新快照。\n差异行：\n"

    private val headerPattern = Regex("^" + Regex.escape(HEADER_PREFIX) + """(\d+)$""")
    private val versionConst = Regex(VERSION_CONST_PATTERN)
    private val versionEntry = Regex(VERSION_ENTRY_PATTERN)

    /** 快照头单行文本（刷新与断言共用，避免两处各拼一次格式）。 */
    fun headerLine(version: Int): String = HEADER_PREFIX + version

    /** 解析快照首行的版本；缺失或格式坏返回 null。 */
    fun headerVersion(snapshotText: String): Int? =
        snapshotText.lineSequence().firstOrNull()
            ?.let { line -> headerPattern.find(line)?.groupValues?.get(1)?.toIntOrNull() }

    /** 断言 b：快照头版本必须等于当前 ModApi.VERSION；通过返回 null，否则返回红消息。 */
    fun checkHeaderVersion(snapshotText: String, version: Int): String? {
        val declared = headerVersion(snapshotText)
        if (declared == version) return null
        val actual = declared?.let(::headerLine) ?: MISSING_HEADER
        return "快照头版本与 ModApi.VERSION 不一致：期望 " + headerLine(version) + "，实际 " + actual +
            "。请刷新快照（-Deratw.refreshApiSurface=true），并核对 KDoc 版本表。"
    }

    /** KDoc 行清洗：与 ModCheckApiSurface 的 KDoc 行处理同口径（去行首空白与一个星号）。 */
    fun cleanKdocLine(raw: String): String = raw.trimStart().removePrefix("*").trim()

    /** 抽出 ModApi.kt KDoc「当前取值」表里登记的全部版本号。 */
    fun kdocVersionNumbers(modApiSource: String): List<Int> =
        modApiSource.lines()
            .map(::cleanKdocLine)
            .mapNotNull { line -> versionEntry.find(line)?.groupValues?.get(1)?.toIntOrNull() }

    /** 断言 c：KDoc「当前取值」表必须存在当前 VERSION 段；通过返回 null，否则返回红消息。 */
    fun checkKdocVersion(modApiSource: String, version: Int): String? {
        val registered = kdocVersionNumbers(modApiSource)
        if (version in registered) return null
        return "ModApi.kt 的 KDoc「" + CURRENT_VALUES_HEADING + "」表没有 `" + version + "` 段；" +
            "bump VERSION 时必须补该段（当前已登记：" + registered.joinToString(", ") + "）。"
    }

    /** 用与 check-mod 同源的正则解析 ModApi.kt 里的 VERSION 常量；找不到返回 null。 */
    fun versionConstNumber(modApiSource: String): Int? =
        versionConst.find(modApiSource)?.groupValues?.get(1)?.toIntOrNull()

    /** 断言 a：反射面文本必须与入库快照逐行一致；通过返回 null，否则返回带 diff 的红消息。 */
    fun checkSurface(snapshotText: String, currentText: String): String? {
        val diff = diffLines(snapshotText, currentText)
        if (diff.isEmpty()) return null
        return MISMATCH_HINT + diff.joinToString("\n")
    }

    /** 逐行 diff：只报「快照有而反射面没有」「反射面有而快照没有」两类，行序稳定且封顶。 */
    fun diffLines(snapshotText: String, currentText: String): List<String> {
        val stored = snapshotText.lines().filter { line -> line.isNotBlank() }
        val current = currentText.lines().filter { line -> line.isNotBlank() }
        val storedSet = stored.toSet()
        val currentSet = current.toSet()
        val removed = stored.filterNot { line -> line in currentSet }.map { line -> "- " + line }
        val added = current.filterNot { line -> line in storedSet }.map { line -> "+ " + line }
        return (removed + added).take(MAX_DIFF_LINES)
    }

    /** 拼出 `NAME = Regex("""PATTERN""")`，供哨兵用例去 check-mod 源码里找同字面量声明。 */
    fun regexDeclarationLine(name: String, pattern: String): String =
        name + " = Regex(\"\"\"" + pattern + "\"\"\")"
}
