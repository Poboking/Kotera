package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `check-mod` 的 API/数据版本一致性：apiVersion 超前、dataVersion 非法/缺迁移回调、
 * 缺省数据版本却写了 onMigrate。夹具复用 new-mod 骨架（其余三面天然干净），只往
 * `MyMod` 类体里插版本覆写。
 */
class ModCheckVersionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun healthyModule(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    private fun modFile(module: Path): Path = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt")

    /** 在 `class MyMod : EraMod {` 后插入成员（每个元素一行，统一缩进 4 空格）。 */
    private fun insertMembers(module: Path, vararg members: String) {
        val block = members.joinToString(separator = "\n", postfix = "\n") { "    $it" }
        replaceOnce(modFile(module), "class MyMod : EraMod {\n", "class MyMod : EraMod {\n$block")
    }

    private fun lineOf(module: Path, fragment: String): Int {
        val index = modFile(module).readLines().indexOfFirst { it.contains(fragment) }
        assertTrue(index >= 0, "fragment not found in ${modFile(module)}: $fragment")
        return index + 1
    }

    @Test
    fun `apiVersion newer than engine is an error naming file and line`() {
        val module = healthyModule()
        insertMembers(module, "override val apiVersion: Int = ${ModApi.VERSION + 1}")
        val error = errorsOf(findingsOf(module)).single { it.message.contains("apiVersion") }
        assertEquals("src/main/kotlin/eratw/mymod/MyMod.kt", error.file)
        assertEquals(lineOf(module, "override val apiVersion"), error.line)
        assertTrue(error.message.contains("比当前引擎新"), "unexpected message: ${error.message}")
    }

    @Test
    fun `apiVersion equal to engine is clean`() {
        val module = healthyModule()
        insertMembers(module, "override val apiVersion: Int = ${ModApi.VERSION}")
        assertEquals(emptyList(), findingsOf(module))
    }

    @Test
    fun `apiVersion declared with multi-line getter is still checked`() {
        val module = healthyModule()
        insertMembers(module, "override val apiVersion: Int", "    get() = ${ModApi.VERSION + 1}")
        assertHasError(findingsOf(module), "比当前引擎新")
    }

    @Test
    fun `negative dataVersion is an error`() {
        val module = healthyModule()
        insertMembers(module, "override val dataVersion: Int = -1")
        assertHasError(findingsOf(module), "dataVersion=-1 非法")
    }

    @Test
    fun `positive dataVersion without onMigrate is an error`() {
        val module = healthyModule()
        insertMembers(module, "override val dataVersion: Int = 2")
        assertHasError(findingsOf(module), "却没有 override fun onMigrate")
    }

    @Test
    fun `positive dataVersion with onMigrate is clean`() {
        val module = healthyModule()
        insertMembers(
            module,
            "override val dataVersion: Int = 2",
            "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}",
        )
        assertEquals(emptyList(), findingsOf(module))
    }

    @Test
    fun `onMigrate with default dataVersion warns and keeps exit code 0`() {
        val module = healthyModule()
        insertMembers(module, "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}")
        val warning = findingsOf(module).single { it.severity == Severity.WARN }
        assertEquals("src/main/kotlin/eratw/mymod/MyMod.kt", warning.file)
        assertEquals(lineOf(module, "override fun onMigrate"), warning.line)
        assertTrue(warning.message.contains("dataVersion 仍是缺省 0"), "unexpected message: ${warning.message}")
        val out = ByteArrayOutputStream()
        val exit = PrintStream(out, true, "UTF-8").use { stream ->
            ModCheck.runCli(arrayOf(module.toString()), stream, PrintStream(System.err))
        }
        assertEquals(0, exit)
        assertTrue(out.toString("UTF-8").contains("0 error(s), 1 warning(s)"))
    }
}
