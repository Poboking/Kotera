package com.eratw.port.core.save

import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.vars.ModCsvInjectionPlan
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path

/**
 * 一次槽档抓取的分片分组：激活分片与孤儿分片（`SidecarSaveBundle` 的容器口径）。
 *
 * - [activeChunks]：本次激活 Package 的新分片（重新抓取，SaveService 语义）；
 * - [orphanChunks]：未激活 Package 的旧分片（逐字节透传，容器层不解释内容）。
 *
 * 同一 packageId 不得同时出现在两组 —— [SaveService.captureBundle] 的合并闸会当场拒绝，
 * 这里在类型上就把两组分开，让宿主分类错误在抓取入口而不是事务中途暴露。
 */
class SidecarChunkGroups(
    val activeChunks: List<PackageChunk>,
    val orphanChunks: List<PackageChunk>,
)

/**
 * 一次 `.rt2` 恢复的读数。
 *
 * [applied] = 绑定吻合且分段已交还宿主（false = 绑定失配 / 容器损坏 / 恢复中途失败）；
 * [csv] = **已激活包**的 Mod CSV 注入值（未激活包的片原样透传，其值不在此列 ——
 * 口径见 [ModCsvValueCodec.mergeApplied]）。`applied = false` 时恒为空袋。
 */
class SidecarRestoreResult(
    val applied: Boolean,
    val csv: ModCsvValueBag = ModCsvValueBag.EMPTY,
    /** true = 端口已在同一 prepare/commit 内应用 CSV；FileSystemOps 不再二次写入。 */
    val csvApplied: Boolean = false,
)

/**
 * mod 存储侧车的宿主端口（RT2 C 车，era-arch-v61u.md §8 / §23.4 C 行）。
 *
 * core 只负责「.sav 与 .rt2 的配对事务、绑定校验时机、缺失/损坏的 fail-closed 警告」；
 * 分片从哪来、恢复后交还给谁，由宿主（app 装配侧）实现本端口回答。这样 split 的理由：
 * [com.eratw.port.core.mod.ModStorageStore] 与 [com.eratw.port.core.entity.EntityKernel]
 * 的会话实例都在装配侧持有，core 不为取它们再造第二条装配路。
 *
 * 「把分段交还给 mod 侧」（context 视图重绑）是 D 车余量 —— 本端口只要求交还给
 * **宿主**（`ModRuntimeSession.adoptStorage`），mod context 持有的旧 `StorageApi` 视图
 * 在读档后如何接上新容器不在本车边界内（交付报告「需组长接线」登记）。
 */
interface SidecarStoragePort {

    /**
     * 抓取本槽的 mod 分片组（激活 + 孤儿）。保存路径每次调一次。
     *
     * @param csv 本轮采集到的 Mod CSV 注入值（按 packageId 分组后要塞进各自**激活**分片）；
     *   空袋时实现必须让分片载荷与改动前逐字节一致（[ModCsvValueCodec.encode] 的空袋短路口径）。
     */
    fun captureChunks(csv: ModCsvValueBag = ModCsvValueBag.EMPTY): SidecarChunkGroups

    /**
     * 抓取 .rt2 容器字节：宿主实现内部经 [SaveService.captureBundle] +
     * `SidecarSaveBundle.encode()`（mod 存储 `encode()` 的字节在分片载荷里随容器走）。
     */
    fun captureRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray

    /**
     * 读档恢复：解码 [bytes] → [SaveService.restoreBundle] → 分段交还宿主。
     *
     * 返回 [SidecarRestoreResult]：`applied = true` = Applied；false = 绑定失配 / 容器损坏 /
     * 恢复中途失败（实现必须同时经 [warn] 留下可定位文案，core 不再二次解释）。
     * [SidecarRestoreResult.csv] 只含**已激活包**的 Mod CSV 注入值。
     */
    fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult

    /**
     * 警告面（fail-closed 的出口）：.rt2 缺失 / 损坏 / 绑定失配时必须留痕，不静默丢。
     * 生产实现 = 日志面（取证面，非玩家可见文案）。
     */
    fun warn(message: String)
}

/** 显式跨设备恢复能力；同设备恢复仍只走 [SidecarStoragePort] 的严格冲突路径。 */
interface CrossDeviceSidecarStoragePort : SidecarStoragePort {
    fun restoreRt2(bytes: ByteArray, binding: SidecarBinding, crossDevice: Boolean): SidecarRestoreResult
}

/**
 * 存档侧车接线的会话级参数（RT2 C 车）：mod 分片端口 + 配对事务的磁盘根。
 *
 * 挂 `store.aux` 的理由与 [SaveWiring] 同一条：装配点唯一在 app 装配链
 * （[com.eratw.port.android.ModRuntimeAssembly.attach]，总闸开态），而消费点
 * [FileSystemOps] 由会话装配先行构造 —— aux 槽是两边都看得见、且**晚装也能现读**的唯一面
 * （装配序：GameSession 先建 FileSystemOps，GameHost 后调 attach；语句层每次现读，
 * 迟到的 install 对下一次存/读档即生效）。总闸关态不 install ⇒ [of] 返回 null ⇒
 * [FileSystemOps] 的存/读档路径与未接线前逐字节相同（缺省路径零变化的机制保证）。
 *
 * @property port mod 分片的抓取/恢复端口（实现 = app 装配侧，持有 SaveService 与存储容器）
 * @property transactionRoot 配对事务（journal/.part/.bak/目标）的磁盘根；生产 = 游戏根
 *   （与 `RootedSaveFileIo` 同一根，相对路径口径一致：`config.normalPath` 直拼）
 * @property csvPlan Mod 注入 CSV 位的计划（[ModCsvInjectionPlan]）；
 *   null = 未接线 ⇒ 不采集、不截断 `.sav`、不应用 `.rt2` 里的 CSV 段（字节与改动前一致）
 * @property csvKeywordIndex 存档期关键字 → 当下注册表下标的查询面（生产 = 冻结快照的
 *   `csvKeywordIndex`）；与 [csvPlan] 同为 null 才全链 no-op
 */
class SidecarSaveWiring(
    val port: SidecarStoragePort,
    val transactionRoot: Path,
    val csvPlan: ModCsvInjectionPlan? = null,
    val csvKeywordIndex: ((table: String, keyword: String) -> Int?)? = null,
) {

    companion object {

        /** aux 槽名（约定：能力家族前缀 + 小写，见 [RuntimeAux] 类 KDoc）。 */
        const val AUX_SLOT: String = "save.sidecar"

        /**
         * 延后孤儿 GC 的 aux 槽名（RT2 MODGC 裁定 2）：[FileSystemOps] 构造早于本接线安装，
         * 迁移面在构造期读不到接线（[of] 恒 null）；把「接线到位后再清孤儿」登记在这里，
         * 由 [install] 安装成功后执行一次。
         */
        const val PENDING_GC_SLOT: String = "save.sidecar.gc.pending"

        /** 现读；未装配（总闸关 / 纯单测）返回 null。 */
        fun of(store: VariableStore): SidecarSaveWiring? = store.aux.peek(AUX_SLOT)

        /**
         * 登记一条**一次性**的孤儿 GC 待办（RT2 MODGC 裁定 2）。
         *
         * 调用面 = [FileSystemOps.init] 在迁移结局为 `Completed` / `MoveFailed` 且接线尚未
         * 安装时。同一会话可能构造多个 [FileSystemOps]（重建会话），故待办按**列表**累积；
         * [install] 成功后整表执行一次并清空，重复 install 不再扫盘。
         * 接线在位时调用方不走本方法（迁移面当场已清），关态（永不 install）⇒ 一次都不扫。
         */
        fun deferOrphanGc(store: VariableStore, action: () -> Unit) {
            store.aux.slot(PENDING_GC_SLOT) { PendingOrphanGc() }.add(action)
        }

        /**
         * 装配（只建一次；重复调用保留先装的那份，口径同 [SaveWiring.install]）。
         *
         * 装配成功后把 [deferOrphanGc] 登记的待办执行一遍（**仅一次**）：此时 [of] 已非 null，
         * 待办内的删除动作与「接线在位」时逐条同源（[SaveFolderMigration.gcOrphanSidecarCompanions]）。
         */
        fun install(store: VariableStore, wiring: SidecarSaveWiring): SidecarSaveWiring {
            val installed = store.aux.slot(AUX_SLOT) { wiring }
            store.aux.peek<PendingOrphanGc>(PENDING_GC_SLOT)?.drain()
            return installed
        }
    }
}

/**
 * [SidecarSaveWiring.deferOrphanGc] 的待办表（RT2 MODGC 裁定 2）。
 *
 * 一次性语义 = [drain] 置空后再调即空操作：装配序里 [SidecarSaveWiring.install] 可能被
 * 重复调用（同会话重建），重复扫盘会把「玩家新放回的孤儿件」误删 —— 那已不是迁移期的
 * 残留，不在本待办的授权范围内。
 */
private class PendingOrphanGc {

    private val actions = ArrayList<() -> Unit>(1)

    /** 登记一条待办；同一会话多次构造 [FileSystemOps] 会各登记一条（各自针对自己的 io 根）。 */
    fun add(action: () -> Unit) {
        actions += action
    }

    /** 执行并清空全部待办；已清空 ⇒ 空操作（一次性）。 */
    fun drain() {
        if (actions.isEmpty()) return
        val pending = actions.toList()
        actions.clear()
        for (action in pending) runCatching(action)
    }
}
