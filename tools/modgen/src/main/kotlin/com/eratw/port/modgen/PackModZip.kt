package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** pack-mod 收集到的一个 zip 条目：zip 内相对名 + 磁盘上的真源文件。 */
internal class PackEntry(val name: String, val path: Path)

/**
 * 打包器生成的内存条目（来源不是模块目录下的文件）：本波只有包级元数据 [PACK_META_ENTRY_NAME]。
 * 与 [PackEntry] 同一个写入口、同一套固定时间戳口径，只是内容来自字节串。
 */
internal class GeneratedEntry(val name: String, val bytes: ByteArray)

/** pack-mod 排除掉的一条磁盘文件 + 原因（摘要按原因聚合计数，不逐个刷屏）。 */
internal class PackSkip(val label: String, val reason: String)

/**
 * pack-mod 拒收的一条条目名 + 判据 + 改法。
 *
 * 打包闸的口径是「导入端收得下」而不是「路径看起来安全」：被拒的条目导入时会被静默跳过
 * （资源面 WARN 丢条目）或让整包导入报错（源面 STORAGE），所以拒绝消息必须点名条目、判据与改法，
 * 作者才能照着改，不用猜。
 */
internal class PackUnsafe(val name: String, val reason: String, val hint: String)

/** pack-mod 的收集计划：条目（已按名排序）、源/资源计数、跳过清单、拒收清单。 */
internal class PackPlan(
    val entries: List<PackEntry>,
    val sourceCount: Int,
    val resourceCount: Int,
    val skipped: List<PackSkip>,
    val unsafe: List<PackUnsafe>,
) {
    /** 只取名字（摘要与既有用例用）。 */
    val unsafeNames: List<String> get() = unsafe.map { it.name }
}

/** pack-mod 的写盘结果：输出路径 + 整份 zip 字节的 sha256（供两次打包对拍）。 */
internal class PackedZip(val output: Path, val sha256Hex: String)

/**
 * 成包结果：完整 zip 字节 + 只有成包后才能判的拒收项（压缩比）。
 *
 * 打包器本来就把整份 zip 算在内存里再落盘（[PackModZip.write] 也只收这段字节），所以
 * 「成包后校验」不需要临时文件，也**不会留下半成品**：超限时 [PackModZip.write] 根本不会被调用，
 * 输出路径保持原状。代价只是压缩比判据延后到成包之后——拒绝时已经付过一次构建 zip 的 CPU，
 * 但不会多写一次磁盘。
 */
internal class PackBuild(val bytes: ByteArray, val unsafe: List<PackUnsafe>)

/** 源文件扩展名（比较时大小写不敏感，与 platform 的 `ModStore.ERB_EXTENSION` 同口径）。 */
private const val ERB_EXTENSION = ".ERB"

/** 口上头文件扩展名（同上）。 */
private const val ERH_EXTENSION = ".ERH"

/** DOS 时间下限的年份；月/日/时/分/秒都是 1/1/0/0/0（0 与 1 不是魔术数）。 */
private const val DOS_EPOCH_YEAR = 1980

/** 固定条目时间戳：DOS 时间能表达的最小值。用 setTimeLocal 写本地字段，换时区不改字节。 */
private val FIXED_ENTRY_TIMESTAMP: LocalDateTime = LocalDateTime.of(DOS_EPOCH_YEAR, 1, 1, 0, 0, 0)

/** 十六进制基数。 */
private const val HEX_RADIX = 16

/** 一个字节的十六进制位数。 */
private const val HEX_DIGITS_PER_BYTE = 2

/** SHA-256 算法名。 */
private const val SHA_256 = "SHA-256"

/**
 * `pack-mod` 的收集与写盘面：只读模块目录，产出一份**字节可复现**的 zip。
 *
 * 条目分两类，与导入端的合同逐字对齐：
 * - **源**：模块目录下递归的全部 `.ERB`/`.ERH`，zip 内路径 = 相对模块根的 `/` 分隔路径。
 *   导入端解包只留这两类扩展名（`ModImportController.kt:1016-1017` 的 `ModZip.unpack`），
 *   所以其余文件（Kotlin/schema/gradle 等构建面）一律不进包，按原因计入「跳过」。
 * - **资源**：`<packageId>.assets/` 下的文件，zip 内路径 = `<packageId>.assets/<相对路径>`，
 *   前缀口径同导入端 `ModImportAssets`（`ASSET_DIR_SUFFIX` 与 `packageId + ".assets/"` 精确前缀）。
 *   非本模块的 `.assets` 目录（导入端同样按前缀不符跳过并 WARN）整目录跳过并计数。
 *
 * ## 确定性（同输入两次打包字节完全相同）
 *
 * 1. 条目按 zip 内路径字典序写入（[collect] 末尾 `sortedBy`）；
 * 2. 时间戳固定为 DOS 时间下限 1980-01-01 00:00:00，用 [ZipEntry.setTimeLocal] 写**本地字段**，
 *    不落任何扩展时间戳字段 —— 换时区也不改字节；
 * 3. 不写目录条目（空目录自然不出现），压缩级别固定 [Deflater.BEST_COMPRESSION]；
 * 4. 摘要里打印整份字节的 sha256，两次打包直接对拍。
 *
 * ## 安全
 *
 * 打包闸 = **导入端收得下**，不是「路径看起来安全」。[isSafeEntryName] 复用运行期资源名谓词
 * [isLegalResourceName]（`ModCheckResources.kt:126`）：只许 `/` 分隔的**相对**路径，禁空名、
 * 前导/尾随斜杠、空段、`.`/`..` 段、反斜杠、控制字符、盘符冒号。
 *
 * 但整名合法 ≠ 导入端收得下，导入端还有两道与整名无关的闸，所以收集面另加两条判据
 * （对照表与差异结论写在 `PackModImportContractTest` 的类 KDoc）：
 * - **资源条目**（[resourceUnsafeReason]）：`ModImportAssets.collectFromZip` 只认
 *   `<来源目录名>.assets/<相对路径>` —— 首个 `.assets/` 必须在开头之后、前缀是合法单段名、
 *   其后相对名合法。`PACKAGE_ID` 为空白串 / `.` / `..` 时整名仍合法（`.assets/x`、
 *   `..assets/x`、`...assets/x`），但导入端前缀为空 / `.` / `..` 段 ⇒ 该目录每条资源都被 WARN 跳过。
 * - **源条目**（[sourceUnsafeReason]）：导入端按**主名**落盘 `mods/` 并过 `ModStore.validateName`
 *   （`platform/.../ModStore.kt:353-367`），主名含 `..`、Windows 保留字符、超长、首尾空白都会
 *   抛错 ⇒ 整包导入报 STORAGE 失败。modgen 不依赖 `:platform`（构建工具独立），按
 *   [ModResourceLimits] 的既有先例在构建期复制规则，用文本哨兵测试盯住真源。
 * - **导入端限额**（[PackLimits]）：条目数 / 单条目 / 整包解压量在 [collect] 判，压缩比在
 *   [build] 成包后判（分母是 zip 字节数）。与 `ModZipGuard` 同源：常量在 modgen 侧声明，
 *   由 [PackModLimitsSentinelTest] 读 app 侧源码文本比对，任一方向漂移即红。
 *
 * 两段对拍而非同进程 A/B：`app` 测试模块看不见 `tools:modgen` 的 `internal`（`PackModZip` 是
 * `internal object`，且 app 的 build 脚本不在本波写面，不能加测试依赖），所以「pack 侧纯函数单测 +
 * app 侧夹具 zip 走真导入」各自断言同一张对照表。
 */
internal object PackModZip {

    /** 跳过原因：构建输出目录下的文件。 */
    private const val REASON_BUILD = "构建输出目录（build/）"

    /** 跳过原因：版本库元数据目录下的文件。 */
    private const val REASON_GIT = "版本库目录（.git/）"

    /** 跳过原因：`.assets` 结尾但不是本模块 packageId 的目录（导入端同样按前缀不符跳过）。 */
    private const val REASON_FOREIGN_ASSETS = "非本模块资源前缀（.assets/）"

    /** 跳过原因：构建面文件（Kotlin/schema/gradle 等），zip 只收 ERB 源与包资源。 */
    private const val REASON_BUILD_ONLY = "模块构建面文件（只打包 ERB 源与包资源）"

    /** 拒收改法：资源面。 */
    private const val RESOURCE_HINT =
        "把 PACKAGE_ID 改成非空合法包名（如 eratw.mymod）并让资源目录名跟着变，或删掉该资源目录"

    /** 拒收改法：源面。 */
    private const val SOURCE_HINT =
        "改文件名：去掉 ..、Windows 保留字符（* ? \" < > |），长度 ≤128，别以空白开头/结尾"

    /** 导入端资源条目标记（`ModImportAssets.collectFromZip` 的 `marker`）。 */
    private const val IMPORT_ASSET_MARKER = ModResourceLimits.ASSET_DIR_SUFFIX + "/"

    /** 导入端落盘文件名长度上限（`ModStore.MAX_NAME_CHARS`）。 */
    private const val MAX_MOD_FILE_NAME_CHARS = 128

    /** 导入端落盘文件名禁用字符：`ModStore.FORBIDDEN_CHARS` 里 [isLegalResourceName] 不管的那几个。 */
    private val MOD_FILE_NAME_FORBIDDEN_CHARS = charArrayOf('*', '?', '"', '<', '>', '|')

    /** 导入端文件名里的双点（`ModStore.validateName` 任意位置拒）。 */
    private const val MOD_FILE_NAME_DOUBLE_DOT = ".."

    /** zip 条目名合法性：与运行期资源名同一条谓词，规则只有一份。 */
    fun isSafeEntryName(name: String): Boolean = isLegalResourceName(name)

    /**
     * 资源条目在导入端被拒的原因（null = 收得下）。与 `ModImportAssets.collectFromZip` 同形：
     * 首个 `.assets/` 必须在开头之后、前缀是合法单段名、其后相对名过 [isLegalResourceName]。
     */
    fun resourceUnsafeReason(name: String): String? {
        val markerAt = name.indexOf(IMPORT_ASSET_MARKER)
        val prefix = if (markerAt > 0) name.substring(0, markerAt) else ""
        val relative = if (markerAt > 0) name.substring(markerAt + IMPORT_ASSET_MARKER.length) else ""
        return when {
            markerAt <= 0 -> "资源条目必须以「<来源目录名>.assets/」开头，来源目录名不能为空"
            !isImportSourcePrefix(prefix) ->
                "来源目录名「$prefix」不是导入端可接受的单段名（空 / 点段 / 含路径分隔符 / 控制字符 / 冒号）"
            !isLegalResourceName(relative) ->
                "相对路径「$relative」非法（前导/尾随斜杠、空段、点段与 .. 段、反斜杠、控制字符、盘符冒号）"
            else -> null
        }
    }

    /**
     * 源条目在导入端被拒的原因（null = 收得下）：整名过 [isSafeEntryName]（zip 形态），且**主名**
     * （最后一个 `/` 之后，导入端按它落盘 `mods/`）过 `ModStore.validateName` 的文件名合同。
     */
    fun sourceUnsafeReason(name: String): String? {
        val diskName = name.substringAfterLast('/')
        return when {
            !isSafeEntryName(name) ->
                "路径非法（前导/尾随斜杠、空段、点段与 .. 段、反斜杠、控制字符、盘符冒号）"
            diskName.contains(MOD_FILE_NAME_DOUBLE_DOT) ->
                "主名「$diskName」含 ..，导入端 ModStore 会拒（文件名里没有正当用途）"
            diskName.any { char -> char in MOD_FILE_NAME_FORBIDDEN_CHARS } ->
                "主名「$diskName」含 Windows 保留字符（* ? \" < > |），导入端 ModStore 会拒"
            diskName.length > MAX_MOD_FILE_NAME_CHARS ->
                "主名「$diskName」超过 $MAX_MOD_FILE_NAME_CHARS 字符，导入端 ModStore 会拒"
            diskName.first().isWhitespace() || diskName.last().isWhitespace() ->
                "主名「$diskName」首/尾是空白，导入端 ModStore 会拒"
            else -> null
        }
    }

    /**
     * 收集 [root] 下的源与资源，并在收集阶段跑三道不需要压缩体积的导入端闸
     * （[PackLimits]：条目数 / 单条目 / 整包解压量）。[packageIds] 来自源码里的
     * `const val PACKAGE_ID` 字面量（[ModCheckResources.packageIds]）；为空时不收集资源面，
     * 全部 `.assets` 目录按外来前缀跳过。
     *
     * [generated] 是打包器自己写的条目（本波只有包元数据，0 或 1 条）：guard 数整包全部条目，
     * 所以它们必须计入条目数 / 单条目 / 总量账本，但**不进** [PackPlan.entries]（摘要的
     * 「条目 N」仍只描述模块内容）。
     *
     * 目录名与声明的 PACKAGE_ID 一个都不匹配时，按 [ModResourceDirConsistency.mismatch] 的同源判据
     * 记 unsafe（check-mod 侧同一判据已报 ERROR；这里纵深防御，直调 collect 也不出坏包）。
     *
     * [resourcesDir] 是 `pack-mod --resources <dir>` 的作者自述目录名（ADR-014 S3）：非 null 时把两棵
     * 旁挂树 [PackModResources] 的文件一并收成条目（zip 内路径 = 游戏根相对路径），它们计入
     * [PackPlan.resourceCount] 与导入端条目/体积预算；树内文件不再按「构建面文件」重复记跳过。
     */
    fun collect(
        root: Path,
        packageIds: List<String>,
        generated: List<GeneratedEntry> = emptyList(),
        resourcesDir: String? = null,
    ): PackPlan {
        val skips = mutableListOf<PackSkip>()
        val unsafe = mutableListOf<PackUnsafe>()
        val existingAssetDirNames = ModResourceDirConsistency.assetDirectoryNames(root)
        ModResourceDirConsistency.mismatch(existingAssetDirNames, packageIds)?.let { reason ->
            existingAssetDirNames.forEach { dir -> unsafe += PackUnsafe(dir + "/", reason, RESOURCE_HINT) }
        }
        val assetsDirs = packageIds
            .map { packageId -> root.resolve(packageId + ModResourceLimits.ASSET_DIR_SUFFIX) }
            .filter { Files.isDirectory(it) }
        val sidecar = if (resourcesDir == null) null else PackModResources.collect(root, resourcesDir, unsafe)
        val sidecarDirs = if (resourcesDir == null) {
            emptyList()
        } else {
            PackModResources.treeRoots(root, resourcesDir).map { root.relativize(it) }
        }
        val resources = assetsDirs.flatMap { dir -> collectResources(dir, unsafe) } + sidecar?.entries.orEmpty()
        val sources = collectSources(root, assetsDirs.map { root.relativize(it) } + sidecarDirs, skips, unsafe)
        val entries = (sources + resources).sortedBy { it.name }
        unsafe += PackLimits.budgetUnsafe(entries, generated)
        return PackPlan(
            entries = entries,
            sourceCount = sources.size,
            resourceCount = resources.size,
            skipped = skips,
            unsafe = unsafe,
        )
    }

    /** 资源条目：`<packageId>.assets/` 目录名原样当前缀，其余相对路径原样；按导入端条目谓词拒收。 */
    private fun collectResources(dir: Path, unsafe: MutableList<PackUnsafe>): List<PackEntry> {
        val prefix = dir.fileName.toString() + "/"
        val entries = mutableListOf<PackEntry>()
        walkFiles(dir).forEach { path ->
            val name = prefix + ModCheckSources.relativeLabel(dir, path)
            val reason = resourceUnsafeReason(name)
            if (reason == null) entries += PackEntry(name, path) else unsafe += PackUnsafe(name, reason, RESOURCE_HINT)
        }
        return entries
    }

    /**
     * 源条目 + 跳过记账。本模块资源目录下的文件交给 [collectResources]（不重复计数）；
     * `build/`、`.git/`、外来 `.assets` 目录与构建面文件按原因进 [skips]。
     */
    private fun collectSources(
        root: Path,
        assetsDirs: List<Path>,
        skips: MutableList<PackSkip>,
        unsafe: MutableList<PackUnsafe>,
    ): List<PackEntry> {
        val entries = mutableListOf<PackEntry>()
        walkFiles(root).forEach { path ->
            val relative = root.relativize(path)
            val label = ModCheckSources.relativeLabel(root, path)
            val fileName = path.fileName.toString()
            val source = isNamedExtension(fileName, ERB_EXTENSION) || isNamedExtension(fileName, ERH_EXTENSION)
            when {
                assetsDirs.any { dir -> relative.startsWith(dir) } -> Unit
                hasExcludedSegment(relative) -> {
                    val reason = if (relative.any { segment -> segment.toString() == GIT_DIR }) {
                        REASON_GIT
                    } else {
                        REASON_BUILD
                    }
                    skips += PackSkip(label, reason)
                }
                hasAssetSegment(relative) -> skips += PackSkip(label, REASON_FOREIGN_ASSETS)
                source -> {
                    val reason = sourceUnsafeReason(label)
                    if (reason == null) {
                        entries += PackEntry(label, path)
                    } else {
                        unsafe += PackUnsafe(label, reason, SOURCE_HINT)
                    }
                }
                else -> skips += PackSkip(label, REASON_BUILD_ONLY)
            }
        }
        return entries
    }

    /** 无目录条目的递归文件清单（不跟符号链接目录，与 Files.walk 缺省口径一致）。 */
    private fun walkFiles(root: Path): List<Path> {
        val stream = Files.walk(root)
        return try {
            stream.filter { Files.isRegularFile(it) }.toList()
        } finally {
            stream.close()
        }
    }

    /** 扩展名判定：大小写不敏感、主名必须非空（`ModStore.hasErbExtension` 同口径）。 */
    /**
     * 成包：模块文件（[entries]）与生成内容（[generated]，本波只有包级元数据）一起按名排序
     * 写进内存 zip，随后跑**成包后**才能算的压缩比闸（[PackLimits.ratioUnsafe]）。
     *
     * 生成内容不走磁盘临时文件——它是确定的字节串，落盘只会引入第二个可失败的相位。
     * 收集阶段的 [PackPlan.entries] 保持「只有源与资源」：条目计数/跳过统计仍只描述模块内容，
     * 元数据是打包器的自述，不属于模块素材（但条目数/体积预算把它计入，见 [collect]）。
     */
    fun build(entries: List<PackEntry>, generated: List<GeneratedEntry> = emptyList()): PackBuild {
        val bytes = zipBytes(entries, generated)
        val totalBytes = entries.sumOf { Files.size(it.path) } + generated.sumOf { it.bytes.size.toLong() }
        val ratio = PackLimits.ratioUnsafe(totalBytes, bytes.size.toLong())
        return PackBuild(bytes, ratio?.let { listOf(it) }.orEmpty())
    }

    /**
     * 把已成包并过完全部闸的字节落盘：父目录不存在则建。只写 [bytes]，不再重算 zip，
     * 因此「成包后拒收」路径不会产生任何半成品文件（[PackMod] 只在 unsafe 为空时调本函数）。
     */
    fun write(output: Path, bytes: ByteArray): PackedZip {
        val parent = output.parent
        if (parent != null) Files.createDirectories(parent)
        Files.write(output, bytes)
        return PackedZip(output, sha256Hex(bytes))
    }

    private fun zipBytes(entries: List<PackEntry>, generated: List<GeneratedEntry>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink, Charsets.UTF_8).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            val named = entries.map { entry -> entry.name to entry } +
                generated.map { entry -> entry.name to entry }
            named.sortedBy { (name, _) -> name }.forEach { (_, item) ->
                when (item) {
                    is PackEntry -> zip.putFixedTimestampEntry(item)
                    is GeneratedEntry -> zip.putFixedTimestampEntry(item)
                }
            }
        }
        return sink.toByteArray()
    }
}

/** 任何深度都排除的目录名：构建输出。 */
private const val BUILD_DIR = "build"

/** 任何深度都排除的目录名：版本库元数据。 */
private const val GIT_DIR = ".git"

/** 路径任一段等于排除目录名（`build` / `.git` 出现在任何深度都排除）。 */
private fun hasExcludedSegment(relative: Path): Boolean =
    relative.any { segment -> segment.toString() == BUILD_DIR || segment.toString() == GIT_DIR }

/** 扩展名判定：大小写不敏感、主名必须非空。 */
private fun isNamedExtension(name: String, extension: String): Boolean {
    val start = name.length - extension.length
    return start > 0 && name.regionMatches(start, extension, 0, extension.length, ignoreCase = true)
}

/** 路径任一段是 `.assets` 目录：本模块的已由资源面收集，其余按外来前缀整目录跳过。 */
private fun hasAssetSegment(relative: Path): Boolean =
    relative.any { segment -> segment.toString().endsWith(ModResourceLimits.ASSET_DIR_SUFFIX) }

/** 写一个条目：固定时间戳、无扩展字段、无目录条目（见 [PackModZip] 的确定性说明）。 */
private fun ZipOutputStream.putFixedTimestampEntry(entry: PackEntry) {
    val zipEntry = ZipEntry(entry.name)
    zipEntry.setTimeLocal(FIXED_ENTRY_TIMESTAMP)
    putNextEntry(zipEntry)
    Files.copy(entry.path, this)
    closeEntry()
}

/** 写一个生成的条目（内容在内存里，来源不是模块目录下的文件；时间戳/字段口径同上）。 */
private fun ZipOutputStream.putFixedTimestampEntry(entry: GeneratedEntry) {
    val zipEntry = ZipEntry(entry.name)
    zipEntry.setTimeLocal(FIXED_ENTRY_TIMESTAMP)
    putNextEntry(zipEntry)
    write(entry.bytes)
    closeEntry()
}

/** 整份字节的 sha256（小写 hex）；不依赖默认 Locale。 */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance(SHA_256).digest(bytes)
    return digest.joinToString("") { byte ->
        byte.toUByte().toString(HEX_RADIX).padStart(HEX_DIGITS_PER_BYTE, '0')
    }
}
