package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L15（CONTRACT-RT2-UNCLAIMED-B1）：顶层未认领键的**原始编码切片**回写（ADR-005 例外）。
 *
 * ## 锁什么
 *
 * - 读侧 `SaveFormat.read(bytes, keepKey)`：`keepKey` 判 false 的**顶层** normal/global 条目
 *   进 [SaveData.unclaimedVariables]，内容是「类型字节 + 键名 + 值」的原始字节，**原序**；
 * - 写侧：已知变量之后、`EOF` 之前原样回写（[SaveFormat.writePayload] /
 *   `SaveCapture.emitNormal` / `emitGlobal` 同一个插入点）；已知条目的字节/顺序不动；
 * - 往返：`load → save → load` 未知键仍在且值相同；`load → save` 切片逐字节一致；
 *   第二次重写起逐字节稳定（不累积）；
 * - 零变化：无未知键的档 `write(read(f)) == f`（sourceFile 幂等短路仍命中）；
 * - 域边界：`char` 域（角色块内）与 EM 段在 L15 **不**回写；L17 把角色块也纳入例外
 *   （按块内 `NO` 分桶回写到同一块的 `EOC` 之前），EM 段仍按原版丢弃；normal/global 两域不串档；
 * - 根修不回退：未知大数组的切片是**编码后的几字节**，不是展开后的数组。
 *
 * 段序证据：`VariableEvaluator.SaveToStreamBinary`（VariableEvaluator.cs:2591-2606）的
 * `WriteEOF()` 在变量段末尾；本例外只把切片插在 EOF **之前**。原版读侧丢弃分支
 * `VariableData.cs:4081-4160` 不动（ADR-005 例外 = 只加回写，不改读面语义）。
 */
class SaveUnclaimedPassthroughTest {

    // ------------------------------------------------------------------
    // 夹具（合成档，零环境依赖）
    // ------------------------------------------------------------------

    private fun idx(vararg v: Long): LongArray = v

    /** 顶层未知：标量。 */
    private val topScalar = "UNCLAIMED_TOP_SCALAR_ZZZ"

    /** 顶层未知：1D 整数数组（含 0 游程，验证原始编码而不是展开值）。 */
    private val topArray = "UNCLAIMED_TOP_ARRAY_ZZZ"

    /** 角色块内的未知键（域边界：不许回写）。 */
    private val charaUnknown = "UNCLAIMED_CHARA_ZZZ"

    private val knownTop = listOf("MONEY", "DAY")

    private fun knownPredicate(): (String) -> Boolean = { it == "MONEY" || it == "DAY" || it == "NO" }

    /** 无角色、顶层 2 已知 + 2 未知（交错原序：MONEY, 未知标量, DAY, 未知数组）。 */
    private fun topLevelFixture(compressed: Boolean = false): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "unclaimed-passthrough",
            characters = emptyList(),
            variables = listOf(
                SaveEntry.IntArray1("MONEY", idx(123)),
                SaveEntry.IntValue(topScalar, 777),
                SaveEntry.IntValue("DAY", 45),
                SaveEntry.IntArray1(topArray, longArrayOf(1, 0, 0, 5)),
            ),
            emData = emptyList(),
            compressed = compressed,
        ),
    )

    /** 1 个角色：已知 NO + 未知角色键（char 域边界用）。 */
    private fun withCharacterFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "unclaimed-with-chara",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 1),
                    SaveEntry.IntArray1(charaUnknown, longArrayOf(7, 8, 9)),
                ),
            ),
            variables = listOf(
                SaveEntry.IntArray1("MONEY", idx(123)),
                SaveEntry.IntValue(topScalar, 777),
                SaveEntry.IntArray1("DAY", idx(45)),
            ),
            emData = emptyList(),
            compressed = false,
        ),
    )

    private fun cleanFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "clean",
            characters = emptyList(),
            variables = listOf(
                SaveEntry.IntArray1("MONEY", idx(123)),
                SaveEntry.IntValue("DAY", 45),
            ),
            emData = emptyList(),
            compressed = false,
        ),
    )

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]（路径键与生产实现同为相对路径）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }
        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }
    }

    /** eratw 生产口径的读档分派只需 binary=true；压缩关掉便于直接读 payload。 */
    private fun config() = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)

    private fun storeWithKnownDeclarations(): VariableStore = VariableStore().also { s ->
        s.declareUserVar(
            DimParser.parse(
                "#DIM", "SAVEDATA KEPT_USER, 2", isPrivate = false,
                DimParseConfig(systemSaveInBinary = true),
            ),
        )
    }

    private fun payloadOf(bytes: ByteArray): ByteArray = assertNotNull(SaveFormat.sourcePayloadOf(bytes))

    /** UTF-16LE 子串出现次数（键名在 payload 里的存在性/重复性判据）。 */
    private fun countUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        var count = 0
        var i = 0
        while (i <= hay.size - n.size) {
            var hit = true
            for (j in n.indices) {
                if (hay[i + j] != n[j]) {
                    hit = false
                    break
                }
            }
            if (hit) {
                count++
                i += n.size
            } else {
                i++
            }
        }
        return count
    }

    private fun assertSliceEquals(expected: ByteArray, actual: ByteArray, label: String) {
        assertTrue(expected.contentEquals(actual), "$label 切片必须逐字节一致（期望 ${expected.size} 字节，实际 ${actual.size}）")
    }

    /**
     * 测试侧独立扫描器：只认本文件夹具用到的类型（0/1/16/17），从 payload 截原始条目区间。
     * 刻意不复用产品代码的解析器 —— 捕获是否「原样」要由测试自己量。
     */
    private class RawScanner(private val b: ByteArray) {
        var pos = 0

        fun u8(): Int = b[pos++].toInt() and 0xFF

        fun i32(): Int {
            var v = 0
            for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun i64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        fun varInt(): Long {
            val tag = u8()
            return when {
                tag in 0..207 -> tag.toLong()
                tag == 208 -> {
                    val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    v.toShort().toLong()
                }
                tag == 209 -> i32().toLong()
                tag == 210 -> i64()
                else -> error("非法整数值标签 $tag")
            }
        }

        fun s7(): Int {
            var n = 0
            var shift = 0
            while (true) {
                val x = u8()
                n = n or ((x and 0x7F) shl shift)
                if ((x and 0x80) == 0) return n
                shift += 7
            }
        }

        fun str(): String {
            val byteLen = s7()
            val chars = CharArray(byteLen / 2)
            for (i in chars.indices) {
                val lo = b[pos + 2 * i].toInt() and 0xFF
                val hi = b[pos + 2 * i + 1].toInt() and 0xFF
                chars[i] = (lo or (hi shl 8)).toChar()
            }
            pos += byteLen
            return String(chars)
        }

        fun skipArrayTag(): Boolean {
            val tag = u8()
            return when (tag) {
                0xFF -> false
                0xF0 -> {
                    varInt(); true
                }
                0xD8 -> {
                    str(); true
                }
                else -> {
                    pos--
                    varInt()
                    true
                }
            }
        }

        fun skipValue(tag: Int) {
            when (tag) {
                0 -> varInt()
                1 -> {
                    i32()
                    while (skipArrayTag()) Unit
                }
                16 -> str()
                17 -> {
                    i32()
                    while (skipArrayTag()) Unit
                }
                else -> error("测试扫描器不支持的类型字节 $tag")
            }
        }

        fun skipCharacterBlocks(count: Int) {
            repeat(count) {
                var tag = u8()
                while (tag != 0xFE) {
                    if (tag != 0xFD) skipValue(tag)
                    tag = u8()
                }
            }
        }

        /** 顶层条目：`(键, 起始偏移, 结束偏移)`，起始 = 类型字节位置、结束 = 值末字节 + 1。 */
        fun topEntries(): List<RawEntry> {
            u8() // fileType
            i64() // uniqueCode
            i64() // scriptVersion
            str() // text
            val count = i64().toInt()
            skipCharacterBlocks(count)
            val out = ArrayList<RawEntry>()
            while (true) {
                val start = pos
                val tag = u8()
                if (tag == 0xFF) break
                val key = str()
                skipValue(tag)
                out.add(RawEntry(key, start, pos))
            }
            return out
        }
    }

    private class RawEntry(val key: String, val start: Int, val end: Int)

    private fun rawEntries(payload: ByteArray): List<RawEntry> = RawScanner(payload).topEntries()

    // ------------------------------------------------------------------
    // 1. 读侧：原始切片、原序、不解析不重编码
    // ------------------------------------------------------------------

    @Test
    fun `读侧保留顶层未知条目原始切片_原序且逐字节等于原档`() {
        val original = topLevelFixture()
        val payload = payloadOf(original)
        val raw = rawEntries(payload)
        assertEquals(
            listOf("MONEY", topScalar, "DAY", topArray),
            raw.map { it.key },
            "夹具原序（未知条目交错在已知条目之间）",
        )

        val data = SaveFormat.read(original, knownPredicate())
        assertEquals(listOf(topScalar, topArray), data.unclaimedVariables.map { it.key }, "只捕顶层未知、保持原序")
        assertEquals(listOf("MONEY", "DAY"), data.variables.map { it.key }, "未知键不进 variables（不建数组）")

        val expectedByKey = raw.associate { it.key to payload.copyOfRange(it.start, it.end) }
        for (u in data.unclaimedVariables) {
            assertSliceEquals(expectedByKey.getValue(u.key), u.bytes, u.key)
        }
        assertEquals(listOf(topScalar, topArray), data.droppedVariables, "键名仍进 droppedVariables（上屏告知面不变）")
    }

    @Test
    fun `读侧不解析未知大数组_切片是编码后的几字节而不是展开值`() {
        val bigKey = "UNCLAIMED_BIG_ZZZ"
        val bytes = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL,
                uniqueCode = 0,
                scriptVersion = 0,
                text = "big",
                characters = emptyList(),
                variables = listOf(
                    SaveEntry.IntValue("DAY", 1),
                    SaveEntry.IntArray1(bigKey, LongArray(1_000_000)),
                ),
                emData = emptyList(),
                compressed = false,
            ),
        )
        val data = SaveFormat.read(bytes, knownPredicate())
        val slice = data.unclaimedVariables.single()
        assertEquals(bigKey, slice.key)
        // 全零数组在编码里 = 长度 4 字节 + 0xF0 游程 + 变长计数 + 0xFF，与元素数无关。
        assertTrue(slice.bytes.size < 64, "未知大数组不许展开成 8 MB 值数组（实际切片 ${slice.bytes.size} 字节）")
        assertTrue(data.variables.none { it.key == bigKey }, "未知键不建 LongArray")
    }

    // ------------------------------------------------------------------
    // 2. 往返：值仍在、切片逐字节一致、重复往返稳定
    // ------------------------------------------------------------------

    @Test
    fun `往返_load_save_load_未知键仍在且值相同`() {
        val first = SaveFormat.read(topLevelFixture(), knownPredicate())
        val rewritten = SaveFormat.write(first)

        val second = SaveFormat.read(rewritten, knownPredicate())
        assertEquals(listOf(topScalar, topArray), second.unclaimedVariables.map { it.key }, "未知键仍在且原序")
        for (i in first.unclaimedVariables.indices) {
            assertSliceEquals(first.unclaimedVariables[i].bytes, second.unclaimedVariables[i].bytes, "第 $i 条")
        }

        // 全量口径读回：值相同（标量与 1D 数组）。
        val parsed = SaveFormat.read(rewritten)
        val scalar = parsed.variables.single { it.key == topScalar } as SaveEntry.IntValue
        assertEquals(777L, scalar.value)
        val array = parsed.variables.single { it.key == topArray } as SaveEntry.IntArray1
        assertEquals(listOf(1L, 0L, 0L, 5L), array.values.toList())
        assertEquals(listOf("MONEY", "DAY"), parsed.variables.filter { it.key in knownTop }.map { it.key }, "已知条目原序")

        // 已知条目的**编码字节**也不许变（段尾插切片不重排、不重编码已知条目）。
        val beforePayload = payloadOf(topLevelFixture())
        val afterPayload = payloadOf(rewritten)
        val beforeKnown = rawEntries(beforePayload).filter { it.key in knownTop }
        val afterKnown = rawEntries(afterPayload).filter { it.key in knownTop }
        assertEquals(beforeKnown.map { it.key }, afterKnown.map { it.key }, "已知条目原序")
        for (i in beforeKnown.indices) {
            assertSliceEquals(
                beforePayload.copyOfRange(beforeKnown[i].start, beforeKnown[i].end),
                afterPayload.copyOfRange(afterKnown[i].start, afterKnown[i].end),
                "已知条目 ${beforeKnown[i].key} 的编码字节",
            )
        }
    }

    @Test
    fun `重复往返稳定_未知键不累积且字节收敛`() {
        val r1 = SaveFormat.read(topLevelFixture(), knownPredicate())
        val b1 = SaveFormat.write(r1)
        val r2 = SaveFormat.read(b1, knownPredicate())
        val b2 = SaveFormat.write(r2)
        val r3 = SaveFormat.read(b2, knownPredicate())
        val b3 = SaveFormat.write(r3)

        assertTrue(b1.contentEquals(b2), "第一次重写后 payload 已收敛（sourceFile 幂等短路命中）")
        assertTrue(b2.contentEquals(b3), "重复往返逐字节稳定")
        for (key in listOf(topScalar, topArray)) {
            assertEquals(1, countUtf16(payloadOf(b3), key), "$key 只能出现一次（不重复累积）")
        }
    }

    // ------------------------------------------------------------------
    // 3. 零变化：无未知条目 ⇒ 输出逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun `无未知条目_输出逐字节不变`() {
        val clean = cleanFixture()
        val data = SaveFormat.read(clean, knownPredicate())
        assertTrue(data.unclaimedVariables.isEmpty(), "无未知键时载体为空")
        val rewritten = SaveFormat.write(data)
        assertTrue(clean.contentEquals(rewritten), "无未知键必须逐字节不变（含 GZip/容器头）")
        assertTrue(clean.contentEquals(SaveFormat.write(SaveFormat.read(clean))), "无过滤器路径同样不变")
    }

    // ------------------------------------------------------------------
    // 4. 生产路：loadData → saveData（变量池现抓的流式路）
    // ------------------------------------------------------------------

    @Test
    fun `生产路读档再存档_顶层未知键回写一次_角色未知键按 NO 回写一次`() {
        val io = MemorySaveIo()
        val original = withCharacterFixture()
        io.files[SLOT_PATH] = original
        val store = storeWithKnownDeclarations()
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        assertEquals(123L, store.readInt("MONEY", idx(0)), "已知变量照常生效")
        assertEquals(1L, store.readInt("NO", idx(0)), "角色 NO 照常生效")

        assertTrue(fs.saveData(0, "rewrite"), "再存档应成功")
        val after = io.files.getValue(SLOT_PATH)
        val reread = SaveFormat.read(after)

        assertEquals(1, reread.variables.count { it.key == topScalar }, "顶层未知键回写且只有一条")
        assertEquals(777L, (reread.variables.single { it.key == topScalar } as SaveEntry.IntValue).value, "值原样")
        assertEquals(
            1,
            reread.characters.sumOf { block -> block.count { it.key == charaUnknown } },
            "角色块未知键按 NO 回写且只有一条（L17）",
        )
        assertEquals(123L, (reread.variables.single { it.key == "MONEY" } as SaveEntry.IntArray1).values[0])
        assertEquals(45L, (reread.variables.single { it.key == "DAY" } as SaveEntry.IntArray1).values[0])
        assertEquals("rewrite", reread.text, "描述文本照常更新")
        assertTrue(countUtf16(payloadOf(after), topScalar) == 1, "未知键在 payload 里只出现一次")
    }

    @Test
    fun `回标题后旧档的未认领切片不再写进新局`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = topLevelFixture()
        val store = storeWithKnownDeclarations()
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        // RESETDATA / gotoTitle 的复位面（VariableStore.resetData）；lastLoadNo 回 -1
        // ⇒ 上一份档的未知键不再属于当前局。
        store.resetData()
        assertTrue(fs.saveData(0, "new-game"), "新局存档应成功")
        val payload = payloadOf(io.files.getValue(SLOT_PATH))
        assertEquals(-1, indexOfUtf16(payload, topScalar), "旧档未认领键不许漏进新局")
        assertEquals(-1, indexOfUtf16(payload, topArray), "旧档未认领数组同样不许漏进")
    }

    @Test
    fun `物化入口抓取保留原始切片_与流式路同字节`() {
        val io = MemorySaveIo()
        val original = withCharacterFixture()
        io.files[SLOT_PATH] = original
        val store = storeWithKnownDeclarations()
        FileSystemOps(store, io, config()).loadData(0)

        val streamed = SaveFormat.read(original, knownPredicate()).unclaimedVariables.single()
        // 侧车配对 / 文本回落 / 运行期快照走的物化入口（SaveCapture.normal → materialize）。
        val data = SaveCapture.normal(store, "materialized", compressed = false)
        val slice = data.unclaimedVariables.single()
        assertEquals(streamed.key, slice.key, "物化入口同样只捕顶层未认领键")
        assertSliceEquals(streamed.bytes, slice.bytes, "物化入口与读档捕获")

        val reread = SaveFormat.read(SaveFormat.write(data))
        assertEquals(1, reread.variables.count { it.key == topScalar }, "物化入口产物仍带未认领键")
        assertEquals(777L, (reread.variables.single { it.key == topScalar } as SaveEntry.IntValue).value)
    }

    @Test
    fun `normal 与 global 两域不串档`() {
        val io = MemorySaveIo()
        val normalUnknown = "UNCLAIMED_NORMAL_DOMAIN_ZZZ"
        val globalUnknown = "UNCLAIMED_GLOBAL_DOMAIN_ZZZ"
        io.files[SLOT_PATH] = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL, uniqueCode = 0, scriptVersion = 0,
                text = "n", characters = emptyList(),
                variables = listOf(SaveEntry.IntValue("DAY", 1), SaveEntry.IntValue(normalUnknown, 11)),
                emData = emptyList(), compressed = false,
            ),
        )
        io.files[GLOBAL_PATH] = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.GLOBAL, uniqueCode = 0, scriptVersion = 0,
                text = "", characters = emptyList(),
                variables = listOf(SaveEntry.IntValue(globalUnknown, 22)),
                emData = emptyList(), compressed = false,
            ),
        )
        val store = storeWithKnownDeclarations()
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        assertTrue(fs.loadGlobal(), "LOADGLOBAL 应成功")
        fs.saveGlobal()
        val globalPayload = payloadOf(io.files.getValue(GLOBAL_PATH))
        assertEquals(-1, indexOfUtf16(globalPayload, normalUnknown), "normal 域的切片不许写进 global.sav")
        assertEquals(1, countUtf16(globalPayload, globalUnknown), "global 域自己的切片回写")

        assertTrue(fs.saveData(0, "n2"), "SAVEDATA 应成功")
        val normalPayload = payloadOf(io.files.getValue(SLOT_PATH))
        assertEquals(-1, indexOfUtf16(normalPayload, globalUnknown), "global 域的切片不许写进普通档")
        assertEquals(1, countUtf16(normalPayload, normalUnknown), "normal 域自己的切片回写")
    }

    @Test
    fun `槽未装配时零变化_未读档的存档不带任何切片`() {
        val io = MemorySaveIo()
        val store = storeWithKnownDeclarations()
        val fs = FileSystemOps(store, io, config())
        assertTrue(fs.saveData(0, "fresh"), "SAVEDATA 应成功")
        assertTrue(
            SaveUnclaimedPassthrough.of(store) == null,
            "只存档不读档不得凭空装配切片槽（零变化）",
        )
        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertTrue(reread.variables.none { it is SaveEntry.IntValue && it.key.startsWith("UNCLAIMED_") })
    }

    private fun indexOfUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        outer@ for (i in 0..hay.size - n.size) {
            for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径。 */
        const val SLOT_PATH = "sav/save00.sav"

        /** 同上：全局档路径。 */
        const val GLOBAL_PATH = "sav/global.sav"
    }
}
