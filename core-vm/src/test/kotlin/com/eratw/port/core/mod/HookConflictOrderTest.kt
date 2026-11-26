package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * 跨包同函数同 priority 钩子撞车的确定性派发序语义闸（MODDX2 Lane moddx2-g4，纯测试面）。
 *
 * 钉住的口径（生产单源 [HookPipeline]，锚 core-vm/src/main/kotlin/com/eratw/port/core/mod/HookPipeline.kt）：
 * - 槽表确定性序 = priority 升序再声明序：of 的 forEachIndexed 令 order = 全局声明索引，
 *   组内排序 compareBy(priority, order)（HookPipeline.kt:419-437）。同 priority 撞车时
 *   声明序胜出，与 packageId 字母序无关；
 * - dispatch 按同一张槽表分相位扫描：prefix 段（HookPipeline.kt:227-254）→ 原函数 →
 *   postfix 段（HookPipeline.kt:269-296），故同 priority 下 prefix 全部先于 postfix、
 *   各相位内部保持槽表序（即声明序）；
 * - registrations 投影全序 = functionKey 字典序 → priority 升序 → 声明序
 *   （HookPipeline.kt:145-156）。单函数键内分相位过滤后与实际派发序一致；跨函数键时投影
 *   是字典序视图，不是任何单次派发的序列（每次派发只涉一个函数键）。
 */
class HookConflictOrderTest {

    /** fixture 口径照抄 HookApiQueryTest/HookPipelineTest 的 decl 工厂（缺省 COM300 prefix priority 0）。 */
    private fun decl(
        pkg: String,
        target: String = "COM300",
        phase: HookPhase = HookPhase.PREFIX,
        priority: Int = 0,
    ): DeclaredHook = DeclaredHook(
        packageId = pkg,
        declaration = HookDeclaration(
            targetKey = target,
            phase = phase,
            priority = priority,
            mayMutateArgs = false,
            mayMutateReturn = false,
            runOnFailure = false,
        ),
    )

    private fun HookPhase.toMod(): ModHookPhase = when (this) {
        HookPhase.PREFIX -> ModHookPhase.PREFIX
        HookPhase.POSTFIX -> ModHookPhase.POSTFIX
    }

    /**
     * 构管线并按声明序把全部钩子绑上，处理器把执行序记进共享列表（`pre:<pkg>`、`post:<pkg>`），
     * 返回管线与记录列表；派发由用例自己做（单次 / 重复 / 多目标各自控制）。
     */
    private fun newBoundPipeline(declarations: List<DeclaredHook>): Pair<HookPipeline, MutableList<String>> {
        val pipeline = HookPipeline.of(declarations)
        val order = mutableListOf<String>()
        for (d in declarations) {
            val tag = if (d.declaration.phase == HookPhase.PREFIX) "pre:" else "post:"
            pipeline.bind(d.declaration.targetKey, d.declaration.phase.toMod(), d.packageId) {
                order += tag + d.packageId
                HookResult.Continue
            }
        }
        return pipeline to order
    }

    /** [newBoundPipeline] 的便捷封装：绑完即派发一次 [dispatchTarget]，返回完整执行序记录。 */
    private fun dispatchOrder(declarations: List<DeclaredHook>, dispatchTarget: String = "COM300"): List<String> {
        val (pipeline, order) = newBoundPipeline(declarations)
        pipeline.dispatch(dispatchTarget, emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        return order
    }

    @Test
    fun `两mod同函数同priority撞车_派发序等于声明序_不随packageId字母序`() {
        // zzz.late 先声明、aaa.early 后声明，而字母序恰好相反（aaa < zzz）：tie 若由 packageId
        // 字母序决定，派发序会与本断言相反 —— 反向敏感性钉在 packageId 的选取上。
        val (pipeline, order) = newBoundPipeline(
            listOf(
                decl("zzz.late"),
                decl("aaa.early"),
            ),
        )
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("pre:zzz.late", "pre:aaa.early", "ORIGINAL"), order)
        // 确定性：同一管线重派一次，序不变（不是碰巧对一次）。
        order.clear()
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("pre:zzz.late", "pre:aaa.early", "ORIGINAL"), order)
    }

    @Test
    fun `交换装载序_派发序整体翻转_证明tie序源是声明序而非字母序`() {
        // 三包的字母序是 aaa < mmm < zzz：下面两种装载序的派发结果互为镜像、且都不等于字母序，
        // 同时排除「按字母序」与「随机」两个竞争解释（三包镜像比两包更强的证据是中位元素 mmm
        // 在两种序里都居中，首尾随声明翻转）。
        val forward = dispatchOrder(
            listOf(
                decl("zzz.late"),
                decl("mmm.mid"),
                decl("aaa.early"),
            ),
        )
        val reversed = dispatchOrder(
            listOf(
                decl("aaa.early"),
                decl("mmm.mid"),
                decl("zzz.late"),
            ),
        )
        assertEquals(listOf("pre:zzz.late", "pre:mmm.mid", "pre:aaa.early", "ORIGINAL"), forward)
        assertEquals(listOf("pre:aaa.early", "pre:mmm.mid", "pre:zzz.late", "ORIGINAL"), reversed)
    }

    @Test
    fun `tie场景registrations投影序与派发序一致_单函数内同源`() {
        val (pipeline, order) = newBoundPipeline(
            listOf(
                decl("zzz.late"),
                decl("aaa.early"),
            ),
        )
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        // 投影序：同函数键、同 priority → 声明序。投影与派发同源槽表（投影排序键
        // targetKey, priority, order；派发排序键 priority, order），单函数键时只差外键恒同。
        val projected = pipeline.registrations().map { it.functionKey to it.packageId }
        assertEquals(listOf("COM300" to "zzz.late", "COM300" to "aaa.early"), projected)
        // 单函数 tie 场景：投影序 == 实际派发序（去掉尾标 ORIGINAL 与相位前缀后逐位比对）。
        assertEquals(projected.map { it.second }, order.dropLast(1).map { it.removePrefix("pre:") })
    }

    @Test
    fun `多函数投影全序按functionKey升序_分键过滤后与各键派发序一致`() {
        val (pipeline, order) = newBoundPipeline(
            listOf(
                decl("zzz.late", target = "COM300"),
                decl("aaa.early", target = "COM300"),
                // mmm.mid 声明在最后，但函数键 COM100 字典序在前：投影全序第一段是 functionKey，
                // 声明序只在同函数键段内生效 —— 外键压过声明序的反向证明。
                decl("mmm.mid", target = "COM100"),
            ),
        )
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        val com300Hooks = order.filterNot { it == "ORIGINAL" }
        order.clear()
        pipeline.dispatch("COM100", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(2L)
        }
        val com100Hooks = order.filterNot { it == "ORIGINAL" }
        assertEquals(listOf("pre:zzz.late", "pre:aaa.early"), com300Hooks)
        assertEquals(listOf("pre:mmm.mid"), com100Hooks)

        // 投影全序：COM100 段整体在最前（外键字典序压过声明序）。如实钉住投影不是任何单次
        // 派发的序列 —— COM100 的行排在最前，但派发是按次调用的，单次只涉一个函数键。
        val projected = pipeline.registrations().map { it.functionKey to it.packageId }
        assertEquals(
            listOf("COM100" to "mmm.mid", "COM300" to "zzz.late", "COM300" to "aaa.early"),
            projected,
        )
        // 一致性成立在「分键过滤」层：每键过滤后的投影序 == 该键派发时的钩子序。
        assertEquals(
            com300Hooks.map { it.removePrefix("pre:") },
            projected.filter { it.first == "COM300" }.map { it.second },
        )
        assertEquals(
            com100Hooks.map { it.removePrefix("pre:") },
            projected.filter { it.first == "COM100" }.map { it.second },
        )
    }

    @Test
    fun `prefix_postfix混合同priority_prefix全部先于postfix_相位内声明序`() {
        // 全部 priority 0，声明序故意交错：POSTFIX(m.two) 夹在两个 PREFIX 之间。槽表序
        // （priority 相同 → 声明序）：pre:m.one, post:m.two, post:m.one, pre:m.two；
        // dispatch 按相位分两段扫同一张槽表，prefix 段先于原函数先于 postfix 段。
        val (pipeline, order) = newBoundPipeline(
            listOf(
                decl("m.one", phase = HookPhase.PREFIX),
                decl("m.two", phase = HookPhase.POSTFIX),
                decl("m.one", phase = HookPhase.POSTFIX),
                decl("m.two", phase = HookPhase.PREFIX),
            ),
        )
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        // prefix 全部先于 postfix（尽管 post:m.two 的声明序在 pre:m.two 之前）；
        // 各相位内部按声明序（postfix 段 m.two 先声明故先跑）。
        assertEquals(
            listOf("pre:m.one", "pre:m.two", "ORIGINAL", "post:m.two", "post:m.one"),
            order,
        )
        // 投影序不带相位偏置（kind 不参与排序）：同函数键内就是纯声明序 —— 与派发序不同
        // （派发按相位分段），对应关系 = 分相位过滤后逐位一致。
        val projected = pipeline.registrations().map { it.kind to it.packageId }
        assertEquals(
            listOf(
                ModHookPhase.PREFIX to "m.one",
                ModHookPhase.POSTFIX to "m.two",
                ModHookPhase.POSTFIX to "m.one",
                ModHookPhase.PREFIX to "m.two",
            ),
            projected,
        )
        assertEquals(
            listOf("pre:m.one", "pre:m.two"),
            projected.filter { it.first == ModHookPhase.PREFIX }.map { "pre:" + it.second },
        )
        assertEquals(
            listOf("post:m.two", "post:m.one"),
            projected.filter { it.first == ModHookPhase.POSTFIX }.map { "post:" + it.second },
        )
    }
}
