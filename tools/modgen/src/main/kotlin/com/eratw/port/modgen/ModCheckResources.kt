package com.eratw.port.modgen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `check-mod` 的资源面自检：扫 `<mod>/<packageId>.assets/`，按运行期 `ModResourceStore` 的规则
 * 做静态校验。
 *
 * 校验项与严重度：
 * - 资源目录的**来源前缀**（`.assets/` 之前那段目录名）不是导入端可接受的单段名 = ERROR
 *   （导入端 `ModImportAssets.collectFromZip` 会把这个目录下**每一条**资源 WARN 跳过，资源全丢）；
 * - 模块根下存在 `.assets` 目录、却没有任何一个目录名等于声明的 `<PACKAGE_ID>.assets` = ERROR
 *   （打包/导入两端都按 PACKAGE_ID 定位资源前缀，目录名对不上就整目录跳过，资源全丢）；
 * - 非法资源名（前导/尾随 `/`、`//` 空段、`.`/`..` 段、反斜杠、控制字符、盘符冒号）= ERROR；
 * - 单文件超过 [ModResourceLimits.MAX_RESOURCE_FILE_BYTES] = ERROR；
 * - 单包累计超过 [ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES] = ERROR；
 * - 资源目录存在但为空 = WARN（资源面可选）；
 * - 资源目录不存在 = 什么都不报。
 *
 * 只读不写盘。发现沿用 [Finding] 形状（资源文件没有行号，`line` 为 null），`file` 是相对 mod 根
 * 的 `/` 分隔标签。packageId 从 `const val PACKAGE_ID` 字面量取；一个模块有多个载体时逐个目录检查。
 */
internal object ModCheckResources {

    private const val FIRST_GROUP = 1

    private val PACKAGE_ID_LITERAL = Regex("""const val PACKAGE_ID: String = "([^"]*)"""")

    /** 对 [sources] 里每个 `PACKAGE_ID` 对应的资源目录做校验，发现追加进 [findings]。 */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val ids = packageIds(sources)
        val assetDirs = ModResourceDirConsistency.assetDirectoryNames(root)
        ModResourceDirConsistency.mismatch(assetDirs, ids)?.let { message ->
            findings += Finding(Severity.ERROR, assetDirs.first(), null, message)
        }
        ids.forEach { packageId -> checkPackage(root, packageId, findings) }
    }

    /** 源码里出现的全部 `PACKAGE_ID` 字面量（去重排序）；图片声明面也用这份清单定位资源目录。 */
    internal fun packageIds(sources: List<SourceFile>): List<String> =
        sources.flatMap { source ->
            source.code.flatMap { line ->
                PACKAGE_ID_LITERAL.findAll(line.text).map { it.groupValues[FIRST_GROUP] }
            }
        }.distinct().sorted()

    /**
     * 单个包的资源目录校验。目录不存在直接跳过（资源面可选）；存在但为空只 WARN；其余逐条发现。
     * 用 `when` 收掉三个出口（detekt 的 ReturnCount 默认上限 2）。
     */
    private fun checkPackage(root: Path, packageId: String, findings: MutableList<Finding>) {
        val dir = root.resolve(packageId + ModResourceLimits.ASSET_DIR_SUFFIX)
        if (Files.exists(dir)) {
            val label = ModCheckSources.relativeLabel(root, dir)
            val prefix = dir.fileName.toString().removeSuffix(ModResourceLimits.ASSET_DIR_SUFFIX)
            when {
                !Files.isDirectory(dir) -> {
                    findings += Finding(Severity.ERROR, label, null, "资源路径不是目录：$label")
                }
                !isImportSourcePrefix(prefix) -> {
                    findings += Finding(Severity.ERROR, label, null, importPrefixMessage(prefix, label))
                }
                else -> checkAssetDir(root, dir, label, findings)
            }
        }
    }

    /**
     * 前缀非法 = 导入端会把该目录下**每一条**资源 WARN 跳过（`resourceEntryInvalidNameFormat`），
     * 整包资源静默失效 ⇒ ERROR（不是「可能」而是确定性失效，故不算假红）。
     */
    private fun importPrefixMessage(prefix: String, label: String): String =
        "资源目录前缀非法：$label（来源目录名「$prefix」）—— 导入端只认「<来源目录名>.assets/」，" +
            "且来源目录名须为非空单段名（非 . / ..、无路径分隔符/控制字符/盘符冒号）；" +
            "该目录下每条资源都会被导入端跳过并 WARN。把 const val PACKAGE_ID 改成合法包名" +
            "（如 eratw.mymod）并同步重命名目录。"

    private fun checkAssetDir(root: Path, dir: Path, label: String, findings: MutableList<Finding>) {
        val entries = collect(dir, label, findings)
        when {
            entries == null -> Unit
            entries.isEmpty() -> {
                val message = "资源目录为空：$label（资源面可选；不需要就删掉该目录）"
                findings += Finding(Severity.WARN, label, null, message)
            }
            else -> {
                entries.forEach { path -> addEntryFinding(root, dir, path, findings) }
                checkTotal(root, dir, entries, findings)
            }
        }
    }

    /** 目录下的全部条目（不含目录本身）；读取失败时 WARN 并返回 null（已记账，调用方不再重复报）。 */
    private fun collect(dir: Path, label: String, findings: MutableList<Finding>): List<Path>? {
        val stream = Files.walk(dir)
        return try {
            stream.filter { it != dir }.toList().sorted()
        } catch (error: IOException) {
            findings += Finding(Severity.WARN, label, null, "资源目录读取失败：$label（${error.message}）")
            null
        } finally {
            stream.close()
        }
    }

    private fun addEntryFinding(root: Path, dir: Path, path: Path, findings: MutableList<Finding>) {
        val name = assetName(dir, path)
        val label = ModCheckSources.relativeLabel(root, path)
        when {
            !isLegalResourceName(name) -> {
                val message = "非法资源名：$name（只许 / 分隔的相对路径；禁前导/尾随斜杠、空段、" +
                    "点段与 .. 段、反斜杠、控制字符与盘符冒号）"
                findings += Finding(Severity.ERROR, label, null, message)
            }
            Files.isRegularFile(path) && sizeOrZero(path) > ModResourceLimits.MAX_RESOURCE_FILE_BYTES -> {
                val size = sizeOrZero(path)
                val message = "资源文件超过单文件上限：$name（$size > ${ModResourceLimits.MAX_RESOURCE_FILE_BYTES}）"
                findings += Finding(Severity.ERROR, label, null, message)
            }
        }
    }

    private fun checkTotal(root: Path, dir: Path, entries: List<Path>, findings: MutableList<Finding>) {
        val total = entries.filter { Files.isRegularFile(it) }.sumOf { sizeOrZero(it) }
        if (total > ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES) {
            val label = ModCheckSources.relativeLabel(root, dir)
            val message = "资源总量超过单包上限：$label（$total > ${ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES}）"
            findings += Finding(Severity.ERROR, label, null, message)
        }
    }

    private fun assetName(dir: Path, path: Path): String = dir.relativize(path).toString().replace('\\', '/')

    private fun sizeOrZero(path: Path): Long =
        try {
            Files.size(path)
        } catch (_: IOException) {
            0L
        }
}

/**
 * 运行期资源名的静态校验（与 `ModResourceStore` 的 `isValidResourceName` 同口径）：
 * 只许 `/` 分隔的相对路径；禁空名、前导/尾随 `/`、空段、`.` 段、`..` 段、反斜杠、控制字符、
 * 盘符冒号。磁盘上的名字不可能出现 `..`/前导斜杠，这里仍按同一口径校验，保证 check-mod 与
 * 运行期不漂移。
 */
internal fun isLegalResourceName(name: String): Boolean =
    name.isNotEmpty() &&
        !name.contains('\\') &&
        !name.startsWith('/') &&
        !name.endsWith('/') &&
        name.split('/').all(::isLegalSegment)

/**
 * 导入端 `ModImportAssets.collectFromZip` 对资源条目**来源目录名**（首个 `.assets/` 之前那段）的判定：
 * 非空、不含路径分隔符、是合法单段名（非 `.`/`..`、无控制字符与盘符冒号）。
 *
 * 不满足时该目录下每一条资源都会被导入端 WARN 跳过（`resourceEntryInvalidNameFormat`），运行期收不到。
 */
internal fun isImportSourcePrefix(prefix: String): Boolean =
    prefix.isNotEmpty() && !prefix.contains('/') && !prefix.contains('\\') && isLegalSegment(prefix)

/** 旁挂资源树目录名（`resources/mod/<dir>/`、`sound/mod/<dir>/`）的长度上限。 */
internal const val MAX_MEDIA_DIR_CHARS = 64

/**
 * 旁挂资源树目录名（ADR-014 `<dir>`）的判据：**与包身份同源**（[ModCheckRequires.PACKAGE_ID_PATTERN]）
 * 且长度 ≤ [MAX_MEDIA_DIR_CHARS]。
 *
 * 为什么不直接用 [isImportSourcePrefix]：后者忠于运行期导入端（允许任意可打印单段名，含空格与非
 * ASCII），而 `<dir>` 还要被作者**手写进 ERB 源码字面量**（`GCREATEFROMFILE "mod/<dir>/a.png"`、
 * `PLAYSOUND "mod/<dir>/se.mp3"`）。ERB 源码是 Shift-JIS：中文/emoji/全角空格在源文件里写不出来
 * 或会被编码破坏 ⇒ 允许非 ASCII 只会给出一个作者用不了的目录名，还给导入端与 check-mod 添歧义。
 * 收紧后本函数同时是 check-mod 侧（S6）复核包内自述 `resources` 字段的判据，两处自动同源。
 */
internal fun isMediaDirName(name: String): Boolean =
    name.length <= MAX_MEDIA_DIR_CHARS && ModCheckRequires.PACKAGE_ID_PATTERN.matches(name)

private fun isLegalSegment(segment: String): Boolean =
    segment.isNotEmpty() && segment != "." && segment != ".." && segment.none(::isForbiddenResourceChar)

private fun isForbiddenResourceChar(char: Char): Boolean =
    char.code < FIRST_PRINTABLE_CHAR_CODE || char.code == DELETE_CHAR_CODE || char == ':'

/** 第一个可打印字符（空格）的码位；低于它的控制字符在资源名里非法。 */
private const val FIRST_PRINTABLE_CHAR_CODE = 0x20

/** ASCII DEL 的码位；资源名里非法。 */
private const val DELETE_CHAR_CODE = 0x7F
