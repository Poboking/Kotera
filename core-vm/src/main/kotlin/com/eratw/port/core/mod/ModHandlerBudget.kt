package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics

/**
 * 作者处理器（hook / event / interaction）的**单次耗时预算**（阈值诊断）。
 *
 * ## 治什么
 *
 * 一个慢处理器会把游戏主相位一起拖慢，但既有面只有两种读法：要么逐次耗时账本
 * （[ModTimingLedger] 的聚合读数，答「谁总共占了多少」），要么什么都没有。本类补的是
 * 「**某一次**调用越过阈值了」这条可上屏/可导出的事件：超阈值时记一条
 * [RuntimeDiagnostics] 记录（domain 由调用点给、key = 操作名、reason =
 * [REASON_SLOW]、detail = 本次耗时与阈值），并把该包的慢调用次数 +1。
 *
 * ## 不改变语义（硬约束）
 *
 * 超阈值**只记录**：不抛异常、不中断、不降级、不改变处理器返回值与调用序。处理器抛异常时
 * 异常**原样**抛给调用点的既有失败隔离，但耗时照记（抛错的慢处理器一样是慢的）。
 *
 * ## 零开销旁路
 *
 * 三个接线点都只在预算对象**非 null** 时才取时钟、才构造操作名字符串；装配侧不建预算
 * （`ModRuntimeHost.handlerBudget == null`）时三处与既有派发逐位相同，不付任何代价。
 *
 * ## 时钟与线程
 *
 * 时钟注入（缺省 `System.nanoTime`）：单测给假时钟即可断言数值，不依赖宿主时钟。
 * 写入只在 VM 线程（hook 派发 / 事件桶派发 / 交互阶段都在 VM 线程，ADR-003 单线程），
 * 与 [ModTimingLedger] 同口径不加锁。负耗时（时钟回拨）按「未超阈值」处理，不产生负值记录。
 *
 * @param clock 纳秒时钟（测试注入假时钟）
 * @param thresholdNanos 单次调用耗时阈值（纳秒，默认 [DEFAULT_THRESHOLD_NANOS] = 16ms）
 * @param diagnostics 诊断总线；null = 只计数不记诊断（仍可 `slowCount`）
 * @throws IllegalArgumentException `thresholdNanos <= 0`
 */
class ModHandlerBudget(
    private val clock: () -> Long = System::nanoTime,
    val thresholdNanos: Long = DEFAULT_THRESHOLD_NANOS,
    private val diagnostics: RuntimeDiagnostics? = null,
) {

    init {
        require(thresholdNanos > 0L) { "处理器耗时阈值必须为正（收到 $thresholdNanos ns）" }
    }

    /** 逐包慢调用次数（键 = packageId，精确匹配；与 [slowCount] 同源）。 */
    private val slowCounts = HashMap<String, Long>()

    /**
     * 计时跑一次处理器调用。
     *
     * 返回值与异常都与直接调用 [block] 逐位相同（异常照原样抛）；唯一副作用是超阈值时
     * 记一条诊断并计数。`block` 抛异常时 finally 里仍按本次耗时判定。
     *
     * @param packageId 处理器归属包（诊断 packageId 与计数的键）
     * @param domain 诊断域（调用点决定：HOOK / INTERACTION / 事件桶所属域）
     * @param operation 操作名（hook = `targetKey.phase`、event = `eventKey.bucket`、
     *   interaction = interactionId）
     */
    fun <T> run(packageId: String, domain: DiagnosticDomain, operation: String, block: () -> T): T {
        val started = clock()
        try {
            return block()
        } finally {
            val elapsed = clock() - started
            if (elapsed > thresholdNanos) {
                slowCounts[packageId] = (slowCounts[packageId] ?: 0L) + 1L
                diagnostics?.record(
                    day = null,
                    phase = null,
                    domain = domain,
                    packageId = packageId,
                    key = operation,
                    outcome = DiagnosticOutcome.OK,
                    reasonCode = REASON_SLOW,
                    detail = { "elapsedNanos=$elapsed thresholdNanos=$thresholdNanos" },
                )
            }
        }
    }

    /** 某包累计超阈值次数（未命中 = 0；`reset` 后归零）。 */
    fun slowCount(packageId: String): Long = slowCounts[packageId] ?: 0L

    /** 清空全部计数（诊断总线里的历史记录不清，那是会话语义）。 */
    fun reset() {
        slowCounts.clear()
    }

    companion object {
        /** 缺省单次阈值：16ms（一帧 60Hz 的预算量级，够宽不误报、够窄能抓到卡顿）。 */
        const val DEFAULT_THRESHOLD_NANOS: Long = 16_000_000L

        /** 慢处理器诊断的原因码（不透明字符串，工具侧按它筛）。 */
        const val REASON_SLOW: String = "SLOW_HANDLER"
    }
}
