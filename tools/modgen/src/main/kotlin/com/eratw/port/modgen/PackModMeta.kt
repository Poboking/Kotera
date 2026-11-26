package com.eratw.port.modgen

/**
 * 包级元数据：pack-mod 写进 zip 根的一条固定名 JSON 条目，导入端读它拿作者自述版本号。
 *
 * ## 为什么是「zip 内固定名条目」而不是 zip comment
 *
 * - **读到手的成本**：导入端手里只有字节（`ModImportController` ⓪ 相位把 zip 字节留在内存），
 *   固定名条目用 `ZipInputStream` 顺序扫就能取；zip 的中央目录注释只能经 `ZipFile`（要求落成
 *   磁盘文件）读，为一个版本号多写一个临时文件、多一个可失败的相位，不划算。
 * - **确定性**：条目内容是我们自己拼的固定字节（键序固定、无空白、无时间戳），加上既有
 *   「条目按名排序 + 1980-01-01 固定时间戳」，两次打包逐字节相同（有对拍用例）。
 * - **老工具兼容**：老导入端 `ModZip.unpack` 只按扩展名收 `.ERB`/`.ERH`，未知条目本就忽略；
 *   老 pack-mod 不写这条目，新导入端按「没有元数据」回落 `"1"` ⇒ 两个方向都 100% 兼容。
 * - **不被当资源误扫**：条目名在 zip 根、不是 `<packageId>.assets/` 前缀，资源面收集
 *   （`PackModZip.collect`）与导入端资源面（`ModImportAssets`）都不会把它当素材。
 * - **不撞市场容器合同**：市场安装包的容器清单也叫 `pack.json`（根级、schema 字段不同、
 *   契约外键直接拒）。这里用自有命名空间 `eratw-pack.json`，并自带 `schema` 版本，
 *   两个合同即使同处一个 zip 也不会互相误读。
 * - **可演进**：`schema` 是所有权与版本判据；导入端只认自己写出的 schema，认不出整体回落，
 *   未来加字段（多余字段被忽略）不需要改导入端。min-api 段就是这么加进来的：**只在作者声明
 *   （N > 0）时才追加键**，未声明时字节与加字段前完全相同（老包老夹具逐字节不变）。
 *
 * 条目名/字段与 app 侧 `com.eratw.port.android.ModImportVersion.kt` 的
 * [PACK_META_ENTRY_NAME] / `PACK_META_SCHEMA` / `parsePackMeta` 是**同一合同的两端**，
 * 改一处必须同步另一处（跨 Gradle 模块，无法共享常量，故两侧 KDoc 互相点名）。
 */

/** 元数据条目名：zip 根、大小写敏感，与 app 端 `PACK_META_ENTRY_NAME` 同值。 */
internal const val PACK_META_ENTRY_NAME = "eratw-pack.json"

/** 元数据 schema 版本：与 app 端 `PACK_META_SCHEMA` 同值；导入端不认的 schema 整体回落。 */
internal const val PACK_META_SCHEMA = 1

/** 导入端接纳的版本号长度上限（超限导入端回落 "1"，不如打包时就不写）；同 app 端形态闸。 */
internal const val PACK_META_MAX_VERSION_LENGTH = 32

/**
 * `compiledSurface` 接纳上限（含），与 app 端 `PACK_META_MAX_COMPILED_SURFACE` 同值。
 * 作者声明的是「编译期 mod-api surface 序号」，只会是小整数；超限只可能是误写/坏档，
 * 导入端会当未声明处理（只丢该字段），打包端干脆拒收。
 */
internal const val PACK_META_MAX_COMPILED_SURFACE = 1_000_000

/**
 * 元数据 JSON 编解码：只做「写得确定、读得回来」的最小集。
 *
 * 字段：`schema`（int，所有权与格式版本）、`packageId`（作者 `const val PACKAGE_ID`）、
 * `version`（作者 `modDeclarations(..., version = …)` 的解析值）、`minApi`（可选，作者声明的
 * 最低 mod-api 版本，见 core 的 `ModuleDescriptor.minApi` / manifest `/6` 的 `min-api` 段）、
 * `resources`（可选，ADR-014 旁挂资源目录名）、`compiledSurface`（可选，ADR-013 D2 编译期
 * mod-api surface）。顺序固定为 schema → packageId → version → minApi → resources →
 * compiledSurface，不留空白；多余字段导入端忽略，故本编解码器不认多余字段。
 *
 * 所有可选键都**只在作者显式声明时才写**：未声明时条目字节与加字段前逐字相同，这是线格式
 * 不变量（冻结夹具 CF/CJ 波对拍的就是这串字节，禁刷新）。
 *
 * `minApi == 0`（= 不设闸）时**不写这个键**：条目字节与引入 min-api 之前逐字相同，
 * 这是线格式不变量（冻结夹具 CF/CJ 波对拍的就是这串字节，禁刷新）。
 */
internal object PackModMeta {

    /**
     * 元数据条目字节（UTF-8、固定键序、无空白 ⇒ 完全确定）。
     *
     * @param minApi 作者声明的最低 mod-api 版本；0 = 不设闸 ⇒ 不写键（字节与无该字段时相同）
     * @param resources 作者自述资源目录名（ADR-014）；null = 不带旁挂资源树 ⇒ 不写键（字节与
     *   无该字段时相同）。写在 minApi 之后（minApi 缺省时紧随 version），保持既有键序。
     * @param compiledSurface 作者自述「本包编译于哪个 mod-api surface」（ADR-013 D2）；null =
     *   不写键（缺省省略，保持与 S2/S3 前的字节兼容——冻结夹具逐字节对拍的就是这串）。**必须
     *   落在 0..[PACK_META_MAX_COMPILED_SURFACE]**，写在 resources 之后（是最后一个可选键）。
     */
    fun json(
        packageId: String,
        version: String,
        minApi: Int = 0,
        resources: String? = null,
        compiledSurface: Int? = null,
    ): ByteArray {
        require(minApi >= 0) { "min-api 不能为负：$minApi（0 = 不设闸）" }
        require(compiledSurface == null || compiledSurfaceAccepted(compiledSurface)) {
            "compiled-surface 必须在 0..$PACK_META_MAX_COMPILED_SURFACE：$compiledSurface"
        }
        val text = buildString {
            append("{\"schema\":").append(PACK_META_SCHEMA)
            append(",\"packageId\":\"").append(escape(packageId))
            append("\",\"version\":\"").append(escape(version)).append("\"")
            if (minApi > 0) append(",\"minApi\":").append(minApi)
            if (resources != null) append(",\"resources\":\"").append(escape(resources)).append("\"")
            if (compiledSurface != null) append(",\"compiledSurface\":").append(compiledSurface)
            append("}")
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * 版本号是否落在导入端接纳形态内：非空、≤ [PACK_META_MAX_VERSION_LENGTH]、无控制字符。
     * 不合法就不写元数据（导入端反正会回落 "1"），并让 CLI 打一条 WARN 提示作者修声明。
     */
    fun versionAccepted(version: String): Boolean =
        version.isNotEmpty() && version.length <= PACK_META_MAX_VERSION_LENGTH &&
            version.all { char -> char.code >= CONTROL_LIMIT && char.code != DEL_CODE }

    /** `compiledSurface` 是否落在打包端/导入端共同接纳的范围内（0 也合法：surface 从 0 起）。 */
    fun compiledSurfaceAccepted(value: Int): Boolean = value in 0..PACK_META_MAX_COMPILED_SURFACE

    /** JSON 字符串转义（RFC 8259 必需集 + 控制字符走 `\u00XX`；非 ASCII 原样出 UTF-8）。 */
    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == '\b' -> append("\\b")
                char == '\u000C' -> append("\\f")
                char.code < CONTROL_LIMIT || char.code == DEL_CODE ->
                    append("\\u").append(char.code.toString(HEX_RADIX).padStart(ESCAPE_DIGITS, '0'))
                else -> append(char)
            }
        }
    }

    /** 版本号里允许的最小字符码（空格及以上；与 app 端形态闸同界）。 */
    private const val CONTROL_LIMIT = 0x20

    /** DEL：唯一一个不在 C0 区间却仍不可打印的 ASCII 控制字符。 */
    private const val DEL_CODE = 0x7F

    /** `\u00XX` 转义的固定位数。 */
    private const val ESCAPE_DIGITS = 4

    /** 十六进制基数（转义位宽计算用）。 */
    private const val HEX_RADIX = 16
}
