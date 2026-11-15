package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.ModCsvInjectionPlan
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import java.nio.file.Path

/**
 * 游戏数据总装：一次装载 = VariableSize → 29 槽名字表 → GAMEBASE → 角色模板。
 *
 * 顺序依据 ConstantData.LoadData（ConstantData.cs:672-757）：
 * 1. `loadVariableSizeData`（:680）——必须先于名字表：29 槽长度（MaxDataList）以它调整后的为准；
 * 2. 29 个 `loadDataTo`（:685-713）——槽号与文件名逐条照抄，见 [SLOT_FILES]；
 * 3. `loadCharacterData`（:750）——角色模板在名字表**之后**：模板行的值要走名字表反查
 *    （如 `基礎,体力,2000` 的「体力」查 base.csv  dic）；
 * 4. GAMEBASE 不在 LoadData 里——它由 Process 启动流程单独装载（GameBase.cs:106，
 *    Process.cs:283），本总装按同一数据口径一并读出，供 registry 构造注入。
 *
 * 用法：
 * ```
 * val data = GameDataLoader.load(csvDir) ?: return  // 目录缺失 → null（优雅降级）
 * val store = VariableStore(data.registry)
 * data.templates.forEach { store.registerTemplate(it) }
 * ```
 */
class GameData(
    val gameBase: GameBaseData,
    val registry: VariableRegistry,
    val templates: List<CharacterTemplate>,
    val warnings: List<String>,
    /**
     * ITEM 价格（Item.csv 第 3 列）。原版是独立的 `long[] ItemPrice`（ConstantData.cs:713），
     * 本移植的 ITEMPRICE 是系统变量（store int1D[60]），故价格在此暂存，
     * **由调用方在建 store 后写入**：`writeInt("ITEMPRICE", longArrayOf(i), price)`。
     */
    val itemPrices: LongArray,
)

object GameDataLoader {

    /**
     * 29 槽 → 文件名。spec: ConstantData.cs:685-713 的 loadDataTo 调用序列（顺序即槽号）。
     * 槽 6（ITEM）额外填 ItemPrice（本移植 = `sizes.int1D[60]`，VariableSizes.kt:79 同口径）。
     */
    val SLOT_FILES: List<String> = listOf(
        "Abl.csv",     // 0 ABL
        "Exp.csv",     // 1 EXP
        "Talent.csv",  // 2 TALENT
        "Palam.csv",   // 3 PALAM
        "Train.csv",   // 4 TRAIN
        "Mark.csv",    // 5 MARK
        "Item.csv",    // 6 ITEM（+ItemPrice）
        "Base.csv",    // 7 BASE
        "Source.csv",  // 8 SOURCE
        "Ex.csv",      // 9 EX
        "Str.csv",     // 10 STR
        "Equip.csv",   // 11 EQUIP
        "Tequip.csv",  // 12 TEQUIP
        "Flag.csv",    // 13 FLAG
        "Tflag.csv",   // 14 TFLAG
        "Cflag.csv",   // 15 CFLAG
        "Tcvar.csv",   // 16 TCVAR
        "Cstr.csv",    // 17 CSTR
        "Stain.csv",   // 18 STAIN
        "Cdflag1.csv", // 19 CDFLAG1
        "Cdflag2.csv", // 20 CDFLAG2
        "Strname.csv", // 21 STRNAME
        "Tstr.csv",    // 22 TSTR
        "Savestr.csv", // 23 SAVESTR
        "Global.csv",  // 24 GLOBAL
        "Globals.csv", // 25 GLOBALS
        "Day.csv",     // 26 DAY
        "Time.csv",    // 27 TIME
        "Money.csv",   // 28 MONEY（moneyIndex）
    )

    /**
     * 装载全部游戏数据。目录不存在 / 不可读 → null（调用方按"无数据"降级，
     * 例如 CI 环境没有游戏数据时 E2E 仍应能跑其余分节）。
     *
     * 下标越界口径：**不扩容**。原版 names 数组在 LoadData 一次性定型
     * `names[i] = new string[MaxDataList[i]]`（ConstantData.cs:678），loadDataTo 对越界下标
     * 只告警跳过（OoRArray + continue，ConstantData.cs:1791-1795）、对 0 长度数组告警后
     * 终止本文件（ProhibitedArrayName + break，:1786-1790），没有按 CSV 内容扩容的分支。
     * 超出 MaxDataList 的下标由 [CsvLoader.loadNameTable] 的越界跳过承接。
     * （2026-10-22 移除了此前的 autoGrowByCsvExtent 试探性扩容——与官方口径不符且在本语料是死代码。）
     */
    fun load(csvDir: Path): GameData? = load(csvDir, null)

    /**
     * **Base 口径的轻量装载**（M1-4 阶段二装载相位）：`VariableSize → GAMEBASE → registry → 29 槽名字表`，
     * **不读角色模板**（`CharaCsvLoader` 是本函数之外最重的一段）。
     *
     * 消费点 = `ModCsvPlanLoader`：推导 `csvTableCapacities` / `baseCsvKeywords` 与变量 Base 容量，
     * 供唯一一次 `ManifestLoader.load` 使用；随后 `GameSession` 会带计划再装一次完整数据。
     * 缺目录 → null（与 [load] 同口径）。
     */
    fun loadBase(csvDir: Path): GameData? {
        if (!java.nio.file.Files.isDirectory(csvDir)) return null
        val warnings = ArrayList<String>()
        val sizes = VariableSizes()
        VariableSizeLoader.load(csvDir, sizes, warnings)
        val gameBase = GameBaseLoader.load(csvDir, warnings)
        val registry = VariableRegistry(sizes, gameBase)
        val itemPrices = LongArray(sizes.maxDataList[6])
        for (slot in SLOT_FILES.indices) {
            val prices = if (slot == 6) itemPrices else null
            CsvLoader.loadNameTable(csvDir, SLOT_FILES[slot], slot, registry.csvTables[slot], prices, warnings)
        }
        return GameData(gameBase, registry, emptyList(), warnings, itemPrices)
    }

    /**
     * 装载全部游戏数据；[plan] 非空时**在 `VariableRegistry` 定型之前**并入 Mod 注入位
     * （CONTRACT-RT2-M14B-IMPL §1 裁定 1/4，锚 `ConstantData.cs:678`：原版容量在 LoadData 一次定型，
     * 注入位等价于在定型前把 mod 声明的关键字并入容量与名字表）。
     *
     * 两步都只做**扩容**不改 Base：
     * 1. `sizes.maxDataList[slot]` 与 `charaInt1D/Str1D[slot]` 抬到 `max(Base, 注入位最大下标 + 1)`；
     * 2. 名字表装载后，把每个注入位的关键字登记进 `registry.csvTables[slot]`（先输原则，同名不改写）。
     *
     * 只支持 `C_INT1` / `C_STR1`（角色 1 维整数/字符串）；`C_INT2`（`CDFLAG`）与系统 1 维变量
     * （`FLAG` / `GLOBAL` 一类）本波**整表/整变量警告排除**，不猜语义。`plan == null` 或空计划
     * ⇒ 与原 [load] 逐字节等价（Base-only 零变化）。
     */
    fun load(csvDir: Path, plan: ModCsvInjectionPlan?): GameData? {
        if (!java.nio.file.Files.isDirectory(csvDir)) return null
        val warnings = ArrayList<String>()
        val sizes = VariableSizes()
        VariableSizeLoader.load(csvDir, sizes, warnings)
        // 扩容 must be before registry：CsvNameTable.capacity 与 CharacterTable.blockSize 都吃 sizes。
        if (plan != null && !plan.isEmpty) expandForInjection(sizes, plan, warnings)
        // GameBase 先读：registry 的 gameBase 是构造期只读字段（VariableRegistry.kt:58）
        val gameBase = GameBaseLoader.load(csvDir, warnings)
        val registry = VariableRegistry(sizes, gameBase)
        val itemPrices = LongArray(sizes.maxDataList[6])
        for (slot in SLOT_FILES.indices) {
            val prices = if (slot == 6) itemPrices else null
            CsvLoader.loadNameTable(csvDir, SLOT_FILES[slot], slot, registry.csvTables[slot], prices, warnings)
        }
        // 名字表建好后才能登记注入关键字（CsvNameTable.set 有容量闸）。
        if (plan != null && !plan.isEmpty) registerInjectedKeywords(registry, plan, warnings)
        val templates = CharaCsvLoader.load(csvDir, registry, warnings)
        return GameData(gameBase, registry, templates, warnings, itemPrices)
    }

    /**
     * 装载相位扩容（裁定 1/4）：只碰计划涉及的 `C_INT1` / `C_STR1` 变量。
     *
     * 两类容量分别推导（生产语料里两者同值，但契约要求各自 `max`）：
     * - `sizes.maxDataList[slot]` = 名字表容量（`CsvNameTable.capacity`，`ConstantData.cs:678`）；
     * - `sizes.charaInt1D[slot]` / `charaStr1D[slot]` = 列块长（`CharacterTable.LongCol.blockSize` 派生）。
     */
    private fun expandForInjection(
        sizes: VariableSizes,
        plan: ModCsvInjectionPlan,
        warnings: MutableList<String>,
    ) {
        for (varName in plan.varNames) {
            val table = SystemLayout.keywords[varName.uppercase()]?.table
            val csvSlot = table?.let { csvSlotOf(it) }
            val int1Slot = SystemLayout.charaInt1D.indexOfFirst { it.first.equals(varName, true) }
            val str1Slot = SystemLayout.charaStr1D.indexOfFirst { it.first.equals(varName, true) }
            val int2Slot = SystemLayout.charaInt2D.indexOfFirst { it.first.equals(varName, true) }
            val required = plan.requiredCapacityOf(varName)
            when {
                int2Slot >= 0 -> warnings +=
                    "Mod CSV 注入位：$varName 属二维变量（C_INT2），v1 不支持持久化，整表排除"
                int1Slot >= 0 && csvSlot != null && required != null -> {
                    sizes.charaInt1D[int1Slot] = maxOf(sizes.charaInt1D[int1Slot], required)
                    sizes.maxDataList[csvSlot] = maxOf(sizes.maxDataList[csvSlot], required)
                }
                str1Slot >= 0 && csvSlot != null && required != null -> {
                    sizes.charaStr1D[str1Slot] = maxOf(sizes.charaStr1D[str1Slot], required)
                    sizes.maxDataList[csvSlot] = maxOf(sizes.maxDataList[csvSlot], required)
                }
                else -> warnings +=
                    "Mod CSV 注入位：$varName 不是角色 1 维变量（C_INT1/C_STR1），本波不扩容，已排除"
            }
        }
    }

    /** 注入关键字登记进名字表（先输原则：`ConstantData.cs:725-735`，同名不改写）。 */
    private fun registerInjectedKeywords(
        registry: VariableRegistry,
        plan: ModCsvInjectionPlan,
        warnings: MutableList<String>,
    ) {
        val supported = expandableVarNames()
        for (varName in plan.varNames) {
            // C_INT2 与系统变量在扩容相位已整表/整变量警告排除（裁定 5），这里不再登记。
            if (supported.any { it.equals(varName, true) }) {
                registerKeywordsOf(registry, plan, varName, warnings)
            }
        }
    }

    /** 本波扩容面 = 角色 1 维整数/字符串（`C_INT2` 与系统 1 维变量不在内，裁定 4/5）。 */
    private fun expandableVarNames(): List<String> =
        SystemLayout.charaInt1D.map { it.first } + SystemLayout.charaStr1D.map { it.first }

    /** 一个变量的全部注入关键字逐格登记；未知表 / 越界逐条警告（不越界写）。 */
    private fun registerKeywordsOf(
        registry: VariableRegistry,
        plan: ModCsvInjectionPlan,
        varName: String,
        warnings: MutableList<String>,
    ) {
        for (cell in plan.cellsOf(varName)) {
            val slot = csvSlotOf(cell.tableId)
            val table = slot?.let { registry.csvTables[it] }
            if (table == null) {
                warnings += "Mod CSV 注入位：未知表 ${cell.tableId}（关键字 ${cell.keyword}），已排除"
            } else if (cell.index !in 0 until table.size) {
                warnings += "Mod CSV 注入位：${cell.tableId}.${cell.keyword}#${cell.index} 越出名字表容量 " +
                    "${table.size}（装载相位扩容未覆盖），已排除"
            } else {
                table.set(cell.index, cell.keyword)
            }
        }
    }

    /** 表名（`CsvNameTable` 的槽位名）→ 29 槽下标；未知返回 null。 */
    private fun csvSlotOf(table: String): Int? {
        val idx = SLOT_FILES.indexOfFirst { it.substringBefore('.').equals(table, ignoreCase = true) }
        return idx.takeIf { it >= 0 }
    }
}
