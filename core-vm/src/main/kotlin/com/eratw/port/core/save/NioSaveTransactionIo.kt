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
 * - [availableBytes] = 根所在卷的可用字节（空间预算门，`SaveTransaction.execute` 第 1 步）；
 *   探测不到时返回 [SaveTransactionIo.AVAILABLE_UNKNOWN] 而不是抛，理由见该方法。
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
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            // write 可能部分写入，必须循环到缓冲耗尽：单次写在大缓冲上会静默留下半截文件，
            // 而事务的第 4 步紧接着按 SHA-256 回读对账（SaveTransaction.execute），截断在这里
            // 只会表现成「.part 指纹不符」，把一个可修的写盘缺陷伪装成数据损坏。
            // 与 SaveFileIo.writeByAtomicReplace 同一条纪律（SaveFileIo.kt:262-264）。
            while (buffer.hasRemaining()) channel.write(buffer)
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

    /**
     * 根所在卷的可用字节；探测不到时返回 [SaveTransactionIo.AVAILABLE_UNKNOWN]。
     *
     * **不许用 `java.nio.file.Files.getFileStore`**：Android 的 libcore 把它整段换成
     * `throw SecurityException`（AOSP platform/libcore
     * `ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java:373-389`），而 android.jar
     * 里签名照旧在——桌面 JVM 更能正常返回，于是「桌面单测绿、真机开局 SAVEGLOBAL 直接
     * 失败」成了这条路的默认结局（0.2.48 现场）。本仓已有的同款取数口是
     * `platform` 侧 GameAssetInstaller 的 freeBytes（第 1974 至 1987 行），这里照它的
     * 语义复刻，只是常量的取法换成端口哨兵。
     *
     * 取数用 `File.usableSpace`（Android 上由 libcore 正常实装，同样是 statvfs 直读）。
     * 三种「不知道」一律折成 [SaveTransactionIo.AVAILABLE_UNKNOWN] 并留痕：
     * - 根目录尚不存在（开局首存、目录还没建）：沿父链找最近存在的祖先再探测；
     * - 读数 <= 0：老 ROM / 外部根走 FUSE 时 statvfs 会失败报 0，按「没空间」判会把
     *   这类机型永远挡在存档门外（同 GameAssetInstaller 的判据）；
     * - 探测过程抛 SecurityException（Android 的 getFileStore 桩、权限被削）或 IOException
     *   （外部根 / document provider 取用量失败）。只吞这两类已知形态：其余异常按端口违约
     *   直接冒泡，取数口不用 `catch (Throwable)` 连 OOM 一起吞。
     * 真盘满不会因此漏判：写不下时 [write] / [rename] 带着路径抛 IO 错，
     * `SaveTransaction.execute` 第 3 步 fail-closed 收口。
     */
    override fun availableBytes(): Long {
        val unknown = SaveTransactionIo.AVAILABLE_UNKNOWN
        // 留痕的 path 位按 SaveDiagnostics 的约定给相对口径：端口这一层拿不到相对路径，
        // 就用固定 token，绝不把游戏根绝对路径塞进会随错误报告出网的缓冲。
        val label = "<transactionRoot>"
        return try {
            var probe: java.io.File? = base.toFile()
            while (probe != null && !probe.exists()) probe = probe.parentFile
            val free = probe?.usableSpace ?: 0L
            if (free > 0L) {
                free
            } else {
                SaveDiagnostics.note("SAVETX", label, "空间探测读数=$free（视同探测失败）")
                unknown
            }
        } catch (t: SecurityException) {
            // 权限被削 / Android 的 getFileStore 一类安全异常：视同读不到，不否决存档。
            SaveDiagnostics.note("SAVETX", label, "空间探测抛 ${t.javaClass.name}: ${t.message}")
            unknown
        } catch (t: java.io.IOException) {
            // 外部根（document provider 一类）在取用量时可能报 I/O 错：同样视同读不到。
            SaveDiagnostics.note("SAVETX", label, "空间探测抛 ${t.javaClass.name}: ${t.message}")
            unknown
        }
    }
}
