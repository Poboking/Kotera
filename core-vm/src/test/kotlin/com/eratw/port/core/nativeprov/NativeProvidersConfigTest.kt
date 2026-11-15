package com.eratw.port.core.nativeprov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 配置链解析的定向单测（契约 MODERN-R13-NAT0 §2 判据 1/2，外加解析侧确定性）。
 *
 * 全部喂 map（[NativeProvidersConfig.parse] 纯函数形态），不碰进程性质表——
 * System properties 只在解析层读、测试不依赖进程全局（契约铁律 3）。
 * 语义锚：ADR-010 §3（`docs/eratw-架构决策记录.md:213-221`）。
 */
class NativeProvidersConfigTest {

    // ---- 判据 1：优先级 系统性质 > 构建档占位 > 默认 off；四旋钮独立；debug 单列 ----

    @Test
    fun `缺省_四类全 off 且 debug 关`() {
        val c = NativeProvidersConfig.parse(emptyMap())
        assertEquals(NativeProvidersConfig.DEFAULT, c, "全缺省 = 平台默认（ADR-010 §3 :221 首版全 off）")
        for (kind in NativeKind.entries) {
            assertEquals(NativeProviderMode.OFF, c.mode(kind), "${kind.key} 缺省 off")
        }
        assertEquals(false, c.debug, "debug 缺省 0")
    }

    @Test
    fun `优先级_系统性质覆盖构建档_构建档覆盖默认`() {
        val build = mapOf(
            "eratw.native.image" to "auto",
            "eratw.native.audio" to "force",
        )
        val system = mapOf("eratw.native.image" to "force")
        val c = NativeProvidersConfig.parse(system, build)
        assertEquals(NativeProviderMode.FORCE, c.mode(NativeKind.IMAGE), "image：系统(force) 压过 构建(auto)")
        assertEquals(NativeProviderMode.FORCE, c.mode(NativeKind.AUDIO), "audio：构建(force) 压过 默认")
        assertEquals(NativeProviderMode.OFF, c.mode(NativeKind.GLYPH), "glyph：两层都没给 = 默认 off")
        assertEquals(NativeProviderMode.OFF, c.mode(NativeKind.KERNELS))
        assertEquals(false, c.debug)
    }

    @Test
    fun `四旋钮相互独立_给一个不带动其他`() {
        val c = NativeProvidersConfig.parse(
            mapOf(
                "eratw.native.image" to "force",
                "eratw.native.kernels" to "auto",
            ),
        )
        assertEquals(NativeProviderMode.FORCE, c.mode(NativeKind.IMAGE))
        assertEquals(NativeProviderMode.AUTO, c.mode(NativeKind.KERNELS))
        assertEquals(NativeProviderMode.OFF, c.mode(NativeKind.AUDIO), "未给的键保持默认（互不牵连，ADR-010 §2 :208）")
        assertEquals(NativeProviderMode.OFF, c.mode(NativeKind.GLYPH))
    }

    @Test
    fun `debug 单列_与档位互不影响`() {
        val withDebug = NativeProvidersConfig.parse(
            mapOf("eratw.native.debug" to "1", "eratw.native.image" to "force"),
        )
        assertEquals(true, withDebug.debug, "debug=1 生效")
        assertEquals(NativeProviderMode.FORCE, withDebug.mode(NativeKind.IMAGE), "debug 不改档位")
        assertEquals(NativeProviderMode.OFF, withDebug.mode(NativeKind.AUDIO))
        val modesOnly = NativeProvidersConfig.parse(mapOf("eratw.native.glyph" to "auto"))
        assertEquals(false, modesOnly.debug, "只给档位不动 debug")
        assertEquals(NativeProviderMode.AUTO, modesOnly.mode(NativeKind.GLYPH))
    }

    @Test
    fun `档位值 trim 与大小写不敏感`() {
        val c = NativeProvidersConfig.parse(
            mapOf(
                "eratw.native.image" to " FORCE ",
                "eratw.native.audio" to "Auto",
            ),
        )
        assertEquals(NativeProviderMode.FORCE, c.mode(NativeKind.IMAGE))
        assertEquals(NativeProviderMode.AUTO, c.mode(NativeKind.AUDIO))
    }

    // ---- 判据 2：非法档位显式报错，绝不静默当 off ----

    @Test
    fun `非法档位_yes_显式报错_消息点名键与原文`() {
        val e = assertFailsWith<IllegalArgumentException> {
            NativeProvidersConfig.parse(mapOf("eratw.native.image" to "yes"))
        }
        assertTrue(e.message!!.contains("eratw.native.image"), "点名键：${e.message}")
        assertTrue(e.message!!.contains("yes"), "点名原文：${e.message}")
    }

    @Test
    fun `非法档位_四类旋钮逐个显式报错`() {
        for (kind in NativeKind.entries) {
            assertFailsWith<IllegalArgumentException>("${kind.key} 的非法档位必须炸出来") {
                NativeProvidersConfig.parse(mapOf(NativeProvidersConfig.propertyKey(kind) to "yes"))
            }
        }
    }

    @Test
    fun `非法档位_构建档占位层同样报错且标明来源`() {
        val e = assertFailsWith<IllegalArgumentException> {
            NativeProvidersConfig.parse(emptyMap(), mapOf("eratw.native.glyph" to "on"))
        }
        assertTrue(e.message!!.contains("构建档占位"), "标明来源层：${e.message}")
    }

    @Test
    fun `非法档位_系统性质层报错标明来源`() {
        val e = assertFailsWith<IllegalArgumentException> {
            NativeProvidersConfig.parse(mapOf("eratw.native.kernels" to ""))
        }
        assertTrue(e.message!!.contains("系统性质"), "标明来源层：${e.message}")
    }

    @Test
    fun `空串与纯空白也是非法档位_不许静默回落 off`() {
        for (raw in listOf("", "   ")) {
            assertFailsWith<IllegalArgumentException>("空档位「$raw」不许被吞成 off") {
                NativeProvidersConfig.parse(mapOf("eratw.native.image" to raw))
            }
        }
    }

    @Test
    fun `debug 非法值显式报错_不静默当 0`() {
        for (raw in listOf("2", "true", "yes", "")) {
            assertFailsWith<IllegalArgumentException>("debug=「$raw」必须炸（合法域 0|1，ADR-010 §3 :217）") {
                NativeProvidersConfig.parse(mapOf(NativeProvidersConfig.PROPERTY_DEBUG to raw))
            }
        }
    }

    // ---- 判据 7（解析侧）：同 map 同结果（确定性）----

    @Test
    fun `同一 props 两次解析_结果相等`() {
        val props = mapOf(
            "eratw.native.image" to "force",
            "eratw.native.audio" to "auto",
            "eratw.native.debug" to "1",
        )
        assertEquals(
            NativeProvidersConfig.parse(props),
            NativeProvidersConfig.parse(props),
            "parse 是纯函数：同输入同输出",
        )
    }
}
