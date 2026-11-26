package com.eratw.port.modapi

/**
 * mod 自持久化能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.6 / §8，wrap `SaveService` → `.rt2`）。
 *
 * mod **不直接动 `.sav`**、不在存档目录乱写文件；自持久化数据经本 API put/get/remove，底层统一
 * 进 `.rt2`，**按 PackageId 隔离**（每个 mod 一个分片）。保证卸载/更新/跨设备迁移/恢复有统一规则：
 * 禁用某 mod 读档时它的分片作为孤儿片逐字节透传（§8），重新启用无损恢复。
 *
 * ## 接线状态（诚实边界）
 *
 * 引擎 `SaveService`（core/save）已管 `.rt2` 的顺序不变量与分片合并（active/orphan）；运行期读写
 * 已接线到会话内存容器（按包隔离的段，「禁用不等于删除」语义闭合）。`.rt2` 字节落盘与读档恢复的
 * 生产构造点仍在接线中：容器保证语义层可往返，盘上往返未闭合 —— mod 侧读写口径不受影响。
 *
 * ## 值形态
 *
 * 取**字符串键值**最小形态（mod 自行序列化复杂结构）；底层分片字节由引擎编码。clean 语义
 * （§18.7）：[get] 缺键返回 null（不抛、不复刻官方怪癖）。写操作即时落进本会话的内存段，
 * 落盘跟随宿主存档节奏 —— 调用点不做调度排队。
 *
 * ## 异常契约（实现侧 fail-closed 口径）
 *
 * - 包未激活（宿主停用后、重新启用前）：四个入口一律抛 [IllegalStateException] —— 禁用的 mod
 *   不得读写自己的段，也不得被静默启用。「[get] 缺键返回 null / [keys] 快照」只在已激活时成立。
 * - [put] / [remove] 的键以宿主保留前缀 `__eratw.` 开头：抛 [IllegalArgumentException]（该命名
 *   空间归宿主记账专用，作者写入按非法入参当场暴露）；读侧（[get] / [keys]）不隐藏保留键。
 */
interface StorageApi {
    /** 写入/覆盖一个键（按本 mod 的 PackageId 隔离）。 */
    fun put(key: String, value: String)

    /** 读一个键；缺键返回 null。 */
    fun get(key: String): String?

    /** 删一个键；缺键返回 false。 */
    fun remove(key: String): Boolean

    /** 本 mod 当前持有的键集合（快照，mod 可自由持有）。 */
    fun keys(): Set<String>
}