package com.eratw.port.core.code

/**
 * VM 指令级嵌套调用被函数钩子接管后的返回快照（[VmHost.dispatchNestedHookCall] 的载体）。
 *
 * 只在**确有声明槽**时构造；无接线 / 无槽时宿主返回 null，VM 照常建帧执行（零分配旁路）。
 *
 * ## 双返回通道（2026-11-26 B1/B2 起）
 *
 * 调用位要哪个通道由调用点的 `isStrRet`（`_CALL.b` bit28）决定，宿主按同一通道投影：
 *
 * - **整数通道**（缺省）：[intValue] 是返回值，[strValue] 为 null；
 * - **字符串通道**（`isStrRet = true`）：[strValue] 是返回值（null 折成空串，与原版
 *   掉尾 `ReturnF(null)` 的消费侧 `SuperUserDefinedMethodTerm.cs:33-38` 得 `""` 同口径），
 *   [intValue] 恒 0。
 *
 * 通道错配由宿主接线层（`FunctionHookWiring`）按调用位期望先行折算并告警（int 调用位收到
 * 字符串替换值 → WARN + 0；str 调用位收到整数替换值 → WARN + `""`），VM 侧不再二次判型。
 * 与宿主按名调用 `ProcessRuntime.doCall` → `FunctionHookWiring.dispatchCall` 的返回值投影
 * 同一套语义，没有第二套实现。
 */
class HookedCallReturn(
    val intValue: Long,
    val strValue: String? = null,
)
