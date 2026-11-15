package com.eratw.port.core.save

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * [SaveTransactionIo] 的生产 NIO 实现（RT2 C 车，era-arch §8 :208「生产 NIO 实现与调用点」）：
 * 全部路径相对 [root] 解析，根 = 游戏根（与 `RootedSaveFileIo` 同一根，应用私有目录，
 * **不走 SAF** —— `GameStorage.kt:39/:47-48`）。
 *
 * 语义逐条对齐端口契约：
 * - [read] 不存在 / 读失败返回 null（journal / 三候选对账都靠 null 判「不在」）；
 * - [write] 失败即抛（fail-closed），写完 `force(true)` 才返回 —— journal 与 .bak 的
 *   持久性是恢复判定可成立的前提，折成布尔会把半截事务糊过去；
 * - [rename] 同目录换名（.part → 目标），目标已存在则替换；
 * - [delete] 幂等；
 * - [availableBytes] = 根所在文件存储的可用字节（空间预算门，`SaveTransaction.execute` 第 1 步）。
 *
 * 防越界：相对路径一律 `normalize` 后必须仍落在根内（`..` 逃逸当场抛）——
 * 成员路径虽都由 core 自己拼（`config.normalPath` 直拼），端口仍按既有
 * `SaveImport.commit` 的「根内约束」纪律自证。
 *
 * 纯 JVM（java.nio.file 在 core-vm 有既有先例：`RootedSaveFileIo` / CsvLoader 系）。
 */
class NioSaveTransactionIo(private val root: Path) : SaveTransactionIo {

    private val base: Path = root.toAbsolutePath().normalize()

    private fun resolve(path: String): Path {
        val resolved = base.resolve(path).normalize()
        check(resolved != base && resolved.startsWith(base)) { "事务路径越出存档根: $path" }
        return resolved
    }

    override fun read(path: String): ByteArray? = try {
        val target = resolve(path)
        if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
    } catch (_: Exception) {
        null
    }

    override fun write(path: String, bytes: ByteArray) {
        val target = resolve(path)
        target.parent?.let { Files.createDirectories(it) }
        java.nio.channels.FileChannel.open(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    override fun fsync(path: String) {
        val target = resolve(path)
        if (!Files.isRegularFile(target)) return
        java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun rename(from: String, to: String) {
        Files.move(resolve(from), resolve(to), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(path: String) {
        Files.deleteIfExists(resolve(path))
    }

    override fun availableBytes(): Long = Files.getFileStore(base).usableSpace
}
