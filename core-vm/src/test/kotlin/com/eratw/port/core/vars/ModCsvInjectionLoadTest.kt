package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.patch.CsvKeywordBinding
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M1-4 阶段二：**装载相位**的注入位扩容 + 关键字登记（CONTRACT-RT2-M14B-IMPL §1 裁定 1/4/5）。
 *
 * 三条判据（契约 §3）：
 * 1. 给定含注入位的计划，`GameDataLoader.load(dir, plan)` 产出的名字表容量与列块长 ≥ `maxIndex+1`，
 *    Base 布局（其余槽位、Base 关键字）逐项不变；
 * 2. 注入关键字登记进名字表（`keywordIndex` 可查），容量不足时拒登记并警告（不越界写）；
 * 3. `C_INT2`（`CDFLAG`）整表排除（语义未定义，裁定 5）；空计划/缺省重载与单趟 `load(dir)` 等价。
 *
 * 装置用空临时目录：`GameDataLoader` 对缺失的 CSV 只告警不阻断，于是走
 * `VariableSizes` / `VariableRegistry` 的出厂缺省（CSTR 槽 17 = 100、CFLAG 槽 15 = 1000），
 * 正好把「名字表容量」与「列块长」两条推导分开钉。
 */
class ModCsvInjectionLoadTest {

    @TempDir
    lateinit var temp: Path

    private fun plan(
        varName: String,
        table: String,
        base: Int,
        index: Int,
        keyword: String = "KOJO_A",
    ): ModCsvInjectionPlan =
        ModCsvInjectionPlan.of(
            baseCapacities = mapOf(varName to base),
            declared = mapOf(table to mapOf(keyword to CsvKeywordBinding(keyword, index, "kojo.test", alias = false))),
            tableToVar = mapOf(table to varName),
        )

    @Test
    fun `C_STR1 注入位 - 列块长与名字表容量都抬到 maxIndex 加一且关键字可查`() {
        // CSTR：charaStr1D 与 maxDataList[17] 出厂都是 100 ⇒ 150 号位两侧都要扩到 151。
        val plan = plan(varName = "CSTR", table = "CSTR", base = 100, index = 150)

        val data = GameDataLoader.load(temp, plan)
        assertNotNull(data)
        assertEquals(151, data.registry.def("CSTR")!!.dims[0], "列块长必须抬到 151（CharacterTable.blockSize 派生）")
        assertEquals(151, data.registry.csvTable("CSTR").size, "名字表容量必须抬到 151（CsvNameTable.capacity）")
        assertEquals(150, data.registry.csvTable("CSTR").keywordIndex("KOJO_A"), "注入关键字必须登记进新下标")
    }

    @Test
    fun `C_INT1 注入位 - 只扩列块长不压名字表既有容量`() {
        // CFLAG：charaInt1D[9] 与 maxDataList[15] 出厂都是 1000 ⇒ 1500 号位两侧都抬到 1501。
        val plan = plan(varName = "CFLAG", table = "CFLAG", base = 1000, index = 1500)

        val data = GameDataLoader.load(temp, plan)
        assertNotNull(data)
        assertEquals(1501, data.registry.def("CFLAG")!!.dims[0], "列块长抬到 1501")
        assertEquals(1501, data.registry.csvTable("CFLAG").size, "名字表容量取 max(Base, required)")
        assertEquals(1500, data.registry.csvTable("CFLAG").keywordIndex("KOJO_A"))
    }

    @Test
    fun `Base 布局零变化 - 空计划与单趟装载逐项一致`() {
        val plain = GameDataLoader.load(temp)!!
        val empty = GameDataLoader.load(temp, ModCsvInjectionPlan.EMPTY)!!

        for (slot in GameDataLoader.SLOT_FILES.indices) {
            assertEquals(
                plain.registry.csvTables[slot].size,
                empty.registry.csvTables[slot].size,
                "槽 $slot 名字表容量必须不变",
            )
        }
        assertEquals(plain.registry.def("CSTR")!!.dims.toList(), empty.registry.def("CSTR")!!.dims.toList())
        assertEquals(plain.registry.def("CFLAG")!!.dims.toList(), empty.registry.def("CFLAG")!!.dims.toList())
        assertNull(empty.registry.csvTable("CSTR").keywordIndex("KOJO_A"), "空计划不得登记任何关键字")
        assertTrue(empty.warnings.isEmpty(), "空计划不得产生扩容警告，实得 ${empty.warnings}")
        assertEquals(plain.warnings, empty.warnings, "Base-only 的告警面也必须一致")
    }

    @Test
    fun `C_INT2 整表排除 - 不扩容也不登记且逐表警告`() {
        val plan = plan(varName = "CDFLAG", table = "CDFLAG1", base = 1, index = 5)

        val data = GameDataLoader.load(temp, plan)
        assertNotNull(data)
        assertEquals(listOf(1, 1), data.registry.def("CDFLAG")!!.dims.toList(), "C_INT2 不扩容（裁定 5）")
        assertNull(data.registry.csvTable("CDFLAG1").keywordIndex("KOJO_A"), "C_INT2 关键字不登记")
        assertTrue(
            data.warnings.any { it.contains("二维变量") },
            "必须逐表警告，实得 ${data.warnings}",
        )
    }

    @Test
    fun `硬上界 - 单表增量超 4096 与绝对容量超 65536 都抛整包拒绝`() {
        val tooWide = plan(varName = "CSTR", table = "CSTR", base = 100, index = 100 + 4096)
        val wide = assertFailsWith<ModCsvCapacityLimitException> { tooWide.requiredCapacityOf("CSTR") }
        assertTrue(wide.reasons.single().contains("增量"), "越界说明必须写增量，实得 ${wide.reasons}")

        val tooLarge = plan(varName = "CSTR", table = "CSTR", base = 65536, index = 65536)
        val large = assertFailsWith<ModCsvCapacityLimitException> { tooLarge.requiredCapacityOf("CSTR") }
        assertTrue(large.reasons.single().contains("65536"), "越界说明必须写绝对上限，实得 ${large.reasons}")
    }

    @Test
    fun `硬上界边界 - 恰好 4096 增量与 65536 绝对容量放行`() {
        val atLimit = plan(varName = "CSTR", table = "CSTR", base = 100, index = 100 + 4096 - 1)
        assertEquals(4196, atLimit.requiredCapacityOf("CSTR"), "增量恰 4096 必须放行")

        val atAbsolute = plan(varName = "CSTR", table = "CSTR", base = 65535, index = 65535)
        assertEquals(65536, atAbsolute.requiredCapacityOf("CSTR"), "绝对容量恰 65536 必须放行")
    }

    @Test
    fun `未知表与系统 1 维变量 - 不扩容不登记并警告`() {
        // GLOBAL 是系统 1 维（不是 C_INT1/C_STR1）：本波不扩，必须警告而不是静默跳过。
        val plan = plan(varName = "GLOBAL", table = "GLOBAL", base = 1000, index = 1200)

        val data = GameDataLoader.load(temp, plan)
        assertNotNull(data)
        assertTrue(
            data.warnings.any { it.contains("不是角色 1 维变量") },
            "系统变量必须警告排除，实得 ${data.warnings}",
        )
        assertNull(data.registry.csvTable("GLOBAL").keywordIndex("KOJO_A"))
    }

    @Test
    fun `扩容在 store 里生效 - 注入位可读写且不越界`() {
        val plan = plan(varName = "CFLAG", table = "CFLAG", base = 1000, index = 1500)
        val data = GameDataLoader.load(temp, plan)!!

        val store = VariableStore(registry = data.registry)
        val def = data.registry.def("CFLAG")!!
        assertEquals(1501, store.characters.int1[def.index].blockSize, "列块长必须已含注入位")

        // 注入位在第 0 号角色上可写可读（未扩容时这里会 ArrayIndexOutOfBounds）。
        store.characters.int1[def.index].set(0, 1500, 7L)
        assertEquals(7L, store.characters.int1[def.index].get(0, 1500))
    }

    @Test
    fun `装载目录缺失 - 返回 null 与单趟语义一致`() {
        val missing = Files.createDirectories(temp.resolve("gone")).resolve("nope")
        assertNull(GameDataLoader.load(missing, plan("CSTR", "CSTR", 100, 150)))
    }
}
