package com.eratw.port.vmdbg

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `srcmap`：pc → `文件:行:函数` 的**离线**单一查询面（MODERN-R4-C）。
 *
 * ```
 * srcmap <pc> [-n 条数]      从 pc 起连续 n 条（缺省 1）
 * srcmap --func <名字>       该函数区间摘要
 * srcmap --audit             全量完备性审计（遍历所有 pc）
 * ```
 *
 * ## 离线铁律
 *
 * **只读 `--erbc`（`program.erbc` + 同目录 `program.meta`），不装配会话。**
 * [Assembler.assemble] 是重路径：要游戏根、建存档沙箱、装配 `GameSession`，还有存档/输入副作用；
 * 本命令一个都不需要。装载照 [Assembler] 的写法抄两行（`ProgramLoader.loadMapped` +
 * `BootMetadata.read`，只调用不改），其余全在 [SourceMap] 纯函数层。
 * `--game` 给了也不使用（`desktop.py cmd` 会自动补，这里不读）。
 *
 * ## 输出契约（机器可断言，逐行 `key=value`）
 *
 * - 每条：`pc=<n> func=<NAME> funcIndex=<i> file=<相对路径|-> line0=<n> line1=<n>`
 *   - 无归属（P4）⇒ `func=- funcIndex=-1 file=- line0=-1 line1=-1`，**另加 `owned=false`**
 *   - 合成标记（P2）⇒ `file=` 是标记原文，另加 `synthetic=true`
 *   - position 不可拆（P3）⇒ `file=-`，另加 `posUnsplit=true`
 * - 文件列若是绝对路径（脏侧车）⇒ 掩成 `file=<absolute>` 并加 `absFile=true`：
 *   输出面**不出现绝对路径**（不暴露跑这台机器的人的目录结构）。
 * - 不出现墙钟、不出现随机序：同一产物两次跑，输出逐字节相同。
 *
 * ## 退出码
 *
 * | 码 | 含义 |
 * |---|---|
 * | 0 | 正常（含 `--audit` 报了异常计数：审计的活是**报**，不是判红） |
 * | 1 | 用法错误 / 装载失败（[CliError] 由 [Cli.run] 折回） |
 * | 2 | pc 越界（`< 0` 或 `>= instructionCount`）或函数名不存在 |
 */
object CmdSrcMap {

    /** 本子命令的取值选项（`--audit` 是布尔旗标，不进这里）。装配进 [Args.VALUE_OPTS]。 */
    val VALUE_OPTS_NEEDED: Set<String> = setOf("--func")

    const val EXIT_OK = 0
    const val EXIT_TARGET_MISSING = 2

    /** 用法一行（[Cli.USAGE] 与本对象共用，避免两处文案漂移）。 */
    const val USAGE_LINE = "srcmap <pc> [-n 条数] | srcmap --func <名字> | srcmap --audit"

    /**
     * CLI 入口。
     *
     * 参数**先解析后装载**：用法错误（缺 pc、`-n` 非法、`--audit` 与 `--func` 同给）不必先付
     * 一次几百 MB 的产物装载；退出码 1 与 2 的分界也因此不依赖装载成败。
     */
    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val auditMode = a.flag("--audit")
        val funcName = a.value("--func")
        val pcRaw = a.positional.getOrNull(1)
        if (auditMode && funcName != null) throw CliError("--audit 与 --func 不能同时给（用法: $USAGE_LINE）")
        if (auditMode && pcRaw != null) throw CliError("--audit 不带 pc（用法: $USAGE_LINE）")
        if (funcName != null && pcRaw != null) throw CliError("pc 与 --func 不能同时给（用法: $USAGE_LINE）")
        if (!auditMode && funcName == null && pcRaw == null) throw CliError("用法: $USAGE_LINE")

        var pc = 0
        var count = 1
        if (!auditMode && funcName == null) {
            pc = pcRaw!!.toIntOrNull() ?: throw CliError("pc 不是整数: $pcRaw")
            val n = a.longOr("-n", 1L)
            if (n <= 0) throw CliError("-n 必须为正整数: $n")
            count = if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
        }

        val map = SourceMap.of(loadProgram(a), loadMetadata(a))

        if (auditMode) {
            printAudit(map.audit(), out)
            return EXIT_OK
        }
        if (funcName != null) return printFunc(map, funcName, out)
        return printSlice(map, pc, count, out)
    }

    // ------------------------------------------------------------------ 数据面
    private fun printSlice(map: SourceMap, pc: Int, count: Int, out: PrintStream): Int {
        if (pc < 0 || pc >= map.instructionCount) {
            out.println("status=OUT_OF_RANGE pc=$pc instructionCount=${map.instructionCount}")
            return EXIT_TARGET_MISSING
        }
        // 越尾截断（`-n` 大过剩余条数）：只打印产物里真实存在的 pc，不补假行。
        val last = minOf(map.instructionCount.toLong(), pc.toLong() + count.toLong()).toInt()
        for (p in pc until last) out.println(locationLine(map.at(p)))
        return EXIT_OK
    }

    private fun printFunc(map: SourceMap, name: String, out: PrintStream): Int {
        val idx = map.findFunction(name) ?: run {
            out.println("status=NOT_FOUND func=$name")
            return EXIT_TARGET_MISSING
        }
        val s = map.summarize(idx) ?: run {
            out.println("status=NOT_FOUND func=$name")
            return EXIT_TARGET_MISSING
        }
        val sb = StringBuilder()
        sb.append("func=").append(s.name)
        sb.append(" funcIndex=").append(s.funcIndex)
        sb.append(" entryPc=").append(s.entryPc)
        sb.append(" instrCount=").append(s.instrCount)
        sb.append(" file=").append(displayFile(s.file, sb))
        sb.append(" posLine=").append(s.positionLine)
        sb.append(" line0Min=").append(s.line0Min)
        sb.append(" line0Max=").append(s.line0Max)
        sb.append(" line1Min=").append(s.line1Min)
        sb.append(" line1Max=").append(s.line1Max)
        sb.append(" linesUnknown=").append(s.linesUnknown)
        if (s.synthetic) sb.append(" synthetic=true")
        out.println(sb)
        return EXIT_OK
    }

    private fun printAudit(r: AuditReport, out: PrintStream) {
        // 契约要求的前七项按契约顺序在前；后三项是本波的诊断面。
        out.println("pcTotal=${r.pcTotal}")
        out.println("pcUnowned=${r.pcUnowned}")
        out.println("funcNoPosition=${r.funcNoPosition}")
        out.println("funcSyntheticPosition=${r.funcSyntheticPosition}")
        out.println("funcNonIncreasingEntry=${r.funcNonIncreasingEntry}")
        out.println("distinctFiles=${r.distinctFiles}")
        out.println("linesUnknown=${r.linesUnknown}")
        out.println("pcSynthetic=${r.pcSynthetic}")
        out.println("funcTotal=${r.funcTotal}")
        out.println("funcAbsoluteFile=${r.funcAbsoluteFile}")
    }

    /**
     * 单条归属 → 一行 `key=value`。
     *
     * 绝对路径掩码走 [displayFile]：它把结果直接追加进 [sb] 并返回空串 —— 调用点写成
     * `file=` + 返回值即可（掩码时返回 `<absolute>`，正常时返回原文件名）。
     */
    private fun locationLine(loc: SourceLocation): String {
        val sb = StringBuilder()
        sb.append("pc=").append(loc.pc)
        if (!loc.owned) {
            sb.append(" func=- funcIndex=-1 file=- line0=-1 line1=-1 owned=false")
            return sb.toString()
        }
        sb.append(" func=").append(loc.funcName)
        sb.append(" funcIndex=").append(loc.funcIndex)
        sb.append(" file=").append(displayFile(loc.file, sb))
        sb.append(" line0=").append(loc.line0)
        sb.append(" line1=").append(loc.line1)
        if (loc.synthetic) sb.append(" synthetic=true")
        if (loc.positionUnsplittable) sb.append(" posUnsplit=true")
        return sb.toString()
    }

    /**
     * 文件列的输出形态：null ⇒ `-`；绝对路径 ⇒ `<absolute>` 且加 `absFile=true`；
     * 其余原样（meta 里的相对路径）。
     */
    private fun displayFile(file: String?, sb: StringBuilder): String {
        if (file == null || file.isEmpty()) return "-"
        if (SourceMap.isAbsolutePath(file)) {
            sb.append(" absFile=true")
            return "<absolute>"
        }
        // 相对路径原样（含空格也不改写：改写会让「输出里的文件」与侧车里的文件对不上）。
        return file
    }

    // ------------------------------------------------------------------ 装载（离线，只读 --erbc）
    private fun erbcDir(a: Args): Path {
        val raw = a.value("--erbc")
            ?: System.getenv("ERATW_ERBC_DIR")
            ?: throw CliError("缺 --erbc <erbc.v1 目录>，且环境变量 ERATW_ERBC_DIR 未设置")
        val dir = Path.of(raw).toAbsolutePath().normalize()
        if (!Files.isDirectory(dir)) throw CliError("--erbc 不是目录: $dir")
        return dir
    }

    private fun loadProgram(a: Args): Program {
        val path = erbcDir(a).resolve("program.erbc")
        if (!Files.isRegularFile(path)) throw CliError("产物缺失: $path")
        return try {
            // 单参形态 = 产品缺省档（不起兼容 mark、无额外 GC），与 Assembler 的缺省路径同式。
            ProgramLoader.loadMapped(path)
        } catch (e: Exception) {
            throw CliError("产物装载失败: $path（${e.message}）", e)
        }
    }

    private fun loadMetadata(a: Args): BootMetadata {
        val path = erbcDir(a).resolve("program.meta")
        if (!Files.isRegularFile(path)) throw CliError("侧车缺失: $path（正式产物必须带 program.meta）")
        return try {
            BootMetadata.read(path)
        } catch (e: Exception) {
            throw CliError("侧车读取失败: $path（${e.message}）", e)
        }
    }
}
