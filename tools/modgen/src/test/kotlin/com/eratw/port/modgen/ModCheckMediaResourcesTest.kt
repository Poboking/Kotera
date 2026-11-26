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
 * `check-mod` 旁挂资源树面（[ModCheckMediaResources]，ADR-014 S6）。
 *
 * 判据同源声明：本文件断言的白名单/限额**不写字面量**，一律从 [PackModResources] /
 * [ModResourceLimits] 的真源常量或集合推导（如 [PackModResources.IMAGE_TEXT_EXTENSIONS]），
 * 因此「check 与 pack 两头漂移」会被这些用例同时钉住；打包端自身的同源用例见
 * `PackModResourcesTest`。
 */
internal class ModCheckMediaResourcesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path = scaffoldModule(tempDir).also { writeGenerated(it) }

    private fun mediaFindings(module: Path, declared: String? = null): List<Finding> =
        ModCheck.check(module.toFile(), emptyList(), declared)

    private fun writeTreeFile(
        module: Path,
        rootName: String,
        dir: String,
        relative: String,
        bytes: Int = TINY_BYTES,
    ): Path {
        val target = module.resolve(rootName).resolve(PackModResources.MOD_SEGMENT).resolve(dir).resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, ByteArray(bytes))
        return target
    }

    private fun makeTreeDir(module: Path, rootName: String, dir: String) {
        Files.createDirectories(module.resolve(rootName).resolve(PackModResources.MOD_SEGMENT).resolve(dir))
    }

    private fun assertHasWarning(findings: List<Finding>, fragment: String) {
        assertTrue(
            findings.any { it.severity == Severity.WARN && it.message.contains(fragment) },
            "expected a WARN containing '$fragment' but got: ${findings.joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun `clean declared sidecar trees produce no findings`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "ui/cat.png")
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "readme.txt")
        writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "bgm/theme.mp3")
        assertEquals(emptyList(), mediaFindings(module, "demo"))
    }

    @Test
    fun `undeclared trees warn but do not error`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        val findings = mediaFindings(module)
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "没给 --resources")
        assertHasWarning(findings, "不会写包元数据 resources 字段")
    }

    @Test
    fun `declared dir without either tree is error naming both trees`() {
        val module = module()
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "都不存在")
        val message = errorsOf(findings).single().message
        assertTrue(message.contains("resources/mod/demo/"), "message should name the image tree: $message")
        assertTrue(message.contains("sound/mod/demo/"), "message should name the audio tree: $message")
        assertTrue(message.contains("静默丢失"), "message should name the consequence: $message")
    }

    @Test
    fun `empty declared tree is error`() {
        val module = module()
        makeTreeDir(module, PackModResources.RESOURCES_ROOT, "demo")
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "存在但一个文件都没有")
    }

    @Test
    fun `csv inside resources tree is error with the shared whitelist`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "table.csv")
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "不在白名单")
        // 白名单文本必须来自 pack/导入端同一集合（这里用真源推导，不写字面量）。
        val whitelist = PackModResources.IMAGE_TEXT_EXTENSIONS.sorted().joinToString("/")
        assertHasError(findings, whitelist)
    }

    @Test
    fun `flac inside sound tree is error with the shared whitelist`() {
        val module = module()
        writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "bgm/track.flac")
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "flac")
        val whitelist = PackModResources.AUDIO_EXTENSIONS.sorted().joinToString("/")
        assertHasError(findings, whitelist)
    }

    @Test
    fun `image file over the single file limit is error`() {
        val module = module()
        val over = (ModResourceLimits.MAX_RESOURCE_FILE_BYTES + ONE_BYTE).toInt()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "huge.png", over)
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "上限被超过")
    }

    @Test
    fun `image file near the single file limit warns without error`() {
        val module = module()
        val near = (ModResourceLimits.MAX_RESOURCE_FILE_BYTES * NEAR_PERCENT / PERCENT).toInt()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "big.png", near)
        val findings = mediaFindings(module, "demo")
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "接近单文件上限")
    }

    @Test
    fun `tree over the package limit is error`() {
        val module = module()
        val atLimit = ModResourceLimits.MAX_RESOURCE_FILE_BYTES.toInt()
        repeat(OVER_TREE_FILES) { index ->
            writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "big-$index.png", atLimit)
        }
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "合计超限")
    }

    @Test
    fun `tree near the package limit warns without error`() {
        val module = module()
        val near = (ModResourceLimits.MAX_RESOURCE_FILE_BYTES * NEAR_PERCENT / PERCENT).toInt()
        repeat(NEAR_TREE_FILES) { index ->
            writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "near-$index.png", near)
        }
        val findings = mediaFindings(module, "demo")
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "接近单包上限")
    }

    @Test
    fun `combined two tree budget is previewed by warn`() {
        // 两树各自贴边上限（16 + 24 MiB）但都没超，合计 40 MiB > 48 MiB 的 80%，故只 WARN。
        val module = module()
        val imageCap = ModResourceLimits.MAX_RESOURCE_FILE_BYTES.toInt()
        repeat(CAP_FILES) { index ->
            writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "cap-$index.png", imageCap)
        }
        val audioCap = PackModResources.MAX_AUDIO_FILE_BYTES.toInt()
        repeat(AUDIO_FILES) { index ->
            writeTreeFile(module, PackModResources.SOUND_ROOT, "demo", "cap-$index.mp3", audioCap)
        }
        val findings = mediaFindings(module, "demo")
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "两棵资源树合计接近上限")
    }

    @Test
    fun `declared dir versus actual dir names both sides`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "legacy", "a.png")
        val findings = mediaFindings(module, "demo")
        assertHasError(findings, "两侧不一致")
        val message = errorsOf(findings).single().message
        assertTrue(message.contains("「demo」"), "message should quote the declared value: $message")
        assertTrue(message.contains("「legacy」"), "message should quote the actual value: $message")
        assertTrue(message.contains("静默丢失"), "message should name the consequence: $message")
    }

    @Test
    fun `non ascii actual dir is error`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, NON_ASCII_DIR, "a.png")
        val findings = mediaFindings(module)
        assertHasError(findings, "不合法")
        assertHasError(findings, "ASCII")
    }

    @Test
    fun `illegal declared dir is error`() {
        val module = module()
        val findings = mediaFindings(module, NON_ASCII_DIR)
        assertHasError(findings, "包元数据 resources 声明")
        assertHasError(findings, "ASCII")
    }

    @Test
    fun `extra dir beside declared one warns`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "a.png")
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "legacy", "b.png")
        val findings = mediaFindings(module, "demo")
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "不会进包")
    }

    @Test
    fun `disjoint tree dir names warn about the one that is dropped`() {
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "images", "a.png")
        writeTreeFile(module, PackModResources.SOUND_ROOT, "audio", "b.mp3")
        val findings = mediaFindings(module)
        assertEquals(emptyList(), errorsOf(findings))
        assertHasWarning(findings, "目录名不一致")
    }

    @Test
    fun `check cli accepts resources option and exits one on error`() {
        val module = module()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = ModCheck.runCli(
            arrayOf(module.toString(), "--resources", "demo"),
            PrintStream(out),
            PrintStream(err),
        )
        assertEquals(1, exit)
        assertTrue(out.toString().contains("都不存在"), "stdout should carry the finding: ${out}")
        assertTrue(err.toString().isEmpty(), "usage errors should not leak into stderr: ${err}")
    }

    @Test
    fun `check cli rejects a repeated resources option with usage exit`() {
        val module = module()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = ModCheck.runCli(
            arrayOf(module.toString(), "--resources", "a", "--resources", "b"),
            PrintStream(out),
            PrintStream(err),
        )
        assertEquals(2, exit)
        assertTrue(err.toString().contains("只能给一次"), "stderr should explain the duplicate: ${err}")
    }

    @Test
    fun `pack side does not inherit media findings`() {
        // pack-mod 走 PackModResources 自己的 PackUnsafe 通道（既有文案/退出码合同），
        // 故 includeMediaResources = false 时同一棵违规树不应产出本面 Finding。
        val module = module()
        writeTreeFile(module, PackModResources.RESOURCES_ROOT, "demo", "table.csv")
        assertHasError(mediaFindings(module, "demo"), "不在白名单")
        val packSide = ModCheck.check(module.toFile(), emptyList(), "demo", includeMediaResources = false)
        assertEquals(emptyList(), packSide)
    }

    private companion object {
        const val TINY_BYTES = 8
        const val ONE_BYTE = 1L
        const val NEAR_PERCENT = 85L
        const val PERCENT = 100L
        const val OVER_TREE_FILES = 5
        const val NEAR_TREE_FILES = 4
        const val CAP_FILES = 4
        const val AUDIO_FILES = 3
        const val NON_ASCII_DIR = "我的图"
    }
}
