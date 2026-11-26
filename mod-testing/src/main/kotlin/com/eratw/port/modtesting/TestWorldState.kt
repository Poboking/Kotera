package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.mod.ModServiceRegistryImpl
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.RelationshipApi
import com.eratw.port.modapi.StorageApi
import java.io.File
import java.nio.file.Files

/** 台架资源根下的引擎布局目录名：`<resourceRoot>/mods/rt2/<packageId>.assets/`。 */
internal const val RT2_DIR = "mods/rt2"

/**
 * 一个台架世界的共享状态：内存实体 + 关系 + 查询 + 存储，全部按同一份 [MemorySchema] 建。
 *
 * `destroy` 的级联清边在这里装配（`EntityStore.onDestroy` → 关系图摘边），与生产
 * `EntityKernel.destroy` 无条件清全部边同口径。
 *
 * 事件 / 函数钩子 / 交互钩子的注册表也挂在这里：**一个会话一份**（生产
 * `EventDispatchRegistry` / `HookPipeline` / `InteractionPipeline` 同样跟着会话走），
 * 于是同一 [TestWorld] 下的多个 [TestContext]（多 mod）共享同一张表，跨 mod 的
 * 「桶序 / priority 序 / 包声明序」才有确定口径。
 *
 * 决策种子与服务注册表同样是**会话级一份**：前者是 mod 随机流的派生输入（与生产
 * `ModRandomApi(decisionSeed, packageId)` 同口径），后者是跨 mod 服务命中的物理前提
 * （每个 context 只拿到「共享表 + 本包身份」的适配器）。
 */
internal class TestWorldState(
    val schema: MemorySchema,
    val decisionSeed: Long = 0L,
    private val settingsDeclarations: Map<String, List<SettingDeclaration>> = emptyMap(),
) {

    /** 该包 manifest 声明的设置项（生产装载同一翻译落点；无声明返回空表 = 台架便利路径）。 */
    fun settingsDeclarationsOf(packageId: String): List<SettingDeclaration> =
        settingsDeclarations[packageId].orEmpty()

    /** 会话级服务注册表（生产 `ModRuntimeHost.services` 同位）；撤销面跟着 [closeServices]。 */
    val services: ModServiceRegistryImpl = ModServiceRegistryImpl()

    private val issuedPackages = LinkedHashSet<String>()

    /** 取本包作者面服务适配器，并登记包名以便会话结束时整包撤销（幂等）。 */
    fun servicesFor(packageId: String): ModServiceRegistry {
        issuedPackages += packageId
        return services.forPackage(packageId)
    }

    /** 会话级 WARN（与生产会话 logSink 同位；作者看 [TestWorld.logEntries]）。 */
    fun recordWarn(packageId: String, message: String) {
        sessionLogs += LogEntry(level = "WARN", packageId = packageId, message = message)
    }

    /** 结束服务面（幂等）：撤销本会话发过 context 的每个包的服务，等同于生产卸载整包。 */
    fun closeServices() {
        issuedPackages.forEach { services.revokePackage(it) }
        issuedPackages.clear()
    }

    private val store = EntityStore(schema)

    /** 会话级诊断日志（处理器异常隔离等，与生产会话 logSink 同位；比 context 日志更接近生产）。 */
    private val sessionLogs = mutableListOf<LogEntry>()

    val logEntries: List<LogEntry> get() = sessionLogs.toList()

    val entities: EntityApi = MemoryEntityApi(store)

    val relationshipsImpl: MemoryRelationships = MemoryRelationships(schema, store)

    val relationships: RelationshipApi = relationshipsImpl

    val storage: StorageApi = MemoryStorage()

    val query: QueryApi = MemoryQuery(store, relationshipsImpl)

    val eventTable: EventRecordTable = EventRecordTable { packageId, message ->
        sessionLogs += LogEntry(level = "WARN", packageId = packageId, message = message)
    }

    val hookTable: HookRecordTable = HookRecordTable(schema.hookSlots) { packageId, message ->
        sessionLogs += LogEntry(level = "WARN", packageId = packageId, message = message)
    }

    val interactionTable: InteractionRecordTable = InteractionRecordTable(schema.interactionSlots)

    /**
     * 资源面临时根（惰性：只有用例真的放/读资源时才建 `<tmp>/eratw-mod-testing-res*` 目录，
     * 不污染不碰资源的既有用例）。目录树 = `<root>/mods/rt2/<packageId>.assets/`。
     */
    private var resourceRootDir: File? = null

    val resourceRoot: File
        get() = resourceRootDir ?: Files.createTempDirectory("eratw-mod-testing-res").toFile()
            .also { resourceRootDir = it }

    /**
     * 资源面**真实现**：直接复用 `:core-vm` 的 [ModResourceStore]（对拍纪律：台架不重写
     * 名字/上限语义）。根 = [resourceRoot] 下的 `mods/rt2/`，WARN 收进 [resourceWarnings]。
     *
     * 缓存跟着 [resourceRoot] 走，不是 `by lazy` 一次定终身：`closeResources()` 会把本缓存
     * 连同根一起丢弃，下一次访问资源面按新根重建（见该方法 KDoc）。
     */
    val resources: ModResourceStore
        get() = resourceStore ?: newResourceStore { message -> resourceWarningsLog += message }
            .also { resourceStore = it }

    /**
     * 写资源时的**校验口**：与 [resources] 同一个 [ModResourceStore] 实现（名字 / 包身份语义
     * 仍由引擎唯一裁决），但 WARN 静默丢弃。
     *
     * 为什么要静默：生产投放资源 = 宿主把文件铺进 `<rt2>/<pkg>.assets/`，**不经过
     * `ModResourceApi`**，所以投放本身不产生任何 WARN。台架若拿 [resources] 去触发校验，
     * 那次 scan 会把该包历史坏文件 / 超限文件的 WARN 提前灌进 [resourceWarnings]，且随投放
     * 次数重复 —— 作者读到的 WARN 序列就取决于「投放了几次」而不是「读了几次」。校验只需要
     * 引擎的「非法名当场抛 IAE」语义，故走静默口。
     */
    val resourceValidator: ModResourceStore
        get() = newResourceStore { }

    private var resourceStore: ModResourceStore? = null

    private val resourceWarningsLog = mutableListOf<String>()

    /** 资源面 WARN（引擎 [ModResourceStore] 原样打的：非法名跳过 / 超限 / 坏目录等）。 */
    val resourceWarnings: List<String> get() = resourceWarningsLog.toList()

    /**
     * 结束本会话的资源面（幂等；未用过资源面时 no-op）：删临时目录、丢 [resources] 缓存、清 WARN。
     *
     * 三件事必须一起做：目录删掉后若还留着旧 store 缓存，再放资源会写到 [resourceRoot] 新建的
     * 目录、而 `context.resources` 仍读旧（已删）目录 —— 静默空面（生产裁决：空面必须可解释）。
     * 旧 WARN 同理属于已结束的会话，留着会与新会话读数混在一起。
     */
    fun closeResources() {
        resourceRootDir?.deleteRecursively()
        resourceRootDir = null
        resourceStore = null
        resourceWarningsLog.clear()
    }

    private fun newResourceStore(sink: (String) -> Unit): ModResourceStore =
        ModResourceStore(rt2Dir = File(resourceRoot, RT2_DIR), logSink = sink)

    init {
        store.onDestroy = { id -> relationshipsImpl.removeAllEdgesOf(id) }
    }
}
