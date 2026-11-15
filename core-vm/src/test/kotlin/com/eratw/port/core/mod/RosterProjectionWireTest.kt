package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl
import com.eratw.port.modapi.RuntimeScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PACKAGE_ID: String = "demo.roster"
private const val ARCHETYPE: String = "PERSON"
private const val FIELD_PARENT: String = "parent"

private val MANIFEST: ModManifest = ModManifest(
    packageId = PACKAGE_ID,
    components = listOf(
        ComponentDecl(
            name = "roster",
            fields = listOf(
                FieldDecl("hp", FieldType.INT),
                FieldDecl(FIELD_PARENT, FieldType.INT, isEntityRef = true),
            ),
        ),
    ),
    archetypes = listOf(ArchetypeDecl(ARCHETYPE, listOf("roster"))),
    relationships = listOf(RelationshipDecl("PARENT")),
)

/**
 * RT2 车次表 E 车 / P1-6 ①② 的**真引擎纵向切片**（era-arch-v61u.md §22.5 / §23.2 F2 / §23.5 第 11 条）。
 *
 * 装置全部是真的：真 [DeclarationRegistry.freeze] 的冻结声明、真 [EntityKernel]、真
 * [VariableStore] 的三行原生角色、真 [RealCharaSlotProjector]（M5-2）、真 [ModRuntimeHost]
 * 与真 [EraMod]。**没有 fake 自证** —— fake 与适配器同源时失败模式为 0，量不出接线断点。
 * 从 mod 侧只看得到 `ModContext.roster` / `ModContext.entities`（mod-api 类型），
 * 业务零耦合的形状由 :mod-family 的 Gradle 依赖方向另行编译期保证。
 *
 * 反向证明：把 [KernelRosterProjectionApi] 的转发退化成硬编码 -1/0，本文件
 * `realEngineVerticalSlice...` 必红；把 [KernelEntityApi.getRef] 改成错位包装，
 * `getRefSetRefShareTheSameIntColumn` 必红。恢复源码后复绿。
 */
class RosterProjectionWireTest {

    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private class ProbeMod : EraMod {
        lateinit var context: ModContext

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private class Fixture(injectProjector: Boolean) {
        val logs = mutableListOf<String>()
        val pkgs = PackageShortIdRegistry()
        val shortId: Int = pkgs.allocate(PACKAGE_ID)
        val registry = DeclarationRegistry()

        val snapshot: DeclarationSnapshot = run {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(MANIFEST))
            registry.freeze()
        }

        val kernel = EntityKernel(
            schemas = snapshot.componentSchemas.values.map { it.schema }.toList(),
            relationshipSchemas = snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            archetypes = snapshot.entityArchetypes.values.map { it.archetype }.toList(),
        )

        val store = VariableStore()
        val projector = RealCharaSlotProjector(store, kernel, VirtualCharaNoRegistry(), pkgs)
        val probe = ProbeMod()
        val host: ModRuntimeHost

        init {
            // M5-2 装配序：桥拿到完整 store 引用后回填，unproject 的删行钩子才有映射平移。
            store.charaSlotProjector = projector
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
            host = ModRuntimeHost(
                snapshot = snapshot,
                entityKernel = kernel,
                nativeMethods = NativeMethodRegistry(),
                logSink = { logs += it },
                runtime = InlineRuntime(),
                shortIdOf = { shortId },
                projector = if (injectProjector) projector else null,
            )
            val report = host.install(listOf(PACKAGE_ID to probe))
            check(report.single().failure == null) { "装载不得失败：${report.single()}；logs=$logs" }
            check(report.single().active) { "onEnable 后应为启用态；logs=$logs" }
        }

        val roster get() = probe.context.roster
        val entities get() = probe.context.entities

        fun newEntity(): EntityId = entities.create(ARCHETYPE)
        fun rows(): Int = store.characters.count
    }

    @Test
    fun `未注入投射器时四件入口全部显式 fail-closed 且生命周期零行为差`() {
        val f = Fixture(injectProjector = false)
        val id = f.newEntity()
        assertEquals(3, f.rows(), "未接线时一个对象都不建")
        assertTrue(f.entities.exists(id), "关闸只关投射，实体面照常可用")

        val failures = listOf(
            runCatching { f.roster.project(id) },
            runCatching { f.roster.unproject(id) },
            runCatching { f.roster.rosterGeneration },
            runCatching { f.roster.charIndexOfChecked(id, 0L) },
        )
        assertEquals(4, failures.size)
        for (result in failures) {
            assertTrue(result.isFailure, "未注入时不得静默返回 -1 / false / 0")
            assertTrue(
                result.exceptionOrNull() is UnsupportedOperationException,
                "四件入口必须统一抛 UnsupportedOperationException，实际 ${result.exceptionOrNull()}",
            )
        }
        val message = failures.first().exceptionOrNull()!!.message.orEmpty()
        assertTrue("F 车" in message, "异常必须点名 F 车装配缺口：$message")
        assertTrue(PACKAGE_ID in message, "异常必须点名 packageId：$message")
        assertEquals(3, f.rows(), "fail-closed 抛错后没有半装副作用")
    }

    @Test
    fun `既有不带 projector 的宿主构造点仍然可用`() {
        val f = Fixture(injectProjector = true)
        // 显式复刻 E 车之前的构造形状：一个 projector 参数都不传。
        val legacyMod = ProbeMod()
        val legacyHost = ModRuntimeHost(
            snapshot = f.snapshot,
            entityKernel = f.kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { f.logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { f.shortId },
        )
        val report = legacyHost.install(listOf(PACKAGE_ID to legacyMod))
        assertTrue(report.single().active, "不带 projector 的旧构造点必须照常装载")
        val id = legacyMod.context.entities.create(ARCHETYPE)
        assertTrue(legacyMod.context.entities.exists(id))
        assertFailsWith<UnsupportedOperationException> { legacyMod.context.roster.rosterGeneration }
    }

    @Test
    fun `真引擎纵向切片 登场读世代校验换 CHARA退场全链跑通`() {
        val f = Fixture(injectProjector = true)
        val child = f.newEntity()
        val nativeRows = f.rows()
        assertEquals(3, nativeRows, "装置先铺 3 行原生角色")

        val generation = f.roster.rosterGeneration
        val row = f.roster.project(child)
        assertEquals(nativeRows, row, "ADDVOIDCHARA 语义：伪角色尾部追加在末行")
        assertEquals(nativeRows + 1, f.rows(), "登场后角色行数 +1")
        assertEquals(generation, f.roster.rosterGeneration, "尾部追加不动既有行号，不递增世代")
        assertEquals(row, f.roster.charIndexOfChecked(child, generation), "同世代现换 CHARA 命中")
        assertEquals(-1, f.roster.charIndexOfChecked(child, generation + 1), "伪造的未来世代必须拒绝")

        assertTrue(f.roster.unproject(child), "退场必须成功")
        assertEquals(nativeRows, f.rows(), "退场后角色行数 -1")
        assertEquals(generation + 1, f.roster.rosterGeneration, "删行递增世代")
        assertEquals(-1, f.roster.charIndexOfChecked(child, generation), "过期世代返回 -1，不拿旧行号硬用")
        assertFalse(f.roster.unproject(child), "未投射退场原样返回 false")
    }

    @Test
    fun `适配器原样转发失败哨兵且只做位域转换`() {
        val f = Fixture(injectProjector = true)
        val dangling = EntityId(0xdead_beefL)
        assertEquals(-1, f.roster.project(dangling), "悬空 EntityId 的 -1 不得被吞")
        assertFalse(f.roster.unproject(dangling), "未投射的 false 不得被吞")
        assertTrue(f.probe.context.roster is KernelRosterProjectionApi, "roster 必须是引擎侧适配器")
    }

    @Test
    fun `getRef 与 setRef 共用同一张 INT 列 不改 codec 不改列存`() {
        val f = Fixture(injectProjector = false)
        val child = f.newEntity()
        val parent = f.newEntity()

        assertTrue(f.entities.setRef(child, FIELD_PARENT, parent))
        assertEquals(parent.raw, f.entities.getInt(child, FIELD_PARENT), "setRef 写的就是那张 INT 列")
        assertEquals(parent, f.entities.getRef(child, FIELD_PARENT))
        assertTrue(f.entities.setInt(child, FIELD_PARENT, parent.raw))
        assertEquals(parent, f.entities.getRef(child, FIELD_PARENT), "setInt 写、getRef 读逐位等价")
        assertEquals(EntityId.NULL, f.entities.getRef(EntityId(-77L), FIELD_PARENT), "不存在实体读得 NULL")
        assertFalse(f.entities.setRef(EntityId(-77L), FIELD_PARENT, parent), "不存在实体写返回 false")
    }

    @Test
    fun `新增 roster 不替换既有 14 个 ModContext 属性`() {
        val ctx = Fixture(injectProjector = true).probe.context
        assertNotNull(ctx.packageId)
        assertNotNull(ctx.logger)
        assertNotNull(ctx.runtime)
        assertNotNull(ctx.entities)
        assertNotNull(ctx.relationships)
        assertNotNull(ctx.events)
        assertNotNull(ctx.hooks)
        assertNotNull(ctx.phases)
        assertNotNull(ctx.jobs)
        assertNotNull(ctx.interactions)
        assertNotNull(ctx.actions)
        assertNotNull(ctx.query)
        assertNotNull(ctx.functions)
        assertNotNull(ctx.storage)
        assertNotNull(ctx.gameState)
        assertNotNull(ctx.ui)
        assertNotNull(ctx.roster)
    }
}
