package com.eratw.port.modapi

/**
 * 定时/周期任务能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §20.2 / §21.1 / ABI-10 Candidate）。
 *
 * 两条任务形态共用本 facade：
 *
 * - **静态任务槽**（[ModManifest.jobs]）：`jobId + 相位 + 首次触发日 + 周期 + priority` 经
 *   Manifest 声明、进声明事务；运行期 [bind] 把处理器绑到已声明槽。
 * - **动态任务实例**（[ModManifest.jobTemplates]）：模板声明补算策略；运行期
 *   [scheduleOnce] 按模板创建实例（实例键 = `packageId::templateKey#序号`），[cancel] /
 *   [reschedule] 幂等操作。
 *
 * 引擎按「游戏日到达 + 相位匹配」派发，确定性排序 = priority 升序 + 链接声明序；任务态
 * （静态下次触发日 + 动态实例 + 实例序号表）随 `.rt2` 按 PackageId 隔离持久化（§8），
 * 重启/读档恢复。
 *
 * ## 边界
 *
 * - 引擎只管时钟/排序/持久化/失败隔离，**任务业务归 Mod**（引擎不认识任务名含义）。
 * - 模板未声明 = 运行期拒绝（fail-closed：运行期不新增声明结构）。
 * - 游戏时钟触发 ≠ [RuntimeScheduler.submit]（线程转移边界，§18.5）。
 * - 静态周期任务跳过「错过的周期」而不是补跑；动态实例按模板补算策略三档处理
 *   （`MISS_ONCE` 目标日补一次 / `MISS_SKIP` 错过作废 / `REPLAY` 逐错过日补、按
 *   `maxCatchUp` 截断并报 `CATCHUP_TRUNCATED`）。
 *
 * clean 语义（§18.7）：未声明任务绑定 = 拒绝；任务处理器失败按 Mod 隔离（§18.4），
 * 不拖垮其余任务。
 *
 * ## 接线边界（§12.1，2026-11-19 现状订正：生产已接线）
 *
 * 生产已接线：core-vm 的 `JobApiAdapter` 四方法全覆写并委托引擎 `JobScheduler`——调度由
 * `ModRuntimeAssembly` 的 `GameTickDriver(phases, jobs)` 生产节拍驱动，读档经
 * `restoreJobState` 回灌。本接口的四个缺省实现 = **未注入适配器实例**（台架 / 直用形态）
 * 的显式 fail-closed 兜底，不伪造第二套调度表。
 */
interface JobApi {
    /**
     * 把处理器绑定到已声明的静态任务槽（本包内 [jobId] 唯一）。
     *
     * @throws IllegalArgumentException 任务未声明、或同一 jobId 重复绑定
     */
    fun bind(jobId: String, handler: JobHandler): Registration

    /**
     * 把处理器绑定到已声明的任务模板（模板键本包内唯一，与 [jobId] 不共享命名空间）。
     *
     * @throws IllegalArgumentException 模板未声明、或同一模板重复绑定
     */
    fun bindTemplate(templateKey: String, handler: JobHandler): Registration =
        throw UnsupportedOperationException(
            "bindTemplate 缺省 fail-closed 兜底：当前实例未注入引擎 JobScheduler 适配 " +
                "（生产宿主经 JobApiAdapter 注入；直用须覆写本方法注入 packageId 后委托 " +
                "JobScheduler.bindTemplate）",
        )

    /**
     * 按已声明模板创建一个一次性动态实例。
     *
     * @return 实例键（`packageId::templateKey#序号`），供 [cancel]/[reschedule] 使用
     * @throws IllegalArgumentException 模板未声明，或 [atDay] 为负
     */
    fun scheduleOnce(templateKey: String, atDay: Long, payloadRef: EntityId? = null): String =
        throw UnsupportedOperationException(
            "scheduleOnce 缺省 fail-closed 兜底：当前实例未注入引擎 JobScheduler 适配 " +
                "（生产宿主经 JobApiAdapter 注入；直用须覆写本方法注入 packageId 后委托 " +
                "JobScheduler.scheduleOnce）",
        )

    /**
     * 取消一个动态实例。不存在 / 已完成 / 已取消 ⇒ false（幂等，不抛异常）。
     */
    fun cancel(instanceKey: String): Boolean =
        throw UnsupportedOperationException(
            "cancel 缺省 fail-closed 兜底：当前实例未注入引擎 JobScheduler 适配 " +
                "（生产宿主经 JobApiAdapter 注入；直用须覆写本方法注入 packageId 后委托 " +
                "JobScheduler.cancel）",
        )

    /**
     * 改期一个待触发的动态实例。不存在 / 已完成 / 已取消 ⇒ false；同 day ⇒ true 且不改。
     *
     * @throws IllegalArgumentException [atDay] 为负
     */
    fun reschedule(instanceKey: String, atDay: Long): Boolean =
        throw UnsupportedOperationException(
            "reschedule 缺省 fail-closed 兜底：当前实例未注入引擎 JobScheduler 适配 " +
                "（生产宿主经 JobApiAdapter 注入；直用须覆写本方法注入 packageId 后委托 " +
                "JobScheduler.reschedule）",
        )
}

/**
 * 任务回调上下文。
 *
 * 静态槽回调只有 [packageId]/[jobId]/[time]；动态实例回调额外携带 [templateKey] /
 * [instanceKey] / [payloadRef]（默认 null，不破既有位置构造）。
 */
class JobContext(
    val packageId: String,
    val jobId: String,
    val time: GameTime,
    /** 动态实例的模板键；静态槽回调为 null。 */
    val templateKey: String? = null,
    /** 动态实例键（`packageId::templateKey#序号`）；静态槽回调为 null。 */
    val instanceKey: String? = null,
    /** 实例创建时登记的 Mod 载荷引用；引擎不解释内容。 */
    val payloadRef: EntityId? = null,
)

/** 一个任务处理器。 */
fun interface JobHandler {
    fun run(context: JobContext)
}
