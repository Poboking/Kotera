package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.compat.EngineDialect
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.RelationMasternamePolicy
import com.eratw.port.core.compat.StringCompareDialect

/**
 * 缺省家族的方言三元组（生成物 `DIALECT` 的单点）。
 *
 * 本文件三个方言 facet 的**构造默认值**与两个 `LEGACY_*` 档都从这里取，不再手抄
 * `FIXED` / `NAME_CALLNAME_ONLY` / `EXCLUDED` 字面量（CONTRACT-ENGCPL §3.1 E3）：
 * 换引擎 = 改 input yaml + 重生成，不回来改这里。
 *
 * 取值家族 = [EngineProfiles.DEFAULT_TARGET].family（input yaml `targets.<id>.default: true`）。
 * 初始化方向单向：本文件 → [EngineProfiles] → [EngineProfileData] → compat 枚举，
 * 三条路都不会回指本类，无初始化环。
 */
private val defaultDialect: EngineDialect =
    EngineProfileData.DIALECT.getValue(EngineProfiles.DEFAULT_TARGET.family)

/**
 * 目标语料版本（本仓双线维护 4.980 / 4.981）。
 *
 * **v1 只声明、不分叉**：任何「按版本走不同行为」的规则都必须落成
 * [RuntimeCompatibilityProfile] 的**具名 facet** 并经 ABI 评审，不许在消费点散写
 * `if (profile.corpusVersion == …)` —— 散写就是第二套代码路径，正是 §9 明令禁止的形态。
 *
 * 加值（例如将来 4.982）= 编译期在本枚举的消费方全红，强制逐个决定「新版本是否复用
 * 旧语义」；不给缺省回落，避免新语料静默沿用旧口径。
 */
enum class CorpusVersion { V4_980, V4_981 }

/**
 * 标识符归一口径（§5 NamePolicy）。**只有一个实现值**：原版标识符字典就是
 * `StringComparer.OrdinalIgnoreCase`（reference: Config.cs:33，切换点在 :303/:308），
 * 移植侧归一实现 = trim + 大写（[com.eratw.port.core.patch.NamePolicy.canonical]）。
 *
 * 单值枚举是**能力标识**，不是旋钮：原版没有「大小写敏感标识符」这个模式。CSV 关键字
 * 的区分大小写是**数据层**规则（`ConstantData.cs:118/:120`），不是这里的分叉点。
 */
enum class IdentifierFolding { ORDINAL_IGNORE_CASE }

/**
 * 事件注册口径（§6 / M3-1）。四桶执行序
 * `ONLY(0) → PRI(1) → NORMAL(2) → LATER(3)`，同桶按链接序：
 * 分桶见 reference: `LabelDictionary.cs:90-105`，逐桶推进见 `CalledFunction.cs:228-243`。
 *
 * **`CompatiCallEvent` 不在这里**：它是玩家可改的 Config 键
 * （[com.eratw.port.core.config.ConfigKey.CompatiCallEvent]，`ConfigCode.cs:75`，出厂
 * false），由 [ProcessRuntime] 的 config 通道单点读取。把它塞进 profile 会造成
 * 「Config 键 vs profile facet」两份真源。
 */
enum class EventRegistrationPolicy { FOUR_BUCKET }

/**
 * 装载策略的能力标识（§9.1）。v1 = **EAGER_ONLY**：本移植尚未实现 `UseLazyLoading`
 * （全仓只有 config 键、无消费代码），兼容性报告必须如实展示这一能力标识，防后来者
 * 误以为已完整复刻原版装载行为。
 *
 * 懒激活落地后新增 `LAZY_ACTIVATION`：加值会让所有消费方的 exhaustive `when` 编译期变红
 * —— 这正是要求，六通道同覆盖（§9.1）不许漏一条。
 */
enum class LazyLoadingPolicy { EAGER_ONLY }

/**
 * 脚本重载语义（§9）。原版 `ReloadErb` 把 ERB 目录**重新装进既有 labelDic**
 * （reference: `Process.cs:350-358`：`saveCurrentState` → `System_Reloaderb` →
 * `LoadErbDir(..., labelDic)`；局部重载 `:360-367` 同形）⇒ 重注册，胜者可随之改变。
 *
 * 对 Mod 架构的约束（§9.1）：Legacy 下 LSC 必须支持**受控增量重解析**
 * （同一 [com.eratw.port.core.code.SymbolCatalog] 构建器重算受影响键），不是第二份符号真相；
 * Modern 下重载不得改变解析结果。
 */
enum class ReloadPolicy { RE_REGISTER }

/**
 * 存档装载顺序（§8）。原版 LOADDATA **先复位再解析**：
 * reference: `VariableEvaluator.cs:2626-2627`（`SetDefaultValue` / `SetDefaultLocalValue`
 * 先于读角色块），移植侧台账见 `specs/verify-notes.md:1445`。
 *
 * 与 Mod 的关系：`.rt2` 的实体/注册表恢复也必须遵守同一顺序 —— 先复位到声明缺省，再灌
 * 载存档数据，否则「存档里缺的域」会继承上一局残值。
 */
enum class SaveLoadPolicy { RESET_THEN_PARSE }

/**
 * 运行期兼容档案（§9）：**一个参数对象、一条管线**，不是第二套代码路径。
 *
 * ## 十个 facet + 版本声明
 *
 * | facet | 声明什么 | 真源 |
 * |---|---|---|
 * | [corpusVersion] | 目标语料版本（4.980 / 4.981） | [CorpusVersion] |
 * | [functionResolution] | 符号胜者选择（last-wins / 唯一符号 + 显式覆盖） | [ResolverPolicy] |
 * | [identifierFolding] | 标识符归一 | [IdentifierFolding] |
 * | [eventRegistration] | 事件四桶 | [EventRegistrationPolicy] |
 * | [lazyLoading] | 装载能力标识 | [LazyLoadingPolicy] |
 * | [reload] | 重载语义 | [ReloadPolicy] |
 * | [saveLoad] | 存档装载顺序 | [SaveLoadPolicy] |
 * | [engineFamily] | 目标引擎家族（三档之一） | `EngineProfileData` / [EngineProfiles] |
 * | [stringCompare] | 字符串关系比较的 bug 方言 | 生成物 `EngineProfileData.DIALECT` |
 * | [nicknameKeywords] | `GETNUM` 昵称关键字面 | 生成物 `EngineProfileData.DIALECT` |
 * | [relationMastername] | `RELATION` 逆引表是否收 `MASTERNAME` | 生成物 `EngineProfileData.DIALECT` |
 *
 * 后四项（ENGC-2/ENGC-4）只声明「跑的是哪家引擎的哪套已取证方言」：值来自
 * `specs/engine-profiles.input.yaml` 的 `dialect:` 段生成出的 `EngineProfileData.DIALECT`，
 * 运行期由
 * [EngineProfiles.profileFor] 装进本档案；消费点（字符串 `>=`/`<=`、`GETNUM` 关键字、
 * `RELATION` 字符串下标）在 B5 接线。
 *
 * 逐函数覆盖政策 [com.eratw.port.core.patch.OverridePolicy] 不在这里：它是 **Module 侧
 * 逐函数声明**（MODERN 下生效），profile 只决定「是否承认覆盖」这一档
 * （[functionResolution] = MODERN 才看它）。
 *
 * ## bug 方言速查（哪个引擎的哪个 bug ⇒ 哪个 facet）
 *
 * 末尾三个 facet 各自钉死一个**已实跑取证**的引擎分歧（取证读数在 specs 下
 * engine-profiles.yaml 的 probe 节，PLAN-ENGCOMPAT §7 记「已实跑清零」）。要改「某档跑哪套
 * 语义」，只在这张表动 facet 的取值来源（生成器 / `EngineProfileData.DIALECT`），**绝不在消费点
 * 写 `if (engineFamily == …)`** —— 那会变成第二套代码路径（本文件顶部纪律）。
 *
 * | facet | v24_1824 | em_ee_1824_v22 | snake_4981（缺省） | C# 出处（= 分叉点本身，非补丁锚） |
 * |---|---|---|---|---|
 * | [stringCompare] | LEGACY_BOTH_LT | FIXED | FIXED | `>=`/`<=` 都退化成 `string.Compare(..) < 0`：v24 `GameData/Expression/OperatorMethod.cs:487`（GreaterEqualStrStr）与 `:518`（LessEqualStrStr）；snake 已修为 `c >= 0` / `c <= 0`：`Runtime/Script/Statements/Expression/OperatorMethod.cs:547` / `:578`；em 同 snake：`.../OperatorMethod.cs:478` / `:509` |
 * | [nicknameKeywords] | WITH_NICKNAME_MASTERNAME | NAME_CALLNAME_ONLY | NAME_CALLNAME_ONLY | `GetKeywordDictionary` 是否给 `VariableCode.NICKNAME`/`MASTERNAME` 开 case：v24 四键同投 relationDic（`GameData/ConstantData.cs:874-878`）；snake 只投 NAME/CALLNAME（`Runtime/Script/Data/ConstantData.cs:1454-1459`）、em 同（`.../ConstantData.cs:1075-1080`）⇒ 其余档 `GETNUM(NICKNAME, …)` / `GETNUM(MASTERNAME, …)` 得 -1 |
 * | [relationMastername] | INCLUDED | INCLUDED | EXCLUDED | `relationDic` 建表循环是否收 `Mastername`：v24 `GameData/ConstantData.cs:634-641`、em `Runtime/Script/Data/ConstantData.cs:705-712` 收；snake 只到 `Nickname`、无 `Mastername`（`Runtime/Script/Data/ConstantData.cs:858-863`）⇒ `RELATION:1:"主人称呼"` 仅 em/v24 可解析 |
 *
 * **为什么是枚举而不是布尔/字符串**（D4）：每个 facet 的取值集合恰好是「已取证存在的方言」，
 * 布尔只能二选一、字符串会退化成自由旋钮。加一个新值 ⇒ 所有 exhaustive `when` 的消费点
 * 编译期变红、强制逐个决策；缺省取值恒等于 4.981 实跑口径（I1 缺省逐位不变）。三 facet 的
 * 取值都由生成物 `EngineProfileData.DIALECT` 单点提供，本文件不写死名单（I2）。
 *
 * [nicknameKeywords] 与 [relationMastername] **互不冗余**，各钉一条独立取证事实：em 把
 * `MASTERNAME` 写进 `relationDic`（故 `RELATION` 下标可查主名，[relationMastername] = INCLUDED），
 * 但它的 `GetKeywordDictionary` 没有 `NICKNAME`/`MASTERNAME` case，`GETNUM(MASTERNAME, …)`
 * 仍落到 -1（故 [nicknameKeywords] = NAME_CALLNAME_ONLY）；v24 两处都有、snake 两处都无。
 * 合成一个布尔会丢掉这一层区别，另见
 * [com.eratw.port.core.compat.RelationMasternamePolicy] 的 KDoc。
 *
 * ## 三条纪律
 *
 * 1. **所有字段都是枚举**：profile 只表达「引擎声明了哪条兼容事实」，不装布尔/字符串/列表
 *    —— 后者会变成自由旋钮，正是反模式。构造器已把这一点写进类型。
 * 2. **单值枚举是能力标识**：加值 = 所有消费方编译期变红，强制逐个接线；不给运行时缺省回落。
 * 3. **用户可配置项不进 profile**（例：`CompatiCallEvent` 留在
 *    [com.eratw.port.core.config.ConfigKey]）：一个事实只有一个出处。
 *
 * ## 接线状态（ENGC-2 / ENGC-3）
 *
 * 装配链已落地：构建期印章（`assets/engine-profile.json`）→ [EngineStamp] → [EngineProfiles]
 * → `GameHost` → `GameSession.compatProfile`。**函数面门控（ENGC-3）已接**：`GameHost`
 * 在**显式目标档**（印章 Present 或显式覆盖）时才构造
 * [com.eratw.port.core.fn.EngineSurface] 并下传 `GameSession.engineSurface` → [ProcessRuntime]
 * → `BuiltinEvalContext`；无印章 = null = 全量注册表（eraTW 零干扰）。**本档案自身仍不按
 * 字段分叉**，门控以独立形参表达，与「profile ≠ 开关」的纪律一致。
 * 三个方言 facet（ENGC-4）已接线：[stringCompare] 经 `EvalContext.stringCompareDialect`
 * 到 `strCompare` 的 `>=`/`<=`；[nicknameKeywords] 与 [relationMastername] 经
 * `VarConfig` 到 `GETNUM` 关键字解析与 `RELATION` 逆引表建表。
 * 仍待接线：[com.eratw.port.core.patch.ModuleLinker.link] 的 `policy` 由调用方显式传
 * [ResolverPolicy]（Mod 波）。缺省档案逐字段等于改动前的 `LEGACY_ERATW_4981`
 * （四个新 facet 的缺省值就是它的方言）。
 *
 * @param corpusVersion 目标语料版本（v1 仅声明，见 [CorpusVersion]）
 * @param functionResolution 符号解析政策（LEGACY_LAST_WINS / MODERN）
 * @param engineFamily 目标引擎家族；缺省 = 缺省档的家族
 *   （[EngineProfiles.DEFAULT_TARGET]，当前 = [EngineFamily.SNAKE_4981] = eraTW 4.981，零干扰）
 * @param stringCompare 字符串关系比较方言；缺省 = 生成物 `DIALECT[缺省家族]`
 *   （当前 = [StringCompareDialect.FIXED]，4.981 实跑口径）
 * @param nicknameKeywords `GETNUM` 昵称关键字面；缺省 = 生成物 `DIALECT[缺省家族]`
 *   （当前 = [NicknameKeywordPolicy.NAME_CALLNAME_ONLY]，无 `MASTERNAME` 桶）
 * @param relationMastername `RELATION` 逆引表是否收 `MASTERNAME`；缺省 = 生成物
 *   `DIALECT[缺省家族]`（当前 = [RelationMasternamePolicy.EXCLUDED]，relationDic 只有
 *   Name/Callname/Nickname）
 */
data class RuntimeCompatibilityProfile(
    val corpusVersion: CorpusVersion,
    val functionResolution: ResolverPolicy,
    val identifierFolding: IdentifierFolding = IdentifierFolding.ORDINAL_IGNORE_CASE,
    val eventRegistration: EventRegistrationPolicy = EventRegistrationPolicy.FOUR_BUCKET,
    val lazyLoading: LazyLoadingPolicy = LazyLoadingPolicy.EAGER_ONLY,
    val reload: ReloadPolicy = ReloadPolicy.RE_REGISTER,
    val saveLoad: SaveLoadPolicy = SaveLoadPolicy.RESET_THEN_PARSE,
    val engineFamily: EngineFamily = EngineProfiles.DEFAULT_TARGET.family,
    val stringCompare: StringCompareDialect = defaultDialect.stringCompare,
    val nicknameKeywords: NicknameKeywordPolicy = defaultDialect.nicknameKeywords,
    val relationMastername: RelationMasternamePolicy = defaultDialect.relationMastername,
) {

    companion object {

        /**
         * Legacy-ERATW 档（4.980 语料）：普通函数 last-wins（真实注册序）、事件四桶、
         * 官方 bug 全复刻 —— 即**本移植当前的全部行为**。目标是现有语料逐位不变。
         *
         * 注意：4.980 / 4.981 两档目前**逐字段相同**，只差 [corpusVersion]
         * （见单测）。这不是偷懒：两个档案的差异必须先落成具名 facet 才能出现，
         * 不许靠「读版本号在消费点分叉」偷偷产生。
         */
        val LEGACY_ERATW_4980: RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(
                corpusVersion = CorpusVersion.V4_980,
                functionResolution = ResolverPolicy.LEGACY_LAST_WINS,
                engineFamily = EngineProfiles.DEFAULT_TARGET.family,
                stringCompare = defaultDialect.stringCompare,
                nicknameKeywords = defaultDialect.nicknameKeywords,
                relationMastername = defaultDialect.relationMastername,
            )

        /** Legacy-ERATW 档（4.981 语料）；与 4.980 档只差版本声明。 */
        val LEGACY_ERATW_4981: RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(
                corpusVersion = CorpusVersion.V4_981,
                functionResolution = ResolverPolicy.LEGACY_LAST_WINS,
                engineFamily = EngineProfiles.DEFAULT_TARGET.family,
                stringCompare = defaultDialect.stringCompare,
                nicknameKeywords = defaultDialect.nicknameKeywords,
                relationMastername = defaultDialect.relationMastername,
            )

        /**
         * Modern 档：默认唯一符号（同名未声明覆盖 = 链接期拒绝），覆盖须显式声明并过
         * 完整签名闸（[com.eratw.port.core.code.SymbolCatalog]）。
         *
         * 语料版本仍须显式给：Modern 改变的是**符号解析**，不是运行哪套语料。
         */
        fun modern(corpusVersion: CorpusVersion): RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(corpusVersion, ResolverPolicy.MODERN)
    }
}
