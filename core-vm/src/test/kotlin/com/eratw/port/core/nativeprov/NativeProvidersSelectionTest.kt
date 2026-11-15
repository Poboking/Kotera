package com.eratw.port.core.nativeprov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 三档选择协议的定向单测（契约 MODERN-R13-NAT0 §2 判据 3/4/5）。
 *
 * 语义锚：ADR-010 §2 :205-207（OFF 缺省完整可用、AUTO 失败记一行告警回落 Kotlin、
 * FORCE 只用于 A/B 且显式失败不回落）。
 */
class NativeProvidersSelectionTest {

    /** 计数探针：统计被调次数；可指定返回值，或指定探针抛出的异常。 */
    private class CountingProbe(private val result: Boolean, private val thrown: Throwable? = null) {
        var calls: Int = 0
            private set

        fun probe(): Boolean {
            calls++
            thrown?.let { throw it }
            return result
        }
    }

    /** 只配置 AUDIO 一键的直构造档（判据面集中在单一子系统，其余保持默认 off）。 */
    private fun config(audio: NativeProviderMode, debug: Boolean = false): NativeProvidersConfig =
        NativeProvidersConfig(
            modes = NativeKind.entries.associateWith { kind ->
                if (kind == NativeKind.AUDIO) audio else NativeProviderMode.OFF
            },
            debug = debug,
        )

    // ---- 判据 3：OFF 短路——不调 probe、不产告警 ----

    @Test
    fun `OFF 短路_不调 probe 不产告警`() {
        val p = CountingProbe(result = false)
        val sel = NativeProviders(config(NativeProviderMode.OFF)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(0, p.calls, "OFF 不触自检（ADR-010 §2 :205：Kotlin 默认实现完整可用）")
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved)
        assertNull(sel.warning, "OFF 零告警")
        assertEquals(NativeProviderMode.OFF, sel.mode)
    }

    // ---- 判据 4：AUTO——probe 失败 ⇒ 恰一条告警 + resolved=KOTLIN；成功 ⇒ NATIVE ----

    @Test
    fun `AUTO probe 成功_resolved NATIVE_无告警`() {
        val p = CountingProbe(result = true)
        val sel = NativeProviders(config(NativeProviderMode.AUTO)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(1, p.calls, "每次 selection 恰好探一次")
        assertEquals(ResolvedProvider.NATIVE, sel.resolved)
        assertNull(sel.warning)
    }

    @Test
    fun `AUTO probe 失败_恰一条告警_回落 KOTLIN`() {
        val p = CountingProbe(result = false)
        val sel = NativeProviders(config(NativeProviderMode.AUTO)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(1, p.calls)
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved, "自检失败回落 Kotlin（ADR-010 §2 :206）")
        assertEquals(
            "eratw.native.audio=auto：原生候选自检失败（probe 返回 false），已回落 KOTLIN",
            sel.warning,
            "告警文案确定性（恰一条 = 单值字段，不叠加）",
        )
    }

    @Test
    fun `AUTO probe 抛 Exception_同按自检失败回落_告警含原因`() {
        val p = CountingProbe(result = true, thrown = IllegalStateException("lib missing"))
        val sel = NativeProviders(config(NativeProviderMode.AUTO)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(1, p.calls)
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved)
        assertEquals(
            "eratw.native.audio=auto：原生候选自检失败（IllegalStateException: lib missing），已回落 KOTLIN",
            sel.warning,
        )
    }

    @Test
    fun `AUTO probe 抛 UnsatisfiedLinkError_加载失败同算自检失败`() {
        val p = CountingProbe(result = true, thrown = UnsatisfiedLinkError("liberatwaccel.so not found"))
        val sel = NativeProviders(config(NativeProviderMode.AUTO)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved, "加载失败也要回落（ADR-010 §2 :206）")
        assertEquals(
            "eratw.native.audio=auto：原生候选自检失败（UnsatisfiedLinkError: liberatwaccel.so not found），已回落 KOTLIN",
            sel.warning,
        )
        assertEquals(1, p.calls)
    }

    // ---- 判据 5：FORCE——probe 失败 ⇒ 显式失败，绝不回落 KOTLIN；成功 ⇒ NATIVE ----

    @Test
    fun `FORCE probe 成功_resolved NATIVE`() {
        val p = CountingProbe(result = true)
        val sel = NativeProviders(config(NativeProviderMode.FORCE)).selection(NativeKind.AUDIO, p::probe)
        assertEquals(1, p.calls)
        assertEquals(ResolvedProvider.NATIVE, sel.resolved)
        assertNull(sel.warning)
    }

    @Test
    fun `FORCE probe 失败_显式抛错_绝不回落 KOTLIN`() {
        val p = CountingProbe(result = false)
        val e = assertFailsWith<IllegalStateException>("force 档候选不可用必须显式失败（ADR-010 §2 :207）") {
            NativeProviders(config(NativeProviderMode.FORCE)).selection(NativeKind.AUDIO, p::probe)
        }
        assertEquals(1, p.calls, "失败前确实探过一次")
        assertTrue(e.message!!.contains("eratw.native.audio"), "点名键：${e.message}")
        assertTrue(e.message!!.contains("force"), "点名档位：${e.message}")
        assertTrue(e.message!!.contains("不回落"), "显式失败语义写进消息：${e.message}")
    }

    @Test
    fun `FORCE probe 抛异常_同样显式失败_原异常挂 cause 不丢栈`() {
        val original = UnsatisfiedLinkError("liberatwaccel.so not found")
        val p = CountingProbe(result = true, thrown = original)
        val e = assertFailsWith<IllegalStateException>("FORCE 对探针异常与 probe=false 同口径：显式失败不回落") {
            NativeProviders(config(NativeProviderMode.FORCE)).selection(NativeKind.AUDIO, p::probe)
        }
        assertSame(original, e.cause, "原异常挂 cause（原栈不丢，不吞不静默）")
        assertTrue(e.message!!.contains("UnsatisfiedLinkError"), "归因点名异常类：${e.message}")
        assertTrue(e.message!!.contains("不回落"), "显式失败语义写进消息：${e.message}")
        assertEquals(1, p.calls)
    }
}
