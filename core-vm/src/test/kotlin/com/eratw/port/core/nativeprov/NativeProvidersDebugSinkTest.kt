package com.eratw.port.core.nativeprov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * debug 计时/日志面的定向单测（契约 MODERN-R13-NAT0 §2 判据 6）：
 * debug=1 时计时/日志经**注入** sink 落形（假 sink 断言）；缺省（debug=0 或不注 sink）零输出。
 *
 * 语义锚：ADR-010 §3 :217（`-Deratw.native.debug=0|1` 计时/日志）、§4 :232（可观测面的
 * 桌面投影——真机侧将来进 DevTools/RunTrail，桌面先走注入 sink）。
 */
class NativeProvidersDebugSinkTest {

    /** 假 sink：把投递收进列表供断言（实现纪律：不抛、不阻塞——本假件天然满足）。 */
    private class FakeSink : NativeProviderDebugSink {
        val records = mutableListOf<NativeProviderDebugRecord>()

        override fun onRecord(record: NativeProviderDebugRecord) {
            records.add(record)
        }
    }

    /** 只配置 AUDIO 一键的直构造档。 */
    private fun config(audio: NativeProviderMode, debug: Boolean): NativeProvidersConfig =
        NativeProvidersConfig(
            modes = NativeKind.entries.associateWith { kind ->
                if (kind == NativeKind.AUDIO) audio else NativeProviderMode.OFF
            },
            debug = debug,
        )

    @Test
    fun `debug1_AUTO 成功_投恰一条记录_含非负计时`() {
        val sink = FakeSink()
        val sel = NativeProviders(config(NativeProviderMode.AUTO, debug = true), sink)
            .selection(NativeKind.AUDIO) { true }
        assertEquals(1, sink.records.size, "一次 selection 恰投一条")
        val r = sink.records[0]
        assertEquals(NativeKind.AUDIO, r.kind)
        assertEquals(NativeProviderMode.AUTO, r.mode)
        assertEquals(ResolvedProvider.NATIVE, r.resolved)
        assertNull(r.warning, "成功路径无告警")
        assertTrue(r.probeNanos >= 0, "计时恒非负：${r.probeNanos}")
        assertNull(sel.warning)
    }

    @Test
    fun `debug1_AUTO 回落_记录携带与结果同一条告警`() {
        val sink = FakeSink()
        val sel = NativeProviders(config(NativeProviderMode.AUTO, debug = true), sink)
            .selection(NativeKind.AUDIO) { false }
        assertEquals(1, sink.records.size, "回落路径也是恰一条记录（告警恰一条不重复投）")
        val r = sink.records[0]
        assertEquals(ResolvedProvider.KOTLIN, r.resolved)
        assertEquals(NativeProviderMode.AUTO, r.mode)
        assertEquals(sel.warning, r.warning, "记录与结果同一条告警文案")
        assertTrue(r.probeNanos >= 0)
    }

    @Test
    fun `debug1_FORCE 成功_投记录`() {
        val sink = FakeSink()
        val sel = NativeProviders(config(NativeProviderMode.FORCE, debug = true), sink)
            .selection(NativeKind.AUDIO) { true }
        assertEquals(1, sink.records.size)
        val r = sink.records[0]
        assertEquals(NativeProviderMode.FORCE, r.mode)
        assertEquals(ResolvedProvider.NATIVE, r.resolved)
        assertNull(r.warning)
        assertEquals(ResolvedProvider.NATIVE, sel.resolved)
    }

    @Test
    fun `debug1_OFF_零记录_零日志`() {
        val sink = FakeSink()
        val sel = NativeProviders(config(NativeProviderMode.OFF, debug = true), sink)
            .selection(NativeKind.AUDIO) { true }
        assertEquals(0, sink.records.size, "OFF 短路零日志：debug=1 也不投")
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved)
    }

    @Test
    fun `缺省 debug0_注入了 sink 也零输出`() {
        val sink = FakeSink()
        val providers = NativeProviders(config(NativeProviderMode.AUTO, debug = false), sink)
        providers.selection(NativeKind.AUDIO) { true }
        providers.selection(NativeKind.AUDIO) { false }
        assertEquals(0, sink.records.size, "开关在 debug 位：debug=0 即使有 sink 也零输出")
    }

    @Test
    fun `debug1_未注入 sink_选择照常返回不炸`() {
        // 缺省零输出：无 sink 即无投递出口；选择语义不受影响（AUTO 回落照常回落）。
        val sel = NativeProviders(config(NativeProviderMode.AUTO, debug = true))
            .selection(NativeKind.AUDIO) { false }
        assertEquals(ResolvedProvider.KOTLIN, sel.resolved)
        assertEquals(
            "eratw.native.audio=auto：原生候选自检失败（probe 返回 false），已回落 KOTLIN",
            sel.warning,
            "告警文案随结果返回（值，不是副作用），不依赖 sink 存在",
        )
    }
}
