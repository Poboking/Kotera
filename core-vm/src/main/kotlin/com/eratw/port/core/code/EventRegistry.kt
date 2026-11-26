package com.eratw.port.core.code

import com.eratw.port.core.proc.FuncMeta
import java.util.Locale

/**
 * 事件注册表（M3-1；从 LSC 的 [SymbolCatalog.events] 一对多视图**派生**，不另存
 * 第二份事件列表 —— era-arch-v61u.md §6/GPT 三审 P0-1）。
 *
 * 四桶执行序（spec: LabelDictionary.cs:90-105 分桶 + CalledFunction.cs:228-243
 * ShiftNext 逐桶推进）：
 *
 * ```
 * ONLY(0) → PRI(1) → NORMAL(2) → LATER(3)，同桶内按链接序（= defIndex 序）。
 * ```
 *
 * - **单一真源**：成员集合与顺序只由 [SymbolCatalog.events]（链接序全成员）与
 *   [FuncMeta.eventBuckets]（桶集合）派生 —— base-only 装载路径的
 *   [com.eratw.port.core.proc.FunctionTable.eventDispatchOrder] 是同一对
 *   （桶集合，defIndex 序）语义的另一视图，两者对同一批元数据必逐行同序（测试对拍钉）；
 * - **事件不参与覆盖**：同名事件的全部物理定义按链接序保留（events 一对多基数，
 *   ABI-7 Gate 的「事件一对多基数证明」落点）——base 与多 Mod 各自的同名事件成员
 *   全部进桶，先 base 装载序、后各包链接序；
 * - **SINGLE 终止旗标**（spec: ProcessState.cs:306-310：返回值 1 时跳过同桶剩余成员）
 *   是运行期执行语义：本表随成员携带 [Member.isSingle]，执行器读到旗标成员返回 1
 *   即截断本桶余下成员（后续桶不受影响 —— C# 的 ShiftNext 按**桶**推进，截断不跨桶）；
 * - **并列加桶（2026-11-26 结案，在册 B23）**：C# 对同一 label 是四个**平行** if
 *   （`if (IsOnly) [0].Add; if (IsPri) [1].Add; if (IsLater) [3].Add;
 *   if (!IsPri && !IsLater) [2].Add`，LabelDictionary.cs:134-141 / :228-235；:233 原注
 *   「#PRIかつ#LATERなら二重に登録する。eramakerの仕様」），`IsPri && IsLater` 的成员落
 *   两桶、被派发两次。本表按 [FuncMeta.eventBuckets] 把同一成员展开成多条 `Member(bucket=…)`
 *   与之同构（PC 侧派发序实证见 [docs/UPSTREAM-BUG-PR.md] 的 B23 行）。`#ONLY` 落桶 0 与桶 2，
 *   桶 2 的那份因 `ProcessState.cs:312-347`（IsOnly → FinishEvent 终止整链）不可达，
 *   与 C# 同形，派发读数不受影响。
 *
 * 构建期不可变；运行期只读。
 */
class EventRegistry private constructor(
    private val byKey: Map<String, List<Member>>,
) {

    /**
     * 一个事件成员：物理下标 + 桶位 + ONLY / SINGLE 旗标 + 归属包（诊断）。
     *
     * [isOnly] 与 [bucket] 同源派生自 [FuncMeta.isOnly]（`eventBuckets` 里的 ONLY=0），
     * 但**不**让运行期拿 `bucket == 0` 反推旗标：派发所需的语义全部随成员带出，
     * 四桶表本身就是可直接执行的真源（M2-6），不依赖第二张元数据表。
     */
    class Member(
        val physicalIndex: Int,
        val bucket: Int,
        val isOnly: Boolean,
        val isSingle: Boolean,
        val ownerPackageId: String,
    )

    /** 事件键集合（大写归一，口径同 [FuncMeta.key]）。 */
    val eventKeys: Set<String> get() = byKey.keys

    /**
     * 事件名的执行序成员（四桶序，同桶链接序）；非事件名或未知名返回空表。
     * 双旗标成员（`#PRI`+`#LATER`、`#ONLY`）在此出现两次，各带自己的桶位。
     * 返回列表不可变。
     */
    fun membersOf(key: String): List<Member> =
        byKey[key.uppercase(Locale.ROOT)] ?: emptyList()

    /** 该事件的成员里是否有 SINGLE 旗标（HAS_SINGLE 类查询面；未知名 false）。 */
    fun hasSingle(key: String): Boolean {
        for (m in membersOf(key)) if (m.isSingle) return true
        return false
    }

    companion object {
        /**
         * 从链接产物的 LSC 派生（M2-1 [com.eratw.port.core.patch.ModuleLinker] 的
         * [com.eratw.port.core.patch.LinkedProgram.catalog] 直接可喂）。
         *
         * @throws IllegalArgumentException events 里的物理下标在候选集中不存在
         *   （LSC 与候选表脱节 —— 装配错误，拒绝）。
         */
        fun of(catalog: SymbolCatalog): EventRegistry {
            val metaByIndex = HashMap<Int, FuncMeta>(catalog.candidates.size * 2)
            for (c in catalog.candidates) {
                val prev = metaByIndex.put(c.meta.index, c.meta)
                require(prev == null) { "候选物理下标重复: ${c.meta.index}（LSC 候选表损坏）" }
            }
            val out = LinkedHashMap<String, List<Member>>(catalog.events.size * 2)
            for ((key, bindings) in catalog.events) {
                require(bindings.isNotEmpty()) { "事件 $key 的成员表为空（LSC 事件视图损坏）" }
                // 一个成员按其桶集合展开成多条（C# 并列加桶），容量按最多双桶给。
                val members = ArrayList<Member>(bindings.size * 2)
                for (b in bindings) {
                    val meta = metaByIndex[b.physicalIndex]
                    require(meta != null) {
                        "事件 $key 的成员物理下标 ${b.physicalIndex} 不在候选集里（LSC 与候选表脱节）"
                    }
                    require(meta.isEvent) {
                        "事件 $key 的成员 ${meta.key} 不是事件函数（LSC 事件视图污染）"
                    }
                    for (bucket in meta.eventBuckets) {
                        members += Member(
                            physicalIndex = b.physicalIndex,
                            bucket = bucket,
                            isOnly = meta.isOnly,
                            isSingle = meta.isSingle,
                            ownerPackageId = b.ownerPackageId,
                        )
                    }
                }
                // 四桶序：稳定排序保持链接序（成员已按链接序进入；同一成员的多条展开
                // 来自同一个桶集合，桶内相对次序仍由链接序决定，与 Map 迭代序无关）。
                members.sortBy { it.bucket }
                out[key] = members.toList()
            }
            return EventRegistry(out)
        }
    }
}
