package com.eratw.port.modtesting

import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.Registration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CLEANUP_PACKAGE_ID = "test.cleanup"
private const val CLEANUP_TARGET_KEY = "TEST.RETRY"

/**
 * 槽状态清理对拍钉子：handlers（绑定）与 retired（撤销读数）必须对同一 key 同进同出，
 * 与生产 `HookPipeline` 的 bind / unregister / handleHandlerFailure 三处口径一致。
 *
 * 修复前的两处遗漏分别让本类与生产产生可观测偏差：
 * - 撤销分支只加 retired、不解绑 handler ⇒ 同 key 重注册被「重复绑定」闸误拒；
 * - unregister 只删 handler、不清 retired ⇒ 注销后 isSlotRetired / retiredSlotCount 对已注销槽说谎。
 */
class RecordingHooksSlotCleanupTest {

    /** 声明一条 `continueOnHandlerFailure=false` 的 PREFIX 槽，首次处理器异常即触发撤销。 */
    private fun hooksWithRetiringSlot(): RecordingHooks = RecordingHooks(
        CLEANUP_PACKAGE_ID,
        HookRecordTable(
            listOf(
                MemorySchema.HookSlotSpec(
                    packageId = CLEANUP_PACKAGE_ID,
                    targetKey = CLEANUP_TARGET_KEY,
                    phase = HookPhase.PREFIX,
                    priority = 0,
                    mayMutateArgs = false,
                    mayMutateReturn = false,
                    runOnFailure = false,
                    continueOnHandlerFailure = false,
                    declarationOrder = 0,
                ),
            ),
        ),
    )

    private fun retireOnce(hooks: RecordingHooks): Registration {
        val registration = hooks.register(CLEANUP_TARGET_KEY, HookPhase.PREFIX) {
            throw IllegalStateException("boom")
        }
        // 必须走 dispatchWithOriginal：只有 runPrefix/runPostfix 用 invokeIsolated → noteHandlerFailure；
        // dispatchChain 直接调 handler，异常穿出且不触发撤销。
        val outcome = hooks.dispatchWithOriginal(CLEANUP_TARGET_KEY) { null }
        assertEquals(listOf(CLEANUP_PACKAGE_ID), outcome.failures)
        assertTrue(hooks.isSlotRetired(CLEANUP_TARGET_KEY, HookPhase.PREFIX))
        assertEquals(1, hooks.retiredSlotCount)
        return registration
    }

    @Test
    fun retiredSlotCanBeReregisteredWithSameKey() {
        val hooks = hooksWithRetiringSlot()
        retireOnce(hooks)

        // 修复前：撤销时未解绑，handlers 仍占着 key ⇒ 这里被「hook 处理器重复绑定」闸误拒。
        val ran = mutableListOf<String>()
        hooks.register(CLEANUP_TARGET_KEY, HookPhase.PREFIX) {
            ran += "rebound"
            HookResult.Continue
        }

        assertFalse(hooks.isSlotRetired(CLEANUP_TARGET_KEY, HookPhase.PREFIX))
        assertEquals(0, hooks.retiredSlotCount)
        hooks.dispatchWithOriginal(CLEANUP_TARGET_KEY) { null }
        assertEquals(listOf("rebound"), ran) // 重新绑定的处理器真的能跑
    }

    @Test
    fun unregisterClearsRetiredReadSurface() {
        val hooks = hooksWithRetiringSlot()
        val registration = retireOnce(hooks)

        registration.unregister()

        // 修复前：unregister 只删 handler，retired 残留 ⇒ 两处只读面对已注销槽说谎。
        assertFalse(hooks.isSlotRetired(CLEANUP_TARGET_KEY, HookPhase.PREFIX))
        assertEquals(0, hooks.retiredSlotCount)
    }
}
