package com.eratw.port.modmemory

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.add
import com.eratw.port.modapi.create
import com.eratw.port.modapi.has
import com.eratw.port.modapi.remove
import com.eratw.port.modapi.valueOf
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MODDX4-B4 定向测试：mod-memory 人物↔人物认识关系（[MemoryDeclarations.ACQUAINTANCE]）
 * 与 teach 前置真值化（[MemoryDeclarations.TEACH_REQUIRES_KNOWS_KEY] 开启时查
 * teacher↔learner 认识边真值，接线前门开启恒 false）。
 *
 * 装置两面分工（照仓内既有口径）：
 * - **真宿主**（照 `MemoryModTest`：真 DeclarationRegistry.freeze → 真 EntityKernel →
 *   真 ModRuntimeHost）：钉无向边内核语义、门真值化的正向/反向证明、门关逐位回归。
 *   每次会话装配都真跑声明冻结闸——ACQUAINTANCE 的声明形状若违规，装载当场失败。
 * - **台架**（`:mod-testing` 内存台架，只复用不改）：钉「设置值 → 门行为」映射在台架的
 *   内存关系实现上同样成立（契约判据 4：台架可测——`MemoryRelationships` 的无向对称
 *   口径与 `EntityKernel` 一致，mod 不依赖任何一边的私有行为）。
 *
 * 反向证明（契约判据 2）：摘认识边 → teach 拒恢复；重加 → 门重开。门关回归（同判据）：
 * 缺省关时 teach 与接线前逐位一致——无 teacher、无认识边照常传授，且不产生认识边。
 */
class ModDx4MemoryKnowsTest {

    // ------------------------------------------------------------------
    // 真宿主装置（照 MemoryModTest：InlineRuntime / freeze / kernelFor / Session / start）
    // ------------------------------------------------------------------

    /** 同线程直接执行：证 mod 侧调用形状，不证宿主调度器（MemoryModTest 同口径）。 */
    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private val logs = mutableListOf<String>()

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

    private class Session(
        val host: ModRuntimeHost,
        val memory: MemoryMod,
    ) {
        val context: ModContext get() = checkNotNull(host.contextOf(MemoryDeclarations.PACKAGE_ID))
        val queries get() = memory.queries
        val entities get() = context.entities
        val relationships get() = context.relationships
    }

    private fun start(gateOn: Boolean = false): Session {
        val snapshot = freeze(MemoryDeclarations.manifest)
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernelFor(snapshot),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { pkg -> if (pkg == MemoryDeclarations.PACKAGE_ID) 9 else 10 },
        )
        // per-mod 设置面同生产序（install 前注入）；persistedValues 走生产同一份解析口径
        // （ModSettingsSnapshot.resolveText：合法持久值生效，否则回退声明默认 false）。
        host.settings = mapOf(
            MemoryDeclarations.PACKAGE_ID to ModSettingsSnapshot(
                MemoryDeclarations.PACKAGE_ID,
                ModDeclarationAdapter.toDescriptor(MemoryDeclarations.manifest).settings,
                persistedValues = if (gateOn) {
                    mapOf(MemoryDeclarations.TEACH_REQUIRES_KNOWS_KEY to "true")
                } else {
                    emptyMap()
                },
            ),
        )
        val mods = listOf(MemoryDeclarations.PACKAGE_ID to MemoryMod())
        val report = host.install(mods)
        assertEquals(1, report.size)
        assertNull(report.single().failure, "装载不得失败：${report.single()}；日志=$logs")
        assertTrue(report.single().active, "onEnable 后应为启用态")
        return Session(host, mods.single().second as MemoryMod)
    }

    // ------------------------------------------------------------------
    // 1. 声明形状：ACQUAINTANCE 无向/去重/级联，KNOWS（entity↔fact）原样不动
    // ------------------------------------------------------------------

    @Test
    fun acquaintanceJoinsKnowsAsSecondRelationWithoutDisturbingIt() {
        val manifest = MemoryDeclarations.manifest
        assertEquals(
            listOf(MemoryDeclarations.KNOWS.name, MemoryDeclarations.ACQUAINTANCE.name),
            manifest.relationships.map { it.name },
            "声明序：KNOWS 之后追加 ACQUAINTANCE（线格式按声明序落盘，既有段不得重排）",
        )
        // entity↔fact 的 KNOWS 原样不动（MODDX4-B4 裁决 3：两个 KNOWS 不许撞名，记忆边不变形）。
        val knows = manifest.relationships.single { it.name == MemoryDeclarations.KNOWS.name }
        assertTrue(knows.directed, "知识边保持有向：owner --KNOWS--> fact")
        assertTrue(knows.dedupeEdges)
        assertTrue(knows.cascadeOnDestroy)
        assertFalse(knows.autoInverse, "知识不设反向边（原裁定不变）")
        assertNull(knows.inverseName)
        // 人物↔人物 ACQUAINTANCE：无向、去重、destroy 级联清边、无反向类型（裁决 1）。
        val acquaintance =
            manifest.relationships.single { it.name == MemoryDeclarations.ACQUAINTANCE.name }
        assertFalse(acquaintance.directed, "「双方已认识」天然对称：无向")
        assertTrue(acquaintance.dedupeEdges, "重复结识去重（含反方向命中同一条边）")
        assertTrue(acquaintance.cascadeOnDestroy, "一方实体被删，认识边随之清掉")
        assertFalse(acquaintance.autoInverse, "无向已对称，不需要第二张反向账本")
        assertNull(acquaintance.inverseName)
    }

    // ------------------------------------------------------------------
    // 2. 无向边内核语义：单向存储、两端对称、跨方向去重与摘除、destroy 级联
    // ------------------------------------------------------------------

    @Test
    fun acquaintanceEdgeIsSymmetricAndDedupedThroughTheRealKernel() {
        val session = start()
        val a = session.entities.create(MemoryDeclarations.PERSON)
        val b = session.entities.create(MemoryDeclarations.PERSON)

        assertTrue(session.relationships.add(a, MemoryDeclarations.ACQUAINTANCE, b), "首次结识成功")
        assertTrue(session.relationships.has(a, MemoryDeclarations.ACQUAINTANCE, b))
        assertTrue(
            session.relationships.has(b, MemoryDeclarations.ACQUAINTANCE, a),
            "无向边反向问询命中同一条边（内核 EntityKernel.kt:347-356 分支）",
        )
        assertEquals(
            0L,
            session.relationships.valueOf(a, MemoryDeclarations.ACQUAINTANCE, b),
            "边值保留位恒 0（本 mod 未给认识边派业务值）",
        )
        assertEquals(0L, session.relationships.valueOf(b, MemoryDeclarations.ACQUAINTANCE, a), "值单份、方向无差")
        assertFalse(
            session.relationships.add(b, MemoryDeclarations.ACQUAINTANCE, a),
            "反方向重复结识被无向去重拒绝（EntityKernel.kt:300-306）",
        )
        assertTrue(
            session.relationships.remove(b, MemoryDeclarations.ACQUAINTANCE, a),
            "任一方向摘除的都是同一条边",
        )
        assertFalse(session.relationships.has(a, MemoryDeclarations.ACQUAINTANCE, b))
        assertFalse(session.relationships.has(b, MemoryDeclarations.ACQUAINTANCE, a))

        // destroy 级联：一方实体被删，认识边随之清掉（内核 destroy 无条件清相关边）。
        assertTrue(session.relationships.add(a, MemoryDeclarations.ACQUAINTANCE, b))
        assertTrue(session.entities.destroy(b))
        assertFalse(
            session.relationships.has(a, MemoryDeclarations.ACQUAINTANCE, b),
            "指向已不存在实体的「认识」不残留",
        )
    }

    // ------------------------------------------------------------------
    // 3. 门真值化（门开启）：前置 = teacher 与 learner 之间有 ACQUAINTANCE 边
    // ------------------------------------------------------------------

    @Test
    fun gatedTeachFollowsTheAcquaintanceEdgeBetweenTeacherAndLearner() {
        val session = start(gateOn = true)
        val teacher = session.entities.create(MemoryDeclarations.PERSON)
        val learner = session.entities.create(MemoryDeclarations.PERSON)
        val fact = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "gated", confidence = 50L),
        )

        assertFalse(
            session.memory.teach(learner = learner, fact = fact, teacher = teacher),
            "门开启且双方未结识 ⇒ 拒绝（不抛、不建知识边）",
        )
        assertFalse(session.relationships.has(learner, MemoryDeclarations.KNOWS, fact), "拒绝时不建知识边")
        assertTrue(session.queries.knowledgeOf(learner).isEmpty(), "学习者知识面仍空")

        assertTrue(session.memory.meet(teacher, learner), "结识（认识边落库）")
        assertTrue(
            session.memory.teach(learner = learner, fact = fact, teacher = teacher),
            "门开启且有认识边 ⇒ 前置过、传授成功（接线前此路径恒 false）",
        )
        assertTrue(session.relationships.has(learner, MemoryDeclarations.KNOWS, fact))
        assertEquals(
            Provenance.HEARSAY,
            session.queries.provenanceOf(learner, fact),
            "认知来源仍记在 learner 自己的边上（缺省听闻）",
        )
        assertEquals(listOf(fact), session.queries.knowledgeOf(learner))
    }

    @Test
    fun removingTheAcquaintanceEdgeRestoresTheRefusalAndReaddingReopensTheGate() {
        val session = start(gateOn = true)
        val teacher = session.entities.create(MemoryDeclarations.PERSON)
        val learner = session.entities.create(MemoryDeclarations.PERSON)
        val f1 = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "beforeCut", confidence = 50L),
        )
        assertTrue(session.memory.meet(teacher, learner))
        assertTrue(session.memory.teach(learner = learner, fact = f1, teacher = teacher))

        // 反向证明第一段：摘认识边 → teach 拒恢复（门真的查边，不是装好后就恒开）。
        assertTrue(session.relationships.remove(teacher, MemoryDeclarations.ACQUAINTANCE, learner), "摘除认识边")
        val f2 = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "afterCut", confidence = 50L),
        )
        assertFalse(
            session.memory.teach(learner = learner, fact = f2, teacher = teacher),
            "摘边后 teach 拒恢复",
        )
        assertFalse(session.relationships.has(learner, MemoryDeclarations.KNOWS, f2), "拒时不建知识边")
        assertFalse(session.queries.knowledgeOf(learner).contains(f2))

        // 反向证明第二段：边恢复 → 门重开（同一对实体、同一调用形状）。
        assertTrue(session.memory.meet(teacher, learner), "重新结识")
        assertTrue(session.memory.teach(learner = learner, fact = f2, teacher = teacher))
        assertTrue(session.relationships.has(learner, MemoryDeclarations.KNOWS, f2))
    }

    @Test
    fun gatedTeachWithoutTeacherIdentityRefusesEvenWhenPartiesAreAcquainted() {
        val session = start(gateOn = true)
        val teacher = session.entities.create(MemoryDeclarations.PERSON)
        val learner = session.entities.create(MemoryDeclarations.PERSON)
        val fact = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "noTeacher", confidence = 50L),
        )
        assertTrue(session.memory.meet(teacher, learner), "双方确实已结识")

        assertFalse(
            session.memory.teach(learner = learner, fact = fact),
            "门开启而未给 teacher 身份 ⇒ 无从验证认识关系，fail-closed 拒绝",
        )
        assertFalse(session.relationships.has(learner, MemoryDeclarations.KNOWS, fact))
        assertTrue(session.queries.knowledgeOf(learner).isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 门关闭回归：与接线前逐位一致——不查、不建认识边，teacher 实参被忽略
    // ------------------------------------------------------------------

    @Test
    fun gateOffBehaviorMatchesThePreWiringContractBitForBit() {
        val session = start(gateOn = false)
        val teacher = session.entities.create(MemoryDeclarations.PERSON)
        val learner = session.entities.create(MemoryDeclarations.PERSON)
        val fact = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "open", confidence = 50L),
        )

        assertTrue(
            session.memory.teach(learner = learner, fact = fact),
            "声明缺省（门关）：无 teacher、无认识边照常传授",
        )
        assertTrue(session.relationships.has(learner, MemoryDeclarations.KNOWS, fact))
        assertEquals(Provenance.HEARSAY, session.queries.provenanceOf(learner, fact))

        // teacher 实参给了也走同一条门关路径：不查认识边、不产生认识边。
        val learner2 = session.entities.create(MemoryDeclarations.PERSON)
        val fact2 = session.memory.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "open2", confidence = 50L),
        )
        assertTrue(session.memory.teach(learner = learner2, fact = fact2, teacher = teacher))
        assertTrue(session.relationships.has(learner2, MemoryDeclarations.KNOWS, fact2))
        assertFalse(
            session.relationships.has(teacher, MemoryDeclarations.ACQUAINTANCE, learner2),
            "门关时 teach 不要求也不产生认识边",
        )
    }

    // ------------------------------------------------------------------
    // 5. meet 写路径：幂等结识、端点缺失 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun meetIsIdempotentAndFailsClosedOnMissingEndpoints() {
        val session = start()
        val a = session.entities.create(MemoryDeclarations.PERSON)
        val b = session.entities.create(MemoryDeclarations.PERSON)

        assertTrue(session.memory.meet(a, b), "首次结识")
        assertTrue(
            session.memory.meet(b, a),
            "重复结识幂等（无向同一条边，反方向去重命中 ⇒ 仍算已认识）：返回 true",
        )
        val ghost = EntityId(765_432_109L)
        assertFalse(session.memory.meet(a, ghost), "端点不存在 ⇒ false，不建边")
        assertFalse(session.memory.meet(ghost, b), "端点不存在 ⇒ false，不建边")
        assertFalse(session.relationships.has(a, MemoryDeclarations.ACQUAINTANCE, ghost))
        assertFalse(session.relationships.has(ghost, MemoryDeclarations.ACQUAINTANCE, a))
    }

    // ------------------------------------------------------------------
    // 6. 台架面（mod-testing 内存台架复用，不改台架文件）：同一门语义在台架成立
    // ------------------------------------------------------------------

    private class Bench(val world: TestWorld, val mod: MemoryMod, val session: TestModSession) {
        val context: TestContext get() = session.context

        fun person(): EntityId = world.entities.create(MemoryDeclarations.PERSON)
    }

    private fun bench(gate: String = "false"): Bench {
        val world = TestWorld.of(MemoryDeclarations)
        val mod = MemoryMod()
        val session = runMod(mod, world, MemoryDeclarations)
        session.context.settingsRecorder.declare(MemoryDeclarations.TEACH_REQUIRES_KNOWS_KEY, gate)
        return Bench(world, mod, session)
    }

    @Test
    fun benchGatedTeachFollowsAcquaintanceTruthToo() {
        val f = bench(gate = "true")
        val teacher = f.person()
        val learner = f.person()
        val fact = f.mod.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "bench.gated", confidence = 50L),
        )

        assertFalse(
            f.mod.teach(learner = learner, fact = fact, teacher = teacher),
            "台架同口径：门开启且未结识 ⇒ 拒绝",
        )
        assertTrue(f.mod.meet(teacher, learner))
        assertTrue(f.mod.teach(learner = learner, fact = fact, teacher = teacher))

        // 台架摘边 → 门拒恢复（台架 MemoryRelationships 的无向对称与内核同口径）。
        assertTrue(f.world.relationships.remove(teacher, MemoryDeclarations.ACQUAINTANCE, learner))
        val fact2 = f.mod.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "bench.gated2", confidence = 50L),
        )
        assertFalse(f.mod.teach(learner = learner, fact = fact2, teacher = teacher), "摘边后拒恢复")
        assertFalse(f.world.relationships.has(learner, MemoryDeclarations.KNOWS, fact2))
    }

    @Test
    fun benchGateOffTeachStillSucceedsWithoutAcquaintance() {
        val f = bench()
        val teacher = f.person()
        val learner = f.person()
        val fact = f.mod.record(
            owner = teacher,
            FactValues(subject = teacher, predicate = "bench.open", confidence = 50L),
        )

        assertTrue(
            f.mod.teach(learner = learner, fact = fact, teacher = teacher),
            "台架缺省门关：无认识边照常传授（与接线前一致）",
        )
        assertTrue(f.world.relationships.has(learner, MemoryDeclarations.KNOWS, fact))
        assertFalse(f.world.relationships.has(teacher, MemoryDeclarations.ACQUAINTANCE, learner))
    }
}
