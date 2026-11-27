package com.eratw.port.modgen

import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.system.exitProcess

/** check-mod 的发现级别：ERROR 让命令非零退出，WARN 只提示。 */
enum class Severity { ERROR, WARN }

/**
 * 一条检查发现：相对 mod 根的 `文件:行` 定位 + 人读消息。
 *
 * [line] 为空表示整文件级发现（缺文件、无法解析等）。
 */
data class Finding(val severity: Severity, val file: String, val line: Int?, val message: String)

/**
 * `modgen check-mod <mod目录>`：mod 作者自检「schema TSV ↔ 生成物 ↔ Declarations/manifest」三面一致。
 *
 * 为什么放在 modgen 而不是 accessors：三面里有两面（Declarations/manifest、Keys 字段键）只有
 * modgen 侧看得见；生成物那一面复用 [com.eratw.port.modgen.accessors.AccessorEmit] 与
 * [com.eratw.port.modgen.accessors.parseSchema]，生成逻辑仍然只有一份真源。
 *
 * 资源面（`<packageId>.assets/`）的静态校验在 [ModCheckResources]：纯文件系统检查，与上述三面
 * 独立，目录不存在时静默跳过。图片元素声明面（`modUiImage` / `ModUiElement.Image`）的
 * 「声明 ↔ 资源文件」一致性在 [ModCheckImages]：只读源码文本抽字面量，抽不到就静默通过。
 * mod 自带三语文案面（`declare` 声明自检 + `strings.of` / `strings.has` 用了但没声明）在
 * [ModCheckStrings]：同样只读源码文本，抽不到字面量就整体静默（宁可漏报，不许假红）；
 * 键 / 文本上限常量在 [ModStringsLimits]，与 mod-api 源码同值，漂移由测试文本哨兵兜底。
 * 音频播放声明面（`audio.play("...")` 的名字 ↔ 资源文件）在 [ModCheckAudio]：与图片面同一套
 * 「名字非法 ERROR / 缺文件 WARN / 抽不到静默」的严重度划分。
 * 依赖声明面（v12 `EraMod.requires`）在 [ModCheckRequires]：源码文本抽 listOf 字面量，核对包名
 * 形态、重复、自依赖与静态可解析的成环；与运行期 `ModDependencyPlan` 的装载判据同口径。
 * 设置声明面（v5 `intSetting` / `strSetting` / `boolSetting` / `enumSetting`）在 [ModCheckSettings]：
 * 声明键与 `settings` 读取键双向对拍（键形态 / 重复声明 / int range 反向 / enum 空候选 / 用了未声明 /
 * 声明了没读）；同样只读源码文本，实参抽不到字面量或常量就相关判据静默。
 * 作者面版本登记面（`ModApi` 的 `VERSION` ↔ KDoc「当前取值」版本表）在 [ModCheckApiSurface]：
 * 只读 mod-api 源码文本，`VERSION` 与版本表最大版本号不一致 / 版本表点名不存在的符号 /
 * `ModContext` 属性没登记都报 ERROR；mod-api 目录缺失或抽不到就整体静默（宁可漏报，不许假红）。
 * min-api 声明面（载体源码 `override val minApi: Int = <字面量>`）在 [ModCheckMinApi]：负数报
 * ERROR（装配期 `require(minApi >= 0)` 必抛）、高于当前 `ModApi.VERSION` 报 WARN（要等引擎升到
 * 该版本才能装载）；上限从 mod-api 源码抽，抽不到字面量或真源就整面静默。
 * 旁挂资源树面（`resources/mod/<dir>/` 与 `sound/mod/<dir>/`）在 [ModCheckMediaResources]：
 * 目录名合法性、子树是否存在/为空、扩展名白名单、三档限额预览全部复用 pack-mod 的单一真源
 * 判据（[PackModResources]）；`--resources <dir>` 与 pack-mod 同名同语义，用于核对包元数据
 * 声明值 ↔ 实际目录名。CLI 面默认开；pack-mod 调 [check] 时传 `includeMediaResources = false`
 * 走它自己的 `PackUnsafe` 通道，保证打包输出逐字不变。
 */
object ModCheck {
    private const val EXIT_USAGE = 2

    private val USAGE: String = """
        |Usage: modgen check-mod <mod dir> [--schema <schema.tsv>]... [--resources <dir>] [--json]
        |                   [--changed-only] [--no-cache] [--fix | --dry-run]
        |
        |检查一个 mod 模块的三面一致性：
        |  1. schemas 目录下的 schema TSV 与 src/generated 下的生成物是否同步；
        |  2. 声明里的 intKey/strKey/refKey 字段键与 schema 字段是否双向齐全；
        |  3. @ModCarrier 的 PACKAGE_ID 与 packageId/manifest.version 是否自洽；
        |  4. schema 的 component/archetype 名字是否都出现在 Declarations 的调用里；
        |  5. src/generated 里多出来的生成物（没有 schema 对号）报 WARN，不算 ERROR；
        |  6. EraMod 的 apiVersion/dataVersion/onMigrate 版本一致性。
        |  7. 资源面 <packageId>.assets/：目录来源前缀（.assets/ 之前那段目录名）不是导入端可接受的
        |     单段名（PACKAGE_ID 为空白串 / . / .. / 含分隔符或盘符冒号）报 ERROR —— 导入端会把该目录
        |     每条资源 WARN 跳过；非法资源名/单文件超 4 MiB/单包超 16 MiB 报 ERROR，
        |     资源目录为空报 WARN，目录不存在不报（资源面可选）。
        |  8. 图片元素声明面：源码里 modUiImage("...") / ModUiElement.Image(resourceName = "...")
        |     的字面量必须在 <packageId>.assets/ 下有同名文件，缺文件报 WARN（缺图会降级成
        |     altText/占位框，属设计允许），名字非法报 ERROR；抽不到字面量就静默通过。
        |  9. 三语文案面（v10 strings）：声明侧四条全报 ERROR —— 键形态非法（空/超 64 字符/非
        |     [a-z0-9_.-]）、同一键重复声明（运行期后值静默覆盖前值）；缺 en/ja 文案、单条文本
        |     超 512 字符（运行期 requireText 是 require，声明阶段当场抛 ⇒ 装配失败、mod 载不
        |     进来，必然失效）。读取侧 strings.of("键") / strings.has("键") 用了、但本模块
        |     declare("键", ...) 没声明的键报 WARN（of() 未声明时原样回键，不报错）。
        |     声明或读取抽不到字面量（变量/插值）就静默，宁可漏报不许假红；上限常量见
        |     ModStringsLimits（与 mod-api StringsSpec 同值，文本哨兵兜底）。
        | 10. 音频播放声明面：源码里 audio.play("...") 的字面量必须在 <packageId>.assets/
        |     下有同名文件，缺文件报 WARN（播放失败只回 false + WARN，不崩），名字非法报 ERROR；
        |     抽不到字面量（变量/插值）就静默，宁可漏报不许假红。
        | 11. 依赖声明面（v12 EraMod.requires）：源码里 override val requires ... = listOf("...")
        |     （也认 : List<String> get() = listOf(...)）的包名逐个核对 —— 形态非法报 ERROR；
        |     同一处重复报 WARN；自依赖 / 静态可解析的成环（如 A→B→A）报 ERROR；本模块、同目录
        |     mod-* 兄弟模块与本仓内置载体（各 mod-* 模块 Declarations.kt 的 const val PACKAGE_ID，
        |     共 7 个）里都没有的包报 WARN（只有内置载体与同仓模块才是静态面能确定的，第三方依赖
        |     可能另外装）；抽不到字面量就静默。
        | 12. 设置声明面（v5 intSetting/strSetting/boolSetting/enumSetting）：声明键与源码里
        |     settings.int/str/bool/enumName/has 读取键双向对拍 —— 键形态非法 / enum 候选为空 /
        |     int range 下界>上界报 ERROR；同一键重复声明 / 用了未声明 / 声明了但全模块无读取报 WARN；
        |     实参抽不到字面量（变量/插值）或用了 setting(IntSettingDecl(...)) 形式就整体静默。
        | 13. 作者面版本登记面（ModApi.VERSION）：VERSION 与 mod-api 的 KDoc「当前取值」版本表最大
        |     版本号不一致报 ERROR；版本表方括号链接点名的符号在 mod-api 源码里不存在报 ERROR；
        |     ModContext 属性（Identity 属性 packageId 除外）没在版本表点名报 ERROR；抽不到真源就静默。
        | 14. min-api 声明面：载体源码里 override val minApi: Int = <字面量> 的负数报 ERROR（装配期
        |     ModuleDescriptor 的 require(minApi >= 0) 当场抛，mod 载不进来，必然失效）；高于当前
        |     ModApi.VERSION 报 WARN（要等引擎升到声明版本才能装载，属预留，故只 WARN）；
        |     minApi = 0（不设闸）与等于 VERSION 都不报；抽不到字面量（变量/表达式/拼接/getter）
        |     就整面静默，宁可漏报不许假红。上限值从 mod-api 源码的 const val VERSION 抽、不写死；
        |     与 pack-mod --min-api 的元数据是否一致属「源码 × 命令行」两面，静态面判不了，不检查。
        | 15. 旁挂资源树面（--resources <dir>，与 pack-mod 同名同语义）：声明了 <dir> 但
        |     resources/mod/<dir>/ 与 sound/mod/<dir>/ 都不存在、实际目录名与声明不一致、目录名
        |     非 ASCII（ERB 源码是 Shift-JIS，写不出这种字面量）报 ERROR；树存在但为空、扩展名
        |     越界（resources 允许 png/jpg/jpeg/webp/txt，sound 允许 mp3/ogg/wav）、单文件超
        |     4/8 MiB、单树合计超 16/32 MiB、两树合计超 48 MiB 报 ERROR；用量 >80% 报 WARN 预览；
        |     没给 --resources 时按实际目录审计，只 WARN 提示「没声明就不会被打进包」。
        |     判据与 pack-mod 单一真源（PackModResources），白名单/限额不会两头漂移。
        | 16. 资源字面量面：从载体源码的 `context.resources.exists/open/size("…")`、
        |     `context.ui.registerImage("…")` 与模块根 ERB 的 `GCREATEFROMFILE`/`LOADTEXT`/`PLAYSOUND`
        |     首实参抽字面量，对照真实运行期根判存在性 —— 治「作者以为资源随包了、玩家看到空白/黑框而
        |     零提示」。Kotlin 侧对照 `<packageId>.assets/`，缺文件 WARN（与图片声明面同口径）、名字非法
        |     ERROR；ERB 侧对照 `resources/mod/<dir>/`、`sound/mod/<dir>/`，只认本包 `<dir>` 内的字面量
        |     （外部路径静默），缺文件 ERROR 且消息尾带 RESOURCE_LITERAL_MISSING 标记；树整体缺失只汇总
        |     一条 WARN；变量/插值/拼接抽不到就静默（宁可漏报不许假红）。`--json` 形状不变，仍是四键 finding。
        | 17. 写授权声明面（v20 writeGrants）：源码里 writeGrant("KEY", ...) 的字面量逐条核对
        |     —— 同一键重复声明（大写归一后判）报 ERROR（声明构造期 IAE，整包构造失败，必然
        |     失效）；同一键被本模块与同目录 mod-* 兄弟模块同时声明报 WARN（跨包授权重叠，运行
        |     期不加闸不拒包，双方都能写同一原版变量 —— 建议作者互相确认）；实参抽不到字面量
        |     （变量/插值）就静默，宁可漏报不许假红。
        | 18. 读侧声明提示面（ModDX4-B1 静态提示）：源码里 gameState.getIntVar/getStrVar/
        |     hasIntVar/hasStrVar 的字符串字面量键，若不在本包声明域（global-dims 的
        |     GlobalKey("...") 字面量声明 ∪ writeGrant 字面量）里出现报 WARN —— 读不受影响
        |     （生产读面是全量按名只读视图，不做运行期拦截），提示的是「该键在声明面之外：
        |     若还打算经 gameState.setIntVar/setStrVar 写它，必须声明 writeGrant，否则运行期
        |     当场 IAE」；抽不到字面量（变量/插值）就静默，宁可漏报不许假红。
        | 19. 代码入口面（[ModCheckCodeEntry]）：模块有代码产物面（code/ 目录 / code.mod）时，
        |     代码产物的内层 manifest.json 必须有字符串 entrypoint，且与源码声明的入口类
        |     `class <Name> ... : EraMod` 的 FQN 一致：缺内层清单或缺 entrypoint 报 ERROR
        |     CODE_ENTRYPOINT_MISSING；形态非法或与源码入口类对不上报 ERROR CODE_ENTRYPOINT_MISMATCH。
        |     只有源码入口类、没有代码产物（new-mod 骨架常态）静默，没有代码面的老模块也静默（向后兼容）。
        |
        |--schema <file>  显式指定 schema TSV（可重复；缺省扫描 <mod>/schemas）
        |                 同一模块有多份 schema 时须一次传齐：字段键按并集核对。
        |--json           把结果输出成单份 JSON（供 CI / 编辑器 / 打包脚本消费；schema 版本见
        |                 ModCheckJson.SCHEMA），与 --human（等价于缺省人类可读模式）互斥。
        |                 带 --json 时 stdout 只有这一份 JSON，用法/IO 错误仍只剩 stderr。
        |--changed-only   增量模式：先算「本包输入指纹」（工具版本 + 本模块全部输入文件 + schema TSV +
        |                 仓库级共享输入 mod-api/mod-* 兄弟模块），指纹与上次一致、且上次结论 0 ERROR
        |                 时跳过重算，只打印一行 `check-mod <pkg>: up to date (cached)`；
        |                 否则全量重算并把结论记到 <mod>/build/modcheck/cache.json（已在 gitignore 的
        |                 build/ 下，不进仓库、可随手删）。error 结论永不缓存 —— 每次必重算报出；
        |                 缓存坏 / 版本不符 / 不可写一律静默回退全量。缺省关闭（= 全量，输出逐字不变）。
        |                 与 --json 同用：命中时仍只输出一份稳定 JSON（findings 为空，不加缓存字段）。
        |--no-cache       不读也不写缓存，强制全量（配 --changed-only 用于临时绕过坏缓存）。
        |--fix            自动重建「生成物与 schema 不一致」这一类 ERROR：按 schema 重跑
        |                 AccessorEmit.emit，写同目录临时文件 + ATOMIC_MOVE 原子覆盖生成物，
        |                 写后回读逐字节校验（不一致即判修失败、目标保持原样）。只修这一类；
        |                 schema 解析错、声明缺 packageId/version、carrier 三方不一致、资源/
        |                 媒体清单问题只报不修（改的是作者源码或真实资源，自动改写会掩盖配置
        |                 错误，见 ModCheckFix 的 KDoc）。生成物缺失（首建）不算 fix：落点约定
        |                 属模块自己的 generate 任务，check-mod 不凭空造文件。修完仍有 ERROR
        |                 ⇒ 退出 1，全部清静 ⇒ 0。与 --dry-run 互斥；总是全量（不读也不写
        |                 --changed-only 缓存，避免缓存命中吞掉待修项）。
        |--dry-run        只演练 --fix 会改哪些生成物：打印 / 进 JSON 的 fixes[] 标 WOULD_FIX，
        |                 不写盘；退出码仍按「当前发现」折算（过期生成物还是 ERROR ⇒ 1）。
        |--resources <dir> 声明包元数据里的旁挂资源目录名（与 pack-mod --resources 同一取值），
        |                 用于核对 resources/mod/<dir>/ 与 sound/mod/<dir>/ 的真实目录是否对得上；
        |                 缺省不给时只按实际目录做审计（不判「声明缺失」，只 WARN 提示）。
        |退出码：0 = 无 ERROR，1 = 有 ERROR，2 = 用法或 IO 问题。
    """.trimMargin()

    /**
     * 检查 [projectDir] 指向的 mod 模块，返回全部发现（按 文件/行 排序）。
     *
     * [schemaFiles] 为空时按缺省布局扫描 `<mod>/schemas`；非空时只认显式给出的 TSV —— 这是给
     * 「schema 住在别处、生成物入库」的既有载体模块（family/memory/livingworld）留的口子。
     *
     * [resourcesDir] 是包元数据 `resources` 的声明值（= `pack-mod --resources` 的取值）；给了就
     * 核对声明目录 ↔ 实际 `resources/mod/<dir>`、`sound/mod/<dir>` 是否对得上，不给则按实际目录
     * 做审计。`pack-mod` 自己已有同一批判据与 `PackUnsafe` 合同，调本函数时传
     * [includeMediaResources] = false，避免重复报 ERROR 改变打包输出。
     *
     * 本函数**永远全量**；`--changed-only` 的缓存命中在 [ModCheckRun.incremental] 里、于调用
     * 本函数之前短路，所以 pack-mod 与所有既有调用方的语义不受缓存影响。
     */
    fun check(
        projectDir: File,
        schemaFiles: List<File> = emptyList(),
        resourcesDir: String? = null,
        includeMediaResources: Boolean = true,
    ): List<Finding> {
        require(projectDir.isDirectory) { "mod directory not found: $projectDir" }
        val root = projectDir.toPath().toAbsolutePath().normalize()
        val findings = mutableListOf<Finding>()
        val sources = ModCheckSources.scan(root, findings)
        val schemas = ModCheckSchemas.load(root, schemaFiles, findings)
        ModCheckSchemas.checkGenerated(root, schemas, sources, findings)
        ModCheckSchemas.checkKeys(schemas, sources, findings)
        ModCheckDeclarations.check(schemas, sources, findings)
        ModCheckVersion.check(sources, findings)
        ModCheckResources.check(root, sources, findings)
        ModCheckImages.check(root, sources, findings)
        ModCheckStrings.check(sources, findings)
        ModCheckAudio.check(root, sources, findings)
        ModCheckRequires.check(root, sources, findings)
        ModCheckSettings.check(sources, findings)
        ModCheckApiSurface.check(root, findings)
        ModCheckMinApi.check(root, sources, findings)
        ModCheckCodeEntry.check(root, sources, findings)
        if (includeMediaResources) {
            ModCheckMediaResources.check(root, resourcesDir, findings)
            ModCheckResourceLiterals.check(root, sources, resourcesDir, findings)
        }
        ModCheckWriteGrants.checkDeclarations(root, sources, findings)
        ModCheckWriteGrants.checkReads(sources, findings)
        return findings.sortedWith(compareBy({ it.file }, { it.line ?: 0 }))
    }

    /** `modgen check-mod` 的进程入口（分派见 ManifestBuild.kt 的顶层 main）。 */
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(runCli(args))
    }

    /**
     * CLI 入口（进程内测试直接调它）：用法/IO 问题折算成退出码 2。
     *
     * 输出模式（互斥）：
     * - 缺省或显式 `--human`：stdout 每行一条 `文件:行: 级别: 消息`，末尾一行 summary；
     * - `--json`：stdout 只写一份 [ModCheckJson.SCHEMA] 版机器可读文档，不写 summary 行。
     *
     * 退出码语义两种模式一致：0 = 无 ERROR，1 = 有 ERROR；用法/IO 错误两种模式都只写 stderr
     * 并退 2（`--json` 不把用法错误伪装成一份「检查结果」文档，stdout 为空）。
     *
     * `--changed-only` 命中缓存时人类可读模式只多一行 `check-mod <pkg>: up to date (cached)`；
     * `--json` 模式仍然只输出一份稳定的空报告（缓存状态不进 stdout，避免污染 JSON 契约）。
     */
    fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int =
        try {
            dispatch(args, out, err)
        } catch (error: IllegalArgumentException) {
            err.println("check-mod: ${error.message}")
            EXIT_USAGE
        } catch (error: IOException) {
            err.println("check-mod: ${error.message}")
            EXIT_USAGE
        }

    private fun dispatch(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val options = parseOptions(args, err) ?: return EXIT_USAGE
        val cacheable = options.fixMode == FixMode.OFF && options.changedOnly
        return if (cacheable) ModCheckRun.incremental(options, out) else ModCheckRun.full(options, out)
    }

    private fun parseOptions(args: Array<String>, err: PrintStream): ModCheckOptions? {
        val projectDirs = mutableListOf<File>()
        val schemaFiles = mutableListOf<File>()
        val resourcesDirs = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val mode = Mode()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val flag = consumeFlag(arg, mode)
            index += if (flag > 0) {
                flag
            } else {
                when (arg) {
                    "--schema" -> consumeSchema(args.getOrNull(index + 1), schemaFiles, problems)
                    "--resources" -> consumeResources(args.getOrNull(index + 1), resourcesDirs, problems)
                    else -> consumeOther(arg, projectDirs, problems)
                }
            }
        }
        mode.validate(projectDirs, problems)
        if (problems.isNotEmpty() || projectDirs.isEmpty()) {
            problems.forEach { err.println(it) }
            err.println(USAGE)
            return null
        }
        return ModCheckOptions(
            projectDir = projectDirs.single(),
            schemaFiles = schemaFiles,
            json = mode.json,
            changedOnly = mode.changedOnly && !mode.noCache,
            resourcesDir = resourcesDirs.singleOrNull(),
            fixMode = mode.fixMode(),
        )
    }

    /** 用法面校验集中一处：命中的条目进 [problems]，由调用方统一打 stderr + USAGE。 */
    /** 布尔开关统一收口：返回吃掉的实参数个数（0 = 不是本组开关，交回 [parseOptions] 的 when 继续判）。 */
    private fun consumeFlag(arg: String, mode: Mode): Int = when (arg) {
        "--json" -> {
            mode.json = true
            1
        }
        "--human" -> {
            mode.human = true
            1
        }
        "--changed-only", "--incremental" -> {
            mode.changedOnly = true
            1
        }
        "--no-cache" -> {
            mode.noCache = true
            1
        }
        "--fix" -> {
            mode.fix = true
            1
        }
        "--dry-run" -> {
            mode.dryRun = true
            1
        }
        else -> 0
    }

    /** 非选项实参：第一个是 `<mod dir>`，其余是用法错误。 */
    private fun consumeOther(arg: String, projectDirs: MutableList<File>, problems: MutableList<String>): Int =
        when {
            arg.startsWith("--") -> {
                problems += "unknown option: $arg"
                1
            }
            projectDirs.isEmpty() -> {
                projectDirs += File(arg)
                1
            }
            else -> {
                problems += "unexpected extra argument: $arg"
                1
            }
        }

    /** `--resources <dir>` 的参数消费：返回吃掉几个实参；重复给是用法错误（包元数据只有一个字段）。 */
    private fun consumeResources(
        next: String?,
        resourcesDirs: MutableList<String>,
        problems: MutableList<String>,
    ): Int =
        when {
            next == null -> {
                problems += "--resources needs a directory name"
                1
            }
            next.startsWith("--") -> {
                problems += "--resources needs a directory name（下一个实参是选项：$next）"
                1
            }
            else -> {
                resourcesDirs += next
                if (resourcesDirs.size > 1) problems += "--resources 只能给一次（包元数据只有一个 resources 字段）"
                2
            }
        }

    /** `--schema <file>` 的参数消费：返回吃掉几个实参。[problems] 收「缺路径/实参是选项」用法错误。 */
    private fun consumeSchema(next: String?, schemaFiles: MutableList<File>, problems: MutableList<String>): Int =
        if (next == null) {
            problems += "--schema needs a path"
            1
        } else if (next.startsWith("--")) {
            problems += "--schema needs a path（下一个实参是选项：$next）"
            1
        } else {
            schemaFiles += File(next)
            2
        }

    /** findings 的单行格式；`pack-mod` 也复用同一格式原样打印（宽 1 词暴露到 internal）。 */
    internal fun format(finding: Finding): String {
        val location = finding.line?.let { "${finding.file}:$it" } ?: finding.file
        return "$location: ${finding.severity}: ${finding.message}"
    }

    /** 布尔开关收集器：把「填字段 + 吃 1 个实参」从 [parseOptions] 的 when 里挪出来，压住圈复杂度。 */
    private class Mode {
        var json = false
        var human = false
        var changedOnly = false
        var noCache = false
        var fix = false
        var dryRun = false

        /** 用法面校验集中一处：命中的条目进 [problems]，由调用方统一打 stderr + USAGE。 */
        fun validate(projectDirs: List<File>, problems: MutableList<String>) {
            if (projectDirs.isEmpty()) problems += "missing <mod dir>"
            if (json && human) problems += "--json and --human are mutually exclusive"
            if (fix && dryRun) problems += "--fix and --dry-run are mutually exclusive"
        }

        /** `--fix` / `--dry-run` / 都不给 三态折叠；两者同给已在 [parseOptions] 里判用法错误。 */
        fun fixMode(): FixMode = when {
            fix -> FixMode.FIX
            dryRun -> FixMode.DRY_RUN
            else -> FixMode.OFF
        }
    }
}

/** `check-mod` 的修复模式三态：缺省普通检查、`--fix` 真写、`--dry-run` 只演练。 */
internal enum class FixMode { OFF, FIX, DRY_RUN }

/**
 * 解析后的 CLI 选项；[changedOnly] 只影响缓存层（`--changed-only` 且未 `--no-cache` 时为 true，
 * 其余组合一律全量），检查语义仍由 [ModCheck.check] 全量决定。[fixMode] 非 [FixMode.OFF] 时
 * 由 [ModCheckRun.full] 走修复面（缓存层整段绕过）。
 */
internal class ModCheckOptions(
    val projectDir: File,
    val schemaFiles: List<File>,
    val json: Boolean,
    val changedOnly: Boolean,
    val resourcesDir: String?,
    val fixMode: FixMode = FixMode.OFF,
)
/**
 * `check-mod` 的写授权声明面（ModDX4-B1：v20 `writeGrant` 声明对拍 + 读侧静态提示）。
 *
 * 与 [ModCheckRequires] 同一套文本抽取纪律：只认字符串字面量，实参是变量/插值就静默
 * （宁可漏报，不许假红）；声明与兄弟模块对拍的扫描范围 = 同目录 `mod-*`（同 [ModCheckRequires]
 * 的兄弟面口径，静态面看不到玩家安装集合，重叠只 WARN 不拒包 —— 运行期冲突语义按裁定
 * 「不加闸，与 ERB 写同权」，重叠只是作者间的协同提醒）。
 *
 * 严重度（与运行期后果对齐）：
 * - 同一键重复声明（大写归一后判）= ERROR：原文同形的重复 `ModManifest` 构造期 require
 *   直接抛 IAE（WriteGrantDeclTest 钉死），大小写异形的重复在装载相位被 core
 *   `ModuleDescriptor` 的归一判重拒载 —— 两种形态 mod 都载不进来，必然失效；
 * - 跨包授权重叠 = WARN：两个包都拿到同一原版键的写权不违反任何运行期闸（后写者胜出），
 *   但通常意味着作者没对齐，报 WARN 让人看一眼；
 * - 读未声明键 = WARN（提示面，非拦截面）：生产读面是全量按名只读视图，读不报错；提示
 *   「想写必须声明 writeGrant」这一条作者最容易踩的运行期 IAE。
 */
internal object ModCheckWriteGrants {

    private const val FIRST_GROUP = 1

    private const val SECOND_GROUP = 2

    private const val SIBLING_PREFIX = "mod-"

    /**
     * `writeGrant("KEY", ...)` 的字面量抽取：组 1 = 键字面量，组 2 = 其余实参原文（用于判
     * `DimType.STR`）。实参带 `$` 插值 / 变量键抽不到，按漏报跳过（正则只认双引号字面量）。
     */
    private val WRITE_GRANT = Regex("""\bwriteGrant\s*\(\s*"([^"\\$]*)"\s*(?:,\s*([^)\n]*))?""")

    /**
     * `GlobalKey("KEY")` 的字面量抽取（global-dims 声明与键定义共用的构造形态）：组 1 = 键
     * 字面量。生成物里的键常量也长这样（accessor 生成），一并算「本包声明域」成员。
     */
    private val GLOBAL_KEY = Regex("""\bGlobalKey\s*\(\s*"([^"\\$]*)"""")

    /**
     * `gameState.getIntVar("KEY")` 族读取的抽取：组 1 = 方法名，组 2 = 键字面量。
     * 四个只读方法（getIntVar/getStrVar/hasIntVar/hasStrVar）都在内；写方法不在此列
     * （写了未声明键走运行期 IAE，声明面由 16 的 writeGrant 对拍兜）。
     */
    private val GAME_STATE_READ = Regex(
        """\bgameState\s*\.\s*(getIntVar|getStrVar|hasIntVar|hasStrVar)\s*\(\s*"([^"\\$]*)"""",
    )

    /** 一处声明的定位与载荷：键 + 归一键 + file/line。 */
    private class KeyUse(val key: String, val canonical: String, val file: String, val line: Int)

    /** 声明面（16）：重复声明 ERROR + 跨包重叠 WARN。 */
    fun checkDeclarations(root: java.nio.file.Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val own = grantUses(sources)
        if (own.isEmpty()) return
        // 重复声明（大写归一后判）：声明构造期 require 抛 IAE ⇒ 整包构造失败，ERROR。
        val seen = HashMap<String, KeyUse>()
        for (use in own) {
            val first = seen.put(use.canonical, use)
            if (first != null) {
                findings += Finding(
                    severity = Severity.ERROR,
                    file = use.file,
                    line = use.line,
                    message = "writeGrant 键重复声明：${use.key}（大写归一后与 ${first.file}:${first.line} " +
                        "重复；原文同形 ModManifest 构造期 require 抛，大小写异形在装载相位被 core " +
                        "ModuleDescriptor 归一判重拒载 —— 两种形态 mod 都载不进来，必然失效）",
                )
            }
        }
        // 跨包重叠（WARN，不拒包）：同一键被兄弟模块也声明了。运行期不加闸，双方都写同一
        // 原版变量按边界内兑现序后写者胜出；静态面只做协同提醒。
        val siblingGrants = siblingGrantKeys(root)
        if (siblingGrants.isEmpty()) return
        val reported = HashSet<String>()
        for (use in own) {
            val siblings = siblingGrants[use.canonical] ?: continue
            if (reported.add(use.canonical)) {
                findings += Finding(
                    severity = Severity.WARN,
                    file = use.file,
                    line = use.line,
                    message = "writeGrant 跨包重叠：${use.key} 也在 ${siblings.joinToString(", ")} 声明了写授权" +
                        "（运行期不加闸：双方都可写同一原版变量，按转移边界兑现序后写者胜出；不拒包，建议作者互相确认）",
                )
            }
        }
    }

    /** 读侧提示面（17）：读未声明键 WARN（提示，非拦截）。 */
    fun checkReads(sources: List<SourceFile>, findings: MutableList<Finding>) {
        val reads = readUses(sources)
        if (reads.isEmpty()) return
        val covered = grantUses(sources).map { it.canonical }.toSet() +
            sources.flatMap { source ->
                val text = source.code.joinToString("\n") { line -> line.text }
                GLOBAL_KEY.findAll(text).map { match ->
                    match.groupValues[FIRST_GROUP].trim().uppercase(java.util.Locale.ROOT)
                }
            }
        val reported = HashSet<String>()
        for (use in reads) {
            if (use.canonical in covered) continue
            if (reported.add(use.canonical)) {
                findings += Finding(
                    severity = Severity.WARN,
                    file = use.file,
                    line = use.line,
                    message = "读未声明键：${use.key}（gameState.${use.member} 用到了它，但它不在本包声明域 " +
                        "global-dims / write-grants 里。读不受影响：生产读面是全量按名只读视图，不做运行期拦截；" +
                        "若还打算经 gameState.setIntVar / setStrVar 写它，必须声明 writeGrant，否则运行期当场 IAE）",
                )
            }
        }
    }

    /**
     * 抽取本模块的 writeGrant 声明。组 2（同行的其余实参原文）当前零消费，留给未来扩展；
     * 丢弃条件只有「键字面量为空」一条 —— 键里带 `$` 或反斜杠时正则整条抽不到（等同未声明），
     * 而跨行实参只是组 2 为空，条目仍保留。
     */
    private fun grantUses(sources: List<SourceFile>): List<KeyUse> = sources.flatMap { source ->
        val text = source.code.joinToString("\n") { line -> line.text }
        WRITE_GRANT.findAll(text).mapNotNull { match ->
            val key = match.groupValues[FIRST_GROUP]
            if (key.isBlank()) return@mapNotNull null
            KeyUse(
                key = key,
                canonical = key.trim().uppercase(java.util.Locale.ROOT),
                file = source.label,
                line = lineOf(text, match.range.first),
            )
        }.toList()
    }

    /** 抽取本模块的 gameState 只读调用（组 1 = 方法名，组 2 = 键字面量）。 */
    private fun readUses(sources: List<SourceFile>): List<ReadUse> = sources.flatMap { source ->
        val text = source.code.joinToString("\n") { line -> line.text }
        GAME_STATE_READ.findAll(text).mapNotNull { match ->
            val key = match.groupValues[SECOND_GROUP]
            if (key.isBlank()) return@mapNotNull null
            ReadUse(
                key = key,
                canonical = key.trim().uppercase(java.util.Locale.ROOT),
                file = source.label,
                line = lineOf(text, match.range.first),
                member = match.groupValues[FIRST_GROUP],
            )
        }.toList()
    }

    /** 一处读取调用：键 + 定位 + 方法名（告警文案点名用）。 */
    private class ReadUse(
        val key: String,
        val canonical: String,
        val file: String,
        val line: Int,
        val member: String,
    )

    /** 兄弟模块的 grant 键 → 包名清单（无 packageId 的模块用目录名兜底，便于告警点名）。 */
    private fun siblingGrantKeys(root: java.nio.file.Path): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (sibling in siblingModules(root)) {
            val findings = mutableListOf<Finding>()
            val sources = ModCheckSources.scan(sibling, findings)
            val keys = grantUses(sources).map { use -> use.canonical }
            if (keys.isEmpty()) continue
            val owner = ModCheckResources.packageIds(sources).singleOrNull()
                ?: sibling.fileName.toString()
            for (key in keys.distinct()) out.getOrPut(key) { mutableListOf() }.add(owner)
        }
        return out
    }

    /** 同目录下的 `mod-*` 兄弟模块（不含自己）；读不到目录就静默当没有。 */
    private fun siblingModules(root: java.nio.file.Path): List<java.nio.file.Path> {
        val parent = root.parent ?: return emptyList()
        return try {
            java.nio.file.Files.newDirectoryStream(parent, "$SIBLING_PREFIX*").use { entries ->
                entries.filter { entry ->
                    java.nio.file.Files.isDirectory(entry) && entry != root
                }.sortedBy { entry -> entry.fileName.toString() }
            }
        } catch (_: java.io.IOException) {
            emptyList()
        }
    }

    /** [text] 里 [offset] 处的 1 基行号（text 是去注释行用换行连回的产物）。 */
    private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1
}
