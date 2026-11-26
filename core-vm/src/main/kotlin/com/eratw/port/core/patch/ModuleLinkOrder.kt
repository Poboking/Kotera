package com.eratw.port.core.patch

import java.util.PriorityQueue

/**
 * 链接请求级的**显式排序配置**（2026-11-12 架构裁决 B'；同日二审冻结排序语义）。
 *
 * 排序意图属于「这一次链接请求 / 当前安装组合」，不属于 Mod 自身声明：同一个 Mod 在
 * 组合甲里可以先于 B、在组合乙里后于 B，这是组合配置的差异而非模块属性的差异。因此
 * 本配置**不进 [ModuleDescriptor]**、不进 18 节文本 Manifest、不扩 ABI-5 字段面。
 *
 * ## 语义冻结：就绪模块优先级（Kahn ready-priority）
 *
 * [preferredPackageIds] 是**就绪队列的比较键**，不是「期望的完整加载顺序」：每一轮只从
 * 「当前已满足全部依赖、可以链接」的模块里，取配置位置最靠前的一个。未显式指定的模块
 * 排在所有已指定模块之后，彼此按 `packageId` 升序。
 *
 * 因此本配置**不承诺**输出序列等于该列表，也**不承诺**对玩家原始排列做最小调整：
 * 配置 `[a,b,c,d]` 且 `a` 硬依赖 `d` 时，初始就绪集合只有 `{b,c,d}`，`a` 不在候选内，
 * 结果是 `[b,c,d,a]`；而「先补 `d` 再续上 `a`」的 `[d,a,b,c]` 属于另一种排序政策，
 * 本实现不采用。玩家若想要后者，把前置依赖 `d` 排到 `a` 前面即可 —— 配置 `[d,a,b,c]`
 * 在本实现下确实得到 `[d,a,b,c]`，行为可控而非残缺。
 *
 * 选这条政策而非「尽量保留完整顺序」的理由：非全序 DAG 上的「最小调整」没有唯一定义
 * （距离度量与多处阻塞时的解阻塞次序都需另定），而就绪优先级是单一、可枚举、可被
 * 测试穷举的偏序；它也不会为迁就一个被阻塞的高优先级模块而饿死已就绪模块。
 * **若产品负责人裁定玩家期待的是后者，必须在玩家排序 UI 接线之前另行裁决并替换算法**
 * （独立波次，含 ABI-8 指纹语义复核），不得在 UI 层用输入 List 顺序偷偷模拟。
 *
 * - [preferredPackageIds] 是**有序 ID 列表**（不是数值优先级）：避免稀疏值、重复值与
 *   「数值大是更靠前还是更靠后」的解释歧义；靠前者在当前就绪集合里优先出队。
 * - 缺省空列表 = 无显式排序意图，全部模块走 [ModuleLinkOrder] 的 `packageId` 规范序。
 * - `requires` / `loadAfter` 形成的拓扑约束**始终优先**于本配置：依赖边让模块不就绪，
 *   偏好只在「同时就绪」的模块之间裁决，绝不会把排序偏好变成依赖环。
 * - 不在配置里出现的模块按 `packageId` 升序兜底；配置里出现但组合里没有的 ID 忽略
 *   （Mod 被卸载 / 禁用时配置无需同步改写）；配置内重复 ID 由 [ModuleLinkOrder.plan]
 *   整组拒绝。
 * - 链接序在 last-wins 语义下**后者胜**：列表靠前 = 先链接 = 覆盖优先级更低。UI 必须
 *   展示规划层解析后的实际顺序并解释依赖造成的调整，不能把配置列表当成最终顺序展示。
 *
 * 刻意**不采用 `Comparator` 函数对象**：它可能捕获外部状态、不可稳定序列化、难以参与
 * ABI-8 缓存指纹；显式数据结构才能成为可审计的语义输入。
 */
data class LinkOrder(
    val preferredPackageIds: List<String> = emptyList(),
)

/**
 * 模块链接序裁决（M2-1 Linker 的前半，纯算法、无 IO）。
 *
 * 蓝图 §11 M2 Gate：「A/B/C 独立二进制任意组合免重编（前提：依赖满足、冲突策略明确
 * ——**缺依赖的组合必须拒绝而非强跑**）」。链接器在拼接物理池之前必须先定序：
 *
 * - [ModuleDescriptor.requires] 是**硬依赖**：目标包必须在本组合里，否则整组拒绝
 *   （缺依赖强跑 = 链接期按「函数可能不存在」猜，正是 Gate 禁止的）。条目还可内嵌
 *   版本约束 `pkg@[min,max]`（语法权威见 [ModuleDescriptor]，RT2 P1-3）：**先存在性
 *   后版本**——目标在组合里但 version 不在区间 / 为空 / 不可解析 ⇒ 同点同族
 *   [LinkOrderException] 整组拒绝并点名实际串（消息四元组：依赖方包 / 目标包 /
 *   声明区间 / 实际串）；
 * - [ModuleDescriptor.loadAfter] 是**软顺序提示**：目标在组合里就排在它后面，
 *   不在就忽略（允许「有 A 时靠后、没 A 时照常」的组合写法），且**不参与版本约束**
 *   （软序语义不动）；
 * - **规范序**：输入 List 的枚举顺序不参与链接语义。多个模块同时就绪时，优先遵循
 *   [LinkOrder.preferredPackageIds] 的显式排序位置，未指定者按 `packageId` 升序兜底。
 *   这是 **Kahn 就绪队列优先级**（语义冻结见 [LinkOrder]），不是对完整偏好排列的最小调整。
 *   同一模块集合 + 同一声明 + 同一解析政策 + 同一显式排序配置 ⇒ 同一条链接序，
 *   与输入排列无关（ABI-8 缓存指纹的确定性前提）；
 * - 依赖环（requires 或 loadAfter 构成）整组拒绝，报告环上剩余的包名。
 *
 * 链接序是有语义的量：它喂给 LEGACY last-wins 胜者、superChain 链序、CSV 同格值补丁
 * 叠加、hook 注册序与 ABI-8 指纹。**文件扫描顺序、目录枚举顺序、文件名与输入 List
 * 顺序都不决定模块优先级**（架构 §1039/§1142）；文件名只负责可重复地发现文件。
 *
 * 返回的是**整包描述符的链接序**（base 不在其中；base 恒排第 0，见 §1）。
 * 本类不校验函数级依赖（externalCalls 的解析要等 LSC 胜者表），也不读字节。
 */
object ModuleLinkOrder {

    /** 缺省配置：无显式排序意图，全部走 `packageId` 规范序。 */
    fun plan(packages: List<ModuleDescriptor>): List<ModuleDescriptor> = plan(packages, LinkOrder())

    /**
     * 按 requires/loadAfter 求规范拓扑序；[linkOrder] 是唯一的显式排序输入。
     *
     * 就绪集合按「显式排序位置 → `packageId`」出队（Kahn 就绪优先级，语义见 [LinkOrder]）；
     * 输入下标、文件名与目录枚举顺序全程不参与比较。依赖边通过 indegree 让模块不就绪，
     * 排序偏好不会绕过依赖，也不会制造环。
     *
     * @throws LinkOrderException 缺硬依赖、版本越界 / 被依赖方版本为空或不可解析、
     *   包重复、显式排序配置重复、依赖环。
     */
    fun plan(packages: List<ModuleDescriptor>, linkOrder: LinkOrder): List<ModuleDescriptor> {
        // 先校验配置：即便空组合也不接受含重复 ID 的排序配置（fail-closed，不静默去重）。
        val ranks = preferredRanks(linkOrder)
        if (packages.isEmpty()) return emptyList()
        val indexById = indexByIdOf(packages)
        val graph = buildGraph(packages, indexById)
        return topoOrder(packages, ranks, graph)
    }

    private fun preferredRanks(linkOrder: LinkOrder): Map<String, Int> {
        val ranks = HashMap<String, Int>(linkOrder.preferredPackageIds.size * 2)
        for ((rank, id) in linkOrder.preferredPackageIds.withIndex()) {
            val previous = ranks.put(id, rank)
            if (previous != null) {
                throw LinkOrderException(
                    "显式排序配置里 packageId 重复：[$id]（位置 $previous 与 $rank）",
                )
            }
        }
        return ranks
    }

    private fun indexByIdOf(packages: List<ModuleDescriptor>): Map<String, Int> {
        val indexById = HashMap<String, Int>(packages.size * 2)
        for ((i, p) in packages.withIndex()) {
            val existing = indexById.put(p.packageId, i)
            if (existing != null) {
                throw LinkOrderException(
                    "链接组合里 packageId 重复：${p.packageId}（下标 $existing 与 $i）",
                    packageIds = listOf(p.packageId),
                )
            }
        }
        return indexById
    }

    /** 依赖图邻接表；[indegree] 会被 [topoOrder] 原地消费。 */
    private class Graph(
        val indegree: IntArray,
        val dependents: Array<ArrayList<Int>>,
    )

    private fun buildGraph(
        packages: List<ModuleDescriptor>,
        indexById: Map<String, Int>,
    ): Graph {
        val indegree = IntArray(packages.size)
        val dependents = Array(packages.size) { ArrayList<Int>(2) }
        val edgeSeen = HashSet<Long>()

        fun addEdge(from: Int, to: Int) {
            val edge = (from.toLong() shl 32) or to.toLong()
            if (edgeSeen.add(edge)) {
                dependents[from].add(to)
                indegree[to]++
            }
        }

        for ((i, p) in packages.withIndex()) {
            for (entry in p.requires) {
                // RT2 P1-3（B 案）：约束内嵌字符串 pkg@[min,max]。条目语法已在
                // ModuleDescriptor 构造期 fail-closed，这里对同一串重放解析必成功；
                // 先存在性（缺包按包名报）后版本（[checkRequiresVersion]）。
                val constraint = ModuleDescriptor.parseRequiresConstraint(entry)
                val j = indexById[constraint.packageId]
                    ?: throw LinkOrderException(
                        "包 [${p.packageId}] 缺少硬依赖 [${constraint.packageId}]（组合里没有这个包，拒绝强跑）",
                        // 归因只给声明方：被缺的目标不在组合里（盘上可能根本没有），写它的
                        // canonical 行等于替一个不存在的包报「装载失败」。
                        packageIds = listOf(p.packageId),
                    )
                requireNotSelf(p.packageId, j, i, "requires")
                checkRequiresVersion(p, constraint, packages[j])
                addEdge(j, i)
            }
            for (after in p.loadAfter) {
                val j = indexById[after] ?: continue
                requireNotSelf(p.packageId, j, i, "loadAfter")
                addEdge(j, i)
            }
        }
        return Graph(indegree, dependents)
    }

    /**
     * 版本裁决（RT2 P1-3，存在性通过之后、入边之前）：裸包名（无约束）直接放行；
     * 被依赖方 version 为空串 / 不可解析 ⇒ 拒绝并四元组点名实际串；version 低于 min
     * 或高于 max（双闭区间）⇒ 同族 [LinkOrderException] 整组拒绝。区间回显用
     * [RequiresConstraint] 段值 joinToString（与声明原文逐字节互逆，见其 KDoc）。
     */
    private fun checkRequiresVersion(
        dependent: ModuleDescriptor,
        constraint: RequiresConstraint,
        target: ModuleDescriptor,
    ) {
        val min = constraint.minSegments ?: return
        val max = requireNotNull(constraint.maxSegments) { "双闭区间两端点成对（解析保证）" }
        val actualRaw = target.version
        val actual = ModuleDescriptor.parseVersionSegments(actualRaw)
        val interval = "[${min.joinToString(".")},${max.joinToString(".")}]"
        val verdict = when {
            actual == null -> "被依赖方版本为空或不可解析"
            versionCompare(actual, min) < 0 || versionCompare(actual, max) > 0 -> "版本越界"
            else -> return
        }
        throw LinkOrderException(
            "包 [${dependent.packageId}] 硬依赖 [${target.packageId}] 版本区间 $interval，" +
                "实际 [$actualRaw] —— $verdict，拒绝强跑",
            // 版本区间不匹配是**双方组合**的问题：两边都在组合里、都因整链拒载装不上，
            // 各自一条 canonical 行，玩家在设置页两个包下都能看到同一条四元组归因。
            packageIds = listOf(dependent.packageId, target.packageId),
        )
    }

    /** 点分段全数值比较（缺段补 0：1.2 == 1.2.0、1.2 < 1.10）；语义权威在 [ModuleDescriptor.compareVersionSegments]。 */
    private fun versionCompare(a: List<Long>, b: List<Long>): Int =
        ModuleDescriptor.compareVersionSegments(a, b)

    private fun requireNotSelf(id: String, from: Int, to: Int, kind: String) {
        if (from == to) {
            throw LinkOrderException("包 [$id] 的 $kind 指向自身（依赖环）", packageIds = listOf(id))
        }
    }

    private fun topoOrder(
        packages: List<ModuleDescriptor>,
        ranks: Map<String, Int>,
        graph: Graph,
    ): List<ModuleDescriptor> {
        // 语义冻结：ready 是「当前就绪模块」的优先级队列，不是对完整偏好列表做最小调整。
        // 键 = (显式排序位置, packageId)；输入下标不参与比较；依赖边靠 indegree 保证优先。
        val ready = PriorityQueue<Int>(
            compareBy<Int> { ranks[packages[it].packageId] ?: Int.MAX_VALUE }
                .thenBy { packages[it].packageId },
        )
        for (i in graph.indegree.indices) if (graph.indegree[i] == 0) ready.add(i)
        val out = ArrayList<ModuleDescriptor>(packages.size)
        while (ready.isNotEmpty()) {
            val i = ready.poll()
            out += packages[i]
            for (dep in graph.dependents[i]) {
                if (--graph.indegree[dep] == 0) ready.add(dep)
            }
        }
        if (out.size != packages.size) {
            val blocked = packages.indices
                .filter { graph.indegree[it] > 0 }
                .map { packages[it].packageId }
            // 环上每个包都在组合里、都装不上 ⇒ 逐包可归因。
            throw LinkOrderException("模块依赖环：${blocked.joinToString(", ")}", packageIds = blocked)
        }
        return out
    }
}

/**
 * 链接组合无法定序（缺硬依赖 / 重复包 / 显式排序配置重复 / 依赖环）——整组拒绝，不猜、不强跑。
 *
 * @property packageIds 本次拒载**可归因的盘上包名**（构造期由各抛出点按已知组合填充，只读）。
 *   空列表 = 无可靠包级归因（例如「显式排序配置内重复」是玩家配置面错误、不指向组合里任何
 *   已装载包），调用方据此改走不可归因留痕出口。本属性只供留痕归因，不参与定序裁决。
 */
class LinkOrderException(
    message: String,
    val packageIds: List<String> = emptyList(),
) : RuntimeException(message)
