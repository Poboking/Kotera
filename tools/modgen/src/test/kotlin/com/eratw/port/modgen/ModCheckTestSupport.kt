package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.AccessorEmit
import com.eratw.port.modgen.accessors.parseSchema
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 在 [root] 下用 new-mod 生成 `eratw.mymod`/`My` 骨架，返回模块目录。 */
internal fun scaffoldModule(root: Path): Path {
    val module = root.resolve("mod-mymod")
    val exit = NewModScaffold.runCli(
        arrayOf("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString()),
    )
    assertTrue(exit == 0, "new-mod scaffold failed with exit $exit")
    return module
}

/** 用唯一的 accessor 生成器按 schema 写出 src/generated 生成物（与入库生成物同口径）。 */
internal fun writeGenerated(module: Path) {
    val decl = parseSchema(schemaFile(module).readText())
    val target = generatedFile(module)
    Files.createDirectories(target.parent)
    Files.writeString(target, AccessorEmit.emit(decl))
}

internal fun schemaFile(module: Path): Path = module.resolve("schemas/My.schema.tsv")

internal fun declarationsFile(module: Path): Path = module.resolve("src/main/kotlin/eratw/mymod/MyDeclarations.kt")

internal fun generatedFile(module: Path): Path = module.resolve("src/generated/kotlin/eratw/mymod/generated/My.kt")

/** 进程内直调 [ModCheck.check]；[schemas] 为空时走缺省发现。 */
internal fun findingsOf(module: Path, schemas: List<Path> = emptyList()): List<Finding> =
    ModCheck.check(module.toFile(), schemas.map { it.toFile() })

internal fun errorsOf(findings: List<Finding>): List<Finding> = findings.filter { it.severity == Severity.ERROR }

internal fun assertNoError(findings: List<Finding>) {
    val errors = errorsOf(findings)
    assertTrue(errors.isEmpty(), "expected no ERROR but got: ${errors.joinToString("; ") { it.message }}")
}

internal fun assertHasError(findings: List<Finding>, fragment: String) {
    val match = errorsOf(findings).firstOrNull { it.message.contains(fragment) }
    assertNotNull(
        match,
        "expected an ERROR containing '$fragment' but got: ${findings.joinToString("; ") { it.message }}",
    )
}

/** 把文件里唯一一处 [old] 换成 [new]（断言确实命中，避免静默无效夹具）。 */
internal fun replaceOnce(file: Path, old: String, new: String) {
    val text = file.readText()
    assertTrue(text.contains(old), "text to replace not found in $file: $old")
    Files.writeString(file, text.replace(old, new))
}

/** 从 Gradle 测试工作目录向上找 settings.gradle.kts，得到本仓（引擎仓）根。 */
internal fun repoRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

/**
 * 壳层仓（eratw-pocket）里的源文件路径。
 *
 * 公开分仓后壳层模块（app / platform / render-canvas）在同级壳层仓，modgen 的几道闸
 * 审的正是**壳层**真源（打包限额与 ModStore 名字规则的同源口）。
 * 壳层仓不在场时（如本仓 CI 只检出引擎仓）跳过 —— 数据缺失不是回归；
 * 壳层仓在场时守卫照常跑，口径与分仓前单体形态一致。
 */
internal fun shellSource(relative: String): Path {
    val file = repoRoot().resolveSibling("eratw-pocket").resolve(relative)
    if (!Files.isRegularFile(file)) {
        throw org.opentest4j.TestAbortedException(
            "找不到壳层真源 $relative（需同级检出壳层仓 eratw-pocket）：" +
                "cwd=${Path.of("").toAbsolutePath()}",
        )
    }
    return file
}
