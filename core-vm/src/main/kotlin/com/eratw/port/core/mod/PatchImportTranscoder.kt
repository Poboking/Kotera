package com.eratw.port.core.mod

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CallArgSource
import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.OperandRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.checkPoolIndexBounds
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta

/**
 * legacy 窗口补丁 → 零基线 RT2 Module 转码器（M2-4，era-arch-v61u.md:75）。
 *
 * 蓝图终态：legacy `.patch`（v1/v2 窗口格式）**只在进口存在**——设备侧把补丁编译产物
 * 转码为 Module，此后与二进制分发 Module 走**完全相同的路**（`ModChainAssembler.assemble`
 * → `ModuleLinker.link` → `ModChainRuntimePlan` → `GameSession`，运行期单管线）；
 * `LinkableModule.kt:11` 明写「legacy .patch 转码器（M2-4）的产物同样落本接口」。
 * R7「并集重编」（kojo-imports.patch）随窗口模型退役——多口上包组合不再需要源码。
 *
 * ## 输入输出
 *
 * 输入 = [PatchCompiler.PatchResult]（窗口补丁编译产物：六基线 = base 计数，数据只持
 * 窗口条目，`_CALL.a` 绝对口径）+ base 函数下标→名字目录（base 侧车 functions 段）。
 * 输出 = [Transcoded]：零基线 [ErbModuleContainer.ErbModule]（可直接
 * `ErbModuleContainer.encode` 落 `.erbm`）+ [ModuleDescriptor]（可直接
 * `ManifestCodec.encode` 落 manifest；externalCalls = imports 表同序）。
 *
 * ## 零基化变换（与 `ModuleLinker.rewriteModuleInstructions` 互为逆操作）
 *
 * 1. **池引用机械下移**：分类只问 [OpcodeRelocation]（与链接器平移面/判界面同一张表，
 *    一条不多一条不少）；CONSTANT/STRING/EXPR/STATEMENT 各按窗口基线下移，
 *    EXPR_NULLABLE 的 [ErbcFormat.NONE_REF] 哨兵不平移（同链接器口径）。
 * 2. **`_CALL.a` 语义分野**（FUNCTION 槽不走机械平移，`CallRelocation.kt:8-17` 四支的
 *    编译期对应形）：`a >= functionBase` ⇒ 模块内下标（`a - functionBase`）；
 *    `a < functionBase` ⇒ 导入哨兵 `-(extIdx+1)`，imports 表按名内注（名字查
 *    base 目录，查不到 = fail-closed 拒绝转码）。补丁编译档不开 SUPER
 *    （R7 裁定），super 哨兵不可能出现，出现即拒绝。
 * 3. **合成事件入口退役**：窗口模型的合成入口（`<事件名>`，body 调 base 入口 + 成员，
 *    `PatchCompiler` 步骤 11.5）在模块形态**整体截断**——模块成员经 LSC events 视图进
 *    四桶派发（M2-6 已接运行期），base 成员链经 base 入口原样执行。入口名对 base 同名
 *    非事件函数在 MODERN 下构成未声明覆盖（签名闸 fail-closed 拒），且入口体对 base
 *    入口的调用无法表达为导入（`ModuleLinker` 拒事件名导入）。合成入口恒在全部成员
 *    **之后**声明（`PatchCompiler` 步骤序）⇒ 截断 = 裁掉指令流尾部，成员 pc 不动；
 *    「入口之后还有成员」的形态 = 上游编译序变化，fail-closed 拒绝（不静默截活代码）。
 *    **fail-closed 负控**：保留代码若有 `_CALL` 指向被截断的入口下标（用户源直接
 *    CALL 事件名），转码拒绝并点名。
 * 4. **元数据 rebase**：FunctionInfo entryPc/labelPc、callArgs 键、FuncMeta index/defIndex
 *    各按窗口基线下移；跳转是函数内相对偏移（`Opcode.kt` `_JUMP` 注），不动；
 *    表达式 FuncCall 走名字通道（`Expr.FuncCall.name` 运行期解析），天然 base 无关，不动。
 * 5. 出口自检与链接器模块级自检同款：[checkPoolIndexBounds]（允许哨兵）+
 *    [ErbcReader.requireJumpTargets]。
 *
 * ## 明确不做（登记）
 *
 * - 事件桶位（#ONLY/#PRI/#LATER）补丁面本就不采集（PatchCompiler 类尾 TODO(verify) 4），
 *   转码照实携带 NORMAL 桶，不新增语义。
 * - 签名导出恒空（补丁函数无签名规范串）⇒ MODERN 下补丁函数覆盖 base 被签名闸拒——
 *   与 R7 I6-② 同向（补丁本就不许覆盖非事件函数），非回归。
 * - `_CALL_BY_NAME` / `_JUMP_BY_NAME`（CALLFORM/JUMPFORM 动态名通道；kojo-community 三包
 *   共 13 处真源形态）在重定位分类表里全槽 NONE（`a` = 帧内名字槽地址，非池下标、
 *   非静态 pc）⇒ 零基化 = 逐字保留，按名决议在运行期对链接产物成立；转码面不外加
 *   分类表之外的拒绝（2026-11-14 真源探针实测：原「补丁编译器不发射」前提与
 *   ErbProgramCompiler 的 CALLFORM 发射形态不符，纯文本非 TRY 也走动态通道）。
 *
 * TODO(verify)：`.erbm` 与转码语义是 RT2 自研（原版 Emuera 无 Mod 代码容器可比），
 * 语义权威 = era-arch-v61u.md §1/§2/§12 与 `LinkableModule` 契约；无 C# 锚。
 */
object PatchImportTranscoder {

    /** 合成事件入口的 meta 位置标记（`PatchCompiler` 步骤 11.5 的登记值，只读判据）。 */
    private const val EVENT_ENTRY_POSITION = "·event-entry·"

    /** 转码结果：容器模型 + manifest descriptor + 诊断。 */
    class Transcoded(
        /** 可直接 `ErbModuleContainer.encode` 的容器模型（零基线）。 */
        val module: ErbModuleContainer.ErbModule,
        /** 可直接 `ManifestCodec.encode` 的 descriptor（externalCalls = imports 同序）。 */
        val descriptor: ModuleDescriptor,
        /** 导入名表（= descriptor.externalCalls，哨兵下标的语义来源）。 */
        val imports: List<String>,
        /** 被截断的合成事件入口事件名（诊断，装载序）。 */
        val droppedEventEntries: List<String>,
    )

    /**
     * 转码一个补丁编译产物。
     *
     * @param result 补丁编译产物（窗口形态；六基线必须与 [baseCounts] 一致，脱节即拒）。
     * @param baseFunctionNameByIndex base 函数物理下标 → 大写归一名（base 侧车 functions 段；
     *   `_CALL` 跨 base 目标的导入名来源，查不到 = fail-closed）。
     * @param packageId 转码产物的包身份（调用方派生；链接序与 manifest 配对用）。
     * @throws IllegalArgumentException 窗口基线脱节 / 补丁调用了 base 不存在的函数 /
     *   保留代码引用合成入口 / super 哨兵出现 / 出口自检失败。
     */
    fun transcode(
        result: PatchCompiler.PatchResult,
        baseCounts: PatchCompiler.BaseCounts,
        baseFunctionNameByIndex: Map<Int, String>,
        packageId: String,
    ): Transcoded {
        require(packageId.isNotBlank()) { "转码产物 packageId 不得为空" }
        require(result.funcMetas.isNotEmpty()) {
            "补丁函数集为空——没有可消费的模块内容，拒绝转码（空函数集负控）"
        }
        val program = result.program
        checkWindowBases(program, result.funcMetas.size, baseCounts)

        val funcBase = baseCounts.functionCount
        val instrBase = baseCounts.instructionCount
        val entry = findEventEntries(result.funcMetas, program, funcBase)
        // firstEntryPc 是绝对 pc；窗口保留条数 = 绝对 pc - 指令基线。
        val instrCount = program.codeSource.wordCount / Instruction.WORDS
        val keptInstrCount = (entry.firstEntryPc ?: (instrBase + instrCount)) - instrBase
        val acc = rewriteKeptInstructions(
            RewriteContext(
                program = program,
                metas = result.funcMetas,
                entryLocals = entry.locals,
                funcBase = funcBase,
                baseFunctionNameByIndex = baseFunctionNameByIndex,
                bases = PoolBases(
                    baseCounts.longCount, baseCounts.stringCount, baseCounts.exprCount, baseCounts.statementCount,
                ),
            ),
            keptInstrCount,
        )
        acc.callToDropped?.let { (local, ev) ->
            throw IllegalArgumentException(
                "补丁函数直接 CALL 事件名 $ev（目标 pc=$local 的合成入口）——模块链不支持该写法" +
                    "（合成事件入口随窗口模型退役，事件成员经四桶派发）；请改用 CALLEVENT 或等价写法",
            )
        }
        val rebased = rebaseFunctions(program, result.funcMetas, funcBase, instrBase, entry.locals)
        val zeroProgram = buildZeroProgram(acc.keptWords, program, instrBase, keptInstrCount, rebased)

        // ---- 出口自检（与链接器模块级自检同款）----
        checkPoolIndexBounds(zeroProgram, allowCallSentinels = true)
        ErbcReader.requireJumpTargets(zeroProgram)

        val importsList = acc.imports.keys.toList()
        return Transcoded(
            module = ErbModuleContainer.ErbModule(
                packageId = packageId,
                // 容器「编译档位」位 = Mod 包编译档（ErbModuleContainer.kt:51 的位语义），
                // 生产装配闸（ModChainAssembly）只收 Mod 包形状。转码产物不含 super 哨兵
                // （上面 rewriteCall 对 super 哨兵 fail-closed 拒绝），档位置位不构成任何
                // 运行期能力授予；R7「补丁档不开 SUPER」的落点是哨兵不出现，不是档位
                // 清零——置 false 会让生产 import 的产物被装配闸整包拒（RT2-KOJOTRANS
                // 真源探针 2026-11-14 实测「不是 Mod 包编译档」）。
                allowSuperCalls = true,
                program = zeroProgram,
                functions = rebased.metas,
                signatures = emptyMap(),
            ),
            descriptor = ModuleDescriptor(
                packageId = packageId,
                version = "1",
                externalCalls = importsList,
                privateDims = result.privateDims.groupBy({ it.functionKey }, { it.spec }),
            ),
            imports = importsList,
            droppedEventEntries = entry.dropped,
        )
    }

    /** 窗口基线核对（脱节 = 编译上下文错位，fail-closed）。 */
    private fun checkWindowBases(
        program: Program,
        metaCount: Int,
        baseCounts: PatchCompiler.BaseCounts,
    ) {
        val instrBase = baseCounts.instructionCount
        val funcBase = baseCounts.functionCount
        check(program.codeBase == instrBase * Instruction.WORDS) {
            "补丁 codeBase=${program.codeBase} 与 base 指令计数 $instrBase 脱节——编译上下文错位，拒绝转码"
        }
        check(program.functionBase == funcBase) {
            "补丁 functionBase=${program.functionBase} 与 base 函数计数 $funcBase 脱节"
        }
        check(
            program.longBase == baseCounts.longCount && program.stringBase == baseCounts.stringCount &&
                program.exprBase == baseCounts.exprCount && program.statementBase == baseCounts.statementCount,
        ) {
            "补丁池基线与 base 计数脱节（long=${program.longBase}/${baseCounts.longCount} " +
                "string=${program.stringBase}/${baseCounts.stringCount} " +
                "expr=${program.exprBase}/${baseCounts.exprCount} " +
                "stmt=${program.statementBase}/${baseCounts.statementCount}）"
        }
        check(program.functions.size == metaCount) {
            "补丁函数表 ${program.functions.size} 条与 FuncMeta $metaCount 条不同长"
        }
    }

    /** 合成事件入口识别结果。 */
    private class EventEntries(
        val locals: Set<Int>,
        val dropped: List<String>,
        val firstEntryPc: Int?,
    )

    /**
     * 合成事件入口识别（meta 判据：非事件位 + 事件名键 + 入口 position）。
     * 成员 meta isEvent=true；普通函数不可能用事件名（I6 闸）；入口恒 isEvent=false。
     */
    private fun findEventEntries(
        metas: List<FuncMeta>,
        program: Program,
        funcBase: Int,
    ): EventEntries {
        val entryLocals = HashSet<Int>()
        val dropped = ArrayList<String>()
        for (meta in metas) {
            if (!meta.isEvent && Symbols.isEventLabelName(meta.key) &&
                meta.position == EVENT_ENTRY_POSITION
            ) {
                val local = meta.index - funcBase
                check(local in program.functions.indices) { "合成入口下标越函数表：${meta.index}" }
                entryLocals += local
                dropped += meta.key
            }
        }
        // 入口恒在成员之后声明（PatchCompiler 步骤序）⇒ 截断点 = 最靠前的入口 entryPc，
        // 其后不允许再出现任何成员函数体（否则上游编译序变化，不静默截活代码）。
        val firstEntryPc = entryLocals.minOrNull()?.let { program.functions[it].entryPc }
        if (firstEntryPc != null) checkNoMemberAfterEntry(metas, program, funcBase, entryLocals, firstEntryPc)
        return EventEntries(entryLocals, dropped, firstEntryPc)
    }

    private fun checkNoMemberAfterEntry(
        metas: List<FuncMeta>,
        program: Program,
        funcBase: Int,
        entryLocals: Set<Int>,
        firstEntryPc: Int,
    ) {
        for ((i, fn) in program.functions.withIndex()) {
            val isEntry = (metas[i].index - funcBase) in entryLocals
            if (!isEntry && fn.entryPc >= firstEntryPc) {
                error(
                    "成员函数 ${fn.name} 的 entryPc=${fn.entryPc} 落在合成入口区 " +
                        "（≥$firstEntryPc）——上游编译序变化，拒绝转码（不静默截活代码）",
                )
            }
        }
    }

    /** 指令改写的累积结果。 */
    private class RewriteAcc(wordCount: Int) {
        val keptWords = IntArray(wordCount)
        val imports = LinkedHashMap<String, Int>() // 名字 → extIdx（内注序）
        var callToDropped: Pair<Int, String>? = null // (局部下标, 事件名) 首个命中
    }

    /** 指令改写的静态上下文（指令流 + 元数据 + 窗口基线）。 */
    private class RewriteContext(
        val program: Program,
        val metas: List<FuncMeta>,
        val entryLocals: Set<Int>,
        val funcBase: Int,
        val baseFunctionNameByIndex: Map<Int, String>,
        val bases: PoolBases,
    )

    /**
     * 指令改写：保留区 [0, firstEntryPc) 逐指令；入口区截断不读。
     * 池引用机械下移，`_CALL.a` 按 base/模块两个语义域分别决议。
     */
    private fun rewriteKeptInstructions(ctx: RewriteContext, keptInstrCount: Int): RewriteAcc {
        val acc = RewriteAcc(keptInstrCount * Instruction.WORDS)
        val words = ctx.program.codeSource.toIntArray()
        words.copyInto(acc.keptWords, 0, 0, keptInstrCount * Instruction.WORDS)
        for (pc in 0 until keptInstrCount) {
            val w = pc * Instruction.WORDS
            val op = acc.keptWords[w]
            if (op == Opcode._CALL) {
                rewriteCall(acc, w, pc, ctx)
            } else {
                // `_CALL_BY_NAME`/`_JUMP_BY_NAME`（CALLFORM/JUMPFORM 动态名通道）照常进
                // [rewritePools]：分类表全槽 NONE（FUNCTION 只出现在 `_CALL.a`，见
                // OpcodeRelocation 不变量），零基化 = 逐字保留——`a` 是帧内名字槽地址
                // （ErbProgramCompiler 的 CALLFORM 发射形态），非池下标、非静态 pc，
                // 名字在运行期对链接产物决议，无任何窗口基线牵涉。
                rewritePools(acc.keptWords, w, op, pc, ctx.bases)
            }
        }
        return acc
    }

    /** `_CALL.a` 的语义分野：`a >= functionBase` 是模块内下标，否则是 base 导入名。 */
    private fun rewriteCall(acc: RewriteAcc, w: Int, pc: Int, ctx: RewriteContext) {
        val a = acc.keptWords[w + 1]
        check(
            a != CallSentinels.CALL_SUPER_SENTINEL &&
                a != CallSentinels.TRY_CALL_SUPER_SENTINEL,
        ) {
            "补丁出现 super 哨兵（pc=$pc）——R7 补丁档不开 SUPER，拒绝转码"
        }
        if (a >= ctx.funcBase) {
            val local = a - ctx.funcBase
            check(local in ctx.program.functions.indices) { "补丁 _CALL 目标越函数表：a=$a（pc=$pc）" }
            if (local in ctx.entryLocals) {
                if (acc.callToDropped == null) acc.callToDropped = local to ctx.metas[local].key
            } else {
                acc.keptWords[w + 1] = local
            }
        } else {
            rewriteImportCall(acc, w, pc, a, ctx.baseFunctionNameByIndex)
        }
    }

    private fun rewriteImportCall(
        acc: RewriteAcc,
        w: Int,
        pc: Int,
        a: Int,
        baseFunctionNameByIndex: Map<Int, String>,
    ) {
        val name = baseFunctionNameByIndex[a]
            ?: throw IllegalArgumentException(
                "补丁 _CALL 目标 a=$a（pc=$pc）在 base 函数目录里没有名字——" +
                    "侧车与产物错位，拒绝转码",
            )
        require(!Symbols.isEventLabelName(name)) {
            "补丁 _CALL 目标 $name（base 下标 a=$a，pc=$pc）是事件名——" +
                "事件成员经 CALLEVENT 四桶派发；模块链拒绝按名导入事件入口"
        }
        val extIdx = acc.imports.getOrPut(name) { acc.imports.size }
        check(extIdx <= CallSentinels.IMPORT_INDEX_MAX) {
            "导入表越上界 ${CallSentinels.IMPORT_INDEX_MAX}（extIdx=$extIdx）"
        }
        acc.keptWords[w + 1] = -(extIdx + 1)
    }

    /** 池引用机械下移（分类只问 [OpcodeRelocation]，与链接器平移面同一张表）。 */
    private fun rewritePools(keptWords: IntArray, w: Int, op: Int, pc: Int, bases: PoolBases) {
        val rel = OpcodeRelocation.of(op)
        if (rel === OperandRelocation.EMPTY) return
        for (slot in 0..2) {
            val ref = rel.at(slot)
            if (ref == PoolRef.NONE) continue
            val raw = keptWords[w + 1 + slot]
            val shifted = shiftPoolRef(ref, raw, bases, "pc=$pc ${"abc"[slot]} op=${Opcode.nameOf(op)}")
            check(
                shifted >= 0 ||
                    (ref == PoolRef.EXPR_NULLABLE && shifted == ErbcFormat.NONE_REF),
            ) {
                "池引用下移后为负（$ref raw=$raw pc=$pc ${"abc"[slot]}）——窗口基线与产物脱节"
            }
            keptWords[w + 1 + slot] = shifted
        }
    }

    /** 函数表 rebase 结果（成对保持函数表与 FuncMeta 同序）。 */
    private class RebasedFunctions(val functions: List<FunctionInfo>, val metas: List<FuncMeta>)

    /** 函数表 rebase：截掉入口；成员 entryPc/labelPc 下移到窗口口径。 */
    private fun rebaseFunctions(
        program: Program,
        metas: List<FuncMeta>,
        funcBase: Int,
        instrBase: Int,
        entryLocals: Set<Int>,
    ): RebasedFunctions {
        val keptFns = ArrayList<FunctionInfo>(program.functions.size - entryLocals.size)
        val keptMetas = ArrayList<FuncMeta>(metas.size - entryLocals.size)
        var keptIdx = 0
        for ((i, fn) in program.functions.withIndex()) {
            val meta = metas[i]
            if (meta.index - funcBase in entryLocals) continue
            val nf = FunctionInfo(fn.name, fn.entryPc - instrBase, fn.arity, fn.slotCount, fn.paramNames)
            for (li in 0 until fn.labelCount) {
                nf.addLabel(fn.labelNames[li], fn.labelPcs[li] - instrBase)
            }
            keptFns += nf
            keptMetas += rebaseMeta(meta, keptIdx, meta.defIndex - funcBase)
            keptIdx++
        }
        return RebasedFunctions(keptFns, keptMetas)
    }

    /** 行号 / 池 / callArgs 按窗口口径复用，基线归零。 */
    private fun buildZeroProgram(
        keptWords: IntArray,
        program: Program,
        instrBase: Int,
        keptInstrCount: Int,
        rebased: RebasedFunctions,
    ): Program {
        val lines = IntArray(keptInstrCount) { i -> program.lineOf(instrBase + i) }
        val callArgs = HashMap<Int, List<CallArg>>()
        for ((pc, args) in program.callArgs) {
            val local = pc - instrBase
            if (local in 0 until keptInstrCount) callArgs[local] = args
        }
        val pool = program.overlayPoolRef
        val newPool = ConstantPool()
        for (v in pool.longArray) newPool.appendLongDirect(v)
        for (s in pool.stringArray) newPool.appendStringDirect(s)
        for (e in pool.exprList) newPool.appendExprDirect(e)
        for (st in pool.statementList) newPool.addStatement(st)
        return Program(
            keptWords,
            lines,
            newPool,
            rebased.functions,
            0, // 入口：模块无运行入口，链接产物入口恒为 base 的
            program.globalSlots,
            callArgs,
            CallArgSource.IN_MEMORY,
        )
    }
}

/** 四池窗口基线（下移量）。 */
private class PoolBases(val long: Int, val string: Int, val expr: Int, val statement: Int)

/** 池引用下移（EXPR_NULLABLE 的 NONE_REF 哨兵不平移，同链接器口径）。 */
private fun shiftPoolRef(ref: PoolRef, raw: Int, bases: PoolBases, where: String): Int = when (ref) {
    PoolRef.CONSTANT -> raw - bases.long
    PoolRef.STRING -> raw - bases.string
    PoolRef.EXPR -> raw - bases.expr
    PoolRef.EXPR_NULLABLE -> if (raw == ErbcFormat.NONE_REF) raw else raw - bases.expr
    PoolRef.STATEMENT -> raw - bases.statement
    // FUNCTION 槽不走机械平移（CallRelocation 语义决议）；编码期对应形在 _CALL
    // 分支单独处理，出现于此 = 分类表与实现脱节。
    PoolRef.NONE, PoolRef.FUNCTION -> error("槽（$where）不应进入平移面：$ref")
}

/** 按窗口口径重建 FuncMeta（位压缩类无 copy；逐字段保真，只改 index/defIndex）。 */
private fun rebaseMeta(meta: FuncMeta, newIndex: Int, newDefIndex: Int): FuncMeta = FuncMeta(
    index = newIndex,
    key = meta.key,
    name = meta.name,
    isEvent = meta.isEvent,
    isMethod = meta.isMethod,
    isOnly = meta.isOnly,
    isPri = meta.isPri,
    isLater = meta.isLater,
    isSingle = meta.isSingle,
    argIntCount = meta.argIntCount,
    argStrCount = meta.argStrCount,
    localSize = meta.localSize,
    localsSize = meta.localsSize,
    floatLocalSize = meta.floatLocalSize,
    returnsStr = meta.returnsStr,
    returnsFloat = meta.returnsFloat,
    defIndex = newDefIndex,
    position = meta.position,
)
