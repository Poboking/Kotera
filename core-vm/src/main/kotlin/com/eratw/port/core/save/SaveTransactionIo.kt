package com.eratw.port.core.save

import java.security.MessageDigest
import java.util.Locale

/**
 * 存档事务的磁盘能力端口（ERA Runtime 2.0 第 8 节；M1-5 的可注入 I/O 面）。
 *
 * 为什么单独一个端口而不复用 SaveFileIo：SaveFileIo 只有 exists、read、write、delete、
 * isReadOnly，且 write 把失败折成 Boolean；事务需要 fsync、定序换名、可用空间查询，
 * 以及「失败即抛」的语义——崩溃注入要能停在任意两次操作之间，布尔返回会把半截事务
 * 糊成一句 false。本接口全是相对路径、纯 JVM：core 不假定工作目录、不碰墙钟、
 * 不碰设备 API、不持静态可变全局。
 *
 * 实现由宿主注入（生产 = 应用私有目录的 NIO 实现，属「需组长接线」）；测试侧用内存
 * 假体 SaveTransactionFixture。路径一律由调用方给出，本层不做任何路径拼接或绝对化。
 *
 * 反编译 C# 锚（原版无本端口，此处只是说明为何要新增事务相位）：原版 SAVEDATA 是
 * VariableEvaluator.cs:2644 的 SaveTo，:2653 用 new FileStream(FileMode.Create) 原地截断
 * 覆盖写，:2666-2668 把一切异常吞成 return false，:2694 读档用 FileMode.Open——单文件、
 * 无 .bak、无 sidecar、无 journal、崩溃即截断且不可回滚。RT2 的配对事务与 fsync 换名
 * 属新增持久化相位，故本端口无 C# 对应物：TODO(verify) 权威 = 蓝图第 8 节与
 * SaveRecovery.kt 的顺序不变量。
 */
interface SaveTransactionIo {
    /** 读全部字节；文件不存在或读失败一律返回 null。 */
    fun read(path: String): ByteArray?

    /** 覆盖写 [bytes]；实现不折失败，出错直接抛，由编排器 fail-closed 收口。 */
    fun write(path: String, bytes: ByteArray)

    /** 把 [path] 已写入的字节刷到持久介质；纯 JVM 假体记一个事件即可。 */
    fun fsync(path: String)

    /** 定序换名：把 [from] 的字节搬到 [to]，[from] 随即不存在。 */
    fun rename(from: String, to: String)

    /** 删除；文件本来不存在也算成功。 */
    fun delete(path: String)

    /** 目标卷当前可用字节，空间预算门专用。 */
    fun availableBytes(): Long
}

/**
 * 事务侧 SHA-256 文本，64 位小写十六进制，与 SaveRecovery.normalizeSha 的形态一致。
 *
 * 纯函数、无状态：用 JDK 的 MessageDigest，不引 Android API、不引 kotlin.random。
 */
fun sha256Hex(bytes: ByteArray): String {
    val hex = "0123456789abcdef".toCharArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(64)
    for (b in digest) {
        val v = b.toInt() and 0xff
        sb.append(hex[(v ushr 4) and 0xf])
        sb.append(hex[v and 0xf])
    }
    return sb.toString().lowercase(Locale.ROOT)
}
