package com.eratw.port.core.save

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 生产 [NioSaveTransactionIo] 的真盘验收（RT2 C 车，era-arch §8 :208「生产 NIO 实现落位」）：
 * 逐端口方法在临时目录上钉语义 —— read 缺失回 null、write 失败即抛且 force、rename 覆盖换名、
 * delete 幂等、availableBytes 取自文件存储、越界路径当场拒绝。
 */
class NioSaveTransactionIoTest {

    @TempDir
    lateinit var temp: Path

    private fun io(): NioSaveTransactionIo = NioSaveTransactionIo(temp)

    @Test
    fun `write 后 read 原样回来 - 缺失返回 null`() {
        val port = io()
        assertNull(port.read("a/b.bin"))
        port.write("a/b.bin", byteArrayOf(1, 2, 3))
        assertTrue(port.read("a/b.bin")!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `rename 覆盖换名且源消失`() {
        val port = io()
        port.write("x.part", byteArrayOf(9))
        port.write("x.target", byteArrayOf(1))
        port.rename("x.part", "x.target")
        assertFalse(Files.exists(temp.resolve("x.part")))
        assertTrue(port.read("x.target")!!.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `delete 幂等`() {
        val port = io()
        port.delete("ghost.bin") // 不存在也算成功
        port.write("real.bin", byteArrayOf(1))
        port.delete("real.bin")
        assertFalse(Files.exists(temp.resolve("real.bin")))
    }

    @Test
    fun `fsync 与 availableBytes 不抛且读数合理`() {
        val port = io()
        port.write("a.bin", byteArrayOf(1))
        port.fsync("a.bin")
        port.fsync("missing.bin") // 缺失静默（write 侧已 force）
        assertTrue(port.availableBytes() > 0)
    }

    @Test
    fun `越界路径当场拒绝`() {
        val port = io()
        try {
            port.write("../escape.bin", byteArrayOf(1))
            throw AssertionError("../ 逃逸应被拒绝")
        } catch (_: IllegalStateException) {
            // 期望
        }
        assertFalse(Files.exists(temp.parent!!.resolve("escape.bin")))
    }
}
