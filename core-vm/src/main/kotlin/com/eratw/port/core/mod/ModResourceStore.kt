package com.eratw.port.core.mod

import com.eratw.port.modapi.ModResourceApi
import java.io.File
import java.io.IOException
import java.nio.file.Path

/** 包身份形态闸（与 app 侧 `ModEnablement` 的 packageId 口径同形；防目录穿越的第一道）。 */
private val PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * RT2 mod 资源存储（资源注入**第一刀**）：根 = `mods/rt2/`，逐包目录 = `<packageId>.assets/`。
 *
 * 与既有 `.manifest` / `.settings` / `.erbm` 同一层目录约定：本存储只按 packageId 派生
 * 包目录，不碰其它文件。每个 context 经 [apiFor] 拿**本包视图** —— A 包视图的根目录
 * 是 `mods/rt2/<A>.assets/`，名字无法指到 B 包目录（目录根不同），即使作者构造
 * `../<B>.assets/x` 这样的名字也会在名字校验层被拒。
 *
 * ## 安全与健壮性口径（本刀的全部裁决）
 *
 * - **名字**：只接受 `/` 分隔、已规范的相对路径；绝对路径 / 反斜杠 / 控制字符 / 空段 /
 *   `.` 段 / `..` 段 / Windows 盘符冒号一律 [IllegalArgumentException]（不静默归一）。
 * - **大小写**：API 面按磁盘原名逐字匹配，`keys()` 保留磁盘原名大小写、不做折叠；
 *   是否命中变体取决于宿主文件系统（Android/Linux 敏感、Windows 不敏感），本层不承诺
 *   跨平台一致（见 [ModResourceApi] 的类型 KDoc）。
 * - **隔离**：包目录由 packageId 派生并先过 [PACKAGE_ID_PATTERN]；目录遍历时每个叶子的
 *   `canonicalPath` 还必须落在包目录内（防符号链接 / 目录链接逃逸，越界者 WARN 后跳过）。
 * - **惰性**：构造与 [apiFor] 都不读盘、不建目录；[ModResourceApi.keys] 只走目录 + stat
 *   （不读字节），[ModResourceApi.open] 每次现读现返（无缓存，热改文件下一次调用可见）。
 * - **上限**：单文件超 [MAX_RESOURCE_FILE_BYTES] ⇒ WARN 后跳过**该文件**；整包累计超
 *   [MAX_RESOURCE_TOTAL_BYTES] ⇒ WARN 后**整包资源面禁用**（`keys()` 空表、其余入口
 *   null / false），不提供「按序截断的部分资源集」（部分集会让 mod 行为取决于遍历序）。
 * - **坏包不炸宿主**：包目录不存在 = 空资源面（不告警，正常包可能不带资源）；路径不是
 *   目录 / 不可读 / IO 异常 = WARN 后按空处理；资源名非法 / 不可读 = WARN 后跳过该文件。
 *
 * 线程口径：与其余 mod 面一致，只在 VM 线程调用；本类不做线程安全承诺。
 * 明确**不做**：渲染 / 音频播放接线、`.erbm` 内嵌资源抽取（后续 lane）。
 *
 * @param rt2Dir `mods/rt2/` 目录（app 侧 = `ModRuntimeAssembly.manifestDirFor(gameRoot)`）。
 * @param logSink 宿主日志面（坏包/坏文件/超限的 WARN 都经它落痕）。
 * @param maxFileBytes 单文件上限（测试可收窄；缺省 [MAX_RESOURCE_FILE_BYTES]）。
 * @param maxTotalBytes 单包总量上限（测试可收窄；缺省 [MAX_RESOURCE_TOTAL_BYTES]）。
 */
class ModResourceStore(
    private val rt2Dir: File,
    private val logSink: (String) -> Unit = {},
    private val maxFileBytes: Long = MAX_RESOURCE_FILE_BYTES,
    private val maxTotalBytes: Long = MAX_RESOURCE_TOTAL_BYTES,
) {
    init {
        require(maxFileBytes > 0L) { "maxFileBytes 必须为正：$maxFileBytes" }
        require(maxTotalBytes > 0L) { "maxTotalBytes 必须为正：$maxTotalBytes" }
    }

    /**
     * 取某包的本包资源视图（不读盘：目录不可用/未发布时视图表现为空资源面）。
     *
     * @throws IllegalArgumentException packageId 形态非法（空、含 `/` / `\` / `..`
     *   之外的路径字符等；与声明装载期的 packageId 口径一致，这里是装配期编程错误）。
     */
    fun apiFor(packageId: String): ModResourceApi {
        require(PACKAGE_ID_PATTERN.matches(packageId)) {
            "packageId 形态非法（资源面 fail-closed）：[$packageId]"
        }
        return PackageResourceApi(
            packageId = packageId,
            dir = File(rt2Dir, packageId + ASSET_DIR_SUFFIX),
            logSink = logSink,
            maxFileBytes = maxFileBytes,
            maxTotalBytes = maxTotalBytes,
        )
    }

    /**
     * 读某包的覆盖侧车（ERA-A3 方案 B / 二期 A）：`mods/rt2/<packageId>.assetmap`。
     *
     * 与 [apiFor] 同源的 packageId 形态闸先于读盘（编程错误直接抛，与 API 面一致）；
     * 之后委托 [ModDx3AssetMap.read] 的 fail-closed 口径：文件不存在 = 空表、坏档 = ok=false
     * 且不部分采信（WARN 经 [logSink] 落痕，不静默吞错、不崩）。侧车是只读面，不建/改目录。
     *
     * 生产消费链由 `EraA3AssetOverlay.collect(rt2Dir, linkOrderIds)` 聚合各包侧车后用
     * `LinkOrder` 定胜者（后者胜）；本口只是「按 packageId 读一张侧车」的最小原语，
     * 便于 core 侧纯 JVM 单测直接钉住读写与坏档口径。
     */
    fun assetMapFor(packageId: String): ModDx3AssetMap.Sidecar {
        require(PACKAGE_ID_PATTERN.matches(packageId)) {
            "packageId 形态非法（覆盖侧车 fail-closed）：[$packageId]"
        }
        return ModDx3AssetMap.read(rt2Dir, packageId, logSink)
    }

    companion object {
        /** 单文件大小上限：4 MiB（资源应离线预处理，运行期不做流式大资产）。 */
        const val MAX_RESOURCE_FILE_BYTES: Long = 4L * 1024L * 1024L

        /** 单包资源总量上限：16 MiB（整包超限 = 整包资源面禁用，见类 KDoc）。 */
        const val MAX_RESOURCE_TOTAL_BYTES: Long = 16L * 1024L * 1024L

        /** 包资源目录后缀：`mods/rt2/<packageId>.assets/`（与 `.manifest`/`.settings` 同目录约定）。 */
        const val ASSET_DIR_SUFFIX: String = ".assets"
    }
}

/**
 * 本包资源视图（[ModResourceApi] 的 core 实现）：根目录固定为 `<packageId>.assets/`，
 * 每次调用重新走目录 + stat，不缓存文件名列表也不缓存字节。
 */
internal class PackageResourceApi(
    override val packageId: String,
    private val dir: File,
    private val logSink: (String) -> Unit,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long,
) : ModResourceApi {

    /** 包目录的真实路径（惰性解析一次）；null = 坏包目录，按空资源面处理。 */
    private val dirPath: Path? by lazy { canonicalPathOrNull(dir) }

    override fun keys(): List<String> = scan()?.keys?.toList().orEmpty()

    override fun exists(name: String): Boolean {
        val key = requireName(name)
        return scan()?.containsKey(key) ?: false
    }

    override fun size(name: String): Long? {
        val key = requireName(name)
        return scan()?.get(key)?.length()
    }

    override fun open(name: String): ByteArray? {
        val key = requireName(name)
        val file = scan()?.get(key) ?: return null
        return try {
            val bytes = file.readBytes()
            // 目录扫描与读取之间文件可能变大（并发写入/热重载）：按读取时大小再判一次上限。
            if (bytes.size.toLong() > maxFileBytes) {
                warn("资源读取时超过单文件上限，按不可用处理：$key（${bytes.size} > $maxFileBytes）")
                null
            } else {
                bytes
            }
        } catch (t: IOException) {
            warn("资源读取失败，按不可用处理：$key（${t::class.java.simpleName}: ${t.message}）")
            null
        }
    }

    /**
     * 当前可用资源表（名字升序 → 文件）；null = 整包超总量上限（整包资源面禁用）。
     *
     * 只 stat 不读内容：单文件超限者跳过（WARN）；累计超总量时整包禁用（WARN）——
     * 不返回按遍历序截断的部分集，避免 mod 行为取决于文件系统枚举序。
     */
    private fun scan(): Map<String, File>? = when {
        !dir.exists() -> emptyMap()
        !dir.isDirectory -> {
            warn("资源路径不是目录，整包资源面按空处理：${dir.path}")
            emptyMap()
        }
        else -> buildResourceMap()
    }

    private fun buildResourceMap(): Map<String, File>? {
        val root = dirPath
        if (root == null) {
            warn("资源路径不可解析，整包资源面按空处理：${dir.path}")
            return null
        }
        val found = ArrayList<Pair<String, File>>()
        collect(dir, "", root, found)
        return limitToBudget(found)
    }

    /** 逐个文件核上限：单文件超限者跳过（WARN），累计超总量 ⇒ null（整包禁用、不返回部分集）。 */
    private fun limitToBudget(found: List<Pair<String, File>>): Map<String, File>? {
        var total = 0L
        // 按名升序（sortedMap）⇒ keys() 顺序稳定，不依赖文件系统枚举序。
        val files = sortedMapOf<String, File>()
        for ((name, file) in found) {
            val bytes = file.length()
            if (bytes > maxFileBytes) {
                warn("资源文件超过单文件上限，跳过：$name（$bytes > $maxFileBytes）")
                continue
            }
            total += bytes
            if (total > maxTotalBytes) {
                warn(
                    "包资源总量超过上限，整包资源面禁用：$packageId" +
                        "（累计 $total > $maxTotalBytes，已计入 ${files.size} 个文件）",
                )
                return null
            }
            files[name] = file
        }
        return files
    }

    /** 递归收集名字 → 文件；叶子必须是落在包目录内的真实普通文件（canonical 校验防逃逸）。 */
    private fun collect(
        current: File,
        prefix: String,
        root: Path,
        out: MutableList<Pair<String, File>>,
    ) {
        val children = current.listFiles()
        if (children == null) {
            warn("资源目录不可读，跳过：${current.path}")
            return
        }
        for (child in children) {
            val name = childName(prefix, child.name)
            if (!isPathInside(child, root) || !isValidResourceName(name)) {
                warn("资源路径非法或越出包目录，跳过：$name")
                continue
            }
            when {
                child.isDirectory -> collect(child, name, root, out)
                child.isFile -> out += name to child
            }
        }
    }

    /** 校验并返回名字；非法即作者错误（抛 [IllegalArgumentException]，不静默归一到合法形态）。 */
    private fun requireName(name: String): String {
        if (!isValidResourceName(name)) {
            throw IllegalArgumentException(
                "资源名非法：[$name]（包 $packageId；只许 / 分隔的相对路径，禁前导/尾随斜杠、" +
                    "绝对路径与盘符、反斜杠、空段、点段与 .. 段、控制字符）",
            )
        }
        return name
    }

    private fun warn(message: String) {
        logSink("[$packageId][WARN] mod.resources $message")
    }
}

/** 名字里第一个可打印字符的码位（0x20 = 空格）：更小的都是控制字符。 */
private const val FIRST_PRINTABLE_CHAR_CODE = 0x20

/** DEL 控制字符码位（0x7F）：可打印区之后、但同样不可作为资源名。 */
private const val DELETE_CHAR_CODE = 0x7F

/** 相对名拼接（文件级纯函数，避免把结构性函数数推到 detekt 类内上限）。 */
private fun childName(prefix: String, child: String): String =
    if (prefix.isEmpty()) child else "$prefix/$child"

/** 节点真实路径是否仍在包目录内（防符号链接 / 目录链接逃逸）；不可解析按越界处理。 */
private fun isPathInside(node: File, root: Path): Boolean {
    val path = canonicalPathOrNull(node) ?: return false
    return path.startsWith(root)
}

private fun canonicalPathOrNull(file: File): Path? = try {
    file.canonicalFile.toPath()
} catch (_: IOException) {
    null
}

/**
 * 名字合法性（API 面唯一校验口径）：`/` 分隔的相对路径，禁止一切可逃逸/跨平台的写法。
 *
 * 裁决是**严格拒绝、不归一**：`/a`、`a//b`、`a/`、`a/../b`、`a\b`、`C:/x`、`.`/`..` 段、
 * 控制字符全部非法 —— 作者写错名字应当在开发期暴露，而不是被静默改写后读到别的文件。
 */
private fun isValidResourceName(name: String): Boolean =
    name.isNotEmpty() &&
        !name.contains('\\') &&
        !name.startsWith('/') &&
        !name.endsWith('/') &&
        name.split('/').all { isAllowedSegment(it) }

private fun isAllowedSegment(segment: String): Boolean {
    if (segment.isEmpty() || segment == "." || segment == "..") return false
    return segment.all { isAllowedNameChar(it) }
}

private fun isAllowedNameChar(ch: Char): Boolean =
    ch.code >= FIRST_PRINTABLE_CHAR_CODE && ch.code != DELETE_CHAR_CODE && ch != ':'

/**
 * 资源面未装配时的显式 fail-closed 桩（宿主没注入 store 的会话 / 旧装配路径 / 直接构造的
 * 测试 context）：每个入口都抛 [UnsupportedOperationException]，**不**返回空表或 null 兜底
 * （静默空资源会让作者以为资源已打进包）。
 */
internal class FailClosedResourceApi(override val packageId: String) : ModResourceApi {
    override fun keys(): List<String> = fail("keys")

    override fun exists(name: String): Boolean = fail("exists")

    override fun size(name: String): Long? = fail("size")

    override fun open(name: String): ByteArray? = fail("open")

    private fun fail(call: String): Nothing = throw UnsupportedOperationException(
        "资源面未装配：$packageId（宿主未注入 ModResourceStore；本会话/旧装配路径不提供资源）；" +
            "$call 不提供静默默认值兜底",
    )
}
