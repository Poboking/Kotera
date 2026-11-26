package com.eratw.port.modapi

/**
 * 受授权游戏状态读写面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3，wrap `VariableStore` 的
 * curated facade；ModDX4-B1 起读面全量 + 写面受控）。
 *
 * mod 经本 API **读**游戏状态（全局变量等），不直接持有内部 `VariableStore` 实现对象（§18.2 铁边界）。
 * 读侧是**全量按名只读视图**（ModDX4-B1 订正：生产装配 = 按名只读投影，无「curated 白名单」
 * —— 历史注释里「受授权白名单随 K3 收口」的说法与生产不符，K1 起生产读缝就是全量按名视图，
 * 存在性探测 v15 已落）。
 *
 * ## 写面（v20，ModDX4-B1：受控写）
 *
 * [setIntVar] / [setStrVar] 开放**两条**写通道，其余原版键 fail-closed：
 *
 * 1. **本包声明的变量**：manifest `global-dims`（[ModManifest.globalDims]）声明的全局变量，
 *    免授权直接写；
 * 2. **原版全局变量的受控写**：manifest `write-grants`（[ModManifest.writeGrants]）逐键声明的
 *    白名单，键级授权 + 类型一致（INT 授权只走 [setIntVar]，STR 授权只走 [setStrVar]）。
 *
 * 未授权的键写 = 当场抛 [IllegalArgumentException]（fail-closed，点名 packageId 与键，
 * 文案对齐 [SettingsApi] 的未声明键口径）；**不静默吞写** —— 静默失败会让作者以为写生效了。
 * 角色变量写不在 v1（charaRoutes 读已通；写面另立后续波）。
 *
 * ## 写通道语义（经转移边界排队）
 *
 * 写**不是即时落库**：两个 setter 都经 [RuntimeScheduler.submit] 排队，在 ERB 主执行线程的
 * 转移边界放行（§18.5，`ModContext.runtime` 的延迟 FIFO 语义）——边界前读不到新值、边界后
 * 读得到（时序判据，生产兑现点 = 页边界 `onTurnBoundary` 的 drain）。授权/类型闸在**调用点**
 * 当场判（fail-fast，异常栈指到作者代码；排进队列再判会让失败被边界隔离吞成日志行）。
 * 与 ERB 写同权、运行期不加闸：同一变量被 ERB 与 mod 各写一笔时按边界内的兑现序生效。
 *
 * 存档语义：写的原版全局变量落 `.sav` 本体（变量本在 `.sav`，零新格式面）；mod 自家
 * globalDims 变量落既有 `.rt2` global 分片。两路都不新增存档格式段。
 *
 * clean 语义（§18.7）：读返回当前值的快照（不可变），缺变量返回干净哨兵（int 0 / str ""），
 * 不复刻官方 VM 怪癖；后台线程可读不可变快照、可提交写（排队），改状态仍须经
 * [RuntimeScheduler.submit] 口径（本面 setter 已内置排队，作者不必再包一层 submit）。
 */
interface GameStateApi {
    /** 读一个整型全局变量；缺变量返回 0。 */
    fun getIntVar(name: String): Long

    /** 读一个字符串全局变量；缺变量返回 ""。 */
    fun getStrVar(name: String): String

    /**
     * 探测一个整型全局变量是否存在（本视图可读）。
     *
     * 用途 = 拼错变量名的可观测性：[getIntVar] 缺变量静默返回 0 的哨兵语义**不变**（K1 冻结契约），
     * 作者无法从返回值区分「值就是 0」与「名字拼错了」；本方法补上这一问。`true` 时
     * [getIntVar] 返回真实读数，`false` 时返回哨兵 0。
     *
     * 名字归一口径与 [getIntVar] 相同（大小写不敏感）。生产宿主未接线存在性供给时返回
     * false（空集口径，与台架形态一致），不抛。
     */
    fun hasIntVar(name: String): Boolean

    /**
     * 探测一个字符串全局变量是否存在（本视图可读）；口径同 [hasIntVar]。
     *
     * `true` 时 [getStrVar] 返回真实读数，`false` 时返回哨兵 ""。
     */
    fun hasStrVar(name: String): Boolean

    /**
     * 写一个整型全局变量（v20 受控写，ModDX4-B1）：排队到转移边界，**不即时落库**。
     *
     * 授权闸（调用点当场判）：本包 `global-dims` 声明的变量，或 `write-grants` 里声明为 INT
     * 的原版键；未授权 / 类型不符 / 宿主未装配写面（缺省实现，本方法抛
     * [UnsupportedOperationException] 的那一路）当场抛，不静默吞写、不进队列。
     *
     * 寻址口径与读侧同形：0 维标量写本体、1 维非角色数组写 0 号元素（ERB 裸引用同形）；
     * 角色变量与 2 维以上声明期即拒（坏 grant），不进运行期。
     */
    fun setIntVar(name: String, value: Long): Unit =
        throw UnsupportedOperationException(
            "gameState.setIntVar($name) 未接线：当前 GameStateApi 实现没有写面供给" +
                "（台架镜像 / 旧装配路径）；不静默吞写",
        )

    /**
     * 写一个字符串全局变量（v20 受控写）；排队与授权口径同 [setIntVar]，
     * 授权须为 `write-grants` 里声明为 STR 的原版键。
     */
    fun setStrVar(name: String, value: String): Unit =
        throw UnsupportedOperationException(
            "gameState.setStrVar($name) 未接线：当前 GameStateApi 实现没有写面供给" +
                "（台架镜像 / 旧装配路径）；不静默吞写",
        )
}
