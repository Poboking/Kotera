package com.eratw.port.modgen.accessors

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `:tools:modgen-accessors` 的 CLI 契约：写盘 / `--check` 逐字节比对 / 错误不落盘。
 *
 * 生成物已入库，`--check` 闸的语义是「一个字节不同就 STALE、报首差行号、且绝不回写」；
 * 本地写入路径则相反（父目录按需创建、mtime 会变）。两套语义分开钉，别混。
 */
class AccessorEmitCliTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun cliWritesChecksReportsChangedLineAndChecksRestoredBytesWithoutTouchingMtime() {
        val input = writeSchema()
        val output = temp.resolve("nested/generated/Family.kt")
        val expected = AccessorEmit.emit(parseSchema(resource("family.schema.tsv"))).toByteArray(Charsets.UTF_8)
        val written = cli(input.toString(), output.toString())
        assertEquals(0, written.code, written.err)
        assertContains(written.out, "WROTE:")
        assertEquals("", written.err)
        assertContentEquals(expected, Files.readAllBytes(output))
        assertCheckReadOnly(input, output, 0, "OK:")

        // 2026-11-16 改造：旧的 `fun setTitle(` 锚点已不存在，改锚在 View 的属性声明上。
        // 文件含非 ASCII，字节偏移必须按 UTF-8 字节算（用字符下标当字节下标会落到别处）。
        val source = expected.toString(Charsets.UTF_8)
        val anchor = "var title: String"
        val offset = source.substringBefore(anchor).toByteArray(Charsets.UTF_8).size
        assertTrue(offset > 0 && source.indexOf(anchor) >= 0)
        val changed = expected.copyOf().also { it[offset] = 'x'.code.toByte() }
        Files.write(output, changed)
        val stale = assertCheckReadOnly(input, output, 1, "STALE:")
        val line = source.lineSequence().indexOfFirst { anchor in it } + 1
        assertContains(stale.err, "first diff at line $line")
        Files.write(output, expected)
        assertCheckReadOnly(input, output, 0, "OK:")
    }

    @Test
    fun cliCheckOfMissingArtifactDoesNotCreateDirectories() {
        val input = writeSchema()
        val output = temp.resolve("not-created/deep/Family.kt")
        val result = cli("--check", input.toString(), output.toString())
        assertEquals(1, result.code, result.err)
        assertContains(result.err, "MISSING:")
        assertContains(result.err, "first diff at line 1")
        assertEquals("", result.out)
        assertFalse(Files.exists(output.parent.parent))
        assertFalse(Files.exists(output))
    }

    @Test
    fun cliRejectsCrlfArtifactsAndMissingFinalNewlineWithoutRewriting() {
        val input = writeSchema()
        val output = temp.resolve("Family.kt")
        val source = AccessorEmit.emit(parseSchema(resource("family.schema.tsv")))
        for (noncanonical in listOf(source.replace("\n", "\r\n"), source.removeSuffix("\n"))) {
            Files.writeString(output, noncanonical, Charsets.UTF_8)
            assertCheckReadOnly(input, output, 1, "STALE:")
        }
        // CRLF is accepted for the input, but the generated output must still be canonical LF.
        val crlfInput = resource("family.schema.tsv").replace("\r\n", "\n").replace("\n", "\r\n")
        Files.writeString(input, crlfInput, Charsets.UTF_8)
        assertEquals(0, cli(input.toString(), output.toString()).code)
        assertContentEquals(source.toByteArray(Charsets.UTF_8), Files.readAllBytes(output))
        assertCheckReadOnly(input, output, 0, "OK:")
    }

    @Test
    fun invalidSchemasNeverOverwriteExistingOutputOrCreateMissingParents() {
        val input = writeSchema()
        val output = temp.resolve("preserved.kt")
        val bytes = "keep this output\r\n".toByteArray(Charsets.UTF_8)
        Files.write(output, bytes)
        val header = "schema\texample.generated\tExample\tfamily\n"
        for (invalid in listOf(header + "field\tage\tINT\tFalse", header + "field\tfather\tSTR\ttrue",
            header + "field\tage\tINT\tfalse\nfield\tAGE\tINT\tfalse", "schema\tbad;package\tExample\tfamily",
            header + "field\tobject\tINT\ttrue\tclass")) {
            Files.writeString(input, invalid, Charsets.UTF_8)
            for (check in listOf(false, true)) {
                val mtime = pinMtime(output)
                val result = cli(*cliArgs(check, input, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "ERROR:")
                assertEquals("", result.out)
                assertUnchanged(output, bytes, mtime)
                val missing = temp.resolve("never-created/Family.kt")
                assertEquals(2, cli(*cliArgs(check, input, missing)).code)
                assertFalse(Files.exists(missing.parent))
            }
        }
    }

    @Test
    fun cliNeverOverwritesSchemaWhenInputAndOutputAreTheSamePath() {
        val input = writeSchema()
        val bytes = Files.readAllBytes(input)
        for (output in listOf(input, temp.resolve("unused/../schema.tsv"))) {
            for (check in listOf(false, true)) {
                val mtime = pinMtime(input)
                val result = cli(*cliArgs(check, input, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "must be different files")
                assertEquals("", result.out)
                assertUnchanged(input, bytes, mtime)
            }
        }
        assertFalse(Files.exists(temp.resolve("unused")))
    }

    @Test
    fun cliArgumentErrorsAreUsageErrorsWithoutCreatingOutput() {
        val input = writeSchema().toString()
        val output = temp.resolve("not-created/Family.kt")
        for (args in listOf(emptyArray<String>(), arrayOf("--check"), arrayOf(input), arrayOf("--check", input),
            arrayOf("--unknown", input, output.toString()), arrayOf(input, output.toString(), "extra"),
            arrayOf(input, "--check"), arrayOf("--check", input, output.toString(), "extra"))) {
            val result = cli(*args)
            assertEquals(2, result.code, result.err)
            assertContains(result.err, "Usage:")
            assertEquals("", result.out)
            assertFalse(Files.exists(output.parent))
        }
    }

    @Test
    fun cliMissingInputAndIoFailuresReturnErrorRatherThanStale() {
        val input = writeSchema()
        val directory = Files.createDirectory(temp.resolve("directory.kt"))
        val missingOutput = temp.resolve("not-created/Family.kt")
        for ((schemaPath, output) in listOf(temp.resolve("missing.tsv") to missingOutput,
            directory to missingOutput, input to directory)) {
            for (check in listOf(false, true)) {
                val result = cli(*cliArgs(check, schemaPath, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "ERROR:")
                assertEquals("", result.out)
                assertFalse(Files.exists(missingOutput.parent))
                assertTrue(Files.isDirectory(directory))
            }
        }
    }

    private fun writeSchema(): Path =
        Files.writeString(temp.resolve("schema.tsv"), resource("family.schema.tsv"), Charsets.UTF_8)

    private data class CliResult(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err -> runCli(arrayOf(*args), out, err) }
        }
        return CliResult(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    private fun cliArgs(check: Boolean, input: Path, output: Path): Array<String> {
        val inputPath = input.toString()
        val outputPath = output.toString()
        return (if (check) listOf("--check", inputPath, outputPath) else listOf(inputPath, outputPath))
            .toTypedArray()
    }

    private fun pinMtime(path: Path): FileTime {
        Files.setLastModifiedTime(path, FileTime.fromMillis(946_684_800_000L))
        return Files.getLastModifiedTime(path)
    }

    private fun assertUnchanged(path: Path, bytes: ByteArray, mtime: FileTime) {
        assertContentEquals(bytes, Files.readAllBytes(path))
        assertEquals(mtime, Files.getLastModifiedTime(path), "Read-only/error path changed the output mtime")
    }

    private fun assertCheckReadOnly(input: Path, output: Path, expectedCode: Int, diagnostic: String): CliResult {
        val bytes = Files.readAllBytes(output)
        val mtime = pinMtime(output)
        val result = cli("--check", input.toString(), output.toString())
        assertEquals(expectedCode, result.code, result.err)
        if (expectedCode == 0) {
            assertContains(result.out, diagnostic)
            assertEquals("", result.err)
        } else {
            assertContains(result.err, diagnostic)
            assertEquals("", result.out)
        }
        assertUnchanged(output, bytes, mtime)
        return result
    }
}
