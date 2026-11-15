package com.eratw.port.core.mod

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModFunction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K1 原型闸：FunctionApi → NativeMethodRegistry 缝可用（era-arch-v61u.md §18.8「起手接 1–2
 * NativeMethod 证手感」）。证「双执行环境，一个 Runtime」的 ERB→Kotlin 半边：mod 经 mod-api
 * 注册 Kotlin 函数，引擎 CALLSHARP 通道能调到它，出参回写穿过适配器回到引擎实参槽。
 *
 * 不跑真 ERB CALLSHARP 语句（那需整 VM 装配，属 K3 生命周期接线）；本闸直接在注册表取出
 * [com.eratw.port.core.fn.NativeMethod] 调用，证适配器桥本身正确——语句层到注册表的链路
 * 既有 CALLSHARP 用例已覆盖。
 */
class ModFunctionApiPrototypeTest {

    @Test
    fun `注册经 facade 进引擎注册表且大写归一`() {
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        api.register("AddHp", ModFunction { })
        assertTrue(api.has("AddHp"))
        assertTrue(api.has("ADDHP"))
        assertTrue(registry.has("addhp"))
        assertFalse(api.has("missing"))
        assertEquals(listOf("ADDHP"), registry.names())
    }

    @Test
    fun `整型出参回写穿过适配器回到引擎实参`() {
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        api.register("ADD_HP", ModFunction { args -> args[0].intValue += 10L })

        val method = registry.find("ADD_HP")!!
        val params = arrayOf(NativeParam(isString = false, intValue = 5L))
        method.execute(params)
        assertEquals(15L, params[0].intValue)
    }

    @Test
    fun `字符串出参回写穿过适配器`() {
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        api.register("ECHO", ModFunction { args -> args[0].strValue = args[0].strValue + "!" })

        val params = arrayOf(NativeParam(isString = true, strValue = "hi"))
        registry.find("ECHO")!!.execute(params)
        assertEquals("hi!", params[0].strValue)
    }

    @Test
    fun `多实参按位回写且 isString 透传`() {
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        api.register(
            "SWAP",
            ModFunction { args ->
                assertTrue(args[0].isString)
                assertFalse(args[1].isString)
                val tmp = args[0].strValue
                args[0].strValue = args[1].intValue.toString()
                args[1].intValue = tmp.toLong()
            },
        )
        val params = arrayOf(
            NativeParam(isString = true, strValue = "7"),
            NativeParam(isString = false, intValue = 99L),
        )
        registry.find("SWAP")!!.execute(params)
        assertEquals("99", params[0].strValue)
        assertEquals(7L, params[1].intValue)
    }

    @Test
    fun `重名拒绝抛 IllegalArgumentException 不静默覆盖`() {
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        api.register("DUP", ModFunction { args -> args[0].intValue = 1L })
        assertThrows<IllegalArgumentException> {
            api.register("dup", ModFunction { args -> args[0].intValue = 2L })
        }
        // 原实现仍在（未被覆盖）。
        val params = arrayOf(NativeParam(intValue = 0L))
        registry.find("DUP")!!.execute(params)
        assertEquals(1L, params[0].intValue)
    }

    @Test
    fun `mod 拿到的是 ModCallArg 副本而非引擎实参`() {
        // 适配器拷进 ModCallArg：mod 函数签名收 Array<ModCallArg>（与引擎 NativeParam 不同类型），
        // 改的是副本，引擎实参只在回写桥之后才变——铁边界 §18.2 由类型系统保证。
        val registry = NativeMethodRegistry()
        val api = NativeFunctionApi(registry)
        var seen: Array<ModCallArg>? = null
        api.register("CAPTURE", ModFunction { args -> seen = args })
        val params = arrayOf(NativeParam(intValue = 5L))
        registry.find("CAPTURE")!!.execute(params)
        assertEquals(5L, seen!![0].intValue)
        // mod 未改副本时引擎实参不变（回写桥把副本值写回，此处副本 == 原值）。
        assertEquals(5L, params[0].intValue)
    }
}