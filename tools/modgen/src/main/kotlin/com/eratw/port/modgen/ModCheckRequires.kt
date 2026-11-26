package com.eratw.port.modgen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `check-mod` 的依赖声明面（v12 `EraMod.requires`）：静态兜住「写错了要等到装载期才炸」的依赖声明。
 *
 * 运行期判据在 [com.eratw.port.core.mod.ModDependencyPlan]：requires 里的包不在本次装载集合、
 * 已停用或没成功启用 ⇒ 本 mod 拒载；依赖成环（含自依赖）⇒ 环上所有 mod 一起拒载。玩家侧表现
 * 只是「mod 没生效」，作者自检必须直接在写法层面报出来。
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析（宁可漏报，不许假红）：
 * - 认 `override val requires = listOf("...", ...)`，也认带类型标注的
 *   `override val requires: List<String> = listOf(...)` 与 getter 形态
 *   `override val requires: List<String> get() = listOf(...)`；
 * - listOf 实参是变量 / 拼接 / 插值（含 `$`）时抽不到确定值 ⇒ 本面整体静默 —— 与
 *   [ModCheckStrings] 的声明侧口径一致：一处抽不到，就不能拿「没抽到的那些」当证据；
 * - 模块里没有 `PACKAGE_ID` 字面量、或没有 requires 声明 ⇒ 什么都不报。
 *
 * 严重度（与运行期后果对齐）：
 * - 包名形态非法 = ERROR（与运行期 packageId 形态闸同口径：首字符字母/数字，其余字母/数字/点/
 *   下划线/连字符）。形态都不对的依赖名不可能被任何载体匹配上；
 * - 同一处 requires 里重复声明同一个包 = WARN（重复条目不会额外生效，属冗余写法）；
 * - 自依赖（requires 里出现本 mod 自己的 packageId、且本模块只有一个载体包）= ERROR
 *   （运行期按成环拒载）；
 * - 声明了、但本模块、同目录 `mod-*` 兄弟模块与内置载体集合（[BUILTIN_CARRIER_PACKAGE_IDS]）
 *   里都没有这个 packageId = WARN —— 第三方依赖可能由玩家另外装，静态面看不到安装集合，报
 *   ERROR 会假红；
 * - 静态可解析的成环（A→B→A，环上每个包都能在本模块/兄弟模块里找到载体）= ERROR，点名环成员。
 *
 * 多包模块（一个模块多个 `PACKAGE_ID`，如 livingworld）：文本面无法把某个 requires 声明归属到
 * 具体载体，故跳过自依赖与成环（可能把「同模块两个包互相依赖」误判成自依赖/成环），只保留
 * 形态、重复、兄弟缺失三条与归属无关的检查。
 *
 * 与运行期 packageId 形态闸同源：本面的正则与 `core-vm` 的 `ModResourceStore` 私有常量无法直接
 * 复用，靠 `ModCheckRequiresTest.packageIdShapeStaysInSyncWithModResourceStore` 的文本哨兵防漂移。
 *
 * 内置载体包名集合是第二个真源：源码面只看得到「同仓 `mod-*` 兄弟目录」，看不到玩家机器上永远
 * 在场的内置 mod（family / memory / livingworld）。[BUILTIN_CARRIER_PACKAGE_IDS] 是各 `mod-*`
 * 模块 `Declarations.kt` 里 `const val PACKAGE_ID` 的副本，个数与集合由
 * `ModCheckRequiresTest.builtinCarrierIdsStayInSyncWithRepoDeclarations` 的文本哨兵钉死
 * （照 [ModStringsLimits] 的「常量副本 + 真源文本哨兵」口径）。
 */
internal object ModCheckRequires {

    private const val FIRST_GROUP = 1

    private const val SIBLING_PREFIX = "mod-"

    /**
     * 本仓内置载体的 `PACKAGE_ID` 单源副本（真源 = 会随包分发的 `mod-*` 模块 `Declarations.kt`
     * 里的 `const val PACKAGE_ID`）。玩家机器上这些 mod 永远在场，第三方 mod 依赖它们时静态面
     * 必须认，否则会假 WARN「依赖包未找到」。
     *
     * **示例模块不计入**：`mod-template`（本模块自带 `eratw.template` 与 `eratw.template.matrix`）
     * 与 `mod-testing` 不随 app 分发，它们的载体只是给作者抄的样例 —— 把它们当「永远在场」会让
     * check-mod 对真实第三方依赖静默放过。这条排除与 `:mod-registry` 构建期扫描的排除表同口径。
     *
     * 漂移由 `ModCheckRequiresTest.builtinCarrierIdsStayInSyncWithRepoDeclarations` 兜底。
     */
    internal val BUILTIN_CARRIER_PACKAGE_IDS: List<String> = listOf(
        "eratw.family",
        "eratw.lw.character",
        "eratw.lw.event",
        "eratw.lw.memory",
        "eratw.lw.relationship",
        "eratw.memory",
    )

    /**
     * 与运行期 packageId 形态闸同口径（`core-vm` 的 `ModResourceStore` 私有常量）；`pack-mod` 的
     * 旁挂资源目录名也复用它（见 [isMediaDirName]）—— 包身份字符集只有这一份。
     */
    internal val PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /**
     * `override val requires ... = listOf(...)` 的一处声明；组 1 = listOf 实参原文。
     *
     * 允许 `listOf<String>(...)` 这种显式类型实参；`[^=\n]*` 覆盖 `: List<String> get() `，故
     * getter 形态 `get() = listOf(...)` 也能抽到（多行 getter 体不行，属「抽不到就静默」）。
     */
    private val REQUIRES_LIST = Regex(
        """override\s+val\s+requires\b[^=\n]*?=\s*listOf\s*(?:<[^>\n]*>)?\s*\(([^)]*)\)""",
    )

    /**
     * listOf 实参里的字符串字面量；组 1 = 字面量正文。
     *
     * 排掉 `$`（插值抽不到确定值）与反斜杠转义（转义串里的引号容易切错边界），前面紧跟反斜杠的
     * 引号不算起始引号 —— 抽不到的实参按「宁可漏报」跳过。
     */
    private val STRING_LITERAL = Regex("""(?<!\\)"([^"\\$]*)"""")

    /** 一处 requires 声明：该 listOf 里的全部字符串字面量（file/line 是各字面量自己的位置）。 */
    private class RequiresList(val values: List<Dependency>)

    /** 一个模块的依赖声明面：载体包 + 全部 requires 声明。 */
    private class ModuleRequires(val packageIds: List<String>, val lists: List<RequiresList>)

    /** 依赖声明里的一处字面量：值是包名，file/line 指向它在源码里的位置。 */
    private class Dependency(val value: String, val file: String, val line: Int)

    /** 依赖面的四类发现；模板里的 `%s` 换成包名。 */
    private enum class Issue(val severity: Severity, private val template: String) {
        ILLEGAL_SHAPE(
            Severity.ERROR,
            "依赖包名形态非法：[%s]（与运行期 packageId 形态闸同口径：" +
                "首字符只许字母/数字，其余只许字母/数字/点/下划线/连字符）",
        ),
        DUPLICATE(
            Severity.WARN,
            "依赖重复声明：[%s] 在同一处 requires 里出现多次；重复条目不会额外生效，建议删成一笔",
        ),
        SELF_DEPENDENCY(
            Severity.ERROR,
            "依赖自指：requires 里出现本 mod 自己的 packageId [%s]" +
                "（运行期按依赖成环整环拒载，本 mod 不会装载，故 ERROR）",
        ),
        MISSING(
            Severity.WARN,
            "依赖包未找到：%s（本模块、同目录 mod-* 兄弟模块与本仓内置载体里都没有这个 packageId；" +
                "若是第三方包、玩家可能另外装，忽略即可）",
        ),
        ;

        fun finding(dependency: Dependency): Finding = Finding(
            severity = severity,
            file = dependency.file,
            line = dependency.line,
            message = template.replace("%s", dependency.value),
        )
    }

    /** 一轮声明核对的可变状态：本处已见包名 + 已报过的自依赖/缺失（同值只报一次，免得刷屏）。 */
    private class CheckState(
        val selfPackage: String?,
        val resolvable: List<String>,
        val findings: MutableList<Finding>,
    ) {
        val reportedSelf = mutableSetOf<String>()
        val reportedMissing = mutableSetOf<String>()
    }

    /** 逐个 mod 检查 requires 声明：形态/重复/自依赖/兄弟缺失，单包模块再查静态成环。 */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val ownPackages = ModCheckResources.packageIds(sources)
        val lists = requiresLists(sources)
        if (ownPackages.isEmpty() || lists.isEmpty()) return
        val dependencies = lists.flatMap { list -> list.values }
        if (dependencies.isEmpty()) return
        val siblings = siblingModules(root).mapNotNull(::moduleRequires)
        val resolvable = (ownPackages + siblings.flatMap { sibling -> sibling.packageIds } +
            BUILTIN_CARRIER_PACKAGE_IDS).distinct()
        val selfPackage = if (ownPackages.size == 1) ownPackages.single() else null
        val state = CheckState(selfPackage, resolvable, findings)
        lists.forEach { list -> checkList(list, state) }
        if (selfPackage != null) {
            checkCycle(selfPackage, dependencies, siblings, findings)
        }
    }

    /** 核对一处 requires 声明里的全部依赖。 */
    private fun checkList(list: RequiresList, state: CheckState) {
        val seen = mutableSetOf<String>()
        list.values.forEach { dependency -> checkDependency(dependency, seen, state) }
    }

    /** 单个依赖的核对：形态非法 ERROR、重复 WARN、自依赖 ERROR（仅单包模块）、兄弟缺失 WARN。 */
    private fun checkDependency(dependency: Dependency, seen: MutableSet<String>, state: CheckState) {
        val value = dependency.value
        if (!PACKAGE_ID_PATTERN.matches(value)) {
            state.findings += Issue.ILLEGAL_SHAPE.finding(dependency)
        } else {
            if (!seen.add(value)) state.findings += Issue.DUPLICATE.finding(dependency)
            if (value == state.selfPackage && state.reportedSelf.add(value)) {
                state.findings += Issue.SELF_DEPENDENCY.finding(dependency)
            }
            if (value != state.selfPackage && value !in state.resolvable && state.reportedMissing.add(value)) {
                state.findings += Issue.MISSING.finding(dependency)
            }
        }
    }

    /** 静态成环检查：只信单包模块（多包模块无法归属，参与建图会假红）。 */
    private fun checkCycle(
        start: String,
        dependencies: List<Dependency>,
        siblings: List<ModuleRequires>,
        findings: MutableList<Finding>,
    ) {
        val graph = mutableMapOf<String, List<String>>()
        graph[start] = dependencies.map { dependency -> dependency.value }
        siblings.filter { sibling -> sibling.packageIds.size == 1 }.forEach { sibling ->
            graph[sibling.packageIds.single()] = sibling.lists
                .flatMap { list -> list.values }
                .map { value -> value.value }
        }
        val cycle = findCycle(start, graph) ?: return
        val origin = dependencies.first()
        findings += Finding(
            severity = Severity.ERROR,
            file = origin.file,
            line = origin.line,
            message = "依赖成环：${cycle.joinToString(" -> ")}（运行期把成环的整环 mod 一起拒载，" +
                "故 ERROR；环上每个包都能在静态面里找到载体）",
        )
    }

    /**
     * 找一条从 [start] 出发回到 [start] 的环（BFS + 父指针）；找不到返回 null。
     *
     * 自环不在这里报（[Issue.SELF_DEPENDENCY] 已按 ERROR 报过），建图时直接跳过「依赖自己」的边。
     */
    private fun findCycle(start: String, graph: Map<String, List<String>>): List<String>? {
        val parent = mutableMapOf<String, String>()
        val queue = ArrayDeque<String>()
        graph[start].orEmpty().filter { dependency -> dependency != start && dependency in graph }
            .forEach { dependency ->
                parent[dependency] = start
                queue += dependency
            }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (graph[node].orEmpty().contains(start)) {
                val cycle = mutableListOf(node)
                var current: String? = parent[node]
                while (current != null && current != start) {
                    cycle += current
                    current = parent[current]
                }
                cycle += start
                return cycle.reversed() + start
            }
            graph[node].orEmpty().filter { dependency -> dependency in graph && dependency !in parent }
                .forEach { dependency ->
                    parent[dependency] = node
                    queue += dependency
                }
        }
        return null
    }

    /** 扫描 [sources] 里的全部 requires 声明；抽不到任何字面量的声明直接丢（不产生空声明）。 */
    private fun requiresLists(sources: List<SourceFile>): List<RequiresList> = sources.flatMap { source ->
        val text = codeText(source)
        REQUIRES_LIST.findAll(text).mapNotNull { match ->
            val group = match.groups[FIRST_GROUP]
            val offset = group?.range?.first ?: 0
            val values = STRING_LITERAL.findAll(group?.value.orEmpty()).map { literal ->
                val line = lineOf(text, offset + literal.range.first)
                Dependency(literal.groupValues[FIRST_GROUP], source.label, line)
            }.toList()
            if (values.isEmpty()) null else RequiresList(values)
        }.toList()
    }

    /** 一个模块的依赖声明面；没有载体包（没有 `PACKAGE_ID` 字面量）时返回 null。 */
    private fun moduleRequires(root: Path): ModuleRequires? {
        val findings = mutableListOf<Finding>()
        val sources = ModCheckSources.scan(root, findings)
        val packageIds = ModCheckResources.packageIds(sources)
        return if (packageIds.isEmpty()) null else ModuleRequires(packageIds, requiresLists(sources))
    }

    /** 同目录下的 `mod-*` 兄弟模块（不含自己）；读不到目录就静默当没有。 */
    private fun siblingModules(root: Path): List<Path> {
        val parent = root.parent ?: return emptyList()
        return try {
            Files.newDirectoryStream(parent, "$SIBLING_PREFIX*").use { entries ->
                entries.filter { entry -> Files.isDirectory(entry) && entry != root }
                    .sortedBy { entry -> entry.fileName.toString() }
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** 去注释后的整文件文本（行间用换行连回，跨行正则可匹配、行号可回算）。 */
    private fun codeText(source: SourceFile): String = source.code.joinToString("\n") { line -> line.text }

    /** [text] 里 [offset] 处的 1 基行号（[text] 是 [codeText] 的产物，行号与原始文件一致）。 */
    private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1
}
