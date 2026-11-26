package com.eratw.port.core.entity

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-2 虚拟 NO 映射表回收验收：虚拟 NO 的绑定生命周期 = 实体行生命周期。
 *
 * 生产缝：真实 [RealCharaSlotProjector] 挂在 [VariableStore.charaSlotProjector] 上，
 * 单行/数组删行（DELCHARA 两种重载）与 [RealCharaSlotProjector.unproject] 退场删行
 * 都经 [VariableStore.deleteCharacter]/[VariableStore.deleteCharacters] 回环到
 * [ICharaSlotProjector.onCharacterRowDeleted]——本类断言该唯一回环点会调
 * [VirtualCharaNoRegistry.retire]：解绑双向映射、nextCandidate 不回退（旧号作废不复用），
 * 且 snapshot()/encode() 的线格式与存档内容不含已回收条目。
 *
 * 位域/线格式是 RT2 自研语义（Emuera C# 无虚拟 NO），行生命周期锚：
 * VariableEvaluator.cs:1241 `DelCharacter(long charaNo)` / :1247-1248 Dispose+RemoveAt
 * ——行删除即角色对象销毁，实体身份不得在行消失后继续留册。
 */
class VirtualNoRecycleOnRowDeleteTest {

    /** 3 行原生角色（NO 1/2/3，行 0..2）+ 真实桥；伪角色从行 3 起尾增。 */
    private class Fixture {
        val kernel = EntityKernel(
            listOf(
                ComponentSchema(
                    "test.pkg",
                    "NativeChara",
                    listOf(ComponentFieldSpec("CF0")),
                ),
            ),
            emptyList(),
            listOf(EntityArchetype("PERSON", listOf("NativeChara"))),
            16,
        )
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate("test.pkg")
        val reg = VirtualCharaNoRegistry()
        val store = VariableStore()
        val domains = listOf(NativeCharaField("CFLAG", longArrayOf(0), "CF0"))
        val projector = RealCharaSlotProjector(store, kernel, reg, pkgs, domains)

        init {
            store.charaSlotProjector = projector
            // 原生 MASTER 指针出厂 0 会钉住行 0；装置统一置无主人公，排除 pin 语义干扰。
            store.writeInt("MASTER", longArrayOf(0), -1L)
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
        }

        fun newEntity(): Long = kernel.create(pkgId, "PERSON")
    }

    // ------------------------------------------------------------------
    // ① 分配 → 回收 → 双向 null
    // ------------------------------------------------------------------

    @Test
    fun `unproject_退场即回收_双向查空且表为空`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        val vno = requireNotNull(f.reg.virtualNoOf(e)) { "投射必须分配虚拟 NO" }
        assertEquals(10_000, vno, "首个虚拟 NO = VIRTUAL_NO_BASE")
        assertEquals(1, f.reg.size)

        assertTrue(f.projector.unproject(e), "退场成功")

        assertNull(f.reg.virtualNoOf(e), "退场后 entity→NO 必须解绑")
        assertNull(f.reg.entityOf(vno), "退场后 NO→entity 必须解绑")
        assertEquals(0, f.reg.size, "回收后表内零条目")
        assertEquals(-1, f.projector.charIndexOf(e), "投射映射已解")
        assertEquals(3, f.store.characters.count, "只删投射行，原生 3 行不动")
        assertEquals(row, 3, "投射行在原生行之后")
    }

    // ------------------------------------------------------------------
    // ② 回收后新分配不复用旧号
    // ------------------------------------------------------------------

    @Test
    fun `回收后新实体拿新号_不复用旧号`() {
        val f = Fixture()
        val e1 = f.newEntity()
        f.projector.project(e1)
        val vno1 = requireNotNull(f.reg.virtualNoOf(e1))
        assertTrue(f.projector.unproject(e1))

        val e2 = f.newEntity()
        f.projector.project(e2)
        val vno2 = requireNotNull(f.reg.virtualNoOf(e2))
        assertNotEquals(vno1, vno2, "旧号作废，再次投射必须拿新号（KDoc：永不复用）")
        assertTrue(vno2 > vno1, "nextCandidate 只增不减")
        assertEquals(1, f.reg.size)
        assertNull(f.reg.entityOf(vno1), "旧号保持作废")
    }

    // ------------------------------------------------------------------
    // ③ 重复回收幂等
    // ------------------------------------------------------------------

    @Test
    fun `重复回收幂等_二次retire返null且不抛`() {
        val f = Fixture()
        val e = f.newEntity()
        f.projector.project(e)
        val vno = requireNotNull(f.reg.virtualNoOf(e))
        assertTrue(f.projector.unproject(e))

        assertNull(f.reg.retire(e), "已回收实体再回收必须返 null（幂等，不抛）")
        assertNull(f.reg.retire(f.newEntity()), "从未分配实体回收同样幂等")
        assertFalse(f.projector.unproject(e), "未投射实体退场原样返 false")
        assertNull(f.reg.entityOf(vno))
        assertEquals(0, f.reg.size, "重复回收不得复活或残留条目")
    }

    // ------------------------------------------------------------------
    // ④ 快照不含已回收（且线格式不变）
    // ------------------------------------------------------------------

    @Test
    fun `快照与encode不含已回收条目`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        f.projector.project(e1)
        f.projector.project(e2)
        val vno1 = requireNotNull(f.reg.virtualNoOf(e1))
        val vno2 = requireNotNull(f.reg.virtualNoOf(e2))
        assertTrue(f.projector.unproject(e2))

        val snap = f.reg.snapshot()
        assertEquals(setOf(vno1), snap.entries.keys, "快照只保留在册投射实体")
        assertEquals(e1, snap.entries[vno1])

        val wire = f.reg.encode()
        val restored = VirtualCharaNoRegistry.decode(wire)
        assertEquals(1, restored.size)
        assertEquals(e1, restored.entityOf(vno1), "在册映射随存档往返")
        assertNull(restored.entityOf(vno2), "已回收号不得随存档复活")

        // 线格式回归：同一快照 decode→encode 逐字节等价（版本常量/段结构未动）。
        assertContentEquals(wire, restored.encode(), "encode/decode 往返必须逐字节稳定")
    }

    // ------------------------------------------------------------------
    // ⑤ 真实行删除漏斗调到回收（DELCHARA 单参/数组两种重载）
    // ------------------------------------------------------------------

    @Test
    fun `DELCHARA单参删投射行_行删除漏斗自动回收`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        val vno = requireNotNull(f.reg.virtualNoOf(e))

        // 不经 unproject，直接走语句层 DELCHARA 的删行落点。
        f.store.deleteCharacter(row.toLong())

        assertNull(f.reg.virtualNoOf(e), "行删除漏斗必须回收虚拟 NO")
        assertNull(f.reg.entityOf(vno))
        assertEquals(0, f.reg.size)
        assertEquals(-1, f.projector.charIndexOf(e))
    }

    @Test
    fun `DELCHARA数组删多行_只回收被删实体_其余不受影响`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        val e3 = f.newEntity()
        val row1 = f.projector.project(e1)
        val row2 = f.projector.project(e2)
        val row3 = f.projector.project(e3)
        val vno1 = requireNotNull(f.reg.virtualNoOf(e1))
        val vno3 = requireNotNull(f.reg.virtualNoOf(e3))

        f.store.deleteCharacters(longArrayOf(row1.toLong(), row2.toLong()))

        assertNull(f.reg.virtualNoOf(e1))
        assertNull(f.reg.virtualNoOf(e2))
        assertNull(f.reg.entityOf(vno1))
        assertEquals(e3, f.reg.entityOf(vno3), "未被删的投射实体映射原样保留")
        assertEquals(1, f.reg.size)
        assertEquals(-1, f.projector.charIndexOf(e1))
        assertEquals(row3 - 2, f.projector.charIndexOf(e3), "两行删除后幸存投射行左移 2")
    }

    // ------------------------------------------------------------------
    // ⑥ 其它实体不受影响（原生行删除不碰虚拟号表）
    // ------------------------------------------------------------------

    @Test
    fun `删原生行不误伤虚拟号表`() {
        val f = Fixture()
        val e = f.newEntity()
        f.projector.project(e)
        val vno = requireNotNull(f.reg.virtualNoOf(e))

        f.store.deleteCharacter(0L) // 删原生行 0（无投射实体）

        assertEquals(e, f.reg.entityOf(vno), "原生行删除不涉及任何虚拟号")
        assertEquals(1, f.reg.size)
        assertEquals(2, f.projector.charIndexOf(e), "投射行随删除左移 1")
    }
}
