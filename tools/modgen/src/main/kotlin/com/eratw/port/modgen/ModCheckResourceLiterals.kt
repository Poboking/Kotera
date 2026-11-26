package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.streams.toList

/** 从载体源码里抽出来的一条资源字面量声明（[file] 是相对 mod 根的 `/` 分隔标签）。 */
internal data class ResourceLiteralSite(val file: String, val line: Int, val literal: String)

/** ERB 侧的一处内建调用：内建名 + 首实参字面量。 */
private data class ErbSite(val site: ResourceLiteralSite, val builtin: String)

/** ERB 字面量解析出的真实落点：`<rootName>/mod/<dir>/<relative>`。 */
private data class ErbTarget(val rootName: String, val dir: String, val relative: String)

private val ERB_EXTENSIONS = setOf("erb", "erh")

/** 扫描模块根时跳过的目录名（生成物/版本库/依赖，里面不会有作者手写 ERB）。 */
private val SKIPPED_SEGMENTS = setOf("build", ".git", ".gradle", "out", "node_modules")

/**
 * 只许 ASCII 单段路径名的 ERB 字面量：`"..."`、内不含引号/反斜杠/`%` 变量。
 *
 * 收尾后紧跟可选空白与 `+` 的一律不算完整实参：`GCREATEFROMFILE "mod/d/" + name` 这类 ERB
 * 字符串拼接截到的 `mod/d/` 片段会冒充作者声明去报「资源名非法」（假红），整段漏过。
 */
private val ERB_CALL = Regex("""\b(GCREATEFROMFILE|LOADTEXT|PLAYSOUND)\s*\(?\s*"([^"\\%]*)"(?!\s*\+)""")

/**
 * Kotlin 包资源消费调用：`context.resources.exists/open/size("...")`、`context.ui.registerImage("...")`。
 *
 * 收尾守卫与 [ERB_CALL] 同口径：`"a" + name` 拼接截不出整段资源名，整段漏过（`$` 插值本就不在字符集）。
 */
private val KOTLIN_CALL = Regex(
    """\bcontext\s*\.\s*(?:resources\s*\.\s*(?:exists|open|size)|ui\s*\.\s*registerImage)""" +
        """\s*\(\s*"([^"\\$]*)"(?!\s*\+)""",
)

/** 读文本按 ISO-8859-1 逐字节映射（行号与偏移都不漂）；调用方只要 ASCII 关键字与字面量。 */
private fun readRawLines(file: Path): List<String> =
    Files.readAllBytes(file).toString(Charsets.ISO_8859_1).split('\n').map { it.removeSuffix("\r") }

/** 模块根下的 `<root>/mod/` 子目录名（已排序）；目录不在就返回空表。 */
private fun modChildDirs(root: Path, rootName: String): List<String> {
    val modDir = root.resolve(rootName).resolve(PackModResources.MOD_SEGMENT)
    if (!Files.isDirectory(modDir)) return emptyList()
    return Files.list(modDir).use { stream ->
        stream.filter(Files::isDirectory).map { path -> path.name }.sorted().toList()
    }
}

/** 判断一条路径是否落在生成物/版本库目录里（按相对模块根的段名匹配）。 */
private fun isSkipped(root: Path, path: Path): Boolean =
    root.relativize(path).any { segment -> segment.toString() in SKIPPED_SEGMENTS }

/** 判断路径是否是 ERB 源文件（大小写不敏感，与 pack-mod 的递归收集同口径）。 */
private fun isErbFile(path: Path): Boolean = path.extension.lowercase() in ERB_EXTENSIONS

/**
 * 资源字面量面：把作者声明的资源名对到真实文件，治「作者以为资源随包了、运行期却读不到」的静默失效
 * （ADR-014 D7 的「ERB 字面量至少对得上一次」）。
 *
 * 两类消费面各按**真实运行期根**解析：
 *
 * 1. Kotlin 载体的包资源面：[ModResourceApi] 的名字以本包资源根为基准、设备侧落在
 *    `mods/rt2/<packageId>.assets/`，缺文件时 exists=false 而 size/open 返回 null（接口 KDoc 已写明
 *    不抛异常）；[UiApi.registerImage] 同名字语义，缺资源按 altText/占位框降级。故本面把「缺文件」
 *    判 WARN（与 [ModCheckImages] 同口径，降级是设计允许的），名字非法才 ERROR。
 * 2. ERB 载体的旁挂树面：`GCREATEFROMFILE` 由引擎 contentDirPath 只补 `resources/` 前缀
 *    （GraphicsMethods.kt:1231-1239）；`LOADTEXT` 按游戏根相对路径放行（HostMethods.kt:369-375、
 *    BootHosts.kt:616-625）；`PLAYSOUND` 落 `sound/`（SoundResourcePaths.kt:37,45-52）——即
 *    `resources/mod/<dir>/…` 与 `sound/mod/<dir>/…` 就是运行期真实落点。这些内建没有 exists 探针，
 *    缺资源就是图返 0 / 文返空串 / 音静默，作者拿不到任何提示；所以在**本包自己的** `<dir>` 内缺文件
 *    判 ERROR，消息尾带稳定标记 [MISSING_TOKEN] 供 CI 与 `--json` 消费方 grep（JSON 仍是四键 finding，
 *    schema 形状不变）。
 *
 * 判定边界（宁可漏报不许假红）：
 * - 只认首实参的整段双引号字面量；变量、`$` 插值、`%` 变量、字符串拼接、跨行一律静默跳过；
 * - ERB 侧只校验路径里带 `mod/<dir>/` 段、且 `<dir>` 是本包声明（`--resources`）或实际存在的树目录的
 *   字面量；指向 base game 或别的 mod 的路径静默（作者可能故意读外部资源）；
 * - `<dir>` 合法性、树存在性、扩展名白名单与限额由 [ModCheckMediaResources]/[PackModResources] 负责，
 *   本面不重复报；声明了 `<dir>` 而树整体不存在时本面静默（那边已报 ERROR）；
 * - 资源树整体缺失但抽到了字面量时只汇总一条 WARN，不逐条刷屏。
 */
internal object ModCheckResourceLiterals {

    /** 落空字面量的稳定标记：缺文件 ERROR 的 message 尾部都带它。 */
    internal const val MISSING_TOKEN = "RESOURCE_LITERAL_MISSING"

    private const val FIRST = 1
    private const val SECOND = 2

    /** 跑完两类资源面（[declaredDir] 与 [ModCheckMediaResources] 同义：包元数据 `resources` 的声明值）。 */
    fun check(root: Path, sources: List<SourceFile>, declaredDir: String?, findings: MutableList<Finding>) {
        checkAssets(root, sources, findings)
        checkTrees(root, declaredDir, findings)
    }

    /** Kotlin 载体面：字面量按 `<packageId>.assets/` 判存在性。 */
    private fun checkAssets(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val packageIds = ModCheckResources.packageIds(sources)
        val sites = kotlinSites(sources)
        if (packageIds.isEmpty() || sites.isEmpty()) return
        val roots = packageIds
            .map { packageId -> root.resolve(packageId + ModResourceLimits.ASSET_DIR_SUFFIX) }
            .filter(Files::isDirectory)
        // 包资源目录整体缺失按既有口径静默（模块可能根本没有包资源，ModCheckResourcesTest 已钉死该口径）。
        if (roots.isNotEmpty()) reportAssetSites(roots, sites, findings)
    }

    /** 从载体源码抽 `context.resources.*` / `context.ui.registerImage` 的首实参字面量。 */
    private fun kotlinSites(sources: List<SourceFile>): List<ResourceLiteralSite> = sources.flatMap { source ->
        source.code.flatMap { line ->
            KOTLIN_CALL.findAll(line.text).map { match ->
                ResourceLiteralSite(source.label, line.number, match.groupValues[FIRST])
            }.filter { site -> site.literal.isNotEmpty() }.toList()
        }
    }

    /** 逐条判包资源存在性：名字非法 ERROR，缺文件 WARN（运行期按 null/altText 降级，非必然失效）。 */
    private fun reportAssetSites(
        roots: List<Path>,
        sites: List<ResourceLiteralSite>,
        findings: MutableList<Finding>,
    ) {
        sites.forEach { site ->
            when {
                !isLegalResourceName(site.literal) -> findings += Finding(
                    Severity.ERROR,
                    site.file,
                    site.line,
                    "资源名非法：${site.literal}（只许 / 分隔的相对路径，禁前导/尾随斜杠、. 与 .. 段、反斜杠）",
                )
                roots.none { dir -> Files.isRegularFile(dir.resolve(site.literal)) } -> findings += Finding(
                    Severity.WARN,
                    site.file,
                    site.line,
                    "包资源不存在：${site.literal}（在 <packageId>.assets/ 下找不到；运行期 exists=false、open/size" +
                        " 为 null，图片元素按 altText 降级）",
                )
            }
        }
    }

    /** ERB 旁挂树面：只校验本包 `<dir>` 内的字面量，缺文件 ERROR、树整体缺失一条 WARN 汇总。 */
    private fun checkTrees(root: Path, declaredDir: String?, findings: MutableList<Finding>) {
        val sites = erbSites(root).mapNotNull { site -> resolveErb(site)?.let { target -> site to target } }
        if (sites.isEmpty()) return
        val dirs = if (declaredDir != null) {
            listOf(declaredDir)
        } else {
            modChildDirs(root, PackModResources.RESOURCES_ROOT) + modChildDirs(root, PackModResources.SOUND_ROOT)
        }
        if (dirs.isEmpty()) {
            findings += aggregate(
                sites.first().first.site,
                Severity.WARN,
                "旁挂资源树不存在：${sites.size} 处 mod/<dir>/... 字面量在运行期都读不到（引擎对缺失资源静默降级）；" +
                    "请把文件放进 resources/mod/<dir>/ 或 sound/mod/<dir>/，并用 pack-mod --resources <dir> 声明",
            )
            return
        }
        sites.filter { (_, target) -> target.dir in dirs }
            .groupBy { (_, target) -> target.rootName to target.dir }
            .toSortedMap(compareBy({ tree -> tree.first }, { tree -> tree.second }))
            .forEach { (tree, entries) -> auditTree(root, declaredDir, tree, entries, findings) }
    }

    /** 审计一棵树的字面量：树不存在时按声明与否决定 WARN/静默，存在时逐文件判存在性（同文件去重）。 */
    private fun auditTree(
        root: Path,
        declaredDir: String?,
        tree: Pair<String, String>,
        entries: List<Pair<ErbSite, ErbTarget>>,
        findings: MutableList<Finding>,
    ) {
        val (rootName, dir) = tree
        val treePath = root.resolve(rootName).resolve(PackModResources.MOD_SEGMENT).resolve(dir)
        if (!Files.isDirectory(treePath)) {
            if (declaredDir != dir) {
                findings += aggregate(
                    entries.first().first.site,
                    Severity.WARN,
                    "旁挂资源树不存在：$rootName/mod/$dir/（${entries.size} 处字面量在运行期都读不到）",
                )
            }
            return
        }
        val seen = mutableSetOf<String>()
        entries.sortedBy { (_, target) -> target.relative }.forEach { (site, target) ->
            val key = if (isLegalResourceName(target.relative)) "file" else "name"
            if (!seen.add(key + ":" + target.relative)) return@forEach
            when {
                key == "name" -> findings += Finding(
                    Severity.ERROR,
                    site.site.file,
                    site.site.line,
                    "资源名非法：${target.relative}（落点 $rootName/mod/$dir/${target.relative}）",
                )
                !Files.isRegularFile(treePath.resolve(target.relative)) -> findings += Finding(
                    Severity.ERROR,
                    site.site.file,
                    site.site.line,
                    "资源字面量指向的文件不存在：${site.site.literal}（落点 $rootName/mod/$dir/${target.relative}）；" +
                        "引擎按游戏根相对路径读，缺文件时图返 0 / 文返空串 / 音静默，属必然失效（$MISSING_TOKEN）",
                )
            }
        }
    }

    /** 从模块根的 ERB/ERH 里抽内建首实参字面量（跳过生成物目录，行号即源文件行号）。 */
    private fun erbSites(root: Path): List<ErbSite> {
        val files = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter(::isErbFile)
                .filter { path -> !isSkipped(root, path) }
                .sorted()
                .toList()
        }
        return files.flatMap { file ->
            val label = ModCheckSources.relativeLabel(root, file)
            readRawLines(file).flatMapIndexed { index, text ->
                ERB_CALL.findAll(text).map { match ->
                    ErbSite(
                        ResourceLiteralSite(label, index + FIRST, match.groupValues[SECOND]),
                        match.groupValues[FIRST],
                    )
                }.filter { site -> site.site.literal.isNotEmpty() }.toList()
            }
        }
    }

    /** 解析 ERB 字面量：`PLAYSOUND` 缺省落 sound/，其余缺省落 resources/；只认带 `mod/<dir>/` 段的路径。 */
    private fun resolveErb(site: ErbSite): ErbTarget? {
        val defaultRoot = if (site.builtin == "PLAYSOUND") {
            PackModResources.SOUND_ROOT
        } else {
            PackModResources.RESOURCES_ROOT
        }
        val resourcesPrefix = PackModResources.RESOURCES_ROOT + "/"
        val soundPrefix = PackModResources.SOUND_ROOT + "/"
        var path = site.site.literal
        val rootName = when {
            path.startsWith(resourcesPrefix) -> {
                path = path.removePrefix(resourcesPrefix)
                PackModResources.RESOURCES_ROOT
            }
            path.startsWith(soundPrefix) -> {
                path = path.removePrefix(soundPrefix)
                PackModResources.SOUND_ROOT
            }
            else -> defaultRoot
        }
        if (!path.startsWith(PackModResources.MOD_SEGMENT + "/")) return null
        val segments = path.split('/')
        return if (segments.size <= SECOND) {
            null
        } else {
            ErbTarget(rootName, segments[FIRST], segments.drop(SECOND).joinToString("/"))
        }
    }

    /** 汇总型发现：只用第一条的定位，数量写进 message，避免逐条刷屏。 */
    private fun aggregate(site: ResourceLiteralSite, severity: Severity, message: String): Finding =
        Finding(severity, site.file, site.line, message)
}
