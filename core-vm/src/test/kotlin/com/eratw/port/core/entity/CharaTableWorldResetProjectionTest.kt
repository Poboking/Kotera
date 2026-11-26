package com.eratw.port.core.entity

import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 整张角色表被清空时的投射身份面回收（task-55）：
 * **世界重置**（DELALLCHARA / RESETDATA / 回标题）⇒ 虚拟 NO 表与投影映射整体作废；
 * **读档前整表替换**（`SaveData.applyTo` 的 deleteAllCharacters）⇒ 什么都不做
 * （.rt2 侧车已并入的身份表必须留着）。
 *
 * 三条清空路径的调用点（取证锚）：
 * - DELALLCHARA：core/sem/StatementExecutor.kt:913、:3556 → `VariableStore.deleteAllCharacters`
 * - RESETDATA：StatementExecutor.kt:1060、:3695 → `VariableStore.resetData` :4129-4134
 *   → 同一条 `deleteAllCharacters`；回标题 core/proc/ProcessRuntime.kt:5078 同 `resetData`
 * - 读档：core/data/save/SaveData.kt:325（NORMAL 档）→ 宿主入口
 *   `FileSystemOps.loadData` :748-753 包在 `withCharacterReplacement` 里
 *
 * 语料无关（自建 [VariableStore] + 最小 schema），走 M5-2 真实投射桥，不依赖
 * ERATW_CORPUS；读档负控用真实 [FileSystemOps] 的 NORMAL .sav 往返。
 */
class CharaTableWorldResetProjectionTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]（与 SaveHostTest 同形：路径键为相对路径）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }

        override fun isReadOnly(path: String): Boolean = false
    }

    /** 一个世界：真实 store + 真投射桥（带虚拟 NO / 关系同步器）+ 最小 schema 内核。 */
    private class World {
        val kernel = EntityKernel(
            listOf(
                ComponentSchema(
                    "test.pkg",
                    "NativeChara",
                    listOf(ComponentFieldSpec("CF0")),
                ),
            ),
            listOf(RelationshipSchema("PARENT")),
            listOf(EntityArchetype("PERSON", listOf("NativeChara"))),
            16,
        )
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate("test.pkg")
        val reg = VirtualCharaNoRegistry()
        val store = VariableStore()
        val projector = RealCharaSlotProjector(store, kernel, reg, pkgs)
        val sync = RelationProjectionSynchronizer(kernel, projector, "PARENT")

        init {
            store.charaSlotProjector = projector
            projector.attachRelations(sync)
        }

        /** 一条原生角色（NO 唯一；关系同步器要求 roster 列键互异）。 */
        fun native(no: Long): Int {
            store.registerTemplate(
                CharacterTemplate(no = no, name = "native$no", callname = "native$no", csvNo = no),
            )
            return store.addCharacter(no)
        }

        fun entity(): Long = kernel.create(pkgId, "PERSON")
    }

    private fun plain(): SaveConfig = SaveConfig(systemSaveInBinary = false)

    // ------------------------------------------------------------------
    // 1. DELALLCHARA ⇒ 整体重置
    // ------------------------------------------------------------------

    /** DELALLCHARA 走 [VariableStore.deleteAllCharacters]（StatementExecutor.kt:913/:3556）。 */
    @Test
    fun `DELALLCHARA 整表清空后身份面整体作废且再投射拿新号`() {
        val w = World()
        w.native(1)
        val e1 = w.entity()
        val e2 = w.entity()
        val r1 = w.projector.project(e1)
        val r2 = w.projector.project(e2)
        assertEquals(r1 + 1, r2, "投射行 = 尾部追加")
        val no1 = w.reg.virtualNoOf(e1)!!
        val no2 = w.reg.virtualNoOf(e2)!!
        assertEquals(2, w.reg.size)
        val generationBefore = w.projector.rosterGeneration

        w.store.deleteAllCharacters()

        assertEquals(0, w.store.characters.count)
        assertEquals(0, w.reg.size, "虚拟 NO 登记必须随世界重置整体作废")
        assertEquals(0, w.projector.projectedEntities().size, "双向映射必须整体清空")
        assertEquals(-1, w.projector.charIndexOf(e1))
        assertEquals(EntityIdCodec.NULL, w.projector.entityAt(r1))
        assertTrue(
            w.projector.rosterGeneration > generationBefore,
            "整表清空 = 行全没了 ⇒ 行排列世代必须递增（旧行号一律失效）",
        )

        // 重置后重新投射：新行 0、新号（旧号永不复用）
        val e3 = w.entity()
        val r3 = w.projector.project(e3)
        val no3 = w.reg.virtualNoOf(e3)!!
        assertEquals(0, r3, "空表上投射 = 行 0")
        assertNotEquals(no1, no3)
        assertNotEquals(no2, no3)
        assertTrue(no3 > maxOf(no1, no2), "新号必须大于已作废的旧号（§16 永不复用）")
        assertEquals(1, w.store.characters.count)
        assertEquals(no3.toLong(), w.store.readInt("NO", longArrayOf(0)),
            "新投射行的 NO 列 = 新虚拟 NO")
    }

    // ------------------------------------------------------------------
    // 2. RESETDATA ⇒ 整体重置
    // ------------------------------------------------------------------

    /** RESETDATA 走 [VariableStore.resetData]（StatementExecutor.kt:1060/:3695）。 */
    @Test
    fun `RESETDATA 整表清空后身份面整体作废且再投射拿新号`() {
        val w = World()
        w.native(1)
        val e1 = w.entity()
        w.projector.project(e1)
        val no1 = w.reg.virtualNoOf(e1)!!

        w.store.resetData()

        assertEquals(0, w.store.characters.count)
        assertEquals(0, w.reg.size, "RESETDATA = 世界重置 ⇒ 身份面整体作废")
        assertEquals(0, w.projector.projectedEntities().size)
        assertEquals(-1, w.projector.charIndexOf(e1))

        val e2 = w.entity()
        assertEquals(0, w.projector.project(e2))
        val no2 = w.reg.virtualNoOf(e2)!!
        assertTrue(no2 > no1, "重置后新号不复用旧号")
    }

    // ------------------------------------------------------------------
    // 3. 读档前整表替换 ⇒ 什么都不做（负控）
    // ------------------------------------------------------------------

    /**
     * 真实 LOADDATA 负控：`FileSystemOps.loadData` 内部
     * `SaveData.applyTo`（core/data/save/SaveData.kt:325）会整表清空角色，但读档是
     * 「同一世界的存档替换」——虚拟 NO / 关系身份由 .rt2 侧车并入，必须保留。
     *
     * 时序说明：先模拟 [SaveService.restoreBundle]（:142 `applyVirtualNoSnapshot`）
     * 已把侧车身份表并入注册表，再走真实 `loadData`；若整表清空处误按世界重置回收，
     * 下面的 `size` / 映射断言会红。
     */
    @Test
    fun `读档整表替换保留已并入的 rt2 身份快照`() {
        val io = MemorySaveIo()
        // 源世界：一条原生角色写 NORMAL 存档
        val src = VariableStore()
        src.registerTemplate(CharacterTemplate(no = 5, name = "saved", callname = "saved", csvNo = 5))
        src.addCharacter(5)
        assertTrue(FileSystemOps(src, io, plain()).saveData(0, "world"), "源存档应写成功")

        val w = World()
        // 读档前的 .rt2 身份表（restoreBundle :142 已并入）
        val sidecar = VirtualCharaNoRegistry()
        val sidecarEntity = w.entity()
        val sidecarNo = sidecar.allocate(sidecarEntity)
        w.reg.mergeSnapshot(sidecar.encode())
        // 同一会话内投射一个实体（号接在侧车快照之后）
        val projected = w.entity()
        w.projector.project(projected)
        val projectedNo = w.reg.virtualNoOf(projected)!!
        val nextBefore = w.reg.snapshot().nextCandidate
        assertEquals(2, w.reg.size)

        FileSystemOps(w.store, io, plain()).loadData(0)

        assertEquals(1, w.store.characters.count, "角色表确实被 .sav 重建（否则没测到清表点）")
        assertEquals(2, w.reg.size, "读档的整表替换不得回收已并入的 .rt2 身份表")
        assertEquals(sidecarEntity, w.reg.entityOf(sidecarNo))
        assertEquals(projectedNo, w.reg.virtualNoOf(projected))
        assertEquals(nextBefore, w.reg.snapshot().nextCandidate)
        assertEquals(0, w.projector.charIndexOf(projected), "投影映射同样不得回收")
    }

    // ------------------------------------------------------------------
    // 4. 幸存路径不误清（逐行删除 ≠ 整体重置）
    // ------------------------------------------------------------------

    /**
     * 逐行删除继续走行钩子（[ICharaSlotProjector.onCharacterRowDeleted]），**不触发整体重置**：
     * 只回收被删那一行的虚拟 NO（行生命周期终点 = 号绑定终点）与其投影映射、关系基线；
     * 幸存投射与整个登记表不受影响，`nextCandidate` 也不因逐行回收回退。
     *
     * 与整体重置（[VirtualCharaNoRegistry.resetIdentities]）的分工：逐行路径只动被删行，
     * 整表清空才整体作废 —— 本用例专门锁住「不要把逐行路径误扩成整体清空」。
     */
    @Test
    fun `逐行删除只回收该行虚拟 NO 不触发整体重置且幸存投射不被误清`() {
        val w = World()
        w.native(1)
        val e1 = w.entity()
        val e2 = w.entity()
        val r1 = w.projector.project(e1)
        val r2 = w.projector.project(e2)
        val no2 = w.reg.virtualNoOf(e2)!!
        val nextBefore = w.reg.snapshot().nextCandidate

        w.store.deleteCharacters(longArrayOf(r1.toLong()))

        assertEquals(-1, w.projector.charIndexOf(e1), "被删行的投影映射解除")
        assertNull(w.reg.virtualNoOf(e1), "被删行的虚拟 NO 随行回收（旧号作废）")
        assertEquals(no2, w.reg.virtualNoOf(e2), "幸存实体的虚拟 NO 不受影响")
        assertEquals(nextBefore, w.reg.snapshot().nextCandidate, "逐行回收不回退续发游标")
        assertEquals(r2 - 1, w.projector.charIndexOf(e2), "幸存投射行号左移（既有钩子语义）")
    }

    // ------------------------------------------------------------------
    // 5. nextCandidate 口径锁定：不回退
    // ------------------------------------------------------------------

    /**
     * 保守口径 = 号永不回池（依据 [VirtualCharaNoRegistry] 类 KDoc 的不变量 + [restore]
     * 「续发游标必须大于已用最大值」的 fail-closed 判据）：回退会让新世界的实体拿到旧号，
     * 再并入老 .rt2 快照时按号解析冒名命中原实体。
     */
    @Test
    fun `世界重置不回退 nextCandidate 口径锁定`() {
        val w = World()
        val e1 = w.entity()
        w.projector.project(e1)
        val no1 = w.reg.virtualNoOf(e1)!!
        val nextBefore = w.reg.snapshot().nextCandidate

        w.reg.resetIdentities()

        assertEquals(0, w.reg.size)
        assertEquals(nextBefore, w.reg.snapshot().nextCandidate, "续发游标不回退（旧号永不复用）")
        val e2 = w.entity()
        val no2 = w.reg.allocate(e2)
        assertEquals(nextBefore, no2, "重置后第一个新号 = 重置前的续发游标（跳过全部旧号）")
        assertTrue(no2 > no1)
    }

    // ------------------------------------------------------------------
    // 6. 幂等
    // ------------------------------------------------------------------

    /** 世界重置可重复（RESETDATA 后紧跟 DELALLCHARA / 重复清空都合法）。 */
    @Test
    fun `世界重置与整表清空可重复调用`() {
        val w = World()
        val e1 = w.entity()
        w.projector.project(e1)

        w.store.deleteAllCharacters()
        w.store.deleteAllCharacters()
        w.store.resetData()

        assertEquals(0, w.store.characters.count)
        assertEquals(0, w.reg.size)
        assertEquals(0, w.projector.projectedEntities().size)
    }

    // ------------------------------------------------------------------
    // 7. 整表替换作用域（读档缝的声明面）
    // ------------------------------------------------------------------

    /** 作用域内清空不动身份面；作用域外恢复世界重置语义（钩子确实挂在缺省路径）。 */
    @Test
    fun `整表替换作用域内清空不回收且作用域外恢复世界重置语义`() {
        val w = World()
        val e1 = w.entity()
        w.projector.project(e1)
        val no1 = w.reg.virtualNoOf(e1)!!

        val marker = w.store.withCharacterReplacement {
            w.store.deleteAllCharacters()
            assertEquals(0, w.store.characters.count)
            assertEquals(no1, w.reg.virtualNoOf(e1), "作用域内整表替换不得回收身份面")
            assertEquals(0, w.projector.charIndexOf(e1))
            "inside"
        }
        assertEquals("inside", marker, "作用域透传 block 返回值")

        w.store.deleteAllCharacters()
        assertNull(w.reg.virtualNoOf(e1), "作用域外 = 世界重置语义")
        assertEquals(-1, w.projector.charIndexOf(e1))
    }

    /** 作用域异常路径必须复位深度，否则后续世界重置会被静默吞掉。 */
    @Test
    fun `整表替换作用域异常后深度复位`() {
        val w = World()
        val e1 = w.entity()
        w.projector.project(e1)

        assertFailsWith<IllegalStateException> {
            w.store.withCharacterReplacement<Unit> { error("读档中途失败") }
        }
        w.store.deleteAllCharacters()

        assertEquals(0, w.reg.size, "异常路径必须恢复深度（否则世界重置被抑制）")
        assertEquals(0, w.projector.projectedEntities().size)
    }

    // ------------------------------------------------------------------
    // 8. 关系投影基线整体作废
    // ------------------------------------------------------------------

    /** 整表重置丢全部基线，但不计「逐行删除事故」的损失计数。 */
    @Test
    fun `世界重置丢弃关系投影基线且不计逐行损失`() {
        val w = World()
        val e1 = w.entity()
        w.projector.project(e1)
        assertEquals(1, w.sync.activeProjections)

        w.store.deleteAllCharacters()

        assertEquals(0, w.sync.activeProjections, "整表重置 = 关系投影整体作废")
        assertEquals(0, w.sync.lostWriteBacks, "世界重置不是行删除事故，不刷事故计数")
    }
}
