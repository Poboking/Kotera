package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SAVE-STREAM 波的两条硬判据：**字节等价**与**写路不物化池**。
 *
 * ## 为什么单独建一份
 *
 * 玩家反馈「一个游戏天玩了 38 分钟，存档报 SAVEDATA命令在存档时发生了意料之外的错误，
 * 试了三次也存不了」。根因不在存档逻辑本身，而在**存档路径把整个变量池又复制了一份**：
 * 原版 `SaveTo` 直接把活变量逐条写进 `FileStream`（`VariableEvaluator.cs:2591-2606`，
 * 变量取数组走 `GetArray()` 的 `return array`，:76/:168/:268 —— 零拷贝），移植侧此前
 * 先 `SaveCapture.normal` 建 `SaveData`（列 `copyOf()`、未分配列整列造零、每角色逐列新建
 * 数组）再编码。单次 `SAVEDATA` 瞬时分配 50 MiB 量级，在 88%~93% 稳态水位上必然失败。
 *
 * 修法把写路换成「访问器直写目标流」。本文件钉住换法没有改变**一个字节**，
 * 并且钉住生产路确实是流式的那一条（结构哨兵 —— 行为用例证明不了「没复制」，
 * 只能证明「结果一样」）。
 *
 * ## 断言的是哪几条契约
 *
 * 1. [SaveFormat.SaveWriter] 的十三种条目发射与 `SaveData` 回放（[SaveFormat.encodePayload]）
 *    **逐字节一致**，压缩与不压缩两档；
 * 2. 真变量池上，`SaveCapture.emitNormal/emitGlobal` 的流式产物与 `SaveCapture.normal/global`
 *    的物化产物**逐字节一致**；
 * 3. `CharaFileIO.emitCapture` 与 `CharaFileIO.capture` 同判（CHARVAR）；
 * 4. 未分配列走 `VariableStore.systemIntColumn` 视图：长度 = 声明维度、逐格读 0、
 *    不物化列数组；
 * 5. 结构哨兵：`FileSystemOps` 的二进制存档路调 `SaveCapture.emitNormal` +
 *    `SaveFormat.writeStreaming`，`SaveCapture` 的发射面里没有 `copyOf()` / `LongArray(`。
 */
class SaveStreamByteIdentityTest {

    // ------------------------------------------------------------------
    // ① 十三种值类型：发射口 vs 条目回放
    // ------------------------------------------------------------------

    /**
     * 同一份内容写两遍：一遍走 `SaveWriter` 的访问器口，一遍走 `SaveData` + `SaveEntry` 回放。
     *
     * 覆盖 `EraSaveDataType` 全部值类型（0/1/2/3 整数、0x04~0x07 浮点、16~19 字符串、
     * 32 MAP、33 XML、34 DT、253 Separator）—— 只要有一条发射分支与回放分支写错位、
     * 写错宽、或顺序不同，字节就会分叉。
     */
    private fun writerPayload(compressed: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        SaveFormat.writeStreaming(compressed, out) { w ->
            w.fileType(SaveFileKind.NORMAL)
            w.i64(7153)
            w.i64(1808)
            w.str("存档描述")
            w.count(1)
            // 角色块：先把十三种值类型全发一遍
            w.intValue("I0", -77)
            w.int1("I1", 4) { if (it == 2) 0L else it.toLong() * 3 }
            w.int2("I2", 2, 2) { if (it == 0) 0L else it.toLong() }
            w.int3("I3", 2, 2, 2) { if (it == 0) 0L else it.toLong() }
            w.floatValue("F0", -0.0)
            w.float1("F1", 3) { it * 0.5 }
            w.float2("F2", 2, 2) { it * 0.25 }
            w.float3("F3", 2, 2, 2) { it * 0.125 }
            w.strValue("S0", "值")
            w.str1("S1", 3) { if (it == 1) null else "s$it" }
            w.str2("S2", 2, 2) { null }
            w.str3("S3", 2, 2, 2) { "v$it" }
            w.mapValue("M0", linkedMapOf("k1" to "v1", "k2" to "v2"))
            w.xmlValue("X0", "<root a=\"1\"/>")
            w.dataTableValue("D0", "<schema/>", "<data/>")
            w.separator()
            w.eoc()
            // 变量段：EOF 收尾
            w.intValue("V0", 9)
            w.eof()
            // EM 段：EOF 收尾
            w.mapValue("EM", linkedMapOf("a" to "b"))
            w.eof()
        }
        return out.toByteArray()
    }

    private fun replayPayload(compressed: Boolean): ByteArray {
        val block = listOf(
            SaveEntry.IntValue("I0", -77),
            SaveEntry.IntArray1("I1", longArrayOf(0, 3, 0, 9)),
            SaveEntry.IntArray2("I2", 2, 2, longArrayOf(0, 1, 2, 3)),
            SaveEntry.IntArray3("I3", 2, 2, 2, longArrayOf(0, 1, 2, 3, 4, 5, 6, 7)),
            SaveEntry.FloatValue("F0", -0.0),
            SaveEntry.FloatArray1("F1", doubleArrayOf(0.0, 0.5, 1.0)),
            SaveEntry.FloatArray2("F2", 2, 2, doubleArrayOf(0.0, 0.25, 0.5, 0.75)),
            SaveEntry.FloatArray3("F3", 2, 2, 2, doubleArrayOf(0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875)),
            SaveEntry.StrValue("S0", "值"),
            SaveEntry.StrArray1("S1", arrayOf("s0", null, "s2")),
            SaveEntry.StrArray2("S2", 2, 2, arrayOf(null, null, null, null)),
            SaveEntry.StrArray3("S3", 2, 2, 2, Array(8) { "v$it" }),
            SaveEntry.MapValue("M0", listOf("k1" to "v1", "k2" to "v2")),
            SaveEntry.XmlValue("X0", "<root a=\"1\"/>"),
            SaveEntry.DataTableValue("D0", "<schema/>", "<data/>"),
            SaveEntry.Separator,
        )
        val data = SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 7153,
            scriptVersion = 1808,
            text = "存档描述",
            characters = listOf(block),
            variables = listOf(SaveEntry.IntValue("V0", 9)),
            emData = listOf(SaveEntry.MapValue("EM", listOf("a" to "b"))),
            compressed = compressed,
        )
        return SaveFormat.write(data)
    }

    @Test
    fun `发射口与条目回放的容器字节逐字节一致 - 压缩档`() {
        val a = writerPayload(compressed = true)
        val b = replayPayload(compressed = true)
        assertEquals(b.size, a.size, "字节数必须一致")
        assertTrue(a.contentEquals(b), "压缩档：流式发射与 SaveData 回放必须逐字节相同")
    }

    @Test
    fun `发射口与条目回放的容器字节逐字节一致 - 未压缩档`() {
        val a = writerPayload(compressed = false)
        val b = replayPayload(compressed = false)
        assertTrue(a.contentEquals(b), "未压缩档：流式发射与 SaveData 回放必须逐字节相同")
    }

    // ------------------------------------------------------------------
    // ②③ 真变量池：流式 vs 物化（NORMAL / GLOBAL / CHARVAR）
    // ------------------------------------------------------------------

    private fun poolStore(): VariableStore = VariableStore().apply {
        registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
        registerTemplate(CharacterTemplate(no = 2, name = "霊夢", callname = "霊夢", maxbase = mapOf(0 to 10L)))
        addCharacter(1L)
        addCharacter(2L)
        writeInt("DAY", longArrayOf(0L), 7L)
        writeInt("MONEY", longArrayOf(0L), 1234L)
        writeInt("DA", longArrayOf(7, 3), 13L)
        writeInt("TA", longArrayOf(97, 3, 5), 11L)
        writeStr("SAVESTR", longArrayOf(2), "第二格")
    }

    @Test
    fun `真变量池 - NORMAL 流式产物与物化产物逐字节一致`() {
        val cfg = SaveConfig(systemSaveInBinary = true, zipSaveData = true)
        for (compressed in listOf(true, false)) {
            val streamed = ByteArrayOutputStream().also { buf ->
                SaveFormat.writeStreaming(compressed, buf) { w ->
                    SaveCapture.emitNormal(poolStore(), "测试", w)
                }
            }.toByteArray()
            val materialized = SaveCodec.write(
                SaveCapture.normal(poolStore(), "测试", compressed = compressed),
                poolStore().registry,
                cfg,
            )
            assertTrue(
                streamed.contentEquals(materialized),
                "compressed=$compressed：流式 NORMAL 必须与物化 NORMAL 逐字节相同",
            )
            // 反向锁：产物必须能被同一个解码器读回（不是「两路一起错」）。
            val back = SaveFormat.read(streamed)
            assertEquals(SaveFileKind.NORMAL, back.fileType)
            assertEquals(2, back.characters.size, "两个角色块")
            assertEquals("测试", back.text)
        }
    }

    @Test
    fun `真变量池 - GLOBAL 与 CHARVAR 同判`() {
        val gStreamed = ByteArrayOutputStream().also { buf ->
            SaveFormat.writeStreaming(false, buf) { w -> SaveCapture.emitGlobal(poolStore(), w) }
        }.toByteArray()
        val gMaterialized = SaveCodec.write(
            SaveCapture.global(poolStore(), compressed = false),
            poolStore().registry,
            SaveConfig(systemSaveInBinary = true, zipSaveData = false),
        )
        assertTrue(gStreamed.contentEquals(gMaterialized), "GLOBAL：流式必须与物化逐字节相同")
        assertEquals(SaveFileKind.GLOBAL, SaveFormat.read(gStreamed).fileType)

        for (compressed in listOf(true, false)) {
            val cStreamed = CharaFileIO.encode(poolStore(), listOf(0L, 1L), "角色档", compressed)
            val cMaterialized = SaveFormat.write(
                CharaFileIO.capture(poolStore(), listOf(0L, 1L), "角色档", compressed),
            )
            assertTrue(
                cStreamed.contentEquals(cMaterialized),
                "CHARVAR compressed=$compressed：流式必须与物化逐字节相同",
            )
            val back = SaveFormat.read(cStreamed)
            assertEquals(SaveFileKind.CHARVAR, back.fileType)
            assertEquals(2, back.characters.size)
        }
    }

    // ------------------------------------------------------------------
    // ④ 未分配列：零拷贝视图
    // ------------------------------------------------------------------

    /**
     * 出厂 `TA` 是 100×100×100 的声明列，未写过就是第 0 行以外全 0（甚至整列为 null）。
     *
     * 旧批量面在「未分配」时用 `LongArray(columnLength)` 造一整列零（1M × 8 B = 8 MiB / 列），
     * 现在 `systemIntColumn` 返回的视图**不分配**：长度取声明读数，`get` 未分配按 0。
     */
    @Test
    fun `未分配大列走零拷贝视图 - 长度取声明且逐格读 0`() {
        val store = VariableStore()
        val ta = store.registry.mustDef("TA")
        val view = store.systemIntColumn(ta)
        assertNotNull(view, "TA 是默认注册表的整数列，视图面应当接管")
        val product = ta.dims.fold(1L) { acc, n -> acc * n }
        assertEquals(product.toInt(), view.length, "列长 = 维度乘积（声明读数）")
        assertEquals(0L, view.get(0))
        assertEquals(0L, view.get(view.length - 1))
        assertEquals(0L, view.get(view.length / 2))
        // 与逐元素读法等值（未分配列 `readInt` 也读 0）。
        assertEquals(store.readInt("TA", longArrayOf(99, 99, 99)), view.get(view.length - 1))
    }

    // ------------------------------------------------------------------
    // ⑤ 结构哨兵：生产路必须是流式、且不物化池
    // ------------------------------------------------------------------

    /** 取 `core-vm` 的 main 源文件（去注释行后）——先例见 `StrictModeGuardTest.codeLines`。 */
    private fun source(relPath: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/$relPath"),
            File("core-vm/src/main/kotlin/com/eratw/port/$relPath"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到源文件 $relPath，已试过：$candidates")
        return file.readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/" + "*")
            }
            .joinToString("\n")
    }

    /** 取某个函数体（按 `fun 名字` 到下一个同缩进 `}`；源码哨兵够用，不做真解析）。 */
    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "源码里找不到 $signature")
        val end = text.indexOf("\n    }\n", start)
        assertTrue(end > start, "$signature 之后找不到同缩进的收尾块")
        return text.substring(start, end)
    }

    @Test
    fun `生产二进制存档路必须是流式发射 - 源码哨兵`() {
        val ops = source("core/save/FileSystemOps.kt")
        val binary = bodyOf(ops, "private fun writeBinaryStream(")
        assertTrue(
            binary.contains("SaveCapture.emitNormal(store, text, w, emSets.save)"),
            "二进制存档必须走发射口（直写活列），实得：$binary",
        )
        assertTrue(
            binary.contains("SaveFormat.writeStreaming("),
            "二进制存档必须走流式容器写口（payload 不物化）",
        )
        assertTrue(
            !binary.contains("SaveCapture.normal("),
            "二进制存档路不许再走物化抓取（那会把整个变量池复制一份）",
        )
        // 全局档同理。
        val global = bodyOf(ops, "override fun saveGlobal()")
        assertTrue(global.contains("SaveCapture.emitGlobal(store, w, emSets.global)"), "SAVEGLOBAL 必须走发射口")
        assertTrue(global.contains("SaveFormat.writeStreaming("), "SAVEGLOBAL 必须走流式容器写口")
        // 角色档走 CharaFileIO.encode（内部流式）。
        val chara = source("core/data/save/CharaFileIO.kt")
        val encode = bodyOf(chara, "fun encode(")
        assertTrue(encode.contains("SaveFormat.writeStreaming("), "SAVECHARA 的 encode 必须流式")
        assertTrue(encode.contains("emitCapture("), "SAVECHARA 的 encode 必须走发射口")
    }

    @Test
    fun `发射面不许物化池 - 源码哨兵`() {
        val capture = source("core/save/SaveCapture.kt")
        // 整列复制与整列造零是旧缺陷的两个手势，发射面里一个都不许有。
        assertTrue(!capture.contains("copyOf()"), "SaveCapture 发射面不许 copyOf() 复制整列")
        assertTrue(!capture.contains("zeroColumn("), "SaveCapture 发射面不许整列造零")
        assertTrue(!capture.contains("LongArray("), "SaveCapture 发射面不许新建整数列数组")
        assertTrue(!capture.contains("Array<String?>("), "SaveCapture 发射面不许新建字符串列数组")
        assertTrue(capture.contains("systemIntColumn(def)"), "整列必须走零拷贝视图")
        // 视图本身仍不许复制。
        val store = source("core/vars/VariableStore.kt")
        assertTrue(
            !store.contains("column.copyOf()"),
            "VariableStore 存档读口不许再交列副本（那是池级复制的源头）",
        )
    }
}
