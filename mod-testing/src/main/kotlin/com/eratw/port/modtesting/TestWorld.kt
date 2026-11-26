package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModServiceRegistryImpl
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.RelationshipApi
import com.eratw.port.modapi.StorageApi

/**
 * 一个内存台架世界：从若干份 [ModManifest] 建 schema，再给每个 mod 发一个 [TestContext]。
 *
 * 典型用法（作者单测）：
 *
 * ```kotlin
 * val world = TestWorld.of(MyDeclarations)
 * val mod = MyMod()
 * val session = runMod(mod, world, MyDeclarations)
 * mod.doSomething()
 * assertEquals(3L, world.entities.getInt(id, MyKeys.level))
 * session.disable()
 * ```
 *
 * 同一 [TestWorld] 可给多个 mod 发 context（共享同一份实体/关系/存储状态，便于测跨 mod 协作）；
 * 也可以 `world.newContext("other.mod")` 手动装配。
 */
class TestWorld internal constructor(
    internal val state: TestWorldState,
    /**
     * 每包受控写授权表（ModDX4-B1；[of] / [ofManifests] 按 manifests 的 `write-grants` 与
     * `global-dims` 段计算）。缺省空表 = 手工构造形态（全部键未授权，生产同口径 IAE）。
     */
    private val writeAuthByPackage: Map<String, BenchWriteAuth> = emptyMap(),
) {

    val entities: EntityApi get() = state.entities

    val relationships: RelationshipApi get() = state.relationships

    val query: QueryApi get() = state.query

    val storage: StorageApi get() = state.storage

    /**
     * 本会话的**诊断日志**（跨 context 共享，与生产会话 logSink 同位）：目前承载事件处理器异常
     * 隔离的 WARN 记录（`level`/`packageId`/`message`）。作者想断言「异常被隔离并点名肇事包」时用它；
     * 按包归属的普通 mod 日志仍看 [TestContext.logEntries]。
     */
    val logEntries: List<LogEntry> get() = state.logEntries

    /**
     * 给某个包发一个新的 [TestContext]（共享本世界的实体/关系/存储状态；写授权表按包取）。
     *
     * [faces] 是三个 fail-closed 桩面（roster / actions / functions）的可选注入口（ModDX4-B6，
     * 见 [BenchFaces]）：缺省不注 = 三面维持桩，行为与注入口引入前逐位一致；注入只跟本次发的
     * context 走，同一 [TestWorld] 再发的缺省 context 仍是桩（摘注入即回桩）。
     */
    fun newContext(
        packageId: String = DEFAULT_PACKAGE_ID,
        faces: BenchFaces = BenchFaces(),
    ): TestContext =
        TestContext(packageId, state, writeAuthByPackage[packageId] ?: BenchWriteAuth.EMPTY, faces)

    /**
     * 放一个资源到缺省包（[DEFAULT_PACKAGE_ID]）：落盘到
     * `<台架临时目录>/mods/rt2/<packageId>.assets/<name>`，与引擎同一布局。
     *
     * 名字/包名校验**复用引擎真实现**（经 [com.eratw.port.core.mod.ModResourceStore] 的
     * `apiFor(...).exists(name)` 触发）：非法名抛的 `IllegalArgumentException` 与作者在真引擎上
     * 拿到的是同一份文案。资源存储无缓存（每次调用现读盘），写完立即可读、无需刷新索引。
     *
     * 验证走**静默** store：生产投放资源不经 API、投放本身不产 WARN，故本方法不改 [resourceWarnings]
     * （只有真去读的 `keys/exists/size/open` 才产 WARN，与生产同口径）。
     */
    fun resource(name: String, bytes: ByteArray) = resource(DEFAULT_PACKAGE_ID, name, bytes)

    /** [resource] 的文本重载（UTF-8）。 */
    fun resourceText(name: String, text: String) = resource(name, text.toByteArray(Charsets.UTF_8))

    /** 多包台架：[resourceText] 的包身份重载。 */
    fun resourceText(packageId: String, name: String, text: String) =
        resource(packageId, name, text.toByteArray(Charsets.UTF_8))

    /** 多包台架：给指定 [packageId] 放资源（与 [newContext] 的包身份对应）。 */
    fun resource(packageId: String, name: String, bytes: ByteArray) {
        writeTestResource(state.resourceRoot, state.resourceValidator, packageId, name, bytes)
    }

    /** 资源面 WARN（引擎真实现原样打的：名字非法跳过 / 单文件超限 / 整包超限 / 坏目录等）。 */
    val resourceWarnings: List<String> get() = state.resourceWarnings

    /** 本会话的决策种子（mod 随机流按 `(decisionSeed, packageId)` 派生，与生产同口径）。 */
    val decisionSeed: Long get() = state.decisionSeed

    /**
     * 会话级服务注册表（生产 `ModRuntimeHost.services` 同位，作者面经 `context.services` 发布）。
     *
     * 这里给的是**宿主视角**：可读 `find` / `owners` / `publishedTypes` 观测全表（装卸前后面
     * 断言都能用），`publish` 需显式给 `owner`（作者面不允许冒名，见 [TestContext.services]）。
     */
    val services: ModServiceRegistryImpl get() = state.services

    /** 结束台架：撤本会话各包已发布的服务 + 删资源临时目录（幂等；两件事互不依赖）。 */
    fun close() {
        state.closeServices()
        state.closeResources()
    }

    companion object {

        /** 未显式指定包身份时的缺省值。 */
        const val DEFAULT_PACKAGE_ID: String = "test.mod"

        /** 按载体声明建世界（schema 取各声明的 `manifest`）。 */
        fun of(vararg declarations: ModCarrierDeclaration, decisionSeed: Long = 0L): TestWorld {
            val manifests = declarations.map { declaration -> declaration.manifest }
            return TestWorld(
                TestWorldState(MemorySchema.of(manifests), decisionSeed, settingsDeclarationsOf(manifests)),
                writeAuthOf(manifests),
            )
        }

        /** 按裸 manifest 建世界（作者只测声明形状 / 没写载体对象时用）。 */
        fun ofManifests(vararg manifests: ModManifest, decisionSeed: Long = 0L): TestWorld {
            val list = manifests.toList()
            return TestWorld(
                TestWorldState(MemorySchema.of(list), decisionSeed, settingsDeclarationsOf(list)),
                writeAuthOf(list),
            )
        }

        /**
         * manifests → 每包受控写授权表（ModDX4-B1）：`write-grants` 段进 [BenchWriteAuth.grants]、
         * `global-dims` 段进 [BenchWriteAuth.ownDims]（类型位按 [DimType] 折 isStr、
         * 0/1 维判 addressable）。判据与生产装载相位（`ModDx4WriteGate.build` 消费声明事务
         * 快照 + manifest 段）同一清单；台架从 mod-api 声明形态直取（不经适配器），
         * 同包同段的授权结论两路一致。
         */
        private fun writeAuthOf(manifests: List<ModManifest>): Map<String, BenchWriteAuth> =
            manifests.associate { manifest ->
                manifest.packageId to BenchWriteAuth(
                    grants = manifest.writeGrants.associate { grant ->
                        BenchWriteAuth.canonical(grant.name) to (grant.type == DimType.STR)
                    },
                    ownDims = manifest.globalDims.associate { dim ->
                        BenchWriteAuth.canonical(dim.name) to BenchWriteAuth.BenchOwnDimEntry(
                            isStr = dim.type == DimType.STR,
                            addressable = dim.dims.size <= 1,
                        )
                    },
                )
            }

        /**
         * manifest 的 `settings` 段 → 生产 [SettingDeclaration]（经 [ModDeclarationAdapter] 同一翻译
         * 落点，台架不另写映射/校验）。只翻译声明表本身：合成 manifest 只带 packageId + settings，
         * 不把载体的其它段带进校验面。
         */
        private fun settingsDeclarationsOf(
            manifests: List<ModManifest>,
        ): Map<String, List<SettingDeclaration>> = manifests
            .filter { manifest -> manifest.settings.isNotEmpty() }
            .associate { manifest ->
                manifest.packageId to ModDeclarationAdapter
                    .toDescriptor(ModManifest(packageId = manifest.packageId, settings = manifest.settings))
                    .settings
            }
    }
}
