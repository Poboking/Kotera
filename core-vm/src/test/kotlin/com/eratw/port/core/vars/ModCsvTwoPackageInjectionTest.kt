package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.patch.CsvKeywordPatch
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.ModuleDescriptor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M3-2-A 判据 4：**双包同表注入端到端**（CONTRACT-RT2-M32A §1.4）。
 *
 * 两包各注入关键字到同一 CSV 表（一包显式下标、一包 `-1` 自动分配）⇒
 * 声明事务错开分配（链接序取最小空闲位）+ 装载缝**一次**扩容到位 +
 * 两包关键字都可查、值互不串位。
 *
 * 边界 = 三个冻结裁定（引用不冲突）：
 * - 容量 B：按需推导 + 硬上界 4096/65536（`CONTRACT-RT2-M14B-IMPL.md` §1，
 *   `ModCsvInjectionPlan.requiredCapacityOf`）；
 * - `C_INT2`（CDFLAG）整表警告排除（`ModCsvLoadContext.kt` KDoc +
 *   `ModCsvInjectionLoadTest.C_INT2 整表排除`）；
 * - alias（`.als`）不进计划、不新造位（`ModCsvLoadContext.kt:61/:187`，裁定 6 口径）。
 */
class ModCsvTwoPackageInjectionTest {

    @TempDir
    lateinit var temp: Path

    private fun descriptor(id: String, index: Int, keyword: String, isAlias: Boolean = false) =
        ModuleDescriptor(
            packageId = id,
            csvPatches = listOf(
                CsvKeywordPatch(tableName = "CFLAG", index = index, keyword = keyword, isAlias = isAlias),
            ),
        )

    @Test
    fun `双包同表注入_显式与自动分配错开_一次扩容到位_值不串位`() {
        // CFLAG 出厂容量 1000（charaInt1D[9] 与 maxDataList[15]，见 ModCsvInjectionLoadTest）。
        // 声明事务（addPackage 调用序 = 链接序）：Base 关键字占 0..999（表容量 1100），
        // mod.a 显式占第 1000 位（首个注入位），mod.b -1 自动分配 ⇒ 错开到下一空闲位 1001。
        val baseNames = (0..999).associate { "B$it" to it }
        val snapshot = DeclarationRegistry(
            csvTableCapacities = mapOf("CFLAG" to 1100),
            baseCsvKeywords = mapOf("CFLAG" to baseNames),
        )
            .addPackage(descriptor("mod.a", 1000, "FLAG_A"))
            .addPackage(descriptor("mod.b", -1, "FLAG_B"))
            .freeze()
        val ia = snapshot.csvKeywordIndex("CFLAG", "FLAG_A")!! // REVERSE-PROOF-3: assertNotNull 摘除，登记缺失时此处红
        val ib = snapshot.csvKeywordIndex("CFLAG", "FLAG_B")!!
        assertEquals(1000, ia, "显式下标照占 1000")
        assertEquals(1001, ib, "-1 自动分配必须错开到下一空闲位（不撞显式位）")

        // 装载缝：两包注入位合成同一 plan ⇒ required = 1002，一次扩容到位。
        val plan = ModCsvInjectionPlan.fromSnapshot(
            snapshot,
            baseCapacities = mapOf("CFLAG" to 1000),
            tableToVar = mapOf("CFLAG" to "CFLAG"),
        )
        assertEquals(1002, plan.requiredCapacityOf("CFLAG"), "一次扩容到位（容量 B 按需推导，未越 4096/65536 硬上界）")

        val data = assertNotNull(GameDataLoader.load(temp, plan))
        assertEquals(1002, data.registry.def("CFLAG")!!.dims[0], "列块长一次抬到 1002")
        assertEquals(1002, data.registry.csvTable("CFLAG").size, "名字表容量一次抬到 1002")
        assertTrue(data.warnings.isEmpty(), "合法双包注入不得有告警，实得 ${data.warnings}")

        // 值不串位：两关键字各自映射自己的注入位；关键字反查与物理位一致 ⇒
        // 第三方按关键字访问时不会拿到对方的值。
        assertEquals(ia, data.registry.csvTable("CFLAG").keywordIndex("FLAG_A"))
        assertEquals(ib, data.registry.csvTable("CFLAG").keywordIndex("FLAG_B"))
        val store = VariableStore(registry = data.registry)
        val def = data.registry.def("CFLAG")!!
        store.characters.int1[def.index].set(0, ia, 7L)
        store.characters.int1[def.index].set(0, ib, 9L)
        assertEquals(7L, store.characters.int1[def.index].get(0, ia), "mod.a 的注入位值不被 mod.b 串写")
        assertEquals(9L, store.characters.int1[def.index].get(0, ib), "mod.b 的注入位值不被 mod.a 串写")
    }

    /** alias 不新造位（裁定 6）与 C_INT2 整表排除（裁定 5）在双包声明下仍成立。 */
    @Test
    fun `边界冻结裁定_alias不新造位与C_INT2排除在双包下仍成立`() {
        // mod.c 的 alias 指向 mod.a 的显式位：alias 进反查表但不进注入计划、不新造位。
        val snapshot = DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to 20))
            .addPackage(descriptor("mod.a", 10, "FLAG_A"))
            .addPackage(descriptor("mod.c", 10, "ALIAS_A", isAlias = true))
            .freeze()
        assertEquals(10, snapshot.csvKeywordIndex("CFLAG", "ALIAS_A"), "alias 指向目标位")
        val plan = ModCsvInjectionPlan.fromSnapshot(
            snapshot,
            baseCapacities = mapOf("CFLAG" to 10),
            tableToVar = mapOf("CFLAG" to "CFLAG"),
        )
        assertEquals(11, plan.requiredCapacityOf("CFLAG"), "alias 不算注入位 ⇒ required 只由显式位决定")

        // C_INT2（CDFLAG）双包声明：整表排除在装载缝（GameDataLoader 扩容相位，裁定 5）
        // —— 计划层收位，扩容/登记相位按变量类别拒绝。
        val cint2 = ModCsvInjectionPlan.of(
            baseCapacities = mapOf("CDFLAG" to 1),
            declared = mapOf(
                "CDFLAG1" to mapOf(
                    "K_A" to com.eratw.port.core.patch.CsvKeywordBinding("K_A", 5, "mod.a", alias = false),
                    "K_B" to com.eratw.port.core.patch.CsvKeywordBinding("K_B", 6, "mod.b", alias = false),
                ),
            ),
            tableToVar = mapOf("CDFLAG1" to "CDFLAG"),
        )
        val data = assertNotNull(GameDataLoader.load(temp, cint2))
        assertEquals(listOf(1, 1), data.registry.def("CDFLAG")!!.dims.toList(), "C_INT2 不扩容（裁定 5）")
        assertNull(data.registry.csvTable("CDFLAG1").keywordIndex("K_A"), "C_INT2 关键字不登记")
        assertTrue(
            data.warnings.any { it.contains("二维变量") },
            "双包 C_INT2 仍逐表警告，实得 ${data.warnings}",
        )
    }
}
