package com.eratw.port.modapi

/**
 * mod 自持久化能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.6 / §8，wrap `SaveService` → `.rt2`）。
 *
 * mod **不直接动 `.sav`**、不在存档目录乱写文件；自持久化数据经本 API put/get/remove，底层统一
 * 进 `.rt2`，**按 PackageId 隔离**（每个 mod 一个分片）。保证卸载/更新/跨设备迁移/恢复有统一规则：
 * 禁用某 mod 读档时它的分片作为孤儿片逐字节透传（§8），重新启用无损恢复。
 *
 * ## 接线状态（诚实边界，K1 只冻结契约）
 *
 * 引擎 `SaveService`（core/save）已管 `.rt2` 的顺序不变量与分片合并（active/orphan），但把 mod
 * 的键值抽离成 `PackageChunk` 的生产面（M1-4/M4-3 抽离面）与运行期读写接线是 **K3 脏活**。
 * 本接口在 K1 冻结 mod 侧契约形状；生产适配器待 K3。
 *
 * ## 值形态
 *
 * K1 取**字符串键值**最小形态（mod 自行序列化复杂结构）；底层分片字节由引擎编码。clean 语义
 * （§18.7）：[get] 缺键返回 null（不抛、不复刻官方怪癖）；写操作只在转移边界经调度器放行。
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