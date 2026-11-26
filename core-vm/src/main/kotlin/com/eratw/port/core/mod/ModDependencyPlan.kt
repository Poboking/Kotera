package com.eratw.port.core.mod

import com.eratw.port.modapi.EraMod
import java.util.PriorityQueue

/**
 * mod 依赖声明的装载规划（Lane AS）：稳定拓扑排序 + 缺依赖/成环/依赖未启用时的 fail-closed 拒载。
 *
 * ## 为什么单独成文件
 *
 * [ModRuntimeHost] 的类内函数数与文件级函数数都已压在 detekt `TooManyFunctions` 阈值上沿（各 10 个），
 * `install` 本体贴着 `LongMethod` 上沿；规划这类**无第二份状态**的纯裁决挪出来才不撑爆，宿主侧只留
 * 「取规划 → 依赖运行时复查 → 追加拒载项」三行接线。
 *
 * ## 边界
 *
 * 本文件只做**装载顺序与拒载裁决**（外加把拒载项的 storage 段按版本门同口径 deactivate），不碰
 * 注册项/生命周期（那些仍归宿主两段既有围栏）。语义面：依赖 = 同一批装载的**顺序约束 + 存在性**，
 * 不是版本约束（版本见 [EraMod.apiVersion] 与 [com.eratw.port.modapi.ModApi.VERSION] 的装载期比较）。
 */

/** mod 声明的依赖不在本次装载集合里（未安装 / 已停用 `.off` / 未成功启用）：拒载本 mod，其余照常。 */
class ModDependencyMissingException(
    packageId: String,
    missingDependency: String,
) : IllegalStateException(
    "mod $packageId 缺少依赖 $missingDependency（不在本次装载集合、已停用或未成功启用）",
)

/** mod 依赖成环（含指向自己）：环上成员**全部**拒载，不做部分装载；消息点名环成员。 */
class ModDependencyCycleException(
    packageId: String,
    cycle: List<String>,
) : IllegalStateException(
    "mod $packageId 依赖成环，本 mod 不装载：" + cycle.joinToString(" -> "),
)

/**
 * 依赖的 mod 没成功启用（版本门拒载 / onLoad / onEnable 抛错 / 构造段失败）：依赖者一并拒载。
 *
 * `cause` 是依赖自身的失败原因（依赖根本没建出 entry 时为 null）；拼进消息便于一眼看到根因。
 */
class ModDependencyUnavailableException(
    packageId: String,
    dependency: String,
    cause: Throwable?,
) : IllegalStateException(
    "mod $packageId 依赖的 $dependency 未成功启用" +
        (cause?.let { "（根因 ${it::class.java.simpleName}: ${it.message}）" } ?: ""),
    cause,
)

/**
 * `install` 的一次性装载规划：[ordered] = 稳定拓扑序（依赖先于依赖者），[rejected] = 规划期已判定
 * 拒载的报告项（按载体声明序，与宿主报告同形，直接并进 `report`）。
 *
 * [requiresByPackage] 只用于按序装载时的**运行时**复查：版本门 / onLoad / onEnable 的失败发生在规划
 * 之后，规划看不到，只能在装载到依赖者时拿实际 `Entry` 状态兜（见 [dependencyFailure]）。
 */
internal class ModInstallPlan(
    val ordered: List<Pair<String, EraMod>>,
    val rejected: List<ModRuntimeState>,
    private val requiresByPackage: Map<String, List<String>>,
) {
    /**
     * 依赖者装载前的运行时复查：返回 null = 全部依赖已启用（`enabled && !shutDown`）；否则返回带
     * **首个**未满足依赖名的失败原因。
     *
     * 只查 [ordered] 内的 mod：规划期就能判定的缺失/成环已进 [rejected]，其依赖者也已按传递闭包
     * 一起拒载 —— 走到这里说明依赖在拓扑序里已经装过，`installed[dep]` 缺失或未启用就是它自己失败。
     */
    fun dependencyFailure(
        packageId: String,
        installed: Map<String, ModRuntimeHost.Entry>,
    ): Throwable? {
        for (dependency in requiresByPackage[packageId].orEmpty()) {
            val entry = installed[dependency]
            if (entry != null && entry.enabled && !entry.shutDown) continue
            return ModDependencyUnavailableException(packageId, dependency, entry?.failure)
        }
        return null
    }
}

/**
 * 由载体声明表算一次装载规划。
 *
 * 步骤（全 fail-closed，不部分装载）：
 *
 * 1. 批内重复 packageId = registry 编程错误：**不重排、不提前拒载**，原样返回，让 `install` 循环里
 *    既有 `require` 在「第一项已装完」时硬失败（既有回归面就这么钉的）。
 * 2. 缺依赖：requires 里既不在本批、也不是已启用 entry ⇒ 本 mod 拒载（点名首个缺失 packageId）。
 * 3. 成环：只在「依赖也在本批且未被第 2 步拒」的边上找强连通分量；SCC 大小 > 1 或自环 ⇒ 成员全拒。
 * 4. 传递闭包：依赖落在拒载集合里的 mod 一并拒载（含链式传递）。
 * 5. 剩余节点 Kahn 稳定拓扑：每步取**载体声明序最靠前**的入度 0 节点 ⇒ 无依赖时输出与输入逐位一致，
 *    同层保持声明序。
 *
 * 规划期拒载的包在这里就 `storage.deactivate`（与版本门拒载同口径：数据段留下、本会话不启用）；
 * 已在本批重复装载的 packageId 仍交由宿主循环里的 `require` 报同一个错。
 */
internal fun planModInstall(
    mods: List<Pair<String, EraMod>>,
    installed: Map<String, ModRuntimeHost.Entry>,
    storage: ModStorageStore,
): ModInstallPlan {
    val ids = mods.map { it.first }
    // 1. 批内重复：保留 legacy 行为（宿主循环里的 require 在装到第二项时才 abort）。
    if (ids.size != ids.toSet().size) return ModInstallPlan(mods, emptyList(), emptyMap())
    val requiresByPackage = mods.associate { (packageId, mod) -> packageId to mod.requires }
    val batchIds = ids.toSet()
    val rejections = linkedMapOf<String, Throwable>()
    // 2. 缺依赖：本批之外也没有「已启用且未关闭」的 entry。
    for ((packageId, mod) in mods) {
        val missing = mod.requires.firstOrNull { dependency ->
            dependency !in batchIds && installed[dependency]?.let { it.enabled && !it.shutDown } != true
        }
        if (missing != null) rejections[packageId] = ModDependencyMissingException(packageId, missing)
    }
    // 3. 成环：只在「依赖也在这批且未被缺依赖拒掉」的边上找（缺依赖的 mod 已判死，不参与环裁决）。
    rejectCycles(mods, batchIds - rejections.keys, requiresByPackage, rejections)
    // 4. 传递闭包：依赖被拒 ⇒ 依赖者一并拒载（环外但依赖环成员/缺依赖者的 mod 在这里落网）。
    rejectDependentsOfRejected(mods, rejections)
    // 5. 剩余节点稳定拓扑排序。
    val accepted = mods.filter { (packageId) -> packageId !in rejections }
    val ordered = stableTopologicalOrder(accepted, requiresByPackage)
    check(ordered.size == accepted.size) {
        "依赖规划丢失节点：accepted=${accepted.size} ordered=${ordered.size}（拓扑排序不应有环）"
    }
    val rejected = mods.filter { (packageId) -> packageId in rejections }.map { (packageId) ->
        storage.deactivate(packageId)
        ModRuntimeState(packageId, active = false, failure = rejections.getValue(packageId))
    }
    return ModInstallPlan(ordered, rejected, requiresByPackage)
}

/**
 * Tarjan 强连通分量：SCC 大小 > 1 或自环 ⇒ 该分量就是环，成员全部拒载，一条消息点名环成员
 * （按载体声明序，可复现、可对拍）。只有 `eligible` 内的节点与边参与。
 */
private fun rejectCycles(
    mods: List<Pair<String, EraMod>>,
    eligible: Set<String>,
    requiresByPackage: Map<String, List<String>>,
    rejections: MutableMap<String, Throwable>,
) {
    val declarationOrder = mods.map { it.first }
    val graph = eligible.associateWith { packageId ->
        requiresByPackage.getValue(packageId).filter { it in eligible }.distinct()
    }
    for (component in stronglyConnectedComponents(eligible, graph)) {
        val selfLoop = component.singleOrNull()?.let { it in graph.getValue(it) } == true
        if (component.size <= 1 && !selfLoop) continue
        val members = declarationOrder.filter { it in component }
        val cycle = members + members.first()
        for (packageId in members) rejections[packageId] = ModDependencyCycleException(packageId, cycle)
    }
}

/**
 * 传递闭包：只要某个 requires 落在拒载集合，本 mod 一并拒载（fixpoint 迭代而不是递归 —— 环已在
 * [rejectCycles] 剔掉，本步只会越加越多、必然收敛）。
 */
private fun rejectDependentsOfRejected(
    mods: List<Pair<String, EraMod>>,
    rejections: MutableMap<String, Throwable>,
) {
    var changed = true
    while (changed) {
        // 每轮按上一轮结束时的拒载集合算一遍「新落网者」，统一入账后再迭代：避免边扫边改，
        // 也让每轮的判定与集合快照一致（fixpoint 收敛性不依赖遍历顺序）。
        val newlyRejected = mods.mapNotNull { (packageId, mod) ->
            if (packageId in rejections) {
                null
            } else {
                mod.requires.firstOrNull { it in rejections }?.let { packageId to it }
            }
        }
        changed = newlyRejected.isNotEmpty()
        for ((packageId, rejectedDependency) in newlyRejected) {
            rejections[packageId] = ModDependencyUnavailableException(
                packageId,
                rejectedDependency,
                rejections[rejectedDependency],
            )
        }
    }
}

/**
 * Kahn 稳定拓扑排序：每一步从入度 0 的节点里取**载体声明序最靠前**的一个（优先队列按声明下标比较），
 * 因此无依赖时输出与输入逐位一致，有依赖时同层保持声明序。调用前环已剔除，结果必然覆盖全部节点。
 */
private fun stableTopologicalOrder(
    accepted: List<Pair<String, EraMod>>,
    requiresByPackage: Map<String, List<String>>,
): List<Pair<String, EraMod>> {
    val byPackage = accepted.associate { it.first to it }
    val declarationIndex = accepted.mapIndexed { index, (packageId, _) -> packageId to index }.toMap()
    val pendingDependencies = HashMap<String, Int>()
    val dependents = HashMap<String, MutableList<String>>()
    for ((packageId, _) in accepted) {
        val dependencies = requiresByPackage.getValue(packageId).filter { it in byPackage && it != packageId }
        pendingDependencies[packageId] = dependencies.distinct().size
        for (dependency in dependencies) dependents.getOrPut(dependency) { mutableListOf() } += packageId
    }
    val ready = PriorityQueue<String>(compareBy { declarationIndex.getValue(it) })
    for ((packageId, _) in accepted) if (pendingDependencies.getValue(packageId) == 0) ready += packageId
    val ordered = ArrayList<Pair<String, EraMod>>(accepted.size)
    while (ready.isNotEmpty()) {
        val packageId = ready.poll()
        ordered += byPackage.getValue(packageId)
        for (dependent in dependents[packageId].orEmpty()) {
            val remaining = pendingDependencies.getValue(dependent) - 1
            pendingDependencies[dependent] = remaining
            if (remaining == 0) ready += dependent
        }
    }
    return ordered
}

/** Tarjan 强连通分量（递归实现；mod 数是个位数，栈深不构成风险）。 */
private fun stronglyConnectedComponents(
    nodes: Set<String>,
    edges: Map<String, List<String>>,
): List<List<String>> {
    val indexOf = HashMap<String, Int>()
    val lowLink = HashMap<String, Int>()
    val onStack = HashSet<String>()
    val stack = ArrayList<String>()
    val components = ArrayList<List<String>>()
    var nextIndex = 0
    fun visit(node: String) {
        indexOf[node] = nextIndex
        lowLink[node] = nextIndex
        nextIndex++
        stack += node
        onStack += node
        for (next in edges.getValue(node)) {
            if (next !in indexOf) {
                visit(next)
                lowLink[node] = minOf(lowLink.getValue(node), lowLink.getValue(next))
            } else if (next in onStack) {
                lowLink[node] = minOf(lowLink.getValue(node), indexOf.getValue(next))
            }
        }
        if (lowLink.getValue(node) != indexOf.getValue(node)) return
        val component = ArrayList<String>()
        while (true) {
            val member = stack.removeAt(stack.lastIndex)
            onStack -= member
            component += member
            if (member == node) break
        }
        components += component
    }
    for (node in nodes) if (node !in indexOf) visit(node)
    return components
}
