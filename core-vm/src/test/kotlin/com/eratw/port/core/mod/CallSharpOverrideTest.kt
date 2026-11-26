package com.eratw.port.core.mod

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.modapi.ModFunction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-11-26 B3：CALLSHARP 通道的**显式覆盖内建面**。
 *
 * 旧形态（REVIEW-MOD-ARCH-20261123 B3）：`FunctionApi.register` 重名拒绝（复刻原版
 * `Dictionary.Add` 崩溃口径）、`NativeMethodRegistry.register` 重名 CodeEE —— mod Kotlin
 * 函数是「新增外部函数」，永远无法以 Kotlin 覆盖宿主内建行为（残疾）。
 *
 * 新形态 = 新增 / 替换双闸 + 装配序前移：
 * - [NativeMethodRegistry.register] 仍只认新增（原版崩溃口径不变）；
 * - [NativeMethodRegistry.replace] 只认替换（名字不存在 IAE，fail-closed 对称），
 *   覆盖留痕 [NativeMethodRegistry.overriddenNames]；
 * - [FunctionApi.override] 是 mod 侧唯一合法覆盖口；
 * - 生产装配序 = 宿主终表 → mod attach → **prefillSharpLinks**（裁决看到终表，常量名
 *   CALLSHARP 行联结期就绑到覆盖后的本体）—— 哨兵用例 ⑤ 钉住 GameHost 的段序不回退。
 */
class CallSharpOverrideTest {

    // ---------------- 注册表双闸 ----------------

    @Test
    fun `register 重名仍 CodeEE 原版崩溃口径不变`() {
        val registry = NativeMethodRegistry()
        registry.register("CHAT") { }
        val e = assertThrows<CodeEE> { registry.register("chat") { } }
        assertTrue(e.message!!.contains("重复注册"), "实际：${e.message}")
    }

    @Test
    fun `replace 未注册名字 IAE 不静默变新增`() {
        val registry = NativeMethodRegistry()
        val e = assertThrows<IllegalArgumentException> { registry.replace("GHOST") { } }
        assertTrue(e.message!!.contains("未注册"), "实际：${e.message}")
        assertTrue(!registry.has("GHOST"), "替换口不得新增条目")
    }

    @Test
    fun `replace 已注册名字生效且键大写归一`() {
        val registry = NativeMethodRegistry()
        registry.register("CHAT") { params -> params[0].strValue = "host" }
        registry.replace("chat") { params -> params[0].strValue = "mod" }
        val params = arrayOf(NativeParam(isString = true))
        registry.find("CHAT")!!.execute(params)
        assertEquals("mod", params[0].strValue, "替换生效（大小写归一取键）")
        assertEquals(listOf("CHAT"), registry.overriddenNames())
    }

    @Test
    fun `overriddenNames 排序稳定且 clear 归零`() {
        val registry = NativeMethodRegistry()
        registry.register("B") { }
        registry.register("A") { }
        registry.replace("b") { }
        registry.replace("a") { }
        assertEquals(listOf("A", "B"), registry.overriddenNames())
        registry.clear()
        assertEquals(emptyList(), registry.overriddenNames(), "表清了留痕就是谎报")
    }

    // ---------------- mod 侧 facade ----------------

    @Test
    fun `facade override 未注册名字 IAE`() {
        val api = NativeFunctionApi(NativeMethodRegistry())
        val e = assertThrows<IllegalArgumentException> {
            api.override("GHOST", ModFunction { })
        }
        assertTrue(e.message!!.contains("新增用 register"), "实际：${e.message}")
    }

    @Test
    fun `facade override 替换宿主内建本体且出参回写走同一桥`() {
        val registry = NativeMethodRegistry()
        // 宿主内建：把出参写成 "host"。
        registry.register("CHAT") { params -> params[0].strValue = "host" }
        val api = NativeFunctionApi(registry)
        // mod 显式覆盖：把出参写成 "mod"（经同一 ModCallArg 回写桥）。
        api.override("chat", ModFunction { args -> args[0].strValue = "mod" })
        val params = arrayOf(NativeParam(isString = true))
        registry.find("CHAT")!!.execute(params)
        assertEquals("mod", params[0].strValue, "覆盖后宿主本体不再被调")
        assertEquals(listOf("CHAT"), registry.overriddenNames())
        // register 口仍拒绝同名字（覆盖 ≠ 重开新增闸）。
        assertThrows<IllegalArgumentException> { api.register("CHAT", ModFunction { }) }
    }
}
