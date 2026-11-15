package com.eratw.port.core.fn

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.entity.DeterministicDeriver
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityLifecycle
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.modapi.EntityId

/**
 * 实体/角色扩展运行时（RT2 M4-2 + M4-4：双栖内置函数 ENTITY_* 族的装配缝）。
 *
 * 消费方 = [registerEntityMethods] 注册的全部函数（Builtins.buildRegistry 末尾追加）；
 * 注入点 = [BuiltinHost.entityRuntime]（缺省 null）。生产装配 = W1-ENTRT：app 侧
 * `ModRuntimeAssembly.attach` 开态把本接口的组装产物写进 [ENTITY_RUNTIME_SLOT] 槽
 * （会话级，读侧 = [entityRuntimeOf]；关态/attach 前槽缺席 = 未装配语义逐位不变）。
 *
 * 成员语义（架构裁定，contract §2.2，逐条冻结）：
 * - [kernel]：实体 CRUD/字段/关系/Query 的唯一后端（[IEntityKernel]，M4-1 已落）；
 * - [projector]：角色投射桥（[ICharaSlotProjector]）。null = 宿主没有投射能力 ——
 *   「无投射」是事实不是错误：TO_CHARA/CHARA_TO_ENTITY 按 -1/0 旁路；PROJECT/UNPROJECT
 *   是有副作用的写操作，null ⇒ CodeEE（不许伪装成功）；
 * - [registry]：packageId → pkgShortId 分配器（ABI-2）。ENTITY_CREATE 的归属包取自
 *   archetype 的声明包（见 [archetypes]），再经本表换 shortId；
 * - [archetypes]：原型名 → 冻结声明（[DeclaredArchetype]，含 packageId）。实现方必须
 *   按 NamePolicy 归一后查 DeclarationSnapshot.entityArchetypes（生产接线 = 该表的
 *   canonical 查表包装）；返回 null = 该原型未声明；
 * - [catalog]：LSC 符号目录（HAS_SUPER 的判据源）。null = 未装配 ⇒ HAS_SUPER 旁路 0；
 * - [modIsActive]：Mod 激活判定（MOD_IS_ACTIVE 的数据源），包名按实现方口径匹配。
 */
interface EntityRuntime {
    /** 实体内核（必装；缺它整个 ENTITY 族无从谈起）。 */
    val kernel: IEntityKernel

    /** 角色投射桥；null = 无投射能力（TO_CHARA/CHARA_TO_ENTITY 按 -1/0 旁路）。 */
    val projector: ICharaSlotProjector?

    /** 包短 ID 注册表（ABI-2；ENTITY_CREATE 归属包换 shortId 用）。 */
    val registry: PackageShortIdRegistry

    /** 原型名 → 冻结声明；null = 未声明 ⇒ ENTITY_CREATE / QUERY 族 CodeEE。 */
    val archetypes: (String) -> DeclaredArchetype?

    /** LSC 符号目录（HAS_SUPER 判据源）；null = 未装配 ⇒ HAS_SUPER 0。 */
    val catalog: SymbolCatalog?

    /** Mod 激活判定（MOD_IS_ACTIVE 数据源）：true = 激活。 */
    val modIsActive: (String) -> Boolean
}

/** [EntityRuntime] 会话级装配的 aux 槽名（entity 族前缀，RuntimeAux.kt:17 的命名约定）。 */
const val ENTITY_RUNTIME_SLOT = "entity.runtime"

/**
 * 会话级 [EntityRuntime] 装配的读缝（W1-ENTRT）：现读挂在 [aux] 上的槽。
 *
 * 写侧唯一生产点 = app 的 `ModRuntimeAssembly.attach` 开态尾段（真源 = 该槽；铁律 4
 * 状态挂 store.aux，无静态可变全局）。槽缺席 = 关态总闸短路未写、或 attach 尚未跑到
 * 回填段 —— 两种形态都按「未装配」语义回答（裁定 3 的分组口径原样生效），
 * 所以本函数恒不建槽（[RuntimeAux.peek] 不是 [RuntimeAux.slot]），读侧不可能伪造装配态。
 */
fun entityRuntimeOf(aux: RuntimeAux): EntityRuntime? = aux.peek(ENTITY_RUNTIME_SLOT)

/**
 * ENTITY_* / ROSTER_GENERATION / MOD_IS_ACTIVE / HAS_SUPER / ENTITY_RAND 族的注册入口
 * （RT2 M4-2 + M4-4 + M4-5）。语义权威 = era-arch-v61u.md §7（:104 的 API 面清单）+
 * core-vm 既有 KDoc（EntityContracts.kt / EntityKernel.kt / PackageShortIdRegistry.kt /
 * SymbolCatalog.kt）；ENTITY_RAND 语义权威 = era-arch-v61u.md §16（可选通用原语）+
 * §17 M4-5 判据行 + DeterministicDeriver.kt。该族是 4.980 fork 新增原语，原版 Emuera
 * 无 C# 出处，逐函数出处以架构文档行号 + 内核 KDoc 为准。
 *
 * ## 未装配语义分组（裁定 3，fail-closed 与合法旁路分开）
 *
 * - **CodeEE 组**（装配缺失不许伪装成语义读数）：实体 CRUD/字段/关系/QUERY 族 +
 *   PROJECT/UNPROJECT ⇒ CodeEE「实体运行时未装配」；
 * - **合法旁路组**：「无投射/未装配」是事实：MOD_IS_ACTIVE ⇒ 0、HAS_SUPER ⇒ 0、
 *   ROSTER_GENERATION ⇒ 0、ENTITY_TO_CHARA ⇒ -1、CHARA_TO_ENTITY ⇒ 0。
 *
 * ## 通用约定（各函数 KDoc 的 BuiltinMetadata 公共项，不逐条重复）
 *
 * - 求值约束：全族实参即时求值（无 evalRef，无短路）；有副作用函数（CREATE/DESTROY/
 *   SET_* / ADD_REL/DEL_REL/PROJECT/UNPROJECT）禁进惰性位（不许落在与、或、三目的
 *   惰性求值臂上）——era-arch-v61u.md:30 的副作用类别约定；
 * - 类型错误按既有内建惯例运行期报 CodeEE（`函数 X 的第 N 个参数必须是…`，见
 *   SpriteAnimeFrameMethod.kt「参数校验」节；METHOD_ARG_TYPE 缺口 PcOracleGapTest 已登记）；
 *   个数由 [BuiltinMethod.checkArity] 统一报；
 * - id 参数按 EntityId 形态交给内核：悬空/复用/非良构引用一律按「不存在」处理
 *   （EntityKernel.slotOf 全位域比对，接口 KDoc 口径），函数层不另设 id 校验；
 * - 关系 kind 在 ADD/DEL/HAS/QUERY_REL 上 fail-closed：未声明 kind ⇒ CodeEE
 *   （EntityContracts.kt:187 的 [IEntityKernel.hasRelationKind] KDoc 明示双栖内置函数
 *   用它做声明校验，不静默当无边）；
 * - archetype 在 CREATE/QUERY_COUNT/QUERY_FILL 上 fail-closed：未声明 ⇒ CodeEE
 *   （裁定 1：禁止默认 `__core__`、禁止按调用帧猜包）。
 */
internal fun registerEntityMethods(r: BuiltinRegistry) {
    registerEntityLifecycleMethods(r)
    registerEntityFieldMethods(r)
    registerEntityRelationMethods(r)
    registerEntityQueryMethods(r)
    registerEntityProjectMethods(r)
    registerEntityAuxMethods(r)
    registerEntityDeriveMethods(r)
}

// ---------------------------------------------------------------------------
// 取参助手（类型错误文案与 HtmlMethods / SpriteAnimeFrameMethod 同句式）
// ---------------------------------------------------------------------------

/** 第 [i] 个实参（1 基）必须是整数，否则 CodeEE。 */
private fun emIntArg(a: Value?, fn: String, i: Int): Long =
    (a as? Value.IntV)?.v ?: throw CodeEE("函数 $fn 的第 $i 个参数必须是整数")

/** 第 [i] 个实参（1 基）必须是字符串，否则 CodeEE。 */
private fun emStrArg(a: Value?, fn: String, i: Int): String =
    (a as? Value.StrV)?.v ?: throw CodeEE("函数 $fn 的第 $i 个参数必须是字符串")

/** 取装配缝；缺省即 CodeEE（裁定 3 的 CodeEE 组公共入口）。 */
private fun emRuntime(c: BuiltinContext, fn: String): EntityRuntime =
    c.host.entityRuntime ?: throw CodeEE("函数 $fn：实体运行时未装配")

/**
 * ENTITY_QUERY_FILL / ENTITY_QUERY_REL_FILL 的输出数组解析（裁定 4）。
 *
 * 数组名按**目标帧**（即发起调用的当前帧）解析：[com.eratw.port.core.vars.VariableStore.resolveVarInfo]
 * 缺省走 `userVar(name)`，先查当前帧私有 `#DIM`、再查全局用户变量 —— 与原版
 * `IdentifierDictionary.GetVariableToken` 的作用域次序同构（VariableStore.kt:3885-3892 KDoc）。
 * 拼错（未定义）/ 非数组（维度 0）/ 维度不符（非一维）/ 非整型 ⇒ CodeEE 带数组名
 * （era-arch-v61u.md:104「拼错/非数组/维度不符 = 运行期报错带数组名」）。
 * 只要求**一维整型**：FILL 的契约面是「具名 ERB 一维整型数组」。
 */
private fun emCheckIntOneDArray(c: BuiltinContext, fn: String, name: String) {
    val info = c.store.resolveVarInfo(name)
        ?: throw CodeEE("函数 $fn：\"$name\" 不是已定义的变量")
    if (info.operandType != OperandType.INT) {
        throw CodeEE("函数 $fn：\"$name\" 不是整型数组")
    }
    if (info.dimension != 1) {
        throw CodeEE("函数 $fn：\"$name\" 不是一维数组")
    }
}

// ---------------------------------------------------------------------------
// 生命周期族（CREATE / DESTROY / EXISTS / GET_STATE / SET_STATE）
// ---------------------------------------------------------------------------

private fun registerEntityLifecycleMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 archetype=STR；返回 INT（新 EntityId，失败 -1）；
    // 副作用类别=有副作用（建实体），禁进惰性位；实参即时求值；能力依赖=EntityRuntime
    // （kernel+registry+archetypes），未装配即 CodeEE；执行上下文约定=未声明原型 CodeEE、
    // 归属包=声明包（裁定 1），槽容量耗尽（内核 NULL 哨兵）改报 -1。
    r.register(
        BuiltinMethod("ENTITY_CREATE", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_CREATE"
            val rt = emRuntime(c, fn)
            val name = emStrArg(a[0], fn, 1)
            // 归属包 = archetype 的声明包（裁定 1）：未声明原型 ⇒ CodeEE，
            // 禁止默认 __core__、禁止按调用帧猜包。
            val declared = rt.archetypes(name)
                ?: throw CodeEE("函数 $fn：原型 [$name] 未声明")
            val shortId = rt.registry.shortIdOf(declared.packageId)
                ?: throw CodeEE("函数 $fn：声明包 [${declared.packageId}] 未登记短 ID")
            val id = rt.kernel.create(shortId, name)
            // 失败哨兵：内核唯一失败面 = 槽容量耗尽（返回 NULL=0）；NULL 是全零空引用
            // 不是合法实体身份，按名单口径改报 -1，给脚本一个无歧义的失败值。
            Value.IntV(if (id == EntityIdCodec.NULL) -1L else id)
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)；返回 INT(0/1)；副作用类别=有副作用
    // （真删 + 级联清边 + generation 递增），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=不存在/悬空引用返回 0（内核 destroy 的接口口径）。
    r.register(
        BuiltinMethod("ENTITY_DESTROY", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_DESTROY"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            Value.IntV(if (rt.kernel.destroy(id)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)；返回 INT(0/1)；副作用类别=无副作用
    // （存活判定，RETIRED 也算存在）；能力依赖=EntityRuntime；约定=悬空/复用引用 0。
    r.register(
        BuiltinMethod("ENTITY_EXISTS", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_EXISTS"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            Value.IntV(if (rt.kernel.exists(id)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)；返回 INT（生命周期三态，不存在=0 哨兵）；
    // 副作用类别=无副作用；能力依赖=EntityRuntime；约定=不存在返回 0（三态恒 ≥ 1）。
    r.register(
        BuiltinMethod("ENTITY_GET_STATE", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_GET_STATE"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            Value.IntV(rt.kernel.stateOf(id).toLong())
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、state=INT（三态 1/2/3）；返回 INT(0/1)；
    // 副作用类别=有副作用（改生命周期状态），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=非法 state 或实体不存在返回 0（内核 setState 的校验口径，不抛）。
    r.register(
        BuiltinMethod("ENTITY_SET_STATE", OperandType.INT, 2, 2) { c, a ->
            val fn = "ENTITY_SET_STATE"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val state = emIntArg(a[1], fn, 2).toInt()
            Value.IntV(if (rt.kernel.setState(id, state)) 1L else 0L)
        },
    )
}

// ---------------------------------------------------------------------------
// 组件字段族（GET/SET_INT / GET/SET_STR）
// ---------------------------------------------------------------------------

private fun registerEntityFieldMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 id=INT(EntityId)、field=STR；返回 INT；副作用类别=无副作用；
    // 能力依赖=EntityRuntime；约定=未注册字段/不存在返回 0（内核 getInt 失败哨兵）。
    r.register(
        BuiltinMethod("ENTITY_GET_INT", OperandType.INT, 2, 2) { c, a ->
            val fn = "ENTITY_GET_INT"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val field = emStrArg(a[1], fn, 2)
            Value.IntV(rt.kernel.getInt(id, field))
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、field=STR、value=INT；返回 INT(0/1)；
    // 副作用类别=有副作用（写组件列），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=未注册字段/不存在返回 0（不隐式建列，内核纪律）。
    r.register(
        BuiltinMethod("ENTITY_SET_INT", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_SET_INT"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val field = emStrArg(a[1], fn, 2)
            val value = emIntArg(a[2], fn, 3)
            Value.IntV(if (rt.kernel.setInt(id, field, value)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、field=STR；返回 STR；副作用类别=无副作用；
    // 能力依赖=EntityRuntime；约定=未注册字段/不存在返回空串（内核失败哨兵）。
    r.register(
        BuiltinMethod("ENTITY_GET_STR", OperandType.STR, 2, 2) { c, a ->
            val fn = "ENTITY_GET_STR"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val field = emStrArg(a[1], fn, 2)
            Value.StrV(rt.kernel.getStr(id, field))
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、field=STR、value=STR；返回 INT(0/1)；
    // 副作用类别=有副作用（写组件列），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=未注册字段/不存在返回 0。
    r.register(
        BuiltinMethod("ENTITY_SET_STR", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_SET_STR"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val field = emStrArg(a[1], fn, 2)
            val value = emStrArg(a[2], fn, 3)
            Value.IntV(if (rt.kernel.setStr(id, field, value)) 1L else 0L)
        },
    )
}

// ---------------------------------------------------------------------------
// 关系族（ADD_REL / DEL_REL / HAS_REL）
// ---------------------------------------------------------------------------

/**
 * 关系 kind 的 fail-closed 声明闸（本族与 QUERY_REL 公共入口）：未声明 kind ⇒ CodeEE。
 * 依据 = [IEntityKernel.hasRelationKind] 的 KDoc（EntityContracts.kt:187-189）：
 * 「投影/双栖内置函数用它 fail-closed 校验声明」。内核内部路径对未声明 kind 静默
 * false，双栖函数面必须把「声明缺失」与「端点不存在」分开报，否则 Mod 拼错 kind
 * 会拿到「实体不存在」的误导读数。
 */
private fun emRequireRelationKind(fn: String, kind: String, rt: EntityRuntime) {
    if (!rt.kernel.hasRelationKind(kind)) {
        throw CodeEE("函数 $fn：关系类型 [$kind] 未声明")
    }
}

private fun registerEntityRelationMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 from/to=INT(EntityId)、kind=STR；返回 INT(0/1)；
    // 副作用类别=有副作用（写边 + autoInverse 派生反边），禁进惰性位；
    // 能力依赖=EntityRuntime；约定=kind 未声明 CodeEE；任一端不存在返回 0；
    // dedupe 命中（边已存在）返回 0（内核声明缺省口径）。
    r.register(
        BuiltinMethod("ENTITY_ADD_REL", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_ADD_REL"
            val rt = emRuntime(c, fn)
            val from = emIntArg(a[0], fn, 1)
            val kind = emStrArg(a[1], fn, 2)
            val to = emIntArg(a[2], fn, 3)
            emRequireRelationKind(fn, kind, rt)
            Value.IntV(if (rt.kernel.addRelation(from, kind, to)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 from/to=INT(EntityId)、kind=STR；返回 INT(0/1)；
    // 副作用类别=有副作用（摘边 + 反边同步摘），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=kind 未声明 CodeEE；边不存在返回 0。
    r.register(
        BuiltinMethod("ENTITY_DEL_REL", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_DEL_REL"
            val rt = emRuntime(c, fn)
            val from = emIntArg(a[0], fn, 1)
            val kind = emStrArg(a[1], fn, 2)
            val to = emIntArg(a[2], fn, 3)
            emRequireRelationKind(fn, kind, rt)
            Value.IntV(if (rt.kernel.removeRelation(from, kind, to)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 from/to=INT(EntityId)、kind=STR；返回 INT(0/1)；
    // 副作用类别=无副作用；能力依赖=EntityRuntime；约定=kind 未声明 CodeEE；
    // 无向边反向问询按同一条边判真（内核 hasRelation 口径）。
    r.register(
        BuiltinMethod("ENTITY_HAS_REL", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_HAS_REL"
            val rt = emRuntime(c, fn)
            val from = emIntArg(a[0], fn, 1)
            val kind = emStrArg(a[1], fn, 2)
            val to = emIntArg(a[2], fn, 3)
            emRequireRelationKind(fn, kind, rt)
            Value.IntV(if (rt.kernel.hasRelation(from, kind, to)) 1L else 0L)
        },
    )
}

// ---------------------------------------------------------------------------
// Query 族（QUERY_COUNT / QUERY_FILL / QUERY_REL_COUNT / QUERY_REL_FILL，M4-4）
// ---------------------------------------------------------------------------

/** state 实参的合法性闸：0 = 不限，1/2/3 = 生命周期三态，其余 CodeEE（内核 query 的 require 不外漏）。 */
private fun emCheckQueryState(fn: String, state: Int) {
    if (state != 0 && !EntityLifecycle.isValid(state)) {
        throw CodeEE("函数 $fn：state 仅允许 0（不限）或生命周期三态（1/2/3）")
    }
}

/** dir 实参的合法性闸（裁定 4 / era-arch-v61u.md:104）：仅 0 = 出边、1 = 入边，其它 CodeEE。 */
private fun emQueryRelDir(fn: String, raw: Long): Boolean = when (raw) {
    0L -> true
    1L -> false
    else -> throw CodeEE("函数 $fn：dir 仅允许 0（出边）或 1（入边）")
}

private fun registerEntityQueryMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 archetype=STR、state=INT(0/1/2/3)；返回 INT（命中数）；
    // 副作用类别=无副作用（非快照活查询，era-arch-v61u.md:319 的严格校验面）；
    // 能力依赖=EntityRuntime；约定=原型未声明 CodeEE；state 非 0..3 CodeEE；
    // 返回读数在后续建/删实体后即失效（不保证与下一次 FILL 之间稳定）。
    r.register(
        BuiltinMethod("ENTITY_QUERY_COUNT", OperandType.INT, 2, 2) { c, a ->
            val fn = "ENTITY_QUERY_COUNT"
            val rt = emRuntime(c, fn)
            val arch = emStrArg(a[0], fn, 1)
            val state = emIntArg(a[1], fn, 2).toInt()
            emCheckQueryState(fn, state)
            if (rt.archetypes(arch) == null) throw CodeEE("函数 $fn：原型 [$arch] 未声明")
            Value.IntV(rt.kernel.query(arch, state).size.toLong())
        },
    )
    // BuiltinMetadata：参数 archetype=STR、state=INT(0/1/2/3)、outArrName=STR（数组名）；
    // 返回 INT（实际写入数；容量不足 = 截断写入 + 返回负总数）；副作用类别=有副作用
    // （写具名 ERB 数组），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=原型未声明 CodeEE；state 非 0..3 CodeEE；数组名按目标帧 NamePolicy 解析
    // （先当前帧私有 #DIM 再全局用户变量），拼错/非数组/维度不符/非整型 ⇒ CodeEE 带数组名
    // （era-arch-v61u.md:104）；结果按 EntityId 升序写前 capacity 个。
    r.register(
        BuiltinMethod("ENTITY_QUERY_FILL", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_QUERY_FILL"
            val rt = emRuntime(c, fn)
            val arch = emStrArg(a[0], fn, 1)
            val state = emIntArg(a[1], fn, 2).toInt()
            val arrName = emStrArg(a[2], fn, 3)
            emCheckQueryState(fn, state)
            if (rt.archetypes(arch) == null) throw CodeEE("函数 $fn：原型 [$arch] 未声明")
            emCheckIntOneDArray(c, fn, arrName)
            Value.IntV(emFillOutput(c, arrName, rt.kernel.query(arch, state)))
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、kind=STR、dir=INT(0 出边/1 入边)；
    // 返回 INT（邻接实体数）；副作用类别=无副作用；能力依赖=EntityRuntime；
    // 约定=kind 未声明 CodeEE；dir 非 0/1 CodeEE；悬空 id 按无边读 0。
    r.register(
        BuiltinMethod("ENTITY_QUERY_REL_COUNT", OperandType.INT, 3, 3) { c, a ->
            val fn = "ENTITY_QUERY_REL_COUNT"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val kind = emStrArg(a[1], fn, 2)
            val outgoing = emQueryRelDir(fn, emIntArg(a[2], fn, 3))
            emRequireRelationKind(fn, kind, rt)
            Value.IntV(rt.kernel.queryRelations(id, kind, outgoing).size.toLong())
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)、kind=STR、dir=INT(0/1)、outArrName=STR；
    // 返回 INT（实际写入数；容量不足 = 截断 + 负总数）；副作用类别=有副作用
    // （写具名 ERB 数组），禁进惰性位；能力依赖=EntityRuntime；
    // 约定=kind 未声明 CodeEE；dir 非 0/1 CodeEE；数组名解析与溢出口径同 QUERY_FILL。
    r.register(
        BuiltinMethod("ENTITY_QUERY_REL_FILL", OperandType.INT, 4, 4) { c, a ->
            val fn = "ENTITY_QUERY_REL_FILL"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val kind = emStrArg(a[1], fn, 2)
            val outgoing = emQueryRelDir(fn, emIntArg(a[2], fn, 3))
            val arrName = emStrArg(a[3], fn, 4)
            emRequireRelationKind(fn, kind, rt)
            emCheckIntOneDArray(c, fn, arrName)
            Value.IntV(emFillOutput(c, arrName, rt.kernel.queryRelations(id, kind, outgoing)))
        },
    )
}

/**
 * FILL 族的统一落盘与返回值（era-arch-v61u.md:104 的容量契约）：
 *
 * - 容量足够：写全部 [ids]，返回实际写入数（= ids.size，正数）；
 * - 容量不足：**截断写入**前 capacity 个，返回 `-ids.size`（负总数示溢出，不静默）；
 * - 空结果：写 0 个，返回 0。
 *
 * 写入走 `store.writeInt(name, idx, v)`（与数组解析同一条 resolveVarInfo 通道，
 * REF 绑定目标同写）。
 */
private fun emFillOutput(c: BuiltinContext, arrName: String, ids: LongArray): Long {
    val cap = c.store.getLength(arrName, 0)
    val n = minOf(ids.size, cap)
    for (i in 0 until n) {
        c.store.writeInt(arrName, longArrayOf(i.toLong()), ids[i])
    }
    return if (ids.size > cap) -ids.size.toLong() else n.toLong()
}

// ---------------------------------------------------------------------------
// 投射族（PROJECT_CHARA / UNPROJECT_CHARA / TO_CHARA / CHARA_TO_ENTITY）
// ---------------------------------------------------------------------------

private fun registerEntityProjectMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 id=INT(EntityId)；返回 INT（charIndex，失败 -1）；
    // 副作用类别=有副作用（独占原生角色行 + 注水），禁进惰性位；能力依赖=EntityRuntime
    // 且必须含 projector；约定=投射桥缺失 CodeEE（写操作不许伪装成功）；已投射幂等
    // 返回当前行号（ICharaSlotProjector.project 接口口径）。
    r.register(
        BuiltinMethod("ENTITY_PROJECT_CHARA", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_PROJECT_CHARA"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val projector = rt.projector
                ?: throw CodeEE("函数 $fn：角色投射桥未装配")
            Value.IntV(projector.project(id).toLong())
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)；返回 INT(0/1)；副作用类别=有副作用
    // （脱水 + deleteCharacter + 指针清扫），禁进惰性位；能力依赖=EntityRuntime
    // 且必须含 projector；约定=投射桥缺失 CodeEE；未投射返回 0（接口口径）。
    r.register(
        BuiltinMethod("ENTITY_UNPROJECT_CHARA", OperandType.INT, 1, 1) { c, a ->
            val fn = "ENTITY_UNPROJECT_CHARA"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val projector = rt.projector
                ?: throw CodeEE("函数 $fn：角色投射桥未装配")
            Value.IntV(if (projector.unproject(id)) 1L else 0L)
        },
    )
    // BuiltinMetadata：参数 id=INT(EntityId)（1 参）或 id + expectedGeneration=INT（2 参）；
    // 返回 INT（charIndex）；副作用类别=无副作用（纯换算）；能力依赖=EntityRuntime.projector
    // （可旁路）；约定=无投射桥 ⇒ -1（裁定 3，「无投射」是事实）；2 参变体走
    // charIndexOfChecked 世代校验通道（EntityContracts.kt:270-277）：世代过期或未投射
    // 一律 -1（拒绝）；1 参走裸换算 charIndexOf，未投射 -1。
    r.register(
        BuiltinMethod("ENTITY_TO_CHARA", OperandType.INT, 1, 2) { c, a ->
            val fn = "ENTITY_TO_CHARA"
            val projector = c.host.entityRuntime?.projector ?: return@BuiltinMethod Value.IntV(-1L)
            val id = emIntArg(a[0], fn, 1)
            val row = if (a.size >= 2) {
                projector.charIndexOfChecked(id, emIntArg(a[1], fn, 2))
            } else {
                projector.charIndexOf(id)
            }
            Value.IntV(row.toLong())
        },
    )
    // BuiltinMetadata：参数 charIndex=INT；返回 INT（EntityId，Long 直通）；
    // 副作用类别=无副作用（纯反查）；能力依赖=EntityRuntime.projector（可旁路）；
    // 约定=无投射桥 ⇒ 0（裁定 3）；该行无投射实体时 entityAt 返回 EntityIdCodec.NULL
    // （=0），与名单口径一致；返回值是 EntityId 不是 charIndex 的逆运算快照，
    // 行排列后须重取（Mod 引用铁律：持久引用只存 EntityId）。
    r.register(
        BuiltinMethod("CHARA_TO_ENTITY", OperandType.INT, 1, 1) { c, a ->
            val fn = "CHARA_TO_ENTITY"
            val projector = c.host.entityRuntime?.projector ?: return@BuiltinMethod Value.IntV(EntityIdCodec.NULL)
            val charIndex = emIntArg(a[0], fn, 1).toInt()
            Value.IntV(projector.entityAt(charIndex))
        },
    )
}

// ---------------------------------------------------------------------------
// 辅助查询族（ROSTER_GENERATION / MOD_IS_ACTIVE / HAS_SUPER）
// ---------------------------------------------------------------------------

private fun registerEntityAuxMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：无参；返回 INT（行排列世代，Long）；副作用类别=无副作用；
    // 能力依赖=EntityRuntime.projector（可旁路）；约定=无投射桥 ⇒ 0（裁定 3）；
    // 底座语义=原生行排列/删除/退场删行即递增、尾部追加不递增
    // （EntityContracts.kt:259-268）；Mod 存原生引用时一并快照此值做自检。
    r.register(
        BuiltinMethod("ROSTER_GENERATION", OperandType.INT, 0, 0) { c, _ ->
            val gen = c.host.entityRuntime?.projector?.rosterGeneration ?: 0L
            Value.IntV(gen)
        },
    )
    // BuiltinMetadata：参数 packageId=STR；返回 INT(0/1)；副作用类别=无副作用；
    // 能力依赖=EntityRuntime.modIsActive（可旁路）；约定=未装配 ⇒ 0（裁定 3）；
    // 包名匹配口径由 modIsActive 实现方决定（生产 = Manifest 链接态）。
    r.register(
        BuiltinMethod("MOD_IS_ACTIVE", OperandType.INT, 1, 1) { c, a ->
            val fn = "MOD_IS_ACTIVE"
            val rt = c.host.entityRuntime ?: return@BuiltinMethod Value.IntV(0L)
            val packageId = emStrArg(a[0], fn, 1)
            Value.IntV(if (rt.modIsActive(packageId)) 1L else 0L)
        },
    )
    // BuiltinMetadata：无参；返回 INT(0/1)；副作用类别=无副作用；能力依赖=
    // EntityRuntime.catalog（可旁路）；约定=未装配 ⇒ 0（裁定 3）；当前函数键为 null
    // （不在任何函数体内）⇒ 0；判据 = SymbolCatalog.hasSuper(key)，即
    // superChain[胜者] 非空（SymbolCatalog.kt:152-156 的后端判据，裁定 5）；
    // 事件键/未知名经 winnerIndexOf 落 -1 ⇒ 0。
    r.register(
        BuiltinMethod("HAS_SUPER", OperandType.INT, 0, 0) { c, _ ->
            val fnKey = c.store.currentFunction ?: return@BuiltinMethod Value.IntV(0L)
            val catalog = c.host.entityRuntime?.catalog ?: return@BuiltinMethod Value.IntV(0L)
            Value.IntV(if (catalog.hasSuper(fnKey)) 1L else 0L)
        },
    )
}

// ---------------------------------------------------------------------------
// 确定性派生族（ENTITY_RAND，M4-5）
// ---------------------------------------------------------------------------

/**
 * ERB 面的固定派生用途标签（冻结契约，spec builtin-functions.yaml 同口径）。
 *
 * 架构口径（era-arch-v61u.md §16 / §17 M4-5）钉的 ERB 签名就是 `(entityId, salt)` 两参 ——
 * ERB 侧没有 purpose/ordinal 实参：mod 作者按「一个特征一个 salt」取互不相关的派生值。
 * Kotlin mod 侧要真正的用途分域/一串取值，直接走 [DeterministicDeriver.derive] 全参面，
 * 不经本函数（那条路不占 ERB 符号表）。
 */
private const val EM_ERB_DERIVE_PURPOSE = "erb"

private fun registerEntityDeriveMethods(r: BuiltinRegistry) {
    // BuiltinMetadata：参数 id=INT(EntityId)、salt=INT；返回 INT（64 位派生值，Long）；
    // 副作用类别=无副作用（纯函数：不消耗全局 SFMT、不引入存档状态，era-arch-v61u.md §16）；
    // 实参即时求值；能力依赖=EntityRuntime（未装配归裁定 3 的 CodeEE 组：没有实体系统时
    // 调用方手里的任何 id 都是垃圾，伪装成读数比报错更危险）；
    // 约定=EntityId.NULL（0）⇒ CodeEE（DeterministicDeriver.require 的函数面口径）；
    // 悬空/复用引用**不拦** —— 派生值是身份位域（pkg+generation+slot 全位域）的纯函数，
    // 与内核存活态解耦正是「跨加载逐位复现」判据（§17 M4-5）的根据；槽复用 generation
    // 变 ⇒ 值必变（DeterministicDeriverTest 已钉）。
    r.register(
        BuiltinMethod("ENTITY_RAND", OperandType.INT, 2, 2) { c, a ->
            val fn = "ENTITY_RAND"
            val rt = emRuntime(c, fn)
            val id = emIntArg(a[0], fn, 1)
            val salt = emIntArg(a[1], fn, 2)
            if (id == EntityIdCodec.NULL) {
                throw CodeEE("函数 $fn：EntityId 为空引用（0），不得派生特征值")
            }
            Value.IntV(
                DeterministicDeriver.derive(
                    EntityId(id),
                    purpose = EM_ERB_DERIVE_PURPOSE,
                    ordinal = 0L,
                    salt = salt,
                ),
            )
        },
    )
}
