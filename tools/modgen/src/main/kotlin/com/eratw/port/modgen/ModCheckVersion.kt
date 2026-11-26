package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi

/**
 * 版本一致性面：载体对 EraMod 版本协商成员（`apiVersion` / `dataVersion` / `onMigrate`）的覆写
 * 是否自洽 —— 这些约束原本只有到设备装载期才会炸，本检查让作者在 CI 里就红。
 *
 * 只读源码文本，不实例化 mod、不装载引擎。抽不到的覆写按 EraMod 缺省值处理：
 * `apiVersion = ModApi.VERSION`、`dataVersion = 0`、无 `onMigrate`。
 *
 * 识别口径是「单行类头 + 类体内覆写」：`class X(...) : EraMod {` 必须在一行内闭合（含泛型/构造
 * 参数），覆写可以在任意行。跨行类头、匿名 `object : EraMod`、以及同一文件里多个 EraMod 实现
 * 共用覆写的写法不区分，按「该文件的 EraMod 实现」整体处理 —— 宁可漏报，不误报。
 */
internal object ModCheckVersion {
    private const val MAIN_KOTLIN_PREFIX = "src/main/kotlin/"
    private const val FIRST_GROUP = 1
    private const val GETTER_SCAN_LINES = 3

    /** 单行类头：`class X(...) : <supertypes> {`；组 1 = 超类型段（冒号后到 `{` 前）。 */
    private val ERA_MOD_CLASS = Regex(
        """\b(?:class|object)\s+\w+\s*(?:<[^>]*>)?\s*(?:\((?:[^()]|\([^()]*\))*\))?\s*:\s*([^{]*)\{""",
    )

    private val ERA_MOD_SUPER = Regex("""\bEraMod\b""")

    private val API_VERSION = Regex("""override\s+val\s+apiVersion\b""")

    private val DATA_VERSION = Regex("""override\s+val\s+dataVersion\b""")

    private val ON_MIGRATE = Regex("""override\s+fun\s+onMigrate\s*\(""")

    private val INT_LITERAL = Regex("""=\s*(-?\d+)\b""")

    private val GETTER_VALUE = Regex("""get\s*\(\s*\)\s*=\s*(-?\d+)\b""")

    /** 对全部手写源码（`src/main/kotlin` 目录树）做版本一致性检查，发现追加进 [findings]。 */
    fun check(sources: List<SourceFile>, findings: MutableList<Finding>) {
        sources.filter { it.label.startsWith(MAIN_KOTLIN_PREFIX) }.forEach { checkFile(it, findings) }
    }

    private fun checkFile(file: SourceFile, findings: MutableList<Finding>) {
        val carrierLine = findEraModClass(file) ?: return
        val api = findIntOverride(file, API_VERSION)
        val data = findIntOverride(file, DATA_VERSION)
        val migrateLine = findOverride(file, ON_MIGRATE)
        val apiVersion = api?.value ?: ModApi.VERSION
        val dataVersion = data?.value ?: 0
        if (apiVersion > ModApi.VERSION) {
            val message = "声明的 apiVersion=$apiVersion 比当前引擎新（ModApi.VERSION=${ModApi.VERSION}）：" +
                "装载期会被拒（把 ModApi.VERSION 提上去或降 mod 的 apiVersion）"
            findings += Finding(Severity.ERROR, file.label, api?.line ?: carrierLine, message)
        }
        if (dataVersion < 0) {
            val message = "dataVersion=$dataVersion 非法：必须 >= 0"
            findings += Finding(Severity.ERROR, file.label, data?.line ?: carrierLine, message)
        }
        if (dataVersion > 0 && migrateLine == null) {
            val message = "声明了 dataVersion=$dataVersion 却没有 override fun onMigrate：" +
                "老档会走缺省空实现，数据格式变化不会被处理"
            findings += Finding(Severity.ERROR, file.label, data?.line ?: carrierLine, message)
        }
        if (dataVersion == 0 && migrateLine != null) {
            val message = "发现 override fun onMigrate 但 dataVersion 仍是缺省 0：" +
                "迁移永远不会被触发（请在载体声明 dataVersion）"
            findings += Finding(Severity.WARN, file.label, migrateLine, message)
        }
    }

    /** 本文件里第一处 EraMod 实现类头的行号；没有则 null。 */
    private fun findEraModClass(file: SourceFile): Int? =
        file.code.firstOrNull { line ->
            ERA_MOD_CLASS.find(line.text)?.groupValues?.get(FIRST_GROUP)?.let(ERA_MOD_SUPER::containsMatchIn) == true
        }?.number

    /** 类体内 `override val <name>` 的整数字面量（同行的 `= N` 或随后几行的 `get() = N`）。 */
    private fun findIntOverride(file: SourceFile, declaration: Regex): ValueAt? {
        val start = file.code.indexOfFirst { declaration.containsMatchIn(it.text) }
        if (start < 0) return null
        return matchValue(file.code.drop(start).take(GETTER_SCAN_LINES))
    }

    private fun matchValue(lines: List<CodeLine>): ValueAt? {
        val declaration = lines.first()
        val inline = INT_LITERAL.find(declaration.text)
            ?.let { ValueAt(it.groupValues[FIRST_GROUP].toInt(), declaration.number) }
        return inline ?: lines.drop(1).firstNotNullOfOrNull { line ->
            GETTER_VALUE.find(line.text)?.let { ValueAt(it.groupValues[FIRST_GROUP].toInt(), line.number) }
        }
    }

    private fun findOverride(file: SourceFile, pattern: Regex): Int? =
        file.code.firstOrNull { pattern.containsMatchIn(it.text) }?.number

    private class ValueAt(val value: Int, val line: Int)
}
