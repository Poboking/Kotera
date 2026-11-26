package com.eratw.port.modgen

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * `check-mod --changed-only` 的输入指纹。
 *
 * 指纹 = SHA-256（工具版本 + 资源目录声明 + 本模块全部输入文件 + 仓库级共享输入）。任一输入
 * 变了指纹就不同，[ModCheckCache] 随之失效重算；因此**只往失效方向偏**：多算一份输入只是
 * 多跑一次全量，少算一份才可能把新错误藏进旧缓存。
 *
 * 本模块输入覆盖检查面真正读到的三棵树：
 * 1. schema TSV：缺省扫 `<mod>/schemas` 目录下的全部 schema TSV，或用显式 `--schema` 给的那批；
 * 2. `<mod>` 根下除构建/版本库目录外的全部文件：`src/main/kotlin`（Declarations/Mod 源）、
 *    `src/generated`（生成物）、模块根 ERB、`<packageId>.assets/`、旁挂 `resources/mod` 与
 *    `sound/mod` 资源树等（[ModCheckResourceLiterals]/[ModCheckMediaResources] 按真实落点核对，
 *    所以这些文件也是输入）；
 * 3. 仓库级共享输入：`mod-api` 作者面源码（[ModCheckApiSurface]/[ModCheckMinApi] 从中抽
 *    `VERSION`）与同目录 `mod-*` 兄弟模块源码（[ModCheckRequires] 抽包名/依赖与成环）。
 *    模块不在仓库里（没有 `settings.gradle.kts` 标记）时整段跳过。
 *
 * 指纹只读不写。文本类文件按内容哈希；非文本文件 ≤1 MiB 也按内容哈希，更大的按
 * 「大小 + mtime」做廉价指纹 —— 只会让缓存更保守地失效，不会误命中内容已变的文件。
 */
internal object ModCheckFingerprint {
    /** 本工具（check-mod 检查语义）版本：语义改了必须 bump，旧缓存整体失效。 */
    const val TOOL_VERSION: String = "1"

    private const val REPO_MARKER = "settings.gradle.kts"
    private const val API_SOURCE_DIR = "mod-api/src/main/kotlin"
    private const val SIBLING_PREFIX = "mod-"
    private const val SMALL_FILE_BYTES = 1L shl 20
    private const val BYTE_MASK = 0xFF

    private val SEPARATOR = byteArrayOf(0)

    /** 指纹跳过的目录段名（构建产物/版本库/依赖；与 ModCheckResourceLiterals 的 skip 面同口径）。 */
    private val SKIPPED_DIR_NAMES = setOf("build", ".git", ".gradle", ".kotlin", ".idea", "out", "node_modules")

    /** 按内容哈希的文本扩展名（其余走「小文件内容 / 大文件元信息」两种廉价口径）。 */
    private val TEXT_EXTENSIONS = setOf(
        "kt", "kts", "tsv", "erb", "erh", "json", "txt", "md",
        "yaml", "yml", "toml", "properties", "xml", "csv",
    )

    /** 计算输入指纹；IO/权限问题向上抛，调用方用 [computeOrNull] 回退全量。 */
    fun compute(
        root: Path,
        schemaFiles: List<File>,
        resourcesDir: String?,
        toolVersion: String = TOOL_VERSION,
    ): String {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val digest = MessageDigest.getInstance("SHA-256")
        feedText(digest, "tool", toolVersion)
        feedText(digest, "resources", resourcesDir ?: "<none>")
        feedTree(digest, normalizedRoot, "mod")
        feedSchemas(digest, normalizedRoot, schemaFiles)
        feedRepoInputs(digest, normalizedRoot)
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and BYTE_MASK)
        }
    }

    /** 指纹计算的容错版：任何 IO/权限问题都返回 null（缓存不可用 ⇒ 调用方回退全量）。 */
    fun computeOrNull(root: Path, schemaFiles: List<File>, resourcesDir: String?): String? =
        try {
            compute(root, schemaFiles, resourcesDir)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun feedText(digest: MessageDigest, label: String, text: String) {
        feed(digest, label, text.toByteArray(Charsets.UTF_8))
    }

    /** 字段级喂入：label 与内容都以 NUL 分隔，避免拼接歧义（`a/b` vs `ab` 之类）。 */
    private fun feed(digest: MessageDigest, label: String, value: ByteArray) {
        digest.update(label.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(value)
        digest.update(SEPARATOR)
    }

    /** 递归喂入 [dir] 下的全部普通文件（跳过构建/版本库段名），按相对路径排序保证确定性。 */
    private fun feedTree(digest: MessageDigest, dir: Path, prefix: String) {
        if (!Files.isDirectory(dir)) {
            feedText(digest, "tree", "$prefix:<absent>")
        } else {
            feedText(digest, "tree", "$prefix:<present>")
            val files = Files.walk(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            files
                .filter { file -> dir.relativize(file).none { segment -> segment.toString() in SKIPPED_DIR_NAMES } }
                .sortedBy { file -> displayPath(file, dir) }
                .forEach { file -> feedFile(digest, file, "$prefix/${displayPath(file, dir)}") }
        }
    }

    /** 单个文件喂入：文本类按内容；非文本小文件按内容；大文件按大小 + mtime（保守方向）。 */
    private fun feedFile(digest: MessageDigest, file: Path, label: String) {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        if (!attributes.isRegularFile) return
        feedText(digest, "file", label)
        val extension = file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension in TEXT_EXTENSIONS || attributes.size() <= SMALL_FILE_BYTES) {
            feed(digest, "content", Files.readAllBytes(file))
        } else {
            feedText(digest, "meta", "${attributes.size()}:${attributes.lastModifiedTime().toMillis()}")
        }
    }

    /** schema 文件：缺省发现与显式 `--schema` 同口径；文件按归一绝对路径排序（显式可指向模块外）。 */
    private fun feedSchemas(digest: MessageDigest, root: Path, schemaFiles: List<File>) {
        val paths = if (schemaFiles.isEmpty()) {
            ModCheckSchemas.discover(root)
        } else {
            schemaFiles.map { file -> file.toPath() }
        }
        val labeled = paths
            .map { path -> path.toAbsolutePath().normalize() }
            .sortedBy { path -> displayPath(path, root) }
        feedText(digest, "schema-count", labeled.size.toString())
        labeled.forEach { path -> feedFile(digest, path, "schema:${displayPath(path, root)}") }
    }

    /** 仓库级共享输入：mod-api 源码树 + 同目录 `mod-*` 兄弟模块（模块不在仓库里时整段缺席）。 */
    private fun feedRepoInputs(digest: MessageDigest, root: Path) {
        val repoRoot = generateSequence(root) { path -> path.parent }
            .firstOrNull { path -> Files.isRegularFile(path.resolve(REPO_MARKER)) }
        if (repoRoot == null) {
            feedText(digest, "repo", "<absent>")
        } else {
            feedText(digest, "repo", repoRoot.fileName?.toString().orEmpty())
            feedTree(digest, repoRoot.resolve(API_SOURCE_DIR), "repo/mod-api")
            val siblings = siblingModules(root)
            feedText(digest, "siblings", siblings.joinToString(separator = ",") { path -> path.fileName.toString() })
            siblings.forEach { sibling -> feedTree(digest, sibling, "repo/${sibling.fileName}") }
        }
    }

    /** [root] 同目录下的 `mod-*` 兄弟模块（不含自己），按目录名排序；读不到目录就当没有。 */
    private fun siblingModules(root: Path): List<Path> {
        val parent = root.parent ?: return emptyList()
        return try {
            Files.newDirectoryStream(parent, "$SIBLING_PREFIX*").use { entries ->
                entries
                    .filter { entry -> Files.isDirectory(entry) && entry.toAbsolutePath().normalize() != root }
                    .sortedBy { entry -> entry.fileName.toString() }
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** [path] 相对 [base] 的 `/` 分隔标签；不在其下时退回归一绝对路径（显式模块外 schema 用）。 */
    private fun displayPath(path: Path, base: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        val relative = if (normalized.startsWith(base)) {
            base.relativize(normalized).toString()
        } else {
            normalized.toString()
        }
        return relative.replace('\\', '/')
    }
}
