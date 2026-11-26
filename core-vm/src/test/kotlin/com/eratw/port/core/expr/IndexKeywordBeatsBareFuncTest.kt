package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 变量下标参数里**关键字命中先于零参函数引用**的顺序锁
 * （spec: ExpressionParser.cs:404-443）。
 *
 * 原版裸标识符的零参函数引用（:404-408）只查 refmethodDic（REF/#DIM 引用方法），
 * 用户 #FUNCTIONS 不在此命中，标识符随后落到 :409-412 的 ConstantData.isDefined
 * → SingleStrTerm（关键字）。移植侧 `resolveFunction` 会把用户 #FUNCTIONS 一并命中，
 * 若让函数引用先判，`EQUIP:ARG:COSPLAY` 就会编译成零参调用 `@COSPLAY`，而函数体内
 * 又求值 `EQUIP:ARG:COSPLAY` → 无限自调。
 *
 * 真实事故（2026-11-20 eratohoReverse 真机）：「调用栈超过上限(10000) @ EVENTTTRAIN#1015 →
 * COSPLAY → COSPLAY → …」，冻结栈全为 COSPLAY 帧。语料真源：`CSV/EQUIP.CSV:27`
 * 的 `9,COSPLAY` —— `COSPLAY` 是 EQUIP 的**索引名**，同时也是
 * `ERB/FUNCTION/GETTER/COMMON_GETTER_CLOTHES.ERB:66` 的 `#FUNCTIONS` 函数名。
 * 同名冲突的完整清单（索引名 ∩ 函数名）都在服装族：COSPLAY / GLOVES …。
 */
class IndexKeywordBeatsBareFuncTest {

    /** EQUIP（角色 1D 关键字变量）+ 同名用户 #FUNCTIONS（返回 STR）+ MASTER（角色位变量）。 */
    private fun equipResolver() = FakeResolver().apply {
        vars["EQUIP"] = VarInfo("EQUIP", OperandType.INT, 1, isCharacterData = true)
        vars["MASTER"] = VarInfo("MASTER", OperandType.INT, 0, isCharacterData = true)
        funcs["COSPLAY"] = OperandType.STR
    }

    @Test
    fun `关键字命中先于零参函数引用 - COSPLAY 编译成关键字下标`() {
        val ref = parseOrThrow(
            listOf(id("EQUIP"), colon(), id("MASTER"), colon(), id("COSPLAY")),
            equipResolver(),
        ) as VarRef
        assertEquals(2, ref.indices.size, "角色 1D 变量完整下标 = [角色位, 关键字位]")
        val kw = assertIs<KeywordIndexArg>(ref.indices[1], "关键字命中不得编成函数调用")
        assertEquals("EQUIP", kw.varName)
        assertEquals(1, kw.dim)
        assertEquals("COSPLAY", assertIs<StrLiteral>(kw.expr).value)
    }

    @Test
    fun `小写写法同样命中关键字`() {
        val ref = parseOrThrow(
            listOf(id("EQUIP"), colon(), id("MASTER"), colon(), id("cosplay")),
            equipResolver(),
        ) as VarRef
        assertIs<KeywordIndexArg>(ref.indices[1])
    }

    @Test
    fun `关键字判三态 null 时零参函数引用保持旧路径 - 用户变量无表保守放行`() {
        // 用户 #DIM 变量（解析期无关键字表）⇒ resolveIndexKeyword 默认 null ⇒
        // 既有口径：保守放行，下标位的裸标识符仍按零参函数引用编译。本测试只锁顺序修复
        // 没有把这条旧路径一并收紧（顶层结点是 VarRef，函数调用在下标位）。
        val resolver = FakeResolver().apply {
            vars["UDIM"] = VarInfo("UDIM", OperandType.INT, 1, isCharacterData = false)
            funcs["MYFUNC"] = OperandType.INT
        }
        val ref = parseOrThrow(listOf(id("UDIM"), colon(), id("MYFUNC")), resolver) as VarRef
        assertIs<FuncCall>(ref.indices.single())
    }

    @Test
    fun `普通表达式位（非下标）的裸函数引用不受影响`() {
        val expr = parseOrThrow(listOf(id("COSPLAY")), equipResolver())
        assertIs<FuncCall>(expr)
    }
}
