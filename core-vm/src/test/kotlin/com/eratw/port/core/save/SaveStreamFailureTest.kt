package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SAVE-STREAM 波失败语义闸：**流式写做什么都可以，就是不许毁掉玩家已有的好档**。
 *
 * ## 为什么必须单独钉这一条
 *
 * 流式化把「抓变量池」从落盘**前**挪进了落盘**中**（[SaveFormat.writeStreaming] 的 body
 * 边读活列边写），于是抓取期异常第一次变成了「producer 抛在临时文件写到一半时」。
 * 若 `SaveFileIo` 把它当普通 IO 失败走原地截断退路，玩家会看到：存档没成功、**旧档也没了**。
 * 这比原 bug 更糟，所以拿三条用例把它钉死：
 *
 * 1. producer 抛异常 ⇒ `write` 返回 false，**旧档字节一个不变**，且不留临时文件；
 * 2. 异常**原样传播**（不被吞成「写成功」），且压缩壳**不补 CRC/ISIZE** ——
 *    「写了一半的档」不许长得像合法档；
 * 3. 失败一次之后，同一路径下一次正常存档仍成功（native deflate 状态已释放，无跨调用污染）。
 *
 * 判据出处：`SaveFileIo.write` 的流式口 KDoc（producer 自身抛 = 序列化失败，不可退路），
 * 以及原版 `SaveTo` 的 `catch (Exception) { return false; }`（VariableEvaluator.cs:2666-2669）
 * —— 原版在这一层同样只是返回 false，从不半截覆盖。
 */
class SaveStreamFailureTest {

    private fun tempDir(): Path = Files.createTempDirectory("eratw-save-stream-fail")

    /** 一段「看起来像存档」的旧字节：只要一个字节都不被改动就能证明没截断。 */
    private fun oldBytes(): ByteArray = ByteArray(4096) { (it * 31 + 7).toByte() }

    @Test
    fun `producer 中途抛异常 - 旧档字节一个不变且不留临时文件`() {
        val root = tempDir()
        val path = "save00.sav"
        val io = RootedSaveFileIo(root)
        val old = oldBytes()
        root.resolve(path).writeBytes(old)

        var written = 0
        val ok = io.write(path) { out ->
            // 先写出一段真实字节，模拟「已经写了一半」
            out.write(ByteArray(1024) { 1 })
            written = 1024
            error("模拟抓取期异常（变量读失败 / 业务异常）")
        }

        assertFalse(ok, "producer 抛异常必须折成 false（原版 SaveTo 的 catch 口径）")
        assertEquals(1024, written, "开始写后才抛，证明这是「写到一半」的现场")
        assertTrue(
            root.resolve(path).readBytes().contentEquals(old),
            "旧档必须逐字节不变 —— 半截覆盖是把「存不上」升级成「档没了」",
        )
        val leftovers = Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(path), leftovers, "失败后不许留临时文件，实得 $leftovers")
    }

    @Test
    fun `压缩壳中途抛出 - 异常原样传播且不补 CRC 与 ISIZE`() {
        val out = ByteArrayOutputStream()
        val boom = IllegalStateException("模拟编码期异常")
        val caught = assertFailsWith<IllegalStateException> {
            SaveFormat.writeStreaming(true, out) { w ->
                w.fileType(SaveFileKind.NORMAL)
                w.i64(7153)
                w.i64(1808)
                w.str("半截")
                throw boom
            }
        }
        assertTrue(caught === boom, "异常必须原样传播（不许换类型、不许吞成返回值）")

        val bytes = out.toByteArray()
        // 16 B 容器头 + 10 B GZip 头之后就该断掉：没有正文尾巴、没有 CRC32/ISIZE。
        assertTrue(bytes.size >= 16 + 10, "头部应已写出，实得 ${bytes.size} 字节")
        assertFailsWith<SaveFormatException>(
            "半截档必须读不回来（若这里能读，说明异常路径补了假尾巴）",
        ) {
            SaveFormat.read(bytes)
        }
    }

    @Test
    fun `失败一次之后下一次正常存档仍成功 - 无跨调用状态污染`() {
        val root = tempDir()
        val path = "save01.sav"
        val io = RootedSaveFileIo(root)
        assertFalse(
            io.write(path) { out ->
                out.write(ByteArray(64))
                error("第一次失败")
            },
        )
        val ok = io.write(path) { out ->
            SaveFormat.writeStreaming(true, out) { w ->
                w.fileType(SaveFileKind.GLOBAL)
                w.i64(1)
                w.i64(2)
                w.str("")
                w.eof()
                w.eof()
            }
        }
        assertTrue(ok, "同一路径的下一次存档必须成功（defalter/缓冲不存在跨调用残留）")
        val back = SaveFormat.read(root.resolve(path).readBytes())
        assertEquals(SaveFileKind.GLOBAL, back.fileType)
    }
}
