package com.eratw.port.core.config

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `setting.json`（原版 JSONConfig）读取口径：
 *
 * - 键存在时取字面布尔（大小写不敏感、允许任意空白）；
 * - 缺文件 / 缺键 / 非法内容一律回落 C# `bool` 缺省 false（`JSONConfigData.cs:13`）；
 * - 只认本引擎有消费点的键，其余键忽略（System.Text.Json 的未知字段容忍）。
 *
 * 判据用例取真实游戏目录的原文：eratw 4.980 与 eraFL 的 `setting.json`。
 */
class JsonSettingTest {

    @Test
    fun `eraFL写法取true`() {
        val s = JsonSetting.parse(
            """{"UseButtonFocusBackgroundColor":false,"UseNewRandom":false,"UseScopedVariableInstruction":true}""",
        )
        assertTrue(s.useScopedVariableInstruction, "eraFL setting.json 原文：UseScopedVariableInstruction=true")
    }

    @Test
    fun `eratw写法取false`() {
        val s = JsonSetting.parse(
            """{"UseButtonFocusBackgroundColor":false,"UseNewRandom":false,"UseScopedVariableInstruction":false}""",
        )
        assertFalse(s.useScopedVariableInstruction)
    }

    @Test
    fun `缺键与空串回落缺省false`() {
        assertFalse(JsonSetting.parse("""{"UseNewRandom":true}""").useScopedVariableInstruction)
        assertFalse(JsonSetting.parse("").useScopedVariableInstruction)
        assertFalse(JsonSetting.parse("not json").useScopedVariableInstruction)
    }

    @Test
    fun `大小写与空白不敏感`(@TempDir dir: Path) {
        val f = dir.resolve(JsonSetting.FILE_NAME)
        f.writeText("""{ "useScopedVariableInstruction" :   TRUE }""")
        val loaded = JsonSetting.loadFile(f)
        assertTrue(loaded.useScopedVariableInstruction)
    }

    @Test
    fun `文件缺失回落缺省false`(@TempDir dir: Path) {
        assertFalse(JsonSetting.loadGameDir(dir).useScopedVariableInstruction)
    }
}
