package com.eratw.port.core.mod

import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.LinkInputs
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.LinkedProgram
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestLoader
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale

/**
 * Mod 链装配器（RT2 波 C 车 C3，对应蓝图第 12.1 节的装配半边）。
 *
 * 本类只做接线：把装载产物 descriptor 集合配成 LinkableModule、调用链接器、
 * 再把链接产物的 catalog 喂给事件注册表。不改任何既有引擎判定；链接器与事件
 * 注册表此前在 main 内没有生产调用者。纯 JVM、无状态：本对象是单例但不持有
 * 可变全局，每次 assemble 的中间状态全在栈上。
 *
 * TODO(verify)：装配器本身是 RT2 自研，C# 原版无对应物；语义权威 = 蓝图第 6 节、
 * 第 10 节 ABI-7、第 12.1 节以及 ModuleLinker.kt 的 KDoc。事件四桶序的 C# 锚
 * 只用于消费 EventRegistry 的既有判据，不给本装配链背书；卡点见交付消息。
 */
object ModChainAssembler {

    /**
     * 固定五步：装载 decode、声明事务 freeze、装配配对与 validate、link、事件注册表派生。
     * 任一失败原样抛、不 catch、不返回半成品（裁定 F）。无 Mod 完全旁路（裁定 D）。
     *
     * @param sources Manifest 来源集合；descriptor 集合只从这里解码得到。**枚举顺序
     *   不参与链接语义**（链接序由规划层按依赖约束 + 显式排序配置 + `packageId` 规范序裁决）。
     * @param compiledModules 调用方注入的已编译零基线模块；只按 packageId 参与配对。
     * @param policy 透传给链接器，带模块组合默认 MODERN。
     * @param trace 可观测轨迹，装配器在五个真实调用点打点；生产不传即空实现。
     * @param linkOrder 链接请求级显式排序配置（缺省 = 规范序）；透传给链接器，
     *   不与 [sources] 的枚举顺序耦合。
     */
    fun assemble(
        sources: List<ManifestSource>,
        compiledModules: List<CompiledModule>,
        base: Program,
        baseMetas: List<FuncMeta>,
        policy: ResolverPolicy = ResolverPolicy.MODERN,
        builtinVariableNames: Set<String> = emptySet(),
        csvTableCapacities: Map<String, Int> = emptyMap(),
        baseCsvKeywords: Map<String, Map<String, Int>> = emptyMap(),
        baseSignatureOf: (String) -> String = { "" },
        trace: ModChainTrace = ModChainTrace(),
        linkOrder: LinkOrder = LinkOrder(),
        /**
         * 预冻结的声明快照（M1-4 阶段二单源，CONTRACT-RT2-M14B-IMPL §1 裁定 3）。
         *
         * 非 null = 装载相位已经用**真实容量/关键字**跑过唯一一次 `ManifestLoader.load`，
         * 本处只消费、不再装载（两份快照会漂移）；null（缺省）= 原有自装载口径，
         * 既有调用点与测试零变化。
         */
        snapshot: DeclarationSnapshot? = null,
    ): AssembledChain {
        // 裁定 D：无 Mod 完全旁路。不调 link、不构造 catalog、不调事件注册表，
        // 直接返回 base 原实例；事件视图沿用 base-only 既有口径，不产出第二份事件表。
        if (sources.isEmpty() && compiledModules.isEmpty()) {
            trace.record("bypass")
            return AssembledChain(
                program = base,
                descriptors = emptyList(),
                snapshot = null,
                linked = null,
                eventRegistry = null,
                hasOverrides = false,
                byPassed = true,
            )
        }

        // 第一步 装载：先把全部 Manifest 解码完毕再进入下一步（fail-closed）。
        // descriptor 集合只从这里来，不凭空构造、不注入第二份。
        val descriptors = ArrayList<ModuleDescriptor>(sources.size)
        for (src in sources) {
            descriptors += ManifestCodec.decode(src.bytes)
        }
        trace.record("decode")

        // 第二步 声明事务 freeze：只读调用 ManifestLoader.load。它内部会再 decode 一次，
        // 这是只读面不动的必然代价（裁定 A），合并 decode 属需组长接线。
        // onCommit 只作观测打点、不改状态；freeze 冲突时不会触发，异常整包抛。
        // M1-4 阶段二：装载相位已用真实容量/关键字跑过唯一一次 load 时直接消费（裁定 3），
        // 本处不再装载 —— 否则会出现「CSV 注入用一份容量、Mod 链用另一份」的漂移。
        val frozen: DeclarationSnapshot = snapshot ?: ManifestLoader.load(
            sources,
            builtinVariableNames = builtinVariableNames,
            csvTableCapacities = csvTableCapacities,
            baseCsvKeywords = baseCsvKeywords,
            onCommit = { _ -> trace.record("load(freeze)") },
        )
        if (snapshot != null) trace.record("load(shared)")

        // 第三步 装配：按 packageId 一一配对，逐个过 LinkableModule.validate。
        // 配对不齐、validate 失败都原样抛，整包拒绝。
        val modules = pairAndValidate(descriptors, compiledModules)
        trace.record("validate")

        // 第四步 link：模块集非空（旁路已先返回），不会撞空集拒绝。
        val linked: LinkedProgram = ModuleLinker.link(
            LinkInputs(
                base = base,
                baseMetas = baseMetas,
                modules = modules,
                policy = policy,
                baseSignatureOf = baseSignatureOf,
                linkOrder = linkOrder,
                diagnostics = trace::recordDiagnostic,
            ),
        )
        trace.record("link")

        // 第五步 事件注册表：从链接产物的 catalog 派生，随结果带出。
        val registry: EventRegistry = EventRegistry.of(linked.catalog)
        trace.record("EventRegistry.of")

        return AssembledChain(
            program = linked.program,
            descriptors = descriptors.toList(),
            snapshot = frozen,
            linked = linked,
            eventRegistry = registry,
            // 裁定 C：直接取 LinkedProgram.hasOverrides，不用装了 Mod 或模块非空替代。
            hasOverrides = linked.hasOverrides,
            byPassed = false,
        )
    }

    /**
     * 配对：descriptor 与 CompiledModule 数量相等、packageId 一一对应且互不重复。
     * 任一不齐即抛可定位异常；构造出的 LinkableModule 逐个 validate（失败原样传播）。
     */
    private fun pairAndValidate(
        descriptors: List<ModuleDescriptor>,
        compiledModules: List<CompiledModule>,
    ): List<LinkableModule> {
        require(descriptors.size == compiledModules.size) {
            "装配配对不齐：descriptor ${descriptors.size} 个 / CompiledModule " +
                "${compiledModules.size} 个 —— 整包拒绝"
        }
        val byPkg = HashMap<String, CompiledModule>(compiledModules.size * 2)
        for (c in compiledModules) {
            val prev = byPkg.put(c.packageId, c)
            require(prev == null) {
                "CompiledModule 的 packageId 重复：${c.packageId} —— 整包拒绝"
            }
        }
        val seenDescriptors = HashSet<String>(descriptors.size * 2)
        for (d in descriptors) {
            require(seenDescriptors.add(d.packageId)) {
                "descriptor 的 packageId 重复：${d.packageId} —— 整包拒绝"
            }
        }
        val modules = ArrayList<LinkableModule>(descriptors.size)
        for (d in descriptors) {
            val c = byPkg[d.packageId]
                ?: throw IllegalArgumentException(
                    "装配配对不齐：descriptor [${d.packageId}] 没有对应的 " +
                        "CompiledModule —— 整包拒绝",
                )
            val module = AssembledModule(d, c.program, c.functions, c.signatures)
            LinkableModule.validate(module)
            modules += module
        }
        return modules
    }
}

/**
 * 调用方注入的已编译零基线模块。装配器不编译 ERB、不解析容器字节块（裁定 B），
 * 零基线 Program、FuncMeta 列表与签名映射一律由调用方给；descriptor 不从这里注入，
 * 只从 Manifest 装载产物来（裁定 A 第一步，catalog 来源唯一真源）。
 */
class CompiledModule(
    val packageId: String,
    val program: Program,
    val functions: List<FuncMeta>,
    val signatures: Map<String, String> = emptyMap(),
)

/**
 * 可观测轨迹：由调用方注入，装配器在五个真实调用点打点。不是静态可变全局，
 * 每次 assemble 用独立实例；生产调用不传时就是一个空轨迹。
 */
class ModChainTrace {
    private val steps = ArrayList<String>()

    /**
     * 覆盖裁决告警收集口（2026-11-23 KI 改判，BOARD KI-2）：撞名三态告警随留痕汇导出。
     * 缺省空实现 = 既有调用点零变化；app 装配层把告警汇接到 logSink。
     */
    var overrideDiagnostics: (String) -> Unit = {}

    fun record(step: String) {
        steps += step
    }

    /** 覆盖裁决告警的汇接点（链接器 diagnostics 的透传目标）。 */
    fun recordDiagnostic(message: String) {
        overrideDiagnostics(message)
    }

    fun snapshot(): List<String> = steps.toList()
}

/**
 * 装配结果。无 Mod 旁路时 snapshot、linked、eventRegistry 均为 null，
 * program 就是 base 的原实例；hasOverrides 直接透传 LinkedProgram 的判据（裁定 C）。
 */
class AssembledChain(
    val program: Program,
    val descriptors: List<ModuleDescriptor>,
    val snapshot: DeclarationSnapshot?,
    val linked: LinkedProgram?,
    val eventRegistry: EventRegistry?,
    val hasOverrides: Boolean,
    val byPassed: Boolean,
)

/**
 * descriptor 来自 decode 的唯一真源，program 与 FuncMeta 由 CompiledModule 注入，
 * 签名查询按 FuncMeta.key 的大写归一查表（口径同 ModuleLinkerTest 的 TestModule）。
 */
private class AssembledModule(
    override val descriptor: ModuleDescriptor,
    override val program: Program,
    override val functions: List<FuncMeta>,
    private val signatures: Map<String, String>,
) : LinkableModule {
    override fun signatureHashOf(functionKey: String): String =
        signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
}
