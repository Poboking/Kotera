package com.eratw.port.modapi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * hook 可挂函数键发现面（[HookApi.knownFunctionKeys]，MODDX4-B3）的 mod-api 层语义闸。
 *
 * 本模块零 core 依赖，闸只钉缺省实现的 fail-closed 口径：接口缺省抛 UOE 且文案点名方法与
 * 「未接线」，与 [HookApi.registrations] 缺省（ModDX2-G3）及 [EventApi.exists] / [EventApi.knownEventKeys]
 * 缺省（AE 车，v14）同形 —— 不认识本方法的旧实现绝不静默返回空集（空集会被读成「函数目录为空」，
 * 与「宿主没接线」必须可分辨）。真源与灌缝口径（SymbolCatalog.bindings → 宿主 → 各 face）在
 * :core-vm 的 ModDx4HookCatalogTest 与台架侧（RecordingFixturesTest）逐条钉死。
 */
class ModDx4HookCatalogTest {

    @Test
    fun `HookApi 缺省实现 knownFunctionKeys 显式 fail_closed`() {
        val impl = object : HookApi {
            override fun register(
                targetKey: String,
                phase: HookPhase,
                handler: HookHandler,
            ): Registration = error("本用例只测缺省实现，不注册")
        }

        val failure = assertThrows<UnsupportedOperationException> { impl.knownFunctionKeys() }
        assertTrue(failure.message!!.contains("hooks.knownFunctionKeys()"), failure.message)
        assertTrue(failure.message!!.contains("未接线"), failure.message)
        assertTrue(failure.message!!.contains("不猜函数面"), failure.message)
    }
}
