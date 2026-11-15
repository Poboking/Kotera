package com.eratw.port.core.mod

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ContainerRole
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleContainer
import com.eratw.port.core.patch.ZeroBasePools
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale

/**
 * 四池容器补丁（ERBM MODULE 态）→ Mod 链装配器输入形态的适配器
 * （CONTRACT-RT2-M2CODEC 裁定 4「decode → 链接器输入适配」）。
 *
 * 关闭 M2-2 交付登记的「需组长接线」项：把零基线四池解释为 `Program` 加 `List<FuncMeta>`
 * 并喂 `LinkableModule` 形态（f5b349d6c 交付消息原文）。适配只做**输入形态转换**，
 * 不复制任何链接语义 —— 链接语义的唯一真源仍是 `ModuleLinker`（五步链
 * `ModChainAssembler.assemble` 原样消费本适配器的产物，零改动）。
 *
 * ## 载荷解释（poolBlob = Program 自身的 .erbc 序列化）
 *
 * [com.eratw.port.core.patch.ZeroBasePools.poolBlob] 按容器格式 KDoc「池内容以原始字节块
 * 原样保真，容器不解释树」承载零基线 [com.eratw.port.core.code.Program] 的**完整
 * `.erbc` 序列化字节**（`ErbcWriter.encode` 原样直存，与 `ErbModuleContainer`（ERTM1）
 * 对 Program 的直存口径同源；不重造第二套序列化）。适配即 `ErbcReader.decode(poolBlob)`
 * 还原 Program，再用容器自带的四计数与指令流对账（见下 fail-closed 节）。
 * `codeWords` 与 poolBlob 内 CODE 段是同一指令流的两次承载，逐字对账防两份漂移。
 *
 * ## FuncMeta 还原口径（与既有两路同源，不立第三份）
 *
 * 四池容器不携带 FuncMeta（格式只有 descriptor/imports/callTargets/relocations/exports
 * 加四池），运行期元数据从 Program 函数表还原，判据照抄既有两路的同源约定：
 * - 形参桶：`paramNames` 按 `$` 后缀分桶（`argIntCount = count { !it.endsWith("$") }`），
 *   与 `GameSession.buildPatchFunctionMetas` 同款；形参名缺失时桶为 0（ARG 不绑定，
 *   不拿 arity 猜）。TODO(verify)：`.erbc` v1 形态产物（无段 8）会退化到此，M2-4 转码器
 *   必须产出带形参名段的 v2 载荷。
 * - 事件面：Program 函数名 `<事件名>#<下标>` = 事件成员（`isEvent = true`，键取 `#` 前
 *   根名），裸事件名 = 合成入口位（`isEvent = false`，position = `·event-entry·`）——
 *   与 `CorpusCompile` 的事件成员命名约定（`"${d.key}#${d.defIndex}"`）及补丁路还原判据
 *   同源。
 * - 方法/返回通道：`returnsStr`/`returnsFloat` 只在导出表（exports）带**可解析的 SIG1
 *   规范串**时按其返回通道码还原；容器格式没有 `#FUNCTION` 方法位通道，
 *   `isMethod` 恒 false。TODO(verify)：方法 Mod 在四池容器上不可表达（M2-4 转码器
 *   契约项；ERTM1 `.erbm` 路不受影响——它直存 FuncMeta 全字段）。
 * - `defIndex = i`（模块内装载序；链接期由 `LinkCandidateAssembler` 改写为绝对下标）。
 *
 * ## 导出表（exports）的消费口径
 *
 * 导出项 `signature` 字段**能被 [BaseSignatureCatalog.decode] 解析**（SIG1 规范串）的条目
 * 视为签名导出记录：键 = 导出名大写归一，值 = 规范串指纹（[BaseSignatureCatalog.fingerprint]），
 * 进 `CompiledModule.signatures` 供 MODERN 覆盖签名闸对账。解析失败的条目一律忽略
 * （签名特性缺位，不炸装载；覆盖是否放行由核心签名闸 fail-closed 裁决，不在这里放宽）。
 * TODO(verify)：导出名的键形约定（与 Program 函数名大写归一对应）是给 M2-4 转码器的
 * 写侧契约，M2-4 落地时对拍。
 *
 * ## fail-closed 对账（容器自描述 vs 载荷实体，任一脱节整包拒）
 *
 * 容器头的 imports/callTargets/relocations/exports 是格式自描述面（decode 的哨兵闸与
 * 重定位机器闸已验），本适配器再对**自描述与载荷实体的一致性**逐项对账：
 * 1. 六基线全 0（零基线形态；`LinkableModule.validate` 会再验一次，这里提前带源名报错）；
 * 2. 四计数（long/string/expr/statement）与 Program 实际池计数逐项相等；
 * 3. `codeWords` 与 Program 指令流逐字相等；
 * 4. `relocations[i].pc` 落在载荷指令区间内，且该 pc 的实际操作码与声明一致 ——
 *    重定位表引用不存在的指令或谎报操作码 = 容器自述与载荷脱节，拒。
 *
 * descriptor 经 [com.eratw.port.core.patch.ContainerDescriptor.toModuleDescriptor]（M2-2
 * 预留扩展点）构造；装配器第一步只从 Manifest 装载产物取 descriptor（单一真源不注入），
 * 故本适配器同时产出 `ManifestCodec.encode(descriptor)` 的**内存内** ManifestSource ——
 * 磁盘上仍只有容器件本身（裁定 1「免 manifest」），manifest 字节只是喂五步链既有
 * 单一入口的载体，不落盘、不成第二份持久真源。
 *
 * ## 已登记余量：外部导入载荷形态（TODO(verify) + 需组长接线）
 *
 * 容器格式自身接受外部导入哨兵（`_CALL.a = -(extIdx+1)`，`validateCallTargets` 放行），
 * 但 poolBlob 直存 `.erbc` 的载荷经 `ErbcReader.decode` 还原时，装载闸
 * （`isRejectedCallNegative`，RT2 superfix 裁定「仅两个 super 设计哨兵放行、其余负值照拒」，
 * 负控钉板见 `ErbcStreamBaseTest` 第 6 节）会**拒绝导入哨兵流** —— 本适配器把它如实
 * fail-closed（带源名整包拒）。接通 = 装载闸放行第三类负值（动 superfix 已钉的负控面）
 * 或格式另立导入载荷段，两者都超出本波文件所有权，归 M2-4 转码器立项时一并裁定。
 * 链接期的导入决议机器（`ExternalImportTable` + `CallRelocation` External 支）不受影响，
 * 由 `ModuleLinkerTest` 的直连路覆盖。
 */
object ModuleContainerAdapter {

    /** 适配产物：装配器五步链的两个输入件（source 与 compiled 按 packageId 配对）。 */
    class AdaptedModule(
        /** 内存内 ManifestSource（[ManifestCodec.encode] 产物；不落盘）。 */
        val source: ManifestSource,
        /** 链接器输入形态的编译模块（descriptor 从 source 解码，program/functions 由本适配器还原）。 */
        val compiled: CompiledModule,
    )

    /**
     * 把一个已 decode 的四池容器（MODULE 态）适配成装配器输入。
     *
     * @param container [com.eratw.port.core.patch.ModuleContainerCodec.decode] 的产物。
     * @param sourceName 诊断用来源名（建议 `mods/<文件名>`；进 FuncMeta.position 与报错文案）。
     * @throws IllegalArgumentException role 非 MODULE（LINKED_PROGRAM 消费面未接线，见
     *   类 KDoc「LINKED 态」注）/ 载荷不是合法零基线 Program / 任一对账脱节。
     */
    fun adapt(container: ModuleContainer, sourceName: String): AdaptedModule {
        // LINKED 态登记余量（裁定 4）：LINKED 容器只携带三视图段（symbol-catalog/dispatch/
        // event-registry），不携带程序载荷（四池/指令流），无法在本格式内还原可执行 Program；
        // 接通它需要格式扩载荷或 GameSession/ModChainRuntimePlan 增「预链接产物」消费路，
        // 都超出本波文件所有权 —— 显式 fail-closed 点名余量，不静默跳过、不装半成品。
        require(container.role == ContainerRole.MODULE) {
            "容器 [$sourceName] 是 ${container.role.wire} 态 —— LINKED_PROGRAM 消费面未接线" +
                "（余量登记：格式未携带程序载荷，接通需另立项），fail-closed 整包拒绝"
        }
        val pools = requireNotNull(container.pools) {
            "容器 [$sourceName] 声明 MODULE 态却没有零基线四池载荷 —— 容器损坏，fail-closed 整包拒绝"
        }

        val program = try {
            ErbcReader.decode(pools.poolBlob.toByteArray())
        } catch (e: ErbcDecodeException) {
            throw IllegalArgumentException(
                "容器 [$sourceName] 的四池载荷不是合法零基线 .erbc 程序（${e.message}）" +
                    "—— fail-closed 整包拒绝",
                e,
            )
        }
        verifySelfDescription(container, pools, program, sourceName)

        val signatureTable = exportSignatureTable(container, sourceName)
        val metas = deriveMetas(program, sourceName, signatureTable)
        val descriptor = try {
            container.descriptor.toModuleDescriptor(container.imports)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "容器 [$sourceName] 的描述符非法：${e.message} —— fail-closed 整包拒绝",
                e,
            )
        }
        // 内存内 ManifestSource：喂五步链「descriptor 只从 Manifest 装载产物来」的单一入口。
        // 不落盘；磁盘真源仍只有容器件本身（裁定 1 免 manifest）。
        val source = ManifestSource(sourceName, ManifestCodec.encode(descriptor))
        val compiled = CompiledModule(
            packageId = descriptor.packageId,
            program = program,
            functions = metas,
            signatures = signatureTable.mapValues { it.value.fingerprint },
        )
        return AdaptedModule(source, compiled)
    }

    /**
     * fail-closed 对账 1–4（类 KDoc「对账」节）：零基线形态 / 四计数 / 指令流逐字 /
     * 重定位表 pc 与实际操作码。从 [adapt] 拆出只是把校验段独立成段，判据与报错文案
     * 逐字不变，不新增也不放宽任何一条闸。
     */
    private fun verifySelfDescription(
        container: ModuleContainer,
        pools: ZeroBasePools,
        program: Program,
        sourceName: String,
    ) {
        // 对账 1：零基线形态。
        require(
            program.codeBase == 0 && program.statementBase == 0 && program.stringBase == 0 &&
                program.longBase == 0 && program.exprBase == 0 && program.functionBase == 0,
        ) {
            "容器 [$sourceName] 的载荷 Program 不是零基线形态（codeBase=${program.codeBase}）" +
                "—— 链接器只收零基线 Module，fail-closed 整包拒绝"
        }

        // 对账 2：四计数。
        require(pools.longCount == program.constantCount) {
            countMismatch(sourceName, "long", pools.longCount, program.constantCount)
        }
        require(pools.stringCount == program.stringCount) {
            countMismatch(sourceName, "string", pools.stringCount, program.stringCount)
        }
        require(pools.exprCount == program.exprCount) {
            countMismatch(sourceName, "expr", pools.exprCount, program.exprCount)
        }
        require(pools.statementCount == program.statementCount) {
            countMismatch(sourceName, "statement", pools.statementCount, program.statementCount)
        }

        // 对账 3：指令流逐字相等（codeWords 与 poolBlob 内 CODE 段是同一流的两份承载）。
        val words = program.codeSource.toIntArray()
        require(pools.codeWords.toIntArray() contentEquals words) {
            "容器 [$sourceName] 的 codeWords(${pools.codeWords.size} 字)与载荷指令流" +
                "(${words.size} 字)不一致 —— 容器自描述与载荷脱节，fail-closed 整包拒绝"
        }

        // 对账 4：重定位表的 pc 与操作码都须落在载荷指令流上。
        val instrCount = program.instructionCount
        for ((i, r) in container.relocations.withIndex()) {
            require(r.pc in 0 until instrCount) {
                "容器 [$sourceName] 的 relocations[$i].pc=${r.pc} 越出载荷指令区间" +
                    "[0, $instrCount) —— 容器自描述与载荷脱节，fail-closed 整包拒绝"
            }
            val actualOp = words[r.pc * Instruction.WORDS]
            require(actualOp == r.opcode) {
                "容器 [$sourceName] 的 relocations[$i] 声明 " +
                    "${Opcode.nameOf(r.opcode)}(${r.opcode})，载荷 pc=${r.pc} 实为 " +
                    "${Opcode.nameOf(actualOp)}($actualOp) —— fail-closed 整包拒绝"
            }
        }
    }

    /** 导出表里可解析的 SIG1 签名记录（键大写归一；重复键 fail-closed）。 */
    private data class ExportSignature(val fingerprint: String, val returnChannel: Char)

    private fun exportSignatureTable(
        container: ModuleContainer,
        sourceName: String,
    ): Map<String, ExportSignature> {
        val out = LinkedHashMap<String, ExportSignature>()
        for (e in container.exports) {
            val signature = BaseSignatureCatalog.decode(e.signature) ?: continue
            val key = e.name.uppercase(Locale.ROOT)
            val fingerprint = BaseSignatureCatalog.fingerprint(e.signature)
            val previous = out.put(key, ExportSignature(fingerprint, signature.returnChannel))
            require(previous == null) {
                "容器 [$sourceName] 的导出签名键重复：$key —— fail-closed 整包拒绝"
            }
        }
        return out
    }

    /** 从 Program 函数表还原 FuncMeta（口径见类 KDoc「FuncMeta 还原口径」）。 */
    private fun deriveMetas(
        program: Program,
        sourceName: String,
        signatureTable: Map<String, ExportSignature>,
    ): List<FuncMeta> {
        val out = ArrayList<FuncMeta>(program.functions.size)
        for ((i, f) in program.functions.withIndex()) {
            val upperKey = f.upperKey
            val rootKey = upperKey.substringBefore('#')
            val isMember = '#' in upperKey && Symbols.isEventLabelName(rootKey)
            val isEntry = '#' !in upperKey && Symbols.isEventLabelName(upperKey)
            val signature = signatureTable[upperKey]
            out += FuncMeta(
                index = i,
                key = if (isMember) rootKey else upperKey,
                name = if (isMember) rootKey else f.name,
                isEvent = isMember,
                // 四池容器没有方法位通道；方法 Mod 不可表达（TODO(verify) 见类 KDoc）。
                isMethod = false,
                argIntCount = f.paramNames.count { !it.endsWith("$") },
                argStrCount = f.paramNames.count { it.endsWith("$") },
                returnsStr = signature?.returnChannel == 'S',
                returnsFloat = signature?.returnChannel == 'F',
                defIndex = i,
                position = if (isEntry) "·event-entry·" else sourceName,
            )
        }
        return out
    }

    private fun countMismatch(sourceName: String, pool: String, declared: Int, actual: Int): String =
        "容器 [$sourceName] 的 $pool 计数与载荷不符：声明 $declared，实际 $actual" +
            " —— 容器自描述与载荷脱节，fail-closed 整包拒绝"
}
