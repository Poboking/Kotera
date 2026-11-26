package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** 真源 [ModApi.kt] 的仓库相对路径（与 [ModCheckMinApi] 找真源的仓库根口径一致）。 */
private const val REAL_API_SOURCE = "mod-api/src/main/kotlin/com/eratw/port/modapi/ModApi.kt"

/** 与 [ModCheckMinApi] 同口径的 `VERSION` 抽取（测试侧只用来读真实仓库的当前版本号）。 */
private const val VERSION_CONST = """\bconst\s+val\s+VERSION\s*(?::\s*Int\s*)?=\s*(\d+)"""

/** min-api 载体源码的相对路径（行号可预期：1 = package，2 = 空行，3 = 类头，4 起 = 正文）。 */
private const val MIN_API_SAMPLE = "src/main/kotlin/eratw/mymod/MinApiSample.kt"

private const val VALUE_GROUP = 1

/** 从真实仓库的 [ModApi.kt] 抽当前 `VERSION`；假仓库照抄同值，测试里不写死版本号。 */
internal fun repoMinApiVersion(): Int {
    val text = repoRoot().resolve(REAL_API_SOURCE).readText()
    val match = Regex(VERSION_CONST).find(text)
        ?: error("$REAL_API_SOURCE 里抽不到 const val VERSION —— 抽取口径漂移了")
    return match.groupValues[VALUE_GROUP].toInt()
}

/** 造一个假仓库根标记（`settings.gradle.kts`），让 check 的「向上找仓库根」停在测试目录。 */
internal fun fakeRepoRoot(root: Path) {
    Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fake-repo\"\n")
}

/** 假仓库里的 mod-api 真源（只有 `VERSION` 常量；版本表缺失时 [ModCheckApiSurface] 自己整体静默）。 */
internal fun fakeModApiSource(root: Path, version: Int) {
    val file = root.resolve(REAL_API_SOURCE)
    Files.createDirectories(file.parent)
    Files.writeString(
        file,
        "package com.eratw.port.modapi\n\nobject ModApi {\n    const val VERSION: Int = $version\n}\n",
    )
}

/** 「假仓库根 + VERSION 真源 + new-mod 骨架」一整套；[version] 缺省取真实仓库的当前值。 */
internal fun minApiModule(root: Path, version: Int = repoMinApiVersion()): Path {
    fakeRepoRoot(root)
    fakeModApiSource(root, version)
    val module = scaffoldModule(root)
    writeGenerated(module)
    return module
}

/** 往模块里写一份载体源码：类头固定在第 3 行，[body] 从第 4 行起，行号断言可预期。 */
internal fun writeMinApiCarrier(module: Path, body: String): Path {
    val file = module.resolve(MIN_API_SAMPLE)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\nclass MinApiCarrier : EraMod {\n$body\n}\n")
    return file
}

/**
 * 往模块里写一份**真载体形态**（`modDeclarations(…)` DSL）的声明：`minApi` 实参钉在第 4 行，
 * 行号断言与 [writeMinApiCarrier] 同口径。不写 `minApi` 就只传 `arguments` 里的其余实参。
 */
internal fun writeMinApiDeclarationsCarrier(module: Path, arguments: String): Path {
    val file = module.resolve(MIN_API_SAMPLE)
    Files.createDirectories(file.parent)
    Files.writeString(
        file,
        "package eratw.mymod\n\nclass MinApiCarrier : EraMod {\n" +
            "    override val manifest: ModManifest = modDeclarations($arguments)\n}\n",
    )
    return file
}

/** 只挑 min-api 面的发现，别把 schema / 声明面的发现掺进断言。 */
internal fun minApiFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding -> finding.message.contains("min-api") }
