package com.eratw.port.modtesting

import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.RuntimeScheduler

/** 一条日志记录（作者用例断言日志侧链用）。 */
data class LogEntry(
    val level: String,
    val packageId: String,
    val message: String,
    val cause: Throwable? = null,
)

/**
 * 台架硬化上限的缺省值：单条消息最大字符数。
 *
 * 数值与 [com.eratw.port.core.mod.ModContextImpl] 里生产 `PackageLogger` 的口径一致，
 * 硬编码副本是刻意的：mod-testing 的 main 除资源面外只依赖 mod-api，不能引用 core-vm 的私有常量。
 * 逐字对拍由 core-vm 测试源集的 `LoggerHardeningParityTest` 锁住，改一侧会红。
 */
internal const val HARDEN_MAX_MESSAGE_LENGTH: Int = 1000

/**
 * 台架硬化上限的缺省值：每个 context 最多落多少行正文。
 *
 * 口径同 [HARDEN_MAX_MESSAGE_LENGTH]：权威是生产 `PackageLogger`，副本由对拍用例锁。
 */
internal const val HARDEN_MAX_LINES_PER_CONTEXT: Int = 1000

/** 生产截断标记原文（[HARDEN_MAX_MESSAGE_LENGTH] 同款说明）。 */
private const val TRUNCATED_SUFFIX: String = "…(截断)"

/**
 * 生产元告警的**正文**原文（不含 `[包名][WARN] ` 前缀）。
 *
 * 台架把日志存成结构化 [LogEntry]，包名/等级各有字段，故这里只复制生产行正文；
 * 对拍用例把台架记录重新拼回生产行格式后逐字比较。
 * 口径同 [HARDEN_MAX_MESSAGE_LENGTH]：权威是生产 `PackageLogger`。
 */
private const val SUPPRESSED_NOTICE_BODY: String =
    "日志超过 %d 条上限，后续同类日志静默（本行之前已抑制 %d 条）"

/**
 * 把 mod 日志收进列表的 [ModLogger] 实现。
 *
 * 缺省（未调 [harden]）**逐条原样录制**：作者用例断言日志时看到的永远是作者自己写的原文，
 * 不会被截断/丢弃/元告警干扰 —— 这是台架作为「录制面」的本分。
 *
 * 调 [harden] 后按生产 `PackageLogger` 的三条硬化模拟落 [sink]：
 * 1. 空白消息（`isBlank()`）且无 cause：**静默丢弃**，不占行数额度；
 * 2. 每 context 最多 [harden] 传入的行数，越限后正文静默，仅在首次越限时落**一条**元告警
 *    （正文 = [SUPPRESSED_NOTICE_BODY]，等级 WARN，抑制计数取首次越限时的值 = 1）；
 * 3. 正文超过上限字符数时截断为前 N 个字符 + [TRUNCATED_SUFFIX]；cause 在截断**之后**拼接，
 *    故长消息不会把 cause 吞掉。
 *
 * 硬化可关可开、开关只影响该 logger 自己的记录，方便作者两边对照（默认关 = 现状）。
 */
internal class TestLogger(
    private val packageId: String,
    private val sink: MutableList<LogEntry>,
) : ModLogger {

    private var maxMessageLength: Int = 0
    private var maxLinesPerContext: Int = 0
    private var emitted: Int = 0
    private var suppressed: Int = 0
    private var notifiedSuppression: Boolean = false

    /** 是否处于硬化模拟态。 */
    internal val isHardened: Boolean get() = maxLinesPerContext > 0

    /** 硬化模拟下被静默的越限条数（生产不暴露此值，台架供作者断言口径用）。 */
    internal val suppressedLineCount: Int get() = suppressed

    /**
     * 打开硬化模拟（参数口径 = 生产 `PackageLogger`；缺省值见 [HARDEN_MAX_MESSAGE_LENGTH] /
     * [HARDEN_MAX_LINES_PER_CONTEXT]）。可重复调用，每次重置行数/抑制计数。
     */
    internal fun harden(maxMessageLength: Int, maxLinesPerContext: Int) {
        require(maxMessageLength > 0) { "maxMessageLength 必须 > 0，实际 $maxMessageLength" }
        require(maxLinesPerContext > 0) { "maxLinesPerContext 必须 > 0，实际 $maxLinesPerContext" }
        this.maxMessageLength = maxMessageLength
        this.maxLinesPerContext = maxLinesPerContext
        emitted = 0
        suppressed = 0
        notifiedSuppression = false
    }

    override fun info(message: String) = record("INFO", message, null)

    override fun warn(message: String) = record("WARN", message, null)

    override fun error(message: String, cause: Throwable?) = record("ERROR", message, cause)

    private fun record(level: String, message: String, cause: Throwable?) {
        when {
            !isHardened -> sink += LogEntry(level, packageId, message, cause)
            // 生产：空白且无 cause 静默丢弃，不占行数额度。
            message.isBlank() && cause == null -> Unit
            emitted >= maxLinesPerContext -> noteSuppression()
            else -> emitBody(level, message, cause)
        }
    }

    /** 越限：正文静默、抑制计数 +1；仅首次越限落一条元告警（与生产一致）。 */
    private fun noteSuppression() {
        suppressed++
        if (notifiedSuppression) return
        notifiedSuppression = true
        sink += LogEntry("WARN", packageId, SUPPRESSED_NOTICE_BODY.format(maxLinesPerContext, suppressed))
    }

    /** 落一行正文：占行数额度，超长截断后拼接 cause。 */
    private fun emitBody(level: String, message: String, cause: Throwable?) {
        emitted++
        val body = if (message.length <= maxMessageLength) {
            message
        } else {
            message.take(maxMessageLength) + TRUNCATED_SUFFIX
        }
        sink += LogEntry(level, packageId, body, cause)
    }
}

/**
 * 单线程调度器的内存替身：`isOnMainThread = true`、`submit` 同步立即执行。
 *
 * 作者用例里即使出现 `submit` 也不需要 wait / 轮询：台架没有第二线程。
 */
internal class TestRuntime : RuntimeScheduler {

    override val isOnMainThread: Boolean get() = true

    override fun submit(action: () -> Unit) = action()
}
