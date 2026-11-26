package com.eratw.port.modgen

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `modgen new-mod`：一条命令生成一个**能编译**的最小 mod 模块骨架。
 *
 * 用法（`--help` 打印同一份说明）：
 * ```
 * new-mod --package <id> --name <类名前缀> --out <模块目录> [--check] [--force]
 * ```
 *
 * 生成六个条目（相对 `--out`）：
 * - `build.gradle.kts`：`api(project(":mod-api"))` + JUnit 5，形状照 `mod-family`。
 * - `src/main/kotlin/<包路径>/<Name>Declarations.kt`：`@ModCarrier` 载体声明，含
 *   component / archetype / relation / job 各一例。
 * - `src/main/kotlin/<包路径>/<Name>Mod.kt`：`EraMod` 六段生命周期，`ModContext` 的
 *   `storage` / `jobs` / `rng` / `entities` / `strings` / `resources` 都有真实调用
 *   （v10 三语文案在 `onLoad` 声明、`onTurnBoundary` 取用；`README.txt` 真实读一次并打字节数），
 *   另带一段注释版的图片元素用法。
 * - `schemas/<Name>.schema.tsv`：与 Declarations 字段线名逐一对齐，能直接喂 accessor 生成器。
 * - `src/test/kotlin/<包路径>/<Name>ModTest.kt`：断言清单真的带着那几项声明的最小可跑测试。
 * - `<packageId>.assets/` 目录 + `README.txt` 示例资源：运行期资源根
 *   （`mods/rt2/<packageId>.assets/`）的随包骨架，演示按名读取、三语文案面与图片元素面。
 *
 * 幂等与安全约定：
 * - 目标条目（文件或资源目录路径）**已存在默认拒绝**并逐个打印 `CONFLICT:`，一个文件都不写；
 *   `--force` 才覆盖。目录已存在且仍是目录时不算冲突（不覆盖作者的资源文件）。
 * - `--check` 只打印计划，绝不写盘（连目录都不建）；有冲突时退出码 1。
 * - `--package` / `--name` / 模块目录名都做标识符校验，任何一段含 `..`、路径分隔符或非法字符
 *   直接拒绝，防止把文件写出 `--out` 之外。
 * - **不改 `settings.gradle.kts`**（共享文件，改了会跟其他 lane 撞车）：stdout 打印
 *   `NEXT: ... include(":<模块名>")` 让作者自己接线。
 *
 * 退出码：`0` 成功；`1` 目标文件已存在且没给 `--force`；`2` 参数/校验/IO 错误。
 *
 * 为什么不是顶层 `fun main`：同包的 `ManifestBuild.kt` 已有顶层 main（`ManifestProdChainTest`
 * 直接裸调它），再添一个顶层 main 会陷入重载/调用解析歧义（`BuiltinManifestBuild.kt` 的 KDoc
 * 已踩过）。故本入口是 `@JvmStatic fun main`，Gradle 侧 mainClass 用本 object 全名即可。
 */
object NewModScaffold {

    private const val EXIT_CONFLICT: Int = 1
    private const val EXIT_USAGE: Int = 2
    private const val MOD_MODULE_PREFIX: String = "mod-"

    private val USAGE: String = """
        |用法：new-mod --package <id> --name <类名前缀> --out <模块目录> [--check] [--force]
        |
        |在 --out 指定的目录里生成一个能编译的最小 mod 模块骨架（六个条目）：
        |  build.gradle.kts
        |  src/main/kotlin/<包路径>/<Name>Declarations.kt
        |  src/main/kotlin/<包路径>/<Name>Mod.kt
        |  schemas/<Name>.schema.tsv
        |  src/test/kotlin/<包路径>/<Name>ModTest.kt
        |  <packageId>.assets/README.txt（资源包目录 + 示例资源 + 三语文案 / 图片元素用法）
        |
        |参数：
        |  --package <id>   mod 包身份，同时用作 Kotlin 包名（点分段、全小写，如 eratw.mymod）
        |  --name <前缀>    类名前缀（PascalCase，如 My：生成 MyDeclarations / MyMod / MyModTest）
        |  --out <目录>     模块根目录，建议 mod-<名字>（:mod-registry 只扫 mod-* 目录名）
        |  --check          只打印将写哪些文件，绝不写盘；有冲突时退出码 1
        |  --force          允许覆盖已存在的同名文件（默认拒绝并列冲突）
        |
        |退出码：0 成功 / 1 目标已存在（默认拒绝）/ 2 参数或校验错误
        |生成后请自行在 settings.gradle.kts 里加 include(":<模块目录名>")——本工具不改共享文件。
        |
    """.trimMargin()

    /** 进程入口；`exitProcess(2)` 时 Gradle 的 JavaExec 会判失败，正是想要的。 */
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(runCli(args))
    }

    /**
     * 可测试入口：错误只写 [err] 并返回退出码，不抛异常、不退出进程。
     * 与 `tools/modgen-accessors` 的 `runCli` 同形（0 成功 / 1 不一致 / 2 用法或 IO 错误）。
     */
    fun runCli(
        args: Array<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int = try {
        dispatch(args, out, err)
    } catch (error: IllegalArgumentException) {
        err.println("ERROR: ${error.message}")
        EXIT_USAGE
    } catch (error: IOException) {
        err.println("ERROR: ${error.message}")
        EXIT_USAGE
    }

    private fun dispatch(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val arguments = normalizeArguments(args)
        if (arguments.any { it == "--help" || it == "-h" }) {
            out.println(USAGE)
            return 0
        }
        val options = parse(arguments)
        val entries = NewModTemplates.files(options.request)
        out.println("PLAN: ${entries.size} 个条目 → ${options.request.outputDir}")
        return if (options.check) preview(options, entries, out, err) else write(options, entries, out, err)
    }

    /**
     * 手动解析（不引新依赖）：本仓工具链的既有风格就是手写 `--flag value`，参数少、报错文案可控。
     * 未知 `--flag`、缺值、缺必需参数都在这里抛 `IllegalArgumentException`，由 [runCli] 转成退出码 2。
     */
    private fun parse(args: Array<String>): CliOptions {
        var packageId: String? = null
        var className: String? = null
        var output: String? = null
        var check = false
        var force = false
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--package" -> {
                    packageId = valueAfter(args, index, "--package")
                    index += 2
                }
                "--name" -> {
                    className = valueAfter(args, index, "--name")
                    index += 2
                }
                "--out" -> {
                    output = valueAfter(args, index, "--out")
                    index += 2
                }
                "--check" -> {
                    check = true
                    index += 1
                }
                "--force" -> {
                    force = true
                    index += 1
                }
                else -> throw IllegalArgumentException("未知参数：${args[index]}（--help 看用法）")
            }
        }
        val outputDir = validateOutputDir(requireNotNull(output) { "缺少 --out\n$USAGE" })
        return CliOptions(
            request = ScaffoldRequest(
                packageId = kotlinPackageOf(requireNotNull(packageId) { "缺少 --package\n$USAGE" }),
                kotlinPackage = kotlinPackageOf(requireNotNull(packageId) { "缺少 --package\n$USAGE" }),
                className = requireClassName(requireNotNull(className) { "缺少 --name\n$USAGE" }),
                moduleName = moduleNameOf(outputDir),
                outputDir = outputDir,
            ),
            check = check,
            force = force,
        )
    }

    /** `--check`：只报计划。目标条目已存在且没给 `--force` 时返回 1（此时也确实不会写盘）。 */
    private fun preview(
        options: CliOptions,
        entries: List<ScaffoldEntry>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        var conflicts = 0
        entries.forEach { entry ->
            val target = options.request.outputDir.resolve(entry.relativePath)
            val conflict = isConflict(entry, target)
            if (conflict) {
                conflicts += 1
                err.println("CONFLICT: ${display(entry)}（已存在；不加 --force 不覆盖）")
            }
            out.println("${if (conflict) "WOULD OVERWRITE" else "WOULD WRITE"}: ${display(entry)}")
        }
        printNextSteps(options.request, out)
        return if (conflicts == 0 || options.force) 0 else EXIT_CONFLICT
    }

    /**
     * 真正写盘：先整体判冲突，有冲突且没 `--force` 就**一个条目都不写**（不留下半套骨架）；
     * 每个目标路径都 `normalize()` 后复核还在 `--out` 之下，越界即失败。
     */
    private fun write(
        options: CliOptions,
        entries: List<ScaffoldEntry>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val root = options.request.outputDir
        val conflicts = entries.filter { isConflict(it, root.resolve(it.relativePath)) }
        if (conflicts.isNotEmpty() && !options.force) {
            conflicts.forEach { err.println("CONFLICT: ${display(it)}（已存在；--force 才覆盖）") }
            err.println("REFUSED: ${conflicts.size} 个目标已存在，未写任何条目；先用 --check 预览")
            return EXIT_CONFLICT
        }
        Files.createDirectories(root)
        entries.forEach { entry ->
            val target = root.resolve(entry.relativePath).normalize()
            require(target.startsWith(root)) { "生成路径越界，拒绝写入：${entry.relativePath}" }
            materialize(entry, target)
            out.println("WROTE: $target")
        }
        printNextSteps(options.request, out)
        return 0
    }

    /** 只建目录或写文件；父目录按需创建。 */
    private fun materialize(entry: ScaffoldEntry, target: Path) {
        when (entry) {
            is ScaffoldDirectory -> Files.createDirectories(target)
            is ScaffoldFile -> {
                Files.createDirectories(target.parent)
                Files.writeString(target, entry.content, Charsets.UTF_8)
            }
        }
    }

    /** 目录条目只在目标路径已有同名**文件**时算冲突；文件条目目标已存在（文件或目录）都算冲突。 */
    private fun isConflict(entry: ScaffoldEntry, target: Path): Boolean =
        if (entry is ScaffoldDirectory) {
            Files.exists(target) && !Files.isDirectory(target)
        } else {
            Files.exists(target)
        }

    /** 预览/报错里显示的名字：目录带尾斜杠，跟文件区分。 */
    private fun display(entry: ScaffoldEntry): String =
        if (entry is ScaffoldDirectory) "${entry.relativePath}/" else entry.relativePath

    private fun printNextSteps(request: ScaffoldRequest, out: PrintStream) {
        out.println("NEXT: 在 settings.gradle.kts 里自己加 include(\":${request.moduleName}\") —— 本工具不改共享文件")
        if (!request.moduleName.startsWith(MOD_MODULE_PREFIX)) {
            out.println("WARN: 模块目录名不以 ${MOD_MODULE_PREFIX} 开头，:mod-registry 的 @ModCarrier 扫描不会收录它")
        }
    }
}

/** 一次解析出的完整输入：请求内容 + 两个开关。 */
private data class CliOptions(
    val request: ScaffoldRequest,
    val check: Boolean,
    val force: Boolean,
)

/** 子命令词（与 `modgen new-mod ...` 的入口分派一致）。 */
private const val SUBCOMMAND: String = "new-mod"

/** 剥掉可选的 `new-mod` 子命令词；首个参数不是子命令也不是 `--flag` 时报未知子命令。 */
private fun normalizeArguments(args: Array<String>): Array<String> {
    val first = args.firstOrNull() ?: return args
    require(first == SUBCOMMAND || first.startsWith("--") || first == "-h") {
        "未知子命令：$first（本工具的子命令是 $SUBCOMMAND）"
    }
    return if (first == SUBCOMMAND) args.drop(1).toTypedArray() else args
}

/** 取 `--flag` 后面那个值；值缺省或看起来是另一个 flag 时报缺值。 */
private fun valueAfter(args: Array<String>, flagIndex: Int, flag: String): String =
    args.getOrNull(flagIndex + 1)?.takeIf { !it.startsWith("--") }
        ?: throw IllegalArgumentException("$flag 需要一个值（--help 看用法）")

/**
 * `--package` 同时是 mod 包身份与 Kotlin 包名。
 *
 * 包身份口径与 `app` 侧的 `ModEnablement.PACKAGE_ID_PATTERN` 一致（`tools` 不能依赖 `app`，
 * 故复制同一正则，文案也照抄）；Kotlin 包名口径更紧：每段必须是标识符且不是 Kotlin 关键字，
 * 且首段不能是 `kotlin`（生成器侧对 `kotlin.*` 包名有拒绝闸）。两套口径都不放行 `..`、`/`、`\`，
 * 所以拿 `--package` 拼路径不会穿越。
 *
 * 第三道口径是**可派生性**：模板把包身份直接拼进三语文案键（`onLoad` 的 `declare` 等处用
 * 「包身份 + [LONGEST_STRINGS_KEY_SUFFIX]」），而运行期文案键只许全小写 `[a-z0-9_.-]+`、
 * ≤ [ModStringsLimits.MAX_KEY_LENGTH] 字符（ModStringsRegistry 的 requireValidKey）。
 * 这里不拒大写/超长的话，骨架会「生成成功」而 mod 装载 onLoad 即抛 IAE——所以这两条
 * 必须在脚手架入口就地拒绝（给 mod 作者早期干净报错）。
 */
private fun kotlinPackageOf(packageId: String): String {
    require(PACKAGE_ID_PATTERN.matches(packageId)) {
        "非法 packageId：$packageId（只允许字母数字与 . _ -，且不得以符号开头）"
    }
    val segments = packageId.split('.')
    require(segments.all { KOTLIN_SEGMENT_PATTERN.matches(it) && it !in KOTLIN_KEYWORDS }) {
        "--package 同时用作 Kotlin 包名：'$packageId' 含非法段（只允许字母/数字/下划线，且非 Kotlin 关键字）"
    }
    require(segments.first() != "kotlin") { "--package 首段不能是 kotlin：$packageId" }
    require(packageId.lowercase() == packageId) {
        "--package 必须全小写：$packageId（骨架把包身份派生成三语文案键，运行期文案键只许小写字母/数字/_/./-）"
    }
    require(packageId.length <= MAX_PACKAGE_ID_LENGTH) {
        "--package 过长（${packageId.length} > $MAX_PACKAGE_ID_LENGTH）：$packageId" +
            "（骨架派生的三语文案键「$packageId$LONGEST_STRINGS_KEY_SUFFIX」" +
            "必须落在运行期 ${ModStringsLimits.MAX_KEY_LENGTH} 字符上限内）"
    }
    return packageId
}

/** `--name` 是类名前缀：PascalCase 标识符，且不能撞 accessor 生成器保留的基类型名。 */
private fun requireClassName(raw: String): String {
    require(CLASS_NAME_PATTERN.matches(raw)) {
        "--name 必须是 PascalCase 标识符（如 My，不能含点、路径分隔符或空格）：$raw"
    }
    require(raw !in RESERVED_SCHEMA_BASES) {
        "--name 撞上 accessor 生成器保留的基类型名：$raw（换一个前缀）"
    }
    return raw
}

/** 模块根目录：不存在可以（写时建），存在则必须是目录；绝对化后作为后续所有路径的基准。 */
private fun validateOutputDir(raw: String): Path {
    val dir = Path.of(raw).toAbsolutePath().normalize()
    require(!Files.exists(dir) || Files.isDirectory(dir)) { "--out 已存在但不是目录：$dir" }
    return dir
}

/** 模块目录名（include 提示用）：必须是合法 Gradle 目录段。 */
private fun moduleNameOf(outputDir: Path): String {
    val name = requireNotNull(outputDir.fileName) { "--out 不能是文件系统根：$outputDir" }.toString()
    require(MODULE_NAME_PATTERN.matches(name)) { "模块目录名只允许字母数字与 . _ -，且不得以符号开头：$name" }
    return name
}

/**
 * 包身份正则，与 `app/src/main/kotlin/com/eratw/port/android/ModEnablement.kt` 的
 * `PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")` 同口径（tools 不能依赖 app）。
 */
private val PACKAGE_ID_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * packageId 长度上限 = 运行期三语文案键上限倒扣模板派生的最长后缀：
 * 骨架生成的键「packageId + [LONGEST_STRINGS_KEY_SUFFIX]」必须落进
 * [ModStringsLimits.MAX_KEY_LENGTH]（64 − 9 = 55）。
 */
private val MAX_PACKAGE_ID_LENGTH: Int =
    ModStringsLimits.MAX_KEY_LENGTH - LONGEST_STRINGS_KEY_SUFFIX.length

private val KOTLIN_SEGMENT_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val CLASS_NAME_PATTERN: Regex = Regex("[A-Z][A-Za-z0-9]*")

private val MODULE_NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

private val KOTLIN_KEYWORDS: Set<String> = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
    "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
    "init", "param", "property", "receiver", "set", "setparam", "where", "actual",
    "abstract", "annotation", "companion", "const", "crossinline", "data", "enum",
    "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
    "open", "operator", "out", "override", "private", "protected", "public", "reified",
    "sealed", "suspend", "tailrec", "vararg",
)

/** accessor 生成器对 schema `base` 的保留名（见 `AccessorEmit`），生成器不许与之撞名。 */
private val RESERVED_SCHEMA_BASES: Set<String> = setOf("EntityApi", "EntityId", "Long", "String", "Boolean")
