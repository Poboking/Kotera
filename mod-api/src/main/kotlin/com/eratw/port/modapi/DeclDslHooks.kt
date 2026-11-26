package com.eratw.port.modapi

/**
 * 函数 hook 的执行策略：优先级 + 三个「允许做什么 / 失败还跑不跑」开关。
 *
 * ## 为什么把四个参数收成一个对象
 *
 * 参数个数超过 detekt 的 LongParameterList 阈值（6）只是表象；真正的理由是这几个开关描述的是
 * **同一条「hook 执行策略」语义**：[priority] 定执行序，[mayMutateArgs] / [mayMutateReturn] /
 * [runOnFailure] / [continueOnHandlerFailure] 定这次调用允许改什么、失败时还跑不跑。它们总是被一起读、一起传、一起扩
 * （以后要加 `mayObserveOnly` 之类也只会加在这里），散成四个平行布尔参数会让调用点读不出
 * 「这是一份策略」。分组后 `hook(target, phase, HookOptions(priority = 10))` 一眼能看出
 * 「在这里覆写策略」，而不是在一串 `true/false` 里数位置。
 *
 * 默认值即「只读、串行、失败不跑」的保守档，与 [HookDecl] 的缺省一致。
 *
 * @property priority 执行序（升序 + 声明序 = 确定性顺序，不得为负）。
 * @property mayMutateArgs 允许改写被 hook 函数的实参。
 * @property mayMutateReturn 允许改写被 hook 函数的返回值。
 * @property runOnFailure 被 hook 的函数抛错时本 hook 仍执行（与「本 hook 自己抛错」是两个轴）。
 * @property continueOnHandlerFailure 本 hook 的处理器抛异常后是否继续保持绑定：`true`（缺省）=
 *   记失败、隔离异常、后续处理器与后续派发照常进入本槽；`false` = 首次抛异常后**撤销本槽**
 *   （只在本槽生命周期内记一条撤销诊断，之后派发不再进入；重新注册可恢复）。
 */
data class HookOptions(
    val priority: Int = 0,
    val mayMutateArgs: Boolean = false,
    val mayMutateReturn: Boolean = false,
    val runOnFailure: Boolean = false,
    val continueOnHandlerFailure: Boolean = true,
)

/**
 * [ModManifest] 的增量构造器。每个方法只追加，不做校验 —— 校验仍在各 `*Decl` 的 `init`
 * 与 core 的声明事务里，DSL 不制造第二套判据。
 *
 * 继承链末端：本文件只留 hook 段（`hook` / `interactionHook`），其余域见 `DeclDsl.kt` 顶部
 * 的「按域拆分」与各 `DeclDsl*.kt`。
 */
class ModManifestBuilder internal constructor(
    packageId: String,
    version: String,
) : ModManifestDataBuilder(packageId, version) {
    /** 声明一个函数 hook 槽（槽必须先声明，运行期才能 bind）。策略见 [HookOptions]。 */
    fun hook(target: FunctionKey, phase: HookPhase, options: HookOptions = HookOptions()) {
        hooks += HookDecl(
            targetKey = target.name,
            phase = phase,
            priority = options.priority,
            mayMutateArgs = options.mayMutateArgs,
            mayMutateReturn = options.mayMutateReturn,
            runOnFailure = options.runOnFailure,
            continueOnHandlerFailure = options.continueOnHandlerFailure,
        )
    }

    /** 声明一个交互阶段钩子。 */
    fun interactionHook(phase: InteractionPhase, priority: Int = 0) {
        interactionHooks += InteractionHookDecl(phase, priority)
    }
}
