package com.eratw.port.modapi

/**
 * 游戏相位能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §20.2 / ABI-10 Candidate）。
 *
 * Runtime 支持 **Mod 自己定义的游戏阶段**：相位名经 [ModManifest.phases] 声明、进声明事务
 * （与引擎固定相位同全局命名空间），运行期 [subscribe] 把处理器绑到已声明相位。引擎按
 * 声明序 + priority 确定性派发（复用 §6.1 贡献点纪律），不认识相位名里的任何玩法含义。
 *
 * ## 与既有件的边界（勿混）
 *
 * - 引擎固定相位（[BuiltinPhase]）永远存在；Mod 相位与它们并列，不替换原版流程。
 * - 游戏时钟触发 ≠ [RuntimeScheduler.submit] 的线程转移边界（§18.5）：后者是后台线程
 *   把改状态动作排回主执行，本面是「游戏日/相位到达时派发」。
 *
 * clean 语义（§18.7）：未声明相位订阅 = 拒绝（fail-closed）；处理器失败按 Mod 隔离
 * （§18.4），不拖垮其余相位处理器。
 */
interface PhaseApi {
    /**
     * 订阅一个相位（引擎固定相位或本包/他包已声明相位）。相位名按大写归一。
     *
     * @throws IllegalArgumentException 相位未声明、或同一包对同一相位重复订阅
     */
    fun subscribe(phase: String, handler: PhaseHandler): Registration
}

/** 游戏时间快照：当前游戏日 + 相位名。 */
data class GameTime(val day: Long, val phase: String = "")

/**
 * 引擎固定相位名（§6.1 Game Phase Hooks）。
 *
 * 它们与原版事件名派发同纪律；Mod 自定义相位不得占用这些名字（声明事务拒绝）。
 */
object BuiltinPhase {
    const val DAY_START: String = "DAY_START"
    const val DAY_END: String = "DAY_END"
    const val ACTION: String = "ACTION"

    /** 全部固定相位（大写归一后的集合）。 */
    val ALL: Set<String> = setOf(DAY_START, DAY_END, ACTION)
}

/** 相位回调上下文（只读；改游戏状态仍经 [RuntimeScheduler.submit]，§18.5）。 */
class PhaseContext(
    val packageId: String,
    val phase: String,
    val time: GameTime,
)

/** 一个相位处理器。 */
fun interface PhaseHandler {
    fun onPhase(context: PhaseContext)
}
