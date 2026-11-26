package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * `pack-mod --code <file>` 的**代码产物条目名**：zip 根级、固定名、大小写敏感（ADR-013 D1）。
 *
 * 内容 = 作者编译好的 `.mod` 包整份字节（一个含 `classes.dex` + `manifest.json` 的 zip）。
 * 导入端只认这一个名字：根级 `code.mod`，不做「`*.mod` 模糊匹配」，所以打包端也只能写死这个名字。
 *
 * 与 app 侧 `app/src/main/kotlin/com/eratw/port/android/ModImportCodeArtifacts.kt` 里的
 * `CODE_MOD_ENTRY` 是同一合同的两端，必须同值。构建工具不依赖 `:app`（方向不能倒过来），
 * 故按 [PackLimits] 的既有先例在构建期复制一份，由 `PackModCodeArtifactTest` 读 app 侧源码
 * 文本比对兜底：任一侧改了名字而另一侧没跟上 ⇒ 哨兵用例红。
 */
internal const val CODE_MOD_ENTRY: String = "code.mod"

/**
 * 代码产物 `.mod` 包（内层 zip 文件本身）的字节上限：4 MiB。
 *
 * 成品 dex 包一般 100KB–2MB，4 MiB 给 Kotlin 运行时与多 dex 留余量。与 app 侧同名常量同源同值
 * （`ModImportCodeArtifacts.kt`，由 `PackModCodeArtifactTest` 的哨兵比对兜底）：导入端按同一个
 * 上限拒收，打包端先拒可以让作者在出包时就看到判据与实测值，而不是装上后才失败。
 */
internal const val MAX_CODE_MOD_BYTES: Long = 4L * 1024L * 1024L

/** 代码产物内层 `manifest.json` 的字节上限：导入端只读一个小 JSON，超限即按坏包处理。 */
internal const val MAX_CODE_MOD_MANIFEST_BYTES: Int = 4096

/**
 * `pack-mod --code` 的内容校验面：只判「导入端收得下这份代码产物」，不做 schema 演进。
 *
 * ## 校验项（顺序即拒绝优先级）
 *
 * 1. 文件大小 ≤ [MAX_CODE_MOD_BYTES]（超限拒绝，理由点名实测字节数与上限）；
 * 2. 用 [ZipInputStream] 从头读到尾：不是合法 zip / 条目读坏（CRC 错等 [IOException]）⇒ 拒绝，
 *    理由点名「不是合法 zip」；
 * 3. 必须含**非空** `classes.dex` 条目（作者编译产物，空 dex 装不进引擎）；
 * 4. 必须含 `manifest.json` 条目且 ≤ [MAX_CODE_MOD_MANIFEST_BYTES] 字节；
 * 5. `manifest.json` 按 UTF-8 **严格**解码（非法字节序列直接拒），再按 [parseStrictJson] 严格解析成
 *    JSON；顶层必须是对象、且有字符串键 `packageId`；
 * 6. `packageId` 过形态闸 `[A-Za-z0-9][A-Za-z0-9._-]*`（后续消息回显的值因此不含控制字符）；
 *    模块取不到 `const val PACKAGE_ID`（`packageId == null`）⇒ 拒绝并说明「无法校验代码产物身份」；
 *    与模块 `const val PACKAGE_ID` 不等 ⇒ 拒绝，理由点名两边值；
 * 7. `manifest.json` 必须有字符串键 `entrypoint`（装载期 `ModDexModuleLoader` 靠它实例化入口类，
 *    缺键的包装上后整包被 `InvalidManifest` 跳过 ⇒ 打包端先拒）；
 * 8. `entrypoint` 过形态闸 [CODE_ENTRYPOINT_SHAPE]，且与 `pack-mod --entrypoint <fqn>` **逐字相等**
 *    （不等 ⇒ 拒绝，理由点名清单里与命令行两边的值）；
 * 9. 可选的 `apiVersion` 键：**存在**时必须是 JSON 数字（作者自述的 mod-api 版本；导入端目前不消费，
 *    故只判类型，不做数值上下界——数值闸属引擎侧装载判据）。
 *
 * ## 退出码口径
 *
 * - `--code` 路径不存在 / 不是普通文件：**不归本对象**，由 `PackMod.pack` 里的 `require` 抛出
 *   `IllegalArgumentException`，CLI 统一按用法错处理（退出码 2，与 `--out` 路径问题同档）；
 * - 本对象只判**内容**：返回 null = 通过，非 null = 拒绝理由；调用方走 `PackMod.refuse`（退出码 1）。
 *
 * 内层 zip 只顺序扫一遍、逐条目读干净（读到条目尾才会触发 CRC 校验），内存占用与条目数线性但
 * 只保留 `manifest.json` 的 ≤4KB 字节；`classes.dex` 只判「是否非空」，内容原样透传不解析
 * （dex ABI 闸在 app 侧 `ModDexGate`，打包端不重复实现）。
 *
 * 扫描期的可变状态收在文件级 [ScanState] 里、按条目拆成 [absorbEntry] / [absorbManifest] 两个
 * 写点；本对象只留「怎么扫」与「扫描结果怎么判」两类函数（detekt `TooManyFunctions` 阈值内）。
 */
internal object PackModCode {

    /** 内层 zip 里作者编译产物的条目名（导入端同名字段）。 */
    private const val CLASSES_DEX_ENTRY = "classes.dex"

    /** 读 zip 条目的缓冲字节数（只为读到条目尾触发校验，不保留内容）。 */
    private const val READ_BUFFER_BYTES = 8 * 1024

    private const val NOT_A_ZIP_REASON =
        "不是合法 zip（读不到任何 zip 条目；code.mod 必须是作者编译好的 .mod 包，即一个 zip）"

    private const val NO_CLASSES_DEX_REASON =
        "code.mod 里没有 classes.dex 条目（内层 zip 必须是作者编译好的 .mod 包）"

    private const val EMPTY_CLASSES_DEX_REASON =
        "code.mod 里的 classes.dex 是空条目（内层 zip 必须带非空编译产物）"

    private const val NO_MANIFEST_REASON =
        "code.mod 里没有 manifest.json 条目（无法校验代码产物身份与 packageId）"

    /**
     * 校验 [file] 是否可作为 `--code` 代码产物（null = 通过，非 null = 拒绝理由）。
     *
     * @param packageId 模块源码里的 `const val PACKAGE_ID`（[ModCheckResources.packageIds] 的首项）；
     *   null = 模块没声明身份，此时拒绝并使用「无法校验代码产物身份」的理由。
     * @param entrypoint 命令行 `--entrypoint <fqn>`；内层清单的 `entrypoint` 必须与它逐字相等。
     */
    fun problemOf(file: File, packageId: String?, entrypoint: String?): String? {
        val size = file.length()
        return if (size > MAX_CODE_MOD_BYTES) {
            oversizedReason(size)
        } else {
            when (val scan = scanZip(file)) {
                is ScanResult.Rejected -> scan.reason
                is ScanResult.Accepted -> declaredManifestProblem(scan.manifest, packageId, entrypoint)
            }
        }
    }

    /** 已通过 [problemOf] 的产物读成 zip 条目（≤4 MiB，整份进内存，与元数据条目同一写入口）。 */
    fun entryOf(file: File): GeneratedEntry = GeneratedEntry(CODE_MOD_ENTRY, file.readBytes())

    /** 顺序扫内层 zip；读坏统一归到「不是合法 zip」，条目级判据在 [scanEntries] 里收口。 */
    private fun scanZip(file: File): ScanResult =
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip -> scanEntries(zip) }
        } catch (error: IOException) {
            ScanResult.Rejected(notZipReason(error))
        }

    /**
     * 逐条目读干净（读到 -1 才触发 CRC 校验），收 `classes.dex` 的非空与 `manifest.json` 的字节。
     * 内层条目数没有上界判据：真正的包级预算由导入端 `ModZipGuard` 与 [PackLimits] 数外层条目，
     * 内层 zip 是作者自己的 .mod 包，这里只判「读得干净 + 必备条目齐」。
     */
    private fun scanEntries(zip: ZipInputStream): ScanResult {
        val state = ScanState()
        while (true) {
            val entry = zip.nextEntry ?: break
            state.entries += 1
            absorbEntry(zip, entry, state)
            state.rejection?.let { return ScanResult.Rejected(it) }
            zip.closeEntry()
        }
        return verdictOf(state)
    }

    /** 按条目名把内容分派给对应吸收器：两件必备条目各记状态，其余读干净即可。 */
    private fun absorbEntry(zip: ZipInputStream, entry: ZipEntry, state: ScanState) {
        when (entry.name) {
            CLASSES_DEX_ENTRY -> state.dex = readDexState(zip, state.dex)
            INNER_MANIFEST_ENTRY -> absorbManifest(zip, state)
            else -> drain(zip)
        }
    }

    /** 重复的 `manifest.json`：只认第一份；超限记拒绝理由（由 [scanEntries] 的循环收口）。 */
    private fun absorbManifest(zip: ZipInputStream, state: ScanState) {
        if (state.manifest != null) {
            drain(zip)
        } else {
            val bytes = readCapped(zip)
            if (bytes == null) state.rejection = manifestTooLargeReason() else state.manifest = bytes
        }
    }

    /** 扫描结束后按拒绝优先级判：空 zip → 缺/空 dex → 缺 manifest → 通过。 */
    private fun verdictOf(state: ScanState): ScanResult {
        val manifest = state.manifest
        return when {
            state.entries == EMPTY_ENTRY_COUNT -> ScanResult.Rejected(NOT_A_ZIP_REASON)
            state.dex == DexState.MISSING -> ScanResult.Rejected(NO_CLASSES_DEX_REASON)
            state.dex == DexState.EMPTY -> ScanResult.Rejected(EMPTY_CLASSES_DEX_REASON)
            manifest == null -> ScanResult.Rejected(NO_MANIFEST_REASON)
            else -> ScanResult.Accepted(manifest)
        }
    }

    /** `classes.dex` 只判非空；读到的内容不保留（透传的是整个文件字节，不是重新拼的 zip）。 */
    private fun readDexState(zip: ZipInputStream, current: DexState): DexState {
        if (current != DexState.MISSING) {
            drain(zip)
            return current
        }
        val empty = zip.read() < 0
        drain(zip)
        return if (empty) DexState.EMPTY else DexState.NON_EMPTY
    }

    /** 读 `manifest.json`，超过 [MAX_CODE_MOD_MANIFEST_BYTES] 立即返回 null（不再读完整条目）。 */
    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_CODE_MOD_MANIFEST_BYTES) return null
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    /** 把当前条目读到尾（触发 CRC 校验）；内容丢弃，固定缓冲区不随条目大小增长。 */
    private fun drain(zip: ZipInputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var read = zip.read(buffer)
        while (read >= 0) {
            read = zip.read(buffer)
        }
    }
}

/**
 * 内层 zip 扫描期的唯一可变账本：`classes.dex` 状态、已读到的 manifest 字节、条目计数、
 * 以及首个拒绝理由。只有 [PackModCode] 的条目吸收器会写它，扫描循环只读 [rejection]。
 */
/** 内层条目一个都没有：按「不是 zip」判（空文件 / 随机字节都落到这里）。 */
private const val EMPTY_ENTRY_COUNT = 0

/** 实测字节数超上限的拒绝理由（回显实测与上限，便于作者判断该裁哪部分）。 */
private fun oversizedReason(actual: Long): String =
    "code.mod 文件 $actual 字节，超过上限 $MAX_CODE_MOD_BYTES 字节" +
        "（${MAX_CODE_MOD_BYTES / PackLimits.BYTES_PER_MIB} MiB）"

private fun notZipReason(error: IOException): String =
    "不是合法 zip（条目读坏：${error.message ?: error.javaClass.simpleName}）"

private fun manifestTooLargeReason(): String =
    "code.mod 里的 manifest.json 超过 $MAX_CODE_MOD_MANIFEST_BYTES 字节上限"
private class ScanState(
    var dex: DexState = DexState.MISSING,
    var manifest: ByteArray? = null,
    var entries: Int = EMPTY_ENTRY_COUNT,
    var rejection: String? = null,
)

/** 内层 zip 扫描结果：拒绝理由或已读到的 manifest 字节。 */
private sealed interface ScanResult {
    class Accepted(val manifest: ByteArray) : ScanResult

    class Rejected(val reason: String) : ScanResult
}

/** `classes.dex` 的出现状态（缺条目 / 空条目 / 非空条目）。 */
private enum class DexState {
    MISSING,
    EMPTY,
    NON_EMPTY,
}
