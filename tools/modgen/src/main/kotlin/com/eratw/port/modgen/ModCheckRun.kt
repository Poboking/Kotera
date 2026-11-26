package com.eratw.port.modgen

import java.io.PrintStream
import java.nio.file.Path

/**
 * `check-mod` 的结果输出与增量缓存层。
 *
 * 检查语义只有一份真源 [ModCheck.check]（永远全量）；本对象只决定「先看缓存还是直接跑」、
 * 把 0 ERROR 的结论落盘、以及打印结果。`pack-mod` 等既有调用方直接调 [ModCheck.check]，
 * 因此不受缓存层影响。
 */
internal object ModCheckRun {
    private const val EXIT_OK = 0
    private const val EXIT_ERRORS = 1

    /** 全量路径：语义与加缓存前逐字一致（缺省、`--no-cache`、以及任何缓存不可用时的回退路径）。 */
    fun full(options: ModCheckOptions, out: PrintStream): Int =
        if (options.fixMode == FixMode.OFF) {
            checkAndEmit(options, out, null)
        } else {
            fixAndEmit(options, out)
        }

    /**
     * 修复模式：先按 schema 重算可修项（[ModCheckFix.plan]）→ `--fix` 真写 / `--dry-run` 只演练 →
     * **写后再跑一次全量**，退出码只由修复后的结论决定（修失败的会以原 ERROR 留在结论里）。
     */
    private fun fixAndEmit(options: ModCheckOptions, out: PrintStream): Int {
        val root = options.projectDir.toPath().toAbsolutePath().normalize()
        val planned = ModCheckFix.plan(root, options.schemaFiles)
        val fixes = if (options.fixMode == FixMode.FIX) ModCheckFix.apply(planned) else ModCheckFix.preview(planned)
        return checkAndEmit(options, out, fixes)
    }

    /** 普通全量：一次 [ModCheck.check] + 输出；[fixes] 为 null 时 JSON 仍是既有五键形状。 */
    private fun checkAndEmit(options: ModCheckOptions, out: PrintStream, fixes: List<FixResult>?): Int {
        val findings = ModCheck.check(options.projectDir, options.schemaFiles, options.resourcesDir)
        return if (emit(options, findings, out, fixes) == 0) EXIT_OK else EXIT_ERRORS
    }

    /**
     * 增量路径：指纹命中且上次 0 error ⇒ 只打印缓存命中行（`--json` 时仍输出稳定空报告），
     * 否则全量重算；只有 0 error 的结论才落缓存，error 结论永不缓存、每次重算报出。
     *
     * 指纹必须在 [ModCheck.check] **之前**算：先算后查若是并发改动，写回的指纹至多偏旧
     * （下次必失效），偏新才可能让后续命中复用陈旧结论。
     */
    fun incremental(options: ModCheckOptions, out: PrintStream): Int {
        val root = options.projectDir.toPath().toAbsolutePath().normalize()
        val fingerprint = ModCheckFingerprint.computeOrNull(root, options.schemaFiles, options.resourcesDir)
        val cached = fingerprint?.let { value -> ModCheckCache.cachedEntry(root, value) }
        if (cached != null) {
            if (options.json) {
                out.print(ModCheckJson.render(emptyList()))
            } else {
                out.println("check-mod ${cached.packageId}: up to date (cached)")
            }
            return EXIT_OK
        }
        val findings = ModCheck.check(options.projectDir, options.schemaFiles, options.resourcesDir)
        val errors = emit(options, findings, out, null)
        if (fingerprint != null && errors == 0) {
            val entry = ModCheckCacheEntry(
                fingerprint = fingerprint,
                packageId = packageId(root),
                errors = errors,
                warnings = findings.size - errors,
            )
            ModCheckCache.write(ModCheckCache.cacheFile(root), entry)
        }
        return if (errors == 0) EXIT_OK else EXIT_ERRORS
    }

    /** 结果输出（人类可读 / JSON 两种模式）；返回 ERROR 条数供调用方折算退出码。 */
    private fun emit(
        options: ModCheckOptions,
        findings: List<Finding>,
        out: PrintStream,
        fixes: List<FixResult>?,
    ): Int {
        val errors = findings.count { it.severity == Severity.ERROR }
        if (options.json) {
            // --json：stdout 只有一份机器可读文档（序列化见 [ModCheckJson]），不写 summary / 缓存提示。
            out.print(ModCheckJson.render(findings, fixes))
        } else {
            findings.forEach { out.println(ModCheck.format(it)) }
            fixes?.forEach { out.println(formatFix(it)) }
            out.println("check-mod: $errors error(s), ${findings.size - errors} warning(s)")
        }
        return errors
    }

    /** 人类可读面的单条修复行；状态词与 JSON 的 `status` 同源（[FixStatus]），不藏失败。 */
    private fun formatFix(fix: FixResult): String {
        val status = when (fix.status) {
            FixStatus.FIXED -> "fixed"
            FixStatus.WOULD_FIX -> "would fix"
            FixStatus.FAILED -> "fix failed"
        }
        val detail = fix.detail?.let { ": $it" }.orEmpty()
        return "check-mod: $status ${fix.label}$detail"
    }

    /** 缓存里记录的包名：取载体声明的 PACKAGE_ID，抽不到就退回模块目录名（与检查语义无关）。 */
    private fun packageId(root: Path): String =
        ModCheckResources.packageIds(ModCheckSources.scan(root, mutableListOf())).firstOrNull()
            ?: root.fileName?.toString().orEmpty()
}
