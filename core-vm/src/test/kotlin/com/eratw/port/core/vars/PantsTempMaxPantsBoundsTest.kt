package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 上报「变量 PANTS_TEMP 的第 2 维下标 35 越界（长度 34）@ PANTS_GETTING 行81」的引擎侧语义钉死
 * （lane bug-pants；上报 2026-11-09 18:21:26，0.2.39，OnePlus PKR110）。
 *
 * ## 判定：语料侧越界，引擎无偏差（官方 4.980 / 4.981 同形），**不夹下标**
 *
 * 语料（4.981）：`DIM.ERH:221` 声明 `#DIM CONST MAXPANTS = 34`（4.980 `DIM.ERH:218` 同值）；
 * `DIM.ERH:411` 声明 `#DIM CHARADATA SAVEDATA PANTS_TEMP, MAXPANTS` ⇒ 第 2 维长度 34。
 * 但胖次对象表已被「游泳补丁」扩到 id 35：`CLOTHES_下半身下着_ずらし可能.ERB:1168` 是
 * `@下半身内衣_ずらし可能34`（泳裤）、`:1191` 是 `@下半身内衣_ずらし可能35`（泳装下装，
 * 名字取 `UNIQUE_UNDERWARE_SWIMMING_BOTTOM`），`MAXPANTS` 没有跟着抬。
 * `衣装セット.ERB:2984-2986` 的泳装套把这个 35 写进 `下半身内衣２` 槽；`PANTS.ERB:63-64`
 * 的 `PANTS_GET` 把该槽原样当 `種類` 传给 `PANTS_GETTING`，于是
 * `PANTS.ERB:82` 的 `PANTS_TEMP:ARG:種類 += 1` 越界。
 *
 * 引擎（本仓）：装载期常量折叠把 `MAXPANTS` 折成 34（spec:
 * `UserDefinedVariableData.cs:300-343` —— 尺寸段 `ReduceIntegerTerm(...).Restructure(null)`
 * 读 ConstantData 的常量，本仓对应 [DimParser] 的 `DimParseConfig.intConstants` 注入面）；
 * 访问第 2 个实参时按声明长度判界并抛 CodeEE（spec: `CharaVariableToken.cs:62-64` 对
 * 单元素维喂实参 `"2"`、`VariableData.cs:81-100` 同通道），与原版逐字同形。
 * 本层**没有也不许有**夹到范围内或白名单——那会让「错误不可能发生」变成「错误不再报」。
 *
 * ## 文案口径
 * 当前 main 抛官方 `OoRCharaVarArg`（`Lang.cs:1622` / reference zhs 表 `:360`）。上报正文里的
 * 「变量 X 的第 N 维下标 M 越界（长度 L）」是 2026-11-04 lane errtext 已退役的自造串，
 * 不是当前 main 的形态；本测试按官方串断言，防回潮。
 */
class PantsTempMaxPantsBoundsTest {

    /** 语料 4.981 `DIM.ERH:221` 的常量值（4.980 `DIM.ERH:218` 同值）。 */
    private val maxPants = 34L

    /** ERH 已注册常量表的最小注入面（`DimParseConfig.intConstants` = ConstantData 折叠表）。 */
    private fun foldConfig(value: Long = maxPants): DimParseConfig =
        DimParseConfig(systemSaveInBinary = true, intConstants = mapOf("MAXPANTS" to value))

    private fun pantsTempSpec(cfg: DimParseConfig = foldConfig()): DimSpec =
        DimParser.parse("#DIM", "CHARADATA SAVEDATA PANTS_TEMP, MAXPANTS", false, cfg)

    /** 2 行原生角色（物理行 0/1）+ 语料原形的 PANTS_TEMP 声明。 */
    private fun store(): VariableStore {
        val s = VariableStore()
        repeat(2) { i ->
            val no = (i + 1).toLong()
            s.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            s.addCharacter(no)
        }
        s.declareUserVar(pantsTempSpec())
        return s
    }

    // ------------------------------------------------------------------
    // 1. 装载相位：尺寸来自常量折叠，不是写死 34
    // ------------------------------------------------------------------

    @Test
    fun `MAXPANTS 常量折叠成 PANTS_TEMP 第二维长度 34`() {
        val spec = pantsTempSpec()
        assertContentEquals(
            intArrayOf(34),
            spec.dims,
            "DIM.ERH:221 的 MAXPANTS=34 折进 DIM.ERH:411 的尺寸段（语料不写 34 字面量）",
        )
        assertEquals(false, spec.isConst, "PANTS_TEMP 不是 CONST")
        assertEquals(true, spec.isCharaData, "CHARADATA：角色位是第一维，MAXPANTS 是第二维")
        assertEquals(true, spec.isSavedata, "SAVEDATA：进存档，但不改声明尺寸")
    }

    @Test
    fun `常量表换 36 尺寸跟着变 证明走的是常量表`() {
        val spec = pantsTempSpec(foldConfig(36L))
        assertContentEquals(intArrayOf(36), spec.dims, "折错即红：尺寸必须跟随常量表，禁止硬编码")
    }

    @Test
    fun `常量表缺席时尺寸折不出来 按原版报错而非回退默认值`() {
        assertFailsWith<CodeEE>("未注册常量名不得折成任何默认长度") {
            pantsTempSpec(DimParseConfig(systemSaveInBinary = true))
        }
    }

    // ------------------------------------------------------------------
    // 2. 运行相位：第 2 个实参（種類）按声明长度判界，33 合法 / 34、35 越界
    // ------------------------------------------------------------------

    @Test
    fun `维度顺序 角色位加种类两维`() {
        val s = VariableStore()
        val v = s.declareUserVar(pantsTempSpec())
        assertEquals(2, v.def.argCount, "CHARADATA + 1 个元素维 = 2 个下标（角色位在前）")
        assertEquals(34, v.def.dims[0], "第二维（种类）由 MAXPANTS 折出")
        repeat(2) { i ->
            val no = (i + 1).toLong()
            s.registerTemplate(CharacterTemplate(no = no, name = "角色", csvNo = no))
            s.addCharacter(no)
        }
        s.writeInt("PANTS_TEMP", longArrayOf(0, 33), 7L)
        assertEquals(7L, s.readInt("PANTS_TEMP", longArrayOf(0, 33)))
    }

    @Test
    fun `种类 33 是合法上界`() {
        val s = store()
        s.writeInt("PANTS_TEMP", longArrayOf(1, 33), 5L)
        assertEquals(5L, s.readInt("PANTS_TEMP", longArrayOf(1, 33)))
        assertEquals(0L, s.readInt("PANTS_TEMP", longArrayOf(0, 33)), "相邻角色行不受影响")
    }

    @Test
    fun `种类 34 与 35 一律越界 抛官方 OoRCharaVarArg 且不夹下标`() {
        val s = store()
        for (kind in longArrayOf(34L, 35L)) {
            val e = assertFailsWith<CodeEE>("MAXPANTS=34 ⇒ 合法种类 0..33，$kind 必须越界") {
                s.writeInt("PANTS_TEMP", longArrayOf(1, kind), 1L)
            }
            assertEquals(
                "角色数组变量\"PANTS_TEMP\"的第2个变量($kind)超出角色登记编号的最大范围",
                e.message,
                "官方 OoRCharaVarArg（CharaVariableToken.cs:64 喂实参 2）；退役自造串「第 N 维下标…（长度 L）」不许回潮",
            )
        }
        assertEquals(0L, s.readInt("PANTS_TEMP", longArrayOf(1, 33)), "越界写不得留下半写态")
    }

    @Test
    fun `负数种类同走官方 OoR 不静默取 0`() {
        val s = store()
        val e = assertFailsWith<CodeEE> {
            s.writeInt("PANTS_TEMP", longArrayOf(1, -1L), 1L)
        }
        assertEquals(
            "角色数组变量\"PANTS_TEMP\"的第2个变量(-1)超出角色登记编号的最大范围",
            e.message,
        )
    }

    @Test
    fun `每行角色各自 34 格 声明不随角色数膨胀`() {
        val s = store()
        assertEquals(2, s.characters.count)
        for (row in longArrayOf(0L, 1L)) {
            s.writeInt("PANTS_TEMP", longArrayOf(row, 33), row + 1)
            assertFailsWith<CodeEE> { s.writeInt("PANTS_TEMP", longArrayOf(row, 34), 9L) }
        }
    }
}
