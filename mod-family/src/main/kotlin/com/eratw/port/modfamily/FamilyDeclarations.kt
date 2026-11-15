package com.eratw.port.modfamily

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl

/**
 * 家族 Mod 的**全部声明，只此一份真源**（MOD-1 起步，era-arch-v61u.md §23.2 F1 / §17）。
 *
 * 组件名与字段名必须和 [com.eratw.port.modfamily.generated.FamilyAccessors] 一致 —— 那份访问器是
 * `:tools:modgen-accessors` 从 `family.schema.tsv` 生成的。两处真源的风险登记在 §22.7 第 24 条，
 * 这里用 `FamilyModTest.declarationsAndGeneratedAccessorsDescribeTheSameSchema` 把它钉住：
 * **声明改了就要求重新生成**，否则红。这样「两份真源」至少不会安静地漂开。
 *
 * ## 为什么关系里写 cascadeOnDestroy = false
 *
 * §23.2 的裁定：已故家族成员走 [com.eratw.port.modapi.EntityState.RETIRED]（身份有效、退出常规轮询、
 * **关系与数据都留着**），不走 `destroy`。族谱是一份历史记录，不是垃圾回收的副产物。
 * 但 `cascadeOnDestroy` 仍然显式声明成 false 而不是吃缺省（缺省是 true）：万一哪天真的 destroy 了一个
 * 父母实体，**默认行为不该是「把整条亲子边抹掉」** —— 那会让族谱在无人意图的情况下变形。
 * 引擎侧 v1 不消费这个旗标（`EntityKernel.removeEdgesOf` 的 KDoc 写明），本声明是给它落地时准备的。
 */
object FamilyDeclarations {

    const val PACKAGE_ID: String = "eratw.family"

    const val ARCHETYPE_CHILD: String = "family.child"

    /** 组件名：只用于原型（[ARCHETYPE_CHILD]）挂载与声明分组，**不参与字段寻址**。 */
    const val COMPONENT_FAMILY: String = "family"

    /**
     * 字段键 = **字段名本身**。引擎的字段命名空间是全局的一张表：EntityKernel 按
     * `f.name.uppercase()` 寻址列（EntityKernel.kt:57-58、270-271），组件名不参与寻址，
     * 跨 schema 重名由声明事务在 freeze 当场判冲突（DeclarationRegistry.kt:533-551）。
     * 「组件名.字段名」这种写法生成器曾经用过，但引擎不认，且失败形态是静默的
     * （读回 0 / 读回 "" / 写返回 false）—— 判据在 FamilyModTest。
     */
    const val FIELD_AGE: String = "age"
    const val FIELD_TITLE: String = "title"
    const val FIELD_FATHER: String = "father"

    const val RELATION_PARENT: String = "family.parent"
    const val RELATION_LINEAGE: String = "family.lineage"

    const val JOB_GROWTH: String = "family.growth"

    /** 存储键：记「这个mod 曾经装过并推进过多少拍」，用于禁用→重启用后的值对账。 */
    const val STORAGE_GROWTH_TICKS: String = "growthTicks"

    /**
     * F2 成长场景的路由声明（M5-4 刀 3，era-arch-v61u.md:1807 收口条件的第一消费者落点）。
     *
     * 调用场景（RECON-RT2-M54 §2 Q5）：[FamilyMod.presentChild] 把子嗣投进角色行后，
     * ERB 育成事件（白天/教育相位）按行号写**训练写面**；[FamilyMod.withdrawChild] 脱水时
     * 这些行格子必须随实体负载走（capture），日后 [FamilyMod.presentChild] 再投射逐格注回
     * （pour）。声明驱动口径（R6 v1）：声明了哪族就镜像哪族 —— 四族全跨度（cells 空 =
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
     */
    private val F2_CHARA_ROUTES: List<CharaRouteDecl> = listOf(
        CharaRouteDecl("ABL"),
        CharaRouteDecl("EXP"),
        CharaRouteDecl("JUEL"),
        CharaRouteDecl("CFLAG"),
    )

    val manifest: ModManifest = ModManifest(
        packageId = PACKAGE_ID,
        version = "0.1",
        components = listOf(
            ComponentDecl(
                name = COMPONENT_FAMILY,
                fields = listOf(
                    FieldDecl("age", FieldType.INT),
                    FieldDecl("title", FieldType.STR),
                    // isEntityRef 只对整型字段合法：父身份存的是 EntityId，跨设备重映射要按 schema 改它。
                    FieldDecl("father", FieldType.INT, isEntityRef = true),
                ),
            ),
        ),
        archetypes = listOf(
            ArchetypeDecl(ARCHETYPE_CHILD, listOf(COMPONENT_FAMILY)),
        ),
        relationships = listOf(
            RelationshipDecl(
                name = RELATION_PARENT,
                directed = true,
                autoInverse = true,
                inverseName = RELATION_LINEAGE,
                cascadeOnDestroy = false,
            ),
            // 反向边类型必须**自己也进五域声明**：DeclarationRegistry 的 freeze 对
            // `autoInverse ⇒ inverseName 已声明` 是硬闸（DeclarationRegistry.kt:582-594，
            // 判例测试 DeclarationRegistryTest.relationshipAutoInverseRequiresDeclaredInverseType）。
            // 不写这条，整个 manifest 在 freeze 就红，FamilyMod 一行都跑不起来。
            // 这里显式声明（而不是让 EntityKernel 的派生 schema 兜着，EntityKernel.kt:78 putIfAbsent）：
            // 一旦用户声明了同名 schema，用户版优先 —— 于是反向边的方向/去重/级联口径由**本 manifest**
            // 说了算，而不是由内核的继承规则说了算。cascadeOnDestroy 与正向对齐，理由同上（族谱不因
            // 一次 destroy 变形）；autoInverse 不再带上，否则 parent↔lineage 互相折返。
            RelationshipDecl(
                name = RELATION_LINEAGE,
                directed = true,
                cascadeOnDestroy = false,
            ),
        ),
        jobs = listOf(
            // 每游戏日一次：F2「成长」的最小形状。相位吃缺省 DAY_END。
            JobDecl(jobId = JOB_GROWTH, firstDay = 0L, everyDays = 1L),
        ),
        charaRoutes = F2_CHARA_ROUTES,
    )
}
