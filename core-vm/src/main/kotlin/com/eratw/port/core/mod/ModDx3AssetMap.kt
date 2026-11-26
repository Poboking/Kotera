package com.eratw.port.core.mod

import java.io.File
import java.io.IOException

/**
 * 逐包覆盖侧车 `mods/rt2/<packageId>.assetmap` 的**编解码 + fail-closed 读**（ERA-A3 方案 B / 二期 A）。
 *
 * ## 为什么选这个文件位置（新建件选位理由）
 *
 * - 侧车是**消费面资源**（`ResourceContents` / `SoundResourcePaths` / `ModResourceStore` 都要读），
 *   三者都在 `:core-vm`，故读写件落 `core-vm/.../core/mod/`，与 [ModResourceStore] 同包：
 *   core 层纯 JVM，无 Android API，宿主与单测都能直调。
 * - **不碰** `ManifestCodec`（对面 B 历史面禁触）：侧车是独立文件，`RT2MANIFEST` 线格式一字不动。
 * - **不碰** `ModImportController` 的 zip 收录面（A2 在跑）：app 侧只调本件的 [encode] /
 *   [read] 两张纯函数门面，派生落盘段归 controller 独占的 pack.json 段。
 *
 * ## 线格式（UTF-8 文本，稳定序，读回逐字节不变）
 *
 * ```
 * ERATW-ASSETMAP/1
 * <packageId>
 * <entryCount>
 * <SPRITE|BGM>\t<官方资源名>\t<mod 资源名>
 * ...
 * ```
 *
 * - 头三行固定：magic（拒绝未来格式被老端猜读）、包名（读回对账防错包侧车）、条目数（防截断）。
 * - 数据行三列 tab 分隔；`SPRITE` = CSV 资源表名（背景图/CG/立绘共用同一张 `SpritePool` 表，
 *   `ResourceContents.kt:159/189`）；`BGM` = `sound/` 下相对路径名（`SoundResourcePaths.kt:45`）。
 * - 排序 = kind 序 → 官方名 → mod 名，写入端与读回端同序 ⇒ 同输入逐字节相同。
 *
 * ## fail-closed（坏档不静默吞、不崩、不部分采信）
 *
 * 解码任一处不合法（magic / 行数 / 包名 / 条目数 / 列数 / kind / 资源名 / 重复声明）⇒
 * [Sidecar.ok] = false 且 [Sidecar.entries] 恒空表：整包覆盖**不采信**，消费面回落到既有官方行为。
 * 绝不返回「按行跳过坏条目」的部分集 —— 那会让覆盖是否生效取决于坏行在文件里的位置。
 * 非法资源名口径与 [ModResourceStore] 的 `isValidResourceName` 同形：`/` 分隔相对路径，
 * 禁前导/尾随斜杠、反斜杠、盘符冒号、空段、点段、`..` 段、控制字符与 DEL。
 */
object ModDx3AssetMap {

    /** 侧车文件后缀：与包资源目录 `<pkg>.assets/` 同层（`mods/rt2/<pkg>.assetmap`）。 */
    const val SIDECAR_EXTENSION: String = ".assetmap"

    /** 第一行 magic：认不出的版本一律当坏档（不猜未来格式）。 */
    const val MAGIC: String = "ERATW-ASSETMAP/1"

    /** 单条资源名长度上限（防坏档把超长串读进堆）。 */
    const val MAX_NAME_LENGTH: Int = 256

    /** 侧车字节上限（真实声明远小于此；超限按坏档，不读进堆）。 */
    const val MAX_SIDECAR_BYTES: Long = 64L * 1024L

    /** 覆盖目标类别：与消费面一一对应。 */
    enum class Kind(val token: String) {
        SPRITE("SPRITE"),
        BGM("BGM"),
        ;

        companion object {
            /** token → 类别；未知 token 返回 null（坏档）。 */
            fun of(token: String): Kind? = entries.firstOrNull { it.token == token }
        }
    }

    /**
     * 单条覆盖声明：`officialName`（官方资源名）被 `modName`（本包资源名）覆盖。
     *
     * `modName` 是 `<pkg>.assets/` 下的相对路径；消费面据此拼 `mods/rt2/<pkg>.assets/<modName>`,
     * 与官方资源同处一个 gameRoot（见 [com.eratw.port.core.host.ResourceCaseFold] /
     * `SoundResourcePaths.resolve`），故只在**消费面前**重写路径、不改任何宿主实现。
     */
    data class Entry(
        val kind: Kind,
        val officialName: String,
        val modName: String,
    )

    /**
     * 解码结果。[ok] = false ⇔ [entries] 恒空（fail-closed 不变量，单测钉死）；
     * [diagnostics] 只进宿主日志/取证，不上玩家 UI（坏档是作者侧打包问题）。
     */
    class Sidecar(
        val packageId: String?,
        val entries: List<Entry>,
        val ok: Boolean,
        val diagnostics: List<String>,
    )

    /** 编码成稳定序文本（同输入逐字节相同；读回走 [decode] 还原同一张表）。 */
    fun encode(packageId: String, entries: List<Entry>): String {
        val sorted = entries.sortedWith(
            compareBy<Entry> { it.kind.ordinal }.thenBy { it.officialName }.thenBy { it.modName },
        )
        val out = StringBuilder()
        out.append(MAGIC).append('\n')
        out.append(packageId).append('\n')
        out.append(sorted.size).append('\n')
        for (entry in sorted) {
            out.append(entry.kind.token).append('\t')
            out.append(entry.officialName).append('\t')
            out.append(entry.modName).append('\n')
        }
        return out.toString()
    }

    /** 解码文本；任何不合法整张判坏（[Sidecar.ok] = false + 空表），不抛。 */
    fun decode(text: String): Sidecar {
        // encode 每行以 \n 收尾（含最后一行）；lineSequence 会把结尾换行读成一个空行，
        // 去掉末尾连续空行再对账条目数，避免合法侧车被误判「条目数不符」。
        // 只去末尾、不去中间：中间空行会让列数校验失败 ⇒ 整张判坏（fail-closed）。
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList().dropLastWhile { it.isEmpty() }
        if (lines.size < HEADER_LINES) return bad("行数不足三行")
        if (lines[0] != MAGIC) return bad("magic 不认识：${lines[0]}")
        val packageId = lines[1]
        if (!isPackageId(packageId)) return bad("包名形态非法：$packageId")
        val count = lines[2].toIntOrNull() ?: return bad("条目数不是整数：${lines[2]}")
        val dataLines = lines.size - HEADER_LINES
        if (count < 0 || count != dataLines) return bad("条目数 $count 与实际 $dataLines 行不符")

        val entries = ArrayList<Entry>(count)
        val seen = HashSet<String>()
        for (i in HEADER_LINES until lines.size) {
            val columns = lines[i].split('\t')
            if (columns.size != COLUMN_COUNT) return bad("第 $i 行列数不是 $COLUMN_COUNT")
            val kind = Kind.of(columns[0]) ?: return bad("第 $i 行类别不认识：${columns[0]}")
            val official = columns[1]
            val mod = columns[2]
            if (!isValidName(official)) return bad("第 $i 行官方资源名非法：$official")
            if (!isValidName(mod)) return bad("第 $i 行 mod 资源名非法：$mod")
            // 同类别同官方名重复声明 = 作者错误，整张判坏（不猜哪条胜）。
            if (!seen.add(kind.token + '\u0000' + official)) {
                return bad("第 $i 行重复声明：${kind.token} $official")
            }
            entries += Entry(kind, official, mod)
        }
        return Sidecar(packageId, entries, true, emptyList())
    }

    /**
     * 从 `mods/rt2/` 读某包侧车。文件不存在 = 该包无覆盖声明（正常，返回 ok + 空表）；
     * 读失败 / 超上限 / 坏档 / 包名不符 ⇒ ok=false + 空表 + [logSink] 一行 WARN（不静默吞错）。
     */
    fun read(rt2Dir: File, packageId: String, logSink: (String) -> Unit = {}): Sidecar {
        val file = File(rt2Dir, packageId + SIDECAR_EXTENSION)
        if (!file.isFile) return Sidecar(packageId, emptyList(), true, emptyList())
        val text = try {
            if (file.length() > MAX_SIDECAR_BYTES) {
                return badRead(logSink, packageId, "侧车超字节上限（${file.length()} > $MAX_SIDECAR_BYTES）")
            }
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return badRead(logSink, packageId, "侧车读失败（${e::class.java.simpleName}: ${e.message}）")
        }
        val decoded = decode(text)
        if (!decoded.ok) {
            return badRead(logSink, packageId, decoded.diagnostics.firstOrNull() ?: "侧车解码失败")
        }
        if (decoded.packageId != packageId) {
            return badRead(logSink, packageId, "侧车包名不符：期望 $packageId，实为 ${decoded.packageId}")
        }
        return decoded
    }

    /** 资源名合法性：与 [ModResourceStore] 的 API 面校验同形（严格拒绝、不归一）。 */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME_LENGTH &&
            !name.contains('\\') && !name.startsWith('/') && !name.endsWith('/') &&
            name.split('/').all { segment ->
                segment.isNotEmpty() && segment != "." && segment != ".." &&
                    segment.all { ch -> ch.code >= FIRST_PRINTABLE && ch.code != DELETE_CHAR && ch != ':' }
            }

    /** 包身份形态（与 `ModResourceStore.PACKAGE_ID_PATTERN` 同口径）。 */
    private fun isPackageId(value: String): Boolean = PACKAGE_ID_PATTERN.matches(value)

    private fun bad(reason: String): Sidecar = Sidecar(null, emptyList(), false, listOf(reason))

    private fun badRead(logSink: (String) -> Unit, packageId: String, reason: String): Sidecar {
        logSink("[$packageId][WARN] mod.assetmap 整包覆盖不采信：$reason")
        return Sidecar(packageId, emptyList(), false, listOf(reason))
    }

    /** 头三行：magic / 包名 / 条目数（数据行从第 [HEADER_LINES] 行起）。 */
    private const val HEADER_LINES = 3

    private const val COLUMN_COUNT = 3
    private const val FIRST_PRINTABLE = 0x20
    private const val DELETE_CHAR = 0x7F
    private val PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}
