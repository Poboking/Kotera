package com.eratw.port.core.fn

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.DeterministicDeriver
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 实体/角色内置函数族测试（RT2 M4-2 + M4-4：EntityMethods 注册面）。
 *
 * 覆盖契约 §4.2 的 Gate 口径：
 * - 必注册名单逐函数 happy path + arity/类型错误；
 * - Query 截断与溢出（容量不足 = 截断 + 返回负总数）；
 * - 未声明 archetype / 未声明关系 kind ⇒ CodeEE；
 * - 元数据完整性（注册表逐条点名：存在、非占位、返回类型、参数边界）；
 * - 未装配语义分组（CodeEE 组 vs 合法旁路组，逐函数钉住）。
 *
 * 装置 = 真 EntityKernel + 真 PackageShortIdRegistry + fake 投射桥 + fake 目录
 * （本车不做生产装配，见 contract §4.4）。
 */
private class EmBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("EmBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 最小投射桥假件：行号按登场序分配，世代手工拨。 */
private class EmFakeProjector : ICharaSlotProjector {
    val rows = LinkedHashMap<Long, Int>()
    var generation: Long = 0

    override fun project(entityId: Long): Int =
        rows[entityId] ?: rows.size.also { rows[entityId] = it }

    override fun unproject(entityId: Long): Boolean = rows.remove(entityId) != null

    override fun charIndexOf(entityId: Long): Int = rows[entityId] ?: -1

    override val rosterGeneration: Long get() = generation

    override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int =
        if (generation == expectedGeneration) rows[entityId] ?: -1 else -1

    override fun entityAt(charIndex: Int): Long =
        rows.entries.firstOrNull { it.value == charIndex }?.key ?: EntityIdCodec.NULL

    override fun projectedEntities(): LongArray = rows.keys.toLongArray()

    override fun syncFromCharaSlot(entityId: Long) {}

    override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) {}

    override fun onCharacterRowDeleted(deletedIndex: Int) {}

    override fun onWorldReset() {
        rows.clear()
        generation++
    }
}

/** EntityRuntime 假件：内核/注册表/声明查表真件注入，目录与激活判定按用例给。 */
private class EmRuntime(
    override val kernel: IEntityKernel,
    override val registry: PackageShortIdRegistry,
    override val archetypes: (String) -> DeclaredArchetype?,
    override val projector: ICharaSlotProjector? = null,
    override val catalog: SymbolCatalog? = null,
    override val modIsActive: (String) -> Boolean = { false },
) : EntityRuntime

/** 声明装置：包 modA + schema body(HP/NAME) + 关系 PARENT + 原型 CHARA/ITEM。 */
private class EmDecl {
    val registry = PackageShortIdRegistry()
    val kernel: EntityKernel
    val archProvider: (String) -> DeclaredArchetype?
    val modShortId: Int

    init {
        modShortId = registry.allocate("modA")
        val archChara = EntityArchetype("CHARA", listOf("body"))
        val archItem = EntityArchetype("ITEM", listOf("body"))
        kernel = EntityKernel(
            schemas = listOf(
                ComponentSchema(
                    "modA", "body",
                    listOf(
                        ComponentFieldSpec("HP"),
                        ComponentFieldSpec("NAME", ComponentFieldType.STR),
                    ),
                ),
            ),
            relationshipSchemas = listOf(RelationshipSchema("PARENT")),
            archetypes = listOf(archChara, archItem),
        )
        archProvider = { name ->
            when {
                name.equals("CHARA", ignoreCase = true) -> DeclaredArchetype("modA", archChara)
                name.equals("ITEM", ignoreCase = true) -> DeclaredArchetype("modA", archItem)
                else -> null
            }
        }
    }

    fun runtime(
        projector: ICharaSlotProjector? = null,
        catalog: SymbolCatalog? = null,
        modIsActive: (String) -> Boolean = { false },
    ): EmRuntime = EmRuntime(kernel, registry, archProvider, projector, catalog, modIsActive)
}

private fun emEnv(rt: EntityRuntime?): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = EmBaseCtx()
    base.variables = store
    val host = object : BuiltinHost { override val entityRuntime: EntityRuntime? get() = rt }
    return BuiltinEvalContext(base, store, host) to store
}

/** 值调用：Long/Int → IntV，String → StrV。 */
private fun emCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunction(
        name,
        args.map {
            when (it) {
                is Long -> Value.IntV(it)
                is Int -> Value.IntV(it.toLong())
                is String -> Value.StrV(it)
                is Value -> it
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun emIv(v: Value): Long = (v as Value.IntV).v

private fun emSv(v: Value): String = (v as Value.StrV).v

/** 建一个 CHARA 实体（声明包 modA）。 */
private fun EmDecl.mkChara(ctx: BuiltinEvalContext): Long = emIv(emCall(ctx, "ENTITY_CREATE", "CHARA"))

class EntityMethodsTest {

    // ==================================================================
    // 元数据完整性（Gate：漏一条即红，逐条点名）
    // ==================================================================

    /** 名单口径：name → (返回类型, minArgs, maxArgs)。大小写与 contract §3 冻结名单一致。 */
    private val emExpected: List<Triple<String, Triple<OperandType, Int, Int>, Int>> = listOf(
        Triple("ENTITY_CREATE", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_DESTROY", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_EXISTS", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_GET_STATE", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_SET_STATE", Triple(OperandType.INT, 2, 2), 0),
        Triple("ENTITY_GET_INT", Triple(OperandType.INT, 2, 2), 0),
        Triple("ENTITY_SET_INT", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_GET_STR", Triple(OperandType.STR, 2, 2), 0),
        Triple("ENTITY_SET_STR", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_ADD_REL", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_DEL_REL", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_HAS_REL", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_QUERY_COUNT", Triple(OperandType.INT, 2, 2), 0),
        Triple("ENTITY_QUERY_FILL", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_QUERY_REL_COUNT", Triple(OperandType.INT, 3, 3), 0),
        Triple("ENTITY_QUERY_REL_FILL", Triple(OperandType.INT, 4, 4), 0),
        Triple("ENTITY_PROJECT_CHARA", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_UNPROJECT_CHARA", Triple(OperandType.INT, 1, 1), 0),
        Triple("ENTITY_TO_CHARA", Triple(OperandType.INT, 1, 2), 0),
        Triple("CHARA_TO_ENTITY", Triple(OperandType.INT, 1, 1), 0),
        Triple("ROSTER_GENERATION", Triple(OperandType.INT, 0, 0), 0),
        Triple("MOD_IS_ACTIVE", Triple(OperandType.INT, 1, 1), 0),
        Triple("HAS_SUPER", Triple(OperandType.INT, 0, 0), 0),
        Triple("ENTITY_RAND", Triple(OperandType.INT, 2, 2), 0),
    ).map { (name, meta, _) -> Triple(name, meta, 0) }

    @Test
    fun `元数据完整性 - 必注册名单逐条点名`() {
        for ((name, meta, _) in emExpected) {
            val (ret, minA, maxA) = meta
            val m = Builtins.registry[name]
            assertTrue(m != null, "$name 未注册")
            assertTrue(!Builtins.registry.isPlaceholder(name), "$name 仍是占位")
            assertEquals(ret, m!!.retType, "$name 返回类型")
            assertEquals(minA, m.minArgs, "$name minArgs")
            assertEquals(maxA, m.maxArgs, "$name maxArgs")
        }
    }

    @Test
    fun `元数据完整性 - arity 闸逐函数`() {
        val (ctx, _) = emEnv(EmDecl().runtime())
        for ((name, meta, _) in emExpected) {
            val (ret, minA, maxA) = meta
            // 少于 minArgs：填充占位实参（数字）后仍按个数闸报错（minArgs=0 时无「少参」形态）
            if (minA > 0) {
                val tooFew = Array(minA - 1) { 1L }
                assertFailsWith<CodeEE>("$name 少参应报 CodeEE") { emCall(ctx, name, *tooFew) }
            }
            // 多于 maxArgs
            val tooMany = Array(maxA + 1) { 1L }
            assertFailsWith<CodeEE>("$name 多参应报 CodeEE") { emCall(ctx, name, *tooMany) }
        }
    }

    /**
     * 类型闸逐函数（contract §4.2「arity/类型错误」）：
     * 每个函数的实参按名单签名取**全错型**（INT 位给字符串、STR 位给整数），
     * 首参校验即 CodeEE。无参函数（ROSTER_GENERATION / HAS_SUPER）无类型面，跳过。
     * 装置用已装配 + 含投射桥的 runtime，避免「无投射」旁路抢在类型校验之前返回读数。
     */
    @Test
    fun `类型闸 - 逐函数错型实参 CodeEE`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime(EmFakeProjector()))
        // I = INT 形参、S = STR 形参（与 contract §3 名单逐字对应）
        val sig = listOf(
            "ENTITY_CREATE" to "S",
            "ENTITY_DESTROY" to "I",
            "ENTITY_EXISTS" to "I",
            "ENTITY_GET_STATE" to "I",
            "ENTITY_SET_STATE" to "II",
            "ENTITY_GET_INT" to "IS",
            "ENTITY_SET_INT" to "ISI",
            "ENTITY_GET_STR" to "IS",
            "ENTITY_SET_STR" to "ISS",
            "ENTITY_ADD_REL" to "ISI",
            "ENTITY_DEL_REL" to "ISI",
            "ENTITY_HAS_REL" to "ISI",
            "ENTITY_QUERY_COUNT" to "SI",
            "ENTITY_QUERY_FILL" to "SIS",
            "ENTITY_QUERY_REL_COUNT" to "ISI",
            "ENTITY_QUERY_REL_FILL" to "ISIS",
            "ENTITY_PROJECT_CHARA" to "I",
            "ENTITY_UNPROJECT_CHARA" to "I",
            "ENTITY_TO_CHARA" to "II",
            "CHARA_TO_ENTITY" to "I",
            "MOD_IS_ACTIVE" to "S",
            "ENTITY_RAND" to "II",
        )
        assertEquals(22, sig.size, "类型闸应覆盖除两个无参函数外的全部 22 个函数")
        for ((name, types) in sig) {
            val wrong: Array<Any?> = Array(types.length) {
                if (types[it] == 'I') Value.StrV("x") else Value.IntV(1L)
            }
            assertFailsWith<CodeEE>("$name 错型实参应报 CodeEE") { emCall(ctx, name, *wrong) }
        }
        // 无参函数给任意实参 = 个数闸报错（类型面不存在）
        assertFailsWith<CodeEE> { emCall(ctx, "ROSTER_GENERATION", 1L) }
        assertFailsWith<CodeEE> { emCall(ctx, "HAS_SUPER", 1L) }
    }
    // ==================================================================
    // 未装配语义分组（裁定 3）
    // ==================================================================

    @Test
    fun `未装配 - CodeEE 组逐函数钉住`() {
        val (ctx, store) = emEnv(null)
        store.declareUserVar(DimSpec("ARR", isStr = false, dims = intArrayOf(4)))
        // 借装配语境造一个合法 id（本 ctx 无装配，只用于断言 CodeEE）
        val decl = EmDecl()
        val (okCtx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(okCtx)

        val codeeeCalls: List<Pair<String, Array<Any>>> = listOf(
            "ENTITY_CREATE" to arrayOf("CHARA"),
            "ENTITY_DESTROY" to arrayOf(id),
            "ENTITY_EXISTS" to arrayOf(id),
            "ENTITY_GET_STATE" to arrayOf(id),
            "ENTITY_SET_STATE" to arrayOf(id, 2),
            "ENTITY_GET_INT" to arrayOf(id, "HP"),
            "ENTITY_SET_INT" to arrayOf(id, "HP", 5),
            "ENTITY_GET_STR" to arrayOf(id, "NAME"),
            "ENTITY_SET_STR" to arrayOf(id, "NAME", "x"),
            "ENTITY_ADD_REL" to arrayOf(id, "PARENT", id),
            "ENTITY_DEL_REL" to arrayOf(id, "PARENT", id),
            "ENTITY_HAS_REL" to arrayOf(id, "PARENT", id),
            "ENTITY_QUERY_COUNT" to arrayOf("CHARA", 0),
            "ENTITY_QUERY_FILL" to arrayOf("CHARA", 0, "ARR"),
            "ENTITY_QUERY_REL_COUNT" to arrayOf(id, "PARENT", 0),
            "ENTITY_QUERY_REL_FILL" to arrayOf(id, "PARENT", 0, "ARR"),
            "ENTITY_PROJECT_CHARA" to arrayOf(id),
            "ENTITY_UNPROJECT_CHARA" to arrayOf(id),
            "ENTITY_RAND" to arrayOf(id, 0),
        )
        for ((fn, args) in codeeeCalls) {
            val e = assertFailsWith<CodeEE>("$fn 未装配应 CodeEE") {
                emCall(ctx, fn, *args)
            }
            assertTrue(e.message!!.contains("实体运行时未装配"), "$fn 文案不符：${e.message}")
        }
    }

    @Test
    fun `未装配 - 合法旁路组逐函数钉住`() {
        val (ctx, _) = emEnv(null)
        // 「无投射/未装配」是事实：MOD_IS_ACTIVE 0、HAS_SUPER 0、ROSTER_GENERATION 0、
        // ENTITY_TO_CHARA -1、CHARA_TO_ENTITY 0（裁定 3）
        assertEquals(0L, emIv(emCall(ctx, "MOD_IS_ACTIVE", "modA")))
        assertEquals(0L, emIv(emCall(ctx, "HAS_SUPER")))
        assertEquals(0L, emIv(emCall(ctx, "ROSTER_GENERATION")))
        assertEquals(-1L, emIv(emCall(ctx, "ENTITY_TO_CHARA", 1L)))
        // 两参变体同属「无投射」事实 ⇒ -1（裁定 3 只给 -1，不给 0）
        assertEquals(-1L, emIv(emCall(ctx, "ENTITY_TO_CHARA", 1L, 0L)))
        assertEquals(0L, emIv(emCall(ctx, "CHARA_TO_ENTITY", 0L)))
    }

    // ==================================================================
    // 生命周期族 happy path
    // ==================================================================

    @Test
    fun `ENTITY_CREATE - 归属包取 archetype 声明包`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(ctx)
        assertTrue(id > 0, "应返回合法 EntityId：$id")
        assertEquals(decl.modShortId, EntityIdCodec.pkgShortIdOf(id), "归属包 = 声明包的短 ID")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_EXISTS", id)))
    }

    @Test
    fun `ENTITY_CREATE - 未声明原型 CodeEE`() {
        val (ctx, _) = emEnv(EmDecl().runtime())
        val e = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_CREATE", "GHOST") }
        assertTrue(e.message!!.contains("未声明"), "实际：${e.message}")
        // 类型错误：整数实参
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_CREATE", 1L) }
    }

    @Test
    fun `ENTITY_DESTROY - 真删后存在判否`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(ctx)
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_DESTROY", id)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_EXISTS", id)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_DESTROY", id)), "重复销毁返回 0")
    }

    @Test
    fun `ENTITY_GET_SET_STATE - 三态读写`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(ctx)
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_GET_STATE", id)), "初始 ACTIVE")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_SET_STATE", id, 2)))
        assertEquals(2L, emIv(emCall(ctx, "ENTITY_GET_STATE", id)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_SET_STATE", id, 9)), "非法 state 返回 0")
        // 悬空/复用引用：generation 段错配 + 槽仍在界内 ⇒ 内核 slotOf 全位域比对落空 ⇒ 0
        assertEquals(
            0L,
            emIv(emCall(ctx, "ENTITY_GET_STATE", EntityIdCodec.encode(decl.modShortId, 1, 0))),
            "悬空引用（generation 错配）0",
        )
    }

    // ==================================================================
    // 字段族 happy path
    // ==================================================================

    @Test
    fun `ENTITY_GET_SET_INT_STR - 字段读写与失败哨兵`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(ctx)
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_SET_INT", id, "HP", 100L)))
        assertEquals(100L, emIv(emCall(ctx, "ENTITY_GET_INT", id, "HP")))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_SET_INT", id, "NOPE", 1L)), "未注册字段 0")
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_GET_INT", id, "NAME")), "类型不符读 0")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_SET_STR", id, "NAME", "爱丽丝")))
        assertEquals("爱丽丝", emSv(emCall(ctx, "ENTITY_GET_STR", id, "NAME")))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_SET_STR", id, "HP", "x")), "未注册字段 0")
    }

    // ==================================================================
    // 关系族 happy path
    // ==================================================================

    @Test
    fun `ENTITY_ADD_DEL_HAS_REL - 有向边生命周期`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val a = decl.mkChara(ctx)
        val b = decl.mkChara(ctx)
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_ADD_REL", a, "PARENT", b)))
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_HAS_REL", a, "PARENT", b)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_HAS_REL", b, "PARENT", a)), "有向边反向判否")
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_ADD_REL", a, "PARENT", b)), "dedupe 命中 0")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_DEL_REL", a, "PARENT", b)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_HAS_REL", a, "PARENT", b)))
    }

    @Test
    fun `关系族 - 未声明 kind CodeEE`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val a = decl.mkChara(ctx)
        for (fn in listOf("ENTITY_ADD_REL", "ENTITY_DEL_REL", "ENTITY_HAS_REL")) {
            val e = assertFailsWith<CodeEE>("$fn 未声明 kind 应 CodeEE") {
                emCall(ctx, fn, a, "CHILD", a)
            }
            assertTrue(e.message!!.contains("未声明"), "$fn 文案不符：${e.message}")
        }
    }

    // ==================================================================
    // Query 族（M4-4）
    // ==================================================================

    @Test
    fun `ENTITY_QUERY_COUNT - 数量与 state 过滤`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val a = decl.mkChara(ctx)
        val b = decl.mkChara(ctx)
        decl.mkChara(ctx)
        assertEquals(3L, emIv(emCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 0)))
        assertEquals(3L, emIv(emCall(ctx, "ENTITY_QUERY_COUNT", "chara", 0)), "原型名大小写不敏感")
        emCall(ctx, "ENTITY_SET_STATE", a, 2)
        assertEquals(2L, emIv(emCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 1)), "state 过滤")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 2)))
        emCall(ctx, "ENTITY_DESTROY", b)
        assertEquals(2L, emIv(emCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 0)), "销毁后不计（剩 state=2 的 a 与 state=1 的第三个）")
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_COUNT", "GHOST", 0) }
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 9) }
    }

    @Test
    fun `ENTITY_QUERY_FILL - 容量足够返回正数`() {
        val decl = EmDecl()
        val (ctx, store) = emEnv(decl.runtime())
        repeat(3) { decl.mkChara(ctx) }
        store.declareUserVar(DimSpec("ARR", isStr = false, dims = intArrayOf(4)))
        val written = emIv(emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "ARR"))
        assertEquals(3L, written, "实际写入数")
        val ids = rtQueryAll(decl)
        for (i in 0 until 3) {
            assertEquals(ids[i], store.readInt("ARR", longArrayOf(i.toLong())), "第 $i 位")
        }
        assertEquals(0L, store.readInt("ARR", longArrayOf(3)), "第 4 位未被触碰")
        // 未声明原型 ⇒ CodeEE（fail-closed，裁定 1；先于数组解析）
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "GHOST", 0, "ARR") }
    }

    @Test
    fun `ENTITY_QUERY_FILL - 容量不足截断并返回负总数`() {
        val decl = EmDecl()
        val (ctx, store) = emEnv(decl.runtime())
        repeat(3) { decl.mkChara(ctx) }
        store.declareUserVar(DimSpec("ARR", isStr = false, dims = intArrayOf(2)))
        val written = emIv(emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "ARR"))
        assertEquals(-3L, written, "容量不足 = 返回负总数示溢出")
        // 截断写入：前 2 位有值，取自完整结果的前缀
        val ids = rtQueryAll(decl)
        assertEquals(ids[0], store.readInt("ARR", longArrayOf(0)))
        assertEquals(ids[1], store.readInt("ARR", longArrayOf(1)))
    }

    @Test
    fun `ENTITY_QUERY_FILL - 数组名解析走目标帧且错误带数组名`() {
        val decl = EmDecl()
        val (ctx, store) = emEnv(decl.runtime())
        decl.mkChara(ctx)
        // 拼错（未定义）
        val e1 = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "NOPE") }
        assertTrue(e1.message!!.contains("NOPE"), "拼错带数组名：${e1.message}")
        // 非数组（0 维标量）
        store.declareUserVar(DimSpec("SCALAR", isStr = false, dims = intArrayOf()))
        val e2 = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "SCALAR") }
        assertTrue(e2.message!!.contains("SCALAR"), "非数组带数组名：${e2.message}")
        // 维度不符（二维）
        store.declareUserVar(DimSpec("TWO_D", isStr = false, dims = intArrayOf(4, 4)))
        val e3 = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "TWO_D") }
        assertTrue(e3.message!!.contains("TWO_D"), "维度不符带数组名：${e3.message}")
        // 非整型（一维字符串数组）
        store.declareUserVar(DimSpec("STR_ARR", isStr = true, dims = intArrayOf(4)))
        val e4 = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, "STR_ARR") }
        assertTrue(e4.message!!.contains("STR_ARR"), "非整型带数组名：${e4.message}")
        // 类型错误：第 3 参给整数
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_FILL", "CHARA", 0, 1L) }
    }

    @Test
    fun `ENTITY_QUERY_REL_COUNT_FILL - dir 0 出边 1 入边`() {
        val decl = EmDecl()
        val (ctx, store) = emEnv(decl.runtime())
        val a = decl.mkChara(ctx)
        val b = decl.mkChara(ctx)
        emCall(ctx, "ENTITY_ADD_REL", a, "PARENT", b)
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_QUERY_REL_COUNT", a, "PARENT", 0)), "出边")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_QUERY_REL_COUNT", b, "PARENT", 1)), "入边")
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_QUERY_REL_COUNT", b, "PARENT", 0)))
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_REL_COUNT", a, "PARENT", 2) }
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_QUERY_REL_COUNT", a, "CHILD", 0) }
        // FILL：入边写 b 的数组
        store.declareUserVar(DimSpec("REL_ARR", isStr = false, dims = intArrayOf(4)))
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_QUERY_REL_FILL", b, "PARENT", 1, "REL_ARR")))
        assertEquals(a, store.readInt("REL_ARR", longArrayOf(0)))
        // 同一条溢出契约也钉 QUERY_REL_FILL：容量 1、命中 2 ⇒ 截断写 1 个 + 返回 -2
        val c2 = decl.mkChara(ctx)
        emCall(ctx, "ENTITY_ADD_REL", a, "PARENT", c2)
        assertEquals(2L, emIv(emCall(ctx, "ENTITY_QUERY_REL_COUNT", a, "PARENT", 0)))
        store.declareUserVar(DimSpec("REL_TIGHT", isStr = false, dims = intArrayOf(1)))
        assertEquals(
            -2L,
            emIv(emCall(ctx, "ENTITY_QUERY_REL_FILL", a, "PARENT", 0, "REL_TIGHT")),
            "容量不足 = 返回负总数示溢出",
        )
        assertEquals(b, store.readInt("REL_TIGHT", longArrayOf(0)), "截断写：只留前缀第 1 个")
    }

    // ==================================================================
    // 投射族
    // ==================================================================

    @Test
    fun `投射族 - 无投射桥时 PROJECT 族 CodeEE 而纯查询旁路`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime(projector = null))
        val id = decl.mkChara(ctx)
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_PROJECT_CHARA", id) }
        assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_UNPROJECT_CHARA", id) }
        // 纯查询旁路（裁定 3）
        assertEquals(-1L, emIv(emCall(ctx, "ENTITY_TO_CHARA", id)))
        assertEquals(0L, emIv(emCall(ctx, "CHARA_TO_ENTITY", 0L)))
        assertEquals(0L, emIv(emCall(ctx, "ROSTER_GENERATION")))
    }

    @Test
    fun `ENTITY_PROJECT_UNPROJECT_CHARA - 登场退场往返`() {
        val decl = EmDecl()
        val projector = EmFakeProjector()
        val (ctx, _) = emEnv(decl.runtime(projector))
        val id = decl.mkChara(ctx)
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_PROJECT_CHARA", id)), "首登场占第 0 行")
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_PROJECT_CHARA", id)), "幂等返回当前行号")
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_UNPROJECT_CHARA", id)))
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_UNPROJECT_CHARA", id)), "未投射返回 0")
    }

    @Test
    fun `ENTITY_TO_CHARA - 两参走世代校验通道`() {
        val decl = EmDecl()
        val projector = EmFakeProjector()
        val (ctx, _) = emEnv(decl.runtime(projector))
        val id = decl.mkChara(ctx)
        emCall(ctx, "ENTITY_PROJECT_CHARA", id)
        // 世代一致：返回当前行号
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_TO_CHARA", id, 0L)))
        // 世代过期：拒绝 -1（charIndexOfChecked 的四行判据口径）
        projector.generation = 7
        assertEquals(-1L, emIv(emCall(ctx, "ENTITY_TO_CHARA", id, 0L)), "过期世代被拒")
        assertEquals(0L, emIv(emCall(ctx, "ENTITY_TO_CHARA", id, 7L)), "新世代对上")
        // 未投射实体：-1
        val ghost = decl.mkChara(ctx)
        assertEquals(-1L, emIv(emCall(ctx, "ENTITY_TO_CHARA", ghost, 7L)))
    }

    @Test
    fun `CHARA_TO_ENTITY - 行反查与空行哨兵`() {
        val decl = EmDecl()
        val projector = EmFakeProjector()
        val (ctx, _) = emEnv(decl.runtime(projector))
        val id = decl.mkChara(ctx)
        emCall(ctx, "ENTITY_PROJECT_CHARA", id)
        assertEquals(id, emIv(emCall(ctx, "CHARA_TO_ENTITY", 0L)))
        assertEquals(0L, emIv(emCall(ctx, "CHARA_TO_ENTITY", 5L)), "空行返回 0（NULL）")
    }

    // ==================================================================
    // 辅助查询族
    // ==================================================================

    @Test
    fun `ROSTER_GENERATION - 读投射桥世代`() {
        val decl = EmDecl()
        val projector = EmFakeProjector()
        val (ctx, _) = emEnv(decl.runtime(projector))
        assertEquals(0L, emIv(emCall(ctx, "ROSTER_GENERATION")))
        projector.generation = 42
        assertEquals(42L, emIv(emCall(ctx, "ROSTER_GENERATION")))
    }

    @Test
    fun `MOD_IS_ACTIVE - 激活判定`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime(modIsActive = { it.equals("modA", ignoreCase = true) }))
        assertEquals(1L, emIv(emCall(ctx, "MOD_IS_ACTIVE", "modA")))
        assertEquals(0L, emIv(emCall(ctx, "MOD_IS_ACTIVE", "modB")))
        assertFailsWith<CodeEE> { emCall(ctx, "MOD_IS_ACTIVE", 1L) }
    }

    @Test
    fun `HAS_SUPER - 覆盖链判据与无帧旁路`() {
        val decl = EmDecl()
        // 两个同名实现 FN（modA → modB，链接序）+ 单实现 BASE
        val catalog = SymbolCatalog.build(
            candidates = listOf(
                SymbolCandidate(FuncMeta(0, "FN"), ownerPackageId = "modA"),
                SymbolCandidate(FuncMeta(1, "FN"), ownerPackageId = "modB"),
                SymbolCandidate(FuncMeta(2, "BASE"), ownerPackageId = "modA"),
            ),
            policy = ResolverPolicy.LEGACY_LAST_WINS,
        )
        val (ctx, store) = emEnv(decl.runtime(catalog = catalog))
        // 不在任何函数体内：currentFunction 为 null ⇒ 0（裁定 5）
        assertEquals(0L, emIv(emCall(ctx, "HAS_SUPER")))
        store.enterFunction("FN")
        assertEquals(1L, emIv(emCall(ctx, "HAS_SUPER")), "胜者有前实现 ⇒ 1")
        store.leaveFunction()
        store.enterFunction("BASE")
        assertEquals(0L, emIv(emCall(ctx, "HAS_SUPER")), "单实现无 super ⇒ 0")
    }

    // ==================================================================
    // 确定性派生族（ENTITY_RAND，M4-5）
    // ==================================================================

    @Test
    fun `ENTITY_RAND - 与 DeterministicDeriver 接线同值且同参复现`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = decl.mkChara(ctx)
        val v1 = emIv(emCall(ctx, "ENTITY_RAND", id, 7L))
        // 接线正确性：ERB 面必须逐位等于 derive(EntityId(id), "erb", ordinal=0, salt=7)
        assertEquals(DeterministicDeriver.derive(EntityId(id), "erb", 0L, 7L), v1)
        // 同参复现（纯函数：调用次数/顺序不影响）
        assertEquals(v1, emIv(emCall(ctx, "ENTITY_RAND", id, 7L)))
        // 不同 salt ⇒ 不同值（一个特征一个 salt 的分域根据）
        assertTrue(v1 != emIv(emCall(ctx, "ENTITY_RAND", id, 8L)), "不同 salt 派生值应不同")
    }

    @Test
    fun `ENTITY_RAND - 跨环境逐位复现（M4-5 判据）`() {
        // 两个完全独立的环境（各自 fresh 内核/注册表/store）= 模拟重装载：
        // 同一声明链下 create 出的实体 raw 位域一致 ⇒ 派生值必须逐位一致。
        val declA = EmDecl()
        val declB = EmDecl()
        val (ctxA, _) = emEnv(declA.runtime())
        val (ctxB, _) = emEnv(declB.runtime())
        val idA = declA.mkChara(ctxA)
        val idB = declB.mkChara(ctxB)
        assertEquals(idA, idB, "同一声明链 + 相同建序 ⇒ EntityId 位域一致")
        for (salt in longArrayOf(0L, 1L, -42L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(
                emIv(emCall(ctxA, "ENTITY_RAND", idA, salt)),
                emIv(emCall(ctxB, "ENTITY_RAND", idB, salt)),
                "salt=$salt 跨环境应逐位复现",
            )
        }
    }

    @Test
    fun `ENTITY_RAND - golden 向量钉算法漂移`() {
        // 向量来源 = DeterministicDeriver 现行实现（SplitMix64 finalizer + FNV-1a 64，
        // 常量以该文件字面量为权威），离线按位域算法独立复算一致后钉入；
        // 实体位域 pkgShortId=2/generation=0/handleSlot=0 → raw 0x0002000000000000。
        // 本向量把「算法或常量任一侧被改」钉成红，防静默漂移破坏跨加载复现。
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id = EntityIdCodec.encode(2, 0, 0)
        assertEquals(5095805931044760316L, emIv(emCall(ctx, "ENTITY_RAND", id, 0L)), "salt=0 golden")
        assertEquals(4784646121915136053L, emIv(emCall(ctx, "ENTITY_RAND", id, 1L)), "salt=1 golden")
    }

    @Test
    fun `ENTITY_RAND - 槽复用 generation 变值必变`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        val id1 = decl.mkChara(ctx)
        val v1 = emIv(emCall(ctx, "ENTITY_RAND", id1, 0L))
        assertEquals(1L, emIv(emCall(ctx, "ENTITY_DESTROY", id1)))
        val id2 = decl.mkChara(ctx)
        assertTrue(id1 != id2, "槽复用后 EntityId 位域必须不同（generation 递增）")
        val v2 = emIv(emCall(ctx, "ENTITY_RAND", id2, 0L))
        assertTrue(v1 != v2, "槽复用（身份换人）派生值必须不同 —— 不串号的根据")
    }

    @Test
    fun `ENTITY_RAND - 空引用与悬空引用口径`() {
        val decl = EmDecl()
        val (ctx, _) = emEnv(decl.runtime())
        // NULL ⇒ CodeEE（fail-closed，与 DeterministicDeriver.require 同口径）
        val e = assertFailsWith<CodeEE> { emCall(ctx, "ENTITY_RAND", 0L, 0L) }
        assertTrue(e.message!!.contains("空引用"), "NULL 文案不符：${e.message}")
        // 悬空引用不拦：destroy 后身份位域仍是合法派生键（纯函数面，与内核存活态解耦）
        val id = decl.mkChara(ctx)
        val alive = emIv(emCall(ctx, "ENTITY_RAND", id, 0L))
        emIv(emCall(ctx, "ENTITY_DESTROY", id))
        assertEquals(alive, emIv(emCall(ctx, "ENTITY_RAND", id, 0L)), "悬空引用派生值应逐位不变")
    }

    // ==================================================================
    // 反向证明辅助（contract §4.3 的改坏点在本文件之外执行）
    // ==================================================================

    /** Query 全量读数（改坏校验用，不参与 Gate）。 */
    private fun rtQueryAll(decl: EmDecl): LongArray = decl.kernel.query("CHARA", 0)
}
