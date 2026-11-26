package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.parseSchema
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实夹具扩面：`mod-livingworld` 的四份 schema（生成物入库，schema 住在 `:tools:modgen-accessors`）。
 *
 * 单份 schema 的用例只钉「本 schema 的切片」：字段键核对是**模块级并集**（[ModCheckSchemas.checkKeys]
 * 的口径是「多 schema 共享一张全局字段表」），只传一份时其余三份的键会被判「未在任何 schema 中声明」。
 * 那是工具口径，不是 livingworld 三面不一致；模块级「0 ERROR」由四份一起传的用例钉死。
 */
class ModCheckLivingWorldTest {

    private fun schemaPath(base: String): Path =
        repoRoot().resolve("tools/modgen-accessors/schemas/$base.schema.tsv")

    private fun moduleDir(): Path = repoRoot().resolve("mod-livingworld")

    @Test
    fun `four schemas together report no findings`() {
        val schemas = listOf("character", "relationship", "lwmemory", "event").map(::schemaPath)
        // 组合口径是这个模块的真实用法：四份 schema 齐传时三面必须零发现（连 WARN 都不许有，
        // 否则就是真孤儿生成物或别的不自洽）。
        assertEquals(emptyList(), findingsOf(moduleDir(), schemas))
    }

    @Test
    fun `character schema slice is in sync`() = assertSchemaSlice("character")

    @Test
    fun `relationship schema slice is in sync`() = assertSchemaSlice("relationship")

    @Test
    fun `lwmemory schema slice is in sync`() = assertSchemaSlice("lwmemory")

    @Test
    fun `event schema slice is in sync`() = assertSchemaSlice("event")

    /**
     * 单份 schema 的定向断言：本 schema 的生成物/声明面必须零发现；剩余发现只允许是**跨 schema
     * 提示**——其余生成物的字段键提示（见类 KDoc）或它们的孤儿提示（没传 schema 对号）。生成物
     * 过期/缺失、组件/原型未声明、载体身份不一致都会让本用例红，所以「只传一份 schema」也不会把
     * 真实不一致混进预期噪声里。
     */
    private fun assertSchemaSlice(base: String) {
        val schema = schemaPath(base)
        val generated = parseSchema(schema.readText()).baseName + ".kt"
        val findings = findingsOf(moduleDir(), listOf(schema))
        val isOwn: (Finding) -> Boolean = { finding ->
            finding.file.endsWith("generated/$generated") || finding.file.endsWith("schemas/$base.schema.tsv")
        }
        assertEquals(emptyList(), findings.filter(isOwn), "$base: 本 schema 切片必须零发现")
        val crossSchema = findings.filterNot(isOwn)
        assertTrue(
            crossSchema.all { it.isCrossSchemaHint() },
            "$base: 除跨 schema 提示外不许有别的发现：$crossSchema",
        )
    }

    private fun Finding.isCrossSchemaHint(): Boolean =
        message.contains("未在任何 schema 中声明") || message.contains("生成物没有对应的 schema TSV")
}
