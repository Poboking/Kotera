package com.eratw.port.core.data.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `LOADCHARA` **写回段**（`CharaFileIO.writeBack`，原 `load` 内联 `applyTo`，2026-11-18
 * stab-b 修复批抽出）的收敛口径验收。
 *
 * 三条判据（契约 stab-b 修复批）：
 * 1. 写回段抛**非 CodeEE** 异常 → 收敛成 [CodeEE]（官方文案 + 根因进因果链）上抛，
 *    让 `ProcessRuntime.executeCore` 能盖章、顶层红字接得住 —— 反向证明：把收敛段
 *    （catch Throwable 一支）去掉，POISON 直抛 [ArrayIndexOutOfBoundsException]，第 1 条转红；
 * 2. 写回段抛 **CodeEE**（角色行预算闸）→ 原样透传，不二次包装（包一层通用文案会
 *    丢掉「超了多少」的具体原因）；
 * 3. 抽出写回段后 `load` 成功路径不变（SAVECHARA → LOADCHARA 追加）。
 *
 * 触发器为什么用「直构 SaveData + 短 values 的 IntArray2」：`SaveFormat.read` 侧
 * 解码时维度与 values 个数一致性已判死，字节级坏档到不了 applyTo 的写回分支；
 * applyDefined 的 IntArray2 分支按 `e.values[i * e.dim1 + j]` 取值、不校验 values 尺寸
 * （SaveData.kt 的 IntArray2 分支），直构一条 `dim0=1, dim1=1, values=空` 的条目即得
 * 确定性 [ArrayIndexOutOfBoundsException] —— 真实抛源（分配回归 / needInit 尾段回归）
 * 与它是同一收敛段。
 */
class CharaFileIOWriteBackTest {

    /** 官方文案单源：`Lang.Error.UnexpectedErrorInLoaddata`（lang/emuera.zhs.xml:704）。 */
    private val unexpected: String = FileSystemOps.MES_LOAD_UNEXPECTED

    private fun dim(body: String) =
        // CHARADATA + SAVEDATA 的声明只认二进制档口径（文本档拒 CHARADATA 参与存档），
        // 写回面（applyTo）不读这一位，但声明闸在 DimParser/注册时就判 —— 与 SaveHostTest 同参。
        DimParser.parse("#DIM", body, isPrivate = false, DimParseConfig(systemSaveInBinary = true))

    /** 2D 角色用户变量 + 一条「声明 1×1、values 空」的毒条目（详见类 KDoc）。 */
    private fun poisonData(): SaveData = SaveData(
        fileType = SaveFileKind.CHARVAR,
        uniqueCode = 4452L,
        scriptVersion = 0L,
        text = "stab",
        characters = listOf(
            listOf(SaveEntry.IntArray2(key = "CU2", dim0 = 1, dim1 = 1, values = LongArray(0))),
        ),
    )

    /**
     * 判据 1（收敛）：非 CodeEE → [CodeEE] + 官方文案 + 根因进因果链。
     *
     * 「带语句位置信息」按既有管线走：本层抛出的 [CodeEE] 无位置字段
     * （funcName == null / line < 0 / pc < 0），`LOADCHARA` 语句执行体里穿出时由
     * `ProcessRuntime.stampPosition`（ProcessRuntime.kt:3888）按当前帧补齐 —— 与
     * `FileSystemOps.loadData` 写回段同形状（proc 侧盖章已有 `ExprErrorStampTest` 钉住，
     * 不在本文件重复造 VM 夹具）。
     */
    @Test
    fun `写回段非CodeEE异常收敛为CodeEE且根因进因果链`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA SAVEDATA CU2, 2, 2"))
        val e = assertFailsWith<CodeEE> { writeBack(poisonData(), s) }
        assertEquals(unexpected, e.message, "文案 = loadData 写回段同一条官方文案")
        val cause = assertNotNull(e.cause, "根因必须经 initCause 进因果链（可诊断性补账，不吞）")
        assertTrue(
            cause is ArrayIndexOutOfBoundsException,
            "毒条目的确定性别名（IntArray2 短 values），实为 ${cause.javaClass.name}",
        )
    }

    /**
     * 判据 1 反向证明：收敛是 catch 里那一段 —— 去掉它 POISON 的
     * [ArrayIndexOutOfBoundsException] 会原样穿出，本条断言的 [CodeEE] 类型即转红。
     */
    @Test
    fun `反向证明收敛段存在——非CodeEE不再裸穿`() {
        val s = VariableStore()
        s.declareUserVar(dim("CHARADATA SAVEDATA CU2, 2, 2"))
        // 直调 applyTo 对照：毒条目在无收敛段时抛的就是 AIOOBE（收敛段的输入形状自证）。
        val raw = assertFailsWith<ArrayIndexOutOfBoundsException> {
            poisonData().applyTo(s, restoreCharacters = true, setLastLoad = false)
        }
        assertNull(raw.cause, "裸 applyTo 无包装（对照组）")
        // 收敛段把同一输入转成 CodeEE —— 两组断言合起来钉住「收敛只发生在 writeBack 一层」。
        val e = assertFailsWith<CodeEE> { writeBack(poisonData(), s) }
        assertEquals(unexpected, e.message)
    }

    /**
     * 判据 2（透传）：applyTo 自己抛的 [CodeEE]（角色行预算闸，SaveData.kt:319）原样
     * 穿出 —— 文案保持预算闸原文、不带 initCause（被二次包装过必有 cause）。
     */
    @Test
    fun `写回段CodeEE原样透传不二次包装`() {
        val s = VariableStore()
        // 行数取预算闸的触发行数：MAX_RESTORE_CHARACTER_BYTES(256 MiB，SaveData.kt:19)
        // / rowBytes + 2 —— 恒严格超过 `rows > MAX / rowBytes` 的判式，且闸在 addBare
        // 之前抛，测试不真分配大表。
        val rowBytes = s.characters.rowBytes()
        assertTrue(rowBytes > 0, "空店角色列恒非空（夹具自检）")
        val rows = ((256L * 1024 * 1024) / rowBytes + 2).toInt()
        val data = SaveData(
            fileType = SaveFileKind.CHARVAR,
            uniqueCode = 4452L,
            scriptVersion = 0L,
            text = "stab",
            characters = List(rows) { emptyList() },
        )
        val e = assertFailsWith<CodeEE> { writeBack(data, s) }
        assertTrue(
            "可还原上限" in e.message!!,
            "应是预算闸原文直传（不二次包装成通用文案），实为：${e.message}",
        )
        assertNull(e.cause, "透传的预算 CodeEE 不该被 initCause 重包")
        assertEquals(0, s.characters.count, "预算闸在 addBare 之前抛，角色表不增长")
    }

    /** 判据 3：抽出写回段后 `load` 成功路径回归（SAVECHARA 编码 → LOADCHARA 追加）。 */
    @Test
    fun `load成功路径回归——写回段抽取后追加行为不变`() {
        val source = VariableStore()
        source.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
        source.addCharacter(1L)
        val bytes = CharaFileIO.encode(source, listOf(0L), "stab")
        val target = VariableStore()
        assertTrue(CharaFileIO.load(bytes, target), "正文可解的档照常追加（原 :2315 路径）")
        assertEquals(1, target.characters.count, "角色块写回角色表")
    }
}
