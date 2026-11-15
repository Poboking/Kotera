package com.eratw.port.vmdbg

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata

/**
 * pc → 源码位置（`文件:行:函数`）的**纯函数层**（MODERN-R4-C）。
 *
 * ## 为什么是「拼」而不是「读」
 *
 * 产物里三块事实各有各的粒度，没有任何一块直接给出「pc → 文件:行:函数」：
 *
 * | 事实 | 粒度 | 来源 |
 * |---|---|---|
 * | 函数名 + `entryPc` | 函数级区间起点 | 容器段 4（`Program.functions`，specs 里 erbc-format 的 §5.5） |
 * | 逐 pc 行号（0 基，`-1` = 未知） | 指令级行号 | 容器段 6 → `Program.lineOf` |
 * | 逐函数 `position`（`相对路径:行号` / 合成标记） | 函数级文件归属 | `program.meta`（`BootMetadata.functions`） |
 *
 * 段 6 的 `fileTableCount` 恒 0（erbc-format 的 §5.7、§9 第 2 项），所以「指令 → 文件」必须
 * **两跳**：pc → 函数区间（本类按 `entryPc` 原序推导）→ 该函数的 `position` 里的文件列。
 * 本类只做这条查询链，**不读文件、不装配会话**：输入是已经装好的 [Program] 与 [BootMetadata]，
 * 因此单测可以在内存里构造任意形状（P1..P6）而不碰真产物。
 *
 * ## 区间推导口径（契约 §2.1 末段）
 *
 * - 函数下标 = [Program.functions] 的**原序**（= `_CALL` 的 a 操作数；erbcc 的容器段 4 按该序写出）。
 * - 区间 = `[entryPc_i, entryPc_{i+1})`，最后一个函数到 `instructionCount`。
 * - **原序不是 entryPc 递增时**（P6）：不改写 `funcIndex`（输出仍用原下标），只把区间表
 *   按 `entryPc` 升序重建（同值按原序稳定），并把「非递增处数」照实计入
 *   [AuditReport.funcNonIncreasingEntry]。
 * - 该口径下区间**无缝**：`end_i = start_{i+1}`，所以「落在间隙」只可能是**头部**
 *   （`pc < min entryPc`）或**尾部**（`entryPc > instructionCount` 的空区间被截成
 *   `[start, instructionCount)` 之外）；本类对二者都报 `owned=false`，不猜。
 *
 * ## 与 `FunctionTable.sourceFileOfIndex` 的分工
 *
 * 那一侧只回「文件显示名」（运行期帧归因，合成标记返回 null）；本层是**离线全量查询面**，
 * 额外给出函数下标、函数区间、逐 pc 行号（0 基 + 1 基两列）与三类 position 形状的显式标记。
 * 判据同源：`position` 以 `·` 开头即合成标记（`FunctionTable.kt` 的 `fileOfPosition`），
 * 不当真实文件。
 *
 * ## 行号两列的口径（钉死）
 *
 * - [SourceLocation.line0] = `Program.lineOf(pc)` **原值**（0 基；`-1` = 未知）
 * - [SourceLocation.line1] = `line0 + 1`（`line0 < 0` 时**原样**，不做 0 基换算）
 *
 * 注意：函数 `position` 里的行号是**声明行（1 基）**，与逐 pc 的 `line0/line1` 不是同一列；
 * 本层把它单独暴露为 [FuncSummary.positionLine]，不混进 `line0`。
 */
class SourceMap private constructor(
    /** 指令条数（= `Program.instructionCount`；pc 合法域 = `[0, instructionCount)`）。 */
    val instructionCount: Int,
    /** 原序函数槽（下标 = 函数下标）。 */
    private val slots: List<FnSlot>,
    /** 区间表：按 `entryPc` 升序（同值按原序），相邻区间首尾相接。 */
    private val spans: List<Span>,
    /** 原下标 → 区间（P6 的原序乱序也照样能按原下标取回）。 */
    private val spanOfIndex: Array<Span?>,
    /** 逐 pc 行号（0 基，`-1` = 未知）；装配期从 [Program.lineOf] 绑一次。 */
    private val lineOf: (Int) -> Int,
) {

    /** 函数条数（= `Program.functions.size`）。 */
    val functionCount: Int get() = slots.size

    /** 原序下标 → 函数名（审计/摘要用；越界返回 null）。 */
    fun functionName(funcIndex: Int): String? = slots.getOrNull(funcIndex)?.name

    /**
     * 单 pc 归属。pc 越界（`< 0` 或 `>= instructionCount`）按「无归属」返回 —— 越界判定是
     * CLI 的活（退出码 2），本层不抛。
     */
    fun at(pc: Int): SourceLocation {
        if (pc < 0 || pc >= instructionCount) return SourceLocation.unowned(pc)
        val slot = spanAt(pc)?.slot ?: return SourceLocation.unowned(pc)
        return locate(pc, slot)
    }

    /**
     * 按名字查函数原下标（IGNORE CASE 全等，口径 = `Config.StrComper = OrdinalIgnoreCase`；
     * 不做 glob、不做前缀）。重名返回**首个**原下标（Program 函数名本就唯一，容器装载对重复名
     * 直接拒载：erbc-format 的 §5.5）。找不到返回 null。
     */
    fun findFunction(name: String): Int? {
        val want = name.uppercase()
        for (s in slots) if (s.name.uppercase() == want) return s.funcIndex
        return null
    }

    /** 函数区间摘要（[funcIndex] = 原下标；越界返回 null）。 */
    fun summarize(funcIndex: Int): FuncSummary? {
        val slot = slots.getOrNull(funcIndex) ?: return null
        val span = spanOfIndex[funcIndex] ?: return null
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        var unknown = 0
        for (pc in span.start until span.end) {
            val l = lineOf(pc)
            if (l < 0) {
                unknown++
            } else {
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
        }
        // 区间内一条有行号的指令都没有（含空区间）⇒ 范围全报 -1，不拿 0 冒充。
        val hasLine = lo != Int.MAX_VALUE
        return FuncSummary(
            funcIndex = slot.funcIndex,
            name = slot.name,
            entryPc = slot.entryPc,
            start = span.start,
            end = span.end,
            file = slot.position.file,
            positionLine = slot.position.startLine,
            positionKind = slot.position.kind,
            line0Min = if (hasLine) lo else -1,
            line0Max = if (hasLine) hi else -1,
            linesUnknown = unknown,
        )
    }

    /**
     * 全量完备性审计：遍历 `[0, instructionCount)` 每个 pc，汇总七计数（另加三个诊断计数）。
     *
     * 计数口径（逐条写死，避免「读数相同、含义不同」）：
     * - `pcTotal` = `instructionCount`
     * - `pcUnowned` = 无归属 pc 数（头部/尾部空隙 + 区间为空）
     * - `funcNoPosition` = `position` 缺失 / 空串 / 不可拆（无冒号、冒号后不是十进制行号）的函数数
     * - `funcSyntheticPosition` = `position` 以 `·` 开头的函数数（P2，不当真实文件）
     * - `funcNonIncreasingEntry` = 原序里 `entryPc_i <= entryPc_{i-1}` 的位置数（P6；只报不修）
     * - `distinctFiles` = **真实文件**（P1 拆出的文件列）去重数；合成标记与不可拆不计
     * - `linesUnknown` = `lineOf(pc) == -1` 的 pc 数（分母 = `pcTotal`，含 unowned）
     */
    fun audit(): AuditReport {
        var unowned = 0
        var linesUnknown = 0
        var pcSynthetic = 0
        var k = 0
        for (pc in 0 until instructionCount) {
            // 区间按 start 升序且首尾相接 ⇒ 顺序扫一遍即可，不必每个 pc 二分。
            while (k + 1 < spans.size && pc >= spans[k + 1].start) k++
            val s = if (spans.isEmpty() || pc < spans[0].start) null else spans[k]
            if (s == null || pc >= s.end) {
                unowned++
            } else if (s.slot.position.kind == PositionKind.SYNTHETIC) {
                pcSynthetic++
            }
            if (lineOf(pc) < 0) linesUnknown++
        }
        var noPosition = 0
        var synthetic = 0
        var nonIncreasing = 0
        var absFiles = 0
        val files = HashSet<String>()
        for (s in slots) {
            when (s.position.kind) {
                PositionKind.NONE, PositionKind.UNSPLITTABLE -> noPosition++
                PositionKind.SYNTHETIC -> synthetic++
                PositionKind.FILE_LINE -> {
                    val f = s.position.file
                    if (f != null) {
                        files.add(f)
                        if (isAbsolutePath(f)) absFiles++
                    }
                }
            }
        }
        for (i in 1 until slots.size) {
            if (slots[i].entryPc <= slots[i - 1].entryPc) nonIncreasing++
        }
        return AuditReport(
            pcTotal = instructionCount,
            pcUnowned = unowned,
            funcNoPosition = noPosition,
            funcSyntheticPosition = synthetic,
            funcNonIncreasingEntry = nonIncreasing,
            distinctFiles = files.size,
            linesUnknown = linesUnknown,
            pcSynthetic = pcSynthetic,
            funcTotal = slots.size,
            funcAbsoluteFile = absFiles,
        )
    }

    /** 区间查找（头部空隙返回 null；尾部/空区间靠 `pc < end` 挡住）。 */
    private fun spanAt(pc: Int): Span? {
        val n = spans.size
        if (n == 0) return null
        if (pc < spans[0].start) return null
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (spans[mid].start <= pc) lo = mid else hi = mid - 1
        }
        val s = spans[lo]
        return if (pc < s.end) s else null
    }

    private fun locate(pc: Int, slot: FnSlot): SourceLocation {
        val l0 = lineOf(pc)
        val l1 = if (l0 < 0) l0 else l0 + 1
        return SourceLocation(
            pc = pc,
            funcName = slot.name,
            funcIndex = slot.funcIndex,
            file = slot.position.file,
            line0 = l0,
            line1 = l1,
            owned = true,
            positionKind = slot.position.kind,
        )
    }

    /** 单个函数槽（原序下标 + 解析后的 position）。 */
    private class FnSlot(
        val funcIndex: Int,
        val name: String,
        val entryPc: Int,
        val position: ParsedPosition,
    )

    /** 区间（`[start, end)`；`start > end` 已被规整成空区间）。 */
    private class Span(val start: Int, val end: Int, val slot: FnSlot)

    companion object {

        /**
         * `position` 的合成标记前缀：erbcc 给「合成事件入口」写的占位
         * （`·event-entry·`，与 `FunctionTable.fileOfPosition` 判 null 的判据同源）。
         */
        const val SYNTHETIC_PREFIX = "·"

        /** 正式入口：`Program`（函数表 + 逐 pc 行号）+ `BootMetadata`（逐函数 position）。 */
        fun of(program: Program, metadata: BootMetadata): SourceMap {
            // 侧车按 FuncMeta.index 归位（不是列表序）：事件成员的 index 就是 Program 函数下标。
            // 同一 index 多条时**后写者生效** —— 与 FunctionTable 的 byIndex 装配同口径
            // （FuncMeta.kt 的 init：`for (m in sorted) arr[m.index] = m`）。
            val posByIndex = HashMap<Int, String>(metadata.functions.size * 2)
            for (m in metadata.functions) posByIndex[m.index] = m.position
            val fns = program.functions
            val names = ArrayList<String>(fns.size)
            val entries = ArrayList<Int>(fns.size)
            val positions = ArrayList<String?>(fns.size)
            for (i in fns.indices) {
                names += fns[i].name
                entries += fns[i].entryPc
                positions += posByIndex[i]
            }
            return build(names, entries, program.instructionCount, positions) { program.lineOf(it) }
        }

        /**
         * 内存构造（单测/夹具用；与 [of] 走**同一条**装配逻辑，不另立一套判据）。
         *
         * 四个列表等长，下标即函数原下标；`positions[i]` 为 null = 该函数侧车无记录。
         */
        fun build(
            functionNames: List<String>,
            entryPcs: List<Int>,
            instructionCount: Int,
            positions: List<String?>,
            lineOf: (Int) -> Int,
        ): SourceMap {
            require(functionNames.size == entryPcs.size) { "函数名/entryPc 长度不一致" }
            require(functionNames.size == positions.size) { "函数名/position 长度不一致" }
            require(instructionCount >= 0) { "instructionCount 不得为负: $instructionCount" }
            val slots = ArrayList<FnSlot>(functionNames.size)
            for (i in functionNames.indices) {
                slots += FnSlot(i, functionNames[i], entryPcs[i], parsePosition(positions[i]))
            }
            // 排序只影响区间表；funcIndex 仍用原下标（P6：不静默排序掩盖读数）。
            val sorted = slots.sortedBy { it.entryPc }
            val spans = ArrayList<Span>(sorted.size)
            val spanOfIndex = arrayOfNulls<Span>(slots.size)
            for (k in sorted.indices) {
                val s = sorted[k]
                val start = s.entryPc
                val rawEnd = if (k + 1 < sorted.size) sorted[k + 1].entryPc else instructionCount
                val end = if (rawEnd < start) start else rawEnd
                val span = Span(start, end, s)
                spans += span
                spanOfIndex[s.funcIndex] = span
            }
            return SourceMap(instructionCount, slots, spans, spanOfIndex, lineOf)
        }

        /**
         * `position` 拆分（P1/P2/P3）。
         *
         * - null / 空串 ⇒ [PositionKind.NONE]
         * - 以 `·` 开头 ⇒ [PositionKind.SYNTHETIC]（`file` = 原文，不许当真实文件）
         * - 取**最后一个**冒号作分隔；冒号前非空、冒号后是十进制数字 ⇒ [PositionKind.FILE_LINE]
         *   （判据 = 容器里的 `FuncMeta.splitPosition` 同源思路：最后一个冒号，故路径里即便出现
         *   冒号也不会把文件列切错；Windows 盘符本不该出现在 meta 的相对路径里，真出现时由
         *   [isAbsolutePath] 在输出面掩成 `<absolute>`，见 CLI）
         * - 其余（无冒号、冒号在 0 位、冒号后非数字或溢出）⇒ [PositionKind.UNSPLITTABLE]
         */
        fun parsePosition(raw: String?): ParsedPosition {
            if (raw == null || raw.isEmpty()) return ParsedPosition(null, -1, PositionKind.NONE)
            if (raw.startsWith(SYNTHETIC_PREFIX)) {
                return ParsedPosition(raw, -1, PositionKind.SYNTHETIC)
            }
            val cut = raw.lastIndexOf(':')
            if (cut <= 0 || cut == raw.length - 1) {
                return ParsedPosition(null, -1, PositionKind.UNSPLITTABLE)
            }
            val digits = raw.substring(cut + 1)
            for (ch in digits) {
                if (ch < '0' || ch > '9') return ParsedPosition(null, -1, PositionKind.UNSPLITTABLE)
            }
            val line = digits.toIntOrNull() ?: return ParsedPosition(null, -1, PositionKind.UNSPLITTABLE)
            return ParsedPosition(raw.substring(0, cut), line, PositionKind.FILE_LINE)
        }

        /**
         * 绝对路径判据（只用于输出面掩码；meta 的 position 理应是相对路径）。
         *
         * 四条：`/` 开头、`\` 开头、`X:` 盘符开头（X 为字母）、`file:` / `http:` 这类带协议的
         * 形式（第二字符为冒号即按绝对处理，与盘符同判据）。
         */
        fun isAbsolutePath(p: String): Boolean {
            if (p.startsWith("/") || p.startsWith("\\")) return true
            return p.length >= 2 && p[1] == ':' && p[0].isLetter()
        }
    }
}

/** `position` 的可拆三态 + 缺失（P1/P2/P3 的机器可断言形态）。 */
enum class PositionKind {
    /** 侧车没有该函数的 position（或空串）。 */
    NONE,

    /** 有 position，但不是 `文件:行号` 形（无冒号 / 冒号位置非法 / 行号非十进制）。 */
    UNSPLITTABLE,

    /** 合成标记（`·` 开头，如 `·event-entry·`），`file` 是标记原文而非真实文件。 */
    SYNTHETIC,

    /** 正常拆分：`file` = 冒号前全部，[ParsedPosition.startLine] = 声明行（1 基）。 */
    FILE_LINE,
}

/** 一条 position 的拆分结果。 */
class ParsedPosition(
    /** P1 的文件列 / P2 的标记原文；P3 与缺失为 null。 */
    val file: String?,
    /** 声明行（1 基；P2/P3/缺失为 -1）。 */
    val startLine: Int,
    val kind: PositionKind,
)

/**
 * 单 pc 的归属结果。
 *
 * 无归属（P4）时 [funcName] / [file] 为 null、[funcIndex] 为 -1、[line0] / [line1] 为 -1，
 * [owned] 为 false —— 不猜、不回落成「第一个函数」。
 */
class SourceLocation(
    val pc: Int,
    val funcName: String?,
    val funcIndex: Int,
    val file: String?,
    /** `Program.lineOf(pc)` 原值（0 基；-1 = 未知）。 */
    val line0: Int,
    /** `line0 + 1`；`line0 < 0` 时原样。 */
    val line1: Int,
    val owned: Boolean,
    val positionKind: PositionKind,
) {
    /** P2：该 pc 归属的函数 position 是合成标记（`file` 不是真实文件）。 */
    val synthetic: Boolean get() = positionKind == PositionKind.SYNTHETIC

    /** P3：归属到了函数，但它的 position 不可拆（`file` 为 null）。 */
    val positionUnsplittable: Boolean get() = positionKind == PositionKind.UNSPLITTABLE ||
        positionKind == PositionKind.NONE

    companion object {
        /** 无归属形态（P4）。 */
        fun unowned(pc: Int): SourceLocation = SourceLocation(
            pc = pc,
            funcName = null,
            funcIndex = -1,
            file = null,
            line0 = -1,
            line1 = -1,
            owned = false,
            positionKind = PositionKind.NONE,
        )
    }
}

/** 函数区间摘要（`srcmap --func` 的数据面）。 */
class FuncSummary(
    val funcIndex: Int,
    val name: String,
    /** 容器里的区间起点（原值，未规整）。 */
    val entryPc: Int,
    /** 实际区间 `[start, end)`（P6 乱序后按 `entryPc` 排序得到；`start` 可能与 [entryPc] 不同）。 */
    val start: Int,
    val end: Int,
    val file: String?,
    /** position 的声明行（1 基；合成/缺失为 -1）。 */
    val positionLine: Int,
    val positionKind: PositionKind,
    /** 区间内 `lineOf` 最小/最大值（全未知时为 -1）。 */
    val line0Min: Int,
    val line0Max: Int,
    /** 区间内 `lineOf(pc) == -1` 的条数。 */
    val linesUnknown: Int,
) {
    /** 区间指令数（= `end - start`；空区间为 0）。 */
    val instrCount: Int get() = end - start

    /** `line0Max + 1`（全未知时 -1），与 [SourceLocation.line1] 同口径。 */
    val line1Max: Int get() = if (line0Max < 0) line0Max else line0Max + 1

    /** `line0Min + 1`（全未知时 -1）。 */
    val line1Min: Int get() = if (line0Min < 0) line0Min else line0Min + 1

    val synthetic: Boolean get() = positionKind == PositionKind.SYNTHETIC
}

/** `srcmap --audit` 的七计数（+ 三个诊断计数）。 */
class AuditReport(
    val pcTotal: Int,
    val pcUnowned: Int,
    val funcNoPosition: Int,
    val funcSyntheticPosition: Int,
    val funcNonIncreasingEntry: Int,
    val distinctFiles: Int,
    val linesUnknown: Int,
    /** 归属到合成标记函数的 pc 数（P2 的规模读数）。 */
    val pcSynthetic: Int,
    val funcTotal: Int,
    /** position 的文件列是绝对路径的函数数（正常产物恒 0；非 0 说明侧车有脏数据）。 */
    val funcAbsoluteFile: Int,
)
