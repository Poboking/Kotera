package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 遥测转义往返专项（STAB-B）。
 *
 * 钉 [UpdateTelemetry] 的 encode 与 parseLine 互为严格逆：控制字符写成 uXXXX 后能被
 * 对称还原；无控制字符的既有转义不回退；坏 uXXXX 保守还原、不崩不越界也不吞后文。
 * TODO(verify)：移植自造上报链，无 C# 原版锚；严重度低、不影响聚合。
 */
class UpdateTelemetryRoundTripTest {

    private object NoopStorage : UpdateTelemetry.Storage {
        override fun load(): List<String> = emptyList()
        override fun save(lines: List<String>) = Unit
    }

    private fun telemetry() = UpdateTelemetry(NoopStorage, { 100L }, { true })

    /** 用 check_failed 的 failure 维度承载被测字符串（encode/parseLine 本身不做合法性校验）。 */
    private fun withFailure(value: String) =
        UpdateTelemetry.Event("check_failed", 1_700_000_000_000L, 15L, failure = value)

    @Test
    fun 全部ASCII控制字符经编码往返逐字符还原() {
        val t = telemetry()
        for (code in 0..0x1F) {
            val original = withFailure(code.toChar().toString())
            val line = t.encode(original)
            val restored = t.parseLine(line)
            assertNotNull(restored, "控制字符 0x${code.toString(16)} 的行解析失败：$line")
            assertEquals(original, restored, "控制字符 0x${code.toString(16)} 往返丢字：$line")
        }
    }

    @Test
    fun 既有转义与普通维度往返不变() {
        val t = telemetry()
        val samples = listOf(
            "quote\"backslash\\end",
            "crlf\ntab\tcarriage\rreturn",
            "普通中文维度升级成功",
            "",
            "mixed\"\\\n\r\t中文",
        )
        for (value in samples) {
            val original = withFailure(value)
            assertEquals(original, t.parseLine(t.encode(original)), "往返回退：$value")
        }
    }

    @Test
    fun 坏u转义保守还原不崩溃不越界不吞后文() {
        val t = telemetry()
        val cases = listOf(
            "\\u" to "u",
            "\\u1" to "u1",
            "\\u12" to "u12",
            "\\u123" to "u123",
            "\\uZZZZ" to "uZZZZ",
            "\\u12G4" to "u12G4",
            "\\u12\\n" to "u12\n",
            "x\\u000" to "xu000",
        )
        for ((raw, expected) in cases) {
            val line = """{"event":"check_failed","at":123,"failure":"$raw"}"""
            val parsed = t.parseLine(line)
            assertNotNull(parsed, "坏 uXXXX 不应导致整行不可解析：$line")
            assertEquals(expected, parsed.failure, "坏 uXXXX 保守还原不符：$line")
        }
    }
}
