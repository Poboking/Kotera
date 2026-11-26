package com.eratw.port.modmemory

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.create
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.has
import com.eratw.port.modapi.valueOf
import com.eratw.port.modmemory.generated.MemoryKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 记忆 Mod 纵向切片（M9-1，CONTRACT-RT2-M91 判据 1–6）：拿**真实宿主**驱动**真实 mod**，
 * 基建照 `FamilyModTest`（真 DeclarationRegistry.freeze → 真 EntityKernel → 真 ModRuntimeHost
 * → host.install 断言 active → contextOf 驱动）。
 *
 * 「main 不许 import 引擎」的源码扫描不在本文件：那条判据由 `mod-memory/build.gradle.kts`
 * 的依赖方向在**编译期**保证（main source set 的 classpath 上没有 `com.eratw.port.core.*`），
 * 照 FamilyModTest 同条 KDoc 口径。
 */
class MemoryModTest {

    /** 同线程直接执行：证 mod 侧调用形状，不证宿主调度器（那是 GameTickDriver 的用例面）。 */
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
        val queries: MemoryQueries get() = memory.queries
        val entities get() = context.entities
        val relationships get() = context.relationships
    }

    private fun start(injectSettings: Boolean = true): Session {
        val snapshot = freeze(MemoryDeclarations.manifest)
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernelFor(snapshot),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { pkg -> if (pkg == MemoryDeclarations.PACKAGE_ID) 9 else 10 },
        )
        // per-mod 设置面同生产序（install 前注入）；声明默认值当测试替身，不改载体语义。
        // false = 模拟「宿主忘了注入快照」，用于钉 fail-closed 行为。
        if (injectSettings) {
            host.settings = mapOf(
                MemoryDeclarations.PACKAGE_ID to ModSettingsSnapshot(
                    MemoryDeclarations.PACKAGE_ID,
                    ModDeclarationAdapter.toDescriptor(MemoryDeclarations.manifest).settings,
                ),
            )
        }
        val mods = listOf(MemoryDeclarations.PACKAGE_ID to MemoryMod())
        val report = host.install(mods)
        assertEquals(1, report.size)
        assertNull(report.single().failure, "装载不得失败：${report.single()}；日志=$logs")
        assertTrue(report.single().active, "onEnable 后应为启用态")
        return Session(host, mods.single().second as MemoryMod)
    }

    // ------------------------------------------------------------------
    // 1. 判据 1/2：真宿主装载 + 五类字段齐备的 fact + KNOWS 边
    // ------------------------------------------------------------------

    @Test
    fun memoryModInstallsAndEnablesThroughTheRealHost() {
        val session = start()
        assertTrue(session.host.contextOf(MemoryDeclarations.PACKAGE_ID) != null)
        assertTrue(session.memory.isEnabled)
        assertEquals(0L, session.memory.decayTicks)
    }

    @Test
    fun recordedFactCarriesAllFiveFieldGroupsAndTheKnowsEdge() {
        val session = start()
        val owner = session.entities.create(MemoryDeclarations.PERSON)
        val about = session.entities.create(MemoryDeclarations.PERSON)

        val fact = session.memory.record(
            owner = owner,
            FactValues(
                subject = about,
                obj = owner,
                source = owner,
                predicate = "owesMoney",
                tags = "debt,urgent",
                occurredAt = 12L,
                acquiredAt = 13L,
                provenance = Provenance.FIRSTHAND,
                confidence = 80L,
                visibility = Visibility.PRIVATE,
                importance = Importance.HIGH,
            ),
        )

        assertTrue(session.entities.exists(fact), "create 之后实体必须存在")
        assertEquals(EntityState.ACTIVE, session.entities.stateOf(fact))
        // 引用三类：经 getRef 公开面读（与 INT 列逐位同源），不碰 .raw。
        assertEquals(about, session.entities.getRef(fact, MemoryKeys.subject))
        assertEquals(owner, session.entities.getRef(fact, MemoryKeys.obj))
        assertEquals(owner, session.entities.getRef(fact, MemoryKeys.source))
        // 文本两类
        assertEquals("owesMoney", session.entities.getStr(fact, MemoryKeys.predicate))
        assertEquals("debt,urgent", session.entities.getStr(fact, MemoryKeys.tags))
        // 游戏日两类
        assertEquals(12L, session.entities.getInt(fact, MemoryKeys.occurredAt))
        assertEquals(13L, session.entities.getInt(fact, MemoryKeys.acquiredAt))
        // 枚举/度量四类
        assertEquals(Provenance.FIRSTHAND.code, session.entities.getInt(fact, MemoryKeys.provenance))
        assertEquals(80L, session.entities.getInt(fact, MemoryKeys.confidence))
        assertEquals(Visibility.PRIVATE.code, session.entities.getInt(fact, MemoryKeys.visibility))
        assertEquals(Importance.HIGH.code, session.entities.getInt(fact, MemoryKeys.importance))
        // 知识边：owner --KNOWS--> fact，边值 = owner 的认知来源。
        assertTrue(
            session.relationships.has(owner, MemoryDeclarations.KNOWS, fact),
            "知识必须经 KNOWS 边可达",
        )
        assertEquals(
            Provenance.FIRSTHAND.code,
            session.relationships.valueOf(owner, MemoryDeclarations.KNOWS, fact),
            "边值 = owner 持有这条知识的 provenance",
        )
    }

    @Test
    fun recordWithNonexistentOwnerFailsClosedBeforeAnyMutation() {
        val session = start()
        val ghost = EntityId(987_654_321L)
        assertFailsWith<IllegalArgumentException> {
            session.memory.record(
                owner = ghost,
                FactValues(subject = ghost, predicate = "x", confidence = 1L),
            )
        }
        assertTrue(
            session.queries.allFacts().isEmpty(),
            "owner 不存在 = 整笔什么都没发生，不落无主 fact",
        )
    }

    // ------------------------------------------------------------------
    // 2. 判据 3：A 亲历的事实不会自动成为 B 的知识
    // ------------------------------------------------------------------

    @Test
    fun firsthandFactNeverBecomesAnotherOwnersKnowledgeWithoutAnExplicitKnowsEdge() {
        val session = start()
        val a = session.entities.create(MemoryDeclarations.PERSON)
        val b = session.entities.create(MemoryDeclarations.PERSON)

        val fact = session.memory.record(
            owner = a,
            FactValues(
                subject = b,
                predicate = "sawBSteal",
                provenance = Provenance.FIRSTHAND,
                confidence = 90L,
            ),
        )

        assertEquals(listOf(fact), session.queries.knowledgeOf(a), "A 的亲历知识经自己的边可达")
        assertTrue(session.queries.knowledgeOf(b).isEmpty(), "B 不得自动知道 A 亲历的事实")
        assertFalse(
            session.relationships.has(b, MemoryDeclarations.KNOWS, fact),
            "引擎不做任何自动传播：B 的 KNOWS 边必须不存在",
        )

        // 显式学得（teach 是唯一传播通道）：B 的边出现，认知来源记在 B 自己的边上。
        assertTrue(session.memory.teach(learner = b, fact = fact))
        assertTrue(session.relationships.has(b, MemoryDeclarations.KNOWS, fact))
        assertEquals(listOf(fact), session.queries.knowledgeOf(b))
        assertEquals(Provenance.HEARSAY, session.queries.provenanceOf(b, fact), "B 的认知来源 = 听闻")
        assertEquals(Provenance.FIRSTHAND, session.queries.provenanceOf(a, fact), "A 的认知来源 = 亲历")
        assertEquals(
            Provenance.FIRSTHAND.code,
            session.entities.getInt(fact, MemoryKeys.provenance),
            "fact 字段上的首录来源不被 teach 改写",
        )
    }

    @Test
    fun teachRefusesUnknownFactRatherThanSilentlySucceeding() {
        val session = start()
        val learner = session.entities.create(MemoryDeclarations.PERSON)
        val ghost = EntityId(123_456_789L)
        assertFalse(session.memory.teach(learner = learner, fact = ghost), "不存在的 fact 不得学得")
        assertTrue(session.queries.knowledgeOf(learner).isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. 判据 4：§21.5 查询（HasRelation + 组件谓词组合，EntityId 升序）
    // ------------------------------------------------------------------

    @Test
    fun combinedQueryFiltersByOwnershipAndConfidenceInAscendingOrder() {
        val session = start()
        val a = session.entities.create(MemoryDeclarations.PERSON)
        val b = session.entities.create(MemoryDeclarations.PERSON)

        val f1 = session.memory.record(
            owner = a, FactValues(subject = b, predicate = "p1", confidence = 80L),
        )
        val f2 = session.memory.record(
            owner = a, FactValues(subject = b, predicate = "p2", confidence = 30L),
        )
        val f3 = session.memory.record(
            owner = b, FactValues(subject = a, predicate = "p3", confidence = 90L),
        )
        val f4 = session.memory.record(
            owner = a, FactValues(subject = b, predicate = "p4", confidence = 99L),
        )

        // 单谓词（HasRelation）：知识集只按边可达性圈定，跨 owner 不串。
        assertEquals(
            listOf(f1, f2, f4).sortedBy { it.raw },
            session.queries.knowledgeOf(a),
            "A 的知识集 = A 的 KNOWS 边指向的 fact",
        )
        assertEquals(listOf(f3), session.queries.knowledgeOf(b))

        // 多谓词组合（HasRelation + IntFieldCmp，builder 摊平成 AND）：只有达标者入选。
        val confident = session.queries.knowledgeOf(a, minConfidence = 50L)
        assertEquals(
            listOf(f1, f4).sortedBy { it.raw },
            confident,
            "低置信 fact 与别人的 fact 都不得混入",
        )
        assertEquals(
            confident.sortedBy { it.raw },
            confident,
            "QueryApi 契约：结果必须 EntityId 升序",
        )
        assertTrue(session.queries.knowledgeOf(a, minConfidence = 1_000L).isEmpty())

        // EntityRef 谓词：按 subject 引用过滤（B 知道的关于 A 的事实）。
        assertEquals(listOf(f3), session.queries.factsAbout(a))
    }

    // ------------------------------------------------------------------
    // 4. 判据 5：每日衰减（Mod 规则）与 forget（摘边 + 删实体）
    // ------------------------------------------------------------------

    @Test
    fun decayLowersConfidenceDailyWithProvenanceDependentRatesAndFloorsAtZero() {
        val session = start()
        val owner = session.entities.create(MemoryDeclarations.PERSON)
        val firsthand = session.memory.record(
            owner = owner,
            FactValues(
                subject = owner, predicate = "pf",
                provenance = Provenance.FIRSTHAND, confidence = 50L,
            ),
        )
        val hearsay = session.memory.record(
            owner = owner,
            FactValues(
                subject = owner, predicate = "ph",
                provenance = Provenance.HEARSAY, confidence = 50L,
            ),
        )
        val nearlyGone = session.memory.record(
            owner = owner,
            FactValues(
                subject = owner, predicate = "pn",
                provenance = Provenance.FIRSTHAND, confidence = 1L,
            ),
        )

        session.memory.decayOneDay()
        assertEquals(49L, session.entities.getInt(firsthand, MemoryKeys.confidence), "亲历每日 -1")
        assertEquals(48L, session.entities.getInt(hearsay, MemoryKeys.confidence), "听闻每日 -2")
        assertEquals(0L, session.entities.getInt(nearlyGone, MemoryKeys.confidence), "下限 0")

        session.memory.decayOneDay()
        assertEquals(48L, session.entities.getInt(firsthand, MemoryKeys.confidence))
        assertEquals(46L, session.entities.getInt(hearsay, MemoryKeys.confidence))
        assertEquals(0L, session.entities.getInt(nearlyGone, MemoryKeys.confidence), "不得负数")

        assertEquals("2", session.context.storage.get(MemoryDeclarations.DECAY_TICKS))
        // 衰减只动 confidence：实体还在、边还在（衰减到 0 ≠ 遗忘）。
        assertTrue(session.entities.exists(hearsay))
        assertTrue(session.relationships.has(owner, MemoryDeclarations.KNOWS, hearsay))
    }

    @Test
    fun bindingAnUndeclaredJobIsRejectedRatherThanSilentlyAccepted() {
        // 反向证明：如果 onEnable 的 bind 是个 no-op，这条也会「过」—— 所以必须验未声明的那个。
        val session = start()
        assertFailsWith<IllegalArgumentException> {
            session.context.jobs.bind("memory.not.declared") {}
        }
    }

    @Test
    fun forgetRemovesTheEdgeAndDestroysTheFactForEveryKnower() {
        val session = start()
        val a = session.entities.create(MemoryDeclarations.PERSON)
        val b = session.entities.create(MemoryDeclarations.PERSON)
        val fact = session.memory.record(
            owner = a, FactValues(subject = b, predicate = "secret", confidence = 70L),
        )
        assertTrue(session.memory.teach(learner = b, fact = fact))

        assertTrue(session.memory.forget(owner = a, fact = fact), "知道者遗忘必须成功")
        assertFalse(session.entities.exists(fact), "知识本体被删")
        assertFalse(session.relationships.has(a, MemoryDeclarations.KNOWS, fact))
        assertFalse(
            session.relationships.has(b, MemoryDeclarations.KNOWS, fact),
            "destroy 级联清边：知识本体没了，其余知道者也不该留着悬空边",
        )
        assertTrue(session.queries.knowledgeOf(a).isEmpty())
        assertTrue(session.queries.knowledgeOf(b).isEmpty())
        assertFalse(session.memory.forget(owner = a, fact = fact), "二次遗忘 = false，不静默成功")
    }

    // ------------------------------------------------------------------
    // 5. 判据 6：禁用 → 数据保留 → 重启用 → 原值回来
    // ------------------------------------------------------------------

    @Test
    fun disablingTheModPreservesItsStorageAndReenablingRestoresTheValues() {
        val session = start()
        val owner = session.entities.create(MemoryDeclarations.PERSON)
        val fact = session.memory.record(
            owner = owner,
            FactValues(
                subject = owner, predicate = "kept",
                provenance = Provenance.FIRSTHAND, confidence = 66L,
            ),
        )
        assertTrue(session.queries.knowledgeOf(owner).isNotEmpty())
        repeat(5) { session.memory.decayOneDay() }
        session.host.shutdown() // 走 onDisable，把计数写进 mod 存储

        val bytes = session.host.storage.encode()
        assertTrue(bytes.isNotEmpty())

        // 下一次会话：记忆 mod 被禁用（不在 knownPackages 里）。
        val offline = ModStorageStore.load(bytes, knownPackages = emptySet())
        assertEquals(setOf(MemoryDeclarations.PACKAGE_ID), offline.inactivePackages())
        assertContentEquals(bytes, offline.encode(), "禁用态原样再编码必须逐字节相同")

        // 玩家重新启用它。
        offline.activate(MemoryDeclarations.PACKAGE_ID)
        val api = offline.apiFor(MemoryDeclarations.PACKAGE_ID)
        assertEquals("5", api.get(MemoryDeclarations.DECAY_TICKS), "重启用必须拿回原计数，不是 0")
        // 知识本体 = 实体/关系（随内核既有持久化），不走 mod 存储：衰减计数独立于它。
        assertTrue(session.entities.exists(fact), "内核侧知识数据与 mod 存储分域，禁用不删实体")
    }

    // ------------------------------------------------------------------
    // 6. 声明真源自检（判据 2/8：五类字段、KNOWS 形状、任务槽、字段键与访问器同源）
    // ------------------------------------------------------------------

    @Test
    fun declarationsAndAccessorsDescribeTheSameFiveCategorySchema() {
        val component = MemoryDeclarations.manifest.components.single()
        assertEquals(MemoryKeys.component.name, component.name)
        // 字段键口径 = 声明里的字段名本身（引擎按这张全局表寻址，组件名不参与）。
        assertEquals(
            setOf(
                MemoryKeys.subject.name,
                MemoryKeys.obj.name,
                MemoryKeys.source.name,
                MemoryKeys.predicate.name,
                MemoryKeys.tags.name,
                MemoryKeys.occurredAt.name,
                MemoryKeys.acquiredAt.name,
                MemoryKeys.provenance.name,
                MemoryKeys.confidence.name,
                MemoryKeys.visibility.name,
                MemoryKeys.importance.name,
            ),
            component.fields.map { it.name }.toSet(),
            "五类字段齐备（判据 2），多一字段少一字段都算漂移",
        )
        // 引用三类：isEntityRef = true 且类型 INT（core 不变量）。
        val refs = component.fields.filter { it.isEntityRef }
        assertEquals(
            setOf(MemoryKeys.subject.name, MemoryKeys.obj.name, MemoryKeys.source.name),
            refs.map { it.name }.toSet(),
        )
        for (field in refs) {
            assertEquals(FieldType.INT, field.type, "isEntityRef 只对整型字段合法：${field.name}")
        }
        // 文本两类 = STR；其余六类 = INT。
        for (name in listOf(MemoryKeys.predicate.name, MemoryKeys.tags.name)) {
            assertEquals(FieldType.STR, component.fields.single { it.name == name }.type)
        }
        for (name in listOf(
            MemoryKeys.occurredAt.name,
            MemoryKeys.acquiredAt.name,
            MemoryKeys.provenance.name,
            MemoryKeys.confidence.name,
            MemoryKeys.visibility.name,
            MemoryKeys.importance.name,
        )) {
            assertEquals(FieldType.INT, component.fields.single { it.name == name }.type)
        }
        // 字段键 = 线格式字面量（生成器与声明共用这批键，漂移即红）。
        // `object` 是 Kotlin 硬关键字 ⇒ 属性名覆盖成 `obj`，而**键仍是 `object`**（线格式零变更）。
        assertEquals("subject", MemoryKeys.subject.name)
        assertEquals("object", MemoryKeys.obj.name)
        assertEquals("source", MemoryKeys.source.name)
        assertEquals("predicate", MemoryKeys.predicate.name)
        assertEquals("tags", MemoryKeys.tags.name)
        assertEquals("occurredAt", MemoryKeys.occurredAt.name)
        assertEquals("acquiredAt", MemoryKeys.acquiredAt.name)
        assertEquals("provenance", MemoryKeys.provenance.name)
        assertEquals("confidence", MemoryKeys.confidence.name)
        assertEquals("visibility", MemoryKeys.visibility.name)
        assertEquals("importance", MemoryKeys.importance.name)
        // 枚举码值一经发布即固化（进 INT 字段与边值，只增不改）。
        assertEquals(1L, Provenance.FIRSTHAND.code)
        assertEquals(2L, Provenance.HEARSAY.code)
        assertEquals(0L, Visibility.PRIVATE.code)
        assertEquals(2L, Visibility.PUBLIC.code)
        assertEquals(2L, Importance.NORMAL.code)
        assertEquals(4L, Importance.CRITICAL.code)
    }

    @Test
    fun knowsEdgeAndDecayJobAreDeclaredThroughTheSameManifest() {
        val manifest = MemoryDeclarations.manifest
        // 原型：person 无组件、fact 挂 factData。
        assertEquals(
            listOf(MemoryDeclarations.PERSON.name, MemoryDeclarations.FACT.name),
            manifest.archetypes.map { it.name },
        )
        assertEquals(emptyList(), manifest.archetypes.first().components)
        assertEquals(
            listOf(MemoryKeys.component.name),
            manifest.archetypes.last().components,
        )
        // KNOWS：有向、无自动反向边、destroy 级联清边（缺省 true，KDoc 有裁定）。
        // MODDX4-B4 起另有 ACQUAINTANCE 第二关系（形状由 ModDx4MemoryKnowsTest 钉），这里按名点名。
        val knows = manifest.relationships.single { it.name == MemoryDeclarations.KNOWS.name }
        assertEquals(MemoryDeclarations.KNOWS.name, knows.name)
        assertTrue(knows.directed)
        assertFalse(knows.autoInverse, "知识不设反向边（第二张账本），查询走入边方向")
        assertNull(knows.inverseName)
        assertTrue(knows.cascadeOnDestroy, "fact 被删时 KNOWS 边随之清掉是本模型的正确语义")
        // 衰减任务槽：每游戏日一次（判据 5）。
        val job = manifest.jobs.single()
        assertEquals(MemoryDeclarations.DECAY.name, job.jobId)
        assertEquals(0L, job.firstDay)
        assertEquals(1L, job.everyDays)
    }

    @Test
    fun settingsNotInjectedFailsClosedInsteadOfSilentDefault() {
        // 载体读设置走 context.settings：宿主没在 install 前注入按包快照时整面 fail-closed
        // （SettingsApi 口径），**不**静默回退声明默认值。反向证明：把 decayOneDay 里的读值
        // 改回常量 rate=1，本用例仍绿但 MemorySettingsConsumptionTest 的 rate=2 用例立红
        // —— 两条一起钉住「读值确实来自设置面」。
        val session = start(injectSettings = false)
        val failure = assertFailsWith<UnsupportedOperationException> { session.memory.decayOneDay() }
        assertTrue(
            "设置面未装配" in failure.message.orEmpty(),
            "异常必须点名设置面缺口：${failure.message}",
        )
        assertEquals(0L, session.memory.decayTicks, "读值在改计数之前 ⇒ 抛时零副作用")
    }
}
