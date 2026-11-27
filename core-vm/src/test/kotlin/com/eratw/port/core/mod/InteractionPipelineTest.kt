package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * InteractionPipeline 契约闸（§20.3，ABI-11 Candidate；§19.4.2 的可执行形态）。
 *
 * 钉住：五阶段执行序、INTERRUPT/REPLACE 语义、多目标上下文、嵌套 push/pop 与外层恢复、
 * 失败隔离、深度闸、异常后栈恢复、派发快照隔离、未声明钩子注册拒绝。
 */
class InteractionPipelineTest {

    private fun hookDecl(pkg: String, phase: InteractionPhase, priority: Int = 0): DeclaredInteractionHook =
        DeclaredInteractionHook(pkg, InteractionHookDeclaration(phase, priority))

    private fun context(id: String = "INT-1"): InteractionContext = InteractionContext(
        interactionId = id,
        actor = EntityId(1L),
        targets = listOf(EntityId(2L), EntityId(3L)),
        primaryTarget = EntityId(2L),
    )

    @Test
    fun `五阶段执行序`() {
        val declarations = ModInteractionPhase.entries.map {
            hookDecl("pkgA", toEnginePhase(it))
        }
        val pipeline = InteractionPipeline.of(declarations)
        val order = mutableListOf<String>()
        for (phase in ModInteractionPhase.entries) {
            pipeline.registerHook(phase, "pkgA") {
                order += phase.name
                InteractionResult.CONTINUE
            }
        }
        val result = pipeline.run(context()) {
            order += "MAIN_BODY"
            ModValue.of(1L)
        }
        assertEquals(
            listOf("PRECONDITION", "PRE", "MAIN", "MAIN_BODY", "POST", "SETTLEMENT"),
            order,
        )
        assertEquals(ModValue.of(1L), result.returnValue)
        assertFalse(result.interrupted)
    }

    @Test
    fun `多目标上下文原样送达五阶段`() {
        val expected = context()
        val pipeline = InteractionPipeline.of(
            ModInteractionPhase.entries.map { hookDecl("pkgA", toEnginePhase(it)) },
        )
        val observed = mutableListOf<InteractionContext>()
        for (phase in ModInteractionPhase.entries) {
            pipeline.registerHook(phase, "pkgA") {
                observed += it.interaction
                InteractionResult.CONTINUE
            }
        }

        pipeline.run(expected) { ModValue.of(1L) }

        assertEquals(List(ModInteractionPhase.entries.size) { expected }, observed)
        assertEquals(listOf(EntityId(2L), EntityId(3L)), observed.first().targets)
        assertEquals(EntityId(2L), observed.first().primaryTarget)
    }

    @Test
    fun `同阶段按priority再按包链接序且包内保持注册序`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                hookDecl("pkgA", InteractionPhase.PRE, priority = 9),
                hookDecl("pkgB", InteractionPhase.PRE, priority = 0),
                hookDecl("pkgC", InteractionPhase.PRE, priority = 9),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            order += "A1"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgC") {
            order += "C1"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            order += "A2"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgB") {
            order += "B1"
            InteractionResult.CONTINUE
        }
        pipeline.run(context()) { ModValue.of(1L) }
        assertEquals(listOf("B1", "A1", "A2", "C1"), order)
    }

    @Test
    fun `precondition_interrupt_中止主流程与后续阶段`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                hookDecl("pkgA", InteractionPhase.PRECONDITION),
                hookDecl("pkgA", InteractionPhase.POST),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.registerHook(ModInteractionPhase.PRECONDITION, "pkgA") {
            order += "PRE"
            InteractionResult.INTERRUPT
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") {
            order += "POST"
            InteractionResult.CONTINUE
        }
        val result = pipeline.run(context()) {
            order += "MAIN_BODY"
            ModValue.of(1L)
        }
        assertEquals(listOf("PRE"), order)
        assertTrue(result.interrupted)
        assertNull(result.returnValue)
    }

    @Test
    fun `pre_replace_跳过主流程但继续post与settlement`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                hookDecl("pkgA", InteractionPhase.PRE),
                hookDecl("pkgA", InteractionPhase.POST),
                hookDecl("pkgA", InteractionPhase.SETTLEMENT),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(99L)
            InteractionResult.REPLACE
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") {
            order += "POST"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.SETTLEMENT, "pkgA") {
            order += "SETTLE"
            InteractionResult.CONTINUE
        }
        val result = pipeline.run(context()) {
            order += "MAIN_BODY"
            ModValue.of(1L)
        }
        assertEquals(listOf("POST", "SETTLE"), order)
        assertEquals(ModValue.of(99L), result.returnValue)
        assertTrue(result.replaced)
        assertFalse(result.interrupted)
    }

    @Test
    fun `pre_replace_跳过main阶段钩子与主流程`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                hookDecl("pkgA", InteractionPhase.PRE),
                hookDecl("pkgA", InteractionPhase.MAIN),
                hookDecl("pkgA", InteractionPhase.POST),
            ),
        )
        var mainHookRan = false
        var mainBodyRan = false
        var postRan = false
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(42L)
            InteractionResult.REPLACE
        }
        pipeline.registerHook(ModInteractionPhase.MAIN, "pkgA") {
            mainHookRan = true
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") {
            postRan = true
            InteractionResult.CONTINUE
        }
        val result = pipeline.run(context()) {
            mainBodyRan = true
            ModValue.of(1L)
        }
        assertFalse(mainHookRan)
        assertFalse(mainBodyRan)
        assertTrue(postRan)
        assertEquals(ModValue.of(42L), result.returnValue)
        assertTrue(result.replaced)
        assertFalse(result.interrupted)
    }

    @Test
    fun `main阶段钩子replace_跳过主流程`() {
        val pipeline = InteractionPipeline.of(listOf(hookDecl("pkgA", InteractionPhase.MAIN)))
        pipeline.registerHook(ModInteractionPhase.MAIN, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(7L)
            InteractionResult.REPLACE
        }
        var mainRan = false
        val result = pipeline.run(context()) {
            mainRan = true
            ModValue.of(1L)
        }
        assertFalse(mainRan)
        assertEquals(ModValue.of(7L), result.returnValue)
        assertTrue(result.replaced)
    }

    @Test
    fun `post_replace改写结果`() {
        val pipeline = InteractionPipeline.of(listOf(hookDecl("pkgA", InteractionPhase.POST)))
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(88L)
            InteractionResult.REPLACE
        }
        val result = pipeline.run(context()) { ModValue.of(1L) }
        assertEquals(ModValue.of(88L), result.returnValue)
        assertTrue(result.replaced)
    }

    @Test
    fun `嵌套互动_内外层上下文独立且返回后恢复`() {
        val pipeline = InteractionPipeline.of(listOf(hookDecl("pkgA", InteractionPhase.PRE)))
        val outer = context("OUTER")
        val inner = context("INNER")
        val observed = mutableListOf<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            val current = pipeline.current()
            if (current?.interactionId == "OUTER") {
                observed += current.interactionId
                val innerResult = pipeline.run(inner) { ModValue.of(2L) }
                observed += pipeline.current()?.interactionId.orEmpty()
                observed += (innerResult.returnValue as ModValue.IntValue).value.toString()
            }
            InteractionResult.CONTINUE
        }
        val result = pipeline.run(outer) { ModValue.of(1L) }
        assertEquals(listOf("OUTER", "OUTER", "2"), observed)
        assertEquals(ModValue.of(1L), result.returnValue)
        assertEquals(0, pipeline.depth())
        assertNull(pipeline.current())
    }

    @Test
    fun `内层main抛异常被外层捕获后外层上下文与主流程继续`() {
        val pipeline = InteractionPipeline.of(listOf(hookDecl("pkgA", InteractionPhase.PRE)))
        val outer = context("OUTER")
        val observed = mutableListOf<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            val current = pipeline.current()
            if (current?.interactionId == "OUTER") {
                val failure = assertThrows<IllegalStateException> {
                    pipeline.run(context("INNER")) { error("inner-main-boom") }
                }
                observed += failure.message.orEmpty()
                observed += pipeline.current()?.interactionId.orEmpty()
            }
            InteractionResult.CONTINUE
        }
        var mainRan = false
        val result = pipeline.run(outer) {
            mainRan = true
            ModValue.of(1L)
        }
        assertEquals(listOf("inner-main-boom", "OUTER"), observed)
        assertTrue(mainRan)
        assertEquals(ModValue.of(1L), result.returnValue)
        assertTrue(result.failures.isEmpty())
        assertEquals(0, pipeline.depth())
        assertNull(pipeline.current())
    }

    @Test
    fun `hook失败_失败隔离且主流程继续`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                hookDecl("pkgA", InteractionPhase.POST),
                hookDecl("pkgB", InteractionPhase.POST),
            ),
        )
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { error("boom") }
        var b = 0
        pipeline.registerHook(ModInteractionPhase.POST, "pkgB") {
            b++
            InteractionResult.CONTINUE
        }
        val result = pipeline.run(context()) { ModValue.of(5L) }
        assertEquals(ModValue.of(5L), result.returnValue)
        assertEquals(listOf("pkgA"), result.failures)
        assertEquals(1, b)
    }

    @Test
    fun `超过深度闸_拒绝且不栈溢出`() {
        val pipeline = InteractionPipeline.of(
            listOf(hookDecl("pkgA", InteractionPhase.PRE)),
            maxDepth = 2,
        )
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            pipeline.run(context("DEPTH-${pipeline.depth()}")) { ModValue.of(0L) }
            InteractionResult.CONTINUE
        }
        assertThrows<InteractionDepthExceededException> {
            pipeline.run(context("ROOT")) { ModValue.of(1L) }
        }
        assertEquals(0, pipeline.depth())
        assertNull(pipeline.current())
    }

    @Test
    fun `run异常后栈恢复`() {
        val pipeline = InteractionPipeline.of(emptyList())
        assertThrows<IllegalStateException> {
            pipeline.run(context()) { error("main-boom") }
        }
        assertEquals(0, pipeline.depth())
        assertNull(pipeline.current())
    }

    @Test
    fun `派发中途注册_本轮快照不受影响`() {
        val pipeline = InteractionPipeline.of(listOf(hookDecl("pkgA", InteractionPhase.PRE)))
        var late = 0
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
                late++
                InteractionResult.CONTINUE
            }
            InteractionResult.CONTINUE
        }
        pipeline.run(context("R1")) { ModValue.of(1L) }
        assertEquals(0, late)
        pipeline.run(context("R2")) { ModValue.of(1L) }
        assertEquals(1, late)
    }

    @Test
    fun `未声明钩子注册拒绝`() {
        val pipeline = InteractionPipeline.of(emptyList())
        assertThrows<IllegalArgumentException> {
            pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { InteractionResult.CONTINUE }
        }
    }

    private fun toEnginePhase(phase: ModInteractionPhase): InteractionPhase = when (phase) {
        ModInteractionPhase.PRECONDITION -> InteractionPhase.PRECONDITION
        ModInteractionPhase.PRE -> InteractionPhase.PRE
        ModInteractionPhase.MAIN -> InteractionPhase.MAIN
        ModInteractionPhase.POST -> InteractionPhase.POST
        ModInteractionPhase.SETTLEMENT -> InteractionPhase.SETTLEMENT
    }
}
