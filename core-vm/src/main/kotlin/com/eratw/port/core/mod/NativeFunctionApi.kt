package com.eratw.port.core.mod

import com.eratw.port.core.fn.NativeMethod
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModFunction

/**
 * [FunctionApi] 的引擎侧适配器（K1 原型，era-arch-v61u.md §18.3/§18.8）：wrap [NativeMethodRegistry]
 * （CALLSHARP 通道，core/fn/PluginMethods.kt）。
 *
 * ## 证手感原型（K1 的「起手接 1–2 NativeMethod」）
 *
 * 这是「双执行环境，一个 Runtime」的 ERB→Kotlin 半边的最小可运行证明：mod 经 mod-api 的
 * [ModFunction] 注册一个 Kotlin 函数，本适配器把它包成引擎 [NativeMethod] 进 [NativeMethodRegistry]；
 * ERB `CALLSHARP "名字", 实参…` 经语句层调到它。生产生命周期接线（在 onEnable 注册、卸载撤销）
 * 属 K3 脏活，本原型只证缝可用。
 *
 * ## 出参回写桥（铁边界 §18.2）
 *
 * mod 不持有引擎 [com.eratw.port.core.fn.NativeParam] 对象。适配器把引擎实参拷进 mod 侧
 * [ModCallArg]，调 mod 函数，再把 mod 改后的值**回写**进引擎实参数组——语句层据此把变量实参
 * 位置的值带回 ERB（与原版 IPluginMethod.Execute 无返回值、全靠改实参槽的口径一致）。
 *
 * clean 语义（§18.7）：[register] 重名经 [require] 抛 [IllegalArgumentException]（mod 侧干净报错），
 * 不静默覆盖；[override]（B3，2026-11-26）只认已存在的名字——mod 显式覆盖宿主内建的合法通道，
 * 拼错的名字在替换口当场红。不复刻官方装载期怪癖（ADR-005 仍约束 ERB 语句面）。
 */
class NativeFunctionApi(private val registry: NativeMethodRegistry) : FunctionApi {

    override fun register(name: String, function: ModFunction) {
        require(!registry.has(name)) { "函数 $name 重复注册" }
        registry.register(name, wrap(function))
    }

    override fun override(name: String, function: ModFunction) {
        require(registry.has(name)) {
            "函数 $name 未注册，override 只能替换已存在的注册（新增用 register）"
        }
        registry.replace(name, wrap(function))
    }

    override fun has(name: String): Boolean = registry.has(name)

    /** mod 侧 [ModFunction] → 引擎 [NativeMethod] 适配（拷进 / 回写桥，铁边界 §18.2）。 */
    private fun wrap(function: ModFunction): NativeMethod =
        NativeMethod { nativeArgs ->
            val modArgs = Array(nativeArgs.size) { i ->
                val a = nativeArgs[i]
                ModCallArg(isString = a.isString, intValue = a.intValue, strValue = a.strValue)
            }
            function.call(modArgs)
            for (i in nativeArgs.indices) {
                nativeArgs[i].intValue = modArgs[i].intValue
                nativeArgs[i].strValue = modArgs[i].strValue
            }
        }
}