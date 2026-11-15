package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L28（CONTRACT-RT2-UNCLAIMED-B3-EM）：EM 段（Map / Xml / DataTable）未认领容器名的
 * **原始编码切片**回写（ADR-005 例外第三波）。
 *
 * ## 锁什么
 *
 * - 读侧 `SaveFormat.read(bytes, keepKey, emKeepKey)`：EM 接受谓词判 false 的条目**只走标签**、
 *   不建 `SaveEntry`，把「类型字节 + 容器名 + 值」的原始字节按原序收进
 *   [SaveData.unclaimedEmData]；被拒的名字**不进** `droppedVariables`（EM 名不是变量，
 *   上屏文案只说变量，见 L16）。
 * - 写侧：已知 EM 条目之后、EM 段 `EOF` 之前原样直写（[SaveFormat.writePayload] 与
 *   `SaveCapture.emitNormal` / `emitGlobal` 同一个插入点）；已知条目的字节/顺序不动。
 * - 往返：`load → save → load` 未知容器仍在且值相同；切片逐字节一致；第二次重写起稳定。
 * - 零变化：无未知 EM 条目的档 `write(read(f)) == f`（sourceFile 幂等短路仍命中）。
 * - 域与生命周期：normal / global 分槽不串档；normal 走 `lastLoadNo` 闸（回标题后不写进新局）；
 *   装载后又被本档 `SAVE_*` / `GLOBAL_*` 名字表声明的名字跳过（避免同名两条）。
 * - 物化入口（`SaveCapture.normal` / `global` → `materialize`）与流式入口逐字节一致。
 *
 * ## 为什么这条不是「顺手修好原版」
 *
 * 原版读侧 `VariableData.LoadVariableBinary` 的 Map/Xml/DT 三支是
 * `if (SaveMaps.Contains(key) || GlobalSaveMaps.Contains(key)) DataStringMaps[key] = value;`
 * —— 不在并集里的名字读完值就丢；写侧 `SaveEMDataToStreamBinary` 只遍历声明名
 * （`VariableData.cs:3936-3959` / `:4095-4124`）。触发面是真实的：
 * 4.981 的 `CSV/VarExtGameData.csv` 比 4.980 多一条 `SAVE_DTS, QOL_FISH_INSTANCE`
 * （钓鱼实例表，`ERB/DLC/生活系统大修/0_生活系统_变量.ERH:139`），4.980 的名字表读 4.981 的档
 * 时它就是未知名。本例外只补文件层面的保留，读面接受集语义不变。
 */
class SaveUnclaimedEmPassthroughTest {

    // ------------------------------------------------------------------
    // 夹具（合成档，零环境依赖）
    // ------------------------------------------------------------------

    /** 已知（本档 `SAVE_*` 声明）的 EM 容器名。 */
    private val knownMap = "KNOWN_MAP_ZZZ"

    /** 4.981 新增、4.980 名字表里没有的 `SAVE_DTS`（真实触发面）。 */
    private val unknownDt = "QOL_FISH_INSTANCE"

    /** 未知 Map / Xml 容器名（ASCII，便于在 payload 字节里反查）。 */
    private val unknownMap = "UNCLAIMED_EM_MAP_ZZZ"
    private val unknownXml = "UNCLAIMED_EM_XML_ZZZ"

    /** 本档声明表：只有一张 Map（`knownMap`）。 */
    private fun saveNames() = EmDataNames(maps = listOf(knownMap))

    /**
     * 普通档：1 个已知顶层变量 + 4 条 EM（未知 Map、已知 Map、未知 Xml、未知 DT，
     * 未知条目交错在已知条目之间以验原序）。
     */
    private fun emFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "em-unclaimed",
            characters = emptyList(),
            variables = listOf(SaveEntry.IntArray1("DAY", longArrayOf(45))),
            emData = listOf(
                SaveEntry.MapValue(unknownMap, listOf("k1" to "v1", "k2" to "v2")),
                SaveEntry.MapValue(knownMap, listOf("m1" to "n1")),
                SaveEntry.XmlValue(unknownXml, "<r><a>1</a></r>"),
                // 未知 DT 的值故意不是合法 XML：未认领路径只走标签，绝不解析（见下面的用例）。
                SaveEntry.DataTableValue(unknownDt, "not-a-schema", "not-a-data"),
            ),
            compressed = false,
        ),
    )

    /** 只含已知 EM 条目的干净档（零变化判据）。 */
    private fun cleanEmFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "em-clean",
            characters = emptyList(),
            variables = listOf(SaveEntry.IntArray1("DAY", longArrayOf(1))),
            emData = listOf(SaveEntry.MapValue(knownMap, listOf("m1" to "n1"))),
            compressed = false,
        ),
    )

    /** 全局档：1 个已知全局变量 + 1 条未知 DT（域隔离用）。 */
    private fun globalEmFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.GLOBAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "",
            characters = emptyList(),
            variables = listOf(SaveEntry.IntArray1("GLOBAL", longArrayOf(7))),
            emData = listOf(SaveEntry.DataTableValue(unknownDt, "g-schema", "g-data")),
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

    private fun payloadOf(bytes: ByteArray): ByteArray = assertNotNull(SaveFormat.sourcePayloadOf(bytes))

    /** 生产读档的变量谓词（本夹具只关心 DAY；`DAY` 是系统变量）。 */
    private fun knownVariablePredicate(): (String) -> Boolean = { it == "DAY" || it == "GLOBAL" }

    /** UTF-16LE 子串出现次数（容器名在 payload 里的存在性/重复性判据）。 */
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

    private fun indexOfUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        outer@ for (i in 0..hay.size - n.size) {
            for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun assertSliceEquals(expected: ByteArray, actual: ByteArray, label: String) {
        assertTrue(
            expected.contentEquals(actual),
            "$label 切片必须逐字节一致（期望 ${expected.size} 字节，实际 ${actual.size}）",
        )
    }

    /**
     * 测试侧独立扫描器：从 payload 截「顶层变量段」与「EM 段」的原始条目区间。
     *
     * 刻意不复用产品代码的解析器 —— 捕获是否「原样」要由测试自己量。支持本文件夹具用到的
     * 类型字节：`0` 标量、`1` Int1D、`16` Str、`32` Map、`33` Xml、`34` DataTable。
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
                32 -> {
                    val n = i32()
                    repeat(n) { str(); str() }
                }

                33 -> str()
                34 -> {
                    str(); str()
                }

                else -> error("测试扫描器不支持的类型字节 $tag")
            }
        }

        /** 读一段（变量段 / EM 段）直到 `0xFF`，返回各条目的 `(键, 起始, 结束)`。 */
        fun section(): List<RawEntry> {
            val out = ArrayList<RawEntry>()
            while (true) {
                val start = pos
                val tag = u8()
                if (tag == 0xFF) return out
                val key = str()
                skipValue(tag)
                out.add(RawEntry(key, start, pos))
            }
        }

        /** header 之后的两个段：`(变量段, EM 段)`。 */
        fun sections(): Pair<List<RawEntry>, List<RawEntry>> {
            u8() // fileType
            i64() // uniqueCode
            i64() // scriptVersion
            str() // text
            val charaCount = i64().toInt()
            repeat(charaCount) {
                var tag = u8()
                while (tag != 0xFE) {
                    if (tag != 0xFD) skipValue(tag)
                    tag = u8()
                }
            }
            return section() to section()
        }
    }

    private class RawEntry(val key: String, val start: Int, val end: Int)

    private fun sectionsOf(payload: ByteArray): Pair<List<RawEntry>, List<RawEntry>> =
        RawScanner(payload).sections()

    private fun sliceOf(payload: ByteArray, entry: RawEntry): ByteArray =
        payload.copyOfRange(entry.start, entry.end)

    // ------------------------------------------------------------------
    // 1. 读侧：按 EM 接受集捕获未知条目、原序、逐字节等于原档
    // ------------------------------------------------------------------

    @Test
    fun `读侧按接受集捕获未知 EM 条目原始切片_原序且逐字节等于原档`() {
        val original = emFixture()
        val payload = payloadOf(original)
        val (_, emBefore) = sectionsOf(payload)
        assertEquals(
            listOf(unknownMap, knownMap, unknownXml, unknownDt),
            emBefore.map { it.key },
            "夹具原序（未知条目交错在已知条目之间）",
        )

        val data = SaveFormat.read(original, knownVariablePredicate()) { it == knownMap }
        assertEquals(listOf(knownMap), data.emData.map { it.key }, "接受集内的条目照常解析")
        assertEquals(
            listOf(unknownMap, unknownXml, unknownDt),
            data.unclaimedEmData.map { it.key },
            "接受集外的容器名进原始切片载体、保持原序",
        )
        val expected = emBefore.filter { it.key in setOf(unknownMap, unknownXml, unknownDt) }
            .associate { it.key to sliceOf(payload, it) }
        for (u in data.unclaimedEmData) {
            assertSliceEquals(expected.getValue(u.key), u.bytes, u.key)
        }

        assertTrue(data.droppedVariables.isEmpty(), "EM 名字不是变量，不进上屏报告的 dropped 清单")
        assertTrue(data.unclaimedVariables.isEmpty(), "顶层变量段不受 EM 谓词影响")
        assertEquals(listOf("DAY"), data.variables.map { it.key })

        // 旧签名（不给 EM 谓词）逐字不变：全部 EM 条目照旧解析进 emData，载体为空。
        val legacy = SaveFormat.read(original, knownVariablePredicate())
        assertEquals(4, legacy.emData.size, "旧口径不筛 EM（读出即由池侧丢弃）")
        assertTrue(legacy.unclaimedEmData.isEmpty(), "旧签名不产生 EM 切片（零变化）")
    }

    // ------------------------------------------------------------------
    // 2. 未知条目不解析值（Xml/DT 只走标签）
    // ------------------------------------------------------------------

    @Test
    fun `未知 Xml 与 DT 条目不解析值_坏 XML 内容同样原样往返`() {
        // 夹具里的未知 DT 两个字符串都不是合法 XML：若走「解析后再重编码」，读档就会抛。
        val data = SaveFormat.read(emFixture(), knownVariablePredicate()) { it == knownMap }
        assertEquals(listOf(unknownXml, unknownDt), data.unclaimedEmData.drop(1).map { it.key })

        val rewritten = SaveFormat.write(data)
        val parsed = SaveFormat.read(rewritten)
        val dt = parsed.emData.single { it.key == unknownDt } as SaveEntry.DataTableValue
        assertEquals("not-a-schema", dt.schemaXml, "未知 DT 的 schema 原文")
        assertEquals("not-a-data", dt.dataXml, "未知 DT 的数据原文")
        val xml = parsed.emData.single { it.key == unknownXml } as SaveEntry.XmlValue
        assertEquals("<r><a>1</a></r>", xml.xml, "未知 Xml 的 OuterXml 原文")
        assertEquals(
            listOf("k1" to "v1", "k2" to "v2"),
            (parsed.emData.single { it.key == unknownMap } as SaveEntry.MapValue).entries,
            "未知 Map 的键值对与原序",
        )
    }

    // ------------------------------------------------------------------
    // 3. 往返：仍在、切片逐字节一致、重复往返稳定
    // ------------------------------------------------------------------

    @Test
    fun `往返_load_save_load_未知 EM 容器仍在且切片逐字节一致`() {
        val first = SaveFormat.read(emFixture(), knownVariablePredicate()) { it == knownMap }
        val rewritten = SaveFormat.write(first)

        val (_, emAfter) = sectionsOf(payloadOf(rewritten))
        assertEquals(
            listOf(knownMap, unknownMap, unknownXml, unknownDt),
            emAfter.map { it.key },
            "已知条目在前、未知切片按原序落在段尾",
        )

        val second = SaveFormat.read(rewritten, knownVariablePredicate()) { it == knownMap }
        assertEquals(
            first.unclaimedEmData.map { it.key },
            second.unclaimedEmData.map { it.key },
            "第二趟读档仍捕到同名同序切片",
        )
        for (i in first.unclaimedEmData.indices) {
            assertSliceEquals(
                first.unclaimedEmData[i].bytes,
                second.unclaimedEmData[i].bytes,
                "第 $i 条",
            )
        }
        // 全量口径读回：值与第一条读到时相同。
        val parsed = SaveFormat.read(rewritten)
        assertEquals(
            listOf("m1" to "n1"),
            (parsed.emData.single { it.key == knownMap } as SaveEntry.MapValue).entries,
            "已知 Map 值不变",
        )
        assertEquals("not-a-data", (parsed.emData.single { it.key == unknownDt } as SaveEntry.DataTableValue).dataXml)

        // 第二次重写起字节收敛（源档与第一趟产物 payload 相同 ⇒ 幂等短路命中）。
        val b1 = rewritten
        val b2 = SaveFormat.write(SaveFormat.read(b1, knownVariablePredicate()) { it == knownMap })
        val b3 = SaveFormat.write(SaveFormat.read(b2, knownVariablePredicate()) { it == knownMap })
        assertTrue(b1.contentEquals(b2), "第一次重写后 payload 已收敛")
        assertTrue(b2.contentEquals(b3), "重复往返逐字节稳定")
        val payload = payloadOf(b3)
        for (key in listOf(unknownMap, unknownXml, unknownDt)) {
            assertEquals(1, countUtf16(payload, key), "$key 只能出现一次（不重复累积）")
        }
    }

    // ------------------------------------------------------------------
    // 4. 已知 EM 条目字节/顺序不变
    // ------------------------------------------------------------------

    @Test
    fun `已知 EM 条目字节与顺序不变`() {
        val original = emFixture()
        val data = SaveFormat.read(original, knownVariablePredicate()) { it == knownMap }
        val rewritten = SaveFormat.write(data)

        val (_, emBefore) = sectionsOf(payloadOf(original))
        val (_, emAfter) = sectionsOf(payloadOf(rewritten))
        val knownBefore = emBefore.single { it.key == knownMap }
        val knownAfter = emAfter.single { it.key == knownMap }
        assertSliceEquals(
            sliceOf(payloadOf(original), knownBefore),
            sliceOf(payloadOf(rewritten), knownAfter),
            "已知 EM 条目的编码字节",
        )
        assertEquals(0, emAfter.indexOfFirst { it.key == knownMap }, "已知条目仍在段首（未知切片只追加）")
        // 顶层变量段一个字节都不动。
        val (varsBefore, _) = sectionsOf(payloadOf(original))
        val (varsAfter, _) = sectionsOf(payloadOf(rewritten))
        assertSliceEquals(
            payloadOf(original).copyOfRange(varsBefore.first().start, varsBefore.last().end),
            payloadOf(rewritten).copyOfRange(varsAfter.first().start, varsAfter.last().end),
            "变量段字节区间",
        )
    }

    // ------------------------------------------------------------------
    // 5. 零变化：无未知 EM 条目 ⇒ 逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun `无未知 EM 条目_输出逐字节不变`() {
        val clean = cleanEmFixture()
        val data = SaveFormat.read(clean, knownVariablePredicate()) { it == knownMap }
        assertTrue(data.unclaimedEmData.isEmpty(), "接受集内的条目不算未认领")
        assertTrue(clean.contentEquals(SaveFormat.write(data)), "无未知 EM 必须逐字节不变（含容器头）")
        assertTrue(
            clean.contentEquals(SaveFormat.write(SaveFormat.read(clean))),
            "旧签名路径同样逐字节不变",
        )
        // 名字表为空（未装配）时同样零变化：没有任何 EM 条目的档不受影响。
        val noEm = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL, uniqueCode = 1, scriptVersion = 0,
                text = "", compressed = false,
            ),
        )
        assertTrue(noEm.contentEquals(SaveFormat.write(SaveFormat.read(noEm, { false }, { false }))))
    }

    // ------------------------------------------------------------------
    // 6. 生产路：loadData → saveData（变量池现抓的流式路）
    // ------------------------------------------------------------------

    @Test
    fun `生产路读档再存档_未知 EM 容器回写一次_已知容器按活池写一次`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = emFixture()
        val store = VariableStore()
        val sets = EmSaveSets(save = saveNames())
        val fs = FileSystemOps(store, io, config(), sets)

        fs.loadData(0)
        assertEquals(45L, store.readInt("DAY", longArrayOf(0)), "已知变量照常生效")
        assertTrue(fs.saveData(0, "rewrite"), "再存档应成功")

        val after = io.files.getValue(SLOT_PATH)
        val reread = SaveFormat.read(after)
        assertEquals(
            listOf(knownMap, unknownMap, unknownXml, unknownDt),
            reread.emData.map { it.key },
            "已知容器由活池写一条，未知容器按原序追加",
        )
        val payload = payloadOf(after)
        for (key in listOf(knownMap, unknownMap, unknownXml, unknownDt)) {
            assertEquals(1, countUtf16(payload, key), "$key 在 payload 里只出现一次")
        }
        assertEquals(
            listOf("k1" to "v1", "k2" to "v2"),
            (reread.emData.single { it.key == unknownMap } as SaveEntry.MapValue).entries,
            "未知 Map 值原样",
        )
        assertEquals("rewrite", reread.text, "描述文本照常更新")
    }

    @Test
    fun `回标题后旧档的 EM 切片不再写进新局`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = emFixture()
        val store = VariableStore()
        val fs = FileSystemOps(store, io, config(), EmSaveSets(save = saveNames()))

        fs.loadData(0)
        assertTrue(
            SaveUnclaimedPassthrough.of(store)?.emSlices(store, global = false)?.isNotEmpty() == true,
            "读档后 EM 切片已存槽",
        )
        // RESETDATA / gotoTitle 的复位面（VariableStore.resetData）；lastLoadNo 回 -1
        // ⇒ 上一份档的 EM 切片与顶层/角色切片一起失效。
        store.resetData()
        assertTrue(
            SaveUnclaimedPassthrough.of(store)?.emSlices(store, global = false)?.isEmpty() != false,
            "复位后 EM 切片必须失效（lastLoadNo 闸）",
        )
        assertTrue(fs.saveData(0, "new-game"), "新局存档应成功")
        val payload = payloadOf(io.files.getValue(SLOT_PATH))
        for (key in listOf(unknownMap, unknownXml, unknownDt)) {
            assertEquals(-1, indexOfUtf16(payload, key), "旧档 EM 未知容器不许漏进新局：$key")
        }
    }

    // ------------------------------------------------------------------
    // 7. 域隔离：normal / global 分槽不串档
    // ------------------------------------------------------------------

    @Test
    fun `normal 与 global 的 EM 切片不串档`() {
        val io = MemorySaveIo()
        val normalUnknown = "UNCLAIMED_EM_NORMAL_ZZZ"
        io.files[SLOT_PATH] = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL, uniqueCode = 0, scriptVersion = 0,
                text = "n", characters = emptyList(),
                variables = listOf(SaveEntry.IntArray1("DAY", longArrayOf(3))),
                emData = listOf(SaveEntry.XmlValue(normalUnknown, "<n/>")),
                compressed = false,
            ),
        )
        io.files[GLOBAL_PATH] = globalEmFixture()
        val store = VariableStore()
        val fs = FileSystemOps(store, io, config(), EmSaveSets.EMPTY)

        fs.loadData(0)
        assertTrue(fs.loadGlobal(), "LOADGLOBAL 应成功")
        fs.saveGlobal()
        val globalPayload = payloadOf(io.files.getValue(GLOBAL_PATH))
        assertEquals(1, countUtf16(globalPayload, unknownDt), "global 域自己的切片回写")
        assertEquals(-1, indexOfUtf16(globalPayload, normalUnknown), "normal 域的 EM 切片不许写进 global.sav")

        assertTrue(fs.saveData(0, "n2"), "SAVEDATA 应成功")
        val normalPayload = payloadOf(io.files.getValue(SLOT_PATH))
        assertEquals(1, countUtf16(normalPayload, normalUnknown), "normal 域自己的切片回写")
        assertEquals(-1, indexOfUtf16(normalPayload, unknownDt), "global 域的 EM 切片不许写进普通档")
    }

    // ------------------------------------------------------------------
    // 8. 写回闸：装载后又被本档声明表接纳的名字跳过
    // ------------------------------------------------------------------

    @Test
    fun `装载后重新声明_写侧跳过多余的 EM 切片`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = emFixture()
        val store = VariableStore()
        // 第一趟：名字表为空 ⇒ 全部 EM 容器都成了未认领切片。
        FileSystemOps(store, io, config(), EmSaveSets.EMPTY).loadData(0)
        assertTrue(
            SaveUnclaimedPassthrough.of(store)?.emSlices(store, global = false)?.size == 4,
            "空名字表下 4 条 EM 全部进切片",
        )

        // 第二趟：同一份局，写侧名字表里已经有 unknownDt（= 装载后被重新声明）；
        // emitSnapshot 会按活池写它，若再回写切片就是同名两条 ⇒ 必须跳过。
        val later = EmSaveSets(save = EmDataNames(dts = listOf(unknownDt)))
        assertTrue(FileSystemOps(store, io, config(), later).saveData(0, "redeclared"), "再存档应成功")
        val payload = payloadOf(io.files.getValue(SLOT_PATH))
        assertEquals(-1, indexOfUtf16(payload, unknownDt), "已进本档声明表的名字不再回写切片")
        assertEquals(1, countUtf16(payload, unknownMap), "仍未声明的名字照常回写")
        assertEquals(1, countUtf16(payload, unknownXml), "仍未声明的名字照常回写")

        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertTrue(reread.emData.none { it.key == unknownDt }, "档里没有同名重复条目")
    }

    // ------------------------------------------------------------------
    // 9. 物化入口与流式入口逐字节一致
    // ------------------------------------------------------------------

    @Test
    fun `物化入口与流式入口逐字节一致_EM 切片只写一次`() {
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = emFixture()
        val store = VariableStore()
        val sets = EmSaveSets(save = saveNames())
        FileSystemOps(store, io, config(), sets).loadData(0)

        val out = ByteArrayOutputStream()
        SaveFormat.writeStreaming(compressed = false, out) { w ->
            SaveCapture.emitNormal(store, "materialize", w, sets.save)
        }
        val streamed = out.toByteArray()
        val captured = SaveCapture.normal(store, "materialize", compressed = false, emData = sets.save)
        val materialized = SaveFormat.write(captured)

        assertTrue(streamed.contentEquals(materialized), "物化入口与流式入口必须逐字节一致")
        val streamedRead = SaveFormat.read(streamed, knownVariablePredicate()) { it == knownMap }
        assertEquals(
            streamedRead.unclaimedEmData.map { it.key },
            captured.unclaimedEmData.map { it.key },
            "物化读回同样保住 EM 切片（载体不空、不解析成值对象）",
        )
        for (i in streamedRead.unclaimedEmData.indices) {
            assertSliceEquals(
                streamedRead.unclaimedEmData[i].bytes,
                captured.unclaimedEmData[i].bytes,
                "第 $i 条切片",
            )
        }
        val payload = payloadOf(streamed)
        for (key in listOf(knownMap, unknownMap, unknownXml, unknownDt)) {
            assertEquals(1, countUtf16(payload, key), "$key 只写一次")
        }
    }

    // ------------------------------------------------------------------
    // 10. 未装配槽 / 未读档：不带任何 EM 切片
    // ------------------------------------------------------------------

    @Test
    fun `槽未装配时零变化_新局存档不带 EM 切片`() {
        val io = MemorySaveIo()
        val store = VariableStore()
        val sets = EmSaveSets(save = saveNames())
        val fs = FileSystemOps(store, io, config(), sets)
        assertTrue(fs.saveData(0, "fresh"), "SAVEDATA 应成功")
        assertTrue(
            SaveUnclaimedPassthrough.of(store) == null,
            "只存档不读档不得凭空装配切片槽（零变化）",
        )
        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertTrue(reread.emData.isEmpty(), "池里没有 EM 容器 ⇒ EM 段为空")
        assertFalse(reread.variables.isEmpty(), "变量段照常有内容")
    }

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径。 */
        const val SLOT_PATH = "sav/save00.sav"

        /** 同上：全局档路径。 */
        const val GLOBAL_PATH = "sav/global.sav"
    }
}
