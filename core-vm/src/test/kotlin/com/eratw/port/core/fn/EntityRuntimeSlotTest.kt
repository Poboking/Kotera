package com.eratw.port.core.fn

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.core.patch.NamePolicy
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * W1-ENTRT 会话级槽缝测试（CONTRACT-RT2-ENTRT 判据 1/2 的 core-vm 半边）：
 * 真源 = [ENTITY_RUNTIME_SLOT] 槽（状态挂 store.aux，铁律 4），读侧 = [entityRuntimeOf]
 * 与求值语境装饰器（Builtins.kt 的 AuxMacroHost 覆写）。装配产物用**真** EntityKernel +
 * 真 PackageShortIdRegistry（FamilyModTest 基建形态；装饰器接线面用本文件最小装置）。
 *
 * 既有 EntityMethodsTest 的未装配语义锁（19 函数 CodeEE 文案 + 5 旁路钉值）走的是
 * 「委托显式注入」形态，本组补的是 aux 缺席路径上的同一语义（判据 2 时序边界），
 * 既有锁零改写复绿是验收底线。
 */

/** 槽测试用的最小求值语境基座（与 EntityMethodsTest 的 EmBaseCtx 同形）。 */
private class SlotBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SlotBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

/** 声明装置：包 pkg.slot + schema body(HP) + 关系 PARENT + 原型 CHARA（真内核，全真件）。 */
private class SlotDecl {
    val registry = PackageShortIdRegistry()
    val kernel: EntityKernel
    val runtime: EntityRuntime

    init {
        registry.allocate("pkg.slot")
        val archChara = EntityArchetype("CHARA", listOf("body"))
        kernel = EntityKernel(
            schemas = listOf(
                ComponentSchema(
                    "pkg.slot",
                    "body",
                    listOf(ComponentFieldSpec("HP")),
                ),
            ),
            relationshipSchemas = listOf(RelationshipSchema("PARENT", directed = false)),
            archetypes = listOf(archChara),
        )
        runtime = object : EntityRuntime {
            override val kernel: com.eratw.port.core.entity.IEntityKernel = this@SlotDecl.kernel
            override val projector: com.eratw.port.core.entity.ICharaSlotProjector? = null
            override val registry: PackageShortIdRegistry = this@SlotDecl.registry
            override val archetypes: (String) -> DeclaredArchetype? =
                { name ->
                    if (NamePolicy.canonical(name) == "CHARA") DeclaredArchetype("pkg.slot", archChara) else null
                }
            override val catalog: SymbolCatalog? = null
            override val modIsActive: (String) -> Boolean = { pkg -> NamePolicy.canonical(pkg) == "PKG.SLOT" }
        }
    }
}

/** 值调用：Long/Int → IntV，String → StrV。 */
private fun slotCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
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

private fun slotIv(v: Value): Long = (v as Value.IntV).v

class EntityRuntimeSlotTest {

    @Test
    fun `空aux读缝恒null且不建槽`() {
        val aux = RuntimeAux()
        assertNull(entityRuntimeOf(aux), "未装配（关态 / attach 前）必须读到 null")
        assertTrue(aux.slotNames().isEmpty(), "读缝只许 peek 不许建槽（读侧不得伪造装配态）")
    }

    @Test
    fun `槽内装配经DEFAULT宿主的求值语境可达`() {
        val decl = SlotDecl()
        val store = VariableStore()
        store.aux.slot(ENTITY_RUNTIME_SLOT) { decl.runtime }
        val base = SlotBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)
        assertSame(decl.runtime, ctx.host.entityRuntime, "AuxMacroHost 读缝必须让任何宿主形态吃到会话真源")
    }

    @Test
    fun `委托显式注入优先于槽`() {
        val decl = SlotDecl()
        val other = SlotDecl()
        val store = VariableStore()
        store.aux.slot(ENTITY_RUNTIME_SLOT) { decl.runtime }
        val explicit = object : BuiltinHost {
            override val entityRuntime: EntityRuntime? get() = other.runtime
        }
        val base = SlotBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, explicit)
        assertSame(
            other.runtime,
            ctx.host.entityRuntime,
            "委托显式非空注入优先于槽（测试注入形态不受槽回填遮蔽）",
        )
    }

    @Test
    fun `委托显式null与缺省null不可区分_回退到槽`() {
        val decl = SlotDecl()
        val store = VariableStore()
        store.aux.slot(ENTITY_RUNTIME_SLOT) { decl.runtime }
        val explicitNull = object : BuiltinHost {
            override val entityRuntime: EntityRuntime? get() = null
        }
        val base = SlotBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, explicitNull)
        assertSame(
            decl.runtime,
            ctx.host.entityRuntime,
            "接口缺省实现无重写标记：显式 null 与缺省 null 同读，回退到槽是唯一可实现口径" +
                "（与 AuxMacroHost.imageSizeOf 的 delegate ?: aux 同形）",
        )
    }

    @Test
    fun `经槽缝的真内核往返`() {
        val decl = SlotDecl()
        val store = VariableStore()
        store.aux.slot(ENTITY_RUNTIME_SLOT) { decl.runtime }
        val base = SlotBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)

        val id1 = slotIv(slotCall(ctx, "ENTITY_CREATE", "CHARA"))
        val id2 = slotIv(slotCall(ctx, "ENTITY_CREATE", "CHARA"))
        assertTrue(id1 > 0 && id2 > 0, "真内核建实体必须成功（判据 1：改走真内核）")
        assertEquals(1L, slotIv(slotCall(ctx, "ENTITY_SET_INT", id1, "HP", 42)), "SET_INT 真写组件列")
        assertEquals(42L, slotIv(slotCall(ctx, "ENTITY_GET_INT", id1, "HP")), "GET_INT 同一内核读回（往返）")
        assertEquals(2L, slotIv(slotCall(ctx, "ENTITY_QUERY_COUNT", "CHARA", 0)), "QUERY_COUNT 原型过滤读数")
        assertEquals(1L, slotIv(slotCall(ctx, "ENTITY_ADD_REL", id1, "PARENT", id2)), "ADD_REL 声明 kind 落真内核")
        assertEquals(1L, slotIv(slotCall(ctx, "ENTITY_HAS_REL", id2, "PARENT", id1)), "HAS_REL 无向反向问询命中")
        assertEquals(1L, slotIv(slotCall(ctx, "MOD_IS_ACTIVE", "pkg.slot")), "MOD_IS_ACTIVE 真值（归一匹配）")
        assertEquals(0L, slotIv(slotCall(ctx, "MOD_IS_ACTIVE", "no.such.pkg")), "未链接包如实 0")
        assertEquals(-1L, slotIv(slotCall(ctx, "ENTITY_TO_CHARA", id1)), "无投射读数旁路 -1（projector=null 语义）")
    }

    @Test
    fun `槽缺席时未装配语义逐位不变`() {
        val store = VariableStore()
        val base = SlotBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)
        val e = assertFailsWith<CodeEE> { slotCall(ctx, "ENTITY_CREATE", "CHARA") }
        assertEquals("函数 ENTITY_CREATE：实体运行时未装配", e.message, "判据 2：关态 / attach 前文案逐位不变")
    }
}
