package com.eratw.port.core.mod

import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.RosterProjectionApi

/**
 * [RosterProjectionApi] 的引擎侧适配器（RT2 车次表 E 车 / P1-6 ①，era-arch-v61u.md §22.5 / §23.2 F2）。
 *
 * 只做 [EntityId] 的 `raw` 与内核 Long 位域之间的转换，然后**原样转发** [ICharaSlotProjector]：
 * 不自建映射缓存、不重写投射判定、不改世代语义、不吞失败哨兵（[project] 失败 -1、
 * [unproject] 失败 false 都原样回给 mod）。真判定全在 M5-2 已收口的
 * `RealCharaSlotProjector` 里，本车一行不动 core-vm 的 entity 包。
 *
 * ## 未注入 = 显式 fail-closed
 *
 * [projector] 为 null 时四个入口（含 [rosterGeneration] 读）一律抛
 * [UnsupportedOperationException]，消息点名 F 车装配缺口与 packageId：
 * **不返回静默的 -1 / false / 0**，不让 mod 在半装状态下以为「投射没成功但也没坏」。
 * 这与 `FailClosedActionApi` / `EmptyCharaSlotProjector.project` 的既有口径一致，
 * 不是让错误不再出现的开关（AGENTS §3.1）。既有 `ModRuntimeHost(` 构造点缺省 null，
 * 因此不调用本面时生命周期与既有测试零行为差。
 *
 * TODO(verify)：适配器本身是移植侧 RT2 自建公开面，原版 Emuera 无对应物可比；
 * 卡点 = 无官方 SDK 可比，只能以 §23.2 F2 调用场景与真引擎纵向切片作依据。
 * 投射行为语义锚 `ADDVOIDCHARA` / `CHARANUM` 与语料 200 / 301 硬墙。
 *
 * @param projector 宿主注入的真投射器；null = F 车尚未接线（MR-2b-2）。
 * @param packageId 本 mod 的包身份，仅用于 fail-closed 诊断文案，不参与任何判定。
 */
class KernelRosterProjectionApi(
    private val projector: ICharaSlotProjector?,
    private val packageId: String,
) : RosterProjectionApi {

    override fun project(id: EntityId): Int = requireProjector().project(id.raw)

    override fun unproject(id: EntityId): Boolean = requireProjector().unproject(id.raw)

    override val rosterGeneration: Long get() = requireProjector().rosterGeneration

    override fun charIndexOfChecked(id: EntityId, expectedGeneration: Long): Int =
        requireProjector().charIndexOfChecked(id.raw, expectedGeneration)

    private fun requireProjector(): ICharaSlotProjector = projector ?: throw UnsupportedOperationException(
        "真投射器未装配：packageId=$packageId（F 车 MR-2b-2 需把 RealCharaSlotProjector 与 " +
            "VariableStore.charaIndexResolver 接进生产构造点）；roster 不提供静默 -1/false/0 半装",
    )
}
