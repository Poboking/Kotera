package com.eratw.port.modgen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** `check-mod --changed-only` 的缓存条目（只有 [ModCheckCache] 会产出/消费它）。 */
internal data class ModCheckCacheEntry(
    val fingerprint: String,
    val packageId: String,
    val errors: Int,
    val warnings: Int,
)

/**
 * `check-mod --changed-only` 的结论缓存，落 `<mod>/build/modcheck/cache.json`。
 *
 * 为什么放 `<mod>/build`：那是模块自己的构建产物目录，已在 `.gitignore` 的 `build/` 规则下，
 * 天然不进仓库、可随手删、随 `clean` 消失；不写 `$TMPDIR` 是因为按绝对路径 hash 出的临时
 * 文件名跨 worktree/清理策略都不可预期，而模块内 build 目录语义上就是「可丢弃产物」。
 *
 * **安全边界**：只有「上次结论 0 error」的条目会被 [cachedEntry] 认；error 结论一律不写、
 * 也不认。缓存坏了、版本不对、指纹对不上、目录不可写 —— 全部静默当作没有缓存，退回全量。
 */
internal object ModCheckCache {
    /** 缓存文件格式版本；字段/语义变了解析自然失败并回退全量。 */
    const val FORMAT: String = "modcheck-cache/1"

    private const val CACHE_DIR = "build/modcheck"
    private const val CACHE_NAME = "cache.json"
    private const val SHORT_SHA256 = "64"

    private val FIELD_LINE = Regex("""^\s*"([A-Za-z]+)"\s*:\s*(.+?)\s*,?\s*$""", RegexOption.MULTILINE)
    private val SHA256 = Regex("[0-9a-f]{$SHORT_SHA256}")

    /** 缓存文件路径（只算路径，不碰磁盘）。 */
    fun cacheFile(root: Path): Path = root.toAbsolutePath().normalize().resolve(CACHE_DIR).resolve(CACHE_NAME)

    /** 读缓存；文件不存在/不是普通文件/读不出/内容不合法 ⇒ null（静默回退全量）。 */
    fun read(file: Path): ModCheckCacheEntry? =
        try {
            if (Files.isRegularFile(file)) parse(Files.readString(file)) else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** 写缓存；任何 IO/权限问题静默吞掉（缓存写失败不影响 check 结论）。返回是否真写成功。 */
    fun write(file: Path, entry: ModCheckCacheEntry): Boolean =
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, render(entry))
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    /** 命中判定：指纹一致**且**上次结论 0 error 才算命中；error 结论永不复用。 */
    fun cachedEntry(root: Path, fingerprint: String): ModCheckCacheEntry? =
        read(cacheFile(root))?.takeIf { entry -> entry.fingerprint == fingerprint && entry.errors == 0 }

    private fun parse(text: String): ModCheckCacheEntry? {
        val values = FIELD_LINE.findAll(text).associate { match ->
            match.groupValues[1] to match.groupValues[2].removeSurrounding("\"")
        }
        val fingerprint = values["fingerprint"]
        val packageId = values["packageId"]
        val errors = values["errors"]?.toIntOrNull()
        val warnings = values["warnings"]?.toIntOrNull()
        val valid = values["format"] == FORMAT &&
            values["tool"] == ModCheckFingerprint.TOOL_VERSION &&
            fingerprint != null && SHA256.matches(fingerprint) &&
            packageId != null && errors != null && warnings != null
        return if (valid) {
            ModCheckCacheEntry(
                fingerprint = fingerprint.orEmpty(),
                packageId = packageId.orEmpty(),
                errors = errors ?: 0,
                warnings = warnings ?: 0,
            )
        } else {
            null
        }
    }

    private fun render(entry: ModCheckCacheEntry): String = buildString {
        append("{\n")
        append("  \"format\": \"").append(FORMAT).append("\",\n")
        append("  \"tool\": \"").append(ModCheckFingerprint.TOOL_VERSION).append("\",\n")
        append("  \"fingerprint\": \"").append(entry.fingerprint).append("\",\n")
        append("  \"packageId\": \"").append(escape(entry.packageId)).append("\",\n")
        append("  \"errors\": ").append(entry.errors).append(",\n")
        append("  \"warnings\": ").append(entry.warnings).append("\n")
        append("}\n")
    }
}

/** JSON 字符串转义：缓存只存包名这类短标识，仍按最小可用集转义，避免坏值写坏整个文件。 */
private fun escape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
