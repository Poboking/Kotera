package com.eratw.port.modlivingworld

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.StorageKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modlivingworld.generated.LwEventKeys

/**
 * 社交事件 Mod（eratw.lw.event）的**全部声明，只此一份真源**（§21.9 判据 5「多候选
 * 确定性 + 可解释淘汰」与判据 7「错误可定位」的声明面）。
 *
 * ## Demo 内容策略（§21.9）
 *
 * 事件文本与剧情是**手写常量**（候选键、约束键、handler 行为都是字面量），系统只做
 * 组合/条件/参与者/后果；不做程序化内容生成。事件按「记忆/时间/关系/状态」筛选：
 * 茶会的约束读关系 Mod 的好感边（跨包读数据面），权重与优先级是声明面数据。
 *
 * ## 字段名从哪来
 *
 * 事件读数组件与字段由 [LwEventKeys] 提供（`:tools:modgen-accessors` 生成），与
 * [com.eratw.port.modlivingworld.generated.LwEventView] 同一批键。事件包**不声明原型**：
 * 读数挂在其宿主实体上，不需要自己的实体形状（故生成物里的 Facade 只有 `of`，没有 `create`）。
 *
 * ## 与关系 Mod 的共存（判据 4 正向半边的另一半）
 *
 * 本包在同一对实体上声明**自己的**边型 [TENSION]（紧张度，事件后果的落点）与
 * **自己的**字段 [LwEventKeys.seen]：与关系 Mod 的 `lw.rel.affinity` /
 * `lwBondNotes` 互不重名（全局命名空间各占一名），经声明事务共存、互不覆盖；重名 = freeze
 * 整包拒绝（fail-closed 半边由 LivingWorldDeclarationTest 钉住）。
 */
@ModCarrier
object EventDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.lw.event"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = EventMod()

    /** 紧张度边型：本包自己的关系 kind（事件后果写在这里，不动关系 Mod 的好感边）。 */
    val TENSION: RelationKey = RelationKey("lw.rel.tension")

    /** 仲裁域：社交事件（判据 5 的多候选同层抽取在这里跑）。 */
    const val DOMAIN_EVENT: String = "LW_EVENT"

    /** 约束键：好感为正才办茶会（读关系 Mod 边值 —— 跨包数据面组合的演示）。 */
    const val CONSTRAINT_AFFINITY_POSITIVE: String = "lw.event.affinityPositive"

    /** 约束键：恒假谓词（判据 5 的 CONSTRAINT_FALSE 淘汰演示）。 */
    const val CONSTRAINT_ALWAYS_FALSE: String = "lw.event.alwaysFalse"

    /** 约束键：点名抛异常的谓词（判据 7 的失败可定位演示，只在 metadata 点名时抛）。 */
    const val CONSTRAINT_THROWING: String = "lw.event.throwing"

    /** 约束键：候选引用了未注册约束（判据 7 的「condition 未注册」淘汰演示）。 */
    const val CONSTRAINT_MISSING: String = "lw.event.missing"

    /** 候选集选择键（metadata [CharacterDeclarations.METADATA_SET] 的取值）：冷却演示对。 */
    const val SET_COOLDOWN: String = "cooldown-demo"

    /** 候选集选择键：分层/约束/确定性演示组。 */
    const val SET_TIER: String = "tier-demo"

    /** 候选集选择键：失败定位演示组（判据 7）。 */
    const val SET_FAILURE: String = "failure-demo"

    /** 存储键：本包 provider 被收集过的轮数（判据 6 禁用→重启用对账凭据）。 */
    val EVENT_ROUNDS: StorageKey = StorageKey("eventRounds")

    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("生活世界 · 事件")
        author("eratw-port")
        description("事件约束与冷却、分层调度示例")
        component(LwEventKeys.component) {
            int(LwEventKeys.seen)
        }
        relation(TENSION)
    }
}
