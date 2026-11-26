package com.eratw.port.core.vars

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.sem.TestEvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 4.981 稀疏 1 维背板的下标语义：**动态**下标越界不报错，**常量**下标越界照旧硬抛。
 *
 * 权威 = 蛇版 4.981 反编译源码 + PC 端 Emuera 实测（`1824+v24+EMv18+EEv56+Skiav16`）：
 * - 存储：全局/角色 1 维数组是 `SparseArray<long>` / `SparseArray<string>` 字典
 *   （VariableData.cs:44；CharacterData.cs:25），下标器对任意 long 取键、缺席读默认值
 *   （SparseArray.cs:25-40）⇒ 运行期越界读 0 / ""、越界写落字典（可读回）。
 * - 常量下标：执行前 `VariableTerm.Restructure` 只对 `SingleTerm` 实参调
 *   `Identifier.CheckElement`（VariableTerm.cs:316-347）⇒ 常量越界仍抛 OoRVarArg。
 * - 2D/3D、CSV 常量表、用户 `#DIM`：fork 没有换成字典背板 ⇒ 任何越界都抛。
 *
 * PC 实测判词（本用例逐条对照）：`X = -1` 后 `{ITEM:X}` → 0；`ITEM:X = 7` 后读回 7；
 * `ITEM:X += 5` 后读回 5；`STR:X = "ab"` 后读回 "ab"；`{ITEM:(-1)}` → 抛
 * 「配列変数"ITEM"の第1引数(-1)は配列の範囲外です」。
 */
class SparseArrayOorTest {

    private fun store() = VariableStore()

    /** 运行期求值的下标表达式：把 [value] 写进 `DAY:0` 再拿它当下标（非字面量 ⇒ 动态形态）。 */
    private fun dynamicIndex(store: VariableStore, value: Long): Expr {
        store.writeInt("DAY", longArrayOf(0), value)
        return VarRef("DAY", listOf(IntLiteral(0)), operandType = OperandType.INT)
    }

    private fun intRef(name: String, vararg idx: Expr) =
        VarRef(name, idx.toList(), operandType = OperandType.INT)

    private fun strRef(name: String, vararg idx: Expr) =
        VarRef(name, idx.toList(), operandType = OperandType.STR)

    // ---------------- 全局 1 维整数 ----------------

    @Test
    fun `动态下标越界读返回默认值 0`() {
        val s = store()
        val ctx = TestEvalContext(s)
        assertEquals(0L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
        assertEquals(0L, intRef("ITEM", dynamicIndex(s, 99999)).getIntValue(ctx))
        assertEquals(0L, intRef("FLAG", dynamicIndex(s, 10000)).getIntValue(ctx), "FLAG 长 10000，上界外同样返 0")
    }

    @Test
    fun `动态下标越界写落覆盖层并能读回`() {
        val s = store()
        val ctx = TestEvalContext(s)
        val idx = dynamicIndex(s, -1)
        intRef("ITEM", idx).setIntValue(ctx, 7)
        assertEquals(7L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx), "PC 实测 `ITEM:X = 7`（X=-1）后读回 7")
        // 覆盖层按 (数组, 角色行, 元素) 隔离：另一个越界下标读到的仍是默认值。
        assertEquals(0L, intRef("ITEM", dynamicIndex(s, -2)).getIntValue(ctx))
    }

    @Test
    fun `动态下标越界自增读回累加值`() {
        val s = store()
        val ctx = TestEvalContext(s)
        assertEquals(5L, intRef("ITEM", dynamicIndex(s, -1)).addIntValue(ctx, 5), "PC 实测 += 返回加完的新值")
        assertEquals(5L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
        assertEquals(8L, intRef("ITEM", dynamicIndex(s, -1)).addIntValue(ctx, 3))
    }

    @Test
    fun `写默认值等价于删键`() {
        val s = store()
        val ctx = TestEvalContext(s)
        intRef("ITEM", dynamicIndex(s, 5000)).setIntValue(ctx, 9)
        intRef("ITEM", dynamicIndex(s, 5000)).setIntValue(ctx, 0)
        assertEquals(0L, intRef("ITEM", dynamicIndex(s, 5000)).getIntValue(ctx), "SparseArray.set(默认值) = Remove")
    }

    @Test
    fun `复位清空覆盖层`() {
        val s = store()
        val ctx = TestEvalContext(s)
        intRef("ITEM", dynamicIndex(s, -1)).setIntValue(ctx, 7)
        s.resetValues()
        assertEquals(0L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx), "新局/复位后上一局的越界值不许穿越")
    }

    // ---------------- 全局 1 维字符串 ----------------

    @Test
    fun `动态下标越界读返回空串`() {
        val s = store()
        val ctx = TestEvalContext(s)
        assertEquals("", strRef("STR", dynamicIndex(s, -1)).getStrValue(ctx))
        assertEquals("", strRef("STR", dynamicIndex(s, 20000)).getStrValue(ctx), "STR 长 20000")
    }

    @Test
    fun `动态下标越界写能读回`() {
        val s = store()
        val ctx = TestEvalContext(s)
        strRef("STR", dynamicIndex(s, -1)).setStrValue(ctx, "ab")
        assertEquals("ab", strRef("STR", dynamicIndex(s, -1)).getStrValue(ctx), "PC 实测 `STR:X = \"ab\"` 后读回 ab")
        strRef("STR", dynamicIndex(s, -1)).setStrValue(ctx, "")
        assertEquals("", strRef("STR", dynamicIndex(s, -1)).getStrValue(ctx))
    }

    // ---------------- 角色 1 维 ----------------

    @Test
    fun `角色 1 维动态元素下标越界同样容忍`() {
        val s = store()
        s.addPseudoCharacter()
        val ctx = TestEvalContext(s)
        val row = IntLiteral(0)
        assertEquals(0L, intRef("CFLAG", row, dynamicIndex(s, -1)).getIntValue(ctx))
        intRef("CFLAG", row, dynamicIndex(s, -1)).setIntValue(ctx, 5)
        assertEquals(5L, intRef("CFLAG", row, dynamicIndex(s, -1)).getIntValue(ctx), "PC 角色 1 维元素槽同样是每角色 SparseArray")
    }

    @Test
    fun `角色行越界仍然抛出`() {
        val s = store()
        s.addPseudoCharacter()
        val ctx = TestEvalContext(s)
        // 角色位是稠密槽：角色列表本身没有稀疏背板，行越界仍是硬错误。
        val e = assertFailsWith<CodeEE> { intRef("CFLAG", dynamicIndex(s, 9), IntLiteral(0)).getIntValue(ctx) }
        assertEquals(true, e.message!!.startsWith("角色数组变量\"CFLAG\""), e.message!!)
    }

    // ---------------- 常量下标：照旧硬抛 ----------------

    @Test
    fun `常量下标越界仍抛 OoRVarArg`() {
        val s = store()
        val ctx = TestEvalContext(s)
        val e = assertFailsWith<CodeEE> { intRef("ITEM", IntLiteral(-1)).getIntValue(ctx) }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message, "PC 实测常量下标 -1 抛同一条")
        assertFailsWith<CodeEE> { intRef("ITEM", IntLiteral(99999)).getIntValue(ctx) }
        assertFailsWith<CodeEE> { intRef("ITEM", IntLiteral(99999)).setIntValue(ctx, 1) }
        assertFailsWith<CodeEE> { strRef("STR", IntLiteral(-1)).getStrValue(ctx) }
    }

    @Test
    fun `常量算术式下标按常量处理 仍抛`() {
        val s = store()
        val ctx = TestEvalContext(s)
        // 解析期不折常量（Parser.buildVariableTerm 保留 Binary），判据递归判「折得动」——
        // 与 PC 的 Restructure 口径等价：`ITEM:(-2+1)` 同样在 CheckBounds 抛。
        val expr = Binary(OperatorCode.PLUS, IntLiteral(-2), IntLiteral(1), OperandType.INT)
        val e = assertFailsWith<CodeEE> { intRef("ITEM", expr).getIntValue(ctx) }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message)
    }

    // ---------------- 真机现场通道：二元比较里的动态越界读 ----------------

    @Test
    fun `二元比较里的动态下标越界读取默认值后照常比较`() {
        val s = store()
        val ctx = TestEvalContext(s)
        // 真机现场（0.2.47 上报 `数组变量"ITEM"的第1参数(-1)超出数组范围`）：
        // COOKING_UI.ERB 物理行 293 `ELSEIF ITEM:SELECT_ING <= 0`，`SELECT_ING` = -1（未选，
        // 同文件行 134 `SIF SELECT_ING >= 0`、行 183 `IF SELECT_ING == DISH_ING:LOCAL` 都把 -1
        // 当合法态）。Java 栈面 = Binary.getIntValue → VarRef.getIntValue → readIntH → checkFlat
        // ⇒ 必须容忍动态越界读（SparseArray.cs:25-40 取默认 0），否则整条 COM413 料理流程中断。
        val cmp = Binary(
            OperatorCode.LESS_EQUAL,
            intRef("ITEM", dynamicIndex(s, -1)),
            IntLiteral(0),
            OperandType.INT,
        )
        assertEquals(1L, cmp.getIntValue(ctx), "0 <= 0 成立；修复前此处抛 OoRVarArg")
        val notEqual = Binary(
            OperatorCode.NOT_EQUAL,
            intRef("ITEM", dynamicIndex(s, -1)),
            IntLiteral(0),
            OperandType.INT,
        )
        assertEquals(0L, notEqual.getIntValue(ctx))
    }

    // ---------------- 表达式位 ++ / --（前置与后置） ----------------

    @Test
    fun `前置自增走稀疏背板并返回加完的新值`() {
        val s = store()
        val ctx = TestEvalContext(s)
        // spec: OperatorMethodManager.cs:609-627 → VariableTerm.ChangeValue（VariableTerm.cs:218-235）：
        // 返回 **加完的新值**；1 维数组落 SparseArray.cs:25-40 ⇒ 缺省 0 加增量写回字典。
        val inc = Unary(OperatorCode.INCREMENT, intRef("ITEM", dynamicIndex(s, -1)))
        assertEquals(1L, inc.getIntValue(ctx))
        assertEquals(1L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
        assertEquals(2L, inc.getIntValue(ctx), "同一越界槽第二次自增基于已落字典的值")
    }

    @Test
    fun `前置自减走稀疏背板`() {
        val s = store()
        val ctx = TestEvalContext(s)
        val dec = Unary(OperatorCode.DECREMENT, intRef("ITEM", dynamicIndex(s, -1)))
        assertEquals(-1L, dec.getIntValue(ctx), "缺省 0 - 1")
        assertEquals(-1L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
    }

    @Test
    fun `后置自增走稀疏背板并返回旧值`() {
        val s = store()
        val ctx = TestEvalContext(s)
        // spec: OperatorMethodManager.cs:629-655（IncrementAfterInt）—— 返回**改写前**的旧值。
        val inc = UnaryAfter(OperatorCode.INCREMENT, intRef("ITEM", dynamicIndex(s, -1)))
        assertEquals(0L, inc.getIntValue(ctx), "旧值 = 稀疏缺省 0")
        assertEquals(1L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
        assertEquals(1L, inc.getIntValue(ctx), "第二次自增的旧值 = 上一次写回的 1")
    }

    @Test
    fun `后置自减走稀疏背板并返回旧值`() {
        val s = store()
        val ctx = TestEvalContext(s)
        intRef("ITEM", dynamicIndex(s, -1)).setIntValue(ctx, 3)
        val dec = UnaryAfter(OperatorCode.DECREMENT, intRef("ITEM", dynamicIndex(s, -1)))
        assertEquals(3L, dec.getIntValue(ctx))
        assertEquals(2L, intRef("ITEM", dynamicIndex(s, -1)).getIntValue(ctx))
    }

    @Test
    fun `常量下标的自增自减照旧抛`() {
        val s = store()
        val ctx = TestEvalContext(s)
        val e = assertFailsWith<CodeEE> {
            Unary(OperatorCode.INCREMENT, intRef("ITEM", IntLiteral(-1))).getIntValue(ctx)
        }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message, "动态才转稀疏；常量下标 PC 同样在 CheckBounds 抛")
        assertFailsWith<CodeEE> {
            UnaryAfter(OperatorCode.DECREMENT, intRef("ITEM", IntLiteral(99999))).getIntValue(ctx)
        }
    }

    @Test
    fun `二维数组的自增动态越界仍抛`() {
        val s = store()
        val ctx = TestEvalContext(s)
        assertFailsWith<CodeEE> {
            Unary(OperatorCode.INCREMENT, intRef("DITEMTYPE", dynamicIndex(s, -1), IntLiteral(0))).getIntValue(ctx)
        }
        assertFailsWith<CodeEE> {
            UnaryAfter(
                OperatorCode.INCREMENT,
                intRef("DITEMTYPE", IntLiteral(0), dynamicIndex(s, 100)),
            ).getIntValue(ctx)
        }
    }

    // ---------------- 其它背板：保持严格 ----------------

    @Test
    fun `2 维内置数组动态越界仍抛`() {
        val s = store()
        val ctx = TestEvalContext(s)
        val e = assertFailsWith<CodeEE> {
            intRef("DITEMTYPE", dynamicIndex(s, -1), IntLiteral(0)).getIntValue(ctx)
        }
        assertEquals("数组变量\"DITEMTYPE\"的第1参数(-1)超出数组范围", e.message, "2D 是稠密 long[][,]，越界必抛")
        assertFailsWith<CodeEE> {
            intRef("DITEMTYPE", IntLiteral(0), dynamicIndex(s, 100)).getIntValue(ctx)
        }
    }

    @Test
    fun `store 公共 API 保持严格`() {
        val s = store()
        val e = assertFailsWith<CodeEE> { s.readInt("ITEM", longArrayOf(-1)) }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message, "容忍语义只在 VarRef 层；公共入口照旧严格")
        assertFailsWith<CodeEE> { s.writeInt("ITEM", longArrayOf(1000), 1) }
        assertFailsWith<CodeEE> { s.readStr("STR", longArrayOf(-1)) }
    }
}
