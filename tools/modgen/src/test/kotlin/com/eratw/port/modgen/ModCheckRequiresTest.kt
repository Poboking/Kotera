package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 依赖声明面（`EraMod.requires`）的静态检查：只抽文本字面量，抽不到就静默。 */
class ModCheckRequiresTest {

    @TempDir
    lateinit var tempDir: Path

    /** 用 new-mod 生成任意包/类名的骨架（module 目录名走 `mod-*`，才会被兄弟扫描收录）。 */
    private fun customModule(dirName: String, packageId: String, className: String): Path {
        val module = tempDir.resolve(dirName)
        val exit = NewModScaffold.runCli(
            arrayOf(
                "new-mod", "--package", packageId, "--name", className, "--out", module.toString(),
            ),
        )
        assertEquals(0, exit, "new-mod scaffold failed for $dirName")
        return module
    }

    /** 骨架里 EraMod 实现文件的路径（new-mod 的包路径 = packageId 的点分段）。 */
    private fun modFile(module: Path, packageId: String, className: String): Path =
        module.resolve("src/main/kotlin/${packageId.replace('.', '/')}/${className}Mod.kt")

    /** 在 EraMod 实现类头后面插一行 requires 声明。 */
    private fun addRequires(module: Path, packageId: String, className: String, expression: String) {
        replaceOnce(
            modFile(module, packageId, className),
            "class ${className}Mod : EraMod {",
            "class ${className}Mod : EraMod {\n\n    $expression",
        )
    }

    /** 只挑依赖面的发现，别把 schema/生成物面的发现掺进断言。 */
    private fun dependencyFindings(module: Path): List<Finding> =
        findingsOf(module).filter { finding -> finding.message.startsWith("依赖") }

    /** 依赖面里某类消息的发现数（按消息片段过滤）。 */
    private fun dependencyFindings(module: Path, fragment: String): List<Finding> =
        dependencyFindings(module).filter { finding -> finding.message.contains(fragment) }

    @Test
    fun noRequiresDeclarationIsSilent() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        assertEquals(emptyList(), findingsOf(module), "没有 requires 时依赖面必须完全静默")
    }

    @Test
    fun annotatedRequiresStyleIsExtracted() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires: List<String> = listOf(\"eratw.mymod\")")

        val errors = dependencyFindings(module, "依赖自指")

        assertEquals(1, errors.size, "带类型标注的 requires 也要抽出来：$errors")
        assertEquals(Severity.ERROR, errors.single().severity)
    }

    @Test
    fun getterStyleRequiresIsExtracted() {
        val module = scaffoldModule(tempDir)
        addRequires(
            module,
            "eratw.mymod",
            "My",
            "override val requires: List<String> get() = listOf(\"eratw.mymod\")",
        )

        assertHasError(dependencyFindings(module), "依赖自指")
    }

    @Test
    fun inferredRequiresStyleIsExtracted() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.mymod\")")

        assertHasError(dependencyFindings(module), "依赖自指")
    }

    @Test
    fun illegalPackageNameReportsError() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw/bad\")")

        val findings = dependencyFindings(module, "形态非法")

        assertEquals(1, findings.size, "非法包名应恰一条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "形态不对的依赖名永远匹配不上载体，必须 ERROR")
        assertTrue(findings.single().message.contains("eratw/bad"), "message=${findings.single().message}")
    }

    @Test
    fun blankPackageNamesReportErrors() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"\", \" \")")

        val findings = dependencyFindings(module, "形态非法")

        assertEquals(2, findings.size, "空串与纯空白都不是合法包名，应各报一条 ERROR：$findings")
        assertTrue(findings.all { finding -> finding.severity == Severity.ERROR }, "findings=$findings")
    }

    @Test
    fun duplicateDependencyReportsOneWarning() {
        val module = scaffoldModule(tempDir)
        addRequires(
            module,
            "eratw.mymod",
            "My",
            "override val requires = listOf(\"eratw.external\", \"eratw.external\")",
        )

        val findings = dependencyFindings(module, "重复声明")

        assertEquals(1, findings.size, "同一处 listOf 里重复应恰一条 WARN：$findings")
        assertEquals(Severity.WARN, findings.single().severity)
        assertTrue(findings.single().message.contains("eratw.external"), "message=${findings.single().message}")
    }

    @Test
    fun declaredButMissingSiblingReportsWarningWithHint() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.external\")")

        val findings = dependencyFindings(module, "依赖包未找到")

        assertEquals(1, findings.size, "兄弟模块里没有应恰一条 WARN：$findings")
        assertEquals(Severity.WARN, findings.single().severity, "外部包可能另外装，静态面不许 ERROR")
        assertTrue(
            findings.single().message.contains("本仓内置载体里都没有这个 packageId"),
            "message=${findings.single().message}",
        )
        val expectedLine = modFile(module, "eratw.mymod", "My").readText()
            .lines().indexOfFirst { line -> line.contains("override val requires") } + 1
        assertEquals(expectedLine, findings.single().line, "应点名 requires 声明所在行")
        assertEquals("src/main/kotlin/eratw/mymod/MyMod.kt", findings.single().file, "应点名声明所在文件")
    }

    @Test
    fun siblingCarrierMakesDependencyResolvable() {
        customModule("mod-sister", "eratw.sister", "Sister")
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.sister\")")

        assertEquals(emptyList(), dependencyFindings(module), "兄弟模块里有这个包时依赖面不该报任何发现")
    }

    @Test
    fun selfDependencyReportsError() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.mymod\")")

        assertHasError(dependencyFindings(module), "依赖自指")
    }

    @Test
    fun builtinCarrierDependencyIsResolvable() {
        val module = scaffoldModule(tempDir)
        addRequires(
            module,
            "eratw.mymod",
            "My",
            "override val requires = listOf(\"eratw.family\", \"eratw.memory\", \"eratw.lw.character\")",
        )

        assertEquals(
            emptyList(),
            dependencyFindings(module),
            "内置载体在玩家机器上永远在场，依赖它们不该报兄弟缺失（否则第三方 mod 假 WARN）",
        )
    }

    @Test
    fun misspelledBuiltinCarrierStillWarns() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.famly\")")

        val findings = dependencyFindings(module, "依赖包未找到")

        assertEquals(1, findings.size, "拼错的内置包名不该被内置集合宽容：$findings")
        assertEquals(Severity.WARN, findings.single().severity)
    }

    @Test
    fun builtinCarriersHaveNoDependencyFindings() {
        val root = repoRoot()
        val carriers = listOf("mod-family", "mod-memory", "mod-template", "mod-livingworld")
        val offenders = carriers.flatMap { name ->
            dependencyFindings(root.resolve(name)).map { finding ->
                "$name: ${finding.severity} ${finding.message}"
            }
        }

        assertTrue(offenders.isEmpty(), "内置载体按新口径必须 0 error / 0 warning：$offenders")
    }

    @Test
    fun builtinCarrierIdsStayInSyncWithRepoDeclarations() {
        // 真源 = 会随包分发的载体模块；示例模块（mod-template / mod-testing）只是给作者抄的样例，
        // 不随 app 分发，故与 :mod-registry 构建期扫描同样排除。
        val excluded = setOf("mod-api", "mod-registry", "mod-template", "mod-testing")
        val modules = repoRoot().toFile()
            .listFiles { file ->
                file.isDirectory && file.name.startsWith("mod-") && file.name !in excluded
            }
            .orEmpty()
        val extracted = modules.flatMap { module ->
            module.resolve("src/main").walkTopDown()
                .filter { file -> file.isFile && file.name.endsWith("Declarations.kt") }
                .flatMap { file ->
                    DECLARATION_PACKAGE_ID.findAll(file.readText()).map { match -> match.groupValues[1] }
                }
                .toList()
        }.distinct().sorted()

        assertEquals(
            6,
            extracted.size,
            "内置载体个数变了：BUILTIN_CARRIER_PACKAGE_IDS 要同步（真源 = 随包分发的 mod-* 模块 " +
                "Declarations.kt，排除示例模块）：$extracted",
        )
        assertEquals(
            ModCheckRequires.BUILTIN_CARRIER_PACKAGE_IDS.sorted(),
            extracted,
            "mod-* 模块 const val PACKAGE_ID 与 check-mod 内置载体集合漂移",
        )
    }

    @Test
    fun staticCycleReportsErrorNamingEveryMember() {
        val moduleA = customModule("mod-a", "eratw.a", "A")
        val moduleB = customModule("mod-b", "eratw.b", "B")
        addRequires(moduleA, "eratw.a", "A", "override val requires = listOf(\"eratw.b\")")
        addRequires(moduleB, "eratw.b", "B", "override val requires = listOf(\"eratw.a\")")

        val errors = errorsOf(dependencyFindings(moduleA))

        assertEquals(1, errors.size, "A→B→A 应恰一条成环 ERROR：$errors")
        val message = errors.single().message
        assertTrue(message.contains("依赖成环"), "message=$message")
        assertTrue(message.contains("eratw.a -> eratw.b -> eratw.a"), "成环消息应点名环上成员：$message")
    }

    @Test
    fun nonLiteralRequiresIsSilent() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires: List<String> = externalDependencies")

        assertEquals(emptyList(), dependencyFindings(module), "非字面量（变量）抽不到就静默，宁可漏报")
    }

    @Test
    fun interpolationIsSkippedAndSilent() {
        val module = scaffoldModule(tempDir)
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.\$name\")")

        assertEquals(emptyList(), dependencyFindings(module), "插值抽不到确定包名，必须静默")
    }

    @Test
    fun multiPackageModuleSkipsSelfDependency() {
        val module = scaffoldModule(tempDir)
        val second = module.resolve("src/main/kotlin/eratw/mymod/Second.kt")
        Files.createDirectories(second.parent)
        Files.writeString(second, "package eratw.mymod\n\nconst val PACKAGE_ID: String = \"eratw.other\"\n")
        addRequires(module, "eratw.mymod", "My", "override val requires = listOf(\"eratw.other\")")

        assertEquals(
            emptyList(),
            dependencyFindings(module, "依赖自指"),
            "多包模块无法把 requires 归属到具体载体，不许把同模块另一个包误判成自依赖",
        )
        assertEquals(
            emptyList(),
            dependencyFindings(module, "依赖包未找到"),
            "同模块另一个包属于可解析依赖，不许报兄弟缺失",
        )
    }

    @Test
    fun packageIdShapeStaysInSyncWithModResourceStore() {
        val source = repoRoot().resolve("core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt")
        val text = source.readText()

        assertTrue(
            text.contains("""private val PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")"""),
            "运行期 packageId 形态闸改了，check-mod 的依赖面会与装载期漂移：$source",
        )
    }
}

/** `Declarations.kt` 里 `const val PACKAGE_ID: String = "..."` 的字面量（真源文本哨兵用）。 */
private val DECLARATION_PACKAGE_ID = Regex("const val PACKAGE_ID: String = \"([^\"]+)\"")
