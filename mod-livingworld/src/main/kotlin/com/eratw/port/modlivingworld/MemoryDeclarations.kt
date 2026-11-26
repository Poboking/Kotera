package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modlivingworld.generated.LwFactKeys

/**
 * Living World 记忆 Mod（eratw.lw.memory）的**全部声明，只此一份真源**（§21.9 判据 3
 * 「亲历不自动传播」的声明面；era-arch-v61u.md §21.9 / §21.10 M10-1）。
 *
 * ## 为什么自声明而不复用 eratw.memory（交付说明口径）
 *
 * 判据 3 冻结口径给两条路：「记忆包按 M91 模型自声明或复用 eratw.memory」。本包取
 * **自声明最小模型**：四逻辑包按波次要求全部定义在 `:mod-livingworld` 一个 Gradle 模块内、
 * 各自独立 packageId；复用 eratw.memory 会把跨 Gradle 模块依赖与跨包实体归属搅进演示面。
 * 模型形状照 M91 已收口的先例（fact 实体 + `owner --KNOWS--> fact` 有向边，边值 = 认知
 * 来源码），字段名全部 `lw` 前缀（全局字段命名空间防撞），语义最小化：判据 3 只需要
 * 「谁、关于谁、什么事、何时 + 亲历/听闻」五类里的四类。
 *
 * ## 名字从哪来
 *
 * fact 的组件/原型/三字段由 [LwFactKeys] 提供（`:tools:modgen-accessors` 生成），与
 * [com.eratw.port.modlivingworld.generated.LwFactView] 同一批键。[PERSON] 是本包自有的
 * 最小原型（生成物里没有对应数据形状，故手写键）。
 */
@ModCarrier
object MemoryDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.lw.memory"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = MemoryMod()

    /** 知识持有者原型（测试/演示用最小形状）。 */
    val PERSON: ArchetypeKey = ArchetypeKey("lw.mem.person")

    /** 知识（fact）原型：[LwFactKeys.component] 是它唯一挂载的组件。 */
    val FACT: ArchetypeKey = LwFactKeys.archetype

    /**
     * 知识边：`owner --KNOWS--> fact`，有向、无自动反向边、destroy 级联清边（缺省组）。
     * **知识只经 KNOWS 边可达**：引擎不做任何自动传播，「A 亲历的事实不会自动成为 B 的
     * 知识」由 [LivingWorldCombinationTest] 钉住 —— B 想知道，必须有人显式调
     * [MemoryMod.teach] 给 B 挂上自己的边。边值 = 该 owner 持有这条知识的
     * [Provenance].code（同一条 fact 可被多人分别 KNOWS，各人认知来源挂在各自的边上）。
     */
    val KNOWS: RelationKey = RelationKey("lw.mem.knows")

    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("生活世界 · 记忆")
        author("eratw-port")
        description("NPC 对事实的知晓与来源")
        component(LwFactKeys.component) {
            // ref：整型列存的 EntityId，跨设备重映射要按 schema 改写。
            ref(LwFactKeys.subject)
            str(LwFactKeys.predicate)
            int(LwFactKeys.occurredAt)
        }
        archetype(PERSON)
        archetype(FACT) {
            uses(LwFactKeys.component)
        }
        relation(KNOWS)
    }
}

/**
 * [MemoryDeclarations.KNOWS] 边值（认知来源）的取值枚举（判据 3 冻结口径）：
 * 至少含 FIRSTHAND / HEARSAY 两档；码值进边值，一经发布即固化，只增不改。
 */
enum class Provenance(val code: Long) {
    /** owner 亲历（亲眼所见/亲手所为）：只有首录者可以以此档入账。 */
    FIRSTHAND(1L),

    /** 听闻（他人转述）：经 [MemoryMod.teach] 学来的知识对学习者落此档。 */
    HEARSAY(2L),
}
