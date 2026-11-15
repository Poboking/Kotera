package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * HookPipeline 契约闸（§20.1，ABI-9 Candidate）。
 *
 * 钉住：无 hook 完全旁路、priority + 声明序确定性、Replace 短路、SkipOriginal 语义、
 * 失败隔离、参数/返回值修改权限、runOnFailure、绑定 fail-closed。
 */
class HookPipelineTest {

    private fun decl(
        pkg: String,
        target: String = "COM300",
        phase: HookPhase = HookPhase.PREFIX,
        priority: Int = 0,
        mayMutateArgs: Boolean = false,
        mayMutateReturn: Boolean = false,
        runOnFailure: Boolean = false,
    ): DeclaredHook = DeclaredHook(
        packageId = pkg,
        declaration = HookDeclaration(
            targetKey = target,
            phase = phase,
            priority = priority,
            mayMutateArgs = mayMutateArgs,
            mayMutateReturn = mayMutateReturn,
            runOnFailure = runOnFailure,
        ),
    )

    @Test
    fun `无hook声明_直接执行原函数且完全旁路`() {
        val pipeline = HookPipeline.of(emptyList())
        var calls = 0
        val outcome = pipeline.dispatch("COM300", emptyArray()) {
            calls++
            ModValue.of(7L)
        }
        assertEquals(1, calls)
        assertEquals(ModValue.of(7L), outcome.returnValue)
        assertTrue(outcome.originalRan)
        assertFalse(outcome.replaced)
        assertTrue(outcome.failures.isEmpty())
        assertFalse(pipeline.hasHooks("COM300"))
    }

    @Test
    fun `多mod同目标_按priority升序与声明序执行`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", priority = 10),
                decl("pkgB", priority = 0),
                decl("pkgC", priority = 0),
            ),
        )
        val order = mutableListOf<String>()
        for (pkg in listOf("pkgA", "pkgB", "pkgC")) {
            pipeline.bind("COM300", ModHookPhase.PREFIX, pkg) {
                order += pkg
                HookResult.Continue
            }
        }
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("pkgB", "pkgC", "pkgA", "ORIGINAL"), order)
    }

    @Test
    fun `prefix_skipOriginal_跳过原函数但仍执行postfix`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", phase = HookPhase.PREFIX),
                decl("pkgA", phase = HookPhase.POSTFIX),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") {
            order += "PRE"
            HookResult.SkipOriginal
        }
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgA") {
            order += "POST"
            HookResult.Continue
        }
        val outcome = pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("PRE", "POST"), order)
        assertFalse(outcome.originalRan)
        assertNull(outcome.returnValue)
    }

    @Test
    fun `prefix_replace_短路原函数与余下hook`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", phase = HookPhase.PREFIX, priority = 0),
                decl("pkgB", phase = HookPhase.PREFIX, priority = 1),
                decl("pkgC", phase = HookPhase.POSTFIX),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") {
            order += "A"
            HookResult.Replace(ModValue.of(99L))
        }
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgB") {
            order += "B"
            HookResult.Continue
        }
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgC") {
            order += "C"
            HookResult.Continue
        }
        val outcome = pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("A"), order)
        assertEquals(ModValue.of(99L), outcome.returnValue)
        assertTrue(outcome.replaced)
        assertFalse(outcome.originalRan)
    }

    @Test
    fun `handler异常_失败隔离且其余hook与原函数继续`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgA", phase = HookPhase.PREFIX, priority = 0),
                decl("pkgB", phase = HookPhase.PREFIX, priority = 1),
                decl("pkgC", phase = HookPhase.POSTFIX),
            ),
        )
        val order = mutableListOf<String>()
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") {
            order += "A"
            error("boom")
        }
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgB") {
            order += "B"
            HookResult.Continue
        }
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgC") {
            order += "C"
            HookResult.Continue
        }
        val outcome = pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(5L)
        }
        assertEquals(listOf("A", "B", "ORIGINAL", "C"), order)
        assertEquals(1, outcome.failures.size)
        assertEquals("pkgA", outcome.failures.single().packageId)
        assertEquals(ModValue.of(5L), outcome.returnValue)
    }

    @Test
    fun `未授权实参修改不回流_授权时回流`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgNo", phase = HookPhase.PREFIX, priority = 0, mayMutateArgs = false),
                decl("pkgYes", phase = HookPhase.PREFIX, priority = 1, mayMutateArgs = true),
            ),
        )
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgNo") { ctx ->
            ctx.args[0].intValue = 1L
            HookResult.Continue
        }
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgYes") { ctx ->
            ctx.args[0].intValue = 42L
            HookResult.Continue
        }
        val args = arrayOf(ModCallArg(intValue = 7L))
        pipeline.dispatch("COM300", args) { ModValue.of(0L) }
        assertEquals(42L, args[0].intValue)

        val onlyNo = HookPipeline.of(listOf(decl("pkgNo", mayMutateArgs = false)))
        onlyNo.bind("COM300", ModHookPhase.PREFIX, "pkgNo") { ctx ->
            ctx.args[0].intValue = 1L
            HookResult.Continue
        }
        val args2 = arrayOf(ModCallArg(intValue = 7L))
        onlyNo.dispatch("COM300", args2) { ModValue.of(0L) }
        assertEquals(7L, args2[0].intValue)
    }

    @Test
    fun `postfix改返回值仅在授权时生效`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgNo", phase = HookPhase.POSTFIX, priority = 0, mayMutateReturn = false),
                decl("pkgYes", phase = HookPhase.POSTFIX, priority = 1, mayMutateReturn = true),
            ),
        )
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgNo") { ctx ->
            ctx.returnValue = ModValue.of(100L)
            HookResult.Continue
        }
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgYes") { ctx ->
            ctx.returnValue = ModValue.of(200L)
            HookResult.Continue
        }
        val outcome = pipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(ModValue.of(200L), outcome.returnValue)

        val onlyNo = HookPipeline.of(listOf(decl("pkgNo", phase = HookPhase.POSTFIX, mayMutateReturn = false)))
        onlyNo.bind("COM300", ModHookPhase.POSTFIX, "pkgNo") { ctx ->
            ctx.returnValue = ModValue.of(100L)
            HookResult.Continue
        }
        val outcome2 = onlyNo.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(ModValue.of(1L), outcome2.returnValue)
    }

    @Test
    fun `原函数异常_只跑runOnFailure的postfix且原异常照抛`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl("pkgNo", phase = HookPhase.POSTFIX, priority = 0, runOnFailure = false),
                decl("pkgYes", phase = HookPhase.POSTFIX, priority = 1, runOnFailure = true),
            ),
        )
        val ran = mutableListOf<String>()
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgNo") {
            ran += "NO"
            HookResult.Continue
        }
        pipeline.bind("COM300", ModHookPhase.POSTFIX, "pkgYes") {
            ran += "YES"
            HookResult.Continue
        }
        val error = assertThrows<IllegalStateException> {
            pipeline.dispatch("COM300", emptyArray()) { error("boom") }
        }
        assertEquals("boom", error.message)
        assertEquals(listOf("YES"), ran)
    }

    @Test
    fun `未声明槽绑定拒绝_重复绑定拒绝`() {
        val pipeline = HookPipeline.of(listOf(decl("pkgA")))
        assertThrows<IllegalArgumentException> {
            pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgB") { HookResult.Continue }
        }
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") { HookResult.Continue }
        assertThrows<IllegalArgumentException> {
            pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") { HookResult.Continue }
        }
    }
}
