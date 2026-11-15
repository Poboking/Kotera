package com.eratw.port.modapi

/**
 * 函数 Hook 能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §20.1 / ABI-9 Candidate）。
 *
 * Override + CALL SUPER 是**整函数替换**（同名 last-wins + SUPER 回退）；本面补另一档：
 * **多个 Mod 在同一函数外层挂 Prefix/Postfix 钩子，且都不替换原函数**。钩子挂在 dispatch
 * 解析出的目标外层（Override 胜者 ?: 原函数），与覆盖正交，不改 base 字节码。
 *
 * ## 声明先于绑定（v1 确定性纪律）
 *
 * hook 槽（目标函数键 + 相位 + priority）经 [ModManifest.hooks] 在装载期声明、链接期冻结；
 * 运行期 [register] 只把处理器**绑定**到已声明的槽。目标/相位未声明 = 拒绝（fail-closed，
 * 避免运行期改 dispatch）。槽的确定性序 = priority 升序 + 链接声明序（§6 真实注册序同纪律）。
 *
 * ## 返回协议（clean 语义，§18.7）
 *
 * - [HookResult.Continue]：继续后续 hook / 原函数；
 * - [HookResult.Replace]：立即以该值返回，**跳过原函数与其余 hook**（短路）；
 * - [HookResult.SkipOriginal]：跳过原函数体，Postfix 照常执行。
 *
 * Postfix 里 [HookResult.SkipOriginal] 无意义（原函数已执行完），按 Continue 处理。
 * 处理器抛异常 = 该 Mod 降级报告、跳过该 hook，原函数与其余 hook 继续（§18.4 注册隔离）。
 *
 * ## 参数与返回值的修改权限
 *
 * 声明里逐 hook 指定 [HookDecl.mayMutateArgs] / [HookDecl.mayMutateReturn]（默认否）。
 * 未授权的修改由引擎隔离：实参改在副本上（不回流），返回值改被忽略。不留实现期解释。
 */
interface HookApi {
    /**
     * 把一个处理器绑定到已声明的 hook 槽。[targetKey] 按大写归一。
     *
     * @throws IllegalArgumentException 槽未声明、或同一槽重复绑定
     */
    fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration
}

/** hook 相位（映射引擎侧 `HookPhase`）。 */
enum class HookPhase { PREFIX, POSTFIX }

/** hook 返回值协议（clean 语义；见接口 KDoc 的短路规则）。 */
sealed interface HookResult {

    /** 继续后续 hook / 原函数。 */
    data object Continue : HookResult

    /** 短路：以该值返回，跳过原函数与其余 hook。[value] 为 null 表示无返回值。 */
    data class Replace(val value: ModValue?) : HookResult

    /** 跳过原函数体，Postfix 照常执行。 */
    data object SkipOriginal : HookResult
}

/**
 * hook 回调上下文。
 *
 * [args] 是函数实参槽（与 FunctionApi 的 [ModCallArg] 同形）；只有声明了
 * `mayMutateArgs` 的 hook 改它才会回流到调用点。[returnValue] 仅 Postfix 可读/改，
 * 且仅声明了 `mayMutateReturn` 的 hook 改它才生效。
 */
class HookContext(
    val packageId: String,
    val targetKey: String,
    val phase: HookPhase,
    val args: Array<ModCallArg>,
    var returnValue: ModValue? = null,
)

/** 一个 hook 处理器。 */
fun interface HookHandler {
    fun onHook(context: HookContext): HookResult
}
