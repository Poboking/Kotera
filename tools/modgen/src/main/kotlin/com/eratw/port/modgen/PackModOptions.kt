package com.eratw.port.modgen

import java.io.File
import java.io.PrintStream

/** 作者编译好的代码产物 `.mod` 包（可选、只给一次）：整份字节作为 zip 根级 [CODE_MOD_ENTRY] 条目（ADR-013 D1）。 */
internal const val OPTION_CODE = "--code"

/** 代码入口类全限定名（与 [OPTION_CODE] 成对、只给一次）：写进内层清单并作为装载期入口。 */
internal const val OPTION_ENTRYPOINT = "--entrypoint"

/** stderr 文案前缀；拒绝/用法错/成功消息共用。 */
internal const val ERROR_PREFIX = "pack-mod: "

/** 长选项前缀。 */
private const val OPTION_PREFIX = "--"

/** 输出 zip 路径（必填、只给一次）。 */
private const val OPTION_OUT = "--out"

/** 追加的 schema 文件（可重复，透传给 ModCheck）。 */
private const val OPTION_SCHEMA = "--schema"

/** 作者声明的最低 mod-api 版本（可选、只给一次、N ≥ 1；0 = 不设闸，等同省略）。 */
private const val OPTION_MIN_API = "--min-api"

/** 作者自述资源目录名（可选、只给一次）：打包带上两棵旁挂资源树（ADR-014 S3）。 */
private const val OPTION_RESOURCES = "--resources"

/** 作者自述编译期 mod-api surface（可选、只给一次、N ≥ 0）：写进元数据可选 `compiledSurface` 键（ADR-013 D2）。 */
private const val OPTION_SURFACE = "--surface"

/** 解析出的全部选项；路径相对当前工作目录，落盘前统一绝对化。 */
internal class PackOptions(
    val projectDir: File,
    val output: File,
    val schemaFiles: List<File>,
    val minApi: Int,
    val resources: String?,
    val artifacts: PackArtifacts,
)

/**
 * 产物形状旋钮（都是「省缺 ⇒ 不写/不带」的可选项）。
 *
 * @param compiledSurface 作者声明的编译期 mod-api surface；null = 不写元数据 `compiledSurface` 键
 * @param code 作者编译好的代码产物包；null = 不写 zip 根级 `code.mod` 条目（包字节与不加参数逐字相同）
 * @param entrypoint 代码入口类全限定名；有 [code] 时必填（内层清单 `entrypoint` 键须与它逐字相等）
 */
internal class PackArtifacts(val compiledSurface: Int?, val code: File?, val entrypoint: String?)

/**
 * 命令行解析面：拆选项 / 判定次数与取值 / 组 [PackOptions]。
 *
 * 与打包编排分开只是为了让 [PackMod] 的函数数留在 detekt 的 `TooManyFunctions` 阈值内；判定口径
 * （文案与顺序）与合并前逐字相同：位置参数 → 未知选项 → `--out` → `--min-api` → `--resources`
 * → `--code` → `--surface`。
 */
internal object PackOptionParser {

    /**
     * 解析失败（含用法错误）返回 null，已打印原因与用法；调用方按退出码 2 处理。
     *
     * 取值以 [OPTION_PREFIX] 开头判用法错（`ModCheck.consumeSchema` 的同族先例）：本命令的全部
     * 选项都带取值、没有布尔旗标，下一个实参是选项即「本选项缺取值」——不判就会把后面的选项
     * 静默吃成本选项取值（`--out --json` 把 zip 写进名为 `--json` 的文件后报「成功」；
     * `--schema --json` 把旗标吃成 schema 路径）。路径取值以 `--` 开头属病态命名，与先例同判。
     */
    fun parse(args: Array<String>, err: PrintStream): PackOptions? {
        val positional = mutableListOf<String>()
        val flags = mutableMapOf<String, MutableList<String>>()
        var failure: String? = null
        var index = 0
        while (index < args.size && failure == null) {
            val arg = args[index]
            val value = args.getOrNull(index + 1)
            when {
                !arg.startsWith(OPTION_PREFIX) -> {
                    positional += arg
                    index += 1
                }
                value == null -> failure = "选项 $arg 缺少取值"
                value.startsWith(OPTION_PREFIX) ->
                    failure = "选项 $arg 缺少取值（下一个实参是选项：$value）"
                else -> {
                    flags.getOrPut(arg) { mutableListOf() } += value
                    index += 2
                }
            }
        }
        val message = failure ?: problem(positional, flags)
        return if (message == null) {
            buildOptions(positional, flags)
        } else {
            err.println(ERROR_PREFIX + message)
            printPackUsage(err)
            null
        }
    }

    /** 与解析循环分开的判定：逐项交给小函数，口径顺序见 [PackOptionParser] 的 KDoc。 */
    private fun problem(positional: List<String>, flags: Map<String, List<String>>): String? =
        positionalProblem(positional)
            ?: unknownProblem(flags)
            ?: outProblem(flags[OPTION_OUT])
            ?: minApiProblem(flags[OPTION_MIN_API])
            ?: repeatedProblem(flags[OPTION_RESOURCES], OPTION_RESOURCES)
            ?: repeatedProblem(flags[OPTION_CODE], OPTION_CODE)
            ?: repeatedProblem(flags[OPTION_ENTRYPOINT], OPTION_ENTRYPOINT)
            ?: entrypointProblem(flags[OPTION_ENTRYPOINT], flags[OPTION_CODE])
            ?: surfaceProblem(flags[OPTION_SURFACE])

    private fun positionalProblem(positional: List<String>): String? =
        if (positional.size == 1) null else "需要且只接受 1 个模块目录参数（收到 ${positional.size} 个）"

    private fun unknownProblem(flags: Map<String, List<String>>): String? {
        val known = setOf(
            OPTION_OUT,
            OPTION_SCHEMA,
            OPTION_MIN_API,
            OPTION_RESOURCES,
            OPTION_SURFACE,
            OPTION_CODE,
            OPTION_ENTRYPOINT,
        )
        val unknown = flags.keys.firstOrNull { it !in known }
        return if (unknown == null) null else "未知选项：$unknown"
    }

    private fun outProblem(values: List<String>?): String? = when {
        values == null -> "$OPTION_OUT <zip路径> 必填"
        values.size != 1 -> "$OPTION_OUT 只能给一次"
        else -> null
    }

    /** 取值闸：给一次且是 >= 0 的整数；过闸后 [buildOptions] 里的 toInt 不会抛。 */
    private fun minApiProblem(values: List<String>?): String? = when {
        values == null -> null
        values.size != 1 -> "$OPTION_MIN_API 只能给一次"
        values.single().toIntOrNull()?.let { it >= 0 } == true -> null
        else -> "$OPTION_MIN_API <N> 取值必须是 >= 0 的整数"
    }

    /** 只保证「给了一次」；目录名形态在 pack() 里判（属「已拒绝」档，退出码 1）。 */
    private fun repeatedProblem(values: List<String>?, name: String): String? =
        if (values != null && values.size != 1) "$name 只能给一次" else null

    /**
     * `--entrypoint` 与 `--code` 的配对闸（两个方向都拒，避免悬空声明/无入口包）：
     * 给了代码包却没给入口类名，或入口名不是 `<小写包名>.<大写类名>` 形态，一律按用法错拒。
     * 清单内 `entrypoint` 与这里逐字相等的判据在 [PackModCode] 里（内容面，属「已拒绝」档）。
     */
    private fun entrypointProblem(entrypoint: List<String>?, code: List<String>?): String? = when {
        entrypoint == null && code != null ->
            "$OPTION_CODE 需要同时给 $OPTION_ENTRYPOINT <fqn>（内层清单 entrypoint 是必填键）"
        entrypoint == null -> null
        code == null -> "$OPTION_ENTRYPOINT 只有给了 $OPTION_CODE 时才允许"
        entrypoint.single().matches(CODE_ENTRYPOINT_SHAPE) -> null
        else -> "$OPTION_ENTRYPOINT <fqn> 取值必须是 <小写包名>.<大写类名>（收到 ${entrypoint.single()}）"
    }

    /** `--surface` 的出现次数与取值判定（拆出来压 [problem] 的圈复杂度）。 */
    private fun surfaceProblem(values: List<String>?): String? = when {
        values == null -> null
        values.size != 1 -> "$OPTION_SURFACE 只能给一次"
        values.single().toIntOrNull()?.let { PackModMeta.compiledSurfaceAccepted(it) } == true -> null
        else -> "$OPTION_SURFACE <N> 取值必须是 0..$PACK_META_MAX_COMPILED_SURFACE 的整数"
    }

    /** 全部闸都过了才构造：这里不做任何再校验，否则错误文案会走两条路。 */
    private fun buildOptions(positional: List<String>, flags: Map<String, List<String>>): PackOptions =
        PackOptions(
            projectDir = File(positional.single()),
            output = File(flags.getValue(OPTION_OUT).single()),
            schemaFiles = flags[OPTION_SCHEMA].orEmpty().map { File(it) },
            minApi = flags[OPTION_MIN_API]?.single()?.toInt() ?: 0,
            resources = flags[OPTION_RESOURCES]?.single(),
            artifacts = PackArtifacts(
                compiledSurface = flags[OPTION_SURFACE]?.single()?.toInt(),
                // 路径存在性/普通文件在 pack() 里 require（用法错，退出码 2）；内容校验在 writePackage。
                code = flags[OPTION_CODE]?.single()?.let { File(it) },
                entrypoint = flags[OPTION_ENTRYPOINT]?.single(),
            ),
        )
}

/** CLI 用法文本（stderr 打印；退出码含义见 [PackMod] 的 KDoc）。 */
internal fun printPackUsage(err: PrintStream) {
    err.println(
        "用法: modgen pack-mod <模块目录> --out <zip路径> [--schema <schema文件>]... " +
            "[--min-api <N>] [--resources <目录名>] [--surface <N>] [--code <文件> --entrypoint <fqn>]",
    )
    err.println("  先跑 check-mod 全套：有 ERROR 拒绝打包（退出码 1），只 WARN 允许并打印。")
    err.println("  打包内容：递归全部 .ERB/.ERH（排除 build/、.git/）+ <packageId>.assets/ 下的资源。")
    err.println("  输出确定：条目按路径字典序、时间戳固定 1980-01-01、不写目录条目，同输入两次打包字节相同。")
    err.println("  --min-api <N>：声明本包要求的最低 mod-api 版本（N ≥ 1），写进包元数据的 minApi 键；")
    err.println("    省缺或 0 = 不设闸（不写该键，元数据字节与老包逐字相同）；装配期低于 N 的引擎整链拒载。")
    err.println("  --resources <目录名>：带上旁挂资源树 resources/mod/<目录名>/（图/文本）与")
    err.println("    sound/mod/<目录名>/（音频），zip 内路径保持游戏根一致；两棵树存在时元数据追加")
    err.println("    resources 字段（在 minApi 之后）。目录名须与包身份同源")
    err.println("    （[A-Za-z0-9][A-Za-z0-9._-]*，≤ $MAX_MEDIA_DIR_CHARS 字符；ERB 是 Shift-JIS，非 ASCII 写不出来）；")
    err.println("    图/文只 png/jpg/jpeg/webp/txt（禁 csv），音频只 mp3/ogg/wav；超限/非法名拒绝并点名。")
    err.println("  --surface <N>：声明本包编译于哪个 mod-api surface（N ≥ 0），写进包元数据的 compiledSurface 键；")
    err.println("    省缺 = 不写该键（元数据字节与老包逐字相同）；引擎 API 更旧的导入端在 dex 装载前拒载。")
    err.println("  --code <文件>：把作者编译好的 .mod 包（含 classes.dex + manifest.json 的 zip）整份字节")
    err.println("    作为 zip 根级固定名条目 code.mod 打进包；省缺 = 不写该条目（包字节与不加参数逐字相同）。")
    err.println("    校验：≤ $MAX_CODE_MOD_BYTES 字节、合法 zip、含非空 classes.dex 与 ≤")
    err.println("    $MAX_CODE_MOD_MANIFEST_BYTES 字节的 manifest.json（其 packageId 须等于模块 PACKAGE_ID）；")
    err.println("    内容不合格拒绝（退出码 1），--code 路径不存在/不是普通文件按用法错（退出码 2）。")
    err.println("  --entrypoint <fqn>：代码入口类全限定名，必须与 --code 成对出现（只给 --code 或只给 --entrypoint 都按用法错）；")
    err.println("    形态 = <小写包名>.<大写类名>；内层清单 manifest.json 的 entrypoint 键须与它逐字相等，")
    err.println("    缺键/形态非法/与参数不符拒绝（退出码 1）。")
    err.println("  安全：--out 落在模块目录内拒绝；条目名必须是合法相对路径（无 ..、无绝对路径、无反斜杠、无控制字符）。")
    val mib = PackLimits.BYTES_PER_MIB
    err.println(
        "  导入端限额：条目数 ${PackLimits.MAX_ENTRIES}、单条目 ${PackLimits.MAX_ENTRY_BYTES / mib} MiB、" +
            "整包 ${PackLimits.MAX_TOTAL_BYTES / mib} MiB、压缩比 ${PackLimits.MAX_COMPRESSION_RATIO} 倍" +
            "（解压 >${PackLimits.RATIO_MIN_BYTES / mib} MiB 才判）；超限拒绝并点名。",
    )
    err.println("  退出码: 0 成功 / 1 已拒绝（check-mod ERROR、条目名非法、超导入端限额、无 ERB 源）/ 2 用法或 I/O 错误。")
}
