package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `modgen check-mod` 的三面一致性：生成物同步、字段键双向、载体身份、schema 名字覆盖。 */
class ModCheckTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `healthy scaffold module has no findings`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        assertEquals(emptyList(), findingsOf(module))
        val out = ByteArrayOutputStream()
        val exit = ModCheck.runCli(arrayOf(module.toString()), PrintStream(out), PrintStream(System.err))
        assertEquals(0, exit)
        assertTrue(out.toString().contains("0 error(s)"))
    }

    @Test
    fun `stale generated file is reported`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val generated = generatedFile(module)
        generated.writeText(generated.readText() + "// drift\n")
        assertHasError(findingsOf(module), "生成物过期")
    }

    @Test
    fun `schema field without key is reported`() {
        val module = scaffoldModule(tempDir)
        val nickname = "        val NICKNAME: FieldKey<String> = strKey(\"eratw.mymod.nickname\")\n"
        replaceOnce(declarationsFile(module), nickname, "")
        assertHasError(findingsOf(module), "缺少 intKey/strKey/refKey")
    }

    @Test
    fun `extra key not in schema is reported`() {
        val module = scaffoldModule(tempDir)
        replaceOnce(
            declarationsFile(module),
            "    object Keys {",
            "    object Keys {\n        val GHOST: FieldKey<Long> = intKey(\"eratw.mymod.ghost\")",
        )
        assertHasError(findingsOf(module), "未在任何 schema 中声明")
    }

    @Test
    fun `packageId mismatch is reported`() {
        val module = scaffoldModule(tempDir)
        replaceOnce(declarationsFile(module), "get() = PACKAGE_ID", "get() = \"eratw.other\"")
        assertHasError(findingsOf(module), "packageId 与 PACKAGE_ID 不一致")
    }

    @Test
    fun `undeclared archetype is reported`() {
        val module = scaffoldModule(tempDir)
        replaceOnce(declarationsFile(module), "        archetype(Keys.ARCHETYPE) {\n", "")
        assertHasError(findingsOf(module), "未在 Declarations 的 archetype")
    }

    @Test
    fun `self referential key alias resolves to null instead of stack overflow`() {
        val module = scaffoldModule(tempDir)
        // 别名环文本（作者写一半的非法 Kotlin）：resolve 必须返回 null 静默跳过，
        // 不许栈溢出把整个 check-mod 进程炸掉（坏输入要落在可读诊断上）。
        replaceOnce(
            declarationsFile(module),
            "    object Keys {",
            "    object Keys {\n        val LOOP: ArchetypeKey = LOOP",
        )
        val symbols = ModCheckSymbols.build(ModCheckSources.scan(module, mutableListOf()))
        assertEquals(null, symbols.resolve("LOOP"))
    }

    @Test
    fun `blank manifest version is reported`() {
        val module = scaffoldModule(tempDir)
        replaceOnce(declarationsFile(module), "const val VERSION: String = \"0.1\"", "const val VERSION: String = \"\"")
        assertHasError(findingsOf(module), "manifest.version 为空")
    }

    @Test
    fun `broken schema tsv is reported with line number`() {
        val module = scaffoldModule(tempDir)
        schemaFile(module).writeText("schema\teratw.mymod.generated\tMy\nfield\teratw.mymod.level\tINT\tfalse\n")
        assertHasError(findingsOf(module), "line 2")
    }

    @Test
    fun `real mod-family module is consistent`() {
        val root = repoRoot()
        val schema = root.resolve("tools/modgen-accessors/src/test/resources/family.schema.tsv")
        assertNoError(findingsOf(root.resolve("mod-family"), listOf(schema)))
    }

    @Test
    fun `real mod-memory module is consistent`() {
        val root = repoRoot()
        val schema = root.resolve("tools/modgen-accessors/schemas/memory.schema.tsv")
        assertNoError(findingsOf(root.resolve("mod-memory"), listOf(schema)))
    }
}
