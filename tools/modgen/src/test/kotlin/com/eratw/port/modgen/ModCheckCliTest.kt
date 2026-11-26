package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CLI 契约：`modgen check-mod` 的退出码语义（0 = 无 ERROR，1 = 有 ERROR，2 = 用法/IO 问题）
 * 与末尾 summary 行。KDoc 与 USAGE 里写的就是这三档，这里逐档钉住。
 */
class ModCheckCliTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `usage error exits with two`() {
        val run = runCheck()
        assertEquals(2, run.exit)
        assertTrue(run.err.contains("missing <mod dir>"), "stderr=${run.err}")
    }

    @Test
    fun `option value starting with dashes is a usage error`() {
        // `--schema` 丢了实参、后面跟的是下一个选项：必须报用法错误，不许把选项名吞成路径
        // （吞掉会让真正的 --json 静默失效，输出模式悄悄改变）。
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val run = runCheck(module.toString(), "--schema", "--json")
        assertEquals(2, run.exit, "stdout=${run.out} stderr=${run.err}")
        assertTrue(run.err.contains("--schema needs a path"), "stderr=${run.err}")
    }

    @Test
    fun `healthy module exits with zero`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val run = runCheck(module.toString())
        assertEquals(0, run.exit, "stdout=${run.out}")
        assertTrue(run.out.contains("check-mod: 0 error(s)"), "stdout=${run.out}")
    }

    @Test
    fun `module with error exits with one`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(generatedFile(module))
        val run = runCheck(module.toString())
        assertEquals(1, run.exit)
        assertTrue(run.out.contains("check-mod: 1 error(s)"), "stdout=${run.out}")
    }
}

private class CliRun(val exit: Int, val out: String, val err: String)

private fun runCheck(vararg args: String): CliRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = PrintStream(out, true, "UTF-8").use { outStream ->
        PrintStream(err, true, "UTF-8").use { errStream ->
            ModCheck.runCli(arrayOf(*args), outStream, errStream)
        }
    }
    return CliRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
}
