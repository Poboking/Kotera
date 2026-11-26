package com.eratw.port.modapi

/**
 * 类型化声明键（MOD-API 人机工程 §23，2026-11-16）。
 *
 * ## 要治的病
 *
 * 旧写法把同一个标识符手抄三遍：`FieldDecl("age")` 声明一份、`const val FIELD_AGE = "age"`
 * 再抄一份、生成器里 `getInt(e, "age")` 又抄一份。任一份打错都不会编译报错 ——
 * 引擎按名字查表，查不到就返回失败哨兵（读 0 / 读空串 / 写 false），**静默**。
 * 判据见 `core-vm` 的 `EntityKernel` 字段寻址与 `FamilyModTest` 的静默失败用例。
 *
 * ## 为什么是类型化类而不是 enum
 *
 * 分界线是**集合归谁维护**：
 * - 闭合、由引擎/我们维护、值本身带行为 ⇒ `enum`（`FieldType` / `CsvOp` / `HookPhase` /
 *   `EventBucket` / `EntityState` / `JobCatchUp` / `DimType` / `InteractionPhase` 已经是）。
 * - 开放、由 mod 作者自创 ⇒ 本文件的键类。作者每加一个字段就去改 SDK 的枚举，
 *   那就不是 mod 了。
 *
 * 还有一条硬约束直接排除「字段也用 enum」：**enum 不能带类型参数**。`age` 是 `Long`、
 * `title` 是 `String`、`father` 是 `EntityId`，一个 `enum class Field` 装不下三种类型；
 * 拆成三个枚举再互相转换，只是把丑陋换个地方住。故字段键取 `FieldKey<T>`。
 *
 * ## 为什么是普通类而不是 `@JvmInline value class`
 *
 * 实测试过 value class：Kotlin 不允许 `vararg` 取 value class（`Prohibited vararg parameter
 * type`），而且 value class 包 String 后与 String 参数**擦除后同签名**，于是
 * `CharaRouteDecl(String)` 与 `CharaRouteDecl(CharaVar)`、`component(String, block)` 与
 * `component(ComponentKey, block)` 全部撞「Platform declaration clash」。键只出现在声明期
 * 与生成代码里，一次分配换掉一整套重载死锁，划算。
 *
 * 语义等价于值对象：`equals` / `hashCode` 按类 + 名字，`toString` 就是裸名字（直接进日志）。
 */
abstract class DeclKey(open val name: String) {
    init {
        require(name.isNotBlank()) { "声明键不能为空" }
    }

    override fun equals(other: Any?): Boolean =
        other != null && other.javaClass == javaClass && (other as DeclKey).name == name

    override fun hashCode(): Int = name.hashCode() * 31 + javaClass.hashCode()

    override fun toString(): String = name
}

/**
 * 实体字段键。[T] 只允许 `Long` / `String` / [EntityId] 三种，编译期钉死读写类型。
 *
 * 键的名字就是**字段名本身**（不带组件名前缀）：引擎的字段命名空间是全局一张表，
 * `EntityKernel` 按 `f.name.uppercase()` 寻址列，组件名不参与寻址。写
 * 「组件名.字段名」不报错，只会静默读回失败哨兵。
 */
class FieldKey<T>(name: String) : DeclKey(name)

/** 实体原型键（`entity-archetypes` 域）。 */
class ArchetypeKey(name: String) : DeclKey(name)

/** 组件键（`component-schemas` 域）。组件名只用于原型挂载，不参与字段寻址。 */
class ComponentKey(name: String) : DeclKey(name)

/** 关系类型键（`relationship-schemas` 域，全局命名空间）。 */
class RelationKey(name: String) : DeclKey(name)

/** 周期/定时任务槽键（`phase-jobs` 域，包内唯一）。 */
class JobKey(name: String) : DeclKey(name)

/** 动态任务模板键（包内唯一，与 [JobKey] 不共享命名空间）。 */
class JobTemplateKey(name: String) : DeclKey(name)

/** 相位键。引擎固定相位见 [BuiltinPhase]；自定义相位经 `phase-jobs` 域声明。 */
class PhaseKey(name: String) : DeclKey(name)

/** 全局变量键（`global-dims` 域；运行期经 [GameStateApi] 读写）。 */
class GlobalKey(name: String) : DeclKey(name)

/** Mod 私有存储键（按 packageId 隔离，落 `.rt2` 侧车）。 */
class StorageKey(name: String) : DeclKey(name)

/**
 * 引擎函数键（大写归一）。两处用途：hook 槽的 [HookDecl.targetKey]，以及 CALLSHARP 注册名。
 * 这个名字拼错**不会**静默 —— hook 槽未声明时 bind 直接抛；CALLSHARP 未注册时 ERB 侧报错。
 */
class FunctionKey(name: String) : DeclKey(name)

/**
 * CSV 表名（`csv-patches` 域的 [CsvKeywordDecl] / [CsvValueDecl] 表身份）。
 *
 * 为什么不是 enum：可打补丁的表集合由**声明事务喂**（未列出的表整表拒绝补丁，容量未知时
 * fail-closed），且档位随语料换代变化。类型化键保住「表名拼错编译期查得出」这一半收益
 * （常量由 IDE 补全），又不会在引擎加表时逼所有人改 SDK。
 */
class CsvTable(name: String) : DeclKey(name) {
    companion object {
        // ---- 角色 1 维整数表 ----
        val BASE = CsvTable("BASE")
        val MAXBASE = CsvTable("MAXBASE")
        val ABL = CsvTable("ABL")
        val TALENT = CsvTable("TALENT")
        val EXP = CsvTable("EXP")
        val MARK = CsvTable("MARK")
        val PALAM = CsvTable("PALAM")
        val SOURCE = CsvTable("SOURCE")
        val EX = CsvTable("EX")
        val CFLAG = CsvTable("CFLAG")
        val JUEL = CsvTable("JUEL")
        val EQUIP = CsvTable("EQUIP")
        val TEQUIP = CsvTable("TEQUIP")
        val STAIN = CsvTable("STAIN")
        val GOTJUEL = CsvTable("GOTJUEL")
        val NOWEX = CsvTable("NOWEX")
        val DOWNBASE = CsvTable("DOWNBASE")
        val CUP = CsvTable("CUP")
        val CDOWN = CsvTable("CDOWN")
        val TCVAR = CsvTable("TCVAR")

        // ---- 角色 1 维字符串表 ----
        val CSTR = CsvTable("CSTR")

        // ---- 角色 0 维字符串表（v1 路由拒载，CSV 补丁仍合法）----
        val NAME = CsvTable("NAME")
        val CALLNAME = CsvTable("CALLNAME")
        val NICKNAME = CsvTable("NICKNAME")
        val MASTERNAME = CsvTable("MASTERNAME")

        // ---- 角色 2 维整数表 ----
        val CDFLAG1 = CsvTable("CDFLAG1")
        val CDFLAG2 = CsvTable("CDFLAG2")
    }
}

/**
 * 角色变量族名（`chara-field-routes` 域的路由目标）。
 *
 * 词汇表取自引擎 `SystemLayout` 的角色变量登记表 —— 这是**引擎侧的封闭词汇**，写错一个
 * 字母（例如 `"ABLE"`）在旧写法里既编译得过、也不会被 freeze 拒载（未注册变量名的存在性
 * 是 attach 期终审），表现就是「这族格子没随实体走」，一路静默到玩家发现数据丢了。
 *
 * 路由拒载名单（声明事务裁决，见 `DeclarationRegistry` 的 chara-field-routes 三道闸）：
 * `RELATION`、`NO`、`CDFLAG` 与 0 维族（`NAME` 等）。它们在本类里保留常量是为了 CSV 补丁
 * 与变量读取表达式可复用同一份词汇，**不代表可以拿去声明路由**。
 */
class CharaVar(name: String) : DeclKey(name) {
    companion object {
        // ---- 0 维（路由拒载）----
        val ISASSI = CharaVar("ISASSI")
        val NO = CharaVar("NO")
        val NAME = CharaVar("NAME")
        val CALLNAME = CharaVar("CALLNAME")
        val NICKNAME = CharaVar("NICKNAME")
        val MASTERNAME = CharaVar("MASTERNAME")

        // ---- 1 维整数（路由主用面）----
        val BASE = CharaVar("BASE")
        val MAXBASE = CharaVar("MAXBASE")
        val ABL = CharaVar("ABL")
        val TALENT = CharaVar("TALENT")
        val EXP = CharaVar("EXP")
        val MARK = CharaVar("MARK")
        val PALAM = CharaVar("PALAM")
        val SOURCE = CharaVar("SOURCE")
        val EX = CharaVar("EX")
        val CFLAG = CharaVar("CFLAG")
        val JUEL = CharaVar("JUEL")
        val RELATION = CharaVar("RELATION")
        val EQUIP = CharaVar("EQUIP")
        val TEQUIP = CharaVar("TEQUIP")
        val STAIN = CharaVar("STAIN")
        val GOTJUEL = CharaVar("GOTJUEL")
        val NOWEX = CharaVar("NOWEX")
        val DOWNBASE = CharaVar("DOWNBASE")
        val CUP = CharaVar("CUP")
        val CDOWN = CharaVar("CDOWN")
        val TCVAR = CharaVar("TCVAR")

        // ---- 1 维字符串 ----
        val CSTR = CharaVar("CSTR")

        // ---- 2 维整数（路由拒载）----
        val CDFLAG = CharaVar("CDFLAG")
    }
}

// ---- 字段键工厂 ----

/** 整型字段键（对应 `FieldDecl(type = INT)`）。 */
fun intKey(name: String): FieldKey<Long> = FieldKey(name)

/** 字符串字段键（对应 `FieldDecl(type = STR)`）。 */
fun strKey(name: String): FieldKey<String> = FieldKey(name)

/** 实体引用字段键（整型列，跨设备重映射时按 schema 改写 EntityId）。 */
fun refKey(name: String): FieldKey<EntityId> = FieldKey(name)
