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
import java.util.Locale

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
 *    base 目录，查不到 = fail-closed 拒绝转码——编译器与转码器同源
 *    `base.metadata.functions`，该分支纯防御）。**事件名放行**（S9 改判 T1/T2，
 *    CONTRACT-S9-MISSING-CALL.md）：CALL 事件名（base 事件入口或本包被截断的合成
 *    入口）同样按名进 imports——链接期无非事件胜者 ⇒ 缺名残桩，运行期
 *    `resolveFunctionIndex` 按 `CompatiCallEvent` 配置解析（NO = `CallToEventFunc`
 *    原文报错；YES = 真调首成员，spec: CalledFunction.cs:100-103、
 *    LabelDictionary.cs:181-184）。补丁编译档不开 SUPER
 *    （R7 裁定），super 哨兵不可能出现，出现即拒绝。
 * 3. **合成事件入口退役**：窗口模型的合成入口（`<事件名>`，body 调 base 入口 + 成员，
 *    `PatchCompiler` 步骤 11.5）在模块形态**整体截断**——模块成员经 LSC events 视图进
 *    四桶派发（M2-6 已接运行期），base 成员链经 base 入口原样执行。入口名对 base 同名
 *    非事件函数在 MODERN 下构成未声明覆盖（签名闸 fail-closed 拒），且入口体对 base
 *    入口的调用无法表达为导入（`ModuleLinker` 拒事件名导入）。合成入口恒在全部成员
 *    **之后**声明（`PatchCompiler` 步骤序）⇒ 截断 = 裁掉指令流尾部，成员 pc 不动；
 *    「入口之后还有成员」的形态 = 上游编译序变化，fail-closed 拒绝（不静默截活代码）。
 *    **保留代码指向被截断入口的 `_CALL`**（用户源直接 CALL 事件名，S9 T1 改判）⇒
 *    按事件名进 imports（同上，运行期按配置解析），不再拒绝转码。
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
 * - 签名导出**只覆盖声明面**：覆盖集（模块名 ∩ base 名）里的键逐个导出 base 指纹（R7-①），
 *   其余补丁函数指纹仍为空串——非覆盖键不参与签名闸比较，导出全套指纹属需组长接线。
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
        /**
         * 降级覆盖键（2026-11-23 KI 改判，BOARD KI-2）：base 侧车给不出签名指纹的覆盖键。
         * 包照常落盘（链接期 last-wins 放行），清单供调用方汇进导入告警面
         * （玩家可见「ABI 未校验，请向口上作者反馈」）。空 = 常态。
         */
        val degradedOverrideKeys: List<String> = emptyList(),
    )

    /**
     * 覆盖装载的输入（[transcode] 的末位参数，把「查 base 指纹」与「包声明收窄」收成一个概念，
     * 免得参数表继续长）。
     *
     * @param baseSignatureOf base 签名指纹查询口：大写归一函数键 → 规范指纹串，无该键或无签名段
     *   ⇒ 空串。生产真源 = [com.eratw.port.core.patch.BaseSignatureCatalog] 走 `program.meta`
     *   尾段，与装配期链接器读的是同一份。**2026-11-23 KI 改判后不再作前置闸**：缺指纹的
     *   覆盖键照常声明（链接期 last-wins 放行 + ABI 未校验告警），查询口现在只用于把缺指纹键
     *   收进 [Transcoded.degradedOverrideKeys] 降级清单（告警面用），**不是**容器签名段的值
     *   来源（那是补丁自己的真实签名，见 [transcode]）。
     * @param allowList 可选的**显式收窄声明**（市场面 `pack.json` 的 `overrideFunctions`；SAF
     *   直选无清单即缺省）。`null` = 未声明 ⇒ 名单按「模块函数名 ∩ base 函数名（事件名除外）」
     *   自动推导（存量第三方包零改）；非 `null` = 作者声明「本包只允许覆盖这些目标」，必须与
     *   实际推导集**逐一相等**：多列（声明了不可覆盖的目标）或漏列（补丁含同名函数却没声明）
     *   都拒——声明只能约束事实，不能放宽、也不能掩盖；`emptyList()` = 显式声明「本包不覆盖
     *   任何函数」，此时补丁一旦含同名函数即拒。用途 = 作者钉住覆盖面，防后续改源时意外多覆盖
     *   （次要能力，非覆盖名单的来源）。
     */
    data class OverrideInputs(
        val baseSignatureOf: (String) -> String = { "" },
        val allowList: Collection<String>? = null,
    )

    /**
     * 转码一个补丁编译产物。
     *
     * @param result 补丁编译产物（窗口形态；六基线必须与 [baseCounts] 一致，脱节即拒）。
     * @param baseFunctionNameByIndex base 函数物理下标 → 大写归一名（base 侧车 functions 段；
     *   `_CALL` 跨 base 目标的导入名来源，查不到 = fail-closed）。
     * @param packageId 转码产物的包身份（调用方派生；链接序与 manifest 配对用）。
     * @param override 覆盖装载的输入（base 签名查询口 + 可选显式收窄声明，见 [OverrideInputs]）。
     *   覆盖集里的每个键（= 模块函数名 ∩ base 函数名，事件名除外）都必须拿到非空 base 指纹，
     *   缺一个就抛——空指纹的覆盖会在装配期被
     *   [com.eratw.port.core.code.OverrideSignatureException] 整链炸成启动砖化
     *   （spec: SymbolCatalog.kt:213-237）。空表（缺省）= 纯加性补丁，`override-functions` 段与
     *   签名段都不写，产物与放开前逐字节一致。
     *
     *   **融合方案（R7-①）**：覆盖名单仍按「模块函数名 ∩ base 函数名」现推（存量第三方包
     *   无需补声明字段），但写进容器签名段的是 [result] 的**补丁真实签名**（编译期同一
     *   SignatureExport 渲染器的指纹），不是本查询口给的 base 签名——链接期 MODERN 闸直比
     *   base 指纹，签名不符即拒；若写 base 签名则恒等通过、ABI 闸形同虚设。本查询口只做
     *   「base 有指纹才允许覆盖」的前置判据（防链接期空指纹），不再充当签名值来源。
     * @throws IllegalArgumentException 窗口基线脱节 / 补丁调用了 base 不存在的函数 /
     *   super 哨兵出现 / 出口自检失败 / 覆盖声明缺签名指纹 /
     *   显式声明与实际可覆盖集不一致。
     */
    fun transcode(
        result: PatchCompiler.PatchResult,
        baseCounts: PatchCompiler.BaseCounts,
        baseFunctionNameByIndex: Map<Int, String>,
        packageId: String,
        override: OverrideInputs,
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
        val rebased = rebaseFunctions(program, result.funcMetas, funcBase, instrBase, entry.locals)
        val zeroProgram = buildZeroProgram(acc.keptWords, program, instrBase, keptInstrCount, rebased)

        // ---- 出口自检（与链接器模块级自检同款）----
        checkPoolIndexBounds(zeroProgram, allowCallSentinels = true)
        ErbcReader.requireJumpTargets(zeroProgram)

        val overrides = declareOverrides(
            rebased.metas, baseFunctionNameByIndex, override.baseSignatureOf, result.signatures,
            override.allowList,
        )
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
                signatures = overrides.signatures,
            ),
            descriptor = ModuleDescriptor(
                packageId = packageId,
                version = "1",
                externalCalls = acc.imports.keys.toList(),
                overrideFunctions = overrides.keys,
                privateDims = result.privateDims.groupBy({ it.functionKey }, { it.spec }),
            ),
            imports = acc.imports.keys.toList(),
            droppedEventEntries = entry.dropped,
            degradedOverrideKeys = overrides.degradedKeys,
        )
    }

    /**
     * 便捷入口：只给 base 签名查询口、不带显式收窄声明（= 覆盖名单按同名自动推导）。
     *
     * 与 [OverrideInputs] 版本同一实现，只为让「没有包清单」的调用点（SAF 直选、单测夹具）
     * 少写一层包装；语义与 `transcode(..., OverrideInputs(baseSignatureOf = f))` 逐字相同。
     */
    fun transcode(
        result: PatchCompiler.PatchResult,
        baseCounts: PatchCompiler.BaseCounts,
        baseFunctionNameByIndex: Map<Int, String>,
        packageId: String,
        baseSignatureOf: (String) -> String = { "" },
    ): Transcoded = transcode(
        result, baseCounts, baseFunctionNameByIndex, packageId,
        OverrideInputs(baseSignatureOf = baseSignatureOf),
    )

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
                // S9 改判 T1：CALL 本包被截断的合成事件入口 = 用户源直接 CALL 事件名 ——
                // 按事件名进 imports（链接期缺名残桩，运行期按 CompatiCallEvent 配置
                // 解析：NO = CallToEventFunc 原文；YES = 真调首成员）。不再拒绝转码。
                importSentinel(acc, w, ctx.metas[local].key, pc)
            } else {
                acc.keptWords[w + 1] = local
            }
        } else {
            rewriteImportCall(acc, w, pc, a, ctx.baseFunctionNameByIndex)
        }
    }

    /**
     * 导入哨兵发射（S9 T1/T2 后 base 名与事件名共用）：imports 按名内注去重
     *（同名共用同一哨兵槽），`a` 改写为 `-(extIdx+1)`。
     */
    private fun importSentinel(acc: RewriteAcc, w: Int, name: String, pc: Int) {
        val extIdx = acc.imports.getOrPut(name) { acc.imports.size }
        check(extIdx <= CallSentinels.IMPORT_INDEX_MAX) {
            "导入表越上界 ${CallSentinels.IMPORT_INDEX_MAX}（extIdx=$extIdx，pc=$pc）"
        }
        acc.keptWords[w + 1] = -(extIdx + 1)
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
        importSentinel(acc, w, name, pc)
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

/** 覆盖声明的出口：descriptor 的覆盖键集 + 容器签名表（两者必须是同一批键）。 */
private class OverrideDeclaration(
    val keys: Set<String>,
    val signatures: Map<String, String>,
    /**
     * 降级覆盖键（2026-11-23 KI 改判，BOARD KI-2）：base 侧车给不出签名指纹的覆盖键。
     * 照常进 [keys]/[signatures]（声明覆盖、链接期 last-wins 放行），另记清单供调用方
     * 汇进导入告警面（玩家可见「ABI 未校验」）。空 = 全部覆盖键都有 base 指纹（常态）。
     */
    val degradedKeys: List<String> = emptyList(),
)

/**
 * 覆盖声明面（R7-①）：模块函数名 ∩ base 函数名 = 覆盖集（事件名除外，同名事件是
 * 叠加语义而不是覆盖）。
 *
 * 判据在这里现推而不是从 [PatchCompiler] 携带来：作废（poison）的函数压根不进模块函数表，
 * 现推天然与产物同进退，不留「descriptor 点名了个模块里没有的键」这类分叉。
 * 键序 = 模块函数表序（转码后确定）⇒ 签名段字节确定。
 *
 * **融合方案**：base 签名只作前置判据（有指纹才允许覆盖，防链接期空指纹启动砖化），
 * 写进容器签名段的是 [patchSignatures] 给的补丁真实签名——链接期 MODERN 闸直比 base
 * 指纹，补丁 ABI 不符即拒；若这里写 base 签名则恒等通过、ABI 闸形同虚设。
 * **2026-11-23 KI 改判（BOARD KI-2）**：base 指纹缺失的键不再拒——改记入降级覆盖清单
 * （[OverrideDeclaration.degradedKeys]）照常声明覆盖；链接期签名闸已同步改判为
 * diagnostics 告警放行（spec: SymbolCatalog.kt build），砖化不再发生，前置闸撤销。
 * 补丁真实签名缺失仍拒（转码器自算指纹缺失 = 内部错位，真异常）。
 *
 * **可选收窄**：[allowList] 非 null 时是包清单的显式声明，必须与实际推导集逐一相等
 * （见 [checkDeclaredOverrides]）——声明不提供名单，只把「意外多覆盖」变成导入期硬拒。
 *
 * @throws IllegalArgumentException 覆盖键缺补丁真实签名（内部错位，真异常）；
 *   显式声明与实际可覆盖集不一致。
 */
private fun declareOverrides(
    metas: List<FuncMeta>,
    baseFunctionNameByIndex: Map<Int, String>,
    baseSignatureOf: (String) -> String,
    patchSignatures: Map<String, String>,
    allowList: Collection<String>?,
): OverrideDeclaration {
    val baseKeySet = baseFunctionNameByIndex.values.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
    val keys = LinkedHashSet<String>()
    for (meta in metas) {
        val key = meta.key.uppercase(Locale.ROOT)
        if (key in baseKeySet && !Symbols.isEventLabelName(key)) keys += key
    }
    allowList?.let { checkDeclaredOverrides(it, keys) }
    val signatures = LinkedHashMap<String, String>(keys.size)
    val degraded = ArrayList<String>()
    for (key in keys) {
        val baseSig = baseSignatureOf(key)
        // 2026-11-23 KI 改判（BOARD KI-2）：base 指纹缺失不再拒——该键照常声明覆盖
        //（链接期 last-wins 放行 + ABI 未校验告警，spec: SymbolCatalog.kt build diagnostics），
        // 键收进降级清单随导入结果上告警面（玩家可见「该函数 ABI 未校验」）。
        // 原判 = require(baseSig.isNotEmpty()) 抛——防空指纹覆盖在链接期炸成启动砖化，
        // 链接期改判后前置闸失去存在意义。
        if (baseSig.isEmpty()) degraded += key
        val patchSig = patchSignatures[key] ?: ""
        require(patchSig.isNotEmpty()) {
            "覆盖声明缺补丁真实签名：$key —— 本波编译产物未导出该函数的签名指纹；" +
                "写 base 签名顶替会让链接期 ABI 闸恒等通过（静默错值），故拒（fail-closed）"
        }
        signatures[key] = patchSig
    }
    return OverrideDeclaration(keys, signatures, degraded)
}

/**
 * 显式声明的双向一致性闸（可选收窄能力，见 [transcode] 的 `overrideAllowList`）。
 *
 * 只做约束不做推导：覆盖名单的真源仍是「模块函数名 ∩ base 函数名（事件名除外）」的现推集。
 * 两个方向都拒，是因为两种偏差都会造成**静默语义差**：多列 = 作者以为覆盖了实际覆盖不了
 * 的目标；漏列 = 补丁带着没声明的同名函数进来，作者以为已收窄。键按大写归一对齐模块侧口径。
 */
private fun checkDeclaredOverrides(allowList: Collection<String>, derived: Set<String>) {
    val declared = allowList.mapTo(LinkedHashSet()) { it.uppercase(Locale.ROOT) }
    val phantom = declared - derived
    require(phantom.isEmpty()) {
        "包声明了不可覆盖的目标：${phantom.joinToString("、")} —— 本补丁实际可覆盖集 = " +
            "「模块函数名 ∩ base 函数名（事件名除外）」= ${renderKeySet(derived)}；" +
            "声明只能是该集的子集，不能放宽（fail-closed）"
    }
    val undeclared = derived - declared
    require(undeclared.isEmpty()) {
        "补丁含与 base 同名但包声明未列出的函数：${undeclared.joinToString("、")} —— " +
            "声明集 = ${renderKeySet(declared)}" +
            (if (declared.isEmpty()) "（= 显式声明不覆盖任何函数）" else "") +
            "；这些函数仍会被链接期当作覆盖装载，请补进 pack.json 的 overrideFunctions " +
            "或改名/删除（fail-closed）"
    }
}

/** 键集的人话渲染（空集显式写「空」，免得报错里出现一对空括号）。 */
private fun renderKeySet(keys: Set<String>): String = if (keys.isEmpty()) "空" else keys.joinToString("、")

/** 窗口基线核对（脱节 = 编译上下文错位，fail-closed）。纯函数，与对象状态无关。 */
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
