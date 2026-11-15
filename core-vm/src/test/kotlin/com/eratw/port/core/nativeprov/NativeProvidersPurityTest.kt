package com.eratw.port.core.nativeprov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纯度与确定性的定向单测（契约 MODERN-R13-NAT0 §2 判据 7）：
 * 无静态可变全局；同 config 同 selection（确定性）；多次 selection 结果一致。
 */
class NativeProvidersPurityTest {

    /**
     * 包内公开类型（含伴生、嵌套、枚举合成字段）的**所有**字段一律 final。
     *
     * Kotlin 的 object/companion 挂 `var` 会落成非 final 字段（静态字段或 Companion
     * 实例字段）——这个字节码层扫描把「偷偷挂全局」当场闸红，而不是靠自觉。
     * 与既有「平台 API 兼容闸」（AndroidApiCompatTest 扫字节码签名）同一思路的包内版。
     */
    @Test
    fun `包内类型零可变字段_含伴生与嵌套`() {
        val rootTypes = listOf(
            NativeKind::class.java,
            NativeProviderMode::class.java,
            NativeProvidersConfig::class.java,
            NativeProviderSelection::class.java,
            ResolvedProvider::class.java,
            NativeProviders::class.java,
            NativeProviderDebugRecord::class.java,
            NativeProviderDebugSink::class.java,
        )
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque(rootTypes)
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type)) {
                continue
            }
            for (field in type.declaredFields) {
                assertTrue(
                    java.lang.reflect.Modifier.isFinal(field.modifiers),
                    "${type.name}.${field.name} 必须 final（禁静态可变全局）",
                )
            }
            type.declaredClasses.forEach { queue.add(it) }
        }
    }

    @Test
    fun `同实例多次 selection_结果一致`() {
        val providers = NativeProviders(
            NativeProvidersConfig.parse(mapOf("eratw.native.audio" to "auto")),
        )
        val fail1 = providers.selection(NativeKind.AUDIO) { false }
        val fail2 = providers.selection(NativeKind.AUDIO) { false }
        assertEquals(fail1, fail2, "AUTO 回落：多次调用同结果（含恰一条告警的文案）")
        val ok1 = providers.selection(NativeKind.AUDIO) { true }
        val ok2 = providers.selection(NativeKind.AUDIO) { true }
        assertEquals(ok1, ok2, "AUTO 成功：多次调用同结果")
        assertEquals(ResolvedProvider.NATIVE, ok1.resolved)
    }

    @Test
    fun `同 config 不同实例_结果一致`() {
        val cfg = NativeProvidersConfig.parse(mapOf("eratw.native.audio" to "force"))
        val a = NativeProviders(cfg).selection(NativeKind.AUDIO) { true }
        val b = NativeProviders(cfg).selection(NativeKind.AUDIO) { true }
        assertEquals(a, b, "选择器无实例间状态漂移：同 config 同 selection")
    }

    @Test
    fun `DEFAULT 与 parse 空表_结果一致`() {
        assertEquals(
            NativeProvidersConfig.DEFAULT,
            NativeProvidersConfig.parse(emptyMap()),
            "平台默认可由纯函数复得（ADR-010 §3 :221 首版全 off）",
        )
    }
}
