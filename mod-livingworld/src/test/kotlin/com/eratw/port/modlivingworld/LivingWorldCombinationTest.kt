package com.eratw.port.modlivingworld

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.DecisionDrawKey
import com.eratw.port.core.mod.DecisionFailurePhase
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.DecisionRandomSource
import com.eratw.port.core.mod.DecisionReasonCodes
import com.eratw.port.core.mod.DecisionReport
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.Sha256DecisionRandom
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.add
import com.eratw.port.modapi.get
import com.eratw.port.modapi.has
import com.eratw.port.modapi.queryPlan
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setValue
import com.eratw.port.modlivingworld.generated.LwEventKeys
import com.eratw.port.modlivingworld.generated.NpcKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NPC Living World Demo 纵向切片（M10-1，CONTRACT-RT2-M101 §1 判据 1–7）：**四个真实 Mod 包**
 * 装进**真实宿主** + **真 DecisionPipeline**（K3-B G1 的缺省参数透传位，ModRuntimeHost.kt:50
 * `decisionPipeline`），基建照 FamilyModTest / MemoryModTest（真 DeclarationRegistry.freeze →
 * 真 EntityKernel → 真 ModRuntimeHost → install 断言 active → contextOf 驱动）。
 *
 * 判据编号都写进用例名；四个包的仲裁参与经 `context.actions` 的包作用域 facade 注册（跨包
 * 冒名由引擎按包身份拒收），日程由 JobApi 槽驱动（判据 1），传播闸 = KNOWS 边可达性（判据 3）。
 *
 * 「main 不许 import 引擎」的源码扫描不在本文件：由 `mod-livingworld/build.gradle.kts` 的
 * 依赖方向在编译期保证（main source set 的 classpath 上没有 `com.eratw.port.core.*`），
 * 照 FamilyModTest / MemoryModTest 同条 KDoc 口径。
 */
class LivingWorldCombinationTest {

    /** 同线程直接执行：证 mod 侧调用形状，不证宿主调度器（那是 GameTickDriver 的用例面）。 */
    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    /**
     * 决策随机流 spy（反向证明②）：按 [high] 恒答 0（抽首个候选）或 bound-1（抽末个候选）。
     * 同一候选集下两种应答给出**确定性不同**的胜者 —— 加权抽取是承重墙，不是装饰。
     */
    private class TwoAnswerRandom(private val high: Boolean) : DecisionRandomSource {
        override fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long =
            if (high) bound - 1L else 0L
    }

    private val logs = mutableListOf<String>()

    /** 测试侧宿主驱动的嵌套仲裁报告（判据 1 的 RESERVATION_CONFLICT 证据面）。 */
    private val nestedReports = mutableListOf<DecisionReport>()

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

    /** 四包声明（顺序 = 包链接序 = install 序 = 仲裁收集序的 packageRank）。 */
    private fun productionManifests(): List<ModManifest> = listOf(
        CharacterDeclarations.manifest,
        RelationshipDeclarations.manifest,
        MemoryDeclarations.manifest,
        EventDeclarations.manifest,
    )

    /** 四包 mod 实例的 holder（Session 构造器瘦身；命名访问器见 [Session]）。 */
    private class LwMods(
        val character: CharacterMod,
        val relationship: RelationshipMod,
        val memory: MemoryMod,
        val event: EventMod,
    )

    private class Session(
        val host: ModRuntimeHost,
        val pipeline: DecisionPipeline,
        val kernel: EntityKernel,
        val mods: LwMods,
    ) {
        val character: CharacterMod get() = mods.character
        val relationship: RelationshipMod get() = mods.relationship
        val memory: MemoryMod get() = mods.memory
        val event: EventMod get() = mods.event
        val characterContext: ModContext get() = checkNotNull(host.contextOf(CharacterDeclarations.PACKAGE_ID))
        val memoryContext: ModContext get() = checkNotNull(host.contextOf(MemoryDeclarations.PACKAGE_ID))
        val eventContext: ModContext get() = checkNotNull(host.contextOf(EventDeclarations.PACKAGE_ID))

        /** 两名演示 NPC（EntityId 升序；四包共享同一内核）。 */
        val npcA: EntityId get() = character.queries.npcs().first()
        val npcB: EntityId get() = character.queries.npcs().last()
    }

    private fun start(
        decisionSeed: Long = DEFAULT_SEED,
        random: DecisionRandomSource = Sha256DecisionRandom,
        diagnostics: RuntimeDiagnostics? = null,
    ): Session {
        val manifests = productionManifests()
        val snapshot = freeze(*manifests.toTypedArray())
        val kernel = kernelFor(snapshot)
        val packageIds = manifests.map { it.packageId }
        val pipeline = DecisionPipeline.of(packageIds, decisionSeed, random = random, diagnostics = diagnostics)
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { pkg -> packageIds.indexOf(pkg) + 2 },
            decisionPipeline = pipeline,
        )
        val character = CharacterMod()
        val relationship = RelationshipMod()
        val memory = MemoryMod()
        val event = EventMod()
        val mods = packageIds.zip(listOf<EraMod>(character, relationship, memory, event))
        val report = host.install(mods)
        assertEquals(4, report.size)
        for (state in report) {
            assertNull(state.failure, "装载不得失败：$state；日志=$logs")
            assertTrue(state.active, "onEnable 后应为启用态：${state.packageId}")
        }
        // 嵌套仲裁驱动缝：宿主（测试）传真管线（判据 1 的 RESERVATION_CONFLICT 路径）。
        character.decisionDriver = CharacterMod.DecisionDriver { domain, metadata ->
            nestedReports += pipeline.run(ActionSelectionContext(domain, EVENING_TIME, metadata = metadata))
        }
        return Session(host, pipeline, kernel, LwMods(character, relationship, memory, event))
    }

    /** 傍晚仲裁的选择上下文（判据 1）。 */
    private fun evening(day: Long, metadata: Map<String, String> = emptyMap()) =
        ActionSelectionContext(CharacterDeclarations.DOMAIN_EVENING, GameTime(day, "DAY_END"), metadata = metadata)

    /** 长活仲裁的选择上下文（判据 2）。 */
    private fun longCraft(npc: EntityId, day: Long = 3L) = ActionSelectionContext(
        CharacterDeclarations.DOMAIN_ACTION,
        GameTime(day, "DAY_END"),
        metadata = mapOf(CharacterDeclarations.METADATA_NPC to npc.raw.toString()),
    )

    /** 事件仲裁的选择上下文（判据 5/7）。 */
    private fun eventRound(set: String, day: Long = 10L, extra: Map<String, String> = emptyMap()) =
        ActionSelectionContext(
            EventDeclarations.DOMAIN_EVENT,
            GameTime(day, "DAY_END"),
            metadata = mapOf(CharacterDeclarations.METADATA_SET to set) + extra,
        )

    /** 淘汰记录的稳定投影（DecisionElimination 无结构 equals，逐字段比）。 */
    private fun eliminationsOf(report: DecisionReport) =
        report.eliminations.map { Triple(it.packageId, it.candidateKey, it.reasonCode) }

    /** 两名 NPC 里非 [winner] 的那名。 */
    private fun otherNpc(winner: EntityId, session: Session): EntityId =
        session.character.queries.npcs().single { it != winner }

    // ------------------------------------------------------------------
    // 判据 1：独立日程 + 互斥不双占（真 DecisionPipeline，RESERVATION_CONFLICT）
    // ------------------------------------------------------------------

    @Test
    fun `判据1 独立日程经JobApi驱动 两角色抢同一资源恰一成功一淘汰`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB
        // 日程由 JobApi 静态槽驱动（bind 在 onEnable 已成功；槽体 = advanceDay）：性格决定槽位。
        session.character.advanceDay()
        assertEquals(CharacterDeclarations.SLOT_WORKSHOP, session.character.queries.eveningSlotOf(a), "warmth=30 排工坊")
        assertEquals(CharacterDeclarations.SLOT_WORKSHOP, session.character.queries.eveningSlotOf(b))
        // 独立日程：改 B 自己的性格字段，下一日只有 B 的槽位变化（A 不动）。
        session.characterContext.entities.setInt(b, NpcKeys.warmth, 70L)
        session.character.advanceDay()
        assertEquals(CharacterDeclarations.SLOT_STROLL, session.character.queries.eveningSlotOf(b))
        assertEquals(CharacterDeclarations.SLOT_WORKSHOP, session.character.queries.eveningSlotOf(a))
        // 复原：两人今晚都要工坊（同一互斥资源的两个候选）。
        session.characterContext.entities.setInt(b, NpcKeys.warmth, 40L)
        session.character.advanceDay()

        val outer = session.pipeline.run(evening(10L))
        assertEquals(ActionExecutionOutcome.COMPLETED, outer.outcome, "外轮必须有胜者且执行完成")
        assertEquals(CharacterDeclarations.PACKAGE_ID, outer.chosenPackageId, "胜者候选归属角色包")
        val winner = assertNotNull(outer.chosen).actor
        assertEquals(CharacterDeclarations.ACT_CRAFT, session.character.queries.activityOf(winner))

        // 嵌套轮（胜者 handler 执行期间驱动，工坊键仍 held）：另一人的手艺候选吃 RESERVATION_CONFLICT。
        val nested = assertNotNull(nestedReports.lastOrNull(), "宿主驱动缝必须把嵌套仲裁报告带回测试侧")
        val conflict = nested.eliminations.single()
        assertEquals(DecisionReasonCodes.RESERVATION_CONFLICT, conflict.reasonCode)
        assertEquals(CharacterDeclarations.PACKAGE_ID, conflict.packageId)
        val loser = otherNpc(winner, session)
        assertEquals(
            "${CharacterMod.HANDLER_CRAFT}:${loser.raw}", conflict.candidateKey,
            "被淘汰的必须是另一人的工坊候选",
        )
        // 恰一成功一淘汰：恰一人 craft（工坊不双占），另一人回落散步。
        val crafters = session.character.queries.npcs().filter {
            session.character.queries.activityOf(it) == CharacterDeclarations.ACT_CRAFT
        }
        assertEquals(listOf(winner), crafters, "工坊恰被一人占用")
        assertEquals(
            CharacterDeclarations.ACT_STROLL,
            session.character.queries.activityOf(loser),
            "被淘汰者回落到无预留的散步候选",
        )
    }

    // ------------------------------------------------------------------
    // 根因回归：散步槽 NPC 在晚间仲裁后必须被解析（修复前永停在 ""）
    // ------------------------------------------------------------------

    @Test
    fun `判据1 混合槽位 散步槽NPC经晚间仲裁后活动被解析`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB
        // 混合阵容：A 工坊槽、B 散步槽（warmth=70 >= 阈值）
        session.character.advanceDay()
        session.characterContext.entities.setInt(b, NpcKeys.warmth, 70L)
        session.character.advanceDay()
        assertEquals(CharacterDeclarations.SLOT_WORKSHOP, session.character.queries.eveningSlotOf(a))
        assertEquals(CharacterDeclarations.SLOT_STROLL, session.character.queries.eveningSlotOf(b))
        assertEquals("", session.character.queries.activityOf(b), "仲裁前 B 未解析")

        val outer = session.pipeline.run(evening(10L))
        assertEquals(ActionExecutionOutcome.COMPLETED, outer.outcome)
        assertEquals(CharacterDeclarations.ACT_CRAFT, session.character.queries.activityOf(a))
        // 钉子：修复前 next 过滤只挑工坊槽、STROLL handler 又不驱动嵌套 ⇒ B 永远停在 ""；
        // 修复后胜者 handler 把未解析的 B 交给嵌套轮，B 的散步候选在该轮单独胜出。
        assertEquals(
            CharacterDeclarations.ACT_STROLL,
            session.character.queries.activityOf(b),
            "散步槽 NPC 必须在晚间仲裁后被解析为 stroll，不得停在未解析",
        )
        val nested = assertNotNull(nestedReports.lastOrNull(), "必须为散步槽 NPC 单独驱动一轮嵌套仲裁")
        assertEquals(b, assertNotNull(nested.chosen).actor, "嵌套轮胜者应是被单独解析的散步槽 NPC")
    }

    @Test
    fun `判据1 全散步槽 所有NPC经晚间仲裁后活动均被解析`() {
        val session = start()
        session.character.advanceDay()
        session.characterContext.entities.setInt(session.npcA, NpcKeys.warmth, 70L)
        session.characterContext.entities.setInt(session.npcB, NpcKeys.warmth, 70L)
        session.character.advanceDay()
        assertEquals(CharacterDeclarations.SLOT_STROLL, session.character.queries.eveningSlotOf(session.npcA))
        assertEquals(CharacterDeclarations.SLOT_STROLL, session.character.queries.eveningSlotOf(session.npcB))

        val outer = session.pipeline.run(evening(10L))
        assertEquals(ActionExecutionOutcome.COMPLETED, outer.outcome)
        // 钉子：修复前 STROLL handler 只写 activity 不驱动嵌套 ⇒ 外轮只解析一名，另一名停在 ""。
        for (entity in session.character.queries.npcs()) {
            assertEquals(
                CharacterDeclarations.ACT_STROLL,
                session.character.queries.activityOf(entity),
                "全员散步槽时每个 NPC 都应被解析为 stroll（修复前有一名停在未解析）",
            )
        }
        assertEquals(1, nestedReports.size, "仅首名胜者驱动一轮嵌套解析其余 NPC，不重复展开")
    }

    // ------------------------------------------------------------------
    // 反向证明①：摘预留持有期（释放后再仲裁）⇒ 双占出现
    // ------------------------------------------------------------------

    @Test
    fun `反向证明 预留释放后再仲裁同一资源双占出现`() {
        val session = start()
        session.character.advanceDay()
        val winner = assertNotNull(session.pipeline.run(evening(10L)).chosen).actor
        assertEquals(CharacterDeclarations.ACT_CRAFT, session.character.queries.activityOf(winner))
        // 外轮结束（预留已释放）后，另一人的工坊候选不再有 held 冲突 ⇒ 直接执行 ⇒ 双占。
        val other = otherNpc(winner, session)
        val late = session.pipeline.run(
            evening(10L, mapOf(CharacterDeclarations.METADATA_NPC to other.raw.toString())),
        )
        assertEquals(ActionExecutionOutcome.COMPLETED, late.outcome, "预留释放后冲突消失（互斥由持有期承担）")
        val crafters = session.character.queries.npcs().count {
            session.character.queries.activityOf(it) == CharacterDeclarations.ACT_CRAFT
        }
        assertEquals(
            2, crafters,
            "摘掉预留持有期 ⇒ 判据 1 红出双占（恰一成功一淘汰不成立）",
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：发起 / 中断（取消令牌原子段后生效 CANCELED）/ 恢复（重新生成候选）
    // ------------------------------------------------------------------

    @Test
    fun `判据2 长活可发起可中断可恢复且取消不回滚原子段`() {
        val session = start()
        val npc = session.npcA
        // 发起：候选 → 仲裁 → handler 两段原子段全落（COMPLETED）。
        val initiated = session.pipeline.run(longCraft(npc))
        assertEquals(ActionExecutionOutcome.COMPLETED, initiated.outcome)
        assertEquals(2L, session.character.queries.craftStageOf(npc), "发起 = 两段原子段完成")
        assertEquals(CharacterMod.ACT_LONG_CRAFT_DONE, session.character.queries.activityOf(npc))

        // 中断：外部请求 → Mod 重新生成候选（provider 无状态每轮重出）→ 原子段一后令牌生效。
        session.character.requestInterrupt(npc)
        val interrupted = session.pipeline.run(longCraft(npc))
        assertEquals(ActionExecutionOutcome.CANCELED, interrupted.outcome, "取消令牌在原子段边界生效")
        assertEquals(1L, session.character.queries.craftStageOf(npc), "原子段一写入不回滚（引擎不回滚已发生的写入）")
        assertEquals(CharacterMod.ACT_LONG_CRAFT_STAGE1, session.character.queries.activityOf(npc), "原子段二没跑")

        // 恢复：中断请求一次性已消费，Mod 重新生成的候选下一轮完整执行。
        val resumed = session.pipeline.run(longCraft(npc))
        assertEquals(ActionExecutionOutcome.COMPLETED, resumed.outcome)
        assertEquals(2L, session.character.queries.craftStageOf(npc), "恢复 = 重跑两段原子段")
        assertEquals(CharacterMod.ACT_LONG_CRAFT_DONE, session.character.queries.activityOf(npc))
    }

    // ------------------------------------------------------------------
    // 判据 3：亲历不自动传播（FIRSTHAND / HEARSAY）
    // ------------------------------------------------------------------

    @Test
    fun `判据3 亲历事实不自动传播 听闻只能经显式teach获得`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB
        val fact = session.memory.recordWitness(a, b, "lent a tool", 12L)

        assertEquals(listOf(fact), session.memory.knowledgeOf(a), "A 的亲历知识经自己的 KNOWS 边可达")
        assertTrue(session.memory.knowledgeOf(b).isEmpty(), "B 不得自动知道 A 亲历的事实")
        assertFalse(
            session.memoryContext.relationships.has(b, MemoryDeclarations.KNOWS, fact),
            "引擎不做任何自动传播：B 的 KNOWS 边必须不存在",
        )
        assertEquals(Provenance.FIRSTHAND, session.memory.provenanceOf(a, fact))

        // 显式传播是唯一通道：B 的边出现，认知来源记在 B 自己的边上（HEARSAY）。
        assertTrue(session.memory.teach(learner = b, fact = fact))
        assertEquals(listOf(fact), session.memory.knowledgeOf(b))
        assertEquals(Provenance.HEARSAY, session.memory.provenanceOf(b, fact))
        assertEquals(Provenance.FIRSTHAND, session.memory.provenanceOf(a, fact), "A 的认知来源不被改写")
    }

    // ------------------------------------------------------------------
    // 判据 4：两 Mod 合法扩展同一对实体互不覆盖（声明事务共存）
    // ------------------------------------------------------------------

    @Test
    fun `判据4 关系包与事件包扩展同一对实体互不覆盖`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB

        // 关系包：双向独立好感 + 备注字段。
        assertTrue(session.relationship.setAffinity(a, b, 5L))
        assertTrue(session.relationship.setAffinity(b, a, 3L))
        assertTrue(session.relationship.recordBondNotes(a, "workshop mate"))
        // 事件包：自己的边型（紧张度）+ 自己的字段（事件读数），落在同一对实体上。
        val eventRelations = session.eventContext.relationships
        assertTrue(eventRelations.add(a, EventDeclarations.TENSION, b))
        assertTrue(eventRelations.setValue(a, EventDeclarations.TENSION, b, 42L))
        assertTrue(session.eventContext.entities.setInt(a, LwEventKeys.seen, 1L))

        // 互不覆盖：各自 kind 的边值、各自字段的数据全部独立保留。
        assertEquals(5L, session.relationship.affinityOf(a, b), "关系包正向好感不被事件包写数波及")
        assertEquals(3L, session.relationship.affinityOf(b, a), "双向独立：反向边是独立数据")
        assertEquals(42L, session.event.tensionOf(a, b), "事件包自己的边型持自己的值")
        assertEquals("workshop mate", session.relationship.bondNotesOf(a))
        assertEquals(1L, session.event.eventSeenOf(a))
        assertEquals(0L, session.event.tensionOf(b, a), "事件包只写了 a→b 方向，反向不受影响")
    }

    // ------------------------------------------------------------------
    // 判据 5：多候选确定性 + 可解释淘汰 + 同 seed 重放
    // ------------------------------------------------------------------

    @Test
    fun `判据5 淘汰逐候选带原因码且同seed重放逐位一致`() {
        val first = start()
        first.relationship.setAffinity(first.npcA, first.npcB, 5L)
        val tierRound = first.pipeline.run(eventRound(EventDeclarations.SET_TIER))
        // 可解释淘汰：恒假约束 → CONSTRAINT_FALSE；低优先级层 → PRIORITY_LOST；全部带包归属。
        val prank = tierRound.eliminations.single { it.candidateKey == EventMod.HANDLER_PRANK }
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, prank.reasonCode)
        assertEquals(EventDeclarations.PACKAGE_ID, prank.packageId)
        val nap = tierRound.eliminations.single { it.candidateKey == EventMod.HANDLER_NAP }
        assertEquals(DecisionReasonCodes.PRIORITY_LOST, nap.reasonCode)
        assertTrue(tierRound.eliminations.none { it.candidateKey == EventMod.HANDLER_TEA }, "达标者不淘汰")
        val winnerKey = assertNotNull(tierRound.chosen).candidateKey
        assertTrue(winnerKey in listOf(EventMod.HANDLER_TEA, EventMod.HANDLER_GOSSIP), "胜者出自最高层")

        // 冷却演示：闹鬼首轮 COMPLETED，同日二轮 COOLDOWN（原因码点名）。
        val hauntFirst = first.pipeline.run(eventRound(EventDeclarations.SET_COOLDOWN))
        assertEquals(ActionExecutionOutcome.COMPLETED, hauntFirst.outcome)
        val hauntSecond = first.pipeline.run(eventRound(EventDeclarations.SET_COOLDOWN))
        val cooldown = hauntSecond.eliminations.single { it.candidateKey == EventMod.HANDLER_HAUNT }
        assertEquals(DecisionReasonCodes.COOLDOWN, cooldown.reasonCode)
        assertEquals(ActionExecutionOutcome.NONE, hauntSecond.outcome, "唯一候选被冷却 ⇒ 无执行")

        // 同 seed 重放：全新会话（新内核/新宿主/新管线，同种子）逐位一致。
        val replay = start()
        replay.relationship.setAffinity(replay.npcA, replay.npcB, 5L)
        val replayRound = replay.pipeline.run(eventRound(EventDeclarations.SET_TIER))
        assertEquals(winnerKey, assertNotNull(replayRound.chosen).candidateKey, "同 seed 同键 ⇒ 同胜者")
        assertEquals(EventDeclarations.PACKAGE_ID, replayRound.chosenPackageId)
        assertEquals(eliminationsOf(tierRound), eliminationsOf(replayRound), "淘汰记录逐位一致")
    }

    @Test
    fun `判据5 负控 仲裁抽取不消费原版RAND序列且应答决定胜者`() {
        // 决策随机流独立性（§21.3.5，core DecisionPipelineTest 用例 18 的 Mod 层复刻）：
        // 原版序列参考 = Sfmt19937(12345)，仲裁期间无人消费它，事后逐位一致。
        val expected = Sfmt19937(12345L).let { rng -> List(20) { rng.next(1_000L) } }
        val untouched = Sfmt19937(12345L)
        val session = start()
        session.relationship.setAffinity(session.npcA, session.npcB, 5L)
        repeat(20) { session.pipeline.run(eventRound(EventDeclarations.SET_TIER, day = 10L)) }
        val after = List(20) { untouched.next(1_000L) }
        assertEquals(expected, after, "仲裁抽取不得消费原版 RAND 序列")

        // 反向证明②：摘掉加权抽取（恒取首候选的假实现）在这里必红 —— 应答 0 与 bound-1
        // 给出确定性不同的胜者，证明抽取是承重墙。
        val low = start(random = TwoAnswerRandom(high = false))
        low.relationship.setAffinity(low.npcA, low.npcB, 5L)
        val lowWinner = assertNotNull(low.pipeline.run(eventRound(EventDeclarations.SET_TIER)).chosen)
        assertEquals(EventMod.HANDLER_TEA, lowWinner.candidateKey, "应答 0 = 首个高层候选（茶会 weight 3）")
        val high = start(random = TwoAnswerRandom(high = true))
        high.relationship.setAffinity(high.npcA, high.npcB, 5L)
        val highWinner = assertNotNull(high.pipeline.run(eventRound(EventDeclarations.SET_TIER)).chosen)
        assertEquals(EventMod.HANDLER_GOSSIP, highWinner.candidateKey, "应答 bound-1 = 末个高层候选（闲话）")
    }

    // ------------------------------------------------------------------
    // 判据 6：存读档往返无损 + 禁用不丢 + 重启用原值回来
    // ------------------------------------------------------------------

    @Test
    fun `判据6 实体边与决策态往返无损且禁用包数据保留重启用原值回来`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB
        session.character.advanceDay()
        session.character.advanceDay()
        assertTrue(session.relationship.setAffinity(a, b, 5L))
        assertTrue(session.relationship.setAffinity(b, a, 3L))
        assertTrue(session.relationship.recordBondNotes(a, "workshop mate"))
        val fact = session.memory.recordWitness(a, b, "lent a tool", 12L)
        assertTrue(session.memory.teach(b, fact))
        session.pipeline.run(eventRound(EventDeclarations.SET_COOLDOWN))
        val eventRoundsAtShutdown = session.event.eventRounds

        // mod 存储域 + 内核域（实体/关系/字段）+ 决策态三面往返，恢复会话逐值读回（判据 6）。
        session.host.shutdown()
        assertStorageDisableRoundTrip(session, eventRoundsAtShutdown)
        val restored = installRestoredSession(session)
        assertEquals(5L, restored.relationship.affinityOf(a, b), "好感边值随快照往返无损")
        assertEquals(3L, restored.relationship.affinityOf(b, a))
        assertEquals("workshop mate", restored.relationship.bondNotesOf(a))
        assertEquals(listOf(fact), restored.memory.knowledgeOf(a), "KNOWS 边可达性随快照往返无损")
        assertEquals(
            CharacterDeclarations.SLOT_WORKSHOP,
            restored.character.queries.eveningSlotOf(a),
            "日程字段（内核列）随快照往返无损；EntityId 槽+代+包位恒等",
        )
        // 决策态续跑：恢复管线与原管线从同一快照出发，同一轮报告一致（M6-2 跨存读档延续）。
        val continued = restored.pipeline.run(eventRound(EventDeclarations.SET_COOLDOWN))
        val continuedOriginal = DecisionPipeline.of(
            productionManifests().map { it.packageId },
            DecisionStateCodec.decode(DecisionStateCodec.encode(session.pipeline.snapshotState())),
        ).run(eventRound(EventDeclarations.SET_COOLDOWN))
        assertEquals(
            continuedOriginal.chosen?.candidateKey, continued.chosen?.candidateKey,
            "恢复管线与原管线同快照同轮必须一致",
        )
    }

    /** 判据 6 的 mod 存储域往返：事件包禁用 → 数据保留 → 原样再编码 → 重启用原值回来。 */
    private fun assertStorageDisableRoundTrip(session: Session, eventRoundsAtShutdown: Long) {
        val storageBytes = session.host.storage.encode()
        assertTrue(storageBytes.isNotEmpty())
        val offline = ModStorageStore.load(
            storageBytes,
            knownPackages = setOf(
                CharacterDeclarations.PACKAGE_ID,
                RelationshipDeclarations.PACKAGE_ID,
                MemoryDeclarations.PACKAGE_ID,
            ),
        )
        assertEquals(setOf(EventDeclarations.PACKAGE_ID), offline.inactivePackages(), "缺席包 = 禁用态")
        assertContentEquals(storageBytes, offline.encode(), "禁用态原样再编码必须逐字节相同")
        offline.activate(EventDeclarations.PACKAGE_ID)
        assertEquals(
            eventRoundsAtShutdown.toString(),
            offline.apiFor(EventDeclarations.PACKAGE_ID).get(EventDeclarations.EVENT_ROUNDS),
            "重启用必须拿回原计数，不是 0",
        )
        assertEquals(
            "2",
            offline.apiFor(CharacterDeclarations.PACKAGE_ID).get(CharacterDeclarations.SCHEDULE_DAYS),
            "未禁用包的计数同时在场（分域隔离）",
        )
    }

    /** 判据 6 的恢复会话读数 holder（四包 mod 实例 + 决策态恢复的管线）。 */
    private class RestoredSession(
        val character: CharacterMod,
        val relationship: RelationshipMod,
        val memory: MemoryMod,
        val pipeline: DecisionPipeline,
    )

    /**
     * 判据 6 的恢复会话：内核快照 export → 空内核 restore（既有持久化面，EntityId 槽+代+包位
     * 恒等）+ mod 存储域重建 + 决策态编解码恢复，然后重装四包。
     */
    private fun installRestoredSession(session: Session): RestoredSession {
        val manifests = productionManifests()
        val packageIds = manifests.map { it.packageId }
        val snapshot = freeze(*manifests.toTypedArray())
        val restoredKernel = kernelFor(snapshot)
        restoredKernel.restore(session.kernel.exportSnapshot())
        val pipeline = DecisionPipeline.of(
            packageIds,
            DecisionStateCodec.decode(DecisionStateCodec.encode(session.pipeline.snapshotState())),
        )
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = restoredKernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { pkg -> packageIds.indexOf(pkg) + 2 },
            decisionPipeline = pipeline,
        )
        val character = CharacterMod()
        val relationship = RelationshipMod()
        val memory = MemoryMod()
        val event = EventMod()
        val report = host.install(packageIds.zip(listOf<EraMod>(character, relationship, memory, event)))
        for (state in report) {
            assertNull(state.failure, "恢复会话装载不得失败：$state；日志=$logs")
        }
        return RestoredSession(character, relationship, memory, pipeline)
    }

    // ------------------------------------------------------------------
    // 判据 7：失败/淘汰可定位到 package / candidate / condition
    // ------------------------------------------------------------------

    @Test
    fun `判据7 失败与淘汰可定位到包候选与条件`() {
        val diagnostics = RuntimeDiagnostics()
        val session = start(diagnostics = diagnostics)
        val failureRound = session.pipeline.run(eventRound(EventDeclarations.SET_FAILURE))
        // 约束谓词抛异常：failure 记录（包 + 候选 + 阶段）与淘汰记录（CONSTRAINT_FALSE）双落。
        val boomFailure = failureRound.failures.single {
            it.key == EventMod.HANDLER_BOOM && it.phase == DecisionFailurePhase.CONSTRAINT
        }
        assertEquals(EventDeclarations.PACKAGE_ID, boomFailure.packageId)
        val boomElimination = failureRound.eliminations.single { it.candidateKey == EventMod.HANDLER_BOOM }
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, boomElimination.reasonCode)
        assertEquals(EventDeclarations.PACKAGE_ID, boomElimination.packageId)
        // condition 未注册：淘汰 detail 点名缺失的约束键（condition 可定位）。
        val ghostElimination = failureRound.eliminations.single { it.candidateKey == EventMod.HANDLER_GHOST }
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, ghostElimination.reasonCode)
        assertEquals(
            "unregistered constraint: ${EventDeclarations.CONSTRAINT_MISSING}", ghostElimination.detail,
        )
        assertNull(failureRound.chosen, "两候选全灭 ⇒ 无执行")

        // provider 整轮失败：failure 记录定位到包 + 域 + PROVIDER 阶段（其余包不受影响）。
        val providerBoom = session.pipeline.run(
            eventRound(EventDeclarations.SET_FAILURE, extra = mapOf(EventMod.METADATA_PROVIDER_BOOM to "1")),
        )
        val providerFailure = providerBoom.failures.single { it.phase == DecisionFailurePhase.PROVIDER }
        assertEquals(EventDeclarations.PACKAGE_ID, providerFailure.packageId)
        assertEquals(EventDeclarations.DOMAIN_EVENT, providerFailure.key)
        assertEquals(0, providerBoom.evaluated, "provider 失败 = 本轮候选全丢")

        // 诊断环（宿主注入位可用）：DECISION 域记录带 packageId / domain / reasonCode。
        val decisionRecords = diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        assertTrue(
            decisionRecords.any {
                it.packageId == EventDeclarations.PACKAGE_ID &&
                    it.reasonCode == DecisionReasonCodes.CONSTRAINT_FALSE &&
                    it.key == EventDeclarations.DOMAIN_EVENT
            },
            "诊断环必须留下可定位的淘汰记录：${decisionRecords.take(5)}",
        )
    }

    // ------------------------------------------------------------------
    // 反向证明③：摘 KNOWS 可达闸（裸原型查询）⇒ 判据 3 红出「自动传播」
    // ------------------------------------------------------------------

    @Test
    fun `反向证明 裸原型查询会漏出他人亲历知识 KNOWS闸是承重墙`() {
        val session = start()
        val a = session.npcA
        val b = session.npcB
        val fact = session.memory.recordWitness(a, b, "lent a tool", 12L)
        // 裸查询（无 KNOWS 过滤）会返回全部 fact —— 若 knowledgeOf 退成这个形状，
        // B 就「自动知道」A 的亲历（判据 3 红）。闸 = HasRelation 入边过滤，本例钉住差异。
        val bareQuery = session.characterContext.query.query(
            queryPlan { archetype(MemoryDeclarations.FACT) },
        )
        assertTrue(fact in bareQuery, "裸查询漏出全部 fact（失败形态可见）")
        assertTrue(session.memory.knowledgeOf(b).isEmpty(), "经 KNOWS 闸后 B 仍不知道（判据 3 保持）")
    }

    companion object {
        /** 全部会话共用的决策种子（同 seed 重放判据的基准值）。 */
        const val DEFAULT_SEED: Long = 20261114L

        /** 傍晚仲裁的固定时间快照（嵌套驱动缝用它，保证外内两层同日同相位）。 */
        val EVENING_TIME: GameTime = GameTime(10L, "DAY_END")
    }
}
