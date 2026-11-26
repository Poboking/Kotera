package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/**
 * `check-mod` 的图片元素声明面：作者在源码里声明的浮层图片，必须能在 `<packageId>.assets/` 里找到。
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析（宁可漏报，不许假红）：认两种字面量写法 ——
 * `modUiImage("...")` 与 `ModUiElement.Image(resourceName = "...")`；实参不是字符串字面量
 * （变量、拼接、跨行实参）就跳过、不报。两种写法与参数名的真源在
 * `mod-api/src/main/kotlin/com/eratw/port/modapi/ModUiElement.kt`；若生产侧改名而这里没跟上，
 * `ModCheckImagesTest.dslNamesStayInSyncWithModApiSource` 的文本哨兵会红。
 *
 * 严重度：
 * - 名字非法（与运行期 `ModResourceStore` 同口径，直接复用 [isLegalResourceName]）= ERROR；
 * - 名字合法但资源目录下没有这个文件 = WARN —— 宿主按 `altText` / 占位框降级是设计允许的，
 *   缺文件不该让作者自检直接失败；
 * - 源码里没有图片声明、或抽不到包身份（无 `PACKAGE_ID` 字面量）= 什么都不报。
 *
 * 多包模块：一处声明只要命中**任一**包资源目录就算存在，避免「声明写在对端包」的误红。
 */
internal object ModCheckImages {

    private const val FIRST_GROUP = 1

    /**
     * `modUiImage("images/bg.png", ...)` 的首个字符串实参。
     *
     * 字符集排除 `$`（插值抽不出确定的资源名），收尾后紧跟可选空白与 `+` 的一律不算完整实参
     * （`"a" + name` 拼接截不出整段资源名，截到的片段冒充作者声明去报非法名/缺文件就是假红）——
     * 与 [ModCheckAudio.AUDIO_PLAY_CALL] 同一条「拼接/插值整段漏过」口径。
     */
    private val MOD_UI_IMAGE_CALL = Regex("""\bmodUiImage\s*\(\s*"([^"\\$]*)"(?!\s*\+)""")

    /** `ModUiElement.Image(resourceName = "images/bg.png", ...)` 的具名实参；守卫同 [MOD_UI_IMAGE_CALL]。 */
    private val MOD_UI_IMAGE_ELEMENT =
        Regex("""\bModUiElement\s*\.\s*Image\s*\(\s*resourceName\s*=\s*"([^"\\$]*)"(?!\s*\+)""")

    /** 逐个源文件抽图片声明，对着 `<packageId>.assets/` 判名字合法性与文件存在性。 */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val packageIds = ModCheckResources.packageIds(sources)
        if (packageIds.isEmpty()) return
        sources.forEach { source ->
            source.code.forEach { line ->
                declarationsIn(line.text).forEach { name ->
                    checkDeclaration(root, packageIds, Declaration(name, source.label, line.number), findings)
                }
            }
        }
    }

    private fun declarationsIn(text: String): List<String> =
        MOD_UI_IMAGE_CALL.findAll(text).map { it.groupValues[FIRST_GROUP] }.toList() +
            MOD_UI_IMAGE_ELEMENT.findAll(text).map { it.groupValues[FIRST_GROUP] }.toList()

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
        message = "图片资源名非法：[${declaration.resourceName}]（只许 / 分隔的相对路径；" +
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
            message = "图片资源不存在：${declaration.resourceName}（$expected 下没有该文件；" +
                "缺文件时宿主渲染 altText 或占位框，故只 WARN；" +
                "把文件放进 $expected 或修正声明里的资源名）",
        )
    }

    private fun exists(root: Path, packageId: String, name: String): Boolean =
        Files.exists(root.resolve(packageId + ModResourceLimits.ASSET_DIR_SUFFIX).resolve(name))

    private data class Declaration(val resourceName: String, val file: String, val line: Int)
}
