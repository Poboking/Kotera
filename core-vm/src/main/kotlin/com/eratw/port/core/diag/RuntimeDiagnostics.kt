package com.eratw.port.core.diag

import com.eratw.port.modapi.Registration
import java.util.Collections

/**
 * 运行期诊断的**域**：一条记录「说的是哪一类事」。
 *
 * 前七域与 era-arch-v61u.md §21.6 的 `domain{PHASE, JOB, HOOK, INTERACTION, DECISION, ACTION, SAVE}`
 * 逐项同序；`EVENT`（事件处理器派发）是 §21.6 之后**末尾追加**的第八域，**不许插在旧域中间**：
 * 顺序本身是对外可观测面（[RuntimeDiagnosticsTest] 把 `entries.map { it.name }` 钉死），
 * 改序 = 改导出与工具侧判读；追加只动新域，旧七域的名字与相对次序都不变。
 * 本类只落**域枚举**，不落业务语义。
 */
enum class DiagnosticDomain { PHASE, JOB, HOOK, INTERACTION, DECISION, ACTION, SAVE, EVENT }

/**
 * 运行期诊断的**结果**：这条记录描述的那一步最后成了没有（§21.6 冻结四态）。
 *
 * 四态含义由记录方给，本类不做推断：`OK` = 走通、`SKIPPED` = 按条件跳过、
 * `FAILED` = 执行失败、`REJECTED` = 被仲裁/守卫拒掉。顺序同 §21.6，钉法同上。
 */
enum class DiagnosticOutcome { OK, SKIPPED, FAILED, REJECTED }

/**
 * 一条运行期诊断记录（值对象，构造后不再变）。
 *
 * ## 字段口径（§21.6 + 契约 §2）
 *
 * - [seq]：**会话内**单调，从 1 开始，与容量无关（环丢老记录不回收 seq，见 [RuntimeDiagnostics]）。
 * - [day] / [phase]：时间只来自入参（本类**不读墙钟**）；未知 = `null`。`phase` 的「未知/空」
 *   由调用方传 `null` —— 本类**不改造入参**（与 [reasonCode] 原样保存同一口径：诊断只记录事实，
 *   不做归一化，免得读数与现场对不上）。
 * - [domain] / [outcome]：见 [DiagnosticDomain] / [DiagnosticOutcome]。
 * - [packageId]：`null` = 引擎自身；非 null = 该记录归属的 Mod 包。
 * - [key]：被记录对象的键（相位名 / 任务键 / hook 目标 / 候选键 / 存档点……）。语义由记录方定，
 *   本类只当**不透明字符串**搬运。
 * - [reasonCode]：**不透明字符串**，不校验词汇、不做枚举映射（决策原因码词汇归 §21.3 仲裁车，
 *   本文件**不定义**任何业务原因码常量 —— 避免第二套真源，契约 §2.1 第 9 条）。
 * - [detail]：**惰性构造**的可选细节，`null` = 无；开启态在 `record` 里求值一次并缓存成
 *   这里的字符串（求值失败给 `<detail failed: 类名>`，见 [RuntimeDiagnostics.record]）。
 *
 * 为什么是 `data class`：形状（九个字段 / 名字 / 顺序）与契约 §2 逐字一致，只额外拿到
 * `equals`/`hashCode`/`toString` —— 这是纯诊断值对象，不是 ABI（§21.6 头注：P1 工程契约）；
 * 工具与单测按值比对是常态，手写三件套没有收益。
 */
data class DiagnosticRecord(
    val seq: Long,
    val day: Long?,
    val phase: String?,
    val domain: DiagnosticDomain,
    val packageId: String?,
    val key: String,
    val outcome: DiagnosticOutcome,
    val reasonCode: String,
    val detail: String?,
)

/**
 * 失败归因摘要条目：按 (domain, packageId, outcome) 三元组聚合出的计数（契约 §2 Lane DX3-B）。
 *
 * 只由 [diagnosticSummariesOf] 产出，`count >= 1`。`packageId` 为 null 表示引擎自身
 * （与 [DiagnosticRecord.packageId] 同义），摘要里保留 null 而不归一化 —— 否则「引擎自身」
 * 与某个恰好叫占位串的包会混成一组。
 */
data class DiagnosticSummary(
    val domain: DiagnosticDomain,
    val packageId: String?,
    val outcome: DiagnosticOutcome,
    val count: Int,
)

/**
 * 失败归因摘要聚合（纯函数，契约 §2 Lane DX3-B）。
 *
 * 口径（组长裁定，禁止自创）：
 * - 只收 FAILED / REJECTED；OK / SKIPPED 一律不进摘要（反向证明的摘除点就在下面那行过滤）；
 * - 分组键 = (domain, packageId, outcome)，三元组全等才算同组；
 * - 排序 = count 降序，同数按 key 字典序（key 串形如 DOMAIN|package|OUTCOME，null 包 id
 *   用 `<engine>` 占位，字典序即 [String.compareTo] 的自然序）。
 *
 * 本函数只读入参、不碰任何账本内部表，是可被 vmdbg 复用的**单一真源**：vmdbg 把 JSONL
 * 还原成 [DiagnosticRecord] 后调同一个 [diagnosticSummariesOf]，保证工具面与引擎面的摘要口径不会漂移。
 *
 * 顶层函数而非 object：core 层铁律禁静态可变全局，`RuntimeDiagnosticsTest` 的
 * 「源码不读墙钟且无静态可变全局」逐文件扫顶层 object 并判红。
 */
fun diagnosticSummariesOf(records: Iterable<DiagnosticRecord>): List<DiagnosticSummary> {
    val counts = HashMap<Triple<DiagnosticDomain, String?, DiagnosticOutcome>, Int>()
    for (r in records) {
        // 摘掉本行过滤 ⇒ OK/SKIPPED 进摘要 ⇒ 对应用例红（反向证明锚点）。
        if (r.outcome != DiagnosticOutcome.FAILED && r.outcome != DiagnosticOutcome.REJECTED) continue
        val key = Triple(r.domain, r.packageId, r.outcome)
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.entries
        .map { (k, n) -> DiagnosticSummary(k.first, k.second, k.third, n) }
        .sortedWith(compareByDescending<DiagnosticSummary> { it.count }.thenBy { diagnosticSummaryKeyOf(it) })
}

/** 摘要行的三元组键串（排序用；null 包 id 的占位串不与非空包名碰撞）。 */
private fun diagnosticSummaryKeyOf(s: DiagnosticSummary): String =
    "${s.domain.name}|${s.packageId ?: "<engine>"}|${s.outcome.name}"

/**
 * 运行期诊断总线（M8-1，era-arch-v61u.md §21.6；落地边界按 §12.1 口径）。
 *
 * ## 它治什么
 *
 * 既有诊断面都是**引擎专用**的：`ErrorReport`（错误报告）、`PageFunctionTrail`（本页函数流水）、
 * `SaveDiagnostics` 各自只答自己那一问；Mod Runtime（相位 / 任务 / hook / 交互 / 决策 / 行动 /
 * 存档）没有统一的可查询执行轨迹，「事件为什么没触发」只能靠猜。本类给一条**通用**的结构化
 * 轨迹：有界环形缓冲 + 订阅推送 + JSON lines 导出，**无 UI、无墙钟、无静态全局**（§21.6）。
 *
 * 与既有诊断**共存**：不替换、不 import、不改 `ErrorReport` / `PageFunctionTrail` /
 * `ProgramDiagnostics` / `LiveSetCensus` / `LockWatch`（契约 §2.1 第 8 条）。消费者是编辑器 /
 * 游戏内工具，不是引擎的一部分。
 *
 * ## 有界环与会话语义
 *
 * 容量 `capacity >= 1`（构造期 `require`，`0` / 负数直接拒）。写满后新记录挤掉最旧；
 * [snapshot] 恒按**最旧 → 最新**保序。
 *
 * [DiagnosticRecord.seq] **严格单调 +1、从 1 开始，与容量无关**：容量 4 的第 100 条记录
 * `seq` 仍是 100 —— 环丢的是老记录，不是编号（工具靠 `seq` 判「中间被挤掉了多少」）。
 *
 * [setEnabled] `false` = 清空环并停止记录；再 `true` = 从**空环**与 `seq = 1` 重新开始
 * （**会话语义**：读档重建 / 换会话就该重编号）。同一状态重复设置是 no-op（不清环）。
 *
 * ## 关闭态零构造成本（契约 §2.1 第 2 条）
 *
 * `isEnabled == false` 时 [record] 在**求值 `detail` thunk 之前**就返回：不进实例锁、不分配
 * [DiagnosticRecord]、不推进 `seq`、不通知订阅者。挂载点因此可以「无脑 record」，开关关着时
 * 只付一次 volatile 布尔读（同 `ProcessRuntime.statementProbe` 的守卫口径）。
 * [disabled] 给的是「关闭态实例」的现成工厂（`capacity = 1, startEnabled = false`）。
 *
 * ## 惰性 detail
 *
 * 开启态才求值 `detail()`，且**只求值一次**、结果缓存在 [DiagnosticRecord.detail]（之后
 * 快照 / 导出都不再调）；`detail()` 抛任何 `Throwable` 一律吞成 `"<detail failed: 类名>"`，
 * **绝不把异常抛给调用方**（诊断面不许改变游戏语义，§21.6「不改变任何游戏语义」）。
 *
 * ## 订阅
 *
 * [subscribe] 返回可撤销 [Registration]（撤销幂等）。记录成功后在**实例锁外同步**按**注册序**
 * 回调；派发用的是本次记录取到的**订阅快照**，所以「本轮记录不受中途退订影响」，退订只影响
 * 后续记录。单个 listener 抛异常被隔离（不影响记录入库、不影响其他 listener）。
 *
 * `Registration` 复用 `modapi` 的既有同类（`core/mod` 的 `HookPipeline` / `GameScheduler` /
 * `InteractionPipeline` / `ModRuntimeHost` 等六个订阅面同源），**不新定义** —— 契约 §2 末行的
 * 「有同类则复用既有」，避免同一角色出现两套注册类型。方向也合法：`:core-vm` 已 `api` 依赖
 * `:mod-api`（`core-vm/build.gradle.kts`）。
 *
 * ## 线程安全
 *
 * [record] / [snapshot] / [latest] / [subscribe] / [exportJsonLines] / [setEnabled] 全部
 * 线程安全：状态读写收在**实例锁**（`synchronized`）内，记录顺序 = 进入锁的顺序。
 * **回调在锁外执行**（防死锁：listener 里调 `snapshot`/`record` 不会自锁，也不会卡住别的线程）。
 * 状态全部挂实例，**无静态可变全局**（铁律 4：状态挂实例 / `store.aux`；本类不给 companion
 * 留任何 `var`）。
 *
 * ## JSON lines 导出（确定性）
 *
 * 每条一行 `\n` 结尾，九个键**恒在**且顺序恒为 `seq, day, phase, domain, package, key,
 * outcome, reason, detail`；无记录 = 空串（零字节）：
 *
 * `{"seq":1,"day":3,"phase":"morning","domain":"JOB","package":null,"key":"k","outcome":"OK","reason":"r","detail":null}`
 *
 * 转义：`"`→`\"`、`\`→`\\`、换行 / 回车 / 制表→`\n` / `\r` / `\t`，其余 `< 0x20` 的控制字符
 * →`\u00XX`（**大写**十六进制）；非 ASCII 原样输出（UTF-8）。`null` 字段写 JSON `null`。
 * 键序与转义都钉死 = 导出可逐字节比对（历史读数的判读靠这个）。
 *
 * ## 不是 ABI
 *
 * 本类是 P1 工程契约（§21.6 头注），不冻结、不进 §10 的 ABI 清单；[DiagnosticRecord.reasonCode]
 * 是不透明字符串，决策原因码词汇归 §21.3 仲裁车，本文件**不定义业务原因码常量、不做枚举映射**。
 *
 * @param capacity 环容量（`>= 1`，默认 256 —— 一屏工具读数 × 数轮交互的取样量级）
 * @param startEnabled 起始开关（`false` ⇒ 从关闭态起，[record] 零成本旁路）
 * @throws IllegalArgumentException `capacity < 1`
 */
class RuntimeDiagnostics(
    capacity: Int = DEFAULT_CAPACITY,
    startEnabled: Boolean = true,
) {

    init {
        require(capacity >= 1) { "RuntimeDiagnostics 容量必须 >= 1（收到 $capacity）" }
    }

    /** 实例锁：所有状态读写都在它里面；回调**不在**它里面（见类 KDoc「线程安全」）。 */
    private val lock = Any()

    /** 环容量（构造后不变）。 */
    private val capacity: Int = capacity

    /** 环形槽；有效槽位恒非 null，[count] 之外的槽位不读。 */
    private val ring: Array<DiagnosticRecord?> = arrayOfNulls(capacity)

    /** 下一个写入位。 */
    private var head: Int = 0

    /** 当前有效条数（封顶 [capacity]）。 */
    private var count: Int = 0

    /** 已发出去的最后一个 seq（0 = 本会话还没记录过）。 */
    private var seq: Long = 0L

    /** 订阅表（注册序；派发前取快照）。 */
    private val subscribers: MutableList<Subscriber> = ArrayList()

    /**
     * 开关。`@Volatile` 只为 [isEnabled] 的无锁读与 [record] 的关闭态快速旁路；
     * 真正的状态迁移仍在 [lock] 内（[setEnabled]）。
     */
    @Volatile
    private var enabledState: Boolean = startEnabled

    /** 当前是否记录（关闭态 [record] 零构造成本旁路，见类 KDoc）。 */
    val isEnabled: Boolean get() = enabledState

    /**
     * 开关诊断（会话级）。
     *
     * - `setEnabled(false)`：清空环、`seq` 归零、停止记录（之后的 [record] 是零成本 no-op）；
     * - `setEnabled(true)`：从**空环**与 `seq = 1` 重新开始（会话语义）；
     * - 与当前状态相同 ⇒ no-op（**不清环**，避免「重复设置把现场抹了」）。
     *
     * 订阅表**不**随开关清空（订阅是装配期一次性的，关掉再打开仍该收到新记录）。
     */
    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (enabledState == enabled) return
            enabledState = enabled
            ring.fill(null)
            head = 0
            count = 0
            seq = 0L
        }
    }

    /**
     * 记一条诊断。
     *
     * 关闭态（[isEnabled] `false`）在**求值 [detail] 之前**返回：不进锁、不分配、不推进
     * `seq`、不通知订阅者（契约 §2.1 第 2 条）。
     *
     * 开启态：`seq` 先 +1（**与容量无关**，环溢出照推），再求值 [detail]（一次、异常吞成
     * `"<detail failed: 类名>"`），入环，最后在**锁外**按订阅快照回调（单 listener 异常隔离）。
     *
     * @param day `GameTime.day`；未知传 `null`
     * @param phase `GameTime.phase`；未知/空传 `null`
     * @param domain 记录所属域
     * @param packageId `null` = 引擎自身
     * @param key 被记录对象的键（不透明字符串）
     * @param outcome 该步结果
     * @param reasonCode 不透明原因码（本类不校验词汇）
     * @param detail 惰性细节；`null` = 无。仅在开启态、且在锁内求值一次
     */
    fun record(
        day: Long?,
        phase: String?,
        domain: DiagnosticDomain,
        packageId: String?,
        key: String,
        outcome: DiagnosticOutcome,
        reasonCode: String,
        detail: (() -> String)? = null,
    ) {
        // 关闭态零构造：不进锁、不求值 thunk、不分配。
        if (!enabledState) return
        val pending = synchronized(lock) {
            // 进锁后复查：检查与入锁之间可能被 setEnabled(false) 关掉。
            if (!enabledState) return@synchronized null
            seq += 1
            val detailText = if (detail == null) null else evaluateDetail(detail)
            val record = DiagnosticRecord(
                seq = seq,
                day = day,
                phase = phase,
                domain = domain,
                packageId = packageId,
                key = key,
                outcome = outcome,
                reasonCode = reasonCode,
                detail = detailText,
            )
            ring[head] = record
            head = wrap(head + 1)
            if (count < capacity) count += 1
            // 订阅快照：本轮派发不受派发途中退订影响（契约 §2.1 第 4 条）。
            PendingDispatch(record, if (subscribers.isEmpty()) emptyList() else subscribers.map { it.listener })
        } ?: return
        for (listener in pending.listeners) {
            // 单 listener 异常隔离：不影响记录入库，也不影响其他 listener。
            try {
                listener(pending.record)
            } catch (_: Throwable) {
                // 诊断面不许把订阅方的异常带回记录方。
            }
        }
    }

    /**
     * 当前环的**不可变副本**，最旧 → 最新（空环 = 空列表）。
     *
     * 副本与实例解耦：之后 [record] / [setEnabled] 都不会改已返回的列表。
     */
    fun snapshot(): List<DiagnosticRecord> = synchronized(lock) { snapshotLocked() }

    /**
     * 最近 [n] 条（同样最旧 → 最新）。
     *
     * `n <= 0` ⇒ 空列表；`n >= 当前条数` ⇒ 全部（= [snapshot]）。
     */
    fun latest(n: Int): List<DiagnosticRecord> {
        if (n <= 0) return emptyList()
        return synchronized(lock) {
            if (count == 0) return@synchronized emptyList()
            val take = if (n >= count) count else n
            val out = ArrayList<DiagnosticRecord>(take)
            // count == capacity 时 head 指向最旧之后的下一格；否则有效区间从 0 起。
            val first = head - take
            for (i in 0 until take) {
                val r = ring[physical(first + i)]
                if (r != null) out.add(r)
            }
            Collections.unmodifiableList(out)
        }
    }

    /**
     * 订阅推送：每次记录成功后在**锁外同步**按注册序回调（派发用本轮快照）。
     *
     * 返回的 [Registration] 撤销幂等；撤销只影响**后续**记录（本轮已取快照的照常回调）。
     */
    fun subscribe(listener: (DiagnosticRecord) -> Unit): Registration {
        val entry = Subscriber(listener)
        synchronized(lock) { subscribers.add(entry) }
        return object : Registration {
            override fun unregister() {
                synchronized(lock) { subscribers.remove(entry) }
            }
        }
    }

    /**
     * JSON lines 导出：一条记录一行、行尾 `\n`；空环 = 空串（零字节）。
     *
     * 九键固定序、转义规则与 `null` 写法见类 KDoc「JSON lines 导出」节。快照在锁内取，
     * 序列化在锁外做（不给导出拖长持锁时间）。
     */
    fun exportJsonLines(): String {
        val rows = synchronized(lock) { snapshotLocked() }
        if (rows.isEmpty()) return ""
        val sb = StringBuilder(rows.size * 160)
        for (row in rows) {
            appendRecordJson(sb, row)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 失败归因摘要查询（契约 §2 Lane DX3-B）：按 (domain, packageId, outcome) 聚合，
     * 只出 FAILED / REJECTED，count 降序、同数按 key 字典序（口径见 [diagnosticSummariesOf]）。
     *
     * 纯查询：锁内取 [snapshotLocked] 副本后交给纯函数聚合，**不改环、不改 seq、不改开关**，
     * 对 [snapshot] / [exportJsonLines] 零改写；空环返回空列表。
     */
    fun failureSummary(): List<DiagnosticSummary> =
        diagnosticSummariesOf(synchronized(lock) { snapshotLocked() })

    /** 锁内取快照：按物理环序还原成最旧 → 最新。 */
    private fun snapshotLocked(): List<DiagnosticRecord> {
        if (count == 0) return emptyList()
        val out = ArrayList<DiagnosticRecord>(count)
        val first = head - count
        for (i in 0 until count) {
            val r = ring[physical(first + i)]
            if (r != null) out.add(r)
        }
        return Collections.unmodifiableList(out)
    }

    /** 物理下标归一（[first] 可能为负：`head - count`）。 */
    private fun physical(i: Int): Int {
        val m = i % capacity
        return if (m < 0) m + capacity else m
    }

    /** 写入位推进（只 +1，故只需处理等于容量这一种回卷）。 */
    private fun wrap(i: Int): Int = if (i == capacity) 0 else i

    /** 惰性 detail 求值：只调一次；任何 `Throwable` 都吞成占位串，绝不外抛。 */
    private fun evaluateDetail(detail: () -> String): String =
        try {
            detail()
        } catch (t: Throwable) {
            val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
            "<detail failed: $name>"
        }

    /** 一条记录的九个键（顺序恒为 seq,day,phase,domain,package,key,outcome,reason,detail）。 */
    private fun appendRecordJson(sb: StringBuilder, r: DiagnosticRecord) {
        sb.append("{\"seq\":").append(r.seq)
        sb.append(",\"day\":")
        if (r.day == null) sb.append("null") else sb.append(r.day)
        sb.append(",\"phase\":")
        appendJsonStringOrNull(sb, r.phase)
        sb.append(",\"domain\":\"").append(r.domain.name).append('"')
        sb.append(",\"package\":")
        appendJsonStringOrNull(sb, r.packageId)
        sb.append(",\"key\":")
        appendJsonString(sb, r.key)
        sb.append(",\"outcome\":\"").append(r.outcome.name).append('"')
        sb.append(",\"reason\":")
        appendJsonString(sb, r.reasonCode)
        sb.append(",\"detail\":")
        appendJsonStringOrNull(sb, r.detail)
        sb.append('}')
    }

    /** `null` 写 JSON `null`，否则按字符串规则转义（两侧带引号）。 */
    private fun appendJsonStringOrNull(sb: StringBuilder, value: String?) {
        if (value == null) sb.append("null") else appendJsonString(sb, value)
    }

    /**
     * 字符串转义（契约 §2.1 第 6 条）：`"` / `\` / 换行 / 回车 / 制表五个命名转义，
     * 其余 `< 0x20` 转 `\u00XX`（大写十六进制），其余原样（含非 ASCII，按 UTF-8 输出）。
     */
    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (ch < ' ') {
                        sb.append("\\u00")
                        sb.append(HEX[(ch.code shr 4) and 0xF])
                        sb.append(HEX[ch.code and 0xF])
                    } else {
                        sb.append(ch)
                    }
            }
        }
        sb.append('"')
    }

    /** 订阅项：包一层，让撤销按**这一次注册**精确移除（同一 listener 重复订阅也互不误伤）。 */
    private class Subscriber(val listener: (DiagnosticRecord) -> Unit)

    /** 锁内备好的派发物：一条记录 + 本轮订阅快照（出锁后再回调）。 */
    private class PendingDispatch(
        val record: DiagnosticRecord,
        val listeners: List<(DiagnosticRecord) -> Unit>,
    )

    companion object {
        /** 缺省容量：256 条（§21.6 只要求「有界 + 可配置」，具体值取一屏工具读数的量级）。 */
        private const val DEFAULT_CAPACITY = 256

        /** 控制字符转义用的十六进制表（大写字面量，常量字符串 ⇒ 非静态可变全局）。 */
        private const val HEX = "0123456789ABCDEF"

        /**
         * 关闭态实例工厂：`capacity = 1, startEnabled = false`。
         *
         * 给「本会话不需要诊断」的装配点一个现成对象 —— [record] 只付一次 volatile 读，
         * 不分配、不进锁（契约 §2.1 第 2 条）。需要可开关实例就用主构造器。
         */
        fun disabled(): RuntimeDiagnostics = RuntimeDiagnostics(capacity = 1, startEnabled = false)
    }
}
