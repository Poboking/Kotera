package com.eratw.port.core.proc

/**
 * TEMP(perf-probe) E0：嵌套 VM 通道的墙钟探针。**缺省 null = 生产零行为**。
 *
 * 接线点（全部先判空再取 `System.nanoTime()`，null 路径零额外计时）：
 * - [ProcessRuntime.exprCallUserMethodRef]：实参求值 + scratch 借还；
 * - [ProcessRuntime.invokeUserMethod]：meta/depth/ref 绑定、rootBind、`nested.run`、返回装箱；
 * - [ProcessRuntime.onFunctionEnter]/[onFunctionExit]：`store.enterFunction`/`leaveFunction`；
 * - [com.eratw.port.core.code.VirtualMachine.run]：`Vm.kt:1140-1170` 的状态重置段。
 *
 * 回调实现**不得阻塞、不得抛**：调用点包 `runCatching`，探针故障不得反噬执行流。
 * 时间单位一律 ns；`totalNs` 不含 [ProcessRuntime.exprCallUserMethodRef] 的实参求值（见 [argNs]）。
 */
fun interface NestedChannelProbe {

    /**
     * 一次嵌套方法调用结束。
     *
     * @param name 被调方法名（用于在测试侧按 callee 过滤，例如只看 `CHK_DATENOW`）
     * @param argNs `exprCallUserMethodRef` 里实参逐位求值 + scratch 借还（不含 [totalNs]）
     * @param totalNs `invokeUserMethod` 入口到返回装箱完成（含 [runNs]）
     * @param runNs `nested.run(...)` 整段（含 [resetNs]/[enterNs]/[exitNs]/被调体指令）
     * @param resetNs `VirtualMachine.run` 的 `Vm.kt:1140-1170` 状态重置段
     * @param enterNs `store.enterFunction` 单次耗时
     * @param exitNs `store.leaveFunction` 单次耗时
     * @param retNs `invokeUserMethod` 的返回通道选择 + `Value` 装箱
     */
    fun onCall(
        name: String,
        argNs: Long,
        refNs: Long,
        toValueNs: Long,
        scratchNs: Long,
        totalNs: Long,
        runNs: Long,
        resetNs: Long,
        enterNs: Long,
        exitNs: Long,
        retNs: Long,
    )
}
