package com.eratw.port.core.vars

import com.eratw.port.core.patch.CsvKeywordBinding
import com.eratw.port.core.patch.DeclarationSnapshot
import java.util.Collections

/**
 * Mod 注入 CSV 关键字在角色列上的一个「注入位」。
 *
 * 注入位 = 关键字绑定下标 `index >= baseCapacity` 的那一段（Base CSV 自己声明的格子
 * 由 `.sav` 角色块承载，不进 `.rt2`）。[tableId] 已按表名归一（大写），[keyword]
 * 保持原文大小写（[ModCsvValueKey] 的口径），[packageId] 保留 manifest 原文 ——
 * 它要与 `ModStorageSection.packageId` 逐字对上，故不能走 [CsvIdentityKeys.keyOf] 的包名归一。
 */
data class InjectedCell(
    val index: Int,
    val packageId: String,
    val tableId: String,
    val keyword: String,
)

/**
 * 值袋的内部键：`ModCsvValueKey` 是诊断用的**身份对象**（非 data class，equals = 引用），
 * 不能直接当 Map 键。[CsvValueSlot] 把四个字段摊平成 data class，等价性由结构决定。
 *
 * 顺序：包 → 表 → 关键字 → 身份形态 → 身份值（编解码按此序落盘，字节可复现）。
 */
data class CsvValueSlot(
    val packageId: String,
    val tableId: String,
    val keyword: String,
    val identity: CharIdentity,
) {
    fun toKey(): ModCsvValueKey = ModCsvValueKey(packageId, tableId, keyword, identity)

    companion object {
        fun of(key: ModCsvValueKey): CsvValueSlot =
            CsvValueSlot(key.packageId, key.tableId, key.keyword, key.identity)
    }
}

/** Mod 注入 CSV 格子的值：整数格（1 维/2 维整数列）或字符串格（CSTR）。 */
sealed interface CsvValue {
    data class IntValue(val v: Long) : CsvValue

    data class StrValue(val v: String) : CsvValue
}

/**
 * 一次存/读档搬动的全部 Mod CSV 注入值（按 [CsvValueSlot] 去重）。
 *
 * `byPackage()` 是 `.rt2` 分片的切面：每个 Package 的值只进自己那一片，
 * 孤儿片逻辑因此不需要理解值内容（见 [com.eratw.port.core.save.ModCsvValueCodec]）。
 */
class ModCsvValueBag private constructor(slots: Map<CsvValueSlot, CsvValue>) {

    private val slots: Map<CsvValueSlot, CsvValue> =
        Collections.unmodifiableMap(LinkedHashMap(slots))

    val size: Int get() = slots.size

    val isEmpty: Boolean get() = slots.isEmpty()

    /** 规范序（包/表/关键字/身份）的只读视图；编解码与恢复都按此序遍历。 */
    val entries: Map<CsvValueSlot, CsvValue> get() = slots

    fun get(key: ModCsvValueKey): CsvValue? = slots[CsvValueSlot.of(key)]

    fun byPackage(): Map<String, ModCsvValueBag> =
        slots.entries.groupBy({ it.key.packageId }, { it.key to it.value })
            .mapValues { (_, list) -> ofSlots(list.toMap()) }

    companion object {
        val EMPTY: ModCsvValueBag = ModCsvValueBag(emptyMap())

        fun of(pairs: Iterable<Pair<ModCsvValueKey, CsvValue>>): ModCsvValueBag =
            ofSlots(pairs.associate { CsvValueSlot.of(it.first) to it.second })

        fun ofSlots(slots: Map<CsvValueSlot, CsvValue>): ModCsvValueBag = ModCsvValueBag(slots)
    }
}

/**
 * 注入位计划：哪些变量、哪些下标是 Mod 注入的，注入位的持久化键怎么拼。
 *
 * 与 [DeclarationSnapshot] 解耦（构造只吃扁平参数），生产适配见 [fromSnapshot]。
 * 只收 `alias = false` 的绑定（.als 只写反查表，不占命名位）；`index < baseCapacity`
 * 的绑定属于 Base，不进计划。
 *
 * @property baseCapacities 变量名（归一）→ Base 扁平容量（1 维长度；2 维为各维乘积）
 * @property baseDims 变量名（归一）→ Base 2 维形状（仅 C_INT2 写 `.sav` 截断时用；缺席按
 *   [baseDimsOf] 的守恒推导）
 */
class ModCsvInjectionPlan private constructor(
    private val tableToVar: Map<String, String>,
    private val baseCapacities: Map<String, Int>,
    private val baseDims: Map<String, IntArray>,
    private val cellsByVar: Map<String, List<InjectedCell>>,
) {

    val isEmpty: Boolean get() = cellsByVar.isEmpty()

    /** 有计划（含注入位）的变量名集合（归一）。 */
    val varNames: Set<String> get() = cellsByVar.keys

    /** 表名（归一）→ 变量名（归一）；无对应变量返回 null。 */
    fun varOf(table: String): String? = tableToVar[normName(table)]

    fun baseCapacityOf(varName: String): Int? = baseCapacities[normName(varName)]

    fun cellsOf(varName: String): List<InjectedCell> = cellsByVar[normName(varName)].orEmpty()

    fun cellAt(varName: String, index: Int): InjectedCell? =
        cellsOf(varName).firstOrNull { it.index == index }

    /**
     * 该变量的注入位要求的**物理列长**（最大注入下标 + 1）；无注入位 ⇒ null。
     *
     * 装载相位据此扩 `sizes.maxDataList[slot]` 与 `charaInt1D/Str1D[slot]`（CONTRACT-RT2-M14B-IMPL §1 裁定 1/4）。
     * **硬上界守卫**（同契约裁定 2）：单表注入增量 > [MAX_INJECTION_INCREMENT] 或推导后绝对容量 >
     * [MAX_TABLE_CAPACITY] ⇒ 抛 [ModCsvCapacityLimitException]（整包拒绝，不钳制、不静默截断）。
     */
    fun requiredCapacityOf(varName: String): Int? {
        val key = normName(varName)
        val cells = cellsByVar[key]
        val base = baseCapacities[key]
        if (cells == null || base == null) return null
        val required = cells.maxOf { it.index } + 1
        val increment = required - base
        if (increment > MAX_INJECTION_INCREMENT || required > MAX_TABLE_CAPACITY) {
            throw ModCsvCapacityLimitException(
                listOf(
                    "变量 $varName：Base=$base，注入位要求 $required（增量 $increment，上限 " +
                        "增量 $MAX_INJECTION_INCREMENT / 绝对 $MAX_TABLE_CAPACITY）",
                ),
            )
        }
        return required
    }

    /** 注入位判定：该变量已知 Base 容量且 `index >= baseCapacity`。 */
    fun isInjected(varName: String, index: Int): Boolean {
        val base = baseCapacities[normName(varName)] ?: return false
        return index >= base
    }

    /**
     * Base 侧要写进 `.sav` 的长度：C_INT1 / C_STR1 直接是 [baseCapacityOf]；
     * C_INT2 是 Base 2 维形状的乘积。null = 该变量没有 Base 容量记录 ⇒ 调用方按现状全长写
     * （不截断，宁可不抽离也不丢值）。
     */
    fun baseLength(varName: String, storage: Storage, dims: IntArray): Int? {
        val key = normName(varName)
        val base = baseCapacities[key] ?: return null
        return if (storage == Storage.C_INT2) {
            baseDimsOf(key, dims).fold(1) { acc, d -> acc * d }
        } else {
            base
        }
    }

    /**
     * Base 侧的 2 维形状。
     *
     * 显式给出的 [baseDims] 优先；缺席时按「扁平容量守恒」推导：保持末维不变，
     * 首维 = base / 末维（整除时），否则退化成一维 `[base, 1]`。阶段二的生产装配
     * 直接喂 `VariableSizes.charaInt2D` 的原值，不走这条推导。
     */
    fun baseDimsOf(varName: String, dims: IntArray): IntArray {
        val key = normName(varName)
        val explicit = baseDims[key]
        val base = baseCapacities[key]
        return when {
            explicit != null -> explicit.copyOf()
            base == null -> dims.copyOf()
            else -> shrinkDims(base, dims)
        }
    }

    companion object {

        /** 单表注入增量硬上界：`required - base` 不得超过它（CONTRACT-RT2-M14B-IMPL §1 裁定 2）。 */
        const val MAX_INJECTION_INCREMENT: Int = 4096

        /** 推导后单表绝对容量硬上界（同裁定 2）。 */
        const val MAX_TABLE_CAPACITY: Int = 65536

        val EMPTY: ModCsvInjectionPlan =
            ModCsvInjectionPlan(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /**
         * @param baseCapacities 变量名（原文或归一均可）→ Base 扁平容量
         * @param declared 表名（归一）→ 关键字原文 → 绑定（[DeclarationSnapshot.declaredCsvKeywords]）
         * @param tableToVar 表名 → 变量名（如 `CFLAG` → `CFLAG`、`CDFLAG1` → `CDFLAG`）
         * @param baseDims 变量名 → Base 2 维形状（仅 C_INT2 需要）
         */
        fun of(
            baseCapacities: Map<String, Int>,
            declared: Map<String, Map<String, CsvKeywordBinding>>,
            tableToVar: Map<String, String>,
            baseDims: Map<String, IntArray> = emptyMap(),
        ): ModCsvInjectionPlan {
            val caps = baseCapacities.mapKeys { (name, _) -> normName(name) }
            val vars = tableToVar.entries.associate { (table, name) -> normName(table) to normName(name) }
            val dims = baseDims.mapKeys { (name, _) -> normName(name) }
            val byVar = HashMap<String, MutableList<InjectedCell>>()
            for ((rawTable, keywords) in declared) {
                val table = normName(rawTable)
                val varName = vars[table]
                val base = varName?.let { caps[it] }
                if (varName == null || base == null) continue
                for ((keyword, binding) in keywords) {
                    if (binding.alias || binding.index < base) continue
                    byVar.getOrPut(varName) { ArrayList() }
                        .add(InjectedCell(binding.index, binding.packageId, table, keyword))
                }
            }
            val frozen = byVar.mapValues { (_, cells) -> cells.sortedBy { it.index }.toList() }
            return ModCsvInjectionPlan(vars, caps, dims, frozen)
        }

        /** 生产适配：直接吃冻结快照的 `declaredCsvKeywords`。 */
        fun fromSnapshot(
            snapshot: DeclarationSnapshot,
            baseCapacities: Map<String, Int>,
            tableToVar: Map<String, String>,
            baseDims: Map<String, IntArray> = emptyMap(),
        ): ModCsvInjectionPlan =
            of(baseCapacities, snapshot.declaredCsvKeywords, tableToVar, baseDims)
    }
}

/**
 * 注入位容量越界（CONTRACT-RT2-M14B-IMPL §1 裁定 2）：**整包拒绝**，不钳制、不静默截断。
 *
 * [reasons] 是逐条说明（每个越界变量一条），由调用方原样上日志/警告面。
 */
class ModCsvCapacityLimitException(val reasons: List<String>) :
    IllegalStateException(reasons.joinToString("；"))

/**
 * 存档侧的采集桶：逐个角色位采注入值，末尾一次性物化成 [ModCsvValueBag]。
 *
 * 采集**只收非默认值**（整数非 0 / 字符串非空）：缺省位由读档时的列零底补齐，
 * 值袋因此与「写进去多少格」而不是「列有多宽」成正比。
 */
class ModCsvValueBagSink(
    val plan: ModCsvInjectionPlan,
    private val warn: (String) -> Unit,
) {
    private val collected = ArrayList<Pair<ModCsvValueKey, CsvValue>>()
    private var materialized: ModCsvValueBag? = null

    val bag: ModCsvValueBag
        get() = materialized ?: ModCsvValueBag.of(collected).also { materialized = it }

    fun capture(store: VariableStore, pos: Int) {
        collected += ModCsvValueSampler.collectChar(store, plan, pos, warn)
    }
}

/**
 * 存档侧采集器：活列 → 值袋条目（键 = 包/表/关键字 + [CharIdentity.CsvNo]）。
 *
 * 身份取 `NO` → `CharacterTemplate.csvNo`（[CharIdentity] 类文档的稳定身份口径），
 * `csvNo <= 0`（ADDVOIDCHARA 匿名角色 / 伪角色）v1 明确不支持：上警告并跳过，
 * 不退回注册下标（下标在 DELCHARA 后会串位）。
 */
object ModCsvValueSampler {

    fun collectChar(
        store: VariableStore,
        plan: ModCsvInjectionPlan,
        pos: Int,
        warn: (String) -> Unit,
    ): List<Pair<ModCsvValueKey, CsvValue>> {
        val csvNo = csvNoAt(store, pos)
        if (csvNo <= 0) {
            warn("Mod CSV 注入值：角色位 $pos 无 CSV 番号（NO=0 为匿名角色，v1 不支持持久化），跳过")
            return emptyList()
        }
        val identity = CharIdentity.CsvNo(csvNo)
        val out = ArrayList<Pair<ModCsvValueKey, CsvValue>>()
        for (varName in plan.varNames) {
            val def = store.registry.def(varName) ?: continue
            for (cell in plan.cellsOf(varName)) {
                val value = readCell(store, def, pos, cell.index) ?: continue
                out += ModCsvValueKey(cell.packageId, cell.tableId, cell.keyword, identity) to value
            }
        }
        return out
    }

    /** 角色位的 CSV 番号；0 = 无稳定身份（匿名/伪角色/模板缺失）。 */
    internal fun csvNoAt(store: VariableStore, pos: Int): Int {
        val no = store.characters.int0[VariableCode.lowIndex(VariableCode.NO_VAR)].get(pos, 0)
        val csvNo = store.template(no)?.csvNo ?: 0L
        return if (csvNo in 1..Int.MAX_VALUE.toLong()) csvNo.toInt() else 0
    }

    private fun readCell(store: VariableStore, def: VarDef, pos: Int, index: Int): CsvValue? {
        val c = store.characters
        return when (def.storage) {
            Storage.C_INT1 -> intCell(c.int1[def.index], pos, index)
            Storage.C_INT2 -> intCell(c.int2[def.index], pos, index)
            Storage.C_STR1 -> strCell(store, c.str1[def.index], pos, index)
            else -> null
        }
    }

    private fun intCell(col: CharacterTable.LongCol, pos: Int, index: Int): CsvValue? {
        if (index !in 0 until col.blockSize) return null
        val v = col.get(pos, index)
        return if (v == 0L) null else CsvValue.IntValue(v)
    }

    private fun strCell(store: VariableStore, col: CharacterTable.StrCol, pos: Int, index: Int): CsvValue? {
        if (index !in 0 until col.blockSize) return null
        val text = store.pool.lookup(col.get(pos, index))
        return if (text.isEmpty()) null else CsvValue.StrValue(text)
    }
}

/**
 * 读档侧恢复器：值袋 → 当前变量池。
 *
 * 落点序：`CharIdentity` → 当前角色位（扫活角色表的 `NO` → `csvNo`，**同 csvNo 多实例取首个**，
 * v1 明示共享）；关键字 → 当下注册表下标（[keywordIndex]）。任一步查不到就逐条上警告并丢弃，
 * 不静默、不拿存档期的下标猜。
 */
object ModCsvValueRestorer {

    fun apply(
        store: VariableStore,
        plan: ModCsvInjectionPlan,
        keywordIndex: (table: String, keyword: String) -> Int?,
        bag: ModCsvValueBag,
        warn: (String) -> Unit,
    ) {
        if (bag.isEmpty) return
        val session = RestoreSession(store, plan, keywordIndex, csvPositions(store), warn)
        for ((slot, value) in bag.entries) session.restore(slot, value)
    }

    /** 一条键的落位会话（`store`/`plan`/`warn` 收进实例，主循环只剩一次调用）。 */
    private class RestoreSession(
        private val store: VariableStore,
        private val plan: ModCsvInjectionPlan,
        private val keywordIndex: (table: String, keyword: String) -> Int?,
        private val positions: Map<Int, Int>,
        private val warn: (String) -> Unit,
    ) {
        private val writer = CellWriter(store, warn)

        fun restore(slot: CsvValueSlot, value: CsvValue) {
            val label = "${slot.packageId}/${slot.tableId}[${slot.keyword}]"
            val csvNo = (slot.identity as? CharIdentity.CsvNo)?.no
            val pos = csvNo?.let { positions[it] }
            val def = plan.varOf(slot.tableId)?.let { store.registry.def(it) }
            val index = keywordIndex(slot.tableId, slot.keyword)
            when {
                csvNo == null ->
                    warn("Mod CSV 注入值 $label：身份是 EntityId，v1 只支持 CSV 番号，丢弃")
                pos == null ->
                    warn("Mod CSV 注入值 $label：当前角色表里没有 CSV 番号 $csvNo，丢弃")
                def == null ->
                    warn("Mod CSV 注入值 $label：当前注册表没有表 ${slot.tableId} 对应的变量，丢弃")
                index == null ->
                    warn("Mod CSV 注入值 $label：关键字已从当前注册表消失，丢弃")
                else -> writer.write(def, pos, index, value)
            }
        }
    }

    /** 落格器（`store` / `warn` 收进实例，避免每个写口都拖一串公共参数）。 */
    private class CellWriter(
        private val store: VariableStore,
        private val warn: (String) -> Unit,
    ) {
        fun write(def: VarDef, pos: Int, index: Int, value: CsvValue) {
            val c = store.characters
            when (def.storage) {
                Storage.C_INT1 -> writeInt(c.int1[def.index], def.name, pos, index, value)
                Storage.C_INT2 -> writeInt(c.int2[def.index], def.name, pos, index, value)
                Storage.C_STR1 -> writeStr(c.str1[def.index], def.name, pos, index, value)
                else -> warn("Mod CSV 注入值：变量 ${def.name} 的存储 ${def.storage} 不支持，丢弃")
            }
        }

        private fun writeInt(col: CharacterTable.LongCol, varName: String, pos: Int, index: Int, value: CsvValue) {
            val v = value as? CsvValue.IntValue
            when {
                v == null -> warn("Mod CSV 注入值：$varName[$index] 期望整数，存档里是字符串，丢弃")
                index !in 0 until col.blockSize ->
                    warn("Mod CSV 注入值：$varName[$index] 超出当前容量 ${col.blockSize}，丢弃")
                else -> col.set(pos, index, v.v)
            }
        }

        private fun writeStr(col: CharacterTable.StrCol, varName: String, pos: Int, index: Int, value: CsvValue) {
            val v = value as? CsvValue.StrValue
            when {
                v == null -> warn("Mod CSV 注入值：$varName[$index] 期望字符串，存档里是整数，丢弃")
                index !in 0 until col.blockSize ->
                    warn("Mod CSV 注入值：$varName[$index] 超出当前容量 ${col.blockSize}，丢弃")
                else -> col.set(pos, index, store.pool.intern(v.v))
            }
        }
    }

    /** CSV 番号 → 当前角色位（首个命中）；活表扫描而非建档期快照，DELCHARA 后仍正确。 */
    private fun csvPositions(store: VariableStore): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (pos in 0 until store.characters.count) {
            val csvNo = ModCsvValueSampler.csvNoAt(store, pos)
            if (csvNo > 0) out.putIfAbsent(csvNo, pos)
        }
        return out
    }
}

/** 标识符归一（表名/变量名）：大写 + 去空白，与 [CsvIdentityKeys.normalizeIdentifier] 同口径。 */
private fun normName(raw: String): String = CsvIdentityKeys.normalizeIdentifier(raw)

private fun shrinkDims(base: Int, dims: IntArray): IntArray {
    val flat = dims.fold(1) { acc, d -> acc * d }
    val last = dims.lastOrNull() ?: 1
    return when {
        base >= flat -> dims.copyOf()
        last > 1 && base % last == 0 -> intArrayOf(base / last, last)
        else -> intArrayOf(base, 1)
    }
}
