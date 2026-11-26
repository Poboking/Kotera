package com.eratw.port.core.patch

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-115 向后兼容矩阵（manifest 面）：`RT2MANIFEST/3`、`/4`、`/5`、`/6` 的**手写旧字节**逐档
 * 验证「读得进 + 语义等价 + 不静默降级」。
 *
 * 纪律：本文件的字节全部按线格式常量手工拼装（[wire] 只做行拼接 + [str] 做 hex 编码），
 * **不得**用 [ManifestCodec.encode] 生成后再解码 —— 那测的是自己读自己，对向后兼容零鉴别力。
 * 盘面 fixtures 里没有 `/3~/6` 的历史产物（导入链从不留旧 manifest），故旧样例只能按
 * 线格式常量复原；`/3` 与 `/4` 的段表差异（meta 两字段 vs 五字段）、`/5` 的 settings 段、
 * `/6` 的 min-api 段是三个真实版本分界，必须逐档钉死。
 *
 * 线格式（[ManifestCodec] 写侧）：首行 magic；每段 `@name` + 段体 + `/`；段序 = SECTIONS。
 * 空段体 = 计数 `I0`（计数也是 I 行）；artifact-sha256 例外 = 单个字符串 token（空串 = `S`）。
 */
class ManifestLegacyCompatibilityMatrixTest {

    @Test
    fun `01 RT2MANIFEST-3 手写旧档解码成功且展示字段为空串`() {
        val legacy = wire(
            magic = "RT2MANIFEST/3",
            meta = listOf(str("legacy.pkg"), str("1.4.2")),
        )
        // 前置：首行确实是 /3（不是当前编码器产物，当前编码器对空展示字段同样出 /3，但本字节
        // 由本文件手工拼出，不经过 encode）。
        assertTrue(String(legacy, Charsets.UTF_8).startsWith("RT2MANIFEST/3\n"))

        val d = ManifestCodec.decode(legacy)
        assertEquals("legacy.pkg", d.packageId)
        assertEquals("1.4.2", d.version)
        // /3 无展示字段：读侧回落空串（列表显示名回落 packageId 的前提，见 app 侧用例 11b）。
        assertEquals("", d.displayName)
        assertEquals("", d.author)
        assertEquals("", d.description)
        // /3 无 settings / min-api 段：快照为空表、minApi 取缺省 0，不静默合成声明。
        assertTrue(d.settings.isEmpty())
        assertEquals(0, d.minApi)
        assertTrue(d.requires.isEmpty())
        assertTrue(d.exportedMacros.isEmpty())
    }

    @Test
    fun `01b RT2MANIFEST-3 旧档的其余段保持空声明而非猜缺段`() {
        val bytes = wire(
            magic = "RT2MANIFEST/3",
            meta = listOf(str("legacy.empty"), str("0.1")),
        )
        val d = ManifestCodec.decode(bytes)
        assertTrue(d.settings.isEmpty())
        assertTrue(d.overrideFunctions.isEmpty())
        assertTrue(d.entityRefs.isEmpty())
        assertTrue(d.hookDeclarations.isEmpty())
        assertTrue(d.interactionHookDeclarations.isEmpty())
        assertTrue(d.phaseDeclarations.isEmpty())
        assertTrue(d.jobDeclarations.isEmpty())
        assertTrue(d.jobTemplateDeclarations.isEmpty())
        assertTrue(d.entityArchetypes.isEmpty())
        assertTrue(d.relationshipSchemas.isEmpty())
        assertEquals("", d.artifactSha256)
    }

    @Test
    fun `02 RT2MANIFEST-4 旧档有展示字段但无 settings 段且不抛`() {
        val legacy = wire(
            magic = "RT2MANIFEST/4",
            meta = listOf(
                str("legacy.meta"),
                str("2.0.0"),
                str("旧档显示名"),
                str("旧档作者"),
                str("旧档说明"),
            ),
        )
        val d = ManifestCodec.decode(legacy)
        assertEquals("legacy.meta", d.packageId)
        assertEquals("2.0.0", d.version)
        assertEquals("旧档显示名", d.displayName)
        assertEquals("旧档作者", d.author)
        assertEquals("旧档说明", d.description)
        // /4 段表不含 settings / min-api：快照为空且不抛（不静默降级成默认声明）。
        assertTrue(d.settings.isEmpty())
        assertEquals(0, d.minApi)
    }

    @Test
    fun `03 RT2MANIFEST-5 旧档 settings 声明数与值原样读回`() {
        val legacy = wire(
            magic = "RT2MANIFEST/5",
            meta = listOf(
                str("legacy.settings"),
                str("3.1.0"),
                str("带设置"),
                str("作者名"),
                str(""),
            ),
            settings = listOf(
                "I2",
                "E" + hex("INT"),
                str("legacy.int"),
                str("整数示例"),
                str("默认 30"),
                "I30",
                "Z",
                "Z",
                "E" + hex("ENUM"),
                str("legacy.mode"),
                str("模式"),
                str("二选一"),
                str("a"),
                "I2",
                str("a"),
                str("b"),
            ),
        )
        val d = ManifestCodec.decode(legacy)
        assertEquals(2, d.settings.size)
        val int = d.settings[0] as IntSettingDeclaration
        assertEquals(SettingType.INT, int.type)
        assertEquals("legacy.int", int.key)
        assertEquals("整数示例", int.title)
        assertEquals("默认 30", int.description)
        assertEquals(30L, int.default)
        assertEquals(null, int.min)
        assertEquals(null, int.max)
        val enum = d.settings[1] as EnumSettingDeclaration
        assertEquals(SettingType.ENUM, enum.type)
        assertEquals("legacy.mode", enum.key)
        assertEquals("a", enum.default)
        assertEquals(listOf("a", "b"), enum.candidates)
        // /5 不写 min-api 段（段 20 是 /6 新增）：minApi 缺省 0。
        assertEquals(0, d.minApi)
    }

    @Test
    fun `04 RT2MANIFEST-6 旧档 min-api 读数原样且段表含空 settings`() {
        val legacy = wire(
            magic = "RT2MANIFEST/6",
            meta = listOf(str("legacy.minapi"), str("4.0.0"), str(""), str(""), str("")),
            settings = listOf("I0"),
            minApi = 7,
        )
        val d = ManifestCodec.decode(legacy)
        assertEquals("legacy.minapi", d.packageId)
        assertEquals(7, d.minApi)
        assertTrue(d.settings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 手写线格式 helper（只做常量拼接，不调用 encode）
    // ------------------------------------------------------------------

    /** 18 段骨架里除 meta 外的空段体（顺序 = 写侧 [ManifestCodec] 段表；artifact-sha256 为单串）。 */
    private fun emptySections(): List<Pair<String, String>> = listOf(
        "requires" to "I0",
        "load-after" to "I0",
        "external-calls" to "I0",
        "override-functions" to "I0",
        "global-dims" to "I0",
        "private-dims" to "I0",
        "csv-patches" to "I0",
        "csv-value-patches" to "I0",
        "entity-refs" to "I0",
        "component-schemas" to "I0",
        "entity-archetypes" to "I0",
        "relationship-schemas" to "I0",
        "hooks" to "I0",
        "interaction-hooks" to "I0",
        "phase-jobs" to "I0",
        "exported-macros" to "I0",
        "artifact-sha256" to "S",
    )

    private fun wire(
        magic: String,
        meta: List<String>,
        settings: List<String>? = null,
        minApi: Int? = null,
    ): ByteArray {
        val lines = mutableListOf(magic, "@meta")
        lines += meta
        lines += "/"
        for ((name, body) in emptySections()) {
            lines += "@$name"
            lines += body
            lines += "/"
        }
        if (settings != null) {
            lines += "@settings"
            lines += settings
            lines += "/"
        }
        if (minApi != null) {
            lines += "@min-api"
            lines += "I$minApi"
            lines += "/"
        }
        return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun str(value: String): String = "S" + hex(value)

    private fun hex(value: String): String =
        value.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
