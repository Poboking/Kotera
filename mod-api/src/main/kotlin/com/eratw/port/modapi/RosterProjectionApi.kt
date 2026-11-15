package com.eratw.port.modapi

/**
 * 原版角色槽投射的 mod 公开面（RT2 车次表 E 车 / P1-6 ①，era-arch-v61u.md §22.5 / §23.2 F2 / §7）。
 *
 * 扩展实体默认只活在 RT2 实体表里，原版 ERB 的 CHARA 数组看不见它们；[project] 把某个
 * [EntityId] 独占投射到一个原生角色行并注水，[unproject] 脱水后撤回该行。原版语义锚
 * `ADDVOIDCHARA` → `AddPseudoCharacter()`（伪角色尾部追加）与 `CHARANUM` 伪变量；
 * 投射只给活动小额集合，语料硬墙 200 / 301 一行不动。
 *
 * ## Mod 引用铁律（§7）
 *
 * [project] 与 [charIndexOfChecked] 返回的 charIndex 是**瞬时行号**：行排列/删除随时发生，
 * **严禁持久化**。跨帧跨天只存 [EntityId]；要用行号就先读 [rosterGeneration] 快照世代，
 * 再用 [charIndexOfChecked] 现换，世代过期返回 -1 就重新投射，不拿旧行号硬用。
 *
 * ## 装配状态（E 车）
 *
 * 真投射内核 `RealCharaSlotProjector`（M5-2 已收口）由宿主在 F 车（MR-2b-2）接线；
 * 宿主未注入时本面是**显式 fail-closed**：四个入口一律抛 [UnsupportedOperationException]
 * 并点名装配缺口，不返回静默的 -1 / false / 0 半装。关闸（无 mod）一个对象都不建。
 *
 * ## 面宽裁定
 *
 * 本面只给 mod 业务需要的四件入口；`projectedEntities` / `syncFromCharaSlot` /
 * `onCharacterRowsPermuted` / `entityAt` 属宿主与存档面，不进 SDK 公开面。
 *
 * TODO(verify)：本接口是移植侧 RT2 自建公开面，原版 Emuera 无对应 SDK 接口可比。
 * 卡点 = 无官方 SDK 可比，只能以 §23.2 F2 调用场景与真引擎纵向切片作依据；
 * 不得以本 KDoc 自述充当 C# 锚。投射**行为**语义锚上段所列原版指令与语料硬墙。
 */
interface RosterProjectionApi {

    /**
     * 登场：为 [id] 申请一个原生角色行并返回其 charIndex；已投射则幂等返回当前行号。
     *
     * 失败返回 -1（实体不存在 / 包短 ID 未注册）；**返回值为瞬时行号，禁止持久化**。
     * 尾部追加不动既有行号，因此本调用不递增 [rosterGeneration]。
     */
    fun project(id: EntityId): Int

    /**
     * 退场：脱水并撤回 [id] 占用的原生行，其余投射映射左移。未投射返回 false。
     * 删行会递增 [rosterGeneration]，此前快照的世代一律过期。
     */
    fun unproject(id: EntityId): Boolean

    /**
     * 行排列世代：仅在行排列/删除（含退场删行）时递增，尾部追加不递增。
     * mod 存它作为「缓存的行号是否还有效」的自检凭据，不拿它当业务时间。
     */
    val rosterGeneration: Long

    /**
     * 世代校验换 CHARA（§7 诚实四行判据第 3 行）：仅当 [expectedGeneration] 与当前
     * [rosterGeneration] 一致且 [id] 已投射时返回当前行号；世代过期或未投射返回 -1。
     * 返回值同为瞬时行号，禁止持久化。
     */
    fun charIndexOfChecked(id: EntityId, expectedGeneration: Long): Int
}
