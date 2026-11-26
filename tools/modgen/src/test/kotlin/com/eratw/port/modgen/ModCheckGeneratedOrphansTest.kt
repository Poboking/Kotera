package com.eratw.port.modgen

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `check-mod` 生成物反方向：`src/generated` 里多出来的文件报 WARN（schema 删了/生成物写错目录）。 */
class ModCheckGeneratedOrphansTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generated file without schema is reported as warning`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        orphanFile(module, "Orphan.kt").writeText("object OrphanKeys\n")
        val findings = findingsOf(module)
        assertNoError(findings)
        val warning = findings.firstOrNull { it.severity == Severity.WARN && it.message.contains("没有对应的 schema TSV") }
        assertTrue(warning != null, "expected orphan generated warning but got: $findings")
    }

    @Test
    fun `generated file without keys object is reported as warning`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        orphanFile(module, "Broken.kt").writeText("object Broken\n")
        val findings = findingsOf(module)
        assertNoError(findings)
        val warning = findings.firstOrNull {
            it.severity == Severity.WARN && it.message.contains("没有 object <Base>Keys")
        }
        assertTrue(warning != null, "expected unparsable generated warning but got: $findings")
    }

    private fun orphanFile(module: Path, fileName: String): Path =
        module.resolve("src/generated/kotlin/eratw/mymod/generated/$fileName")
}
