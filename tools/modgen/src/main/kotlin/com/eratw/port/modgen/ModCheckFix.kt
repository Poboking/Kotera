package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.AccessorEmit
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 一条可自动修复的生成物：[target] 应被 [expected] 的逐字节内容覆盖（内容由 schema 唯一确定）。 */
internal class PlannedFix(
    val target: Path,
    val label: String,
    val expected: ByteArray,
)

/** 单条修复的结局：真写了 / 只演练 / 写失败。 */
internal enum class FixStatus { FIXED, WOULD_FIX, FAILED }

/** 单条修复的观测结果；[detail] 只在 [FixStatus.FAILED] 时给失败原因。 */
internal class FixResult(
    val label: String,
    val status: FixStatus,
    val detail: String?,
)

/**
 * 原子写的最小文件操作面。生产实现是 [DefaultFixFileOps]；测试注入一份会让「改名」失败的
 * 实现，验证失败时目标文件一个字节都不动、且临时文件被清掉（回滚语义）。
 */
internal interface FixFileOps {
    fun write(path: Path, bytes: ByteArray)

    fun read(path: Path): ByteArray

    fun moveIntoPlace(tmp: Path, target: Path)

    fun deleteIfExists(path: Path)
}

/** 生产实现：写目标同目录的临时文件 → 回读校验 → `ATOMIC_MOVE` 覆盖目标 → 回读目标校验。 */
internal object DefaultFixFileOps : FixFileOps {
    override fun write(path: Path, bytes: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, bytes)
    }

    override fun read(path: Path): ByteArray = Files.readAllBytes(path)

    override fun moveIntoPlace(tmp: Path, target: Path) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

/**
 * `check-mod --fix` 的自动修复面：只重建「生成物内容与 schema 派生结果不一致」这一类。
 *
 * 判据是唯一真源 [AccessorEmit.emit]（纯函数，输入只有 schema 的 [SchemaDecl]）：把 emit 出来的
 * 字节逐字写回生成物，因此「修好了」严格等价于「生成物 == 生成器重跑的结果」，不引入任何猜测。
 *
 * 只修这一类、其余一律只报不修，理由：
 * - schema TSV 解析失败 / 生成器拒绝该 schema（[AccessorEmit] 抛 IAE）：修 = 猜作者意图
 *   （改 TSV 还是改声明都可能错），必须人来定，见 [planOne]。
 * - 生成物缺失（首建）：没有现成落点可写；落点路径约定来自模块自己的 generate 任务而不是
 *   check-mod，凭空造文件会写出 schema 未映射的输出，见 [planOne]。
 * - 声明缺 packageId / version、carrier 三方不一致、资源/媒体清单：要改的是作者源码或真实
 *   资源文件，自动改写会掩盖真实配置错误，属 [ModCheckDeclarations]/[ModCheckResources] 等面
 *   的只读诊断。
 *
 * 幂等：修完再 plan 一次为空（生成物已与 emit 逐字节相等），第二次 `--fix` 的 fixes 为空。
 */
internal object ModCheckFix {
    /** 临时文件后缀：与目标同目录，`ATOMIC_MOVE` 才是同一文件系统内的改名。 */
    const val TEMP_SUFFIX: String = ".tmp"

    private const val STAGED_MISMATCH = "临时文件回读与 emit 内容不一致"
    private const val LANDED_MISMATCH = "落盘回读与 emit 内容不一致"
    private const val WRITE_FAILED = "写入失败"

    /** 列出所有可修项（按生成物标签排序，保证输出确定性）；不写盘。 */
    fun plan(root: Path, schemaFiles: List<File>): List<PlannedFix> {
        val ignored = mutableListOf<Finding>()
        val sources = ModCheckSources.scan(root, ignored)
        val schemas = ModCheckSchemas.load(root, schemaFiles, ignored)
        val byBase = ModCheckSources.generatedByBase(sources)
        return schemas.mapNotNull { schema -> planOne(schema, byBase[schema.decl.baseName]) }.sortedBy { it.label }
    }

    /** 真写：逐条修复并返回结局（单条失败不影响其它条）。 */
    fun apply(planned: List<PlannedFix>, ops: FixFileOps = DefaultFixFileOps): List<FixResult> =
        planned.map { fix -> applyOne(fix, ops) }

    /** 演练：不写盘，只把「会修哪些」映射成 [FixStatus.WOULD_FIX]。 */
    fun preview(planned: List<PlannedFix>): List<FixResult> =
        planned.map { fix -> FixResult(fix.label, FixStatus.WOULD_FIX, null) }

    /** 可修 = 有现成生成物 + emit 成功 + 内容与 emit 不等；其余三类都返回 null（只报不修）。 */
    private fun planOne(schema: LoadedSchema, generated: SourceFile?): PlannedFix? =
        if (generated == null) {
            null
        } else {
            expectedContent(schema)?.let { expected -> staleFix(generated, expected) }
        }

    private fun staleFix(generated: SourceFile, expected: String): PlannedFix? =
        if (expected == generated.raw.replace("\r\n", "\n")) {
            null
        } else {
            PlannedFix(generated.path, generated.label, expected.toByteArray(Charsets.UTF_8))
        }

    private fun expectedContent(schema: LoadedSchema): String? =
        try {
            AccessorEmit.emit(schema.decl)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun applyOne(fix: PlannedFix, ops: FixFileOps): FixResult {
        val tmp = fix.target.resolveSibling(fix.target.fileName.toString() + TEMP_SUFFIX)
        return try {
            writeVerified(fix, tmp, ops)
        } catch (error: IOException) {
            failed(fix, tmp, ops, error.message)
        } catch (error: SecurityException) {
            failed(fix, tmp, ops, error.message)
        }
    }

    private fun writeVerified(fix: PlannedFix, tmp: Path, ops: FixFileOps): FixResult {
        ops.write(tmp, fix.expected)
        val staged = ops.read(tmp).contentEquals(fix.expected)
        if (staged) ops.moveIntoPlace(tmp, fix.target) else ops.deleteIfExists(tmp)
        return when {
            !staged -> FixResult(fix.label, FixStatus.FAILED, STAGED_MISMATCH)
            ops.read(fix.target).contentEquals(fix.expected) -> FixResult(fix.label, FixStatus.FIXED, null)
            else -> FixResult(fix.label, FixStatus.FAILED, LANDED_MISMATCH)
        }
    }

    /** 失败收口：目标文件保持原样；临时文件尽力清掉（清不掉只补一句，不覆盖原始失败原因）。 */
    private fun failed(fix: PlannedFix, tmp: Path, ops: FixFileOps, message: String?): FixResult {
        val cleaned = runCatching { ops.deleteIfExists(tmp) }.isSuccess
        val reason = message ?: WRITE_FAILED
        return FixResult(fix.label, FixStatus.FAILED, if (cleaned) reason else "$reason（临时文件清理失败）")
    }
}
