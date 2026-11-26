package com.eratw.port.modgen

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** manifest 字节 ⇒ 严格 UTF-8 文本（非法序列返回 null，不做替换字符兜底；[PackModCode] 与 `check-mod` 复用）。 */
internal fun strictUtf8OrNull(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (ignored: CharacterCodingException) {
        // 严格解码失败 = manifest 不是合法 UTF-8 ⇒ 上层按 NOT_UTF8_REASON 拒（不吞成替换字符）。
        null
    }

/**
 * 读代码产物内层 `manifest.json` 的文本（`check-mod` 静态面复用：核作者产物清单的 `entrypoint`）。
 *
 * 与 [PackModCode] 扫描口径同源：文件不存在/不是普通文件/超 [MAX_CODE_MOD_BYTES]/不是 zip/
 * 没有该条目/超 [MAX_CODE_MOD_MANIFEST_BYTES]/不是严格 UTF-8 ⇒ null（调用方按「读不到清单」处理，
 * 是否算错误由调用方的判据决定）。
 */
internal fun manifestJsonOf(file: File): String? =
    if (!file.isFile || file.length() > MAX_CODE_MOD_BYTES) null else readInnerManifest(file)

private fun readInnerManifest(file: File): String? =
    try {
        ZipFile(file).use { zip -> innerManifestText(zip) }
    } catch (_: IOException) {
        // 非 zip / 条目读坏：与 PackModCode 的「不是合法 zip」同档，这里只返回「读不到」。
        null
    }

private fun innerManifestText(zip: ZipFile): String? {
    val entry = zip.getEntry(INNER_MANIFEST_ENTRY) ?: return null
    val bytes = zip.getInputStream(entry).use { stream -> stream.readNBytes(MAX_CODE_MOD_MANIFEST_BYTES + 1) }
    return if (bytes.size > MAX_CODE_MOD_MANIFEST_BYTES) null else strictUtf8OrNull(bytes)
}
