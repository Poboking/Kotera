package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * `continueOnHandlerFailure` 契约闸（§18.4 追加：HookPipeline 处理器失败后的槽撤销）。
 *
 * 两个失败轴别混：
 * - `runOnFailure`：**被 hook 的原函数**抛异常时本槽是否补跑（POSTFIX 专用，锁在
 *   [HookPipelineTest]）；
 * - `continueOnHandlerFailure`：**处理器自己**抛异常时本槽是否保持绑定。缺省 true = 逐位保持
 *   历史行为（每次都记失败、下次照进）；false = 首次异常后撤销本槽，此后派发不再进入。
 *
 * 撤销是槽级状态（targetKey + phase + packageId）：不跨槽、不跨包；撤销后可经
 * `retiredSlotCount` / `isSlotRetired(...)` 读出，并在 [RuntimeDiagnostics] 留一条
 * `HANDLER_THREW_SLOT_RETIRED`（只一条，不随派发重复刷）；同槽重新 bind 即解除（撤销只针对
 * 上一次绑定）。
 */
class ContinueOnHandlerFailureTest {

    private fun decl(
        packageId: String,
        phase: HookPhase = HookPhase.PREFIX,
        continueOnHandlerFailure: Boolean = true,
        runOnFailure: Boolean = false,
    ): DeclaredHook = DeclaredHook(
        packageId = packageId,
        declaration = HookDeclaration(
            targetKey = TARGET,
            phase = phase,
            runOnFailure = runOnFailure,
            continueOnHandlerFailure = continueOnHandlerFailure,
        ),
    )

    private fun boomHandler(onCall: () -> Unit = {}): HookHandler = HookHandler {
        onCall()
        error("handler-boom")
    }

    @Test
    fun `缺省 true 处理器异常只记失败 不撤销且后续照进`() {
        val diagnostics = RuntimeDiagnostics(capacity = 8)
        val pipeline = HookPipeline.of(listOf(decl(PKG)), diagnostics)
        var calls = 0
        pipeline.bind(TARGET, ModHookPhase.PREFIX, PKG, boomHandler { calls++ })

        val first = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val second = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(2, calls, "缺省 true：处理器每次派发都会被调用")
        assertEquals(1, first.failures.size)
        assertEquals(1, second.failures.size)
        assertEquals(ModValue.of(1L), second.returnValue, "处理器失败不影响原函数返回值")
        assertTrue(second.originalRan)
        assertEquals(0, pipeline.retiredSlotCount)
        assertFalse(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, PKG))
        assertEquals(
            0,
            diagnostics.snapshot().count { it.reasonCode == "HANDLER_THREW_SLOT_RETIRED" },
            "缺省 true 不产生撤销诊断",
        )
    }

    @Test
    fun `false 首次异常撤销本槽 撤销诊断只一条且后续派发不再进入`() {
        val diagnostics = RuntimeDiagnostics(capacity = 8)
        val pipeline = HookPipeline.of(listOf(decl(PKG, continueOnHandlerFailure = false)), diagnostics)
        var calls = 0
        pipeline.bind(TARGET, ModHookPhase.PREFIX, PKG, boomHandler { calls++ })

        val first = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val second = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(1, calls, "false：撤销后第二次派发不再进入处理器")
        assertEquals(1, first.failures.size)
        assertTrue(second.failures.isEmpty(), "撤销后的派发不再产生处理器失败")
        assertTrue(second.originalRan, "撤销槽不影响原函数执行")
        assertEquals(ModValue.of(1L), second.returnValue)
        assertTrue(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, PKG))
        assertEquals(1, pipeline.retiredSlotCount)
        val retired = diagnostics.snapshot().filter { it.reasonCode == "HANDLER_THREW_SLOT_RETIRED" }
        assertEquals(1, retired.size, "撤销诊断只记一条（不随派发重复刷）")
        assertEquals(DiagnosticOutcome.REJECTED, retired.single().outcome)
        assertEquals(PKG, retired.single().packageId)
        assertEquals(
            1,
            diagnostics.snapshot().count { it.reasonCode == "HANDLER_THREW" },
            "首次失败的 HANDLER_THREW 照记",
        )
    }

    @Test
    fun `撤销只影响本槽 同包另一相位与其它包照跑`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl(PKG, phase = HookPhase.PREFIX, continueOnHandlerFailure = false),
                decl(PKG, phase = HookPhase.POSTFIX),
                decl(OTHER_PKG),
            ),
        )
        val calls = mutableListOf<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, PKG, boomHandler { calls += "retire-prefix" })
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, PKG) {
            calls += "retire-postfix"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, OTHER_PKG) {
            calls += "other-prefix"
            HookResult.Continue
        }

        pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        calls.clear()
        val second = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(listOf("other-prefix", "retire-postfix"), calls, "只撤本槽，另一相位与其它包照跑")
        assertTrue(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, PKG))
        assertFalse(pipeline.isSlotRetired(TARGET, ModHookPhase.POSTFIX, PKG))
        assertFalse(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, OTHER_PKG))
        assertEquals(1, pipeline.retiredSlotCount)
        assertTrue(second.failures.isEmpty())
    }

    @Test
    fun `重新 bind 解除撤销 槽恢复可用`() {
        val pipeline = HookPipeline.of(listOf(decl(PKG, continueOnHandlerFailure = false)))
        pipeline.bind(TARGET, ModHookPhase.PREFIX, PKG, boomHandler())
        pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        assertTrue(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, PKG))

        var reboundCalls = 0
        pipeline.bind(TARGET, ModHookPhase.PREFIX, PKG) {
            reboundCalls += 1
            HookResult.SkipOriginal
        }
        assertFalse(pipeline.isSlotRetired(TARGET, ModHookPhase.PREFIX, PKG), "重新绑定 = 撤销解除")
        val outcome = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        assertEquals(1, reboundCalls)
        assertFalse(outcome.originalRan, "新绑定生效（SkipOriginal 跳过原函数）")
    }

    @Test
    fun `旧 manifest 文本往返 新字段取缺省 true 且不写进文本`() {
        val descriptor = ModuleDescriptor(
            packageId = "mod.legacy",
            hookDeclarations = listOf(
                HookDeclaration(targetKey = TARGET, phase = HookPhase.PREFIX, runOnFailure = false),
            ),
        )
        val text = String(ManifestCodec.encode(descriptor), Charsets.UTF_8)
        val decoded = ManifestCodec.decode(ManifestCodec.encode(descriptor))

        assertEquals(1, decoded.hookDeclarations.size)
        assertTrue(
            decoded.hookDeclarations.single().continueOnHandlerFailure,
            "新字段不进 manifest 文本格式 ⇒ 旧文本解析后必须取缺省 true",
        )
        assertFalse(text.contains("continueOnHandlerFailure"), "ManifestCodec 未改：字段不写进文本")
        assertFalse(text.contains("continue-on-handler-failure"), "ManifestCodec 未改：字段不写进文本")
    }

    @Test
    fun `撤销后的槽不再参加原函数异常补跑 但未撤销的 runOnFailure 槽照跑`() {
        val pipeline = HookPipeline.of(
            listOf(
                decl(PKG, phase = HookPhase.POSTFIX, continueOnHandlerFailure = false, runOnFailure = true),
                decl(OTHER_PKG, phase = HookPhase.POSTFIX, runOnFailure = true),
            ),
        )
        var retiredCalls = 0
        var liveCalls = 0
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, PKG, boomHandler { retiredCalls++ })
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, OTHER_PKG) {
            liveCalls++
            HookResult.Continue
        }
        // 第一次正常派发：PKG 处理器抛异常 ⇒ 撤销；随后原函数抛异常触发补跑段。
        runCatching { pipeline.dispatch(TARGET, emptyArray()) { throw IllegalStateException("original-boom") } }
        assertEquals(1, retiredCalls)
        val liveAfterRetire = liveCalls
        // 第二次：只剩未撤销的 OTHER_PKG 参加补跑段（PKG 已撤销、不再进入）。
        runCatching { pipeline.dispatch(TARGET, emptyArray()) { throw IllegalStateException("original-boom") } }
        assertEquals(1, retiredCalls, "撤销槽不再进入补跑段")
        assertEquals(1, liveAfterRetire, "补跑段首次同时进入未撤销槽")
        assertEquals(liveAfterRetire + 1, liveCalls, "未撤销的 runOnFailure 槽照跑补跑段")
    }

    private companion object {
        private const val TARGET = "COM300"
        private const val PKG = "pkg.retire"
        private const val OTHER_PKG = "pkg.other"
    }
}
