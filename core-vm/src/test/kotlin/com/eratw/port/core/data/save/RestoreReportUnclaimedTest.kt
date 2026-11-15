package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-2：读档报告的三域「未认领键」清单（`RestoreReport.unclaimed*`）。
 *
 * 判据（CONTRACT-RT2-M12 决定 2）：
 * - 只收**变量池查不到定义**的键 —— 类型/维度不符是「已声明但落不进」，只计 skipped；
 * - 分域：普通档顶层 → normal，`global.sav` 顶层 → global，角色块 → chara；
 * - 排序去重（同键在一块里出现多次只列一次），不截断；
 * - applied / skipped / characters 三项计数不受新增清单影响（形状闸回归）。
 */
class RestoreReportUnclaimedTest {

    private fun newStore(): VariableStore = VariableStore().apply {
        val cfg = DimParseConfig(systemSaveInBinary = true)
        declareUserVar(DimParser.parse("#DIM", "SAVEDATA KNOWN_N", isPrivate = false, cfg))
        declareUserVar(DimParser.parse("#DIM", "GLOBAL SAVEDATA KNOWN_G", isPrivate = false, cfg))
        declareUserVar(DimParser.parse("#DIM", "CHARADATA SAVEDATA KNOWN_C", isPrivate = false, cfg))
    }

    private fun data(
        kind: SaveFileKind,
        variables: List<SaveEntry>,
        characters: List<List<SaveEntry>> = emptyList(),
    ): SaveData = SaveData(
        fileType = kind,
        uniqueCode = 0L,
        scriptVersion = 0L,
        text = "",
        characters = characters,
        variables = variables,
    )

    @Test
    fun `普通档_三域分别入册且排序去重`() {
        val store = newStore()
        val report = data(
            SaveFileKind.NORMAL,
            variables = listOf(
                SaveEntry.IntArray1("KNOWN_N", longArrayOf(1L)),
                SaveEntry.IntValue("ZZZ_N", 2L),
                SaveEntry.IntValue("AAA_N", 3L),
            ),
            characters = listOf(
                listOf(
                    SaveEntry.IntArray1("KNOWN_C", longArrayOf(4L)),
                    SaveEntry.IntValue("ZZZ_C", 5L),
                    SaveEntry.IntValue("AAA_C", 6L),
                    SaveEntry.IntValue("ZZZ_C", 7L),
                ),
            ),
        ).applyTo(store)

        assertEquals(listOf("AAA_N", "ZZZ_N"), report.unclaimedNormal)
        assertEquals(listOf("AAA_C", "ZZZ_C"), report.unclaimedChara, "同键重复只列一次（排序）")
        assertTrue(report.unclaimedGlobal.isEmpty(), "普通档顶层不进 global 域")
        assertEquals(2, report.applied, "KNOWN_N + KNOWN_C")
        assertEquals(5, report.skipped, "两个顶层未知 + 三个角色未知（重复键各计一次）")
        assertEquals(1, report.characters)
    }

    @Test
    fun `全局档_顶层未声明键只进global域`() {
        val store = newStore()
        val report = data(
            SaveFileKind.GLOBAL,
            variables = listOf(
                SaveEntry.IntArray1("KNOWN_G", longArrayOf(1L)),
                SaveEntry.IntValue("ZZZ_G", 2L),
            ),
        ).applyTo(store)

        assertEquals(listOf("ZZZ_G"), report.unclaimedGlobal)
        assertTrue(report.unclaimedNormal.isEmpty(), "global.sav 顶层不得落进 normal 域")
        assertTrue(report.unclaimedChara.isEmpty())
        assertEquals(1, report.applied)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `VAR档_顶层未声明键进global域`() {
        val store = newStore()
        val report = data(
            SaveFileKind.VAR,
            variables = listOf(
                SaveEntry.IntArray1("KNOWN_G", longArrayOf(1L)),
                SaveEntry.IntValue("ZZZ_V", 2L),
            ),
        ).applyTo(store)

        assertEquals(listOf("ZZZ_V"), report.unclaimedGlobal, "var_*.dat 顶层只有全局变量")
        assertTrue(report.unclaimedNormal.isEmpty())
        assertEquals(1, report.applied)
    }

    @Test
    fun `类型不符是已声明但不符_只计skipped不入未认领`() {
        val store = newStore()
        val report = data(
            SaveFileKind.NORMAL,
            variables = listOf(
                // KNOWN_N 声明为 INT 标量，档里却是字符串 ⇒ 原版「读掉值再丢弃」，不算未声明。
                SaveEntry.StrValue("KNOWN_N", "x"),
            ),
        ).applyTo(store)

        assertTrue(report.unclaimedNormal.isEmpty(), "落不进 ≠ 没声明")
        assertEquals(1, report.skipped)
        assertEquals(0, report.applied)
    }

    @Test
    fun `全认领档_三域清单皆空`() {
        val store = newStore()
        val report = data(
            SaveFileKind.NORMAL,
            variables = listOf(SaveEntry.IntArray1("KNOWN_N", longArrayOf(9L))),
            characters = listOf(listOf(SaveEntry.IntArray1("KNOWN_C", longArrayOf(8L)))),
        ).applyTo(store)

        assertTrue(report.unclaimedNormal.isEmpty())
        assertTrue(report.unclaimedGlobal.isEmpty())
        assertTrue(report.unclaimedChara.isEmpty())
        assertEquals(2, report.applied)
        assertEquals(0, report.skipped)
    }

    /**
     * 生产二进制路径：`FileSystemOps.loadData` 传 `isKnownSaveKey` 当 `keepKey`，
     * 未知键的值不建数组、**键名**必须留进报告（否则玩家永远看不到「哪些变量被跳过」）。
     */
    @Test
    fun `二进制读侧_keepKey丢弃的键名进入未认领清单`() {
        val store = newStore()
        val bytes = SaveFormat.write(
            data(
                SaveFileKind.NORMAL,
                variables = listOf(
                    SaveEntry.IntArray1("KNOWN_N", longArrayOf(1L)),
                    SaveEntry.IntArray1("ZZZ_N", longArrayOf(2L)),
                ),
                characters = listOf(
                    listOf(
                        SaveEntry.IntArray1("KNOWN_C", longArrayOf(3L)),
                        SaveEntry.IntArray1("ZZZ_C", longArrayOf(4L)),
                    ),
                ),
            ),
        )
        val read = SaveFormat.read(bytes) { key -> key == "KNOWN_N" || key == "KNOWN_C" }
        assertEquals(listOf("ZZZ_N"), read.droppedVariables, "顶层被丢弃的键名要留档")
        assertEquals(listOf("ZZZ_C"), read.droppedCharacterVariables, "角色块被丢弃的键名要留档")

        val report = read.applyTo(store)
        assertEquals(listOf("ZZZ_N"), report.unclaimedNormal)
        assertEquals(listOf("ZZZ_C"), report.unclaimedChara)
        assertEquals(2, report.applied, "KNOWN_N + KNOWN_C")
        assertEquals(0, report.skipped, "丢弃的值不建数组，也不进 skipped 计数")
    }
}
