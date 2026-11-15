package com.eratw.port.core.save

import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path

/**
 * global.rt2 侧车的宿主端口（RT2 C-RES1，era-arch-v61u.md §8 持久化域矩阵 GLOBAL 行）。
 *
 * 与槽档 [SidecarStoragePort] 分开成第二个端口而不是给它加方法，理由有三：
 * 1. **内容不同**：global.rt2 走 [SaveService.captureGlobalBundle]（实体载荷恒空、
 *    虚拟 NO 恒空），槽档走 [SaveService.captureBundle]——同一个方法名下塞两个
 *    抓取语义会让实现者猜「到底喂哪份」；
 * 2. **恢复不同**：global 恢复走 [SaveService.restoreGlobalBundle]（只验绑定、只交还
 *    分片，不并注册表、不灌内核），槽档恢复走 [SaveService.restoreBundle]（并注册表 +
 *    灌实体）。两者共用一个方法只会逼实现里塞分支；
 * 3. **装配面不同**：槽档端口与 global 端口在 app 装配侧可以同源（同一个
 *    [com.eratw.port.core.mod.ModStorageStore]、同一份 SaveService），但生命周期
 *    判据不同（SAVEGLOBAL/LOADGLOBAL 与 SAVEGAME/LOADGAME 是两条独立语句链），
 *    测试与假体各自独立替换。
 *
 * core 只负责「global.sav 与 global.rt2 的配对事务、绑定校验时机、缺失/损坏的
 * fail-closed 警告」；分片从哪来、恢复后交还给谁，由宿主（app 装配侧）实现本端口回答
 * （与 [SidecarStoragePort] 同一条 split 纪律：ModStorageStore 与 SaveService 的会话
 * 实例都在装配侧持有，core 不为取它们再造第二条装配路）。
 */
interface GlobalSidecarStoragePort {

    /**
     * 抓取 global.rt2 的 mod GLOBAL 分片组（激活 + 孤儿）。SAVEGLOBAL 路径每次调一次。
     *
     * GLOBAL 域的 Mod 载体（PersistenceDomains.routeOf：Mod + GLOBAL → GLOBAL_RT2）今天
     * 物理上就是会话级 ModStorageStore 本身——mod 代码经 `context.storage` 写的每包一段，
     * SAVEGLOBAL 时按「激活包各一段 + 孤儿段原样」抓进 global.rt2。分组语义与槽档分片
     * 同构（同包不得既新又旧，容器层不解释载荷），故直接复用 [SidecarChunkGroups]，
     * 不造第二份分组形状。
     */
    fun captureGlobalChunks(): SidecarChunkGroups

    /**
     * 抓取 global.rt2 容器字节：宿主实现内部经 [SaveService.captureGlobalBundle] +
     * `SidecarSaveBundle.encode()`。绑定三元组的第三位由 core 从 global.sav 容器字节
     * 现算（槽档先例 = .sav 字节 CRC32，构法照抄），实现方不自行发明第二凭据。
     */
    fun captureGlobalRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray

    /**
     * LOADGLOBAL 恢复：解码 [bytes] → [SaveService.restoreGlobalBundle]（绑定校验 +
     * global 形状闸）→ 分片交还各 mod 包（mod 侧 `context.storage` 可见）。
     * 返回 true = Applied；false = 绑定失配 / 容器损坏 / 恢复中途失败
     * （实现必须同时经 [warn] 留下可定位文案，core 不再二次解释）。
     */
    fun restoreGlobalRt2(bytes: ByteArray, binding: SidecarBinding): Boolean

    /**
     * 警告面（fail-closed 的出口）：global.rt2 缺失 / 损坏 / 绑定失配时必须留痕，不静默丢。
     * 生产实现 = 日志面（取证面，非玩家可见文案）。
     */
    fun warn(message: String)
}

/**
 * global 侧车接线的会话级参数（RT2 C-RES1）：mod GLOBAL 分片端口 + 配对事务的磁盘根。
 *
 * 形状与槽档 [SidecarSaveWiring] 逐位同构（照抄不发明第二套）：挂 `store.aux` 的理由同
 * 那条 —— 装配点唯一在 app 装配链（[com.eratw.port.android.ModRuntimeAssembly.attach]，
 * 总闸开态，与槽档接线同一个阶段六），而消费点 [FileSystemOps] 由会话装配先行构造 ——
 * aux 槽是两边都看得见、且**晚装也能现读**的唯一面（语句层每次现读，迟到的 install 对
 * 下一次 SAVEGLOBAL/LOADGLOBAL 即生效）。总闸关态不 install ⇒ [of] 返回 null ⇒
 * [FileSystemOps] 的 SAVEGLOBAL/LOADGLOBAL 路径与未接线前逐字节相同（缺省路径零变化的
 * 机制保证）。
 *
 * @property port mod GLOBAL 分片的抓取/恢复端口（实现 = app 装配侧）
 * @property transactionRoot 配对事务（journal/目标/.part/.bak）的磁盘根；生产 = 游戏根，
 *   与槽档 `SidecarSaveWiring.transactionRoot` 同根：`config.globalPath()` 是相对路径，
 *   `NioSaveTransactionIo` 与 `RootedSaveFileIo` 各自从它解析，落点一致
 */
class GlobalSidecarSaveWiring(
    val port: GlobalSidecarStoragePort,
    val transactionRoot: Path,
) {

    companion object {

        /** aux 槽名（约定：能力家族前缀 + 小写，见 [com.eratw.port.core.host.RuntimeAux] 类 KDoc）。 */
        const val AUX_SLOT: String = "save.global.sidecar"

        /** 现读；未装配（总闸关 / 纯单测）返回 null。 */
        fun of(store: VariableStore): GlobalSidecarSaveWiring? = store.aux.peek(AUX_SLOT)

        /** 装配（只建一次；重复调用保留先装的那份，口径同 [SidecarSaveWiring.install]）。 */
        fun install(store: VariableStore, wiring: GlobalSidecarSaveWiring): GlobalSidecarSaveWiring =
            store.aux.slot(AUX_SLOT) { wiring }
    }
}
