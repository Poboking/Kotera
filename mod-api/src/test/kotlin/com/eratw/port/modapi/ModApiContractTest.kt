package com.eratw.port.modapi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * :mod-api 契约形状闸（K1，era-arch-v61u.md §18）。
 *
 * 本模块零 core 依赖，闸只验 facade 侧**有逻辑**的面：值类型语义、声明形态的边界校验、
 * 枚举顺序。各 facade 接口的可实现性由 :core-vm 侧适配器（KernelEntityApi/NativeFunctionApi/
 * ModDeclarationAdapter）在编译期证明——适配器 implements 这些接口即形状闸。
 */
class ModApiContractTest {

    @Test
    fun `EntityId 空值与包装`() {
        assertTrue(EntityId.NULL.isNull)
        assertEquals(0L, EntityId.NULL.raw)
        val id = EntityId(123L)
        assertFalse(id.isNull)
        assertEquals(123L, id.raw)
        assertEquals("EntityId(NULL)", EntityId.NULL.toString())
        assertEquals("EntityId(123)", id.toString())
    }

    @Test
    fun `EntityState 三态`() {
        assertEquals(listOf("ACTIVE", "INACTIVE", "RETIRED"), EntityState.entries.map { it.name })
    }

    @Test
    fun `EventBucket 四桶序映射引擎 EventRegistry`() {
        assertEquals(listOf("ONLY", "PRI", "NORMAL", "LATER"), EventBucket.entries.map { it.name })
    }

    @Test
    fun `ModManifest 空 packageId 拒绝`() {
        assertThrows<IllegalArgumentException> { ModManifest(packageId = "") }
        assertThrows<IllegalArgumentException> { ModManifest(packageId = "  ") }
    }

    @Test
    fun `声明形态非空校验`() {
        assertThrows<IllegalArgumentException> { ComponentDecl(name = "") }
        assertThrows<IllegalArgumentException> { FieldDecl(name = " ") }
        assertThrows<IllegalArgumentException> { ArchetypeDecl(name = "") }
        assertThrows<IllegalArgumentException> { RelationshipDecl(name = "") }
        assertThrows<IllegalArgumentException> { GlobalDimDecl(name = "") }
        assertThrows<IllegalArgumentException> { CsvKeywordDecl(table = "", index = 0, keyword = "K") }
        assertThrows<IllegalArgumentException> { CsvKeywordDecl(table = "T", index = 0, keyword = "") }
        assertThrows<IllegalArgumentException> { CsvValueDecl(table = "T", row = "", column = "C", op = CsvOp.ADD, value = "v") }
    }

    @Test
    fun `ModManifest 五域默认空且可装配`() {
        val manifest = ModManifest(
            packageId = "demo.mod",
            version = "1.0",
            components = listOf(ComponentDecl("Stats", listOf(FieldDecl("hp", FieldType.INT)))),
            archetypes = listOf(ArchetypeDecl("Child", listOf("Stats"))),
            relationships = listOf(RelationshipDecl("PARENT")),
            csvKeywords = listOf(CsvKeywordDecl("CFLAG", -1, "MY_FLAG")),
            csvValues = listOf(CsvValueDecl("ITEM", "row0", "NAME", CsvOp.REPLACE, "新名字")),
            globalDims = listOf(GlobalDimDecl("MOD_STATE", DimType.INT)),
            entityRefs = setOf("MOD_STATE"),
        )
        assertEquals("demo.mod", manifest.packageId)
        assertEquals(1, manifest.components.size)
        assertEquals(1, manifest.archetypes.size)
        assertEquals(1, manifest.relationships.size)
        assertEquals(1, manifest.csvKeywords.size)
        assertEquals(1, manifest.csvValues.size)
        assertEquals(1, manifest.globalDims.size)
        assertEquals(setOf("MOD_STATE"), manifest.entityRefs)
        // 缺省全空（不声明任何域也合法）。
        val empty = ModManifest(packageId = "empty.mod")
        assertTrue(empty.components.isEmpty())
        assertTrue(empty.globalDims.isEmpty())
        assertTrue(empty.entityRefs.isEmpty())
    }

    @Test
    fun `ModCallArg 出参可写`() {
        val arg = ModCallArg(isString = false, intValue = 1L)
        arg.intValue = 42L
        assertEquals(42L, arg.intValue)
        val strArg = ModCallArg(isString = true, strValue = "a")
        strArg.strValue = "b"
        assertEquals("b", strArg.strValue)
    }

    @Test
    fun `HookPhase 与 HookResult 形状`() {
        assertEquals(listOf("PREFIX", "POSTFIX"), HookPhase.entries.map { it.name })
        assertEquals(HookResult.Continue, HookResult.Continue)
        assertEquals(ModValue.of(1L), HookResult.Replace(ModValue.of(1L)).value)
        assertEquals(ModValue.IntValue(1L), ModValue.of(1L))
        assertEquals(ModValue.StrValue("x"), ModValue.of("x"))
    }

    @Test
    fun `InteractionPhase 五阶段序`() {
        assertEquals(
            listOf("PRECONDITION", "PRE", "MAIN", "POST", "SETTLEMENT"),
            InteractionPhase.entries.map { it.name },
        )
        assertEquals(
            listOf("CONTINUE", "INTERRUPT", "REPLACE"),
            InteractionResult.entries.map { it.name },
        )
    }

    @Test
    fun `扩展声明形态非空与边界校验`() {
        assertThrows<IllegalArgumentException> { HookDecl(targetKey = "", phase = HookPhase.PREFIX) }
        assertThrows<IllegalArgumentException> {
            HookDecl(targetKey = "COM300", phase = HookPhase.PREFIX, priority = -1)
        }
        assertThrows<IllegalArgumentException> { PhaseDecl(name = " ") }
        assertThrows<IllegalArgumentException> { JobDecl(jobId = "", phase = "DAY_END") }
        assertThrows<IllegalArgumentException> { JobDecl(jobId = "j", phase = "DAY_END", everyDays = 0) }
        assertThrows<IllegalArgumentException> { JobDecl(jobId = "j", phase = "DAY_END", firstDay = -1) }
        assertThrows<IllegalArgumentException> { InteractionHookDecl(InteractionPhase.PRE, priority = -1) }
        assertThrows<IllegalArgumentException> {
            InteractionContext(interactionId = "", actor = EntityId(1L), targets = emptyList())
        }
    }

    @Test
    fun `ModManifest 扩展域默认空`() {
        val manifest = ModManifest(packageId = "x")
        assertTrue(manifest.hooks.isEmpty())
        assertTrue(manifest.interactionHooks.isEmpty())
        assertTrue(manifest.phases.isEmpty())
        assertTrue(manifest.jobs.isEmpty())
        assertTrue(manifest.charaRoutes.isEmpty(), "M5-4 刀 1：charaRoutes 缺省空（既有载体零声明）")
        assertEquals(listOf("DAY_START", "DAY_END", "ACTION"), BuiltinPhase.ALL.toList())
    }

    @Test
    fun `ModDeclarations 维长与 index 边界校验`() {
        // 负/零维长在声明构造期即拒：core 侧 dimProduct 同判据（负维 = CodeEE 拒分配），
        // 而 manifest global dims 今天零生产消费者，不在构造期拒 = 坏声明静默进冻结快照。
        assertThrows<IllegalArgumentException> { GlobalDimDecl(name = "X", dims = listOf(-5)) }
        assertThrows<IllegalArgumentException> { GlobalDimDecl(name = "X", dims = listOf(2, 0)) }
        assertThrows<IllegalArgumentException> { PrivateDimDecl(name = "X", dims = listOf(-1)) }
        assertThrows<IllegalArgumentException> { PrivateDimDecl(name = "X", dims = listOf(3, 0)) }
        // csv 关键字 index：只许 -1（自动分配）或非负下标（与 core CsvKeywordPatch 同判据）。
        assertThrows<IllegalArgumentException> { CsvKeywordDecl(table = "CFLAG", index = -5, keyword = "K") }
        // 合法形态不受影响：标量（dims 空）、正维长、index=-1 自动分配。
        GlobalDimDecl(name = "X")
        GlobalDimDecl(name = "X", dims = listOf(2, 3))
        PrivateDimDecl(name = "X", dims = listOf(4))
        CsvKeywordDecl(table = "CFLAG", index = -1, keyword = "K")
    }

    @Test
    fun `导出宏声明构造期校验`() {
        // 宏域没有 *Decl 载体（String→String map）：重复信息在 DSL 收集处就被 LinkedHashMap
        // 覆盖吃掉，core 声明事务无从闸起；空白宏名透传 manifest / 线格式 / 指纹全链无闸
        //（生产代码零编译期消费者，与 global dims 同型）——唯一闸位 = 声明构造期。
        assertThrows<IllegalArgumentException> { modDeclarations("m") { exportedMacro("", "1") } }
        assertThrows<IllegalArgumentException> { modDeclarations("m") { exportedMacro(" ", "1") } }
        // 同包重复声明 = 笔误，静默末位生效不可接受（与 settings/writeGrants 键唯一同口径）。
        assertThrows<IllegalArgumentException> {
            modDeclarations("m") {
                exportedMacro("MAX_HP", "999")
                exportedMacro("MAX_HP", "1000")
            }
        }
        assertThrows<IllegalArgumentException> {
            modDeclarations("m") {
                exportedMacro("M", "1")
                exportedMacro("M", "1")
            }
        }
        // 手写 ModManifest 路径同闸：键唯一在 manifest 层不可查（Map 已去重），空白键两层都拒。
        assertThrows<IllegalArgumentException> {
            ModManifest(packageId = "m", exportedMacros = mapOf("" to "1"))
        }
        assertThrows<IllegalArgumentException> {
            ModManifest(packageId = "m", exportedMacros = mapOf(" " to "1"))
        }
        // 合法形态不受影响：不同名宏照常收进 manifest。
        val manifest = modDeclarations("m") {
            exportedMacro("MAX_HP", "999")
            exportedMacro("GREETING", "hello")
        }
        assertEquals(mapOf("MAX_HP" to "999", "GREETING" to "hello"), manifest.exportedMacros)
    }

    @Test
    fun `CharaRouteDecl 非空与格下标校验`() {
        assertThrows<IllegalArgumentException> { CharaRouteDecl(variable = "") }
        assertThrows<IllegalArgumentException> { CharaRouteDecl(variable = " ") }
        assertThrows<IllegalArgumentException> { CharaRouteDecl(variable = "CFLAG", cells = listOf(1, -2)) }
        assertThrows<IllegalArgumentException> { CharaRouteDecl(variable = "CFLAG", cells = listOf(3, 3)) }
        // 合法两档：全跨度（cells 空）与显式格。
        assertEquals(emptyList(), CharaRouteDecl("CFLAG").cells)
        assertEquals(listOf(3, 7), CharaRouteDecl("CFLAG", listOf(3, 7)).cells)
        val manifest = ModManifest(packageId = "m", charaRoutes = listOf(CharaRouteDecl("CFLAG")))
        assertEquals(1, manifest.charaRoutes.size)
    }

    @Test
    fun `可挂起剧情构建器线性展开且节点 id 全剧情唯一`() {
        val visited = mutableListOf<Int>()
        val story = durableStory {
            say("opening")
            choose("pick", listOf("left", "right")) { option ->
                visited += option
                say("branch.$option")
                choose("nested", listOf("a", "b")) { nested -> say("nested.$nested") }
            }
        }
        assertEquals(listOf(0, 1), visited)
        val ids = mutableListOf<Int>()
        fun collect(nodes: List<DurableStoryNode>) {
            nodes.forEach { node ->
                ids += node.id
                if (node is DurableStoryNode.Choice) node.branches.forEach(::collect)
            }
        }
        collect(story.nodes)
        assertEquals((0 until ids.size).toList(), ids)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `可挂起剧情构建器拒绝非法文案键与选择形状`() {
        assertThrows<IllegalArgumentException> { durableStory { say("UPPER") } }
        assertThrows<IllegalArgumentException> { durableStory { say("") } }
        assertThrows<IllegalArgumentException> { durableStory { choose("pick", listOf("only")) {} } }
        assertThrows<IllegalArgumentException> { durableStory { choose("pick", listOf("same", "same")) {} } }
        assertThrows<IllegalArgumentException> { durableStory { choose("pick", listOf("a", "B")) {} } }
    }

    // ------------------------------------------------------------------
    // RT2 车次表 E 车 / P1-6 公开面形状闸（§22.5 / §23.4 E 行）
    // ------------------------------------------------------------------

    @Test
    fun `EntityApi 暴露类型化 EntityRef 存取器且原生四件列入口不减`() {
        val names = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue("getRef" in names, "P1-6 ②：getRef 必须进公开面")
        assertTrue("setRef" in names, "P1-6 ②：setRef 必须进公开面")
        assertTrue(
            names.containsAll(listOf("getInt", "setInt", "getStr", "setStr")),
            "getRef/setRef 只是 INT 列的包装，不得替换掉原生四件列入口",
        )
    }

    @Test
    fun `ModContext 暴露 roster 且返回类型就是投射公开面`() {
        val getter = ModContext::class.java.methods.single { it.name == "getRoster" }
        assertEquals(RosterProjectionApi::class.java, getter.returnType)
    }

    @Test
    fun `RosterProjectionApi 只暴露四件业务入口且不泄漏宿主面`() {
        val names = RosterProjectionApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue(
            names.containsAll(listOf("project", "unproject", "getRosterGeneration", "charIndexOfChecked")),
            "P1-6 ① 四件缺一不可：实际 $names",
        )
        for (leak in listOf(
            "projectedEntities", "syncFromCharaSlot", "onCharacterRowsPermuted",
            "onCharacterRowDeleted", "entityAt", "charIndexOf",
        )) {
            assertFalse(leak in names, "宿主/存档面 $leak 不得进 SDK 公开面")
        }
    }

    @Test
    fun `投射公开面可由 mod 侧实现且四件入口形状可编译`() {
        // 只钉形状与「过期世代返回 -1」的口径；真语义由 :core-vm 适配器 + 真引擎纵向切片证明。
        val api = object : RosterProjectionApi {
            override fun project(id: EntityId): Int = if (id.isNull) -1 else 3
            override fun unproject(id: EntityId): Boolean = !id.isNull
            override val rosterGeneration: Long get() = 7L
            override fun charIndexOfChecked(id: EntityId, expectedGeneration: Long): Int =
                if (expectedGeneration == 7L && !id.isNull) 3 else -1
        }
        val id = EntityId(42L)
        assertEquals(3, api.project(id))
        assertEquals(-1, api.project(EntityId.NULL))
        assertTrue(api.unproject(id))
        assertEquals(7L, api.rosterGeneration)
        assertEquals(3, api.charIndexOfChecked(id, 7L))
        assertEquals(-1, api.charIndexOfChecked(id, 6L), "过期世代必须拒绝")
    }
}