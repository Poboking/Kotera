package com.eratw.port.modfamily

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EntityRuntime
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ERB `ENTITY_SET_STATE(id, 3)` 的 state 实参（ADR-015 D1：死亡 ≡ RETIRED）。 */
private const val RETIRED_STATE = 3

/** [EntityState.INACTIVE] 在实体段的 raw 值。 */
private const val INACTIVE_STATE = 2

/** 宿主给 `eratw.family` 分配的包短 ID（EntityId 的 pkg 段）。 */
private const val SHORT_ID = 7

private const val ELDER_AGE = 5L

private const val YOUNGER_AGE = 3L

/** 家督初始称号；继任者必须原样继承。 */
private const val HEAD_TITLE = "家督"

private const val RECONCILE_MARK = "mod.family reconcile"

private const val SUCCESSION_MARK = "家督继任"

/**
 * ADR-015 S5：**两条死亡入口的对拍闸**。
 *
 * 死亡的 A1（载体直接标死 [FamilyMod.markDeceased]）与 A2（ERB 直调 `ENTITY_SET_STATE(id, 3)`）
 * 在生产里是两条码路：前者写穿透 mod 存储的名册，后者只改实体段 state（`EntityMethods.kt`
 * 的 ENTITY_SET_STATE 只调 `kernel.setState`），而名册与实体段是两份独立权威（D2）。
 * 两条入口必须在**同一日拍**收敛到同一结果，否则玩家会看到「同一个死法、两种后果」的分叉。
 *
 * ## 这不是「直接 set 字段」的桩
 *
 * ERB 那一侧经 `Builtins.registry` 里的**真注册项** `ENTITY_SET_STATE` 派发（`BuiltinEvalContext`
 * 的 `callFunction` 走与脚本一致的 dispatch → `BuiltinMethod.eval` → `EntityRuntime.kernel.setState`），
 * 而 `EntityRuntime.kernel` 与载体的 `context.entities` 是**同一个** [EntityKernel] 实例
 * （`ModRuntimeHost.install` 用同一 kernel 装配 `KernelEntityApi`）—— 所以「两条入口共用一份世界」
 * 是夹具结构保证的，不是断言出来的。
 *
 * 对拍口径：两套全新夹具做同一剧本，下一日拍后名册落盘字节、reconcile 日志、继任日志（含死/继任
 * 的 EntityId）必须逐字节相同；另加载体侧的显式语义断言与负控/顺序/存档往返用例。
 */
class FamilyDeathEntryParityTest {

    /** 同线程直接执行：本文件只证语义，不证宿主调度器。 */
    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    /** 最小求值底座：内建派发不经过它调函数，只提供变量读者。 */
    private class ParityBase(override val variables: VariableReader) : EvalContext {
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("ParityBase 不应被直接调用：$name")

        override fun printSystemLine(message: String) = Unit
    }

    private class ParityHost(private val runtime: EntityRuntime) : BuiltinHost {
        override val entityRuntime: EntityRuntime get() = runtime
    }

    /** ERB 侧装配：与生产一致，只装 ENTITY 族派发面要用的 kernel（其余能力面 null = 合法旁路）。 */
    private class ParityRuntime(override val kernel: IEntityKernel) : EntityRuntime {
        override val projector: ICharaSlotProjector? = null
        override val registry: PackageShortIdRegistry =
            PackageShortIdRegistry().apply { allocate(FamilyDeclarations.PACKAGE_ID) }
        override val archetypes: (String) -> DeclaredArchetype? = { null }
        override val catalog: SymbolCatalog? = null
        override val modIsActive: (String) -> Boolean = { false }
    }

    private class ParitySession(
        val host: ModRuntimeHost,
        val family: FamilyMod,
        val kernel: EntityKernel,
        private val erb: BuiltinEvalContext,
        val logs: MutableList<String>,
    ) {
        val context: ModContext get() = checkNotNull(host.contextOf(FamilyDeclarations.PACKAGE_ID))

        val entities get() = context.entities

        /** 日拍：与 `family.growth` 任务绑定的同一个 `advanceOneDay`（onEnable 绑的就是它）。 */
        fun tick() = family.advanceOneDay()

        /** 走真 `Builtins.registry` 派发一个整数台账内建函数，返回其 INT 结果。 */
        fun erbCall(name: String, vararg args: Long): Long =
            (erb.callFunction(name, args.map { Value.IntV(it) }) as Value.IntV).v

        /** 名册持久真源 `childIds` 的原始字符串（空名单 = 空串，键保持存在）。 */
        fun roster(): String = context.storage.get(FamilyDeclarations.CHILD_IDS).orEmpty()

        fun markLines(mark: String): List<String> = logs.filter { it.contains(mark) }
    }

    private class Household(val head: EntityId, val elder: EntityId, val younger: EntityId)

    private fun freeze(vararg manifests: ModManifest): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (manifest in manifests) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        }
        return registry.freeze()
    }

    private fun kernelFor(snapshot: DeclarationSnapshot): EntityKernel = EntityKernel(
        schemas = snapshot.componentSchemas.values.map { it.schema }.toList(),
        relationshipSchemas = snapshot.relationshipSchemas.values.map { it.schema }.toList(),
        archetypes = snapshot.entityArchetypes.values.map { it.archetype }.toList(),
    )

    /** 真宿主 + 真内核 + 真 ERB 派发面：三者共享同一个 [EntityKernel] 实例。 */
    private fun start(
        persisted: Map<String, String> = emptyMap(),
        storage: ModStorageStore = ModStorageStore(),
        kernelOverride: EntityKernel? = null,
    ): ParitySession {
        val snapshot = freeze(FamilyDeclarations.manifest)
        val kernel = kernelOverride ?: kernelFor(snapshot)
        val logs = mutableListOf<String>()
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { SHORT_ID },
            storage = storage,
        )
        host.settings = mapOf(
            FamilyDeclarations.PACKAGE_ID to ModSettingsSnapshot(
                FamilyDeclarations.PACKAGE_ID,
                ModDeclarationAdapter.toDescriptor(FamilyDeclarations.manifest).settings,
                persisted,
            ),
        )
        val mod = FamilyMod()
        val report = host.install(listOf(FamilyDeclarations.PACKAGE_ID to mod))
        assertNull(report.single().failure, "装载不得失败：${report.single()}；日志=$logs")
        assertTrue(report.single().active, "onEnable 后应为启用态")
        val store = VariableStore()
        val erb = BuiltinEvalContext(ParityBase(store), store, ParityHost(ParityRuntime(kernel)))
        return ParitySession(host, mod, kernel, erb, logs)
    }

    private fun household(session: ParitySession): Household {
        val head = session.family.adoptChild(HEAD_TITLE, null)
        val elder = session.family.adoptChild("长子", head)
        val younger = session.family.adoptChild("次子", head)
        session.entities.setInt(elder, FamilyKeys.age, ELDER_AGE)
        session.entities.setInt(younger, FamilyKeys.age, YOUNGER_AGE)
        return Household(head, elder, younger)
    }

    private fun ParitySession.rosterIds(): List<Long> =
        roster().split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }

    @Test
    fun carrierAndErbEntryProduceIdenticalNextTickOutcome() {
        val carrier = start()
        val c = household(carrier)
        assertTrue(carrier.family.markDeceased(c.head), "A1 载体入口应真的发生 ACTIVE→RETIRED")
        assertEquals(EntityState.RETIRED, carrier.entities.stateOf(c.head))

        val erb = start()
        val e = household(erb)
        assertEquals(
            1L,
            erb.erbCall("ENTITY_SET_STATE", e.head.raw, RETIRED_STATE.toLong()),
            "A2 真派发应返回 1（内核 setState 成功）",
        )
        assertEquals(
            EntityState.RETIRED,
            erb.entities.stateOf(e.head),
            "ERB 派发必须落到载体读到的那份实体内核上",
        )

        carrier.tick()
        erb.tick()

        assertEquals(erb.roster(), carrier.roster(), "两条入口下一日拍的持久名册必须逐字节相同")
        // 两份权威（D2）的对齐时机在两条入口上本来就不一样，这不算结果分叉，反而是机制证据：
        // A1 在 markDeceased 里当场写穿名册（日拍无需再摘、无 reconcile 日志）；
        // A2 只改实体段，名册留给下一日拍 reconcile 收口（恰好一条 INFO）。
        assertEquals(0, carrier.markLines(RECONCILE_MARK).size, "A1 已当场写穿：日拍不重复摘除")
        assertEquals(1, erb.markLines(RECONCILE_MARK).size, "A2 只改实体段：由下一日拍 reconcile 对齐")
        assertEquals(
            erb.markLines(SUCCESSION_MARK),
            carrier.markLines(SUCCESSION_MARK),
            "继任结算日志（含前任/继任 EntityId）必须逐字节相同",
        )
        assertEquals(
            erb.entities.getRef(e.elder, FamilyKeys.father),
            carrier.entities.getRef(c.elder, FamilyKeys.father),
            "长子父引用对拍",
        )
        assertEquals(
            erb.entities.getRef(e.younger, FamilyKeys.father),
            carrier.entities.getRef(c.younger, FamilyKeys.father),
            "次子父引用对拍（reparentActiveChildren 结果）",
        )

        assertFalse(carrier.rosterIds().contains(c.head.raw), "名册不得留死者")
        assertTrue(carrier.entities.exists(c.head), "死亡不删实体（D4）")
        assertEquals(1, carrier.markLines(SUCCESSION_MARK).size)
        val keepers = listOf(c.elder, c.younger)
        val successor = keepers.first { carrier.entities.getRef(it, FamilyKeys.father) == EntityId.NULL }
        val other = keepers.first { it != successor }
        assertEquals(successor, carrier.entities.getRef(other, FamilyKeys.father), "其余子嗣须改挂继任者")
        assertEquals(HEAD_TITLE, carrier.entities.getStr(successor, FamilyKeys.title), "继任者继承称号")
    }

    @Test
    fun nonRetiredStateDoesNotReconcileOrTriggerSuccession() {
        val inactive = start()
        val hi = household(inactive)
        assertEquals(1L, inactive.erbCall("ENTITY_SET_STATE", hi.head.raw, INACTIVE_STATE.toLong()))
        inactive.tick()
        assertTrue(inactive.rosterIds().contains(hi.head.raw), "INACTIVE 不是死亡：名册不得摘除")
        assertEquals(EntityState.INACTIVE, inactive.entities.stateOf(hi.head))
        assertEquals(emptyList(), inactive.markLines(RECONCILE_MARK), "非 RETIRED 不得触发 reconcile 写盘")
        assertEquals(emptyList(), inactive.markLines(SUCCESSION_MARK), "非 RETIRED 不得触发继任")
        assertEquals(hi.head, inactive.entities.getRef(hi.elder, FamilyKeys.father), "父引用不变")

        val active = start()
        val ha = household(active)
        assertEquals(1L, active.erbCall("ENTITY_SET_STATE", ha.head.raw, 1L), "ACTIVE=1 也是合法状态写")
        active.tick()
        assertTrue(active.rosterIds().contains(ha.head.raw), "ACTIVE 不是死亡：名册不摘除")
        assertEquals(emptyList(), active.markLines(SUCCESSION_MARK))
    }

    @Test
    fun ghostRosterEntryIsNotTreatedAsDeath() {
        val session = start()
        val orphan = session.family.adoptChild("幽灵", null)
        val before = session.roster()
        assertEquals(1L, session.erbCall("ENTITY_DESTROY", orphan.raw))
        assertFalse(session.entities.exists(orphan), "真实派发销毁后实体不存在")

        session.tick()

        assertEquals(before, session.roster(), "实体已销毁是幽灵条目不是死亡：名册不写盘（交给读档 exists 过滤）")
        assertEquals(emptyList(), session.markLines(RECONCILE_MARK), "零 reconcile 日志")
        assertEquals(emptyList(), session.markLines(SUCCESSION_MARK), "零继任日志")
    }

    @Test
    fun deathBeforeTickIsReconciledInThatSameTick() {
        val session = start()
        val h = household(session)
        assertEquals(1L, session.erbCall("ENTITY_SET_STATE", h.head.raw, RETIRED_STATE.toLong()))

        session.tick()

        assertFalse(session.rosterIds().contains(h.head.raw), "同拍标死 ⇒ 当拍 reconcile 摘除")
        assertEquals(1, session.markLines(RECONCILE_MARK).size)
        assertEquals(1, session.markLines(SUCCESSION_MARK).size)
        assertEquals(0L, session.entities.getInt(h.head, FamilyKeys.age), "死者当拍不再涨年龄")
    }

    @Test
    fun deathAfterTickIsReconciledOnTheNextTick() {
        val session = start()
        val h = household(session)
        session.tick()
        assertEquals(emptyList(), session.markLines(RECONCILE_MARK), "活着的第一拍零摘除")

        assertEquals(1L, session.erbCall("ENTITY_SET_STATE", h.head.raw, RETIRED_STATE.toLong()))
        session.tick()

        assertFalse(session.rosterIds().contains(h.head.raw))
        assertEquals(1, session.markLines(RECONCILE_MARK).size)
        assertEquals(1, session.markLines(SUCCESSION_MARK).size)
        assertEquals(1L, session.entities.getInt(h.head, FamilyKeys.age), "反序时死者多涨了一拍（reconcile 必须先于成长）")
    }

    @Test
    fun manualSuccessionModeReconcilesButDoesNotSettle() {
        val session = start(
            mapOf(FamilyDeclarations.SUCCESSION_MODE_KEY to FamilyDeclarations.SUCCESSION_MANUAL),
        )
        val h = household(session)
        assertEquals(1L, session.erbCall("ENTITY_SET_STATE", h.head.raw, RETIRED_STATE.toLong()))

        session.tick()

        assertFalse(session.rosterIds().contains(h.head.raw), "D6：manual 下 reconcile 照常收口名册")
        assertEquals(1, session.markLines(RECONCILE_MARK).size)
        assertEquals(emptyList(), session.markLines(SUCCESSION_MARK), "manual 不自动结算继任")
        assertEquals(h.head, session.entities.getRef(h.elder, FamilyKeys.father), "父引用保持原样")
        assertEquals(h.head, session.entities.getRef(h.younger, FamilyKeys.father), "父引用保持原样")
        assertEquals("长子", session.entities.getStr(h.elder, FamilyKeys.title), "称号不继承")
    }

    @Test
    fun savedTickIsNotReplayedAfterReload() {
        val first = start()
        val h = household(first)
        assertEquals(1L, first.erbCall("ENTITY_SET_STATE", h.head.raw, RETIRED_STATE.toLong()))
        first.tick()
        val rosterAfterTick = first.roster()
        val elderFather = first.entities.getRef(h.elder, FamilyKeys.father)
        val youngerFather = first.entities.getRef(h.younger, FamilyKeys.father)
        assertEquals(1, first.markLines(RECONCILE_MARK).size)
        assertEquals(1, first.markLines(SUCCESSION_MARK).size)

        val bytes = first.host.storage.encode()
        val reloaded = ModStorageStore.load(bytes, knownPackages = setOf(FamilyDeclarations.PACKAGE_ID))
        val second = start(storage = reloaded, kernelOverride = first.kernel)
        second.host.notifySaveLoaded()
        assertEquals(rosterAfterTick, second.roster(), "读档后名册与存档一致")
        assertEquals(1L, second.family.state.growthTicks, "growthTicks 随存档还原")

        second.tick()

        assertEquals(emptyList(), second.markLines(RECONCILE_MARK), "读档后重放不得重复摘除/写盘")
        assertEquals(emptyList(), second.markLines(SUCCESSION_MARK), "读档后重放不得重复结算继任")
        assertEquals(rosterAfterTick, second.roster())
        assertEquals(elderFather, second.entities.getRef(h.elder, FamilyKeys.father))
        assertEquals(youngerFather, second.entities.getRef(h.younger, FamilyKeys.father))
    }
}
