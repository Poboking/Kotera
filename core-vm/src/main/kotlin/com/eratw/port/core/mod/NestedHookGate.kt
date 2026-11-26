package com.eratw.port.core.mod

import com.eratw.port.core.vars.VariableStore

/**
 * 嵌套指令调用派发前的两道**旁路快判**（VM 的 `callInto`/`jumpInto` 接管点专用）。
 *
 * 与 [FunctionHookWiring.dispatchCall] 的旁路判据逐条同口径：aux 未装 / 槽已解绑 ⇒ ① 假；
 * 该函数键无声明槽 ⇒ ② 假。两道都假时调用方在**算函数键、解实参之前**就返回，连实参数组
 * 都不构造（无 mod 常态成本 = 一次 aux 查表 + 一次 boolean 读，无分配）。
 *
 * 单独成文件而非挂在 [FunctionHookWiring] 上：后者的函数数接近 detekt 阈值。
 */
internal object NestedHookGate {

    /** 旁路快判①：aux 未装 / 槽已解绑 ⇒ false。 */
    fun installed(store: VariableStore): Boolean =
        store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)?.pipeline != null

    /** 旁路快判②：该函数键是否有声明槽。无接线恒 false。 */
    fun hasHooks(store: VariableStore, key: String): Boolean =
        store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)?.pipeline?.hasHooks(key) == true
}
