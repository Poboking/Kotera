package com.eratw.port.vmdbg

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.proc.FuncMeta
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 单函数子命令：call / where / var / vars / disasm（CONTRACT-DBG1 路2 W1 集合）。
 * trace 依赖路1 探针，单独成文件（Trace.kt）。
 */
object CmdCall {

    /**
     * `call <函数名> [--args 0,1,...] [--steps N]`。
     *
     * `--args` 的每个逗号分段：能解析成 Long 进 intArgs（桶内保序），否则进 strArgs ——
     * 两桶独立保序，交叉顺序不保留（callFunction 的形参按 `(intArgs, strArgs)` 两桶绑定，
     * 混合顺序本就无从表达；KDoc 明示给 agent）。
     *
     * NOT_FOUND 预检：vmdbg 自持的 [Assembled.metadata] 里没有该 key（大写归一）时，
     * 不进运行期直接报 NOT_FOUND（callFunction 对未知名同样抛 CodeEE，预检只是把状态
     * 归类得更准、退出码区分开）。事件名/方法名照常进运行期，由 callFunction 的
     * 原版口径报错（ProcessRuntime.kt:1415-1424：事件名/方法名 CodeEE）。
     */
    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val name = a.positional.getOrNull(1) ?: throw CliError("用法: call <函数名> [--args 0,1,...] [--steps N]")
        val assembled = Assembler.assemble(a, sinkOverride = sinkOverride(a), out = out)
        applyPreState(assembled, a, out)?.let { return it }
        val (intArgs, strArgs) = parseArgs(a.value("--args"))
        return invokeAndReport(assembled, name, intArgs, strArgs, a, out = out)
    }

    /**
     * `--pre` / `--addchara` / `--sets` 的统一注入点（call / trace 共用）：
     * 先跑前置真实函数链、再加角色、最后写变量。
     *
     * `--pre "QOL_DB_INIT;GRAPH_DB_INIT"` 是「同一 GameSession 里先走启动链」的入口 ——
     * SQL_CONNECT 的连接存活于进程内会话，跨 `call` 进程不保留，所以真链必须在同一个
     * 装配里按游戏自己的顺序跑（本命令不伪造连接状态）。
     * `--addchara` 逐号走 [com.eratw.port.core.vars.VariableStore.addCharacter]——与 ERB
     * `ADDCHARA` 语句（StatementExecutor.kt:853）同一条实现，会按模板拷 ABL/CFLAG 等；
     * 不做「只往角色表塞一行」的测试专用替身。成功时打印 `addChara` / `charaNum`，
     * 失败（模板不存在）折成 CODEEE + 退出码 2。
     */
    fun applyPreState(assembled: Assembler.Assembled, a: Args, out: PrintStream): Int? {
        var failure = runPreCalls(assembled, a, out)
        if (failure == null) failure = applyAddChara(assembled, a, out)
        if (failure == null) failure = applySets(assembled, a, out)
        return failure
    }

    /**
     * --inputs 脚本：按序投给取值面的脚本（见 [ScriptedInputSink]）。未给 = null ⇒
     * 装配期仍用缺省 [ImmediateInputSink]，既有调用面参数序列逐字不变。
     */
    fun sinkOverride(a: Args): InputSink? {
        val raw = a.value("--inputs") ?: return null
        val items = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) throw CliError("--inputs 不能为空")
        return ScriptedInputSink(items)
    }

    /** `--addchara`：逐号走真实 `ADDCHARA` 的同一实现；任一失败即停。 */
    private fun applyAddChara(assembled: Assembler.Assembled, a: Args, out: PrintStream): Int? {
        val raw = a.value("--addchara")
        var failure: Int? = null
        if (!raw.isNullOrBlank()) {
            val nos = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { seg ->
                seg.toLongOrNull() ?: throw CliError("--addchara 不是整数列表: $raw")
            }
            for (no in nos) {
                try {
                    assembled.session.store.addCharacter(no)
                } catch (e: CodeEE) {
                    out.println("status=CODEEE")
                    out.println("message=${e.message}")
                    failure = 2
                    break
                }
            }
            if (failure == null) {
                out.println("addChara=${nos.joinToString(",")}")
                out.println("charaNum=${assembled.session.store.characters.count}")
            }
        }
        return failure
    }

    /**
     * `--sets` 批量注入：分号分隔 `NAME[i,j]=V`，逐项走 [CmdSet.apply] 的公开写单点。
     * 分号分隔而不是逗号：下标语法本身用逗号（`ABL[1,9]`），逗号当分隔会切坏 LHS。
     */
    private fun applySets(assembled: Assembler.Assembled, a: Args, out: PrintStream): Int? {
        val raw = a.value("--sets")
        if (raw.isNullOrBlank()) return null
        var failure: Int? = null
        for (expr in raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val code = applyOneSet(assembled, a, expr, out)
            if (code != 0) {
                failure = code
                break
            }
        }
        return failure
    }

    /**
     * `--pre "FN1;FN2"`：在目标函数前按序跑真实函数（无实参）。
     *
     * 只经 [ProcessRuntime.callFunction]（与 ERB `CALL` 同一条入口），不伪造 SQL 连接状态；
     * 任一前置函数失败即停并返回其分类退出码。逐条打印 `preCall[i]=...` 便于把启动链
     * 与目标函数的步数/耗时分开记账（trace 在全部前置跑完后才装探针，轨迹只含目标链）。
     */
    private fun runPreCalls(assembled: Assembler.Assembled, a: Args, out: PrintStream): Int? {
        val raw = a.value("--pre")
        var failure: Int? = null
        if (!raw.isNullOrBlank()) {
            val names = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            for ((i, name) in names.withIndex()) {
                failure = runOnePreCall(assembled, i, name, out)
                if (failure != null) break
            }
        }
        return failure
    }

    /** 跑一条前置函数并记账；返回 null = PASS，2 = 未命中，3 = 运行期失败。 */
    private fun runOnePreCall(
        assembled: Assembler.Assembled,
        index: Int,
        name: String,
        out: PrintStream,
    ): Int? {
        val rt = assembled.session.runtime
        val meta = assembled.metadata.functions.firstOrNull { it.key == name.uppercase() }
        var failure: Int? = null
        if (meta == null) {
            out.println("preCall[$index]=$name status=NOT_FOUND")
            out.println("message=函数表中没有该前置函数（大写归一后未命中 metadata.functions）")
            failure = 2
        } else {
            val t0 = System.nanoTime()
            var error: Throwable? = null
            try {
                rt.callFunction(meta.key)
            } catch (t: Throwable) {
                error = t
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            val status = Status.classify(error)
            out.println(
                "preCall[$index]=${meta.name} status=$status steps=${StepReader.executedSteps(rt) ?: -1L} " +
                    "elapsedMs=$elapsedMs",
            )
            if (status != "PASS") {
                out.println("location=${Status.location(error)}")
                out.println("message=${error?.message ?: ""}")
                failure = 3
            }
        }
        return failure
    }

    /** `--args` 分桶：数字 → int 桶，其余 → str 桶。 */
    fun parseArgs(raw: String?): Pair<LongArray, Array<String>> {
        if (raw.isNullOrEmpty()) return LongArray(0) to emptyArray()
        val ints = ArrayList<Long>()
        val strs = ArrayList<String>()
        for (tok in raw.split(',')) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            t.toLongOrNull()?.let { ints.add(it) } ?: strs.add(t)
        }
        return ints.toLongArray() to strs.toTypedArray()
    }

    /**
     * call/trace 共用的「调用 + 报告」主体：执行、分类、输出 key=value 段、写 last-error.json、
     * 给出退出码。[extraOutput] 是 trace 的轨迹落点（Trace.kt 装好探针后传「输出环形缓冲」
     * 的闭包；call 恒 null —— 本文件不引用任何探针类型，保持无探针时全模块可编译）。
     */
    fun invokeAndReport(
        assembled: Assembler.Assembled,
        name: String,
        intArgs: LongArray,
        strArgs: Array<String>,
        a: Args,
        out: PrintStream,
        extraOutput: (() -> Unit)? = null,
    ): Int {
        val rt = assembled.session.runtime
        val keyU = name.uppercase()
        val meta: FuncMeta? = assembled.metadata.functions.firstOrNull { it.key == keyU }
        if (meta == null) {
            out.println("status=NOT_FOUND")
            out.println("target=$name")
            out.println("message=函数表中没有该函数（大写归一后未命中 metadata.functions）")
            return 2
        }
        val t0 = System.nanoTime()
        var error: Throwable? = null
        try {
            rt.callFunction(meta.key, intArgs, strArgs)
        } catch (t: Throwable) {
            error = t
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        val steps = StepReader.executedSteps(rt)
        val status = Status.classify(error)
        // 文件列与 location 同源（CodeEE 的 pc）：只有真落在指令上才查表，PASS / 无 pc ⇒ `-`。
        val site = (error as? CodeEE)?.takeIf { it.pc >= 0 }
            ?.let { SourceMap.of(assembled.program, assembled.metadata).at(it.pc) }
        val file = fileColumn(site?.file, site?.positionKind ?: PositionKind.NONE)

        out.println("status=$status")
        out.println("target=${meta.name}")
        out.println("location=${Status.location(error)}")
        out.println("file=$file")
        out.println("message=${error?.message ?: ""}")
        out.println("steps=${steps ?: -1L}")
        out.println("elapsedMs=$elapsedMs")
        if (error is StepLimitExceeded) out.println("callChain=${error.callChain ?: ""}")

        val tail = assembled.session.console.snapshot()
            .takeLast(Cli.CONSOLE_TAIL_LINES)
            .map { assembled.session.console.textOf(it) }
        out.println("--- console tail (${tail.size} lines) ---")
        for (line in tail) out.println(line)

        if (extraOutput != null) extraOutput()
        writeLastError(a, status, error, steps, elapsedMs, file)
        return if (status == "PASS") 0 else if (status == "NOT_FOUND") 2 else 3
    }

    /**
     * `where` 的数据源：把最近一次错误现场落 `<work>/last-error.json`（无错误也写，
     * status=PASS 覆盖旧现场）。写临时文件后原子改名，防 where 读到半行。
     */
    private fun writeLastError(
        a: Args,
        status: String,
        error: Throwable?,
        steps: Long?,
        elapsedMs: Long,
        file: String,
    ) {
        val work = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        val tmp = work.resolve("last-error.json.tmp")
        val target = work.resolve("last-error.json")
        val json = Jsonl.obj(
            listOf(
                "status" to status,
                "funcName" to (error as? CodeEE)?.funcName,
                "line" to ((error as? CodeEE)?.line ?: -1),
                "pc" to ((error as? CodeEE)?.pc ?: -1),
                "callChain" to (error as? StepLimitExceeded)?.callChain,
                "message" to (error?.message ?: ""),
                "steps" to (steps ?: -1L),
                "elapsedMs" to elapsedMs,
                "file" to file,
            ),
        )
        Files.write(tmp, (json + "\n").toByteArray(Charsets.UTF_8))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/** 单条 `--sets` 项：`NAME[i,j]=V`（LHS/RHS 用第一个 `=` 切分）。 */
private fun applyOneSet(
    assembled: Assembler.Assembled,
    a: Args,
    expr: String,
    out: PrintStream,
): Int {
    val eq = expr.indexOf('=')
    if (eq <= 0) throw CliError("--sets 项缺 '=': $expr")
    val (name, indices) = CmdSet.parseLhs(expr.substring(0, eq).trim())
    return CmdSet.apply(assembled, a, "set", name, indices, expr.substring(eq + 1).trim(), out)
}

/** 运行结果的状态分类与位置提取（call / trace / sweep 共用）。 */
object Status {

    /**
     * Throwable → 状态串。StepLimitExceeded / CallStackOverflow 都是 CodeEE 子类
     * （Errors.kt:25/:44），必须**先**于 CodeEE 判断；WallLimitExceeded 是普通
     * RuntimeException（故意不继承 CodeEE，防 TRYC 吞掉），最先判。
     */
    fun classify(t: Throwable?): String = when (t) {
        null -> "PASS"
        is WallLimitExceeded -> "WALL_LIMIT"
        is StepLimitExceeded -> "STEP_LIMIT"
        is CallStackOverflow -> "STACK_OVERFLOW"
        is CodeEE -> "CODEEE"
        else -> "ERROR"
    }

    /** `函数名:行号:pc`（CodeEE 结构化字段，err/CodeEE.kt:20-25）；无现场 = `-`。 */
    fun location(t: Throwable?): String {
        val e = t as? CodeEE ?: return "-"
        return "${e.funcName ?: "-"}:${e.line}:${e.pc}"
    }
}

/**
 * 文件列的上屏口径（disasm / call / where / sweep 共用，与 `CmdSrcMap` 的 displayFile 同源）。
 *
 * 只有 [PositionKind.FILE_LINE] 算真实文件：无归属、合成标记（`·event-entry·`）、position
 * 不可拆、空串一律 `-`；绝对路径掩成 `<absolute>` —— 输出面不出现跑这台机的目录结构。
 */
internal fun fileColumn(file: String?, kind: PositionKind): String =
    if (kind == PositionKind.FILE_LINE) maskFileColumn(file) else "-"

/** 已是真实文件名的一列（`where` 回读落盘值用）：null/空 ⇒ `-`；绝对路径 ⇒ `<absolute>`。 */
internal fun maskFileColumn(file: String?): String {
    if (file.isNullOrEmpty()) return "-"
    return if (SourceMap.isAbsolutePath(file)) "<absolute>" else file
}

/** `where`：读 call/trace 落下的 last-error.json，输出最近一次错误的现场。 */
object CmdWhere {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val work = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        val target = work.resolve("last-error.json")
        if (!Files.isRegularFile(target)) {
            out.println("status=NO_ERROR")
            out.println("message=尚无 call/trace 写下的错误现场（$target 不存在）")
            return 0
        }
        val rec = Jsonl.parseLine(Files.readString(target, Charsets.UTF_8).lineSequence().firstOrNull() ?: "")
            ?: throw CliError("last-error.json 不是可解析的平面对象: $target")
        for (k in listOf("status", "funcName", "line", "pc", "callChain", "message", "steps", "elapsedMs")) {
            out.println("$k=${rec[k] ?: ""}")
        }
        // 旧档（R7 之前）没有该键 ⇒ 空值，不炸也不编造；有值时按上屏口径掩绝对路径。
        val file = rec["file"]
        out.println("file=" + if (file == null) "" else maskFileColumn(file))
        return 0
    }

    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/** `var <名字>[i,j]`：读单变量（值取指定下标，缺省 [0]）。 */
object CmdVar {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val spec = a.positional.getOrNull(1) ?: throw CliError("用法: var <名字>[i,j]")
        val assembled = Assembler.assemble(a, out = out)
        val store = assembled.session.store
        // 名字[下标表]：下标为逗号分隔整数；缺省 [0]（CONTRACT-DBG1 路2：值取 [0] 或指定下标）
        val open = spec.indexOf('[')
        val name = if (open >= 0) spec.substring(0, open) else spec
        val indices: LongArray = if (open >= 0) {
            val close = spec.indexOf(']', open)
            if (close < 0) throw CliError("var 下标缺闭括号: $spec")
            spec.substring(open + 1, close).split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
        } else {
            longArrayOf(0L)
        }
        var value: String? = null
        var readError: Throwable? = null
        try {
            value = store.readInt(name, indices).toString()
        } catch (e: CodeEE) {
            readError = e
        }
        if (value == null) {
            try {
                value = store.readStr(name, indices)
            } catch (e: CodeEE) {
                readError = e
            }
        }
        if (value == null) {
            out.println("status=NOT_FOUND")
            out.println("message=${readError?.message ?: "读不到 $spec"}")
            return 2
        }
        out.println("status=PASS")
        val idx = indices.joinToString(",", prefix = "[", postfix = "]")
        out.println("${name.uppercase()}$idx=$value")
        return 0
    }
}

/** `vars <glob>`：枚举变量名（registry.all + allUserVars，glob 过滤）。 */
object CmdVars {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val glob = a.positional.getOrNull(1) ?: "*"
        val assembled = Assembler.assemble(a, out = out)
        val store = assembled.session.store
        val rx = globToRegex(glob)
        val names = LinkedHashSet<String>()
        for (d in store.registry.all()) if (rx.matches(d.name.uppercase())) names.add(d.name.uppercase())
        for (u in store.allUserVars()) if (rx.matches(u.spec.name.uppercase())) names.add(u.spec.name.uppercase())
        for (n in names.sorted()) out.println(n)
        return 0
    }

    /** glob → regex：星号 = 任意串、问号 = 单字符，其余字符字面匹配（对大写归一名）。 */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob.uppercase()) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString())
    }
}

/**
 * `disasm <pc> [-n 条数]`：反汇编指令区间（op/a/b/c/lineOf + Disassembler 文本）。
 *
 * 每条行格式 `pc=.. line=.. file=.. words=[..] <text>`；`file` = 该 pc 所属函数的源文件
 * （[SourceMap.at]），无归属 / 合成标记 / position 不可拆 ⇒ `-`。header 不带 `file=`。
 */
object CmdDisasm {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val pcRaw = a.positional.getOrNull(1) ?: throw CliError("用法: disasm <pc> [-n 条数]")
        val pc = pcRaw.toIntOrNull() ?: throw CliError("pc 不是整数: $pcRaw")
        val count = a.longOr("-n", 16L).toInt()
        if (count <= 0) throw CliError("-n 必须为正整数")
        val assembled = Assembler.assemble(a, out = out)
        val program = assembled.program
        if (pc < 0 || pc >= program.instructionCount) {
            throw CliError("pc 越界: $pc（指令总数 ${program.instructionCount}）")
        }
        val map = SourceMap.of(program, assembled.metadata)
        out.println("pc=$pc instructionCount=${program.instructionCount} count=$count")
        for (row in DisasmSlice.slice(program, pc, count)) {
            val loc = map.at(row.pc)
            out.println(
                "pc=${row.pc} line=${row.line} file=${fileColumn(loc.file, loc.positionKind)}" +
                    " words=[${row.words[0]},${row.words[1]},${row.words[2]},${row.words[3]}] ${row.text}",
            )
        }
        return 0
    }
}
