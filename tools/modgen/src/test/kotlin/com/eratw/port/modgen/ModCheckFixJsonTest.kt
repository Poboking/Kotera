package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `--fix` / `--dry-run` 的机器可读契约：普通 `--json` 仍是既有五键形状；只有修复模式在
 * findings 之后追加 `fixes[]`（字段序 file/status/detail）。另含原子写失败回滚的注入测试。
 */
class ModCheckFixJsonTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fix json appends a fixes array after the frozen findings shape`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        makeGeneratedStale(module)
        val run = runCheckFix(listOf(module.toString(), "--fix", "--json"))
        assertEquals(0, run.exit, "stdout=${run.out} stderr=${run.err}")
        val json = parseContractJson(run.out)
        assertEquals(listOf("schema", "ok", "errors", "warnings", "findings", "fixes"), json.keys())
        assertEquals(0, json.int("errors"))
        assertTrue(json.bool("ok"))
        assertTrue(json.objects("findings").isEmpty(), "stdout=${run.out}")
        val fix = json.objects("fixes").single()
        assertEquals(listOf("file", "status", "detail"), fix.keys())
        assertEquals("FIXED", fix.string("status"))
        assertEquals("src/generated/kotlin/eratw/mymod/generated/My.kt", fix.string("file"))
        assertTrue(fix.raw("detail") is ContractJsonNull)
    }

    @Test
    fun `plain json without fix mode keeps the five key shape`() {
        val module = healthyContractModule(tempDir)
        val run = runCheckFix(listOf(module.toString(), "--json"))
        assertEquals(0, run.exit, "stdout=${run.out}")
        val json = parseContractJson(run.out)
        assertEquals(listOf("schema", "ok", "errors", "warnings", "findings"), json.keys())
    }

    @Test
    fun `fix reports exit one when non fixable errors remain`() {
        val module = healthyContractModule(tempDir)
        makeGeneratedStale(module)
        insertContractMembers(module, "override val apiVersion: Int = ${ModApi.VERSION + 1}")
        val run = runCheckFix(listOf(module.toString(), "--fix", "--json"))
        assertEquals(1, run.exit, "stdout=${run.out}")
        val json = parseContractJson(run.out)
        assertTrue(json.int("errors") >= 1, "stdout=${run.out}")
        assertEquals("FIXED", json.objects("fixes").single().string("status"))
        assertEquals(expectedGenerated(module), Files.readString(generatedFile(module)))
    }

    @Test
    fun `rename failure rolls back the generated file`() {
        val module = healthyContractModule(tempDir)
        makeGeneratedStale(module)
        val plan = ModCheckFix.plan(module, emptyList())
        assertEquals(1, plan.size, "plan=$plan")
        val before = Files.readAllBytes(generatedFile(module))
        val results = ModCheckFix.apply(plan, FailingMoveOps())
        assertEquals(FixStatus.FAILED, results.single().status)
        assertTrue(before.contentEquals(Files.readAllBytes(generatedFile(module))), "目标文件必须保持原样")
        assertFalse(Files.exists(generatedFile(module).resolveSibling("My.kt.tmp")), "临时文件应被清理")
    }
}

/** 注入「改名失败」，验证原子写失败时目标文件一个字节都不动、临时文件被清掉。 */
private class FailingMoveOps : FixFileOps {
    override fun write(path: Path, bytes: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, bytes)
    }

    override fun read(path: Path): ByteArray = Files.readAllBytes(path)

    override fun moveIntoPlace(tmp: Path, target: Path) {
        throw IOException("injected move failure")
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}
