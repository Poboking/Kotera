package com.eratw.port.modtemplate

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.StorageKey
import com.eratw.port.modapi.modDeclarations

/**
 * 载体声明对象：**一个 mod 只此一份**。
 *
 * `@ModCarrier` 由 `:mod-registry` 在构建期扫描、生成唯一的 `ModRegistry`，所以不需要像旧流程那样
 * 在「构建期资产生产者」和「设备侧装配」两处各登记一行 —— 漏一处只会静默不生效。
 * （本模板模块被 `:mod-registry` 的载体扫描显式排除：它是给人抄的骨架，不是要装进 APK 的 mod。）
 *
 * ## 抄写模板时改这里
 *
 * 1. [PACKAGE_ID] 换成你的包身份（唯一、反域名风格，如 `eratw.mymod`）；[VERSION] 同步改。
 * 2. `component` 块：声明你的组件与字段（字段名全局唯一，建议带包前缀；[TemplateKeys] 同理）。
 * 3. `archetype` 块：`uses(...)` 你声明的组件。
 * 4. `relation`：声明关系类型。[MENTORS] 用 `inverse =` 自动建反向边，但**反向类型自己也必须
 *    进声明**（`DeclarationRegistry` freeze 对「autoInverse ⇒ inverseName 已声明」是硬闸）。
 * 5. `job`：声明周期任务槽（`everyDays = 1L` = 每游戏日一次；`null` = 一次性）。
 * 6. `hook`：声明函数 Prefix/Postfix 钩子槽；运行期只能 `context.hooks.register` 已声明的槽。
 * 7. [newMod] 返回你的 [EraMod] 实现（本模板是 [TemplateMod]）。
 *
 * 真实 mod 的组件/字段/原型请走 `tools/modgen-accessors/schemas/` 下的 `<name>.schema.tsv` + 生成任务，
 * 让 `XxxKeys` 成为唯一真源；本模板为「clone 即编译」手写了等价物（见 [TemplateKeys]）。
 * 写完 schema/生成物后跑 `modgen check-mod .` 自检三面一致（退出码 0 = 无 ERROR，1 = 有 ERROR）。
 */
@ModCarrier
object TemplateDeclarations : ModCarrierDeclaration {

    /** 本 mod 的包身份；存档分片、注册隔离、日志归属都按它区分。 */
    const val PACKAGE_ID: String = "eratw.template"

    /** manifest 版本号（纯展示 / 兼容判定用）。 */
    const val VERSION: String = "0.1"

    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = TemplateMod()

    /** 有向关系：谁认识谁（缺省一组最保守语义：有向、去重、destroy 级联清边）。 */
    val KNOWS: RelationKey = RelationKey("template.knows")

    /** 正向关系：前辈带后辈。 */
    val MENTORS: RelationKey = RelationKey("template.mentors")

    /** [MENTORS] 的反向类型：后辈拜前辈（自动反向边的落点）。 */
    val MENTORED_BY: RelationKey = RelationKey("template.mentored_by")

    /** 周期任务槽：每游戏日推进一次演员成长。 */
    val DAILY_TICK: JobKey = JobKey("template.dailyTick")

    /** 存储键：已推进的天数（禁用→重启用后要对得上）。 */
    val TICK_COUNT: StorageKey = StorageKey("template.tickCount")

    /** 函数钩子槽：本模板挂在示例 ERB 函数 `TEMPLATE.PING` 的 Prefix 上（换成你真实要观察的函数名）。 */
    val PING_TARGET: FunctionKey = FunctionKey("TEMPLATE.PING")

    /**
     * per-mod 设置项（作者面）：在 manifest 里声明一次即可，**不用自己写设置页、不用自己存盘**——
     * 宿主按声明的类型/候选/范围自动渲染控件，值按包持久化在设备侧（`mods/rt2/<packageId>.settings`），
     * mod 侧经 `context.settings` 现读（见 [TemplateMod.greetingLines]）。
     *
     * 声明了设置项 ⇒ 本包 manifest 升 `RT2MANIFEST/5`（无声明时仍是 `/4`）。
     * 读取未声明的键、或按错误类型读（如对 int 键调 [com.eratw.port.modapi.SettingsApi.bool]）会抛
     * [IllegalArgumentException]：忘了声明时不会静默回落默认值。
     */
    const val GREETING_MODE_KEY: String = "template.greetingMode"

    /** [GREETING_MODE_KEY] 候选：简短（只取 v10 已声明的那句三语文案）。 */
    const val GREETING_MODE_BRIEF: String = "brief"

    /** [GREETING_MODE_KEY] 候选：正式（在文案后补一段 ` [包身份]` 落款）。 */
    const val GREETING_MODE_FORMAL: String = "formal"

    /** [GREETING_MODE_KEY] 缺省值：与未声明设置项前的行为一致。 */
    const val GREETING_MODE_DEFAULT: String = GREETING_MODE_BRIEF

    /** 设置键：每拍问候重复次数（int，范围 1..3）。 */
    const val GREET_COUNT_KEY: String = "template.greetCount"

    /** 设置键：诊断日志开关（bool）。 */
    const val VERBOSE_KEY: String = "template.verbose"

    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = VERSION) {
        // 要让本 mod 拒绝旧引擎，就在实参里写 minApi = <N>（N > 0 才升 RT2MANIFEST/6 并写 min-api
        // 段；缺省 0 = 不设闸）。模板保持缺省，好让旧引擎也能装这个 demo：
        //   modDeclarations(PACKAGE_ID, version = VERSION, minApi = 6) { … }
        displayName("模板 Mod")
        author("eratw-port")
        description("mod 作者工具链的最小可编译模板：声明 / 生命周期 / 生成物用法各一段")
        component(TemplateKeys.component) {
            int(TemplateKeys.level)
            str(TemplateKeys.nickname)
            // ref：整型列存的 EntityId，跨设备重映射按 schema 改写。
            ref(TemplateKeys.mentor)
        }
        archetype(TemplateKeys.archetype) {
            uses(TemplateKeys.component)
        }
        relation(KNOWS)
        relation(MENTORS, inverse = MENTORED_BY, cascadeOnDestroy = false)
        relation(MENTORED_BY, cascadeOnDestroy = false)
        job(DAILY_TICK, everyDays = 1L)
        hook(PING_TARGET, HookPhase.PREFIX, HookOptions(priority = 1))
        // 设置项声明：声明一次 ⇒ 设置页自动出控件、值按包持久化、mod 经 context.settings 读回。
        // 消费示范见 TemplateMod.greetingLines（行为真的随这三项变化）。
        enumSetting(
            key = GREETING_MODE_KEY,
            default = GREETING_MODE_DEFAULT,
            candidates = listOf(GREETING_MODE_BRIEF, GREETING_MODE_FORMAL),
            title = "问候语气",
            description = "brief 只取三语文案；formal 额外带 [包身份] 落款。",
        )
        intSetting(
            key = GREET_COUNT_KEY,
            default = 1L,
            range = 1L..3L,
            title = "问候重复次数",
            description = "每拍输出几句问候（1–3）。缺省 1。",
        )
        boolSetting(
            key = VERBOSE_KEY,
            default = false,
            title = "诊断日志",
            description = "打开后每次问候写一条 mode/count 日志，供作者自测。",
        )
    }
}
