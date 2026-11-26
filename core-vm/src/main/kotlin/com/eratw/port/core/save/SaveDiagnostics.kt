package com.eratw.port.core.save

/**
 * 存档写失败的留痕口（**只诊断，不改语义**）。
 *
 * ## 为什么需要
 *
 * 原版 `SaveTo` 把整段落盘包在 `catch (Exception) { return false; }` 里
 * （VariableEvaluator.cs:2666-2669），移植侧 [FileSystemOps.saveData] 照抄了这个口径 ——
 * 语句层随后只能把它折成一句 `SAVEDATA命令在存档时发生了意料之外的错误`
 * （Process.cs:1343-1347）。也就是说**异常本体在移植侧没有任何出口**：
 *
 * - 2026-11-01 玩家反馈「不能覆盖旧存档，报错显示 SAVEDATA命令在存档时发生了意料之外的错误」，
 *   同一份语料 + 同一份存档在桌面（JVM）上覆盖、新建、删档三条链全绿，
 *   说明分叉在 Android 侧的落盘（文件系统 / 存储根 / 权限），而现场没有一条读数可用；
 * - 错误报告通道（`filesDir/eratw-reports/`）在 release 包里不可 `run-as`，
 *   DevTools 通道又只对 debuggable 包开放 —— 玩家机器上两条都够不着。
 *
 * ## 出口为什么是 `System.err`
 *
 * Android 的 zygote 会把 `System.err` 接到 `AndroidHandler` 上，以 **`System.err`** 这个
 * logcat 标签落 ERROR 级日志，**与 debuggable 无关**。于是：
 *
 * ```
 * adb logcat -s System.err
 * ```
 *
 * 在玩家的 release 包上也能读到（这也是本类唯一被验证过的现场取证通道）。
 * 桌面 / 单测里它落标准错误流，不影响任何断言。
 *
 * ## 不改什么
 *
 * - 调用方**仍然**只拿到 `false`（原版语义不变，语句层文案不变）；
 * - 不抛异常、不改控制台输出、不动返回值；
 * - 环形缓冲只是给「同一次会话里连续失败」留上下文，core 自己不落盘、不上报；
 *   上报是**宿主**的事——宿主可装 [onFailure] 观察者（2026-11-20 可上报化），
 *   观察者的异常由本类独立 try/catch 吞掉，主路径观感与没有观察者时一致。
 */
object SaveDiagnostics {

    /** logcat / 测试输出里一眼可认的前缀。 */
    const val PREFIX: String = "[eratw][save]"

    /** 环形缓冲深度：够看清「同一会话里连续几次失败」，又不至于无限长。 */
    private const val RING = 16

    private val last: ArrayDeque<String> = ArrayDeque()

    /**
     * 最近一次存档失败的**上屏用**现场摘要（一句话，异常类型 + 文案或事务拒绝理由）。
     *
     * 为什么另开一个「上屏通道」而不是让语句层读 [Failure]：那一份是给上报链的结构化摘要，
     * 玩家看不到；而真机现场最常见的情况恰恰是**报告发不出来**（没网、上报被关、玩家在群里
     * 只发一句「存档失败」）。原版与移植侧的错误行
     * `SAVEDATA命令在存档时发生了意料之外的错误`（`Process.cs:1344-1346`）不含任何可定案成分
     * —— 抓变量池、序列化、落盘、事务被拒长得一模一样，2026-11-20 多台机报障就是这么卡住的。
     *
     * 语义：**写方**在失败收口处记一条（成功收口记 null），**读方**用 [consumeSaveFailure]
     * 取走并清空 —— 取走即失效，避免上一次失败的残留被下一次无关失败当成现场。
     * 不含绝对路径（错误报告会出网，屏上也同样没必要泄漏玩家目录）。
     */
    @Volatile
    private var lastSaveFailureSummary: String? = null

    /** 记录（或清空，传 null）最近一次存档失败的上屏摘要。写方 = `FileSystemOps.saveData`。 */
    fun recordSaveFailure(summary: String?) {
        lastSaveFailureSummary = summary
    }

    /** 取走并清空上屏摘要；没有现场时返回 null（语句层据此决定是否多打一行）。 */
    fun consumeSaveFailure(): String? {
        val summary = lastSaveFailureSummary
        lastSaveFailureSummary = null
        return summary
    }

    /**
     * 一次写失败的可上报摘要（只给宿主上报链读，不含抛出去的那份异常对象本身）。
     *
     * 为什么是摘要而不是 [Throwable]：观察者的契约是「拿到现场就去上报」，而宿主侧的报告
     * 管线（`ErrorReport`）只吃类型名 + 消息 + 补充现场行；把异常对象递出去会诱使调用方
     * 在**存档失败线程**上走栈、拼串 —— 那正是 [report] 类头禁止的二次事故面。
     *
     * @property op 语句/函数名（`SAVEDATA` / `SAVEGLOBAL` / `SAVECHARA` / `WRITE` …）
     * @property path 落盘相对路径（同 [report] 的 path 参数，不含绝对路径）
     * @property stage 失败阶段：`capture` / `encode` / `write` / `journal-quarantine` …
     * @property errorClass 被吞掉那个异常的类全名
     * @property message 该异常的 message（可能为 null）
     */
    data class Failure(
        val op: String,
        val path: String,
        val stage: String,
        val errorClass: String,
        val message: String?,
    )

    /**
     * 失败观察者：每次 [report] 落地一条失败留痕后**同步**回调一次。
     *
     * 存在的理由：存档失败在语义上只是一句屏上文案（`saveData` 返回 false，异常被原版口径
     * 整个吞掉），既不抛到宿主、也不会触发任何一次报告采集。宿主装上本观察者，才能把
     * 「玩家存档失败但游戏没崩」这一类现场主动送进上报通道；观察者由宿主（app 壳）安装，
     * core 自己不装、不认识 Android。
     *
     * 纪律（与 [report] 同源）：观察者的任何异常都不许冒进存档主路径 —— [report] 在
     * 独立 try/catch 里调用它，见那里的注释。回调发生在**环形缓冲入队之后**，
     * 因此观察者里读 [snapshot] 一定能看到触发它的那一条失败。
     *
     * @see report
     */
    @Volatile
    var onFailure: ((Failure) -> Unit)? = null

    /**
     * 同一个 op 两次「主动上报」之间的冷却窗口（10 分钟）。
     *
     * 为什么需要：存档失败会连续触发几十次（玩家反复点存档 / 脚本循环重试），不设闸就会把
     * 上报端点打爆，同一份现场刷屏对判读是零增量。被 [claimFailureAlert] 挡下的那些次
     * **不是静默丢弃**：它们仍进环形缓冲（[note] 一行），随冷却后的下一份报告带出。
     *
     * 公开给宿主上报链读取（app 侧在日志/证据行里写明窗口长度）；单测直接引用。
     */
    const val ALERT_COOLDOWN_MINUTES: Long = 10L

    /** 冷却窗口的毫秒形态（由 [ALERT_COOLDOWN_MINUTES] 派生，避免两处各写一个数）。 */
    const val ALERT_COOLDOWN_MS: Long = ALERT_COOLDOWN_MINUTES * 60L * 1000L

    /** op -> 上一次放行上报的墙钟毫秒（[clear] 一并清空，仅供单测隔离）。 */
    private val alertStamps: HashMap<String, Long> = HashMap()

    /** 可注入时钟（单测用；生产走墙钟）。读失败按「没记过」处理 = 放行，宁多报不吞现场。 */
    @Volatile
    internal var alertClock: () -> Long = { System.currentTimeMillis() }

    /**
     * 争一次「本 op 现在可以上报」的名额：true = 放行并记下时刻，false = 冷却中。
     *
     * 调用方（宿主上报链）拿到 false 时只许 [note] 一行，不许再落报告。判式在
     * `synchronized` 里做「读 + 写时刻」，并发失败不会同时放行两条。
     *
     * 时钟回拨（NTP 校时）按「冷却已过」处理：宁可多报一条，也不因负差值把上报冻死。
     */
    fun claimFailureAlert(op: String): Boolean = synchronized(alertStamps) {
        val now = runCatching { alertClock() }.getOrDefault(System.currentTimeMillis())
        val lastAt = alertStamps[op]
        val cooling = lastAt != null && now - lastAt in 0 until ALERT_COOLDOWN_MS
        if (cooling) {
            false
        } else {
            alertStamps[op] = now
            true
        }
    }

    /**
     * 记一次写失败。
     *
     * @param op 语句/函数名（`SAVEDATA` / `SAVEGLOBAL` / `SAVECHARA`）
     * @param path 落盘相对路径（[SaveConfig.normalPath] 一类，不是绝对路径 —— 不泄漏玩家目录）
     * @param stage 失败阶段：`capture`（抓变量池）/ `encode`（序列化）/ `write`（落盘）
     * @param error 被吞掉的那个异常
     * @param context 落盘现场快照（目录口径 / 存在性 / 目录内已有条目），见
     *   `FileSystemOps.saveDirContext`；空串 = 调用方没有目录面
     */
    fun report(op: String, path: String, stage: String, error: Throwable, context: String = "") {
        // 整段兜住：本函数跑在**失败的 catch 块里**，其中就包括 OutOfMemoryError。
        // 拼字符串 / 取完整栈 / 环形缓冲入队都要分配内存，在这里再抛一次会把
        // 「存档失败（玩家还能继续玩）」升级成「进程崩溃」—— 比没有现场更糟。
        // 所以留痕的自我要求是：**永远不抛**，做不到就静默。
        runCatching {
            val line = "$PREFIX $op 失败 stage=$stage path=$path " +
                "${error.javaClass.name}: ${error.message}"
            synchronized(last) {
                last.addLast(line)
                while (last.size > RING) last.removeFirst()
            }
            // 三行：可 grep 的摘要 → 现场快照 → 完整栈（`printStackTrace` 默认走 stderr）。
            System.err.println(line)
            if (context.isNotEmpty()) System.err.println("$PREFIX 现场 $context")
            System.err.print(stackOf(error))
        }
        // 观察者在留痕之后单独一层：① 上面任何一步失败（含 OOM）都不许挡住通知；
        // ② 观察者自己抛（含 Error）也只许落到 stderr，绝不冒进存档主路径。
        // 入队先行保证观察者里读 snapshot() 能看到触发它的这一条。
        notifyFailure(op, path, stage, error)
    }

    /** [report] 的通知步；用 runCatching 兜住 Throwable（含 Error），见 [report] 里的两条理由。 */
    private fun notifyFailure(op: String, path: String, stage: String, error: Throwable) {
        val observer = onFailure ?: return
        runCatching { observer(Failure(op, path, stage, error.javaClass.name, error.message)) }
            .onFailure { t ->
                // 连 Error 一起吞：这是宿主上报链，失败只值得一行证据，不值得第二场事故。
                runCatching {
                    System.err.println(
                        "$PREFIX 失败观察者抛异常（已忽略）：${t.javaClass.name}: ${t.message}"
                    )
                }
            }
    }

    /**
     * 记一条**不是失败**但值得留痕的现场（例如「原子换名失败、已退原地覆盖」）。
     *
     * 与 [report] 分开的理由：那一条的语义是「这次存档没了」，这一条是「这次存档成功了，
     * 但走的是退路」。混进同一类会让「玩家报的失败」和「降级成功」在报告里分不开 ——
     * 而后者正是判断「设备存储吃紧 / 文件系统不支持换名」的唯一证据。
     *
     * 只进环形缓冲 + `System.err`，不抛、不改返回值。
     */
    fun note(op: String, path: String, detail: String) {
        // 与 [report] 同一条自我要求：跑在 OOM 之后，永远不抛，做不到就静默。
        runCatching {
            val line = "$PREFIX $op 退路 path=$path $detail"
            synchronized(last) {
                last.addLast(line)
                while (last.size > RING) last.removeFirst()
            }
            System.err.println(line)
        }
    }

    /** 最近的失败摘要（最新在后）；空 = 本会话没失败过。给单测与将来的 DevTools 命令读。 */
    fun snapshot(): List<String> = synchronized(last) { last.toList() }

    /** 清空环形缓冲与上报冷却表（单测用；生产不需要）。 */
    fun clear() {
        synchronized(last) { last.clear() }
        synchronized(alertStamps) { alertStamps.clear() }
    }

    private fun stackOf(error: Throwable): String {
        val sw = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}
