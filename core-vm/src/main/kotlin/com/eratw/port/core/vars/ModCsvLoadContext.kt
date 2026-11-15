package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestLoader
import com.eratw.port.core.patch.ManifestSource

/**
 * 装载相位产出的 Mod CSV 注入上下文（CONTRACT-RT2-M14B-IMPL §1 裁定 1/3）。
 *
 * **单源**：整趟装载只允许一次 `ManifestLoader.load` —— 其产物（[snapshot]）与据此派生的
 * [plan]、以及喂给同一次装载的 [csvTableCapacities] / [baseCsvKeywords] 一并装进本对象，
 * 挂 `store.aux`；`ModRuntimeAssembly.attach` **只读**本对象，不再自己装载（两份会漂移）。
 *
 * @property plan 注入位计划（只含本波支持的 `C_INT1` / `C_STR1` 变量；`C_INT2` 与系统变量已排除并警告）
 * @property snapshot 那唯一一次 `ManifestLoader.load` 的冻结快照（Mod 链与宿主共用同一份）
 * @property csvTableCapacities 表名 → 容量上界（**按需推导**：`max(Base, 最大显式下标 + 1)`，见 [ModCsvPlanLoader]）
 * @property baseCsvKeywords 表名 → Base 关键字（扩容**前**的快照；补丁面判「撞 Base 名」用）
 * @property warnings 装载相位逐条排除/告警（原样进宿主日志面，不静默）
 */
class ModCsvLoadContext(
    val plan: ModCsvInjectionPlan,
    val snapshot: DeclarationSnapshot,
    val csvTableCapacities: Map<String, Int>,
    val baseCsvKeywords: Map<String, Map<String, Int>>,
    val warnings: List<String>,
) {

    val isEmpty: Boolean get() = plan.isEmpty

    companion object {

        /** aux 槽名（能力家族前缀 + 小写，约定见 [com.eratw.port.core.host.RuntimeAux] 类 KDoc）。 */
        const val AUX_SLOT: String = "mod.csv.load"

        /** 现读；装配链没装过 = null。 */
        fun of(store: VariableStore): ModCsvLoadContext? = store.aux.peek(AUX_SLOT)

        /** 装（只建一次；重复调用保留先装的那份，与 `SidecarSaveWiring.install` 同口径）。 */
        fun install(store: VariableStore, context: ModCsvLoadContext): ModCsvLoadContext =
            store.aux.slot(AUX_SLOT) { context }
    }
}

/**
 * 装载相位：Base 游戏数据 + manifest 字节 → [ModCsvLoadContext]（唯一一次 `ManifestLoader.load`）。
 *
 * ## 容量策略 B（CONTRACT-RT2-M14B-IMPL §1 裁定 1/2）
 *
 * `csvTableCapacities[table]` **不取 Base 真值**（那样注入区在 `DeclarationRegistry` 眼里不存在，
 * 显式下标一律「越出表容量」，见 `DeclarationRegistry.kt:694/:714`），而是**按需推导**：
 * 先粗扫每份 manifest 的 `csvPatches` 显式下标（`index >= 0`，别名不参与），取
 * `max(Base 表长, 最大显式下标 + 1)`。硬上界同裁定 2：单表增量 ≤ [ModCsvInjectionPlan.MAX_INJECTION_INCREMENT]、
 * 绝对容量 ≤ [ModCsvInjectionPlan.MAX_TABLE_CAPACITY]；越界**整包拒绝**（[ModCsvCapacityLimitException]
 * 带逐条理由），不钳制、不静默截断。
 *
 * ## 排除面（裁定 5/6）
 *
 * 计划只收 `C_INT1` / `C_STR1` 角色 1 维变量；`C_INT2`（`CDFLAG`：一维关键字 ↔ 扁平格映射未定义）
 * 与系统变量（`FLAG` / `GLOBAL` 一类）整表/整变量警告排除；别名（`.als`）不进计划、不新造位。
 */
object ModCsvPlanLoader {

    /**
     * @param base 装载相位的 Base 游戏数据（`GameDataLoader.load` / `loadBase` 的产物；只用其
     *   `registry` 取 Base 容量、Base 关键字与变量维度，不读角色模板）
     * @param manifests 磁盘 manifest 字节（按链接序；坏档交给唯一那次 `ManifestLoader.load` 整包拒）
     * @param builtinVariableNames 引擎内置变量名（撞名拒绝，透传）
     */
    fun load(
        base: GameData,
        manifests: List<ManifestSource>,
        builtinVariableNames: Set<String> = emptySet(),
    ): ModCsvLoadContext {
        val registry = base.registry
        val baseKeywords = registry.systemKeywordTables()
        val baseCapacities = tableCapacities(registry)
        val capacities = deriveCapacities(manifests, baseCapacities)

        val snapshot = ManifestLoader.load(
            manifests = manifests,
            builtinVariableNames = builtinVariableNames,
            csvTableCapacities = capacities,
            baseCsvKeywords = baseKeywords,
        )

        val warnings = ArrayList<String>()
        val tableToVar = supportedTableToVar()
        val varCapacities = HashMap<String, Int>()
        for ((table, keywords) in snapshot.declaredCsvKeywords) {
            if (keywords.isNullOrEmpty()) continue
            val varName = tableToVar[normTable(table)]
            if (varName == null) {
                warnings += "Mod CSV 注入位：表 $table 不是角色 1 维变量（C_INT1/C_STR1），v1 不持久化，已排除"
            } else {
                val def = registry.def(varName)
                val capacity = def?.let { supportedCapacityOf(it, varName, warnings) }
                if (capacity != null) varCapacities[varName] = capacity
            }
        }

        val plan = ModCsvInjectionPlan.fromSnapshot(snapshot, varCapacities, tableToVar)
        return ModCsvLoadContext(plan, snapshot, capacities, baseKeywords, warnings)
    }

    /** 单个变量能否进本波扩容面；能则返回 Base 容量（列块长），否则警告并返回 null。 */
    private fun supportedCapacityOf(def: VarDef, varName: String, warnings: MutableList<String>): Int? =
        when (def.storage) {
            Storage.C_INT1, Storage.C_STR1 -> def.dims.fold(1) { acc, d -> acc * d }
            Storage.C_INT2 -> {
                warnings += "Mod CSV 注入位：$varName 属二维变量（C_INT2），v1 不支持持久化，整表排除"
                null
            }
            else -> {
                warnings += "Mod CSV 注入位：$varName 的存储形态 ${def.storage} 不在本波扩容面，已排除"
                null
            }
        }

    /** Base 容量：29 槽名字表长度（`ConstantData.cs:678` 的 `MaxDataList` 等价面）。 */
    private fun tableCapacities(registry: VariableRegistry): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (slot in registry.csvTables.indices) {
            val table = com.eratw.port.core.data.csv.GameDataLoader.SLOT_FILES[slot].substringBefore('.')
            out[table] = registry.csvTables[slot].size
        }
        return out
    }

    /**
     * 按需推导容量（裁定 1）：`max(Base, 最大显式下标 + 1)`；越界整包拒绝并逐条给理由。
     *
     * 粗扫用 [ManifestCodec.decode]，解码失败**不在这里报错**（坏档由唯一那次
     * `ManifestLoader.load` 统一整包拒，错误形状不变）。
     */
    private fun deriveCapacities(
        manifests: List<ManifestSource>,
        baseCapacities: Map<String, Int>,
    ): Map<String, Int> {
        val maxExplicit = maxExplicitIndices(manifests)
        val violations = ArrayList<String>()
        val out = LinkedHashMap<String, Int>()
        for ((rawTable, base) in baseCapacities) {
            val derived = maxOf(base, maxExplicit[normTable(rawTable)] ?: 0)
            val violation = violationOf(rawTable, base, derived)
            if (violation != null) violations += violation
            out[rawTable] = derived
        }
        if (violations.isNotEmpty()) throw ModCsvCapacityLimitException(violations)
        return out
    }

    /** 每个表在 manifest 里声明过的**最大显式下标 + 1**（别名与 `-1` 自动分配不参与）。 */
    private fun maxExplicitIndices(manifests: List<ManifestSource>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (source in manifests) {
            val descriptor = runCatching { ManifestCodec.decode(source.bytes) }.getOrNull()
            descriptor?.csvPatches?.forEach { patch ->
                if (!patch.isAlias && patch.index >= 0) {
                    val table = normTable(patch.tableName)
                    out[table] = maxOf(out[table] ?: 0, patch.index + 1)
                }
            }
        }
        return out
    }

    /** 越界理由（null = 合法）：增量与绝对容量两条硬上界（裁定 2）。 */
    private fun violationOf(rawTable: String, base: Int, derived: Int): String? {
        val increment = derived - base
        val over = derived > base &&
            (increment > ModCsvInjectionPlan.MAX_INJECTION_INCREMENT ||
                derived > ModCsvInjectionPlan.MAX_TABLE_CAPACITY)
        return if (!over) {
            null
        } else {
            "表 $rawTable：Base=$base，声明要求 $derived（增量 $increment，上限 " +
                "增量 ${ModCsvInjectionPlan.MAX_INJECTION_INCREMENT} / " +
                "绝对 ${ModCsvInjectionPlan.MAX_TABLE_CAPACITY}）"
        }
    }

    /**
     * 表名 → 变量名（只收本波扩容面：角色 1 维整数/字符串；`CDFLAG1/2 → CDFLAG` 按 byDim 归并）。
     *
     * 与 `DeclarationRegistry` 的别名占位口径对齐（裁定 6）：别名不进计划、不新造位；
     * 显式别名若指向注入区，`DeclarationRegistry.kt:703-707` 已把目标位记进 `occupiedIndices`，
     * 于是自动分配不会再把命名关键字塞进同一位 —— 本对象不另立第三套判据。
     */
    private fun supportedTableToVar(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val vars = SystemLayout.charaInt1D.map { it.first } +
            SystemLayout.charaStr1D.map { it.first } +
            SystemLayout.charaInt2D.map { it.first }
        for (varName in vars) {
            val spec = SystemLayout.keywords[varName.uppercase()] ?: continue
            out.putIfAbsent(normTable(spec.table), varName)
            if (spec.byDim) out.putIfAbsent(normTable(siblingTable(spec.table)), varName)
        }
        return out
    }

    /** `CDFLAG1` ↔ `CDFLAG2`（byDim 变量的一对表名）。 */
    private fun siblingTable(table: String): String =
        when {
            table.endsWith("1") -> table.dropLast(1) + "2"
            table.endsWith("2") -> table.dropLast(1) + "1"
            else -> table
        }

    /** 表名归一：只做大小写折叠（与 `DeclarationRegistry` 的 `NamePolicy.canonical` 同语义面）。 */
    private fun normTable(table: String): String = table.trim().uppercase()
}
