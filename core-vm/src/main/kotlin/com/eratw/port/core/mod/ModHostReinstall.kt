package com.eratw.port.core.mod

import com.eratw.port.modapi.EraMod

/**
 * `install` 循环里「重复装载同 packageId」闸的同会话重装分支（Lane DZ / task-123）。
 *
 * 放宽方向只限一种形态：**本次调用之前**就已登记、且已回滚失败的 entry（`failure != null`、
 * 未启用、未 shutdown），允许在同一条会话里被「摘掉旧条目 → 走正常装载路径新建条目」替换。
 * 其余重复装载保持既有硬失败语义与消息逐字不变：
 * - 批内重复（同一 `install` 调用的 `mods` 里同 id 出现两次）：第一项可能刚装成功、也可能刚
 *   失败，但这属于 registry 编程错误而非坏包降级 ⇒ 仍 `require` 硬失败、不替换、不产出失败
 *   报告项（见 [planModInstall] 契约与既有回归用例）。
 * - 正常装载中（`enabled == true`）或已收口（`shutDown == true`）的条目没有可替换语义：前者
 *   重复装载必炸（同 id 两套 context / 注册面会互相顶掉），后者要重来须整车重装。
 *
 * 回滚态 entry 的注册面在失败时已由 [revokeEntry] 撤过一次（onLoad/onEnable 失败走
 * `revokeFailedInstall`、读档拒载走 `rejectEntryOnRestore`），这里再撤一遍是幂等收口：
 * 撤服务发布、清本包文案、清空并逐个 unregister 注册项 —— 保证旧 context 不会借任何残留
 * 注册面在新条目装载期间复活。`storage` 段不在此处 deactivate：回滚态 entry 的段在失败
 * 回滚时已按 `storageWasActive` 对称退回（数据整段留下），随后正常装载的 [ModStorageStore.activate]
 * 会重新激活**同一段**，数据由此保留（不是新建空段）。
 *
 * 放文件级而不是宿主类内：宿主类 / 本文件的函数数都贴着 detekt `TooManyFunctions` 阈值，
 * 且该操作只读 `entries` 自身与 session 级撤销面，不引入第二份状态。
 *
 * @param packageId 本轮要装载的 packageId
 * @param entries 宿主已登记条目表（命中即摘除，未登记则直接返回）
 * @param preexistingIds 进入本次 `install` 循环**之前** `entries` 的 key 快照：用来区分
 *   「上次会话遗留的回滚态」与「本次批内刚建出的条目」（后者仍硬失败）
 * @param services 服务发布表（撤旧条目的服务面）
 * @param strings 本包三语文案表（清旧条目的文案面）
 */
internal fun openReinstallSlot(
    packageId: String,
    entries: MutableMap<String, ModRuntimeHost.Entry>,
    preexistingIds: Set<String>,
    services: ModServiceRegistryImpl,
    strings: ModStringsRegistry,
) {
    val existing = entries[packageId] ?: return
    require(packageId in preexistingIds && isRolledBack(existing)) {
        "packageId 重复装载：$packageId"
    }
    revokeEntry(existing, "重装替换回滚", RevokeFaces(services, strings))
    entries.remove(packageId)
}

/**
 * 回滚态判据：生命周期段失败过（失败原因保留在 entry 上供读数 / 依赖复查），未启用、未收口。
 *
 * 三个条件缺一不可：`failure` 是「失败过」的印记；`!enabled` 排除装成功后又走到重复装载；
 * `!shutDown` 排除已收口条目（[ModRuntimeHost.shutdown] 后要重来须整车重装）。
 */
private fun isRolledBack(entry: ModRuntimeHost.Entry): Boolean =
    entry.failure != null && !entry.enabled && !entry.shutDown

/**
 * 装载规划与「进入循环前的 `entries` key 快照」在同一处取。
 *
 * 两件事必须同一时刻发生：规划只看当前条目；快照用于区分「本次调用前已登记的遗留条目」与
 * 「本次批内刚建出的条目」（后者重复必须硬失败）。合成一个返回值还有一个工程约束：宿主
 * [ModRuntimeHost.install] 的 detekt `LongMethod` 正好压在阈值上，不能再多一行局部变量。
 *
 * @return first = 装载规划；second = 调用前的 `entries` key 快照
 */
internal fun planInstallWithEntrySnapshot(
    mods: List<Pair<String, EraMod>>,
    entries: Map<String, ModRuntimeHost.Entry>,
    storage: ModStorageStore,
): Pair<ModInstallPlan, Set<String>> {
    val preexistingIds = entries.keys.toSet()
    return planModInstall(mods, entries, storage) to preexistingIds
}
