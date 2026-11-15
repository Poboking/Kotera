package com.eratw.port.core.fn

import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.expr.OperandType

/**
 * 编译期内建查表的**可注入视图**（ENGC-3b，B6 接线）。
 *
 * erbcc 的 `--engine-profile` 编译通道把五个查表点（`ErbProgramCompiler` 两处、
 * `ExprCallTable.classify`、`ArgSignatureCheck` 两处）从直查 [Builtins.registry]
 * 换成查本接口；被裁名字必须走「未定义的函数」同一条错误路径（D7/I4），不允许
 * 报错文案分叉。两个实现：
 *
 * - [FullRegistryView]：直通注册表，**缺省值**。缺省构造的编译器行为与改前逐位
 *   相同（I7：eraTW 无 profile 编译产物逐字节不变）。
 * - [EngineSurface]：按目标档函数面门控（可见 = 生成物函数集 ∪ PORT_EXTENSIONS，
 *   再与注册表取交集）。
 *
 * 语义权威仍是 `specs/engine-profiles.yaml` 生成物；本接口只是查表形态，不装名单。
 */
interface CompileBuiltinView {

    /** 名字的本视图返回类型；不可见 / 未注册返回 null（调用点据此走非内建路径）。 */
    fun returnType(name: String): OperandType?

    /** 内建是否声明 evalRef（语句位方法实参保持 Expr 形态的判据）；不可见 / 未注册 = false。 */
    fun needsExprArgs(name: String): Boolean

    /** 内建方法本体；不可见 / 未注册返回 null（同 [EngineSurface.builtinLookup] 口径）。 */
    fun method(name: String): BuiltinMethod?

    /** 占位（`registerUnimplemented` 家族标签）判定；只在本视图 method 命中后被消费。 */
    fun isPlaceholder(key: String): Boolean
}

/**
 * [CompileBuiltinView] 的缺省实现：全部查询直通 [Builtins.registry]，
 * 与改前各查表点的内联调用逐位相同。
 */
object FullRegistryView : CompileBuiltinView {
    override fun returnType(name: String): OperandType? = Builtins.registry.returnType(name)
    override fun needsExprArgs(name: String): Boolean = Builtins.registry.needsExprArgs(name)
    override fun method(name: String): BuiltinMethod? = Builtins.registry[name]
    override fun isPlaceholder(key: String): Boolean = Builtins.registry.isPlaceholder(key)
}

/**
 * 目标引擎的**函数面可见性**（ENGC-3）。
 *
 * ## 面语义：可见名怎么算出来
 *
 * 三档的可见函数名 = 生成物 `EngineProfileData.functionsOf(family)` ∪
 * [EngineProfileData.PORT_EXTENSIONS]，再与**实际注册表**取交集（[builtinLookup] 的
 * `Builtins.registry[name]`）。交集这一步不可省：
 * - 声明了但未实装的名字（spec 有、`Builtins.registry` 无）⇒ 交集把它滤掉，
 *   避免「ENUMFUNC 枚举出一个调用即崩的空名」；
 * - 实装了但目标档声明里没有的名字 ⇒ 声明 ∪ 扩展这一步就已判它不可见，本档裁掉。
 *
 * 名单唯一来自生成物（`specs/engine-profiles.yaml`）；本类只做「查哪一份集合 + 与注册表取
 * 交集」这层判决，不装第二份名单（I2）。
 *
 * ## 被裁名为什么必须走「这个引擎从来没有它」那条路（I4 / D7）
 *
 * 判决为不可见时，[builtinLookup] / [returnType] / [method] 一律返回 null，让调用点落到既有的
 * 「未定义的函数」报错路径（`Builtins.kt` 的 `dispatchFor` → `FnDispatch.Undefined` →
 * `throw CodeEE("未定义的函数 $name")`；解析期对应 `ExprCallTable.classify`）。**禁止**在这里
 * 新造「此函数被档位裁掉」之类的第二套文案或状态位（I4：不发明文案）——理由是本波目标是
 * diff 级保真：目标引擎从来就没有这个内建，玩家在正确路径上应看到的正是「未定义的函数」，
 * 与原版对该引擎缺失符号的判决逐字同形。若在门控处分叉出专属报错，等于承认「引擎其实有、
 * 只是被我藏了」，与「该引擎没有它」的事实相反，也让 B4 审计、erbcc 编译期与运行期三条面
 * 各说各话。因此**不能**把门控写成「查全量表后按可见性过滤、滤不掉就回落全量表」——
 * 那会让被裁名重新漏出来（门控失效）；null 是门控生效的唯一形状。
 *
 * ## 为什么放在 `fn` 而不是 `compat`
 *
 * 计划原文把本文件排在 `core/compat/`，但 `compat` 是叶子层（不依赖 expr/fn/proc）：
 * 本类要返回 `BuiltinMethod`，只能与 [Builtins] 同层。名单本身仍唯一来自生成物，
 * 分层口径不变。
 *
 * ## 缺省档不建本对象
 *
 * `GameSession` 的 `engineSurface` 缺省 null = 完全不过滤（eraTW 零干扰，I7）；
 * 只有**显式目标档**（APK 带构建期印章或装置显式覆盖）才装配本类。因此任何
 * 「本档可见名比当前注册表少」的差异都不会落到无印章的 eraTW 包上。
 *
 * @param family 目标引擎家族（印章解析结果）
 */
class EngineSurface(val family: EngineFamily) : CompileBuiltinView {

    /** 本档声明的函数名（大写口径；含端口扩展白名单）。 */
    private val declared: Set<String> =
        (EngineProfileData.functionsOf(family) + EngineProfileData.PORT_EXTENSIONS)
            .mapTo(HashSet()) { it.uppercase() }

    /** 名字在本档函数面是否可见（大小写不敏感，同 [Builtins] 注册表口径）。 */
    fun builtinVisible(name: String): Boolean = name.uppercase() in declared

    /**
     * 本档可见且**已实现**的内建方法；不可见或未注册都返回 null。
     *
     * 门控不能写成「查全量表后按可见性过滤不掉就回落」：被裁名字必须返回 null，
     * 调用点才能走「未定义的函数」同一条错误路径（D14/D7）。
     */
    fun builtinLookup(name: String): BuiltinMethod? =
        if (builtinVisible(name)) Builtins.registry[name] else null

    /**
     * [com.eratw.port.core.fn.BuiltinContext.builtinNames] 的本档视图：
     * 已注册名字里本档可见的那些，保持注册表的源序（ENUMFUNC* 的顺序口径）。
     */
    fun builtinNames(): Collection<String> =
        Builtins.registry.names().filter { builtinVisible(it) }

    // ---- CompileBuiltinView（ENGC-3b 编译期注入；不可见一律 null/false，不回落全量表）----

    override fun returnType(name: String): OperandType? =
        if (builtinVisible(name)) Builtins.registry.returnType(name) else null

    override fun needsExprArgs(name: String): Boolean =
        builtinLookup(name)?.evalRef != null

    override fun method(name: String): BuiltinMethod? = builtinLookup(name)

    override fun isPlaceholder(key: String): Boolean = Builtins.registry.isPlaceholder(key)
}
