package com.eratw.port.core.vars

import com.eratw.port.core.patch.CsvKeywordBinding
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.save.ModCsvValueCodec
import com.eratw.port.core.save.PackageChunk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RT2 M1-4 阶段一的值层单测：注入位计划形状、采样口径（CSV 番号身份）、
 * 值袋切面、`.rt2` CSV 段编解码与旧裸 `MST1` 载荷嗅探、恢复器的丢弃出口。
 */
class ModCsvValuesTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun planOf(
        base: Int,
        cells: Map<String, Int>,
        packageId: String = "school.mod",
    ): ModCsvInjectionPlan = ModCsvInjectionPlan.of(
        baseCapacities = mapOf("CFLAG" to base),
        declared = mapOf(
            "CFLAG" to cells.mapValues { (keyword, index) ->
                CsvKeywordBinding(keyword, index, packageId, alias = false)
            },
        ),
        tableToVar = mapOf("CFLAG" to "CFLAG"),
    )

    private fun store(csvNos: List<Int>, cflagSize: Int = 16): VariableStore {
        val sizes = VariableSizes().apply { charaInt1D[9] = cflagSize }
        val store = VariableStore(registry = VariableRegistry(sizes = sizes))
        csvNos.forEachIndexed { i, csvNo ->
            store.registerTemplate(
                CharacterTemplate(no = i + 1L, name = "c$csvNo", callname = "c$csvNo", csvNo = csvNo.toLong()),
            )
        }
        for (i in csvNos.indices) store.addCharacter(i + 1L)
        return store
    }

    private fun cflag(store: VariableStore): CharacterTable.LongCol =
        store.characters.int1[store.registry.def("CFLAG")!!.index]

    private fun key(table: String, keyword: String, csvNo: Int, pkg: String = "school.mod"): ModCsvValueKey =
        ModCsvValueKey(pkg, table, keyword, CharIdentity.CsvNo(csvNo))

    // ------------------------------------------------------------------
    // 计划
    // ------------------------------------------------------------------

    @Test
    fun `计划 - 只收非别名且越过 Base 容量的绑定，关键字大小写原样`() {
        val plan = ModCsvInjectionPlan.of(
            baseCapacities = mapOf("cflag" to 100),
            declared = mapOf(
                "CFLAG" to mapOf(
                    "AliasOnly" to CsvKeywordBinding("AliasOnly", 120, "school.mod", alias = true),
                    "BaseKey" to CsvKeywordBinding("BaseKey", 42, "school.mod", alias = false),
                    "ModKey" to CsvKeywordBinding("ModKey", 103, "school.mod", alias = false),
                    "mIxEd" to CsvKeywordBinding("mIxEd", 100, "school.mod", alias = false),
                ),
            ),
            tableToVar = mapOf("CFLAG" to "CFLAG"),
        )
        assertEquals(setOf("CFLAG"), plan.varNames)
        assertNull(plan.cellAt("CFLAG", 120), "alias 绑定不占命名位，不进注入计划")
        assertNull(plan.cellAt("CFLAG", 42), "Base 自己声明的格子不进注入计划")
        assertEquals("ModKey", plan.cellAt("CFLAG", 103)?.keyword)
        assertEquals("mIxEd", plan.cellAt("CFLAG", 100)?.keyword, "关键字保持原文大小写")
        assertEquals("school.mod", plan.cellAt("CFLAG", 103)?.packageId)
        assertEquals(100, plan.baseCapacityOf("CFLAG"))
    }

    @Test
    fun `计划 - 注入位判定与 Base 容量截断查询`() {
        val plan = planOf(base = 4, cells = mapOf("A" to 4, "B" to 5))
        assertFalse(plan.isInjected("CFLAG", 3))
        assertTrue(plan.isInjected("CFLAG", 4))
        assertEquals(4, plan.baseLength("CFLAG", Storage.C_INT1, intArrayOf(16)))
        // 未登记的变量不截断（宁可不抽离也不丢值）。
        assertNull(plan.baseLength("TALENT", Storage.C_INT1, intArrayOf(100)))
        assertFalse(plan.isInjected("TALENT", 50))
    }

    @Test
    fun `计划 - 2 维 Base 形状按显式优先、缺席按扁平守恒推导`() {
        val explicit = ModCsvInjectionPlan.of(
            baseCapacities = mapOf("CDFLAG" to 4),
            declared = emptyMap(),
            tableToVar = emptyMap(),
            baseDims = mapOf("CDFLAG" to intArrayOf(2, 2)),
        )
        assertContentEquals(intArrayOf(2, 2), explicit.baseDimsOf("CDFLAG", intArrayOf(3, 2)))
        val derived = ModCsvInjectionPlan.of(
            baseCapacities = mapOf("CDFLAG" to 4),
            declared = emptyMap(),
            tableToVar = emptyMap(),
        )
        assertContentEquals(intArrayOf(2, 2), derived.baseDimsOf("CDFLAG", intArrayOf(3, 2)))
        assertEquals(4, derived.baseLength("CDFLAG", Storage.C_INT2, intArrayOf(3, 2)))
    }

    @Test
    fun `计划 - fromSnapshot 与 of 同形`() {
        val snapshot = DeclarationSnapshot(
            packageIds = listOf("school.mod"),
            globalDims = emptyMap(),
            privateDims = emptyMap(),
            declaredCsvKeywords = mapOf(
                "CFLAG" to mapOf("ModKey" to CsvKeywordBinding("ModKey", 7, "school.mod", alias = false)),
            ),
            effectiveCsvKeywords = mapOf("CFLAG" to mapOf("ModKey" to 7)),
            csvValueCells = emptyMap(),
            componentSchemas = emptyMap(),
            entityArchetypes = emptyMap(),
            relationshipSchemas = emptyMap(),
            hookDeclarations = emptyList(),
            interactionHookDeclarations = emptyList(),
            phaseDeclarations = emptyList(),
            jobDeclarations = emptyList(),
            jobTemplateDeclarations = emptyList(),
        )
        val fromSnapshot = ModCsvInjectionPlan.fromSnapshot(snapshot, mapOf("CFLAG" to 4), mapOf("CFLAG" to "CFLAG"))
        assertEquals("ModKey", fromSnapshot.cellAt("CFLAG", 7)?.keyword)
        assertEquals(fromSnapshot.varNames, planOf(base = 4, cells = mapOf("ModKey" to 7)).varNames)
    }

    // ------------------------------------------------------------------
    // 采样
    // ------------------------------------------------------------------

    @Test
    fun `采样 - 匿名角色（csvNo 为 0）拒绝并上警告`() {
        val store = store(listOf(101))
        store.addPseudoCharacter()
        val plan = planOf(base = 4, cells = mapOf("ModKey" to 5))
        cflag(store).set(1, 5, 42)
        val warnings = mutableListOf<String>()
        val pairs = ModCsvValueSampler.collectChar(store, plan, 1, warnings::add)
        assertTrue(pairs.isEmpty(), "匿名角色没有稳定身份，v1 不入值袋")
        assertTrue(warnings.single().contains("无 CSV 番号"))
    }

    @Test
    fun `采样 - 只采非默认值，键按 CSV 番号而不是角色位`() {
        val store = store(listOf(101, 102))
        val plan = planOf(base = 4, cells = mapOf("ModKey" to 5, "ZeroKey" to 6))
        cflag(store).set(1, 5, 22)
        val pairs = ModCsvValueSampler.collectChar(store, plan, 1, {})
        assertEquals(1, pairs.size, "零值格不进值袋")
        assertEquals("CFLAG", pairs.single().first.tableId)
        assertEquals("ModKey", pairs.single().first.keyword)
        assertEquals(CharIdentity.CsvNo(102), pairs.single().first.identity, "键按 CSV 番号而不是角色位")
        assertEquals(CsvValue.IntValue(22), pairs.single().second)
    }

    // ------------------------------------------------------------------
    // 值袋 / 编解码
    // ------------------------------------------------------------------

    @Test
    fun `值袋 - byPackage 按包切片`() {
        val bag = ModCsvValueBag.of(
            listOf(
                key("CFLAG", "A", 101) to CsvValue.IntValue(1),
                key("CFLAG", "B", 101, pkg = "legacy.mod") to CsvValue.StrValue("x"),
            ),
        )
        assertEquals(2, bag.size)
        assertEquals(setOf("school.mod", "legacy.mod"), bag.byPackage().keys)
        assertEquals(1, bag.byPackage().getValue("legacy.mod").size)
        assertEquals(CsvValue.StrValue("x"), bag.get(key("CFLAG", "B", 101, pkg = "legacy.mod")))
    }

    @Test
    fun `编解码 - CSV 段往返整数与字符串且关键字大小写不变`() {
        val bag = ModCsvValueBag.of(
            listOf(
                key("CFLAG", "mIxEd", 101) to CsvValue.IntValue(-3),
                key("CFLAG", "Name", 102) to CsvValue.StrValue("こんにちは"),
                ModCsvValueKey("school.mod", "CFLAG", "Ent", CharIdentity.Entity(0x0102030405060708L)) to
                    CsvValue.IntValue(9),
            ),
        )
        val decoded = ModCsvValueCodec.decodeCsv(ModCsvValueCodec.encodeCsv(bag))
        assertEquals(bag.size, decoded.size)
        assertEquals(CsvValue.IntValue(-3), decoded.get(key("CFLAG", "mIxEd", 101)))
        assertEquals(CsvValue.StrValue("こんにちは"), decoded.get(key("CFLAG", "Name", 102)))
        assertEquals(
            CsvValue.IntValue(9),
            decoded.get(
                ModCsvValueKey("school.mod", "CFLAG", "Ent", CharIdentity.Entity(0x0102030405060708L)),
            ),
        )
    }

    @Test
    fun `编解码 - 空袋不包装，字节原样返回`() {
        val storage = byteArrayOf(0x4D, 0x53, 0x54, 0x31, 0, 0, 0, 1)
        val encoded = ModCsvValueCodec.encode(storage, ModCsvValueBag.EMPTY)
        assertContentEquals(storage, encoded)
        val payload = ModCsvValueCodec.decode(encoded)
        assertContentEquals(storage, payload.storage)
        assertTrue(payload.csv.isEmpty)
    }

    @Test
    fun `编解码 - 旧裸 MST1 载荷被嗅探为 storage，csv 段为空`() {
        val legacy = ByteArray(24) { it.toByte() }
        val payload = ModCsvValueCodec.decode(legacy)
        assertContentEquals(legacy, payload.storage)
        assertTrue(payload.csv.isEmpty)
    }

    @Test
    fun `编解码 - 非空袋往返并还原 storage 段`() {
        val storage = ModStorageLikeFixture.bytes()
        val bag = ModCsvValueBag.of(listOf(key("CFLAG", "ModKey", 101) to CsvValue.IntValue(7)))
        val encoded = ModCsvValueCodec.encode(storage, bag)
        assertFalse(encoded.contentEquals(storage), "非空 CSV 段必须包装")
        val payload = ModCsvValueCodec.decode(encoded)
        assertContentEquals(storage, payload.storage)
        assertEquals(CsvValue.IntValue(7), payload.csv.get(key("CFLAG", "ModKey", 101)))
    }

    @Test
    fun `编解码 - mergeApplied 只并激活包`() {
        val activeStorage = ModStorageLikeFixture.bytes()
        val activeChunk = PackageChunk(
            "school.mod",
            ModCsvValueCodec.encode(
                activeStorage,
                ModCsvValueBag.of(listOf(key("CFLAG", "ModKey", 101) to CsvValue.IntValue(7))),
            ),
        )
        val orphanChunk = PackageChunk(
            "legacy.mod",
            ModCsvValueCodec.encode(
                activeStorage,
                ModCsvValueBag.of(listOf(key("CFLAG", "Old", 101, pkg = "legacy.mod") to CsvValue.IntValue(77))),
            ),
        )
        val merged = ModCsvValueCodec.mergeApplied(listOf(activeChunk, orphanChunk), setOf("school.mod"))
        assertEquals(setOf("school.mod"), merged.byPackage().keys, "孤儿包的值不并回变量池")
        assertEquals(CsvValue.IntValue(7), merged.get(key("CFLAG", "ModKey", 101)))
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    @Test
    fun `恢复 - 关键字消失、角色缺席、EntityId 身份都逐条警告丢弃`() {
        val store = store(listOf(101, 102), cflagSize = 8)
        val plan = planOf(base = 4, cells = mapOf("Ok" to 5))
        val bag = ModCsvValueBag.of(
            listOf(
                key("CFLAG", "Ok", 101) to CsvValue.IntValue(5),
                key("CFLAG", "Ok", 102) to CsvValue.IntValue(7),
                key("CFLAG", "Gone", 101) to CsvValue.IntValue(9),
                key("CFLAG", "Ok", 999) to CsvValue.IntValue(3),
                ModCsvValueKey("school.mod", "CFLAG", "Ent", CharIdentity.Entity(0x0102030405060708L)) to
                    CsvValue.IntValue(1),
            ),
        )
        val warnings = mutableListOf<String>()
        ModCsvValueRestorer.apply(
            store, plan,
            { _, keyword -> if (keyword == "Gone") null else 5 },
            bag, warnings::add,
        )
        assertEquals(5, cflag(store).get(0, 5))
        assertEquals(7, cflag(store).get(1, 5), "按 CSV 番号落到各自角色位，不按值袋顺序")
        assertEquals(3, warnings.size, "Gone / 缺席角色 / EntityId 各一条警告: $warnings")
    }

    /** 冒充旧 `MST1` 存储段的字节（前四字节刻意不是 `R2PK`）。 */
    private object ModStorageLikeFixture {
        fun bytes(): ByteArray = byteArrayOf(0x4D, 0x53, 0x54, 0x31, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
