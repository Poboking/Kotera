package com.eratw.port.core.patch

import com.eratw.port.core.code.SymbolCatalog
import java.util.Locale

/**
 * 外部导入解析表（M2-1 Linker 的核心裁决面之一，ABI-4/7）。
 *
 * Module 容器里 `_CALL.a < 0` 是**外部导入哨兵**：`extIdx = -(a + 1)` 指向本 Package
 * [ModuleDescriptor.externalCalls] 的**列表下标**；链接期把每个导入回填成 LSC 胜者的
 * **物理下标**后，运行期再也看不到负哨兵（[CallSentinels]）。
 *
 * ## 为什么在“全部模块的 LSC 建好之后”解析
 *
 * 导入绑定的是**最终胜者**（受 dispatch 影响），不是“链接序在前的那个定义”。两阶段：
 * ① `ModuleLinkOrder.plan` 定序 → ② 编译产物拼成物理函数表 → ③ `SymbolCatalog.build`
 * 得 LSC 胜者 → ④ 本类按 LSC 解析导入。与依赖顺序无关的绑定让「A/B/C 任意组合免重编」
 * 在函数级也成立；`requires` 只约束声明/数据依赖，不约束函数提供者。
 *
 * ## fail-closed 判据（任一条即整组拒绝）
 *
 * - 导入名在该 Package 的 `externalCalls` 里重复（忽略大小写）——一个名字两个哨兵槽，
 *   回填哪个都是猜；
 * - 导入名为空白；
 * - 同一 packageId 在组合里出现两次（先于 [ModuleLinkOrder.plan] 就该拒，这里再兜一次）。
 *
 * ## 未解析导入（2026-11-24 S9 改判，CONTRACT-S9-MISSING-CALL.md L1）
 *
 * 导入名查不到**非事件**胜者（缺提供者 / 名字拼错 / base 月更删函数）或目标是**事件名**
 * ⇒ **不再整组拒绝**：记入 [PackageImports.unresolved] + diagnostics 告警，链接器把死
 * `_CALL` 改写为「缺名残桩」（运行期按名解析：非 TRY = 原版 `NotDefinedFunc` 原文；
 * 事件名 = `CallToEventFunc` / `CompatiCallEvent=YES` 时真调通首成员——
 * spec: CalledFunction.cs:98-109、FunctionIdentifier.cs:3577-3588）。原版装载
 * **不查 CALL 目标名**，整组拒 = 「别家能导我们不能导」的第三大来源。
 * 维持拒绝的只剩包形状病态（空白 / 重复名 / packageId 重复）。
 *
 * 解析不做 trim / 大小写改写以外的事：键按 `uppercase(Locale.ROOT)` 归一（与
 * `FuncMeta.key` / `Config.StrComper` 同口径），**原文保留**用于诊断与回填日志。
 */
class ExternalImportTable private constructor(
    private val byPackage: Map<String, PackageImports>,
) {
    /** 一个 Package 的外部导入序（= `externalCalls` 列表序，即哨兵下标序）。 */
    class PackageImports internal constructor(
        val packageId: String,
        /** 按 `externalCalls` 列表序；`importIndex` 就是哨兵解码出的 extIdx（已解析段）。 */
        val imports: List<ResolvedImport>,
        /**
         * 未解析段（2026-11-24 S9 改判）：查不到非事件胜者 / 事件名的导入，链接器
         * 据此合成缺名残桩；运行期不可见（同 [imports]）。
         */
        val unresolved: List<UnresolvedImport>,
    ) {
        private val byKey: Map<String, ResolvedImport> =
            imports.associateBy { it.key }

        /** 原文名（忽略大小写）→ 解析结果；未声明导入 = null。 */
        fun importOf(name: String): ResolvedImport? = byKey[name.uppercase(Locale.ROOT)]

        /** 链接器回填用：extIdx → 物理下标（按哨兵序）。 */
        fun physicalIndices(): IntArray = IntArray(imports.size) { imports[it].physicalIndex }

        /**
         * 哨兵解码 extIdx → 任一表条目（[CallRelocation] 用）：已解析段在前、未解析段
         * 在后，两段合起来覆盖 `externalCalls` 全部槽位；越域 = null（病态产物）。
         */
        internal fun entryAt(extIdx: Int): ImportEntry? {
            if (extIdx in imports.indices) return imports[extIdx]
            return unresolved.firstOrNull { it.importIndex == extIdx }
        }
    }

    /** 导入表条目共形（[ResolvedImport] / [UnresolvedImport]）：哨兵槽定位与诊断用。 */
    interface ImportEntry {
        /** 原文名（保留书写形）。 */
        val name: String

        /** 大写归一键。 */
        val key: String

        /** 哨兵槽位（= `externalCalls` 列表下标）。 */
        val importIndex: Int
    }

    /** 一条已解析导入：原文名、归一键、哨兵槽位、LSC 胜者物理下标。 */
    data class ResolvedImport(
        override val name: String,
        override val key: String,
        override val importIndex: Int,
        val physicalIndex: Int,
    ) : ImportEntry

    /**
     * 一条未解析导入（S9 改判 L1）：链接期查不到非事件胜者。`reason` = 诊断原文
     * （缺提供者 / 事件名两族），随 diagnostics 告警上导入告警面。
     */
    data class UnresolvedImport(
        override val name: String,
        override val key: String,
        override val importIndex: Int,
        val reason: String,
    ) : ImportEntry

    val packageIds: Set<String> get() = byPackage.keys

    fun importsOf(packageId: String): PackageImports? = byPackage[packageId]

    /** 原文名（忽略大小写）→ 胜者物理下标；未声明导入或未知包 = null。 */
    fun physicalIndexOf(packageId: String, name: String): Int? =
        byPackage[packageId]?.importOf(name)?.physicalIndex

    /** 原文名（忽略大小写）→ 哨兵槽位（= `externalCalls` 列表下标）；未声明 = null。 */
    fun importIndexOf(packageId: String, name: String): Int? =
        byPackage[packageId]?.importOf(name)?.importIndex

    companion object {
        /**
         * 按 [packages]（**链接序**，[ModuleLinkOrder.plan] 的产物）与最终 LSC 解析全部导入。
         *
         * @throws ImportResolutionException 导入表形状病态（空白 / 重复名 / packageId
         *   重复）——缺提供者 / 事件名**不再拒绝**（S9 改判：记 unresolved + 告警）。
         */
        fun resolve(
            packages: List<ModuleDescriptor>,
            catalog: SymbolCatalog,
            diagnostics: (String) -> Unit = {},
        ): ExternalImportTable {
            val byPackage = LinkedHashMap<String, PackageImports>(packages.size * 2)
            for (descriptor in packages) {
                if (byPackage.containsKey(descriptor.packageId)) {
                    throw ImportResolutionException(
                        descriptor.packageId,
                        "",
                        "packageId 在链接组合里重复（导入表无法归属）",
                    )
                }
                val imports = ArrayList<ResolvedImport>(descriptor.externalCalls.size)
                val unresolved = ArrayList<UnresolvedImport>(0)
                val seenKeys = HashSet<String>(descriptor.externalCalls.size * 2)
                for ((index, raw) in descriptor.externalCalls.withIndex()) {
                    if (raw.isBlank()) {
                        throw ImportResolutionException(
                            descriptor.packageId,
                            raw,
                            "externalCalls 第 $index 项为空",
                        )
                    }
                    val key = raw.uppercase(Locale.ROOT)
                    if (!seenKeys.add(key)) {
                        throw ImportResolutionException(
                            descriptor.packageId,
                            raw,
                            "externalCalls 里重复导入同一个名字（忽略大小写键 $key）",
                        )
                    }
                    val physical = catalog.winnerIndexOf(key)
                    if (physical < 0) {
                        val reason = if (catalog.eventMembersOf(key).isNotEmpty()) {
                            "目标是事件（事件不参与覆盖、不进胜者表）——链接为缺名残桩，" +
                                "运行期按 CompatiCallEvent 配置解析（YES = 真调事件成员）"
                        } else {
                            "组合里没有任何非事件函数提供这个名字（缺依赖或名字拼错）——" +
                                "链接为缺名残桩，执行到调用点才报错（原版 NotDefinedFunc 语义）"
                        }
                        unresolved += UnresolvedImport(raw, key, index, reason)
                        diagnostics("[${descriptor.packageId}] 外部导入 $raw 无法解析：$reason")
                        continue
                    }
                    imports += ResolvedImport(raw, key, index, physical)
                }
                byPackage[descriptor.packageId] =
                    PackageImports(descriptor.packageId, imports, unresolved)
            }
            return ExternalImportTable(byPackage)
        }
    }
}

/** 外部导入表形状病态（空白 / 重复名 / packageId 重复）——整组拒绝，不猜。 */
class ImportResolutionException(
    val packageId: String,
    val importedName: String,
    val reason: String,
) : RuntimeException(
    if (importedName.isEmpty()) {
        "外部导入无法解析：[$packageId] —— $reason"
    } else {
        "外部导入无法解析：[$packageId] $importedName —— $reason"
    },
)
