package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/**
 * `check-mod` 的音频播放声明面（v11 `AudioApi`）：源码里 `audio.play("...")` 的名字必须能在
 * `<packageId>.assets/` 里找到。
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析（宁可漏报，不许假红）：认 `play("...")` 的字符串字面量
 * （`context.audio.play(` 与别名 `audio.play(` 都认，别的 API 的 `play` 不认）；实参是变量 / 拼接 /
 * 插值时抽不到 ⇒ 跳过、不报。`stop()` 没有资源名，不在本面。若 `AudioApi` 侧改名而这里没跟上，
 * `ModCheckAudioTest.playNameStaysInSyncWithAudioApiSource` 的文本哨兵会红。
 *
 * 严重度（与 [ModCheckImages] 同口径）：
 * - 名字非法（与运行期资源面同口径，复用 [isLegalResourceName]）= ERROR —— 非法名连 `false` 都
 *   拿不到稳定语义，作者自检应当直接失败；
 * - 名字合法但资源目录下没有这个文件 = WARN —— 宿主对缺文件 / 坏容器只回 `false` 并落 WARN，
 *   不抛、不砖游戏（见 `com.eratw.port.modapi.AudioApi` 的降级纪律），缺文件不该让自检失败；
 * - 源码里没有播放声明、或抽不到包身份（无 `PACKAGE_ID` 字面量）= 什么都不报。
 *
 * 多包模块：一处声明只要命中**任一**包资源目录就算存在，避免「声明写在对端包」的误红。
 */
internal object ModCheckAudio {

    private const val FIRST_GROUP = 1

    /**
     * `context.audio.play("audio/theme.ogg", ...)` / 别名 `audio.play("...")` 的首个字符串实参。
     *
     * 尾部要求紧跟 `,` 或 `)`：字面量后面还接 `+`（字符串拼接）就不算一整段资源名，直接漏过；
     * 字符集里排除 `$`：`"audio/${name}.ogg"` 这种插值抽不出确定的资源名，也漏过。
     */
    private val AUDIO_PLAY_CALL = Regex("""\baudio\s*\.\s*play\s*\(\s*"([^"\\$]*)"\s*[,)]""")

    /** 逐个源文件抽播放声明，对着 `<packageId>.assets/` 判名字合法性与文件存在性。 */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val packageIds = ModCheckResources.packageIds(sources)
        if (packageIds.isEmpty()) return
        sources.forEach { source ->
            source.code.forEach { line ->
                AUDIO_PLAY_CALL.findAll(line.text).forEach { match ->
                    val declaration = Declaration(match.groupValues[FIRST_GROUP], source.label, line.number)
                    checkDeclaration(root, packageIds, declaration, findings)
                }
            }
        }
    }

    private fun checkDeclaration(
        root: Path,
        packageIds: List<String>,
        declaration: Declaration,
        findings: MutableList<Finding>,
    ) {
        val name = declaration.resourceName
        if (!isLegalResourceName(name)) {
            findings += illegalName(declaration)
        } else if (packageIds.none { packageId -> exists(root, packageId, name) }) {
            findings += missingFile(declaration, packageIds)
        }
    }

    private fun illegalName(declaration: Declaration): Finding = Finding(
        severity = Severity.ERROR,
        file = declaration.file,
        line = declaration.line,
        message = "音频资源名非法：[${declaration.resourceName}]（只许 / 分隔的相对路径；" +
            "禁前导/尾随斜杠、空段、点段与 .. 段、反斜杠、控制字符与盘符冒号）",
    )

    private fun missingFile(declaration: Declaration, packageIds: List<String>): Finding {
        val expected = packageIds.joinToString("、") { packageId ->
            packageId + ModResourceLimits.ASSET_DIR_SUFFIX + "/"
        }
        return Finding(
            severity = Severity.WARN,
            file = declaration.file,
            line = declaration.line,
            message = "音频资源不存在：${declaration.resourceName}（$expected 下没有该文件；" +
                "缺文件/坏容器时 play() 只回 false 并落 WARN，故只 WARN；" +
                "把文件放进 $expected 或修正声明里的资源名）",
        )
    }

    /** 与图片面同一条资源根口径：`mods/rt2/<packageId>.assets/` 的源树等价物。 */
    private fun exists(root: Path, packageId: String, name: String): Boolean =
        Files.exists(root.resolve(packageId + ModResourceLimits.ASSET_DIR_SUFFIX).resolve(name))

    private data class Declaration(val resourceName: String, val file: String, val line: Int)
}
