package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.get
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modfamily.generated.FamilyView
import com.eratw.port.modtesting.LogEntry
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
 * 手动继任作者面（ADR-015 S3 / task-158）：[FamilyApi] 的候选口径、指定/读回/取消，以及
 * manual 模式日拍对显式选择的结算。
 *
 * 与 [FamilySuccessionSettingsTest] / [FamilySuccessionWiringTest] 的分工：
 * - 那边钉「设置值 → 自动选谁」（eldest/youngest）与 manual 的既有「整拍零写入」；
 * - 这里钉「显式指定 → 存哪、何时生效、非法输入是否零写盘」，并**守住既有语义**：
 *   manual 未指定时仍整拍零写入（[manualModeWithoutExplicitChoiceStillDoesNotSettle]）。
 *
 * 零写盘断言口径：`context.storage.keys()` 快照 + [FamilyDeclarations.CHILD_IDS] 原文 +
 * 父引用/标题字段 —— 台架 `MemoryStorage` 不统计写次数，故用「可见状态逐位不变」证零写。
 */
class FamilyApiTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
        val family: FamilyFacade get() = FamilyFacade(world.entities)
        val logs: List<LogEntry> get() = context.logEntries
        val api: FamilyApi get() = mod.api
    }

    private fun fixture(
        mode: String = FamilyDeclarations.SUCCESSION_MANUAL,
        showTitles: Boolean = true,
    ): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, showTitles.toString())
        session.context.settingsRecorder.declare(FamilyDeclarations.SUCCESSION_MODE_KEY, mode)
        return Fixture(world, mod, session)
    }

    /** 收养一个家督（父引用为空）。 */
    private fun head(f: Fixture, title: String = "当主"): EntityId = f.mod.adoptChild(title, null)

    /** 收养一个子嗣并直接钉年龄（让「谁大谁小」一眼可读）。 */
    private fun childAged(f: Fixture, title: String, age: Long, father: EntityId?): EntityId {
        val child = f.mod.adoptChild(title, father)
        f.world.entities.setInt(child, FamilyKeys.age, age)
        return child
    }

    private fun viewOf(f: Fixture, id: EntityId): FamilyView = f.family.of(id)

    private fun successionLogs(f: Fixture): List<LogEntry> = f.logs.filter { it.message.contains("家督继任") }

    private fun childIdsText(f: Fixture): String? = f.context.storage.get(FamilyDeclarations.CHILD_IDS)

    @Test
    fun candidatesExcludeRetiredAndMissingEntities() {
        val f = fixture()
        val head = head(f)
        val active = childAged(f, "嫡", 1L, head)
        val retired = childAged(f, "殁", 2L, head)
        f.world.entities.setState(retired, EntityState.RETIRED)

        val candidates = f.api.candidates()

        assertEquals(setOf(head, active), candidates.toSet(), "已退场者（殁）与在册活人都在不同池：候选只含健在在册成员")
        assertEquals(candidates.sortedBy { it.raw }, candidates, "候选取稳定序（raw 升序），不依赖名册插入序")
        assertEquals(2, candidates.size, "退场者不得重复占用候选位")
    }

    @Test
    fun candidatesKeepStableOrderAcrossSaveReloadAndRepeatedCalls() {
        val f = fixture()
        val head = head(f)
        childAged(f, "甲", 1L, head)
        childAged(f, "乙", 2L, head)
        childAged(f, "丙", 3L, head)

        val first = f.api.candidates()
        f.session.saveLoaded()
        val afterReload = f.api.candidates()

        assertEquals(first, f.api.candidates(), "同一世界重复查询候选，结果逐位一致")
        assertEquals(first, afterReload, "读档重建名册后候选集合与顺序不变（不依赖存储/遍历序）")
    }

    @Test
    fun chooseSuccessorIsRejectedOutsideManualModeWithZeroWrites() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)
        val child = childAged(f, "甲", 1L, head)
        val keysBefore = f.context.storage.keys()
        val childIdsBefore = childIdsText(f)

        assertFalse(f.api.chooseSuccessor(child), "eldest 模式下不得记手动选择（会变成永不生效的幽灵状态）")
        assertNull(f.api.pendingSuccessor(), "被拒绝的指定不得留痕")
        assertEquals(keysBefore, f.context.storage.keys(), "拒绝路径零写盘：存储键集合不变")
        assertEquals(childIdsBefore, childIdsText(f), "拒绝路径不碰在册名单")
    }

    @Test
    fun chooseSuccessorRejectsNonRosterAndRetiredCandidatesWithZeroWrites() {
        val f = fixture()
        val head = head(f)
        val retired = childAged(f, "殁", 1L, head)
        f.world.entities.setState(retired, EntityState.RETIRED)
        val keysBefore = f.context.storage.keys()

        assertFalse(f.api.chooseSuccessor(EntityId(999_999L)), "不存在的实体不是合法候选")
        assertFalse(f.api.chooseSuccessor(retired), "已退场者不是合法候选")
        assertFalse(f.api.chooseSuccessor(EntityId.NULL), "空 id 不是合法候选")
        assertNull(f.api.pendingSuccessor(), "非法候选一律不留痕")
        assertEquals(keysBefore, f.context.storage.keys(), "三条拒绝路径都零写盘")
    }

    @Test
    fun chooseSuccessorIsIdempotentAndReadBack() {
        val f = fixture()
        val head = head(f)
        childAged(f, "甲", 3L, head)
        val chosen = childAged(f, "乙", 1L, head)

        assertTrue(f.api.chooseSuccessor(chosen), "manual 模式下合法候选必须接受")
        assertEquals(chosen, f.api.pendingSuccessor(), "指定必须可读回（作者面/设置面共用这一个真源）")
        assertEquals(
            chosen.raw.toString(),
            f.context.storage.get(FamilyDeclarations.MANUAL_HEIR),
            "落盘形态 = 十进制 EntityId.raw（随存档走，无需新 manifest 声明）",
        )

        assertTrue(f.api.chooseSuccessor(chosen), "重复指定同一人 = 幂等成功")
        assertEquals(chosen, f.api.pendingSuccessor(), "重复指定不改读回值")

        assertTrue(f.api.clearSuccessor(), "取消已存在的指定")
        assertNull(f.api.pendingSuccessor(), "取消后读回 null")
        assertFalse(f.api.clearSuccessor(), "重复取消 = 本来就没有指定（false，零写入）")
    }

    @Test
    fun displayNamesProjectExistingTitlesForKnownCandidates() {
        val f = fixture()
        val head = head(f)
        val eldest = childAged(f, "甲", 3L, head)
        val youngest = childAged(f, "长女", 1L, head)

        val names = f.api.displayNames(f.api.candidates())

        assertEquals(
            mapOf(head to "当主", eldest to "甲", youngest to "长女"),
            names,
            "名字来源 = 既有 title 字段（收养/继承写的那个人物显示串），不是新存储键",
        )
        assertEquals(
            f.api.candidates().toSet(),
            names.keys,
            "每个候选都要有名字（本用例里所有 title 都非空）",
        )
    }

    @Test
    fun displayNamesOmitBlankTitlesSoUiFallsBackToRaw() {
        val f = fixture(showTitles = false)
        val head = head(f)
        val child = childAged(f, "甲", 1L, head)

        assertEquals("", viewOf(f, head).title, "关闭称号时 adoptChild 按既有口径写空串")
        assertEquals("", viewOf(f, child).title)
        assertTrue(
            f.api.displayNames(f.api.candidates()).isEmpty(),
            "空标题按约定『不进映射』，调用方才会回落 #raw，而不是显示「（#id）」空壳",
        )
    }

    @Test
    fun displayNamesCollapseWhitespaceAndTruncateLongTitles() {
        val f = fixture()
        val head = head(f)
        val spaced = childAged(f, "甲\t　 乙", 2L, head)
        val long = childAged(f, "长".repeat(30), 1L, head)
        val emoji = childAged(f, "星".repeat(22) + "🌟" + "尾".repeat(5), 1L, head)

        val names = f.api.displayNames(listOf(spaced, long, emoji))

        assertEquals("甲 乙", names[spaced], "制表 / 全角空格 / 连续空白折叠成一个半角空格")
        assertEquals("长".repeat(23) + "…", names[long], "超长标题截到 24 字符位并补省略号")
        assertEquals("星".repeat(22) + "…", names[emoji], "裁剪点避开代理对，不劈开星位字符")
        assertEquals("甲\t　 乙", viewOf(f, spaced).title, "归一化只影响返回值，绝不回写 title")
    }

    @Test
    fun displayNamesTrimFullWidthAndNbspWhitespaceAtEdges() {
        val f = fixture()
        val head = head(f)
        val padded = childAged(f, "　甲\u00A0", 1L, head)
        val blank = childAged(f, "　　", 1L, head)

        val names = f.api.displayNames(listOf(padded, blank))

        assertEquals("甲", names[padded], "首尾全角/不换行空格按契约剥掉（trim 只剥 ASCII，须在折叠之后做）")
        assertFalse(names.containsKey(blank), "纯空白标题按契约『归一后为空 ⇒ 不进映射』，调用方回落 #raw")
        assertEquals("　甲\u00A0", viewOf(f, padded).title, "归一化只影响返回值，绝不回写 title")
    }

    @Test
    fun displayNamesSkipUnknownAndNullIdsAndPreserveInputOrder() {
        val f = fixture()
        val head = head(f)
        val child = childAged(f, "甲", 1L, head)

        val names = f.api.displayNames(listOf(child, EntityId(999_999L), EntityId.NULL, head, child))

        assertEquals(listOf(child, head), names.keys.toList(), "悬空 / NULL 跳过；重复 id 只留第一次")
        assertEquals(mapOf(child to "甲", head to "当主"), names)
        assertTrue(f.api.displayNames(emptyList()).isEmpty(), "空输入不读世界，直接空映射")
    }

    @Test
    fun displayNamesIsReadOnlyAndStableAcrossReload() {
        val f = fixture()
        val head = head(f)
        childAged(f, "甲", 1L, head)
        childAged(f, "乙", 2L, head)
        val keysBefore = f.context.storage.keys()
        val namesBefore = f.api.displayNames(f.api.candidates())

        assertEquals(namesBefore, f.api.displayNames(f.api.candidates()), "同一世界重复查询逐位一致")
        assertEquals(keysBefore, f.context.storage.keys(), "只读投影：不得新增任何存储键")

        f.session.saveLoaded()
        assertEquals(namesBefore, f.api.displayNames(f.api.candidates()), "读档重建名册后名字映射不变")
        assertEquals(keysBefore, f.context.storage.keys(), "跨读档也不得留下存储痕迹")
    }

    @Test
    fun manualChoiceSettlesTheChosenHeirOnNextGrowthTick() {
        val f = fixture()
        val head = head(f)
        val eldest = childAged(f, "甲", 3L, head)
        val chosen = childAged(f, "乙", 1L, head)
        assertTrue(f.api.chooseSuccessor(chosen))

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH), "family.growth 已绑定 ⇒ 日拍必须能触发")

        assertEquals(EntityId.NULL, viewOf(f, chosen).father, "显式指定者成为新家督（不受 eldest 年龄序影响）")
        assertEquals("当主", viewOf(f, chosen).title, "新家督继承死者的称号")
        assertEquals(chosen, viewOf(f, eldest).father, "其余直系子嗣改挂新家督")
        assertNull(f.api.pendingSuccessor(), "指定被消费后必须清键（否则下一个死者会误用同一选择）")
        val log = successionLogs(f).singleOrNull()
        assertTrue(log != null, "手动继任也必须落既有日志面：${f.logs.map { it.message }}")
        assertEquals("INFO", log.level)
        assertTrue(log.message.contains("mode=${FamilyDeclarations.SUCCESSION_MANUAL}"), "日志带模式：${log.message}")
        assertTrue(log.message.contains("前任=${head.raw}"), "日志带前任：${log.message}")
        assertTrue(log.message.contains("继任=${chosen.raw}"), "日志带继任：${log.message}")
    }

    @Test
    fun manualChoiceIsNotConsumedWhileNobodyDied() {
        val f = fixture()
        val head = head(f)
        val child = childAged(f, "甲", 2L, head)
        assertTrue(f.api.chooseSuccessor(child))
        val childIdsBefore = childIdsText(f)

        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(head, viewOf(f, child).father, "没有死者 ⇒ 不结算，指定不得提前生效")
        assertEquals("甲", viewOf(f, child).title, "不给活人改称号")
        assertEquals(child, f.api.pendingSuccessor(), "指定保留到真正需要结算的那一拍")
        assertEquals(childIdsBefore, childIdsText(f), "零写入：在册名单逐字不变")
        assertEquals(1L, f.mod.state.growthTicks, "成长拍本身照常推进")
        assertTrue(successionLogs(f).isEmpty(), "无继任日志：${successionLogs(f).map { it.message }}")
    }

    @Test
    fun manualModeWithoutExplicitChoiceStillDoesNotSettle() {
        val f = fixture()
        val head = head(f)
        val eldest = childAged(f, "甲", 3L, head)
        val other = childAged(f, "乙", 1L, head)

        f.mod.markDeceased(head)
        val childIdsBefore = childIdsText(f)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(head, viewOf(f, eldest).father, "manual 未显式指定 ⇒ 既有「整拍不自动改」语义不变")
        assertEquals(head, viewOf(f, other).father)
        assertEquals("甲", viewOf(f, eldest).title, "manual 未指定不继承称号")
        assertEquals(childIdsBefore, childIdsText(f), "零写入：名册不因清算被重写")
        assertEquals(1L, f.mod.state.growthTicks)
        assertTrue(successionLogs(f).isEmpty(), "无继任日志")
    }

    @Test
    fun staleChoiceWhoseOwnerAlreadyRetiredIsKeptAndIgnored() {
        val f = fixture()
        val head = head(f)
        val survivor = childAged(f, "甲", 3L, head)
        val doomedChoice = childAged(f, "乙", 1L, head)
        assertTrue(f.api.chooseSuccessor(doomedChoice))

        f.mod.markDeceased(doomedChoice)
        f.mod.markDeceased(head)
        val childIdsBefore = childIdsText(f)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH), "指定人已死不得让日拍崩")

        assertEquals(head, viewOf(f, survivor).father, "指定人已退场 ⇒ 不结算、不改存活子嗣的父引用")
        assertEquals(doomedChoice, f.api.pendingSuccessor(), "脏指定保留（可诊断），不静默丢")
        assertEquals(childIdsBefore, childIdsText(f), "未命中路径零写入名册")
        assertTrue(successionLogs(f).isEmpty(), "未结算不得记继任日志")
    }
}
