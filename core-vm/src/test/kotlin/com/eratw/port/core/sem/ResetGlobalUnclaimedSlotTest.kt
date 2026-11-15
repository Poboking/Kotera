package com.eratw.port.core.sem

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveUnclaimedPassthrough
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.stmt.FunctionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L32（CONTRACT-RT2-RESETGLOBAL-EM）：`RESETGLOBAL` 必须把 L15/L28 新增的**未认领切片槽**
 * 里的 global 域一起复位。
 *
 * ## 锁什么
 *
 * 原版一条 `RESETGLOBAL` = `ResetGlobalData()`（`Instraction.Child.cs:1970-1982` 的
 * `RESETGLOBAL_Instruction` → `:1980 exm.VEvaluator.ResetGlobalData()`；
 * `VariableEvaluator.cs:1746-1755`）的三步：`RemoveEMGlobalData`（`:1749` →
 * `VariableData.cs:1320`）、`RemoveEMStaticData`（`:1750` → `:1344`）、
 * `SetDefaultGlobalValue`（`:1754` → `:618-624`）。global 变量的复位语义是「整份全局状态
 * 丢掉」，未知键自然没有留在 global.sav 里的道理；移植侧的切片槽是 ADR-005 例外新增的
 * 状态，不在原版复位链上，所以必须由 `RESETGLOBAL` 分支显式清 global 域。
 *
 * - global 顶层切片 + EM global 槽清空 ⇒ 之后 `SAVEGLOBAL` 不再写出重置前的未知顶层键
 *   与未知 EM 容器（同一会话里先存一次作对照，确认缺陷面真实存在）；
 * - normal 槽**不动**：它的生命周期由 `ResetData` 的 `RemoveEMSaveData`
 *   （`VariableEvaluator.cs:1727-1732`）与 `LOADDATA` 管，`RESETGLOBAL` 无权清它；
 * - 未装配槽 / 空槽 ⇒ 调用是 no-op，也不凭空建槽；
 * - 清槽异常只留痕、不翻转本语句的成功语义（`OutOfMemoryError` 上抛）；
 * - 对象树入口与扁平直读入口两条分派一致。
 *
 * 既有 `ResetGlobalAuditTest` 不动：本文件只补槽的生命周期，原版三步的读数仍由那边钉住。
 */
class ResetGlobalUnclaimedSlotTest {

    // ------------------------------------------------------------------
    // 夹具
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

    /** 一会话的读档/存档接线 + 内存文件系统，便于逐次 `saveGlobal` 后回读。 */
    private class Session(
        val env: SemTestEnv,
        val io: MemorySaveIo,
        val fs: FileSystemOps,
    )

    /** 生产口径的二进制 + 不压缩（payload 可直接读，容器名可反查）。 */
    private fun config() = SaveConfig(systemSaveInBinary = true, zipSaveData = false, useSaveFolder = true)

    /** 全局档：已知 GLOBAL 值 + 未知顶层键 + 未知 Map / DT 容器（EM 名字表为空 ⇒ 全部未认领）。 */
    private fun globalFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.GLOBAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "",
            characters = emptyList(),
            variables = listOf(
                SaveEntry.IntArray1("GLOBAL", longArrayOf(7)),
                SaveEntry.IntValue(UNKNOWN_GLOBAL_TOP, 777),
            ),
            emData = listOf(
                SaveEntry.MapValue(UNKNOWN_GLOBAL_MAP, listOf("k1" to "v1")),
                SaveEntry.DataTableValue(UNKNOWN_GLOBAL_DT, "schema", "data"),
            ),
            compressed = false,
        ),
    )

    /** 普通档：已知 DAY 值 + 未知顶层键 + 未知 Map 容器（normal 槽对照用）。 */
    private fun normalFixture(): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 0,
            scriptVersion = 0,
            text = "normal",
            characters = emptyList(),
            variables = listOf(
                SaveEntry.IntArray1("DAY", longArrayOf(45)),
                SaveEntry.IntValue(UNKNOWN_NORMAL_TOP, 888),
            ),
            emData = listOf(
                SaveEntry.MapValue(UNKNOWN_NORMAL_MAP, listOf("n1" to "m1")),
            ),
            compressed = false,
        ),
    )

    /** 装配存档接线 + 读入 global 档（槽由 `LOADGLOBAL` 的成功路径装配）。 */
    private fun openGlobal(): Session {
        val env = SemTestEnv()
        val io = MemorySaveIo()
        io.files[GLOBAL_PATH] = globalFixture()
        val cfg = config()
        val emSets = EmSaveSets.EMPTY
        SaveWiring.install(env.store, cfg, 20, emSets)
        val fs = FileSystemOps(env.store, io, cfg, emSets)
        assertTrue(fs.loadGlobal(), "LOADGLOBAL 应成功")
        return Session(env, io, fs)
    }

    private fun slotOf(env: SemTestEnv): SaveUnclaimedPassthrough =
        assertNotNull(SaveUnclaimedPassthrough.of(env.store), "LOADGLOBAL 成功路径应装配切片槽")

    /** 复位后回读：未知顶层键与未知 EM 容器都不许再出现在 global.sav 里。 */
    private fun assertGlobalUnknownsGone(session: Session) {
        val reread = SaveFormat.read(session.io.files.getValue(GLOBAL_PATH))
        assertTrue(
            reread.variables.none { it.key == UNKNOWN_GLOBAL_TOP },
            "RESETGLOBAL 之后 SAVEGLOBAL 不许再写回重置前的未知顶层键",
        )
        assertTrue(
            reread.emData.none { it.key == UNKNOWN_GLOBAL_MAP || it.key == UNKNOWN_GLOBAL_DT },
            "RESETGLOBAL 之后 SAVEGLOBAL 不许再写回重置前的未知 EM 容器",
        )
        val global = reread.variables.single { it.key == "GLOBAL" } as SaveEntry.IntArray1
        assertEquals(0L, global.values[0], "既有 SetDefaultGlobalValue 语义不变：GLOBAL 整表归零")
    }

    // ------------------------------------------------------------------
    // 1. 缺陷面与修复面（对象树入口）
    // ------------------------------------------------------------------

    @Test
    fun `RESETGLOBAL 之后 SAVEGLOBAL 不再写回重置前的顶层未知键与未知 EM 容器`() {
        val session = openGlobal()
        val slot = slotOf(session.env)
        assertEquals(
            listOf(UNKNOWN_GLOBAL_TOP),
            slot.globalSlices(session.env.store).map { it.key },
            "装载后 global 顶层槽持有未知键",
        )
        assertEquals(
            listOf(UNKNOWN_GLOBAL_MAP, UNKNOWN_GLOBAL_DT),
            slot.emSlices(session.env.store, global = true).map { it.key },
            "装载后 EM global 槽持有未知容器",
        )

        // 对照：未 RESETGLOBAL 时同一份槽确实会把未知键/容器写回（缺陷面真实存在）。
        session.fs.saveGlobal()
        val before = SaveFormat.read(session.io.files.getValue(GLOBAL_PATH))
        assertTrue(before.variables.any { it.key == UNKNOWN_GLOBAL_TOP }, "对照组：未复位时未知顶层键会回写")
        assertTrue(
            before.emData.any { it.key == UNKNOWN_GLOBAL_MAP || it.key == UNKNOWN_GLOBAL_DT },
            "对照组：未复位时未知 EM 容器会回写",
        )

        session.env.run(instr(FunctionCode.RESETGLOBAL))

        assertTrue(slot.globalSlices(session.env.store).isEmpty(), "RESETGLOBAL 应清空 global 顶层槽")
        assertTrue(
            slot.emSlices(session.env.store, global = true).isEmpty(),
            "RESETGLOBAL 应清空 EM global 槽",
        )
        session.fs.saveGlobal()
        assertGlobalUnknownsGone(session)
    }

    @Test
    fun `扁平直读分支同样清空 global 槽`() {
        val session = openGlobal()
        session.env.runFlat(instr(FunctionCode.RESETGLOBAL))

        val slot = slotOf(session.env)
        assertTrue(
            slot.globalSlices(session.env.store).isEmpty(),
            "扁平直读入口的 RESETGLOBAL 同样清空 global 顶层槽",
        )
        assertTrue(
            slot.emSlices(session.env.store, global = true).isEmpty(),
            "扁平直读入口的 RESETGLOBAL 同样清空 EM global 槽",
        )
        session.fs.saveGlobal()
        assertGlobalUnknownsGone(session)
    }

    // ------------------------------------------------------------------
    // 2. normal 槽对照：生命周期不归 RESETGLOBAL 管
    // ------------------------------------------------------------------

    @Test
    fun `RESETGLOBAL 不清 normal 槽且普通档仍回写自己的未知键`() {
        val env = SemTestEnv()
        val io = MemorySaveIo()
        io.files[SLOT_PATH] = normalFixture()
        io.files[GLOBAL_PATH] = globalFixture()
        val cfg = config()
        val emSets = EmSaveSets.EMPTY
        SaveWiring.install(env.store, cfg, 20, emSets)
        val fs = FileSystemOps(env.store, io, cfg, emSets)
        fs.loadData(0)
        assertTrue(fs.loadGlobal(), "LOADGLOBAL 应成功")
        val slot = slotOf(env)

        assertEquals(
            listOf(UNKNOWN_NORMAL_TOP),
            slot.normalSlices(env.store).map { it.key },
            "装载后 normal 顶层槽持有未知键",
        )
        assertEquals(
            listOf(UNKNOWN_NORMAL_MAP),
            slot.emSlices(env.store, global = false).map { it.key },
            "装载后 EM normal 槽持有未知容器",
        )

        env.run(instr(FunctionCode.RESETGLOBAL))

        assertEquals(
            listOf(UNKNOWN_NORMAL_TOP),
            slot.normalSlices(env.store).map { it.key },
            "RESETGLOBAL 不许清 normal 顶层槽（生命周期归 ResetData / LOADDATA）",
        )
        assertEquals(
            listOf(UNKNOWN_NORMAL_MAP),
            slot.emSlices(env.store, global = false).map { it.key },
            "RESETGLOBAL 不许清 EM normal 槽",
        )
        assertTrue(slot.globalSlices(env.store).isEmpty(), "同一会话的 global 槽仍应被清")
        assertTrue(slot.emSlices(env.store, global = true).isEmpty(), "同一会话的 EM global 槽仍应被清")

        assertTrue(fs.saveData(0, "normal2"), "SAVEDATA 应成功")
        val reread = SaveFormat.read(io.files.getValue(SLOT_PATH))
        assertTrue(
            reread.variables.any { it.key == UNKNOWN_NORMAL_TOP },
            "normal 域的未知顶层键照旧回写",
        )
        assertTrue(
            reread.emData.any { it.key == UNKNOWN_NORMAL_MAP },
            "normal 域的未知 EM 容器照旧回写",
        )
    }

    // ------------------------------------------------------------------
    // 3. 零变化：未装配槽 / 空槽
    // ------------------------------------------------------------------

    @Test
    fun `未装配槽时 RESETGLOBAL 零变化且不凭空建槽`() {
        val env = SemTestEnv()
        env.store.writeInt("GLOBAL", longArrayOf(5), 7)
        assertNull(SaveUnclaimedPassthrough.of(env.store), "装置起点没有槽")

        env.run(instr(FunctionCode.RESETGLOBAL))

        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(5)), "既有变量复位逐字不变")
        assertNull(
            SaveUnclaimedPassthrough.of(env.store),
            "未装配槽不得因 RESETGLOBAL 被创建（零变化）",
        )
    }

    @Test
    fun `空槽上 RESETGLOBAL 不抛错且既有复位照常`() {
        val env = SemTestEnv()
        val slot = SaveUnclaimedPassthrough.install(env.store)
        env.store.writeInt("GLOBAL", longArrayOf(5), 7)
        env.store.writeInt("DAY", longArrayOf(0), 5)

        env.run(instr(FunctionCode.RESETGLOBAL))

        assertTrue(slot.globalSlices(env.store).isEmpty())
        assertTrue(slot.emSlices(env.store, global = true).isEmpty())
        assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(5)))
        assertEquals(5L, env.store.readInt("DAY", longArrayOf(0)), "RESETGLOBAL 仍不外溢到 RESETDATA 的复位面")
    }

    // ------------------------------------------------------------------
    // 4. 失败面：清槽异常只留痕，不翻转成功语义
    // ------------------------------------------------------------------

    @Test
    fun `清槽异常只留痕不翻转 RESETGLOBAL 成功语义`() {
        val env = SemTestEnv()
        env.store.writeInt("GLOBAL", longArrayOf(0), 3)
        // 槽位放错误类型：泛型擦除使 of() 不报错，clearGlobal() 调用点抛 ClassCastException。
        env.store.aux.slot<String>(SaveUnclaimedPassthrough.AUX_SLOT) { "not-a-passthrough" }
        SaveDiagnostics.clear()
        try {
            val result = env.run(instr(FunctionCode.RESETGLOBAL))

            assertSame(ExecResult.Normal, result, "清槽失败不得改变 RESETGLOBAL 的成功返回")
            assertEquals(0L, env.store.readInt("GLOBAL", longArrayOf(0)), "变量复位不受清槽失败影响")
            assertTrue(
                SaveDiagnostics.snapshot().any {
                    it.contains("RESETGLOBAL") && it.contains("ClassCastException")
                },
                "清槽失败须留痕（SaveDiagnostics），实际：${SaveDiagnostics.snapshot()}",
            )
        } finally {
            SaveDiagnostics.clear()
        }
    }

    private companion object {
        /** 生产配置 `UseSaveFolder=true` 下的槽位路径（与 SaveHostTest 同口径）。 */
        const val SLOT_PATH = "sav/save00.sav"
        const val GLOBAL_PATH = "sav/global.sav"

        /** ASCII 键/容器名，便于在 payload 里按 UTF-16LE 反查（本文件走 SaveFormat.read 断言，足够）。 */
        const val UNKNOWN_GLOBAL_TOP = "UNCLAIMED_GLOBAL_TOP_L32"
        const val UNKNOWN_GLOBAL_MAP = "UNCLAIMED_GLOBAL_MAP_L32"
        const val UNKNOWN_GLOBAL_DT = "UNCLAIMED_GLOBAL_DT_L32"
        const val UNKNOWN_NORMAL_TOP = "UNCLAIMED_NORMAL_TOP_L32"
        const val UNKNOWN_NORMAL_MAP = "UNCLAIMED_NORMAL_MAP_L32"
    }
}
