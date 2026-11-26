package com.eratw.port.core.vars

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VariableWriter
import com.eratw.port.core.sem.TestEvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * QB-A1 回归锁：4.981 稀疏 1 维背板**覆盖矩阵补口**（[SparseArrayOorTest] 未钉到的入口）。
 *
 * 范围契约 = `<internal-workdir>/CONTRACT-QB-A1.md` 第三步：对 lane-C（`c45357a86` + Unary 通道
 * `660e3ef61`）落了稀疏补径、但尚无用例钉住的存取入口补锁；每条锁的 KDoc 写明反向证明
 * （撤掉补径该锁为什么立红），不动既有断言。权威口径：specs 下的 yaml `sparse_1d_backplane`
 * 与 `specs/verify-notes.md` 2026-11-17 稀疏条。
 *
 * 稀疏补径的判定核心 = `VariableStore.sparseDimLength`（VariableStore.kt:1247）：只有
 * `G_INT1`/`G_STR1`/`C_INT1`/`C_STR1` 且恰好 1 维才容忍动态越界；本文件按「背板种类」
 * 把四象限与其余种类逐一钉死：
 * - C_INT1（CFLAG）已有锁；C_STR1（CSTR）⇒ 本文件 `角色 1 维字符串…`；
 * - CSV_CONST（ABLNAME）与 USER（`#DIM`）⇒ 严格（PC 实测照抛，c45357a86 提交信息判词段）；
 * - 浮点三路判 N/A 不补（verify-notes 2026-11-17 条「浮点三路」段）⇒ 本文件 `浮点通道…`；
 * - 缺省钩子回落严格 ⇒ 本文件 `Eval.kt 读侧/写侧…`（Eval.kt:98/:101/:104/:219/:223 缺省体）；
 * - 跑飞护栏（SPARSE_OVERFLOW_LIMIT）⇒ 本文件 `稀疏覆盖层跑飞护栏…`；
 * - B5 半死闸在补径上不豁免 ⇒ 本文件 `半死闸在稀疏补径上仍然生效…`。
 */
class SparseArrayOorCoverageTest {

    /** 运行期求值的下标表达式：把 [value] 写进 `DAY:0` 再拿它当下标（非字面量 ⇒ 动态形态）。 */
    private fun dynamicIndex(store: VariableStore, value: Long): Expr {
        store.writeInt("DAY", longArrayOf(0), value)
        return VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)
    }

    private fun intRef(name: String, vararg idx: Expr) =
        VarRef(name, idx.toList(), operandType = OperandType.INT)

    private fun strRef(name: String, vararg idx: Expr) =
        VarRef(name, idx.toList(), operandType = OperandType.STR)

    private fun floatRef(name: String, vararg idx: Expr) =
        VarRef(name, idx.toList(), operandType = OperandType.FLOAT)

    // ------------------------------------------------------------------
    // C_STR1 象限（角色 1 维字符串）
    // ------------------------------------------------------------------

    /**
     * **反向证明**：撤掉 `c45357a86` 的字符串补径（`readSparseStrOor`/`writeSparseStrOor`
     * + `sparseStrOverflow` 覆盖表），CSTR 的动态越界读/写会走严格通道抛
     * `角色数组变量"CSTR"的…超出数组范围`，本用例两条正向断言立红。
     * 依据与 G_STR1/C_INT1 同源：`CharacterData.cs:28` 角色 1 维字符串同是稀疏背板。
     */
    @Test
    fun `角色 1 维字符串 CSTR 动态越界读空串写读回`() {
        val s = VariableStore()
        s.addPseudoCharacter()
        val ctx = TestEvalContext(s)
        val row = IntLiteral(0)
        assertEquals("", strRef("CSTR", row, dynamicIndex(s, -1)).getStrValue(ctx), "越界读 = 空串缺省")
        assertEquals("", strRef("CSTR", row, dynamicIndex(s, 99999)).getStrValue(ctx))
        strRef("CSTR", row, dynamicIndex(s, -1)).setStrValue(ctx, "ab")
        assertEquals("ab", strRef("CSTR", row, dynamicIndex(s, -1)).getStrValue(ctx), "越界写落覆盖层并读回")
        // 覆盖层按键隔离：另一个越界下标仍是缺省。
        assertEquals("", strRef("CSTR", row, dynamicIndex(s, -2)).getStrValue(ctx))
    }

    /** 常量下标在任何背板种类上都照旧硬抛（VariableTerm.cs:316-347 解析期 CheckElement）。 */
    @Test
    fun `角色 1 维字符串常量下标越界仍抛`() {
        val s = VariableStore()
        s.addPseudoCharacter()
        val ctx = TestEvalContext(s)
        val e = assertFailsWith<CodeEE> { strRef("CSTR", IntLiteral(0), IntLiteral(-1)).getStrValue(ctx) }
        assertTrue(e.message!!.startsWith("角色数组变量\"CSTR\""), e.message)
        assertFailsWith<CodeEE> { strRef("CSTR", IntLiteral(0), IntLiteral(-1)).setStrValue(ctx, "x") }
    }

    // ------------------------------------------------------------------
    // B5 半死闸 × 稀疏补径（交互面，DelCharaOverloadTest 单元 6 的夹具）
    // ------------------------------------------------------------------

    /**
     * 稀疏补径的前置校验 `requireSparseCharaRow`（VariableStore.kt:1282）显式保留了
     * B5 半死闸（`requireSystemRowAlive`）：半死行的动态越界访问必须炸在闸上（裸 NPE、
     * 带半死字样），**不许**被稀疏缺省值盖过去（否则删角色失败路径的释放面语义被
     * 「越界读 = 空串」静默吞掉）。活行对照 = 正常走补径返空串。
     *
     * **反向证明**：撤掉 `c45357a86`，严格通道在 `checkFlat` 的角色 1 维元素判定就抛
     * OoRCharaVarArg，到不了补径——本用例的半死行断言立红（抛的是 CodeEE 而非半死 NPE）。
     */
    @Test
    fun `半死闸在稀疏补径上仍然生效`() {
        val s = VariableStore()
        s.registerTemplate(CharacterTemplate(no = 10, name = "角色10", csvNo = 10))
        s.registerTemplate(CharacterTemplate(no = 20, name = "角色20", csvNo = 20))
        s.registerTemplate(CharacterTemplate(no = 30, name = "角色30", csvNo = 30))
        s.addCharacter(10)
        s.addCharacter(20)
        s.addCharacter(30)
        // 复刻 DelCharaOverloadTest 单元 6：删除序列带越界元素 ⇒ 校验循环中途抛，
        // 已校验的行（0/1）被 Dispose 置空但仍在表内 = 半死行（CHARANUM 不变 = 3）。
        val e = assertFailsWith<CodeEE> { s.deleteCharacters(longArrayOf(0, 1, 9)) }
        assertEquals("试图删除不存在的注册角色(System.Int64[])", e.message)
        assertEquals(3L, s.readInt("CHARANUM", longArrayOf()))

        val ctx = TestEvalContext(s)
        // 半死行（0 号）上动态越界读：补径前置的角色行闸先炸（不是返空串）。
        val npe = assertFailsWith<NullPointerException> {
            strRef("CSTR", IntLiteral(0), dynamicIndex(s, -1)).getStrValue(ctx)
        }
        assertTrue(npe.message!!.contains("半死"), npe.message)
        // 写侧同闸（writeSparseStrOor 前置同走 requireSparseCharaRow）。
        val npeW = assertFailsWith<NullPointerException> {
            strRef("CSTR", IntLiteral(0), dynamicIndex(s, -1)).setStrValue(ctx, "x")
        }
        assertTrue(npeW.message!!.contains("半死"), npeW.message)
        // 活行（2 号）对照 = 正常走补径返空串。
        assertEquals("", strRef("CSTR", IntLiteral(2), dynamicIndex(s, -1)).getStrValue(ctx))
    }

    // ------------------------------------------------------------------
    // CSV 常量表（CSV_CONST）与用户 #DIM（USER）：不落稀疏，动态越界照抛
    // ------------------------------------------------------------------

    /**
     * PC 实测判词（c45357a86 提交信息）：`ABLNAME:X`（CSV 常量表）动态 -1 ⇒ **照抛**。
     * 4.981 的字典背板只覆盖 `VariableData.cs:44,47,50` 的 1 维 DataIntegerArray/
     * DataStringArray 与 `CharacterData.cs:25,28`；CSV 名字表（VariableData.cs:3112-3139）
     * 不在其列。移植侧落点 = `sparseDimLength` 的 else -1（CSV_CONST 不在四个白名单
     * storage 里）⇒ 补径把访问原样退回严格通道。
     *
     * **反向证明**：本锁钉的是「CSV_CONST 不落稀疏」这一**口径**——撤掉 `c45357a86`
     * 本锁不红（严格通道本就抛）；若有人把 CSV_CONST 加进 `sparseDimLength` 的白名单，
     * 动态越界读会静默返空串/0，本锁立红。
     */
    @Test
    fun `CSV 常量表动态越界照抛`() {
        val s = VariableStore()
        val ctx = TestEvalContext(s)
        // ABLNAME 是 CSV 常量**名字**表（VarType.STR，VariableRegistry.buildCsvConstants）；
        // 负下标与远超 CSV 行数的下标在任何注册尺寸下都越界。断言的是「动态读不静默返
        // 缺省」与 OoRVarArg 文案形态，不是具体尺寸。
        val e = assertFailsWith<CodeEE> { strRef("ABLNAME", dynamicIndex(s, -1)).getStrValue(ctx) }
        assertTrue(e.message!!.startsWith("数组变量\"ABLNAME\"的第1参数"), e.message)
        assertFailsWith<CodeEE> { strRef("ABLNAME", dynamicIndex(s, 999_999)).getStrValue(ctx) }
    }

    /**
     * PC 实测判词（c45357a86 提交信息）：`#DIM MYV,10` 动态 -1 ⇒ **照抛**。用户变量
     * 在 fork 里也没换字典背板（`sparseDimLength` 对 USER 恒 -1）。
     *
     * **反向证明**：同上——撤 `c45357a86` 本锁不红；若把 USER 加进白名单，读/写/`+=`
     * 三条正向断言（期望抛）全部立红。
     */
    @Test
    fun `用户 DIM 1 维动态越界照抛`() {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse("#DIM", "MYV, 10", false))
        val ctx = TestEvalContext(s)
        val idx = dynamicIndex(s, -1)
        assertFailsWith<CodeEE> { intRef("MYV", idx).getIntValue(ctx) }
        assertFailsWith<CodeEE> { intRef("MYV", idx).setIntValue(ctx, 7) }
        assertFailsWith<CodeEE> { intRef("MYV", idx).addIntValue(ctx, 1) }
        // 上界外同形。
        assertFailsWith<CodeEE> { intRef("MYV", dynamicIndex(s, 99999)).getIntValue(ctx) }
    }

    // ------------------------------------------------------------------
    // 浮点三路：判 N/A 不补（verify-notes 2026-11-17 条「浮点三路」段）
    // ------------------------------------------------------------------

    /**
     * 4.981 没有浮点稀疏背板：角色浮点标量/数组计数为 0（`VariableCode.cs:138/:167`
     * `__COUNT_CHARACTER_FLOAT*__ = 0x00` ⇒ `CharacterData.cs:38/:41` 皆 Empty、
     * `:55-59` 的 SparseArray 填充循环空转）；全局浮点只有 LOCALF/ARGF 且是密集
     * `double[][]`（`VariableData.cs:29`）。给浮点补稀疏钩子是造语义（verify-notes 判词），
     * 本锁钉住「浮点三路（getFloatValue/setFloatValue/addFloatValue）无 catch、动态越界
     * 照抛」——`VarRef.getFloatValue`/`setFloatValue`/`addFloatValue`（Expr.kt:245/:260/:278）
     * 的代码路径里根本不存在稀疏 catch。
     *
     * **反向证明**：撤 `c45357a86` 本锁不红；若有人给浮点通道也接补径（造语义），
     * 三条「期望抛」断言立红。
     */
    @Test
    fun `浮点通道动态越界照抛 无稀疏背板`() {
        val s = VariableStore()
        s.declareUserVar(
            DimSpec(name = "MYF", isStr = false, dims = intArrayOf(10), isFloat = true),
        )
        val ctx = TestEvalContext(s)
        val idx = dynamicIndex(s, -1)
        assertFailsWith<CodeEE> { floatRef("MYF", idx).getFloatValue(ctx) }
        assertFailsWith<CodeEE> { floatRef("MYF", idx).setFloatValue(ctx, 1.5) }
        assertFailsWith<CodeEE> { floatRef("MYF", idx).addFloatValue(ctx, 1.5) }
    }

    // ------------------------------------------------------------------
    // Eval.kt 五钩子的缺省实现：回落严格通道，不静默兜底
    // ------------------------------------------------------------------

    /**
     * 最小假体：只实现严格通道、**不覆写**任何稀疏钩子（`SparseOorVariableReader`
     * / `SparseOorVariableWriter` 的缺省体）。下标载体 `DAY:0` 走假体自己的 map。
     */
    private class HookDefaultReader : VariableReader, VariableWriter {
        var day: Long = 0

        private fun oorText(name: String, indices: LongArray) =
            "数组变量\"$name\"的第${indices.size}参数(${indices.lastOrNull()})超出数组范围"

        override fun isConst(name: String): Boolean = false

        override fun readInt(name: String, indices: LongArray): Long =
            if (name == "DAY" && indices.size == 1) day else throw CodeEE(oorText(name, indices))

        override fun readStr(name: String, indices: LongArray): String =
            if (name == "DAY" && indices.size == 1) "" else throw CodeEE(oorText(name, indices))

        override fun plusInt(name: String, indices: LongArray, delta: Long): Long =
            throw CodeEE(oorText(name, indices))

        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long =
            throw com.eratw.port.core.expr.ExprEvalException("未知关键字 $keyword")

        override fun writeInt(name: String, indices: LongArray, value: Long) {
            if (name == "DAY" && indices.size == 1) {
                day = value
                return
            }
            throw CodeEE(oorText(name, indices))
        }

        override fun writeStr(name: String, indices: LongArray, value: String) =
            throw CodeEE(oorText(name, indices))
    }

    /**
     * `Eval.kt:98/:101/:104` 读侧三钩子的缺省实现 = 原样转调严格通道（照抛）。
     * KDoc 承诺（Eval.kt:135 一带）：「缺省实现回落严格通道 ⇒ 测试假体零改动，越界仍按
     * 原样抛，不会静默兜底」——本锁把这个承诺钉死：假体不覆写钩子时，动态越界读/字符串
     * 读/`+=` 必须**抛**而不是返缺省值。
     *
     * **反向证明**：撤 `c45357a86` 本锁不红（钩子随补径一并消失，路径一样抛）；本锁红
     * 的唯一方式 = 有人把缺省实现改成吞错返默认值（静默兜底）——那正是铁律禁止的形态。
     */
    @Test
    fun `Eval 读侧钩子缺省实现回落严格通道`() {
        val reader = HookDefaultReader()
        reader.day = -1
        val ctx = object : EvalContext {
            override val variables: VariableReader get() = reader
            override fun callFunction(name: String, args: List<com.eratw.port.core.expr.Value?>) =
                throw com.eratw.port.core.expr.ExprEvalException("测试未绑定函数 $name")
            override fun printSystemLine(message: String) = Unit
        }
        val e = assertFailsWith<CodeEE> {
            intRef("ITEM", VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)).getIntValue(ctx)
        }
        assertTrue(e.message!!.startsWith("数组变量\"ITEM\""), e.message)
        assertFailsWith<CodeEE> {
            strRef("STR", VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)).getStrValue(ctx)
        }
        assertFailsWith<CodeEE> {
            intRef("ITEM", VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)).addIntValue(ctx, 1)
        }
    }

    /** 写侧（`Eval.kt:219/:223` 缺省体）：越界写照抛，不静默落任何覆盖层。 */
    @Test
    fun `Eval 写侧钩子缺省实现回落严格通道`() {
        val reader = HookDefaultReader()
        reader.day = -1
        val ctx = object : EvalContext {
            override val variables: VariableReader get() = reader
            override fun callFunction(name: String, args: List<com.eratw.port.core.expr.Value?>) =
                throw com.eratw.port.core.expr.ExprEvalException("测试未绑定函数 $name")
            override fun printSystemLine(message: String) = Unit
        }
        assertFailsWith<CodeEE> {
            intRef("ITEM", VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)).setIntValue(ctx, 7)
        }
        assertFailsWith<CodeEE> {
            strRef("STR", VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)).setStrValue(ctx, "x")
        }
    }

    // ------------------------------------------------------------------
    // 跑飞护栏：SPARSE_OVERFLOW_LIMIT（VariableStore.kt:69，= 1 shl 16）
    // ------------------------------------------------------------------

    /**
     * `writeSparseIntOor` 的防 runaway 上限：覆盖层满 65536 键后**丢弃新写入**、
     * 既有键不受影响（PC 无上限，手机侧防脚本撑爆堆；c45357a86 提交信息标注
     * TODO(verify) 存档口径——本锁只钉护栏本身，存档持久化属存档 lane）。
     *
     * **反向证明**：撤掉 `c45357a86`，`VarRef.setIntValue` 动态越界走严格通道抛，
     * 驱动面不存在，本锁红。若上限语义变了（改为淘汰旧键/改为扩容），「丢弃新键、
     * 旧键保留」两条断言立红，须随裁决更新。
     */
    @Test
    fun `稀疏覆盖层跑飞护栏 超限丢弃新键旧键保留`() {
        val s = VariableStore()
        val limit = 1 shl 16
        // ITEM 长 10000 ⇒ 下标 100000 起全部越界，经补径写落覆盖层（严格公共口会照抛，
        // 所以这里直呼钩子口 writeSparseIntOor —— 与 VarRef 补径落到的是同一实现）。
        for (i in 1..limit) {
            s.writeSparseIntOor("ITEM", longArrayOf(100_000L + i), i.toLong())
        }
        // 满层后再写 ⇒ 丢弃（读回 0），既有键原样。
        s.writeSparseIntOor("ITEM", longArrayOf(100_000L + limit + 1), 777L)
        assertEquals(0L, s.readSparseIntOor("ITEM", longArrayOf(100_000L + limit + 1)), "超限新键被丢弃")
        assertEquals(123L, s.readSparseIntOor("ITEM", longArrayOf(100_000L + 123)), "既有键不受影响")
        // 复位清层（与 SparseArrayOorTest.复位清空覆盖层 同口径的全量表断言）。
        s.writeSparseIntOor("ITEM", longArrayOf(100_000L + 1), 1L)
        s.resetValues()
        assertEquals(0L, s.readSparseIntOor("ITEM", longArrayOf(100_000L + 123)), "复位后清空")
    }

    // ------------------------------------------------------------------
    // 二元算术通道补口（SparseArrayOorTest 只钉了比较通道；算术 + - * / 同走 VarRef）
    // ------------------------------------------------------------------

    /**
     * 真机现场的同族形态（COOKING_UI 的 `ITEM:SELECT_ING` 还会进算术，如
     * `ITEM:(DISH_ING:LOCAL) + 1` 一类）：二元**算术**操作数里的动态越界读同样吃补径。
     * 通道 = Binary.getIntValue → VarRef.getIntValue（Expr.kt:164 的 catch 已覆盖），
     * 本锁把它钉在 + 与 * 两侧，防「只兜比较、算术另算」的误改。
     *
     * **反向证明**：撤 `c45357a86`，VarRef 严格通道抛 OoRVarArg，Binary 求值整体炸，
     * 两条断言立红（玩家文案同款）。
     */
    @Test
    fun `二元算术里的动态下标越界读取默认值后照常运算`() {
        val s = VariableStore()
        val ctx = TestEvalContext(s)
        val plus = Binary(
            OperatorCode.PLUS,
            intRef("ITEM", dynamicIndex(s, -1)),
            IntLiteral(3),
            OperandType.INT,
        )
        assertEquals(3L, plus.getIntValue(ctx), "0 + 3")
        val mul = Binary(
            OperatorCode.MULT,
            intRef("ITEM", dynamicIndex(s, 99999)),
            IntLiteral(2),
            OperandType.INT,
        )
        assertEquals(0L, mul.getIntValue(ctx), "0 * 2")
    }
}
