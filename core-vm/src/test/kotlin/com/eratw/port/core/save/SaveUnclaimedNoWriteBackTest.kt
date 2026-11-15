package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 未认领存档键的**域边界现状锁**（L12 A 案 → **L15 B1 裁定**后的口径）。
 *
 * ## 锁的是什么
 *
 * 原版读档遇到「存档里有、当前游戏没声明」的变量键是**读掉值再丢弃**
 * （`VariableData.cs:4081-4160`），写档只遍历声明表（`VariableData.cs:3980-3997`）。
 * 移植侧 L12（CONTRACT-RT2-UNCLAIMED-A）先按 A 案锁了「不写回 + 告知」；2026-11-14
 * **L15（CONTRACT-RT2-UNCLAIMED-B1）登记 ADR-005 例外**：**顶层 normal/global 域**的
 * 未认领键改为「原始编码切片」随档回写（已知变量之后、`EOF` 之前），`char` 域与 EM 段
 * 仍按原版丢弃（第二波）。
 *
 * 本文件现在的四条判据：
 *
 * 1. 生产路 `loadData → saveData`：**顶层**未知键仍在且只有一条（原始切片回写），
 *    已知键值逐项不变；**角色块内**未知键自 L17 起按块内 `NO` 回写（ADR-005 例外第二波），
 *    只在**原角色**的块里出现、不挂到别的角色；
 * 2. `RestoreReport` 仍把两域未知键交给上屏清单 —— 回写不改变告知面；
 * 3. `SaveFormat.read(keepKey) → write`：顶层未知键以原始切片（字节级）回写，
 *    不借 `sourceFile` 的逐字节幂等短路把**整份原档**原样吐回来；角色未知键进
 *    `unclaimedCharacterVariables`（按 `NO` 分桶）并随块回写；
 * 4. 未知键的**报告面**（`droppedCharacterVariables` 键名清单）语义不变。
 *
 * 夹具是合成档（零环境依赖）：2 个未知键 + 5 个已知键（含系统键与用户 SAVEDATA 键）。
 */
class SaveUnclaimedNoWriteBackTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun idx(vararg v: Long): LongArray = v

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

    /** eratw 生产口径的读档分派只需 binary=true；压缩与目录不计入本判据。 */
    private fun config() = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)

    private fun dim(body: String) = DimParser.parse(
        "#DIM", body, isPrivate = false, DimParseConfig(systemSaveInBinary = true),
    )

    /** 已知键只声明在当前游戏的池里；两个未知键从不声明。 */
    private fun storeWithKnownDeclarations(): VariableStore = VariableStore().also { s ->
        s.declareUserVar(dim("SAVEDATA KEPT_USER, 2"))
    }

    /**
     * 合成档：顶层 3 个已知键（MONEY / DAY / KEPT_USER）+ 1 个未知键；
     * 角色块 2 个已知键（NO / CFLAG）+ 1 个未知键。
     * 两份未知键都带值，且名字独一无二，便于在 payload 字节里反查「是否真的没了」。
     */
    private fun fixtureSave(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "unclaimed",
            characters = listOf(
                listOf(
                    SaveEntry.IntValue("NO", 1),
                    SaveEntry.IntArray1(UNKNOWN_CHARA_KEY, longArrayOf(7, 8, 9)),
                    SaveEntry.IntArray1("CFLAG", longArrayOf(11, 22, 33)),
                ),
            ),
            variables = listOf(
                SaveEntry.IntArray1("MONEY", idx(123)),
                SaveEntry.IntArray1("DAY", idx(45)),
                SaveEntry.IntArray1("KEPT_USER", longArrayOf(5, 6)),
                SaveEntry.IntValue(UNKNOWN_TOP_KEY, 777),
            ),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 在 UTF-16LE payload 里找键名（存档的变量名编码，EraBinaryDataWriter.cs:83-173）。 */
    private fun indexOfUtf16(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.UTF_16LE)
        outer@ for (i in 0..hay.size - n.size) {
            for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    /** 同上，出现次数（未知键不许重复累积）。 */
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

    /** 键名在 payload 里必须存在且只出现一次（未知键不丢、不重复累积）。 */
    private fun assertOccursOnce(hay: ByteArray, needle: String, label: String) {
        val at = indexOfUtf16(hay, needle)
        assertTrue(at >= 0, "$label 必须回写")
        assertTrue(indexOfUtf16(hay.copyOfRange(at + 1, hay.size), needle) < 0, "$label 只能出现一条")
    }

    // ------------------------------------------------------------------
    // 1. 生产路：读档 → 再存档 → 两域未知键各按自己的身份回写、已知键值不变
    // ------------------------------------------------------------------

    @Test
    fun `读档再写档_顶层未知键回写一条_角色未知键按 NO 回写_已知键值不变`() {
        val io = MemorySaveIo()
        val original = fixtureSave()
        io.files[SLOT_PATH] = original
        val store = storeWithKnownDeclarations()
        val fs = FileSystemOps(store, io, config())

        // 夹具自证：原档 payload 里两个未知键确实存在，否则后面的「回写/消失」不算数。
        val beforePayload = assertNotNull(SaveFormat.sourcePayloadOf(original))
        assertTrue(indexOfUtf16(beforePayload, UNKNOWN_TOP_KEY) >= 0, "夹具应含顶层未知键")
        assertTrue(indexOfUtf16(beforePayload, UNKNOWN_CHARA_KEY) >= 0, "夹具应含角色块未知键")

        // 生产读档：keepKey = 当前游戏声明表 ⇒ 未知键只走标签、值不进变量池；
        // 顶层切口与角色块切口分别存槽（L15 / L17），写侧按各自的域回写。
        fs.loadData(0)
        assertEquals(123L, store.readInt("MONEY", idx(0)), "MONEY 照常生效")
        assertEquals(45L, store.readInt("DAY", idx(0)), "DAY 照常生效")
        assertEquals(5L, store.readInt("KEPT_USER", idx(0)), "用户 SAVEDATA 键第 0 项")
        assertEquals(6L, store.readInt("KEPT_USER", idx(1)), "用户 SAVEDATA 键第 1 项")
        assertEquals(1L, store.readInt("NO", idx(0)), "角色 NO")
        assertEquals(11L, store.readInt("CFLAG", idx(0, 0)), "角色 CFLAG 首项")

        // 再存档：写侧从变量池现抓（SaveCapture.emitVariables），不经过读入的 SaveData。
        assertTrue(fs.saveData(0, "rewrite"), "再存档应成功")

        val after = io.files.getValue(SLOT_PATH)
        val afterPayload = assertNotNull(SaveFormat.sourcePayloadOf(after))
        assertOccursOnce(afterPayload, UNKNOWN_TOP_KEY, "顶层未知键（L15）")
        assertOccursOnce(afterPayload, UNKNOWN_CHARA_KEY, "角色块未知键（L17，按块内 NO 绑定）")

        // 全量口径读回（无过滤器）：两域未知键都在、值不变。
        val reread = SaveFormat.read(after)
        assertEquals(1, reread.variables.count { it.key == UNKNOWN_TOP_KEY }, "顶层未知键条目数 = 1")
        assertEquals(
            777L,
            (reread.variables.single { it.key == UNKNOWN_TOP_KEY } as SaveEntry.IntValue).value,
            "顶层未知键值原样回写",
        )
        assertEquals(
            1,
            reread.characters.sumOf { block -> block.count { it.key == UNKNOWN_CHARA_KEY } },
            "角色未知键条目数 = 1（只挂在 NO=1 的块里）",
        )
        assertEquals(
            listOf(7L, 8L, 9L),
            (reread.characters.single().single { it.key == UNKNOWN_CHARA_KEY } as SaveEntry.IntArray1).values.toList(),
            "角色未知数组值原样回写",
        )
        assertEquals(1, reread.characters.size, "角色数不变")
        assertEquals("rewrite", reread.text, "描述文本照常更新")

        // 已知键值逐项不变（档内条目口径，与被写回的变量池互为见证）。
        assertEquals(123L, (reread.variables.single { it.key == "MONEY" } as SaveEntry.IntArray1).values[0])
        assertEquals(45L, (reread.variables.single { it.key == "DAY" } as SaveEntry.IntArray1).values[0])
        assertEquals(
            listOf(5L, 6L),
            (reread.variables.single { it.key == "KEPT_USER" } as SaveEntry.IntArray1).values.toList(),
        )
        assertEquals(1L, (reread.characters.single().single { it.key == "NO" } as SaveEntry.IntValue).value)
        val cflag = reread.characters.single().single { it.key == "CFLAG" } as SaveEntry.IntArray1
        assertEquals(listOf(11L, 22L, 33L), cflag.values.take(3).toList(), "CFLAG 前 3 项不变")
        assertTrue(cflag.values.drop(3).all { it == 0L }, "CFLAG 尾段按声明尺寸清零（needInit 口径）")
    }

    // ------------------------------------------------------------------
    // 3. 编码面：带过滤器读回的 SaveData 回写时两域各按原始切片带回
    // ------------------------------------------------------------------

    @Test
    fun `读侧丢弃后回写_顶层与角色块未知键都按原始切片带回`() {
        val original = fixtureSave()
        val known = setOf("MONEY", "DAY", "KEPT_USER", "NO", "CFLAG")

        val filtered = SaveFormat.read(original) { it in known }
        assertEquals(listOf(UNKNOWN_TOP_KEY), filtered.droppedVariables, "顶层丢弃的键名进 droppedVariables")
        assertEquals(
            listOf(UNKNOWN_CHARA_KEY),
            filtered.droppedCharacterVariables,
            "角色块丢弃的键名进 droppedCharacterVariables",
        )
        assertNull(filtered.variables.firstOrNull { it.key == UNKNOWN_TOP_KEY }, "顶层值没解析进 variables")
        assertNull(filtered.characters.single().firstOrNull { it.key == UNKNOWN_CHARA_KEY }, "角色块值没留下")
        assertEquals(
            listOf(UNKNOWN_TOP_KEY),
            filtered.unclaimedVariables.map { it.key },
            "顶层未知键进原始切片载体（L15）",
        )
        assertEquals(
            listOf(UNKNOWN_CHARA_KEY),
            filtered.unclaimedCharacterVariables[1L]?.map { it.key },
            "角色块未知键按块内 NO=1 分桶进切片载体（L17）",
        )

        val rewritten = SaveFormat.write(filtered)
        // 逐字节幂等短路只在 payload 未变时生效：顶层切片被挪到段尾，payload 必不同
        // ⇒ 必须重新编码；但切片本身逐字节相同。
        assertFalse(original.contentEquals(rewritten), "段序变化（顶层切片移段尾）后字节必须变化")
        val payload = assertNotNull(SaveFormat.sourcePayloadOf(rewritten))
        assertEquals(1, countUtf16(payload, UNKNOWN_TOP_KEY), "顶层未知键回写一条")
        assertEquals(1, countUtf16(payload, UNKNOWN_CHARA_KEY), "角色块未知键回写一条")

        val again = SaveFormat.read(rewritten)
        assertEquals(
            listOf("MONEY", "DAY", "KEPT_USER", UNKNOWN_TOP_KEY),
            again.variables.map { it.key },
            "已知顶层条目逐条仍在、未知键按原样落到段尾",
        )
        assertEquals(
            listOf("NO", "CFLAG", UNKNOWN_CHARA_KEY),
            again.characters.single().map { it.key },
            "已知角色条目逐条仍在、角色块未知键按原序落到 EOC 之前",
        )
        assertEquals(777L, (again.variables.last() as SaveEntry.IntValue).value, "未知键值原样")
        assertEquals(
            listOf(7L, 8L, 9L),
            (again.characters.single().last() as SaveEntry.IntArray1).values.toList(),
            "角色块未知数组值原样",
        )
    }

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径（`SaveConfig.normalPath`）。 */
        const val SLOT_PATH = "sav/save00.sav"

        /** 顶层未知键（当前游戏从不声明；ASCII 便于 payload 字节反查）。 */
        const val UNKNOWN_TOP_KEY = "UNCLAIMED_TOP_KEY_ZZZ"

        /** 角色块未知键（同上）。 */
        const val UNKNOWN_CHARA_KEY = "UNCLAIMED_CHARA_KEY_ZZZ"
    }
}
