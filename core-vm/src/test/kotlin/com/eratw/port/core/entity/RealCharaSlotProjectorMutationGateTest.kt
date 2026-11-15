package com.eratw.port.core.entity

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.StringHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5-3 活动期投射变更闸（CONTRACT-RT2-M53 裁定 1）：[RealCharaSlotProjector] 的晚装
 * 闸端口 + [project]/[unproject] 行变更面过闸的负控/正控矩阵（蓝图 era-arch-v61u.md
 * §7 Mutation Boundary「投射/退场引发的行增删只允许在 VM 完全让出控制权的安全边界
 * 执行，不只查 depth==0——嵌套调用、事件回调、输入流程」+ :324「活动调用栈禁止重排」）。
 *
 * 覆盖面（判据 → 用例）：
 * - 未绑 = 放行（既有单测面零行为差）+ 取证读数 [RealCharaSlotProjector.activityGateBound]；
 * - 活动调用栈内 project/unproject 当场拒、文案含「活动调用栈」点名；
 * - 页边界（probe false）放行正控；
 * - 晚装幂等：重绑拒绝、解绑按实例比对（照 VariableStore.bindCharaIndexResolver 先例）；
 * - 拒绝无副作用：拒后行数/映射/世代原样，解绑后可正常投射；
 * - **旁路负控**：闸恒拒下脚本语句路（SORTCHARA/DELCHARA 驱动的 store 行操作 → 五接点
 *   钩子）照常执行、映射照常平移——闸只挂 SDK 行变更面，不挡 ADR-005 即时语义；
 * - [com.eratw.port.core.code.VirtualMachine.isRunning] 判据语义：run-loop 内真、
 *   run 外假（闸判据的 VM 层权威读数）。
 */
class RealCharaSlotProjectorMutationGateTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 3 行原生角色（NO 1/2/3）+ 真实桥 + 绑定回填（不带关系同步器：闸用例不需要）。 */
    private class Fixture {
        val kernel = EntityKernel(
            listOf(ComponentSchema("gate.pkg", "NativeChara", emptyList())),
            emptyList(),
            listOf(EntityArchetype("PERSON", listOf("NativeChara"))),
        )
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate("gate.pkg")
        val store = VariableStore()
        val projector = RealCharaSlotProjector(store, kernel)

        init {
            store.charaSlotProjector = projector
            // 原生 MASTER 指针出厂 0 会钉住行 0（SORTCHARA fixMaster 语义），装置统一置
            // 无主人公（-1）让排序参与全量行，与 RealCharaSlotProjectorTest 同口径。
            store.writeInt("MASTER", longArrayOf(0), -1L)
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
        }

        fun newEntity(): Long = kernel.create(pkgId, "PERSON")

        fun charaNum(): Int = store.characters.count
    }

    // ------------------------------------------------------------------
    // 未绑 / 正控 / 负控
    // ------------------------------------------------------------------

    @Test
    fun `未绑闸_放行且取证读数为假`() {
        val f = Fixture()
        assertFalse(f.projector.activityGateBound, "未装配 = 取证读数假")
        val e = f.newEntity()
        assertEquals(3, f.projector.project(e), "未绑闸 = project 放行（既有单测面零行为差）")
        assertTrue(f.projector.unproject(e), "未绑闸 = unproject 放行")
    }

    @Test
    fun `活动调用栈内_project_unproject当场拒且点名`() {
        val f = Fixture()
        f.projector.bindActivityGate { true }
        assertTrue(f.projector.activityGateBound, "装配后取证读数真")
        val e = f.newEntity()
        val projectError = assertFailsWith<IllegalStateException> {
            f.projector.project(e)
        }
        assertTrue(
            projectError.message!!.contains("活动调用栈"),
            "拒文案必须含「活动调用栈」语义，实际：${projectError.message}",
        )
        assertTrue(projectError.message!!.contains("project"), "拒文案必须点名操作，实际：${projectError.message}")
        val unprojectError = assertFailsWith<IllegalStateException> {
            f.projector.unproject(e)
        }
        assertTrue(
            unprojectError.message!!.contains("活动调用栈"),
            "拒文案必须含「活动调用栈」语义，实际：${unprojectError.message}",
        )
    }

    @Test
    fun `页边界probe放行_正控`() {
        val f = Fixture()
        f.projector.bindActivityGate { false }
        val e = f.newEntity()
        assertEquals(3, f.projector.project(e), "页边界（VM 让出）= 放行")
        assertTrue(f.projector.unproject(e), "页边界退场 = 放行")
    }

    // ------------------------------------------------------------------
    // 晚装端口幂等（照 bindCharaIndexResolver 先例）
    // ------------------------------------------------------------------

    @Test
    fun `重绑拒绝`() {
        val f = Fixture()
        f.projector.bindActivityGate { false }
        val rebind = assertFailsWith<IllegalArgumentException> {
            f.projector.bindActivityGate { true }
        }
        assertTrue(rebind.message!!.contains("重复绑定"), "重绑必须拒绝，实际：${rebind.message}")
    }

    @Test
    fun `unbind按实例幂等_别人的no-op_解绑后放行`() {
        val f = Fixture()
        val mine: () -> Boolean = { true }
        f.projector.bindActivityGate(mine)
        val stranger: () -> Boolean = { false }
        f.projector.unbindActivityGate(stranger)
        assertTrue(f.projector.activityGateBound, "解绑别人的实例 = no-op，闸仍在")
        val e = f.newEntity()
        assertFailsWith<IllegalStateException> { f.projector.project(e) }
        f.projector.unbindActivityGate(mine)
        assertFalse(f.projector.activityGateBound, "解绑自己的实例 = 闸摘除")
        assertEquals(3, f.projector.project(e), "解绑后放行")
        f.projector.unbindActivityGate(mine)
        assertFalse(f.projector.activityGateBound, "重复解绑 = no-op")
    }

    // ------------------------------------------------------------------
    // 拒绝无副作用 + 恢复
    // ------------------------------------------------------------------

    @Test
    fun `活动态拒后状态无损_解绑后可正常投射`() {
        val f = Fixture()
        val generation = f.projector.rosterGeneration
        val probe: () -> Boolean = { true }
        f.projector.bindActivityGate(probe)
        val e = f.newEntity()
        assertFailsWith<IllegalStateException> { f.projector.project(e) }
        assertEquals(3, f.charaNum(), "拒绝不得建行")
        assertEquals(generation, f.projector.rosterGeneration, "拒绝不得动世代")
        assertEquals(-1, f.projector.charIndexOf(e), "拒绝不得留映射")
        f.projector.unbindActivityGate(probe)
        assertEquals(3, f.projector.project(e), "解绑后正常投射")
        assertEquals(4, f.charaNum())
    }

    // ------------------------------------------------------------------
    // 旁路负控：闸不挡脚本语句路（ADR-005 即时语义）
    // ------------------------------------------------------------------

    @Test
    fun `闸恒拒下_脚本语句路行操作照常_钩子平移映射`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        assertEquals(3, row)
        // 绑最严形态（恒拒）：若闸错误地挂到了语句路（store 行操作/五接点钩子），
        // 下面两步任何一个都会炸。
        f.projector.bindActivityGate { true }
        // SORTCHARA 语义（store.sortCharactersByInt = 语句驱动面）：行重排后映射被
        // 钩子 onCharacterRowsPermuted 平移，实体仍解析到自己的行。
        f.store.writeInt("CFLAG", longArrayOf(0, 0), 30)
        f.store.writeInt("CFLAG", longArrayOf(1, 0), 10)
        f.store.writeInt("CFLAG", longArrayOf(2, 0), 20)
        f.store.writeInt("CFLAG", longArrayOf(3, 0), 5)
        f.store.sortCharactersByInt("CFLAG", longArrayOf(0))
        // 键 5（投射实体）最小 → 升序后实体的行平移到表头。
        assertEquals(0, f.projector.charIndexOf(e), "SORTCHARA 后钩子把实体平移到新行")
        assertEquals(e, f.projector.entityAt(0), "双向映射一致")
        // DELCHARA 语义（store.deleteCharacter）：删行后映射摘除（ADR-005 即时语义，
        // 行已被引擎删掉、格子无从读取），闸同样不挡。
        f.store.deleteCharacter(0)
        assertEquals(-1, f.projector.charIndexOf(e), "删行钩子解映射")
        assertEquals(3, f.charaNum())
    }

    // ------------------------------------------------------------------
    // Vm.isRunning 判据语义（闸判据的 VM 层权威读数）
    // ------------------------------------------------------------------

    /** 记录入口帧内 [VmHost.onFunctionEnter] 时刻的探针读数。 */
    private class ProbeHost : VmHost by StringHost() {
        var runningAtEnter: Boolean? = null
        var probe: (() -> Boolean)? = null

        override fun onFunctionEnter(funcIndex: Int) {
            runningAtEnter = probe?.invoke()
        }
    }

    @Test
    fun `isRunning判据_run内真_run外假`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
              _HALT
            """.trimIndent(),
        )
        val host = ProbeHost()
        val vm = VirtualMachine(program, host)
        assertFalse(vm.isRunning, "run 前 = 非执行中")
        host.probe = { vm.isRunning }
        vm.run(0)
        assertFalse(vm.isRunning, "run 返回（页边界）= 非执行中")
        assertEquals(
            true,
            host.runningAtEnter,
            "入口帧内 run-loop 执行中（嵌套调用/事件回调/输入流程全程覆盖，蓝图 :176）",
        )
    }
}
