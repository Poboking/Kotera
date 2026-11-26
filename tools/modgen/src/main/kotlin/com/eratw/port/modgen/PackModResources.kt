package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/**
 * 旁挂资源树（ADR-014 切片 S3）：`pack-mod --resources <dir>` 把作者自述资源目录打进包。
 *
 * ## 为什么是两棵旁挂树而不是塞进 `<packageId>.assets/`
 *
 * 图/文本走游戏根的 `resources/mod/<dir>/…`（`LOADTEXT` / `GCREATEFROMFILE` 按路径消费），音频走
 * `sound/mod/<dir>/…`（`PLAYSOUND` 按路径消费）。两棵树的 zip 内条目路径就是**游戏根相对路径**，
 * 导入端解包后原样落在游戏根下，ERB 侧零新内建（见 ADR-014 D2/D3）。
 *
 * ## 名字判据只有一份
 *
 * - `<dir>`：包身份同源的 ASCII 单段名（[isMediaDirName]：`[A-Za-z0-9][A-Za-z0-9._-]*`、
 *   ≤ [MAX_MEDIA_DIR_CHARS]）。比运行期导入端谓词 [isImportSourcePrefix] 更严 —— `<dir>` 要手写进
 *   ERB 源码字面量（`GCREATEFROMFILE "mod/<dir>/a.png"`），而 ERB 源码是 Shift-JIS，中文/emoji
 *   在源文件里写不出来或会被编码破坏，放行只会给作者一个用不了的目录名。
 * - 树内相对名：复用 [isLegalResourceName]；整条 zip 条目名再过
 *   [PackModZip.isSafeEntryName] 纵深防御。
 * - 扩展名白名单：图/文只 png/jpg/jpeg/webp/txt（**禁 .csv**：`ResourceCsvHost.listCsvFiles()`
 *   递归扫 `resources/` 且只收 .csv，放进去会污染资源名表 —— ADR-014 组长裁定③）；音频只
 *   mp3/ogg/wav。
 *
 * ## 限额（bytes）
 *
 * 图/文沿用运行期同一份常量 [ModResourceLimits.MAX_RESOURCE_FILE_BYTES]（4 MiB/文件）与
 * [ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES]（16 MiB/包）；音频 8 MiB/文件、32 MiB/包
 * （组长裁定 D5）；两树合计 ≤ [MAX_TREES_TOTAL_BYTES]（48 MiB）。超限走 [PackUnsafe] 通道 ⇒
 * 退出码 1，消息点名条目、判据与实测值。
 *
 * 两棵树都不存在时 [collect] 返回空、[hasTree] 为 false ⇒ 调用方不写 `resources` 字段，
 * 包字节与加参数前逐字相同（线格式不变量，见 [PackModMeta]）。
 */
internal object PackModResources {

    /** 图/文本树根（游戏根相对）：`resources/mod/<dir>/…`。 */
    const val RESOURCES_ROOT = "resources"

    /** 音频树根（游戏根相对）：`sound/mod/<dir>/…`。 */
    const val SOUND_ROOT = "sound"

    /** 两棵树共同的二级前缀，与 ERB 消费面 `mod/<dir>/…` 对应。 */
    const val MOD_SEGMENT = "mod"

    /** 音频单文件上限（组长裁定 D5）：8 MiB。 */
    const val MAX_AUDIO_FILE_BYTES: Long = 8L * 1024L * 1024L

    /** 音频单包总量上限（组长裁定 D5）：32 MiB。 */
    const val MAX_AUDIO_TOTAL_BYTES: Long = 32L * 1024L * 1024L

    /** 两棵资源树合计上限（组长裁定 D5）：48 MiB。 */
    const val MAX_TREES_TOTAL_BYTES: Long = 48L * 1024L * 1024L

    /**
     * 图/文本扩展名白名单（小写，含点号版本按去点比较）。
     *
     * **单一真源**：`check-mod` 的旁挂资源面（[ModCheckMediaResources]）直接读本集合做作者面预览，
     * 不在那边复制白名单字面量。
     */
    internal val IMAGE_TEXT_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "txt")

    /**
     * 音频扩展名白名单。
     *
     * **单一真源**：同 [IMAGE_TEXT_EXTENSIONS]，`check-mod` 复用本集合。
     */
    internal val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "wav")

    /** unsafe 通道的统一建议语（消息形态照既有 `- 条目：原因；建议…`）。 */
    private const val HINT = "把该文件移出旁挂资源树，或按白名单/限额改写后重新打包"

    /**
     * 两树合计超限的 unsafe 条目名（不是真实条目，只作拒绝消息的归属）。
     *
     * **单一真源**：`check-mod` 的两树合计预览（[ModCheckMediaResources]）复用同一标签。
     */
    internal const val TREES_LABEL = "resources+sound"

    /**
     * `--resources` 取值是否是合法目录名；null = 合法，否则返回点名判据的原因。
     *
     * 判据单一真源在 [isMediaDirName]（包身份字符集 + 长度上限），这里不复制规则。
     */
    fun dirProblem(dir: String): String? =
        if (isMediaDirName(dir)) {
            null
        } else {
            "--resources 取值「$dir」必须是包身份同源的 ASCII 目录名" +
                "（[A-Za-z0-9][A-Za-z0-9._-]*，≤ $MAX_MEDIA_DIR_CHARS 字符）"
        }

    /** 任一棵树（`resources/mod/<dir>` 或 `sound/mod/<dir>`）存在即为声明的资源面。 */
    fun hasTree(root: Path, dir: String): Boolean = treeRoots(root, dir).any { Files.isDirectory(it) }

    /** 两棵树的绝对路径（按 [RESOURCES_ROOT]、[SOUND_ROOT] 顺序，不判存在性）。 */
    fun treeRoots(root: Path, dir: String): List<Path> = listOf(
        root.resolve(RESOURCES_ROOT).resolve(MOD_SEGMENT).resolve(dir),
        root.resolve(SOUND_ROOT).resolve(MOD_SEGMENT).resolve(dir),
    )

    /** 资源树收集结果：收下的条目 + 被拒的条目（拒收原因已在 [unsafe] 里），供摘要与闸共用。 */
    class Collected(val entries: List<PackEntry>, val totalBytes: Long)

    /**
     * 一棵树的判据组：树根名 + 扩展名白名单 + 单文件/单包上限（参数打包，避免超长参数表）。
     *
     * **单一真源**：`check-mod` 的旁挂资源面（[ModCheckMediaResources]）复用同一判据组与
     * [entryProblem]，作者自检与实际打包不会分出两套白名单/限额。
     */
    internal class TreeSpec(
        val rootName: String,
        val allowedExtensions: Set<String>,
        val maxFileBytes: Long,
        val maxTotalBytes: Long,
    )

    /** 图/文本树判据：沿用运行期同源的 4 MiB / 16 MiB（[ModResourceLimits]）。 */
    internal val IMAGE_TEXT_TREE = TreeSpec(
        RESOURCES_ROOT,
        IMAGE_TEXT_EXTENSIONS,
        ModResourceLimits.MAX_RESOURCE_FILE_BYTES,
        ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES,
    )

    /** 音频树判据：组长裁定 D5 的 8 MiB / 32 MiB。 */
    internal val AUDIO_TREE = TreeSpec(SOUND_ROOT, AUDIO_EXTENSIONS, MAX_AUDIO_FILE_BYTES, MAX_AUDIO_TOTAL_BYTES)

    /**
     * 收集两棵树下的全部普通文件为 zip 条目（条目名 = 游戏根相对路径），逐条判合法性与限额，
     * 违规进 [unsafe]（调用方据此拒绝打包）。目录不存在则跳过。
     *
     * 大小写不敏感扩展名判定；符号链接目录不跟（与 [PackModZip] 的收集口径一致）。
     */
    fun collect(root: Path, dir: String, unsafe: MutableList<PackUnsafe>): Collected {
        val resources = collectTree(root, dir, IMAGE_TEXT_TREE, unsafe)
        val audio = collectTree(root, dir, AUDIO_TREE, unsafe)
        val totalBytes = resources.totalBytes + audio.totalBytes
        if (totalBytes > MAX_TREES_TOTAL_BYTES) {
            unsafe += PackUnsafe(
                TREES_LABEL,
                "两棵资源树合计 $totalBytes 字节（${totalBytes / PackLimits.BYTES_PER_MIB} MiB）" +
                    "超过上限 $MAX_TREES_TOTAL_BYTES 字节（${MAX_TREES_TOTAL_BYTES / PackLimits.BYTES_PER_MIB} MiB）",
                HINT,
            )
        }
        return Collected(resources.entries + audio.entries, totalBytes)
    }

    /** 收一棵树；返回收下的条目与该树全部普通文件字节数（含被拒文件，作预算分母）。 */
    private fun collectTree(root: Path, dir: String, spec: TreeSpec, unsafe: MutableList<PackUnsafe>): Collected {
        val treeRoot = root.resolve(spec.rootName).resolve(MOD_SEGMENT).resolve(dir)
        if (!Files.isDirectory(treeRoot)) return Collected(emptyList(), 0L)
        val entries = mutableListOf<PackEntry>()
        var totalBytes = 0L
        walkFiles(treeRoot).forEach { path ->
            val relative = ModCheckSources.relativeLabel(treeRoot, path)
            val name = "${spec.rootName}/$MOD_SEGMENT/$dir/$relative"
            val bytes = Files.size(path)
            totalBytes += bytes
            val reason = entryProblem(name, relative, path, spec)
            if (reason == null) entries += PackEntry(name, path) else unsafe += PackUnsafe(name, reason, HINT)
        }
        if (totalBytes > spec.maxTotalBytes) {
            val label = "${spec.rootName}/$MOD_SEGMENT/$dir/"
            unsafe += PackUnsafe(
                label,
                "该树合计 $totalBytes 字节（${totalBytes / PackLimits.BYTES_PER_MIB} MiB）" +
                    "超过上限 ${spec.maxTotalBytes} 字节（${spec.maxTotalBytes / PackLimits.BYTES_PER_MIB} MiB）",
                HINT,
            )
        }
        return Collected(entries, totalBytes)
    }

    /**
     * 单个条目的拒收原因（null = 收得下）：相对名合法、扩展名白名单、单文件限额、整名安全。
     *
     * **单一真源**：`check-mod` 的作者面预览（[ModCheckMediaResources.auditFile]）直接复用本函数，
     * 消息逐字与打包端一致；那边只额外补「接近上限」WARN，不改本函数的 ERROR 判据。
     */
    internal fun entryProblem(name: String, relative: String, path: Path, spec: TreeSpec): String? {
        val allowedExtensions = spec.allowedExtensions
        val maxFileBytes = spec.maxFileBytes
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return when {
            !isLegalResourceName(relative) ->
                "相对路径「$relative」非法（前导/尾随斜杠、空段、点段与 .. 段、反斜杠、控制字符、盘符冒号）"
            !PackModZip.isSafeEntryName(name) ->
                "条目名「$name」非法（前导/尾随斜杠、空段、点段与 .. 段、反斜杠、控制字符、盘符冒号）"
            extension !in allowedExtensions ->
                "扩展名「$extension」不在白名单（允许 ${allowedExtensions.sorted().joinToString("/")}）"
            Files.size(path) > maxFileBytes ->
                "文件 $maxFileBytes 字节上限被超过：实测 ${Files.size(path)} 字节" +
                    "（上限 ${maxFileBytes / PackLimits.BYTES_PER_MIB} MiB）"
            else -> null
        }
    }

    /**
     * 无目录条目的递归文件清单（不跟符号链接目录，与 [PackModZip] 同口径）。
     *
     * **单一真源**：`check-mod` 的旁挂资源面（[ModCheckMediaResources]）用同一遍历口径做限额预览。
     */
    internal fun walkFiles(root: Path): List<Path> {
        val stream = Files.walk(root)
        return try {
            stream.filter { Files.isRegularFile(it) }.toList()
        } finally {
            stream.close()
        }
    }
}
