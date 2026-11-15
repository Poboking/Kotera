package com.eratw.port.core.mod

import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler

/**
 * 驱动 [EraMod] 四段生命周期的生产宿主（RT2 波 C 车 C1，era-arch-v61u.md §18.4 / §12.1）。
 *
 * 固定时序 `onLoad(context) → onEnable() → …运行… → onDisable() → onUnload()`：
 * [install] 逐 mod 走前两段，[shutdown] 逐 mod 走后两段，[runAll] 把四段连起来供短生命周期
 * 与测试一次跑完。每个 mod 的 [ModContextImpl] 与 per-mod 注册项列表挂本 host 实例，
 * 无任何静态可变全局。
 *
 * 失败隔离（§18.4 / 裁定 §3.2.3）：任一 mod 在 onLoad 或 onEnable 抛异常，只该 mod 判失败、
 * 只回滚它自己已注册的全部项、失败原因进 logger 与 [ModRuntimeState] 报告；循环继续处理其余
 * mod，host 不整体 abort、不吞异常不留痕。失败 mod 在 shutdown 阶段的 onDisable / onUnload
 * 一律用异常隔离包住且由 shutDown 标志保证幂等，不二次抛。
 *
 * 构造段同在围栏内（W3-FAILCLOSE）：[storage] 激活、[ModContextImpl] 构造、Entry 登记任一环节
 * 抛异常，产出与 onLoad/onEnable 失败同形的失败报告项（active=false + 原因进日志面），循环继续、
 * 不整链 abort —— 构造期校验（如声明快照缺包的 require，ModContextImpl init）不再直穿 install，
 * 与 ModCarrierRegistry KDoc「不在快照内 = 逐 mod fail-closed（active=false 上日志面）」对齐。
 * 回退选择：storage 用既有对称操作 [ModStorageStore.deactivate]（只移出激活集、数据整段留下，
 * 与「禁用不等于删除」口径一致；构造前已激活的包——如读档 knownPackages——跳过回退，不把
 * 装前状态误改成禁用）。构造失败的 mod 未进生命周期（onLoad 没跑），不登记 entries，
 * shutdown 不走它的 onDisable / onUnload。边界：重复装载同 packageId 是 registry 编程错误
 * 而非坏包降级，require 硬失败 abort 整链，不产出失败报告项。
 *
 * 撤销语义（裁定 §3.2.4）：host 维护 per-mod 的 [Registration] 列表；[revoke] 逐个
 * unregister，单个抛错只记录不中断其余撤销，撤完 hook / phase / job / interaction / ui
 * 各引擎侧都不再命中。撤销只作用于传入的那一个 mod 条目，绝不触碰其它 mod。
 *
 * K3-B（RT2 波 G 车 G1）：构造可选的 [DecisionPipeline] 逐个透传给每个 [ModContextImpl]，使其
 * `actions` facade 成为决策管线「同包 facade」并经宿主的 per-mod 注册收集器纳入 onDisable
 * 撤销；缺省 null 时各 context 的 `actions` 是显式 fail-closed 桩，其余生命周期零行为不变。
 *
 * RT2 车次表 E 车：构造可选的 [ICharaSlotProjector] 逐个透传给每个 [ModContextImpl]，使其
 * `roster` facade 成为真投射桥的公开转发面；缺省 null 时是显式 fail-closed 桩。
 * **两车都只加可选参数，既有 `ModRuntimeHost(` 调用点零改动**；真投射器的生产构造点
 * （`RealCharaSlotProjector` + `VariableStore.charaIndexResolver`）属 F 车，不在本类接线。
 *
 * 真实主执行调度器、pkgShortId 分配、四引擎与 VM dispatch 的宿主注入点属「需组长接线」，
 * 本波用构造参数注入加测试 fake。
 */
class ModRuntimeHost(
    private val snapshot: DeclarationSnapshot,
    private val entityKernel: IEntityKernel,
    private val nativeMethods: NativeMethodRegistry,
    private val logSink: (String) -> Unit,
    private val runtime: RuntimeScheduler,
    private val shortIdOf: (String) -> Int = { 1 },
    private val intVarView: (String) -> Long = { 0L },
    private val strVarView: (String) -> String = { "" },
    private val decisionPipeline: DecisionPipeline? = null,
    /**
     * 会话级 mod 存储容器。宿主（存档侧）经它 [ModStorageStore.encode] / [ModStorageStore.load]，
     * 每个 mod 拿到的是绑到本包那一段的视图，跨包写不进去。缺省新建空容器 = 与今天等价的未接线形态。
     */
    val storage: ModStorageStore = ModStorageStore(),
    /** 真角色槽投射器（E 车公开面注入缝）；null = F 车未接线，roster 显式 fail-closed。 */
    private val projector: ICharaSlotProjector? = null,
) {
    private class Entry(
        val mod: EraMod,
        val context: ModContextImpl,
        val registrations: MutableList<Registration>,
    ) {
        var enabled: Boolean = false
        var failure: Throwable? = null
        var shutDown: Boolean = false
    }

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 逐 mod 装载并启用：为每个 mod 装配 [ModContextImpl]，把其注册项收集到本 mod 专属列表。
     * 失败围栏盖两段：构造段（[storage] 激活 → [ModContextImpl] 构造 → Entry 登记）与
     * onLoad/onEnable 段。任一 mod 失败只回滚它自己（含失败原因进日志面），产出同形失败报告项
     * （active=false），循环继续，不整体 abort。唯一硬失败例外：重复装载同 packageId 是
     * registry 编程错误而非坏包降级，require 当场 abort 整链、不产出失败报告项。
     */
    fun install(mods: List<Pair<String, EraMod>>): List<ModRuntimeState> {
        val report = ArrayList<ModRuntimeState>(mods.size)
        for ((packageId, mod) in mods) {
            require(packageId !in entries) { "packageId 重复装载：$packageId" }
            val registrations = ArrayList<Registration>()
            val storageWasActive = storage.isActive(packageId)
            val entry: Entry
            try {
                // 必须先显式激活：apiFor 只登记「完全未知」的包，被 deactivate 过的包带着数据在容器里，
                // 不激活就直接 fail-closed —— 这一行就是「禁用 → 重新启用」的接回点。
                storage.activate(packageId)
                val context = ModContextImpl(
                    packageId = packageId,
                    snapshot = snapshot,
                    entityKernel = entityKernel,
                    pkgShortId = shortIdOf(packageId),
                    nativeMethods = nativeMethods,
                    logSink = logSink,
                    runtime = runtime,
                    intVarView = intVarView,
                    strVarView = strVarView,
                    registrationSink = { registrations += it },
                    decisionPipeline = decisionPipeline,
                    storageStore = storage,
                    projector = projector,
                )
                entry = Entry(mod, context, registrations)
                entries[packageId] = entry
            } catch (t: Throwable) {
                // storage 回退 = 既有对称操作 deactivate：activate 的副作用是「建空段 + 进激活集」，
                // deactivate 只逆转后者、数据整段留下；装前已激活的包（读档 knownPackages）跳过，
                // 不把装前状态误改成禁用。此时还没有 context.logger（PackageLogger 在 ModContextImpl
                // 内装配且对本文件不可见），经 logSink 落同格式错误行，失败原因不吞不留痕。
                if (!storageWasActive) storage.deactivate(packageId)
                logSink(
                    "[$packageId][ERROR] 装配失败（构造段），本 mod 不进入生命周期 " +
                        "cause=${t::class.java.simpleName}:${t.message}",
                )
                report += ModRuntimeState(packageId, active = false, failure = t)
                continue
            }
            try {
                mod.onLoad(entry.context)
                mod.onEnable()
                entry.enabled = true
            } catch (t: Throwable) {
                entry.failure = t
                entry.context.logger.error("onLoad 或 onEnable 失败，仅回滚本 mod 已注册项", t)
                revoke(entry, "安装失败回滚")
            }
            report += ModRuntimeState(packageId, entry.enabled, entry.failure)
        }
        return report
    }

    /**
     * 关闭全部 mod：先批量撤销各自注册项，再走 onDisable → onUnload。
     * 两段回调都用异常隔离包住（失败 mod 不二次抛），shutDown 标志保证二次调用幂等。
     */
    fun shutdown(): List<ModRuntimeState> {
        val report = ArrayList<ModRuntimeState>(entries.size)
        for ((packageId, entry) in entries) {
            if (!entry.shutDown) {
                revoke(entry, "onDisable 批量撤销")
                try {
                    entry.mod.onDisable()
                } catch (t: Throwable) {
                    entry.context.logger.warn(
                        "onDisable 异常已隔离（不二次抛）：${t::class.java.simpleName}: ${t.message}",
                    )
                }
                try {
                    entry.mod.onUnload()
                } catch (t: Throwable) {
                    entry.context.logger.warn(
                        "onUnload 异常已隔离（不二次抛）：${t::class.java.simpleName}: ${t.message}",
                    )
                }
                entry.shutDown = true
            }
            report += ModRuntimeState(packageId, entry.enabled, entry.failure)
        }
        return report
    }

    /** 一次跑完四段；[duringRun] 是运行期观察钩子，便于钉住撤销前后引擎命中差异。 */
    fun runAll(
        mods: List<Pair<String, EraMod>>,
        duringRun: () -> Unit = {},
    ): List<ModRuntimeState> {
        install(mods)
        duringRun()
        return shutdown()
    }

    /** 取某 mod 的装配 context（供宿主 dispatch 接线与测试观察）。 */
    fun contextOf(packageId: String): ModContext? = entries[packageId]?.context

    /**
     * 读档恢复后把全部已装 context 的 storage 视图重绑到重建容器 [newStore]（RT2 D 车）。
     *
     * 触发口径：只由读档缝（ModRuntimeSession.adoptStorage）同步调用——同一 VM 线程、
     * 同一同步块内「换会话容器 + 逐 context 重绑」两步完成，中间无观察点 ⇒
     * 「容器已换而 context 未重绑」的状态不可达，这是判据 3 的取舍：原子换而非暂态拒绝。
     * 未恢复前初值视图行为逐值不变。
     *
     * 逐包委托 [ModContextImpl.rebindStorage]：孤儿段保持 fail-closed，新装 mod 登记空段，
     * 不触发激活集变更、不删除任何数据（「禁用不等于删除」在重绑后仍逐字节成立）。
     */
    fun rebindStorage(newStore: ModStorageStore) {
        for (entry in entries.values) {
            entry.context.rebindStorage(newStore)
        }
    }

    /** 取某 mod 的安装失败原因；未失败返回 null。 */
    fun failureOf(packageId: String): Throwable? = entries[packageId]?.failure

    /**
     * 撤销单个 mod 的全部注册项：委托 [revokeAll] 逐个撤销，单个抛错不中断其余但要记录。
     * 只处理传入的 entry，绝不触碰其它 mod（双 mod 失败隔离的回滚面）。
     */
    private fun revoke(entry: Entry, reason: String) {
        val pending = entry.registrations.toList()
        entry.registrations.clear()
        revokeAll(pending) { t ->
            entry.context.logger.warn(
                "$reason：单个 unregister 抛错已记录并跳过，继续撤销其余项" +
                    "（${t::class.java.simpleName}: ${t.message}）",
            )
        }
    }

    /**
     * 逐个撤销注册项：单个 unregister 抛错只经 [onError] 记录，不中断其余撤销。
     * 列表由调用方按 per-mod 传入，本方法不持有任何静态状态。
     */
    fun revokeAll(registrations: List<Registration>, onError: (Throwable) -> Unit) {
        for (registration in registrations) {
            try {
                registration.unregister()
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }
}

/** 单 mod 的终局状态：是否启用成功、失败原因（进交付报告，不吞异常）。 */
data class ModRuntimeState(
    val packageId: String,
    val active: Boolean,
    val failure: Throwable?,
)
