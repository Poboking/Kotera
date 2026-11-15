package com.eratw.port.core.mod

import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.LinkedProgram
import com.eratw.port.core.proc.FunctionTable

/**
 * 链接产物的**运行期四视图**（P1-5 余量一：`program` / `FunctionTable` / `functionDispatch`
 * 三表一致替换 + M2-6 第四视图 `EventRegistry` 四桶表）。
 *
 * ## 单一真源
 *
 * 四张表全部从 [SymbolCatalog] 派生 —— 这是 `SymbolCatalog` 类头写死的「四视图同源」不变量
 * （LSC Resolve 一次，FunctionTable / functionDispatch / MethodTable / EventRegistry 同源，
 * 运行期只读）。本类只做派生与自检，不重新裁决胜者、不改任何链接判定。
 *
 * ## 构造期自检（三表一致的两条硬不变量）
 *
 * 1. **长度相等**：候选物理下标稠密覆盖 `0..program.functions.size-1`，且
 *    `dispatch.size == program.functions.size`（`VirtualMachine` 构造期也查这条，
 *    这里提前到装配期，让「三表脱节」在装载相位就炸而不是运行期错函数）。
 * 2. **分派幂等**：`dispatch[dispatch[i]] == dispatch[i]` 恒成立
 *    （`SymbolCatalog` 的 v6.2-c 不变量；这里再自检一次是给「从别处注入的 catalog」
 *    留一道闸，成本 O(n)）。
 *
 * ## 性能闸：无覆盖 ⇒ dispatch 为 null
 *
 * `SymbolCatalog` 的 v6.2-c 性能闸：无覆盖/无切面的链接产物**不启用**查表
 * （VM 走物理下标直调）。本类照此把 `functionDispatch` 置 null —— 恒等表只是语义基准，
 * 不作为每调用的运行时成本。
 *
 * ## 诚实边界
 *
 * 派生的是「能跑」所需的三视图；`.erbm` 容器的生产来源（erbcc 模块编译档）与宿主侧的
 * 原子替换接线属同批余量，本类不假装它们已通：没有载体时 [of] 永远走旁路返回 null。
 * `MethodTable` 在本仓尚未成形，未纳入本类。
 */
class ModChainRuntimePlan private constructor(
    /** 链接后的完整程序（base + 模块尾巴，指令与池已重定位）。 */
    val program: Program,
    /** 与 [program] 函数表逐下标对齐的元数据表（按名解析 / 事件派发的读面）。 */
    val functionTable: FunctionTable,
    /** null = 无覆盖（VM 直调快路径）；非 null 时长度必等 [program] 函数表长度。 */
    val functionDispatch: IntArray?,
    /** 最终 dispatch 是否存在非恒等项（= `LinkedProgram.hasOverrides`，不是「装了 Mod」）。 */
    val hasOverrides: Boolean,
    /**
     * 事件一对多的四桶执行序（M2-6 第四视图）。与 [functionTable] 同源派生自同一份 LSC，
     * 承载 base + 各 Mod 同名事件的**全部**物理成员（普通函数一名一胜者，事件一名全成员）。
     * 运行期由 [com.eratw.port.core.proc.ProcessRuntime.executeEvent] 消费；旁路装配返回
     * null 的是 [of] 整个计划，不是这个字段。
     */
    val eventRegistry: EventRegistry,
    /**
     * Mod 链侧私有 #DIM 声明（M2-4）：各 descriptor `@private-dims` 的聚合（保序按链接
     * 输入枚举序；序不参与语义，注册失败逐条进清单不打断）。`GameSession` 把它追加进
     * 会话私有变量注册链——模块链函数体内的私有 `#DIM` 变量从此可解析（M2-4 前模块链
     * 无该消费线）。`of(LinkedProgram)` 直派生路径（无 descriptor）恒空表。
     */
    val privateDims: List<BootMetadata.PrivateDim>,
) {

    companion object {

        /**
         * 从装配器结果派生；**旁路（无 Mod / 无模块）返回 null** —— 调用方保持既有
         * program / FunctionTable / dispatch / 事件派发原样，这是「关态与未接线前逐位一致」
         * 的判据面。非旁路复用装配器第五步已派生的 [AssembledChain.eventRegistry]
         * **同一实例**，不重建第二张四桶表（M2-6 单一真源）。私有 #DIM 从
         * [AssembledChain.descriptors] 聚合（`ModChainAssembler` 零改动）。
         */
        fun of(chain: AssembledChain): ModChainRuntimePlan? {
            if (chain.byPassed) return null
            val linked = requireNotNull(chain.linked) {
                "非旁路装配必须带 LinkedProgram（装配器不变量破坏）"
            }
            val registry = requireNotNull(chain.eventRegistry) {
                "非旁路装配必须带 EventRegistry（装配器五步链不变量破坏）"
            }
            val plan = fromLinked(linked.program, linked.catalog, registry)
            val dims = ArrayList<BootMetadata.PrivateDim>()
            for (d in chain.descriptors) {
                for ((functionKey, specs) in d.privateDims) {
                    for (spec in specs) dims += BootMetadata.PrivateDim(functionKey, spec)
                }
            }
            return if (dims.isEmpty()) plan else plan.withPrivateDims(dims)
        }

        private fun ModChainRuntimePlan.withPrivateDims(
            dims: List<BootMetadata.PrivateDim>,
        ): ModChainRuntimePlan = ModChainRuntimePlan(
            program, functionTable, functionDispatch, hasOverrides, eventRegistry, dims,
        )

        /**
         * 从链接产物直接派生（生产与测试同一入口，不给测试留第二套口径）。
         * [eventRegistry] 由装配器传入时复用；null 时从 [catalog] 现派生（等价内容、同一契约）。
         */
        fun fromLinked(
            program: Program,
            catalog: SymbolCatalog,
            eventRegistry: EventRegistry? = null,
        ): ModChainRuntimePlan {
            val metas = catalog.candidates.sortedBy { it.meta.index }.map { it.meta }
            require(metas.size == program.functions.size) {
                "候选函数 ${metas.size} 个 vs program 函数表 ${program.functions.size} 个 —— " +
                    "三表脱节，拒绝装配"
            }
            for ((expected, meta) in metas.withIndex()) {
                require(meta.index == expected) {
                    "候选物理下标不连续：位置 $expected 上是 ${meta.index}（LSC 候选表损坏）"
                }
            }

            val dispatch = if (catalog.hasOverrides) catalog.dispatch.copyOf() else null
            if (dispatch != null) {
                require(dispatch.size == program.functions.size) {
                    "dispatch 长度 ${dispatch.size} 必须等于程序函数表长度 ${program.functions.size}"
                }
                for ((from, to) in dispatch.withIndex()) {
                    require(to in dispatch.indices && dispatch[to] == to) {
                        "dispatch 幂等式不成立：dispatch[$from]=$to，" +
                            "dispatch[$to]=${dispatch.getOrNull(to)}"
                    }
                }
            }
            return ModChainRuntimePlan(
                program = program,
                functionTable = FunctionTable(metas),
                functionDispatch = dispatch,
                hasOverrides = catalog.hasOverrides,
                eventRegistry = eventRegistry ?: EventRegistry.of(catalog),
                privateDims = emptyList(),
            )
        }

        /** 从 [LinkedProgram] 派生（`ModChainAssembler` 之外的调用方用；口径与 [fromLinked] 相同）。 */
        fun of(linked: LinkedProgram): ModChainRuntimePlan =
            fromLinked(linked.program, linked.catalog)
    }
}
