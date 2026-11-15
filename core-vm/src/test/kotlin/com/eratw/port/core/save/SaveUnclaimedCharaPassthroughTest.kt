package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L17（CONTRACT-RT2-UNCLAIMED-B2）：**角色块**未认领键的原始编码切片回写（ADR-005 例外第二波）。
 *
 * ## 锁什么
 *
 * - 读侧 `SaveFormat.read(bytes, keepKey)`：普通存档角色块里被 `keepKey` 拒绝的条目进
 *   [SaveData.unclaimedCharacterVariables]，内容是「类型字节 + 键名 + 值」的原始字节，
 *   **桶键 = 该块内 `NO` 条目的数值**（不是块位置），桶内保持原序；
 * - 身份绑定 fail-closed：块内 `NO` 缺失 / 重复 / 非整数标量 ⇒ **整块**切片丢弃 +
 *   `SaveData.unclaimedCharacterDroppedKeys` 留痕（`FileSystemOps` 落成 `SaveDiagnostics.note`），
 *   绝不按块位置兜底；
 * - 写侧：同一 `NO` 的角色块里、已知条目之后、`EOC` 之前原样回写；当前 store 没有该 `NO`
 *   （角色被删 / 被替换）⇒ 该桶丢弃 + 留痕；不新建角色块、不改已知条目字节/顺序；
 * - 不串块：角色顺序交换后各归各；删掉的角色不把自己的切片挂到别人身上；
 * - 零变化：无角色块未知键的档 `write(read(f)) == f`（sourceFile 幂等短路仍命中）；
 * - `chara_*.dat`（CHARVAR）**不捕获**：那是「追加角色」语义，按 `NO` 回写会混进别人的角色档；
 * - 物化入口（`SaveCapture.normal`）与流式入口（`SaveCapture.emitNormal`）逐字节同源。
 *
 * ## 证据锚
 *
 * 读写不对称的原版锚：读侧 `VariableData.cs:4081-4160`（未知键读完即丢）、写侧
 * `CharacterData.cs:513-558`（只遍历声明表）。`NO` 是角色块首项整数条目
 * （`SystemLayout.charaInt0D` 第 2 项；真档实测块首 `ISASSI/NO`），故身份只能取它。
 */
class SaveUnclaimedCharaPassthroughTest {

    // ------------------------------------------------------------------
    // 夹具（合成档，零环境依赖）
    // ------------------------------------------------------------------

    private fun idx(vararg v: Long): LongArray = v

    /** NO=5 的块：两个未知键（标量 + 1D 整数数组），交错在已知条目之间。 */
    private val unknownA = "UNCLAIMED_CHARA_A_ZZZ"
    private val unknownB = "UNCLAIMED_CHARA_B_ZZZ"

    /** NO=9 的块：一个未知字符串。 */
    private val unknownC = "UNCLAIMED_CHARA_C_ZZZ"

    /** 换身份不可判定用的未知键（NO 缺失 / 重复的块）。 */
    private val unknownNoId = "UNCLAIMED_CHARA_NOID_ZZZ"

    private fun knownPredicate(): (String) -> Boolean = { it == "NO" || it == "CFLAG" || it == "MONEY" }

    /** 两个角色块：NO=5（两个未知键）与 NO=9（一个未知键）。 */
    private fun twoCharFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "chara-slices",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 5),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(1, 2, 3)),
                    SaveEntry.IntValue(unknownA, 77),
                    SaveEntry.IntArray1(unknownB, longArrayOf(4, 0, 0, 6)),
                ),
                listOf(
                    SaveEntry.IntValue("NO", 9),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(9)),
                    SaveEntry.StrValue(unknownC, "keep-me"),
                ),
            ),
            variables = listOf(SaveEntry.IntValue("MONEY", 123)),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 无任何角色块未知键的干净档（零变化判据）。 */
    private fun cleanFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "clean",
            characters = listOf(
                listOf(SaveEntry.IntValue("NO", 5), SaveEntry.IntArray1("CFLAG", longArrayOf(1, 2))),
            ),
            variables = listOf(SaveEntry.IntValue("MONEY", 1)),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 单块夹具：`block` 由调用方给（NO 缺失 / 重复两条失败面用例）。 */
    private fun oneBlockFixture(block: List<SaveEntry>): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "bad-no",
            characters = listOf(block),
            variables = emptyList(),
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

    /** 角色表：按 [nos] 的顺序登记模板并逐个 ADDCHARA（`NO` 列 = 模板 no）。 */
    private fun storeWithCharacters(vararg nos: Long): VariableStore = VariableStore().also { s ->
        for (no in nos) s.registerTemplate(CharacterTemplate(no = no, name = "chara-$no", callname = "chara-$no"))
        for (no in nos) s.addCharacter(no)
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
        assertTrue(
            expected.contentEquals(actual),
            "$label 切片必须逐字节一致（期望 ${expected.size} 字节，实际 ${actual.size}）",
        )
    }

    /**
     * 测试侧独立扫描器：只认本文件夹具用到的类型（0/1/16/17），从 payload 截角色块条目区间。
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

        /** 每个角色块：`(键, 起始偏移, 结束偏移)`，起始 = 类型字节位置、结束 = 值末字节 + 1。 */
        fun charBlocks(): List<List<RawEntry>> {
            u8() // fileType
            i64() // uniqueCode
            i64() // scriptVersion
            str() // text
            val count = i64().toInt()
            return (0 until count).map {
                val entries = ArrayList<RawEntry>()
                while (true) {
                    val start = pos
                    val tag = u8()
                    if (tag == 0xFE) break
                    if (tag != 0xFD) {
                        val key = str()
                        skipValue(tag)
                        entries.add(RawEntry(key, start, pos))
                    }
                }
                entries
            }
        }
    }

    private class RawEntry(val key: String, val start: Int, val end: Int)

    private fun rawBlocks(payload: ByteArray): List<List<RawEntry>> = RawScanner(payload).charBlocks()

    private fun noOf(block: List<SaveEntry>): Long = (block.single { it.key == "NO" } as SaveEntry.IntValue).value

    // ------------------------------------------------------------------
    // 1. 读侧：按块内 NO 分桶、原始切片、原序、不解析不重编码
    // ------------------------------------------------------------------

    @Test
    fun `读侧按块内 NO 分桶捕获角色块未知键_原序且逐字节等于原档`() {
        val original = twoCharFixture()
        val payload = payloadOf(original)
        val raw = rawBlocks(payload)
        assertEquals(2, raw.size, "夹具应有两个角色块")
        assertEquals(listOf("NO", "CFLAG", unknownA, unknownB), raw[0].map { it.key }, "块 0 原序")
        assertEquals(listOf("NO", "CFLAG", unknownC), raw[1].map { it.key }, "块 1 原序")

        val data = SaveFormat.read(original, knownPredicate())
        assertEquals(
            listOf(5L, 9L),
            data.unclaimedCharacterVariables.keys.toList(),
            "桶键 = 块内 NO 的数值，插入序 = 块序",
        )
        assertEquals(
            listOf(unknownA, unknownB),
            data.unclaimedCharacterVariables.getValue(5L).map { it.key },
            "NO=5 的块只捕自己的两个未知键、保持原序",
        )
        assertEquals(
            listOf(unknownC),
            data.unclaimedCharacterVariables.getValue(9L).map { it.key },
            "NO=9 的块只捕自己的未知键",
        )
        assertTrue(data.unclaimedCharacterDroppedKeys.isEmpty(), "身份可判定时不许有丢弃留痕")

        val expectedByKey = raw.flatten().associate { it.key to payload.copyOfRange(it.start, it.end) }
        for (slice in data.unclaimedCharacterVariables.values.flatten()) {
            assertSliceEquals(expectedByKey.getValue(slice.key), slice.bytes, slice.key)
        }
        assertEquals(
            listOf(unknownA, unknownB, unknownC),
            data.droppedCharacterVariables,
            "键名仍进 droppedCharacterVariables（上屏告知面不变）",
        )
        assertEquals(
            listOf("NO", "CFLAG"),
            data.characters[0].map { it.key },
            "未知键不进 characters（不建数组）",
        )
    }

    // ------------------------------------------------------------------
    // 2. 往返：未知键留在原 NO 的块里、已知条目字节不变
    // ------------------------------------------------------------------

    @Test
    fun `往返_角色块未知键留在原 NO 的块里_已知条目字节与顺序不变`() {
        val original = twoCharFixture()
        val first = SaveFormat.read(original, knownPredicate())
        val rewritten = SaveFormat.write(first)

        val again = SaveFormat.read(rewritten)
        assertEquals(2, again.characters.size, "角色数不变")
        assertEquals(
            listOf("NO", "CFLAG", unknownA, unknownB),
            again.characters[0].map { it.key },
            "NO=5 的块：已知条目原序、未知键按原序落在 EOC 之前",
        )
        assertEquals(
            listOf("NO", "CFLAG", unknownC),
            again.characters[1].map { it.key },
            "NO=9 的块：同一个插入点",
        )
        assertEquals(5L, noOf(again.characters[0]), "块 0 身份不变")
        assertEquals(9L, noOf(again.characters[1]), "块 1 身份不变")
        assertEquals(77L, (again.characters[0].single { it.key == unknownA } as SaveEntry.IntValue).value)
        assertEquals(
            listOf(4L, 0L, 0L, 6L),
            (again.characters[0].single { it.key == unknownB } as SaveEntry.IntArray1).values.toList(),
        )
        assertEquals("keep-me", (again.characters[1].single { it.key == unknownC } as SaveEntry.StrValue).value)

        // 已知条目的编码字节也不许变（块尾插切片不重排、不重编码已知条目）。
        val before = rawBlocks(payloadOf(original))
        val after = rawBlocks(payloadOf(rewritten))
        for (bi in before.indices) {
            val beforeKnown = before[bi].filter { it.key == "NO" || it.key == "CFLAG" }
            val afterKnown = after[bi].filter { it.key == "NO" || it.key == "CFLAG" }
            assertEquals(beforeKnown.map { it.key }, afterKnown.map { it.key }, "块 $bi 已知条目原序")
            for (i in beforeKnown.indices) {
                assertSliceEquals(
                    payloadOf(original).copyOfRange(beforeKnown[i].start, beforeKnown[i].end),
                    payloadOf(rewritten).copyOfRange(afterKnown[i].start, afterKnown[i].end),
                    "块 $bi 的已知条目 ${beforeKnown[i].key}",
                )
            }
        }

        // 切片逐字节一致、不累积（重写后的档要用同一个过滤器读回才看得见桶）。
        val againFiltered = SaveFormat.read(rewritten, knownPredicate())
        for ((no, slices) in first.unclaimedCharacterVariables) {
            val after = againFiltered.unclaimedCharacterVariables.getValue(no)
            assertEquals(slices.map { it.key }, after.map { it.key }, "NO=$no 的切片键序")
            for (i in slices.indices) {
                assertSliceEquals(slices[i].bytes, after[i].bytes, "NO=$no 的第 $i 条切片")
            }
        }
        for (key in listOf(unknownA, unknownB, unknownC)) {
            assertEquals(1, countUtf16(payloadOf(rewritten), key), "$key 只能出现一次（不重复累积）")
        }
    }

    // ------------------------------------------------------------------
    // 3. 不串块：角色顺序交换 / 删除角色
    // ------------------------------------------------------------------

    @Test
    fun `生产路角色顺序交换后各归各_切片不挂到别的 NO 上`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoCharFixture()
        val store = storeWithCharacters(5, 9)
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        assertEquals(5L, store.readInt("NO", idx(0)), "装载后 0 号角色 = NO 5")
        assertEquals(9L, store.readInt("NO", idx(1)), "装载后 1 号角色 = NO 9")

        // 顺序交换：删掉 0 号（NO=5）再追加 NO=5 ⇒ 当前表序 [9, 5]。
        store.deleteCharacter(0)
        store.addCharacter(5L)
        assertEquals(9L, store.readInt("NO", idx(0)), "表序已交换：0 号 = NO 9")
        assertEquals(5L, store.readInt("NO", idx(1)), "表序已交换：1 号 = NO 5")

        assertTrue(fs.saveData(0, "reordered"), "再存档应成功")
        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertEquals(2, reread.characters.size, "角色数不变")
        assertEquals(9L, noOf(reread.characters[0]), "新表 0 号 = NO 9")
        assertEquals(5L, noOf(reread.characters[1]), "新表 1 号 = NO 5")

        val keysOf9 = reread.characters[0].map { it.key }
        val keysOf5 = reread.characters[1].map { it.key }
        assertTrue(unknownC in keysOf9, "NO=9 的切片必须挂在 NO=9 的块")
        assertTrue(unknownA !in keysOf9 && unknownB !in keysOf9, "NO=5 的切片不许挂到 NO=9 上")
        assertTrue(unknownA in keysOf5 && unknownB in keysOf5, "NO=5 的切片必须挂在 NO=5 的块")
        assertTrue(unknownC !in keysOf5, "NO=9 的切片不许挂到 NO=5 上")
        for (key in listOf(unknownA, unknownB, unknownC)) {
            assertEquals(1, countUtf16(payloadOf(io.files.getValue(SLOT_PATH)), key), "$key 只出现一次")
        }
    }

    @Test
    fun `当前 store 没有该 NO_该桶丢弃并留痕`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoCharFixture()
        val store = storeWithCharacters(5, 9)
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        store.deleteCharacter(0) // 删掉 NO=5（桶 5 从此无块可挂）
        SaveDiagnostics.clear()
        assertTrue(fs.saveData(0, "dropped"), "再存档应成功")

        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertEquals(1, reread.characters.size, "只剩 NO=9")
        assertEquals(9L, noOf(reread.characters.single()), "留下的块身份不变")
        val payload = payloadOf(io.files.getValue(SLOT_PATH))
        assertEquals(-1, indexOfUtf16(payload, unknownA), "被删角色的切片不许挂到别人身上")
        assertEquals(-1, indexOfUtf16(payload, unknownB), "被删角色的切片不许挂到别人身上")
        assertEquals(1, countUtf16(payload, unknownC), "现存角色的切片照常回写")

        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(log.contains("角色块未认领切片丢弃"), "桶无块可挂必须留痕：$log")
        assertTrue(log.contains("NO=5"), "留痕要带 NO：$log")
        assertTrue(log.contains(unknownA), "留痕要带键名：$log")
    }

    // ------------------------------------------------------------------
    // 4. 身份不可判定：整块丢弃 + 留痕（不按位置兜底）
    // ------------------------------------------------------------------

    @Test
    fun `NO 缺失_整块切片丢弃并留痕`() {
        val bytes = oneBlockFixture(
            listOf(SaveEntry.IntArray1("CFLAG", longArrayOf(1)), SaveEntry.IntValue(unknownNoId, 5)),
        )
        val data = SaveFormat.read(bytes, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "NO 缺失 ⇒ 不产出任何桶")
        assertEquals(listOf(unknownNoId), data.unclaimedCharacterDroppedKeys, "丢弃的键名进留痕清单")
        assertEquals(
            listOf(unknownNoId),
            data.droppedCharacterVariables,
            "上屏报告面照旧（键名仍在 droppedCharacterVariables）",
        )

        val io = MemorySaveIo()
        io.files[SLOT_PATH] = bytes
        val fs = FileSystemOps(storeWithCharacters(), io, config())
        SaveDiagnostics.clear()
        fs.loadData(0)
        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(log.contains("角色块未认领切片丢弃"), "装载期必须留痕：$log")
        assertTrue(log.contains(unknownNoId), "留痕要带键名：$log")

        assertTrue(fs.saveData(0, "no-id"), "存档应成功")
        assertEquals(
            -1,
            indexOfUtf16(payloadOf(io.files.getValue(SLOT_PATH)), unknownNoId),
            "身份不可判定的切片不许写回（更不许按块位置猜）",
        )    }

    @Test
    fun `NO 块内重复_整块切片丢弃并留痕`() {
        val bytes = oneBlockFixture(
            listOf(
                SaveEntry.IntValue("NO", 1),
                SaveEntry.IntValue("NO", 2),
                SaveEntry.IntArray1("CFLAG", longArrayOf(1)),
                SaveEntry.IntValue(unknownNoId, 5),
            ),
        )
        val data = SaveFormat.read(bytes, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "NO 重复 ⇒ 不产出任何桶")
        assertEquals(listOf(unknownNoId), data.unclaimedCharacterDroppedKeys, "丢弃的键名进留痕清单")

        val io = MemorySaveIo()
        io.files[SLOT_PATH] = bytes
        val fs = FileSystemOps(storeWithCharacters(), io, config())
        SaveDiagnostics.clear()
        fs.loadData(0)
        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(log.contains("角色块未认领切片丢弃") && log.contains(unknownNoId), "装载期必须留痕：$log")

        assertTrue(fs.saveData(0, "dup-no"), "存档应成功")
        assertEquals(
            -1,
            indexOfUtf16(payloadOf(io.files.getValue(SLOT_PATH)), unknownNoId),
            "身份歧义的切片不许写回",
        )
    }

    @Test
    fun `两个块解出同一个 NO_桶键撞车时整桶丢弃`() {
        val bytes = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL,
                uniqueCode = 0,
                scriptVersion = 0,
                text = "dup-bucket",
                characters = listOf(
                    listOf(SaveEntry.IntValue("NO", 3), SaveEntry.IntValue(unknownA, 1)),
                    listOf(SaveEntry.IntValue("NO", 3), SaveEntry.IntValue(unknownB, 2)),
                ),
                variables = emptyList(),
                emData = emptyList(),
                compressed = false,
            ),
        )
        val data = SaveFormat.read(bytes, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "同一 NO 出现两次 ⇒ 桶键撞车，全部丢弃")
        assertEquals(listOf(unknownA, unknownB), data.unclaimedCharacterDroppedKeys, "两条都进留痕清单")
    }

    // ------------------------------------------------------------------
    // 5. 零变化 + CHARVAR 不捕获
    // ------------------------------------------------------------------

    @Test
    fun `无角色块未知键_输出逐字节不变_含无过滤器路径`() {
        val clean = cleanFixture()
        val data = SaveFormat.read(clean, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "无未知键时角色桶为空")
        assertTrue(data.unclaimedCharacterDroppedKeys.isEmpty(), "无未知键时不留痕")
        assertTrue(clean.contentEquals(SaveFormat.write(data)), "无未知键必须逐字节不变（含容器头）")
        assertTrue(clean.contentEquals(SaveFormat.write(SaveFormat.read(clean))), "无过滤器路径同样不变")
    }

    @Test
    fun `CHARVAR 角色档不捕获角色块未知键_未知键仍按原版丢弃`() {
        val charvar = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.CHARVAR,
                uniqueCode = 0,
                scriptVersion = 0,
                text = "chara-file",
                characters = listOf(
                    listOf(SaveEntry.IntValue("NO", 5), SaveEntry.IntValue(unknownNoId, 3)),
                ),
                variables = emptyList(),
                emData = emptyList(),
                compressed = false,
            ),
        )
        val data = SaveFormat.read(charvar, knownPredicate())
        assertTrue(data.unclaimedCharacterVariables.isEmpty(), "CHARVAR 不捕获（追加角色语义，按 NO 回写会串档）")
        assertTrue(data.unclaimedCharacterDroppedKeys.isEmpty(), "CHARVAR 不留身份留痕")
        assertEquals(listOf(unknownNoId), data.droppedCharacterVariables, "键名照旧进报告面")

        // 域边界不扩：CHARVAR 的未知键仍按原版消失（不回写 —— 与 NORMAL 的角色块不同）。
        val rewritten = SaveFormat.read(SaveFormat.write(data))
        assertEquals(listOf("NO"), rewritten.characters.single().map { it.key }, "CHARVAR 未知键不回来")
        assertEquals(-1, indexOfUtf16(payloadOf(SaveFormat.write(data)), unknownNoId), "字节里也不许出现")

        // 无未知键的 CHARVAR 仍走 sourceFile 幂等短路：逐字节不变。
        val cleanCharvar = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.CHARVAR,
                uniqueCode = 0,
                scriptVersion = 0,
                text = "chara-clean",
                characters = listOf(listOf(SaveEntry.IntValue("NO", 5))),
                variables = emptyList(),
                emData = emptyList(),
                compressed = false,
            ),
        )
        assertTrue(
            cleanCharvar.contentEquals(SaveFormat.write(SaveFormat.read(cleanCharvar, knownPredicate()))),
            "无未知键的 CHARVAR 逐字节不变",
        )

        // CHARVAR 的写侧入口语义不变：编码 → 装载仍成功（CHARVAR 仍走 CharaFileIO 的口径）。
        val source = storeWithCharacters(5)
        val encoded = CharaFileIO.encode(source, listOf(0), "chara")
        val target = storeWithCharacters()
        assertTrue(CharaFileIO.load(encoded, target), "LOADCHARA 仍成功")
        assertEquals(1, target.characters.count, "角色被追加进目标池")
        assertEquals(5L, target.readInt("NO", idx(0)), "装载后 NO 正确")
    }

    // ------------------------------------------------------------------
    // 6. 物化入口与流式入口同字节
    // ------------------------------------------------------------------

    @Test
    fun `物化入口与流式入口逐字节一致_角色切片只写一次`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoCharFixture()
        val store = storeWithCharacters(5, 9)
        FileSystemOps(store, io, config()).loadData(0)

        val out = ByteArrayOutputStream()
        SaveFormat.writeStreaming(compressed = false, out) { w ->
            SaveCapture.emitNormal(store, "materialize", w)
        }
        val streamed = out.toByteArray()
        val materialized = SaveFormat.write(SaveCapture.normal(store, "materialize", compressed = false))

        assertTrue(streamed.contentEquals(materialized), "物化入口与流式入口必须逐字节一致")
        val payload = payloadOf(streamed)
        for (key in listOf(unknownA, unknownB, unknownC)) {
            assertEquals(1, countUtf16(payload, key), "$key 在两条入口的产物里都只出现一次")
        }
        // 物化入口读回时同样按 NO 分桶（载体不空），再编码才不会丢。
        val captured = SaveCapture.normal(store, "materialize", compressed = false)
        assertEquals(listOf(5L, 9L), captured.unclaimedCharacterVariables.keys.toList(), "物化读回同样按 NO 分桶")
    }

    // ------------------------------------------------------------------
    // 7. 生命周期：回标题后旧档切片不进入新局
    // ------------------------------------------------------------------

    @Test
    fun `回标题后旧档的角色块切片不再写进新局`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = twoCharFixture()
        val store = storeWithCharacters(5, 9)
        val fs = FileSystemOps(store, io, config())

        fs.loadData(0)
        // RESETDATA / gotoTitle 的复位面（VariableStore.resetData）；lastLoadNo 回 -1
        // ⇒ 上一份档的角色桶与顶层桶一起失效。
        store.resetData()
        val slot = SaveUnclaimedPassthrough.of(store)
        assertTrue(slot == null || slot.charaSlices(store).isEmpty(), "复位后角色桶必须失效")
        assertTrue(fs.saveData(0, "new-game"), "新局存档应成功")
        val payload = payloadOf(io.files.getValue(SLOT_PATH))
        for (key in listOf(unknownA, unknownB, unknownC)) {
            assertEquals(-1, indexOfUtf16(payload, key), "旧档角色块未知键不许漏进新局：$key")
        }
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
    }
}
