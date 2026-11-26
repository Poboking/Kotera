package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手写示范模块（`mod-template`）与 `new-mod` 生成骨架的**互相对拍闸**。
 *
 * 治的失效模式：README 教作者的作者面、示范模块真演示的面、生成骨架真调用的面各写各的，
 * 任何一边悄悄落后/超前都只能靠人眼对（task-157）。三段对拍：
 *
 * 1. 作者面集合：从 `ModContext.kt` 源码正则枚举作者面全集（照 app 侧
 *    `ModContextFaceAuditFixture` 的 DH 审计范式），再核 matrix 测试的观测面账与生成骨架的
 *    真调用面账；
 * 2. README：`README.txt` 的能力小节标题必须与 [NewModTemplates.README_CAPABILITY_SECTIONS] 逐字一致；
 * 3. 声明结构：示范 `TemplateDeclarations.kt` 与生成 `<Name>Declarations.kt` 的身份成员 / manifest
 *    DSL 种类 / 参数形状必须同构（值不必相同）。
 *
 * 已知差集冻在 [KNOWN_MATRIX_ONLY] / [KNOWN_SCAFFOLD_ONLY]：现状两侧**未覆盖面并不相同**
 * （见两常量 KDoc），这是本次对拍量出来的真漂移，只登记不补演示 —— 任何一边面增减都会红。
 * task-159 起 `interactions` 已补进骨架（[scaffoldDemosInteractionAndAudioAsRealCalls] 另钉
 * 「真调用不是注释」），`KNOWN_MATRIX_ONLY` 收为空集；`audio` 登记在骨架侧。
 *
 * 声明 DSL 种类另有一份冻结账 [SCAFFOLD_EXTRA_DECLARATION_KINDS]（示范模板暂缺的
 * `interactionHook`）：保持精确集合相等语义，不放宽成子集比较。
 *
 * 输入全按源码文本读（生成物来自 [NewModTemplates.files]，不落盘、不依赖生成任务），
 * 抽不到输入一律 error，宁可红也不静默漏报。
 */
class TemplateScaffoldParityTest {

    @Test
    fun authorFacesMatchModContextSource() {
        assertEquals(
            AUTHOR_FACE_ORDER,
            authorFaces().toList(),
            "ModContext.kt 作者面属性集与冻结账不一致：新增/删除作者面必须同步本账与两侧观测账",
        )
    }

    @Test
    fun matrixTestFaceSetMatchesFrozenLedger() {
        val tokens = contextAccessors(repoSources(TEMPLATE_MATRIX_SOURCE))
        val unknown = tokens - MATRIX_ACCESSOR_FACE.keys
        assertEquals(
            emptySet(),
            unknown,
            "TemplateCapabilityMatrixTest 出现未登记观测器：先补 MATRIX_ACCESSOR_FACE 再补对拍",
        )
        assertEquals(
            emptySet(),
            MATRIX_ACCESSOR_FACE.values.toSet() - AUTHOR_FACE_ORDER.toSet(),
            "观测器映射到了 ModContext 里不存在的作者面",
        )
        val faces = matrixFaces()
        assertEquals(
            MATRIX_FACES,
            faces,
            "matrix 测试覆盖的作者面变了：新增=${faces - MATRIX_FACES} 消失=${MATRIX_FACES - faces}",
        )
    }

    @Test
    fun scaffoldDemoFaceSetMatchesFrozenLedger() {
        val tokens = contextAccessors(scaffoldSource("Mod.kt"))
        val unknown = tokens - SCAFFOLD_ACCESSOR_ALLOWLIST
        assertEquals(
            emptySet(),
            unknown,
            "生成骨架出现未登记 context 访问器：裁决它算不算作者面并同步本账",
        )
        val faces = scaffoldFaces()
        assertEquals(
            SCAFFOLD_FACES,
            faces,
            "生成骨架真调用的作者面变了：新增=${faces - SCAFFOLD_FACES} 消失=${SCAFFOLD_FACES - faces}",
        )
    }

    /**
     * 生成骨架的互动面 / 音频面必须是**真调用**（task-159）：只写注释、只在 README 提不算。
     * 反证口径「删掉生成物的 audio.play ⇒ 本用例红」，与本类其余用例同属冻结账闸。
     */
    @Test
    fun scaffoldDemosInteractionAndAudioAsRealCalls() {
        val mod = scaffoldSource("Mod.kt")
        assertTrue(
            mod.contains("context.interactions.registerHook(InteractionPhase.PRE)"),
            "生成骨架没真注册交互钩子（只写注释不算）",
        )
        assertTrue(mod.contains("context.audio.play("), "生成骨架没真调 audio.play")
        assertTrue(
            mod.contains("Declarations.Keys.THEME_AUDIO"),
            "生成骨架的 audio.play 没走 Keys.THEME_AUDIO 常量（骨架不随包二进制音频，只示范常量引用）",
        )
        assertTrue(mod.contains("context.audio.stop()"), "生成骨架的 onDisable 没真调 audio.stop")
        val declarations = scaffoldSource("Declarations.kt")
        assertTrue(
            declarations.contains("interactionHook(InteractionPhase.PRE)"),
            "生成骨架的 manifest 没声明交互相位槽：运行期 registerHook 会抛「交互阶段钩子未声明」",
        )
        val generatedTest = scaffoldSource("ModTest.kt")
        assertTrue(
            generatedTest.contains("context.interactionsRecorder.registeredPhases"),
            "生成物测试没断言互动钩子的注册相位",
        )
        assertTrue(generatedTest.contains("themeAudioStarted"), "生成物测试没断言 audio.play 的返回值")
        assertTrue(
            generatedTest.contains("context.audioRecorder.stopCount"),
            "生成物测试没断言 onDisable 的 audio.stop 收尾",
        )
    }

    @Test
    fun uncoveredFaceDiffIsTheFrozenKnownDrift() {
        val matrixOnly = matrixFaces() - scaffoldFaces()
        val scaffoldOnly = scaffoldFaces() - matrixFaces()
        assertEquals(
            KNOWN_MATRIX_ONLY,
            matrixOnly,
            "「示范有、骨架没有」的面变了：新增=骨架落后待补/消失=已补齐，都要 lead 裁决后同步本账",
        )
        assertEquals(
            KNOWN_SCAFFOLD_ONLY,
            scaffoldOnly,
            "「骨架有、示范测试没点名」的面变了：新增=示范测试落后/消失=已补齐，都要 lead 裁决后同步本账",
        )
    }

    @Test
    fun readmeSectionsMatchCapabilityLedger() {
        val readme = scaffoldSource("README.txt")
        assertEquals(
            NewModTemplates.README_CAPABILITY_SECTIONS,
            readmeSections(readme),
            "README.txt 能力小节与能力清单常量不一致：README 说了没生成 / 生成了没写 README",
        )
    }

    @Test
    fun declarationStructureParity() {
        val template = withoutComments(repoSources(TEMPLATE_DECLARATIONS_SOURCE))
        val generated = withoutComments(scaffoldSource("Declarations.kt"))
        IDENTITY_ANCHORS.forEach { anchor ->
            assertTrue(anchor.containsMatchIn(template), "示范 TemplateDeclarations.kt 缺身份结构：${anchor.pattern}")
            assertTrue(anchor.containsMatchIn(generated), "生成 Declarations.kt 缺身份结构：${anchor.pattern}")
        }
        assertTrue(PACKAGE_ID_SHAPE.matches(declarationConst(template, "PACKAGE_ID")), "示范包名形状不合法")
        assertTrue(PACKAGE_ID_SHAPE.matches(declarationConst(generated, "PACKAGE_ID")), "生成包名形状不合法")
        assertTrue(VERSION_SHAPE.matches(declarationConst(template, "VERSION")), "示范版本形状不合法")
        assertTrue(VERSION_SHAPE.matches(declarationConst(generated, "VERSION")), "生成版本形状不合法")
        assertEquals(
            declarationKinds(template) + SCAFFOLD_EXTRA_DECLARATION_KINDS,
            declarationKinds(generated),
            "manifest 声明 DSL 种类两边不同构（示范 + 冻结的骨架额外种类 必须逐项等于生成物）",
        )
        val templateFields = componentFieldVerbs(template)
        val generatedFields = componentFieldVerbs(generated)
        assertEquals(setOf("int", "str"), generatedFields, "生成骨架的 component 字段动词账变了")
        assertTrue(templateFields.containsAll(generatedFields), "骨架的 component 字段动词必须是示范的子集（示范可多演示 ref）")
        PARAM_SHAPES.forEach { (label, shape) ->
            assertTrue(shape.containsMatchIn(template), "示范缺参数形状：$label")
            assertTrue(shape.containsMatchIn(generated), "生成骨架缺参数形状：$label")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 冻结账：作者面全集 / 两侧观测面 / README 能力小节在 main 侧常量。
// ---------------------------------------------------------------------------------------------

/** `ModContext` 的 24 个作者面，声明序（DH 审计范式的冻结表）。 */
private val AUTHOR_FACE_ORDER: List<String> = listOf(
    "packageId", "logger", "runtime", "entities", "relationships", "roster", "events", "hooks",
    "phases", "jobs", "interactions", "actions", "query", "functions", "storage", "gameState",
    "rng", "timing", "settings", "services", "resources", "strings", "audio", "ui",
)

/**
 * matrix 测试的观测器 → 作者面映射：测试经 `:mod-testing` 录制替身取证，
 * 访问器名与作者面名不同（如 `eventsRecorder` 观测 `events`）。
 */
private val MATRIX_ACCESSOR_FACE: Map<String, String> = mapOf(
    "boundJobIds" to "jobs",
    "fireJob" to "jobs",
    "eventsRecorder" to "events",
    "dispatchEvent" to "events",
    "firePhase" to "phases",
    "hooksRecorder" to "hooks",
    "interactionsRecorder" to "interactions",
    "entities" to "entities",
    "uiRecorder" to "ui",
    "resourcesRecorder" to "resources",
    "settingsRecorder" to "settings",
    "rng" to "rng",
    "timingRecorder" to "timing",
)

/** matrix 测试点名的作者面（冻结）。 */
private val MATRIX_FACES: Set<String> = MATRIX_ACCESSOR_FACE.values.toSet()

/** 生成骨架 `Mod.kt` 真调用的作者面（冻结）。 */
private val SCAFFOLD_FACES: Set<String> = setOf(
    "storage", "strings", "resources", "logger", "services", "jobs", "events", "hooks",
    "phases", "ui", "timing", "settings", "rng", "entities", "interactions", "audio",
)

/** 生成骨架里属于身份、不算能力面的访问器。 */
private val SCAFFOLD_IDENTITY_ACCESSORS: Set<String> = setOf("packageId")

/** 生成骨架允许出现的 `context.<x>` 全集（作者面 + 身份面）。 */
private val SCAFFOLD_ACCESSOR_ALLOWLIST: Set<String> = AUTHOR_FACE_ORDER.toSet() + SCAFFOLD_IDENTITY_ACCESSORS

/**
 * 已知真漂移①：示范/矩阵测试有、生成骨架没有的面。
 *
 * 现为**空集**：`interactions` 已在 task-159 补进骨架（`onEnable` 真注册 PRE 相位钩子，
 * `Declarations` 真声明 `interactionHook`），对拍两侧一致。空集仍显式冻结 ——
 * 任一侧再出现「示范有、骨架没有」的面都会让断言红，不许悄悄漂。
 */
private val KNOWN_MATRIX_ONLY: Set<String> = emptySet()

/**
 * 已知真漂移②：生成骨架有、matrix 测试没点名的面。
 *
 * `logger` / `services` / `storage` / `strings`：骨架 `onLoad` 真调用，但 matrix 测试只经
 * TemplateMod 自己的用例覆盖（`TemplateModTest` 等），本矩阵测试不点名。属定义性差异，
 * 但对拍账要显式登记，防止「以为两边覆盖面一样」。
 *
 * `audio`：task-159 起骨架 `onEnable` 真调 `audio.play`、`onDisable` 真调 `audio.stop`，
 * 但 matrix 测试（`TemplateCapabilityMatrixTest`）没走音频面替身，故只登记在骨架侧。
 */
private val KNOWN_SCAFFOLD_ONLY: Set<String> = setOf("logger", "services", "storage", "strings", "audio")

// ---------------------------------------------------------------------------------------------
// 输入路径与解析
// ---------------------------------------------------------------------------------------------

private const val MOD_CONTEXT_SOURCE: String = "mod-api/src/main/kotlin/com/eratw/port/modapi/ModContext.kt"
private const val TEMPLATE_MATRIX_SOURCE: String =
    "mod-template/src/test/kotlin/com/eratw/port/modtemplate/TemplateCapabilityMatrixTest.kt"
private const val TEMPLATE_DECLARATIONS_SOURCE: String =
    "mod-template/src/main/kotlin/com/eratw/port/modtemplate/TemplateDeclarations.kt"

private val COMMENT: Regex = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")
private val STRING_LITERAL: Regex = Regex(""""(?:\\.|[^"\\])*"""")

/** 作者访问器：`context.<x>` 以及测试里换名接收者（如 `boundaryContext.rng`）。 */
private val CONTEXT_ACCESSOR: Regex = Regex("""\b\w*[Cc]ontext\.([a-z]\w*)""")

/** `ModContext` 里的属性声明（`val name:`）。 */
private val FACE_DECL: Regex = Regex("""(?m)^\s+val\s+([A-Za-z_]\w*)\s*:""")

/** README 能力小节标题：顶格且以全角右括号收尾（正文段落不会以「）」结尾）。 */
private fun readmeSections(readme: String): List<String> =
    readme.lines().filter { line -> line.isNotBlank() && !line.startsWith(" ") && line.endsWith("）") }

private fun repoSources(relative: String): String {
    val path = repoRoot().resolve(relative)
    check(Files.isRegularFile(path)) { "缺对拍输入源码：$path" }
    return Files.readString(path)
}

private fun withoutComments(source: String): String = COMMENT.replace(source, " ")

/** 面账扫描只认代码：注释与字符串字面量都会出现 `XxxContext.time` 这类说明文字，必须一起抹掉。 */
private fun codeOnly(source: String): String =
    COMMENT.replace(STRING_LITERAL.replace(source, "\"\""), " ")

private fun contextAccessors(source: String): Set<String> =
    CONTEXT_ACCESSOR.findAll(codeOnly(source)).mapTo(mutableSetOf()) { it.groupValues[1] }

private fun authorFaces(): Set<String> {
    val body = repoSources(MOD_CONTEXT_SOURCE)
        .substringAfter("interface ModContext")
        .substringBefore("interface ModLogger")
    return FACE_DECL.findAll(body).mapTo(mutableSetOf()) { it.groupValues[1] }
}

/** 生成骨架全文（按相对路径索引），走模板本体而不是落盘生成物。 */
private fun scaffoldFiles(): Map<String, String> =
    NewModTemplates.files(
        ScaffoldRequest(
            packageId = "eratw.mymod",
            kotlinPackage = "com.example.mymod",
            className = "My",
            moduleName = "mod-my",
            outputDir = Path.of("build", "template-scaffold-parity-unused"),
        ),
    ).filterIsInstance<ScaffoldFile>().associate { it.relativePath to it.content }

private fun scaffoldSource(suffix: String): String {
    val files = scaffoldFiles()
    val matches = files.filterKeys { path -> path.endsWith(suffix) }
    check(matches.size == 1) { "生成物里按后缀 $suffix 应恰好命中一个文件，实际=${files.keys}" }
    return matches.values.single()
}

private fun matrixFaces(): Set<String> =
    contextAccessors(repoSources(TEMPLATE_MATRIX_SOURCE)).mapNotNullTo(mutableSetOf()) { MATRIX_ACCESSOR_FACE[it] }

private fun scaffoldFaces(): Set<String> =
    contextAccessors(scaffoldSource("Mod.kt")) - SCAFFOLD_IDENTITY_ACCESSORS

// ---------------------------------------------------------------------------------------------
// 声明结构（Task 157-c）
// ---------------------------------------------------------------------------------------------

/** 身份成员结构：包名/版本/newMod/manifest 的声明形状（值不参与比较）。 */
private val IDENTITY_ANCHORS: List<Regex> = listOf(
    Regex("""const val PACKAGE_ID: String = "[^"]+""""),
    Regex("""const val VERSION: String = "[^"]+""""),
    Regex("""override val packageId: String get\(\) = PACKAGE_ID"""),
    Regex("""override fun newMod\(\): EraMod = \w+Mod\(\)"""),
    Regex("""override val manifest: ModManifest = modDeclarations\(PACKAGE_ID, version = VERSION\)"""),
)

private val PACKAGE_ID_SHAPE: Regex = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
private val VERSION_SHAPE: Regex = Regex("""^\d+\.\d+$""")

/**
 * 冻结的「骨架额外声明的 DSL 种类」：示范模板 `TemplateDeclarations.kt` 属 `mod-template`，
 * 本波（task-159）明确不动它，因此示范侧还没有 `interactionHook`；生成骨架按作者面示范它。
 * 用**精确集合差**登记（`示范种类 + 本账 == 生成种类`），不放成子集比较 —— 任一侧种类再增删都会红。
 * 示范模板补上 `interactionHook` 后，删掉本账并让两边直接相等。
 */
private val SCAFFOLD_EXTRA_DECLARATION_KINDS: Set<String> = setOf("interactionHook")

/** manifest 声明 DSL 动词（含设置四型）。 */
private val DECLARATION_KIND: Regex = Regex(
    """\b(component|archetype|relation|job|hook|interactionHook|phase""" +
        """|intSetting|boolSetting|enumSetting|strSetting)\s*\(""",
)

/** component 块内的字段动词。 */
private val COMPONENT_BLOCK: Regex = Regex("""component\([^)]*\)\s*\{([^}]*)\}""")

/** component 块里的字段动词。 */
private val FIELD_VERB: Regex = Regex("""\b(int|str|ref|bool|long|float|entity)\s*\(""")

/** 声明参数形状：`job everyDays` / `hook 相位+选项` / `archetype uses` / 设置三型的必填参数。 */
private val PARAM_SHAPES: List<Pair<String, Regex>> = listOf(
    "job everyDays = " to Regex("""job\([^)]*everyDays = """),
    "hook 相位 + HookOptions(priority)" to Regex("""hook\([^)]*HookPhase\.\w+, HookOptions\(priority = \d+\)"""),
    "archetype uses" to Regex("""archetype\([^)]*\)\s*\{\s*uses\("""),
    "intSetting range" to Regex("""intSetting\([^)]*range = \d+L?\.\.\d+L?"""),
    "enumSetting candidates" to Regex("""enumSetting\([^)]*candidates = listOf\("""),
)

private fun declarationConst(source: String, name: String): String =
    Regex("""const val $name: String = "([^"]*)"""").find(source)?.groupValues?.get(1)
        ?: error("缺 const val $name: String 声明")

private fun declarationKinds(source: String): Set<String> =
    DECLARATION_KIND.findAll(source).mapTo(mutableSetOf()) { it.groupValues[1] }

private fun componentFieldVerbs(source: String): Set<String> {
    val block = COMPONENT_BLOCK.find(source)?.groupValues?.get(1)
        ?: error("缺 component(...) { ... } 块")
    return FIELD_VERB.findAll(block).mapTo(mutableSetOf()) { it.groupValues[1] }
}
