package com.eratw.port.modfamily

import com.eratw.port.modapi.CharaVar
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.StorageKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modfamily.generated.FamilyKeys

/**
 * 家族 Mod 的**全部声明，只此一份真源**（MOD-1 起步，era-arch-v61u.md §23.2 F1 / §17）。
 *
 * ## 字段名不在这里
 *
 * 组件、原型与三个字段的名字由 [FamilyKeys] 提供 —— 那是
 * `:tools:modgen-accessors` 从 `family.schema.tsv` 生成的，同时也是 [com.eratw.port.modfamily.generated.FamilyView]
 * 读写列时用的同一批键。于是「同一个字段名手抄三遍」被压到只剩 TSV 一处：
 * 声明、业务代码、生成代码全指着同一个 `FieldKey`，打错是编译错误而不是静默读回 0。
 * TSV 改了必须重跑 `generateFamilyAccessors`，`checkFamilyAccessors` 闸会拦。
 *
 * ## 为什么关系里写 cascadeOnDestroy = false
 *
 * §23.2 的裁定：已故家族成员走 [com.eratw.port.modapi.EntityState.RETIRED]（身份有效、退出常规轮询、
 * **关系与数据都留着**），不走 `destroy`。族谱是一份历史记录，不是垃圾回收的副产物。
 * 但 `cascadeOnDestroy` 仍然显式声明成 false 而不是吃缺省（缺省是 true）：万一哪天真的 destroy 了一个
 * 父母实体，**默认行为不该是「把整条亲子边抹掉」** —— 那会让族谱在无人意图的情况下变形。
 * 引擎侧 v1 不消费这个旗标（`EntityKernel.removeEdgesOf` 的 KDoc 写明），本声明是给它落地时准备的。
 *
 * 反向边类型 [LINEAGE] 必须**自己也进声明**：`DeclarationRegistry` 的 freeze 对
 * 「autoInverse ⇒ inverseName 已声明」是硬闸（DeclarationRegistry.kt:582-594，判例测试
 * `DeclarationRegistryTest.relationshipAutoInverseRequiresDeclaredInverseType`）。DSL 的
 * `inverse =` 参数已经把 `autoInverse` 一起置上，再也不会出现「写了 inverseName 忘了 autoInverse」。
 */
@ModCarrier
object FamilyDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.family"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = FamilyMod()

    /** 亲子边（正向：父母 → 子嗣）。 */
    val PARENT: RelationKey = RelationKey("family.parent")

    /** 亲子边的反向类型（子嗣 → 父母）。 */
    val LINEAGE: RelationKey = RelationKey("family.lineage")

    /** 每游戏日一次的成长任务。 */
    val GROWTH: JobKey = JobKey("family.growth")

    /** 存储键：记「这个 mod 曾经装过并推进过多少拍」，用于禁用→重启用后的值对账。 */
    val GROWTH_TICKS: StorageKey = StorageKey("growthTicks")

    /**
     * 存储键：在册子嗣名单的**持久真源** —— 逗号分隔的十进制 [com.eratw.port.modapi.EntityId.raw]，按登记序。
     * 区别于 [FamilyMod.children] 那份进程内缓存：登记/退场时写穿到这里，读档后由 `onSaveLoaded` 重建缓存。
     */
    val CHILD_IDS: StorageKey = StorageKey("childIds")

    /**
     * 存储键：显式指定的「下一任家督」候选（十进制 [com.eratw.port.modapi.EntityId.raw]；缺键 = 未指定）。
     *
     * ADR-015 S3 的**手动继任落地点**：[com.eratw.port.modapi.StorageApi] 是载体自己的持久面，
     * 写在 mod 存储段里随存档走（读档/跨设备恢复与 [CHILD_IDS] 同一套规则），不需要改 manifest 资产、
     * 也不需要宿主设置页参与 —— 设置页只负责 `family.successionMode = manual` 这个开关。
     *
     * 语义边界：**只有** [FamilyApi.chooseSuccessor] 会写本键，**只有**
     * [FamilyMod.advanceOneDay] 末尾的继任清扫会在 manual 模式下消费它（结算成功后清键）。
     * 键缺省 ⇒ manual 模式保持既有的「整拍零写入」行为。
     */
    val MANUAL_HEIR: StorageKey = StorageKey("manualHeir")

    /** per-mod 设置项键：每个游戏日推进几轮成长（INT，1–10，缺省 1 = 既有行为）。 */
    const val GROWTH_PER_DAY_KEY: String = "family.growthPerDay"

    /** per-mod 设置项键：成长时是否把称号写进实体（BOOL，缺省开）。 */
    const val SHOW_TITLES_KEY: String = "family.showTitles"

    /** per-mod 设置项键：家督继承规则（ENUM：eldest / youngest / manual，缺省 eldest）。 */
    const val SUCCESSION_MODE_KEY: String = "family.successionMode"

    /**
     * per-mod 设置项键：每游戏日每名在册成员的死亡概率（INT，千分比 0–1000，缺省 0 = 关闭）。
     *
     * ADR-015 D6 的触发三级里属于 C 级（时间驱动）：值 0 表示**完全关闭** —— 日拍整段不执行、
     * 不消耗任何随机数（默认配置不得改变随机流位置）；>0 才启用，[FamilyMod.advanceOneDay] 在 reconcile
     * 之后、成长之前按本值逐候选掷一次骰，命中者走 [FamilyMod.markDeceased]（A1 写路径）。
     *
     * 标题/说明按既有 per-mod 设置口径走作者单语（与 [GROWTH_PER_DAY_KEY] 等同款）；壳层三语化
     * 不在本片范围内，等壳层设置页接入时统一改造。
     */
    const val MORTALITY_PERMILLE_KEY: String = "family.mortalityPermille"

    /** 继任候选值：长嗣优先（在册子嗣里年龄最大者）。 */
    const val SUCCESSION_ELDEST: String = "eldest"

    /** 继任候选值：幼嗣优先（在册子嗣里年龄最小者）。 */
    const val SUCCESSION_YOUNGEST: String = "youngest"

    /** 继任候选值：人工指定 —— [chooseSuccessor] 原样保留调用方给的现任。 */
    const val SUCCESSION_MANUAL: String = "manual"

    /** 继任规则的缺省值：声明 `default` 与载体侧「坏值回落」都用它。 */
    const val SUCCESSION_DEFAULT: String = SUCCESSION_ELDEST

    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     *
     * ## F2 成长场景的路由声明（M5-4 刀 3，era-arch-v61u.md:1807 收口条件的第一消费者落点）
     *
     * 调用场景（RECON-RT2-M54 §2 Q5）：[FamilyMod.presentChild] 把子嗣投进角色行后，
     * ERB 育成事件（白天/教育相位）按行号写**训练写面**；[FamilyMod.withdrawChild] 脱水时
     * 这些行格子必须随实体负载走（capture），日后 [FamilyMod.presentChild] 再投射逐格注回
     * （pour）。声明驱动口径（R6 v1）：声明了哪族就镜像哪族 —— 四族全跨度（不写 cells =
     * freeze 以出厂尺寸展开，mod 不写尺寸）：
     * - ABL（能力）：训练直接写的能力值，育成写面的主体；
     * - EXP（经验）：每次训练行为累积的经验，育成进度的累计面；
     * - JUEL（宝石）：训练经 PALAM 折算产出的宝石，育成资源面；
     * - CFLAG（角色旗标）：育成里程碑/事件进度的旗标格。
     *
     * **刻意不声明 CSTR**（契约候选池五族的第五族）：F2 的具体调用场景里没有写字符串行的
     * 落点 —— family 子嗣的身份串（NAME/CALLNAME 等）在 0 维族（v1 路由拒载，RECON TODO-2
     * 在案），CSTR 的行上语义与那批身份族同向，family 成长切片不写它。解锁路径 = 出现真实
     * CSTR 调用点时补一行声明（声明是数据，零代码增量），不提前罗列（§23.3 纪律 1）。
     * 也不声明 TALENT/MARK/PALAM 等：RECON Q5 点名的其余训练面属语料 ERB 的育成事件，
     * family 载体 v1 只保自己切片内真实要过退场关的四族。
     *
     * 族名走 [CharaVar] 常量而不是字面量：拼错 `"ABLE"` 在旧写法里既编译得过、也不会被 freeze
     * 拒载（未注册变量的存在性是 attach 期终审），表现是「这族格子没随实体走」，一路静默。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("家族系统")
        author("eratw-port")
        description("父子血缘、收养与逐日成长示例载体")
        component(FamilyKeys.component) {
            int(FamilyKeys.age)
            str(FamilyKeys.title)
            // ref：整型列存的 EntityId，跨设备重映射要按 schema 改写。
            ref(FamilyKeys.father)
        }
        archetype(FamilyKeys.archetype) {
            uses(FamilyKeys.component)
        }
        relation(PARENT, inverse = LINEAGE, cascadeOnDestroy = false)
        relation(LINEAGE, cascadeOnDestroy = false)
        job(GROWTH, everyDays = 1L)
        charaRoutes(CharaVar.ABL, CharaVar.EXP, CharaVar.JUEL, CharaVar.CFLAG)
        // per-mod 设置项（RT2 per-mod 配置）：作者在这里声明一次，设置页自动出控件、值按包
        // 持久化在设备侧（mods/rt2/eratw.family.settings），mod 侧经 context.settings 读；
        // 声明了设置项 ⇒ 本包 manifest 升 RT2MANIFEST/5（无声明时仍是 /4）。
        intSetting(
            key = GROWTH_PER_DAY_KEY,
            default = 1L,
            range = 1L..10L,
            title = "每日成长次数",
            description = "每个游戏日推进几轮成长（1–10）。缺省 1，与既有行为一致。",
        )
        boolSetting(
            key = SHOW_TITLES_KEY,
            default = true,
            title = "显示家族称号",
            description = "成长时是否把称号写进实体；关掉只保留年龄与血缘。",
        )
        enumSetting(
            key = SUCCESSION_MODE_KEY,
            default = SUCCESSION_DEFAULT,
            candidates = listOf(SUCCESSION_ELDEST, SUCCESSION_YOUNGEST, SUCCESSION_MANUAL),
            title = "继承规则",
            description = "家督继承时按年龄选长嗣（eldest）、幼嗣（youngest），或由上层调用者人工指定（manual）。",
        )
        // ADR-015 D6-C：时间驱动的死亡概率。缺省 0 = 关闭（日拍零随机数消耗），>0 才在日拍抽样。
        // description 文字保持不变：入库资产 app/src/main/assets/mods/rt2/eratw.family.manifest 由
        // 本声明逐字节生成，本片按契约不动该资产（改描述必须同步 generateBuiltinRt2Manifest 并提交新字节）。
        intSetting(
            key = MORTALITY_PERMILLE_KEY,
            default = 0L,
            range = 0L..1000L,
            title = "每日死亡概率（千分比）",
            description = "每个游戏日每名在册成员死亡的概率（0–1000‰）。缺省 0 = 关闭、不产生任何死亡；>0 才启用，时间驱动接线在后续切片。",
        )
    }
}
