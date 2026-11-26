package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 录制型三语文案面的自测：台架的 [`RecordingStrings`] 声明/校验/回退链复用引擎那份
 * `ModStringsRegistry`，所以这三档断言在真引擎上同口径（不会「台架绿、引擎红」）。
 */
class RecordingStringsTest {

    private val harness = TestSchema.worldWithPeer().newContext(TestSchema.PACKAGE_ID)

    @Test
    fun `声明后按语言取且未声明键回落键本身`() {
        harness.strings.declare(
            key = "btn.hello",
            zh = "打个招呼",
            en = "Say hello",
            ja = "あいさつする",
        )
        assertEquals("打个招呼", harness.strings.of("btn.hello"))
        assertEquals(setOf("btn.hello"), harness.stringsRecorder.keys())
        assertTrue(harness.strings.has("btn.hello"))
        assertEquals("btn.ghost", harness.strings.of("btn.ghost"))
        assertFalse(harness.strings.has("btn.ghost"))
    }

    @Test
    fun `改语言码后取到新语言`() {
        harness.strings.declare("btn.hello", "打个招呼", "Say hello", "あいさつする")
        assertEquals("zh", harness.strings.language())
        harness.stringsRecorder.languageCode = "ja"
        assertEquals("ja", harness.strings.language())
        assertEquals("あいさつする", harness.strings.of("btn.hello"))
        assertEquals("Say hello", harness.stringsRecorder.text("btn.hello", "en"))
    }

    @Test
    fun `坏键在声明期抛与生产同口径`() {
        assertFailsWith<IllegalArgumentException> {
            harness.strings.declare("Btn.Hello", "打个招呼", "Say hello", "あいさつする")
        }
        assertFailsWith<IllegalArgumentException> {
            harness.strings.declare("btn.hello", "  ", "Say hello", "あいさつする")
        }
        assertEquals("Btn.Hello", harness.strings.of("Btn.Hello"), "被拒的键不落表")
    }
}
