package com.eratw.port.modapi

/**
 * 作者声明过的 per-mod 设置项的**只读访问面**（RT2 per-mod 配置）。
 *
 * ## 值的来源（谁注入、何时注入）
 *
 * 宿主在 `onLoad` **之前**把「本包设置快照」注入 context（装配细节在引擎侧 `adoptSettings`，
 * 不进本公开 ABI）。快照 = 声明清单（来自 Manifest `settings` 段）加设备侧按包配置文件里
 * 已持久化的值；未改过的键回退到声明里的默认值。**设置是跨存档、跨局保留的设备级配置**
 * （不是存档内数据），所以它不随读档回滚，也不进 `.rt2` 分片。
 *
 * ## 读语义（三条都必须成立，缺一就是静默失真）
 *
 * 1. 只能读**本包声明过**的键：未声明的键 → [IllegalArgumentException]（点名 packageId 与键）。
 * 2. 类型必须与声明一致：拿 `int` 去读 `STR` 项 → [IllegalArgumentException]，不隐式转。
 * 3. 持久化值坏掉（文件损坏 / 不是数字 / 越界 / 枚举不在候选里）→ 回退声明默认值，
 *    **不崩溃、不返回半成品**。
 *
 * ## 写面（v1 有意不给）
 *
 * 设置值的写入点是设置页（UI 线程，按包原子写文件）；VM 线程上的 mod 只读快照。给一个
 * 「运行期改设置」的写面意味着 mod 在 VM 线程落盘或改一份与宿主不同的内存副本，两者都会
 * 让「设置页显示的值」和「mod 生效的值」分叉 —— 那正是本面要消灭的失真。要改值：玩家在
 * 设置页改，下次 attach 后本面读到新快照；作者需要「运行期可变状态」请用 [StorageApi]
 * （进存档）或自己声明变量。
 *
 * ## 未注入时 fail-closed
 *
 * 宿主没有注入快照（直接构造的 context、旧装配路径）时本面整体抛
 * [UnsupportedOperationException]，**不返回声明默认值** —— 静默默认值会让作者以为设置生效了，
 * 而玩家改的值根本没被读到。
 *
 * 只在 VM 线程读；不持有 `SharedPreferences`、不碰 Android API（铁律：非主线程不直接读
 * SharedPreferences；本面拿到的只是宿主注入的不可变快照）。
 */
interface SettingsApi {

    /** 本 mod 的包身份（快照归属；与本面其它 API 同口径）。 */
    val packageId: String

    /** 本包声明的全部设置项键（声明序；设置页渲染顺序与它一致）。 */
    val keys: Set<String>

    /** [key] 是否被本包声明过（读未声明的键会抛错，所以这是唯一无异常的存在性探测）。 */
    fun has(key: String): Boolean

    /** 读整数设置项；未声明 / 声明类型不是 [SettingKind.INT] 当场抛 [IllegalArgumentException]。 */
    fun int(key: String): Long

    /** 读字符串设置项；未声明 / 类型不符当场抛。 */
    fun str(key: String): String

    /** 读布尔设置项；未声明 / 类型不符当场抛。 */
    fun bool(key: String): Boolean

    /** 读枚举设置的**候选原文**；未声明 / 类型不符当场抛。 */
    fun enumName(key: String): String
}
