package com.eratw.port.sqlite

import java.nio.file.Files
import java.nio.file.Path

/**
 * 玩家可写数据源：命中后使用写根里的稳定 target，target 一旦存在就**永不**用随包 seed 刷新
 * （种子只用于首次建档）。路径口径与 [SqlitePathLayout] 一致：统一 `/` 分隔、相对种子根/写根。
 *
 * 默认空集（2026-11-17 主人裁定）：`plugins/qol_data.db` 曾在此列，但经全量取证
 * （`specs/verify-notes.md` 2026-11-17 做菜报错章节）其 12 张表全部由启动注册
 * `QOL_DB_INIT`（`ERB/TITLE.ERB:28` → `qol_db.ERB:47` → 各 `*_DATA_REGISTER`）从语料常量
 * `INSERT OR REPLACE` 全量重建，`is_custom` 是 mod 作者标记、`gettable` 是捕捉条件键、
 * `_meta` 是版本戳 —— **没有任何不可重建的玩家私有数据**，mutable 保护无数据可保，
 * 只会把随包新 seed 挡在老设备外（性能/推广面缺口）。机制本身保留（构造可注入）：
 * 将来真出现存玩家私有状态的库，把它的相对路径加进 [SqlitePathLayout] 的
 * `mutableDataSources` 实参即可恢复「永不回滚」语义。
 */
internal val DEFAULT_MUTABLE_DATA_SOURCES: Set<String> = emptySet()

/** 一次 `SQL_CONNECT` 解析后的打开目标：内存库，或写根里的稳定文件 + 可选种子文件。 */
internal sealed interface SqliteOpenTarget {
    data object Memory : SqliteOpenTarget

    /**
     * @param target 写根内的稳定目标路径（A1 直接交给 AndroidX 打开）。
     * @param seed 种子根内已存在的普通文件；首次打开时由 A1 复制到 [target]，缺失为 null。
     * @param mutable 玩家可写库（[mutableDataSources] 命中）：target 一旦存在就**永不**用 seed
     *   回滚；false 表示随包只读数据，seed 比 target 新时由 A1 原子刷新。
     * @param relativePath 归一后的相对路径（统一 `/`），供策略判定与报错定位。
     */
    data class File(
        val target: Path,
        val seed: Path?,
        val mutable: Boolean = false,
        val relativePath: String = target.toString(),
    ) : SqliteOpenTarget
}

/**
 * SQLite 连接串到沙箱路径的**纯 JVM** 单点解析（SQLite 迁移 A2）。
 *
 * 上游 `SQL_CONNECT` 的第二个参数是 Microsoft.Data.Sqlite 连接串，eraTW 语料实际只用
 * `Data Source=plugins/qol_data.db` 这一形（`qol_dish.ERB`、`qol_item.ERB` 等），但解析按
 * 契约 §3 做通用分段：
 * - 分号分段；键去空白并小写后只认 `datasource` 与 `filename`，后出现的值覆盖先出现的值；
 * - 整串没有 `=` 时按纯路径处理；
 * - 空串、`:memory:`、`file::memory:`（含 `Data Source=` 空值）返回 [SqliteOpenTarget.Memory]；
 * - 路径分隔统一 `/`，Windows 反斜杠等价于 `/`；
 * - 相对路径一律落到 [writeRoot] 下的稳定位置；绝对路径、盘符路径与 `..` 跳出写根一律抛
 *   [IllegalArgumentException]，不做「原串透传」；
 * - 种子只在 [seedRoot] 内且是普通文件时返回，否则为 null（首次打开时建空库）。
 *
 * 本类不 import 任何 AndroidX / Android 类型，也不做文件复制：复制属于 A1 驱动层。
 *
 * [mutableDataSources] 是数据版本刷新策略：命中的路径解析为 [SqliteOpenTarget.File.mutable] =
 * true，A1 只在首次打开时复制 seed，之后即使随包 seed 更新也保留写入（默认空集，见
 * [DEFAULT_MUTABLE_DATA_SOURCES] 的取证记录）；其余 seed 库按 seed 与 target 的修改时间
 * 决定是否原子刷新。
 */
internal class SqlitePathLayout(
    seedRoot: Path,
    writeRoot: Path,
    mutableDataSources: Set<String> = DEFAULT_MUTABLE_DATA_SOURCES,
) {
    private val seedRoot: Path = seedRoot.normalize()
    private val writeRoot: Path = writeRoot.normalize()

    /** 归一后的可变数据源集合（统一 `/`、折叠 `.`/`..`）；命中即 target 永不回滚。 */
    internal val mutableDataSources: Set<String> =
        mutableDataSources.mapTo(LinkedHashSet<String>()) { relativePathOf(it) }

    /** 解析一个 `SQL_CONNECT` 连接串；语义见类 KDoc。 */
    fun resolve(connectionString: String): SqliteOpenTarget {
        val raw = connectionString.trim()
        val dataSource = if (isMemory(raw)) null else dataSourceOf(raw)
        return if (dataSource == null || isMemory(dataSource)) {
            SqliteOpenTarget.Memory
        } else {
            val relative = normalizePathText(dataSource)
            require(!looksAbsolute(relative)) {
                "SQLite 数据源不允许绝对路径：$dataSource"
            }

            val relativePath = relativePathOf(relative)
            val target = writeRoot.resolve(relativePath).normalize()
            require(target.startsWith(writeRoot)) {
                "SQLite 数据源越出写根：$dataSource -> $target"
            }

            val seedCandidate = seedRoot.resolve(relativePath).normalize()
            val seed = seedCandidate.takeIf {
                it.startsWith(seedRoot) && Files.isRegularFile(it)
            }
            SqliteOpenTarget.File(
                target = target,
                seed = seed,
                mutable = relativePath in mutableDataSources,
                relativePath = relativePath,
            )
        }
    }

    /**
     * 取出路径值：无 `=` 的整串就是路径；否则只认 `datasource` / `filename`。
     * 有 `=` 却没有任何可识别键时抛错，避免静默退化成内存库。
     */
    private fun dataSourceOf(raw: String): String {
        if ('=' !in raw) return raw

        var value: String? = null
        for (segment in raw.split(';')) {
            val eq = segment.indexOf('=')
            if (eq < 0) continue
            val key = segment.substring(0, eq).filterNot { it.isWhitespace() }.lowercase()
            if (key == "datasource" || key == "filename") {
                value = segment.substring(eq + 1).trim()
            }
        }
        return value ?: throw IllegalArgumentException("SQLite 连接串缺少 Data Source/Filename：$raw")
    }

    private fun isMemory(value: String): Boolean {
        val token = value.trim()
        return token.isEmpty() ||
            token.equals(":memory:", ignoreCase = true) ||
            token.equals("file::memory:", ignoreCase = true)
    }

    /**
     * 绝对路径判定同时覆盖三套口径：Unix 根、Windows 盘符、以及「在 Unix 上跑 Windows 路径」
     * 与「在 Windows 上跑 Unix 路径」两种测试夹具。UNC 的 `//` 由 Unix 根分支覆盖。
     */
    private fun looksAbsolute(path: String): Boolean =
        path.startsWith("/") ||
            WINDOWS_DRIVE.containsMatchIn(path) ||
            Path.of(path).isAbsolute

    private fun normalizePathText(raw: String): String =
        raw.trim().replace('\\', '/')

    /** 统一 `/` 并折叠 `.`/`..`；相对路径用于可变数据源判定。 */
    private fun relativePathOf(raw: String): String =
        Path.of(normalizePathText(raw)).normalize().toString().replace('\\', '/')

    private companion object {
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:/")
    }
}
