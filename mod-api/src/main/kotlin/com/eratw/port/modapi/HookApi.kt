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

    /**
     * 只读投影：当前会话全部已声明 hook 槽的快照，按「functionKey 字典序 → priority 升序 →
     * 声明序」稳定排序 —— 与引擎 `HookPipeline` 槽表的确定性序（同一函数内 priority 升序 +
     * 声明序）同源同口径，只是把「同一函数内」的序扩成跨函数键的全序，让打印与断言的输出确定。
     *
     * ## 用途：作者自查「谁在钩同一函数、什么顺序、priority 撞没撞」
     *
     * hook 槽在装载期经 [ModManifest.hooks] 声明、跨 mod 汇进同一张槽表，但此前没有任何查询面：
     * 作者只能等运行期行为诡异才排查「是不是别的 mod 也钩了这个函数」。本面把槽表投影出来：
     * 同函数多条槽按真实槽序排列，同函数同相位同 priority 的撞车一眼可见（两条相邻、包名不同）。
     *
     * ## 快照语义
     *
     * 投影源是链接期冻结的槽表（[register] 只往已声明槽上绑处理器）：**声明了但尚未绑定处理器
     * 的槽也在投影里**（它占着执行序里的位置）。返回独立列表，调用方随便增删改都不影响引擎。
     *
     * 缺省实现显式 fail-closed 抛 [UnsupportedOperationException]：**UOE = 宿主未接线（当前实现
     * 不认识本方法 / 没有槽表可投影），不是「没钩子」** —— 没钩子会答空表、不抛，两者不许混。
     * 生产 face（`ModContextImpl.hooks`）与台架面（mod-testing 的 `RecordingHooks`）覆写本方法。
     */
    fun registrations(): List<HookRegistration> =
        throw UnsupportedOperationException(
            "hooks.registrations() 未接线：当前 HookApi 实现没有 hook 槽表可投影" +
                "（台架镜像 / 旧装配路径）；UOE = 宿主未接线，不是没钩子",
        )

    /**
     * 只读查询：当前会话**可挂目标函数键**集合的快照，迭代序按字典序（`String` 自然序）稳定
     * 排序，供作者自查 / 打印「能钩什么」（[registrations] 答「谁在钩」，本面答「能钩什么」，
     * 两面拼起来才是完整的 hook 发现面）。返回独立集合：调用方随便增删都不影响引擎。
     *
     * ## 真源与灌缝（与 events 的 knownEventKeys 完全同构）
     *
     * 可挂目标 = 链接期 `SymbolCatalog.bindings` 的键集 —— 符号目录里全部**非事件胜者绑定**
     * （`FUNCTION` 与 `#FUNCTION` 方法同表；事件键在 `SymbolCatalog.events`，不进本集合）。
     * 宿主装配时经 `ModRuntimeHost.knownFunctionKeys` 灌入（灌源链见 app 侧 `ModRuntimeAssembly`
     * 的函数目录注入行），再落到各 mod context 的 hook 面。[register] 的拼错闸用的就是**同一份**
     * 集合（最近候选口径与 events 的 `EventDispatchRegistry` 未知名拒绝一致），所以
     * `targetKey in knownFunctionKeys()` ⟺ 该键不会被拼错闸判未知 —— 查询面与闸面同源，不新增
     * 第二份真相。
     *
     * **未灌源（台架 / 旧装配）时集合为空**：这是有意的 fail-safe —— 台架不知道真实函数面，
     * 查询面不猜、也不让作者依赖假阳性；此时 [register] 同口径不校验（空集 = 关闸）。
     *
     * 缺省实现显式 fail-closed 抛 [UnsupportedOperationException]（与 [registrations] 缺省同口径）：
     * 不认识本方法的旧实现绝不静默返回空集。生产 face（`ModContextImpl.hooks`）与台架面
     * （mod-testing 的 `RecordingHooks`）覆写本方法。
     */
    fun knownFunctionKeys(): Set<String> =
        throw UnsupportedOperationException(
            "hooks.knownFunctionKeys() 未接线：当前 HookApi 实现没有可挂函数目录" +
                "（台架镜像 / 旧装配路径）；不猜函数面",
        )
}

/** hook 相位（映射引擎侧 `HookPhase`）。 */
enum class HookPhase { PREFIX, POSTFIX }

/**
 * 一条已声明 hook 槽的只读投影（[HookApi.registrations] 的元素）。
 *
 * [kind] 复用 [HookPhase]：PREFIX/POSTFIX 就是 hook 的「种类」概念，本面不另造平行枚举。
 * [functionKey] 是槽表的大写归一目标键；[priority] 与执行序同源（priority 升序先跑）；
 * [packageId] 标注槽的归属包，供「谁在钩同一函数」跨 mod 自查。
 */
data class HookRegistration(
    val functionKey: String,
    val kind: HookPhase,
    val priority: Int,
    val packageId: String,
)

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
