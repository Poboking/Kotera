package com.eratw.port.core.expr

import com.eratw.port.core.compat.StringCompareDialect
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.err.DIVIDE_BY_ZERO_TEXT
import com.eratw.port.core.err.MULTIPLY_10K_TO_STR_TEMPLATE
import com.eratw.port.core.err.MULTIPLY_NEGATIVE_TO_STR_TEMPLATE
import com.eratw.port.core.err.SafeArithmetic
import com.eratw.port.core.err.renderMultiplyToStrLimit
import com.eratw.port.core.err.zeroDivideWarning
import com.eratw.port.core.lexer.longFromScaledDouble

/**
 * 操作数静态类型。对应原版 GetOperandType() 的 typeof(long)/typeof(string)。
 *
 * [FLOAT] 是 4.981 升版新增（1.824 只有 long/string 两型，`GetOperandType` 从无 double 分支，
 * spec: AExpression.cs:23-40；浮点字面量/`TOINT(… * 0.7)` 均为 4.981 语料的新写法）⇒
 * `TODO(verify)`：类型提升与转换语义按本文件注释实现，无 C# 证据。
 */
enum class OperandType {
    INT,
    STR,
    FLOAT,
}

/**
 * 表达式 AST。求值方法直接挂在节点上（对应原版 AExpression 的虚方法
 * GetIntValue/GetStrValue，spec: AExpression.cs:23-40）。
 *
 * 静态类型不变量：所有节点在**解析期**即确定 operandType；类型不匹配的组合
 * 在解析期报错（对应 OperatorMethodManager.ReduceXxxTerm 的检查，M1 不做运行期二次检查）。
 */
sealed interface Expr {
    val operandType: OperandType
    val line: Int
    val col: Int

    fun getIntValue(ctx: EvalContext): Long
    fun getStrValue(ctx: EvalContext): String

    /**
     * 浮点求值通道（4.981 升版）。
     *
     * 默认实现 = 整型通道的宽化（`getIntValue(ctx).toDouble()`）：只有 `OperandType.FLOAT`
     * 的结点会被调用方走到这里（[FloatLiteral] / 浮点算术 [Binary] / [Unary] 取负 /
     * 浮点三目 [Ternary] / 返回 float 的 [FuncCall] 各自覆写），整型子式（含浮点比较的结果）
     * 经默认实现即可。
     */
    fun getFloatValue(ctx: EvalContext): Double = getIntValue(ctx).toDouble()

    fun toValue(ctx: EvalContext): Value =
        // 小整数走缓存（见 [Value.intV] 的 KDoc）：本方法是「表达式结果 → Value」的唯一
        // 默认出口，JFR 实测在这里分配 26.99 MiB 的 `Value$IntV`。
        when (operandType) {
            OperandType.INT -> Value.intV(getIntValue(ctx))
            OperandType.STR -> Value.StrV(getStrValue(ctx))
            OperandType.FLOAT -> Value.FloatV(getFloatValue(ctx))
        }
}

// ---------------------------------------------------------------------------
// 字面量
// ---------------------------------------------------------------------------

/** spec: SingleLongTerm.cs（ExpressionParser.cs:513-515 入栈） */
data class IntLiteral(
    val value: Long,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.INT
    override fun getIntValue(ctx: EvalContext) = value

    /** 原版未覆写 GetStrValue，默认返回 ""（AExpression.cs:28-31）；静态类型守卫下不会被调用。 */
    override fun getStrValue(ctx: EvalContext) = ""
}

/** spec: SingleStrTerm.cs（ExpressionParser.cs:509-511 入栈） */
data class StrLiteral(
    val value: String,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.STR
    override fun getIntValue(ctx: EvalContext) = 0L
    override fun getStrValue(ctx: EvalContext) = value
}

/**
 * 浮点字面量（4.981 升版：`0.7` / `1.0` / `1.5e3`）。
 *
 * ## 整型通道 = 静默向零截断（路次 g5 按 fork 对齐）
 *
 * fork-src `Term.cs:108-146` 的 `SingleFloatTerm` 三条通道**全部覆写**，其中整型通道
 * （:121-124）是 `public override long GetIntValue(ExpressionMediator exm) { return (long)fValue; }`
 * —— C# unchecked 的 `(long)` 向零截断，**不报错**。本仓此前照 1.824 的口径抛错
 * （1.824 无浮点型，`AExpression.cs:29-32` 的基类 `GetIntValue` 直接 `return 0`，
 * 浮点整条通道不存在），⇒ `YOGORE:1.5 = 7` 这类形态本仓红、原版按 1 号格执行。
 *
 * 为什么差异面落在**下标位**：fork `VariableTerm.cs:49-99` 的三条读通道都用同一条
 * `transporter[i] = arguments[i].GetIntValue(exm)`（:55 / :71 / :90，另见 :42
 * `GetElementInt`）求下标 —— 下标表达式恒走整型通道，于是 `SingleFloatTerm` 的截断
 * 在这里直接决定命中哪一格。语句位（`A = 1.5`）在本仓**先**被语句层的右值类型闸拦下
 * （`stmt/ArgBuilders.kt:1799` 的 `if (!term.isInteger()) fail(...)`，实测上屏文案还写着
 * 「不能把字符串赋给整数变量」），编译层 `compile/ErbProgramCompiler.kt:817-828` 的
 * `OperandType.FLOAT` 守卫是第二道 ⇒ 落点面仍不截断。这两处文件都不在本路所有权内，
 * 逐条见交付回执「需组长接线」。
 *
 * 越界口径复用 `core/lexer` 的 [longFromScaledDouble]（与 `TOINT` 的浮点支
 * `fn/PureMethods.kt:1540` 同一个函数）：`d >= 2^63` 给 `Long.MIN_VALUE`，即 .NET x64
 * `cvttsd2si` 的「整数不定值」，不是 JVM 的饱和。NaN 同落 `Long.MIN_VALUE` ——
 * `longFromScaledDouble` 已按 .NET 口径（Term.cs:121-124）实现，f88d4801 起三处
 * `(long)` 落点统一，旧 TODO「都按 JVM 的 0、本路不单独改」随之了结。
 */
data class FloatLiteral(
    val value: Double,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.FLOAT
    override fun getIntValue(ctx: EvalContext): Long = longFromScaledDouble(value)

    override fun getFloatValue(ctx: EvalContext) = value
    override fun getStrValue(ctx: EvalContext) = ""
}

/** #FUNCTION 声明参数的省略默认值占位。 spec: NullTerm.cs */
data class NullTerm(
    val isInt: Boolean,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = if (isInt) OperandType.INT else OperandType.STR
    override fun getIntValue(ctx: EvalContext) = 0L
    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 变量引用
// ---------------------------------------------------------------------------

/**
 * 变量引用（含 `V:1` 下标形态与省略下标补全后的形态）。
 * 省略规则与 TARGET/0 的填充见 ExprParser.buildVariableTerm。
 *
 * [isNoArg] 对应原版 VariableNoArgTerm（全部下标省略、SystemNoTarget 等），spec: VariableParser.cs:76,92,113,130,141。
 *
 * ⚠ 原版 VariableNoArgTerm **不是**「按下标 0 / 当前 TARGET 取值」，而是错误哨兵：
 * `GetIntValue` / `GetStrValue` / `GetValue` / `SetValue` / `ChangeValue` / `GetFixedVariableTerm`
 * **逐一抛 `CodeEE(MissingVarArg)`**（VariableNoArgTerm.cs:17-70）。本模型不带该拒绝节点，
 * 由存储层 [VariableReader] 的「下标个数必须等于变量声明的槽位数」校验承担同一拒绝
 * （VariableStore 的 checkElement / checkUserElementImpl：空下标数组在 2D/3D 与角色变量上
 * 都落 `indices.size != argCount` 分支）⇒ 求值结果同样是硬错误，只是文案不同。
 */
data class VarRef(
    val name: String,
    val indices: List<Expr>,
    val isNoArg: Boolean = false,
    override val operandType: OperandType,
    val isConst: Boolean = false,
    val subKey: String? = null,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        // 0 下标变量（绝大多数全局/角色变量读）复用同一份空数组：每个 VarRef 求值都 new 一个
        // `LongArray(0)` 会在世界初始化这种百万级访问里制造无谓的短命对象。
        val n = indexArray.size
        if (n == 0) {
            val reader = ctx.variables
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readIntH(h, NO_INDICES)
            return reader.readInt(name, NO_INDICES)
        }
        // 下标缓冲向读取方**借**（见 VariableReader.borrowIndices）：带下标的访问是环体里的
        // 高频路径，逐次 new LongArray 会把 GC 喂饱；借还按深度栈式复用，可重入。
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            return try {
                if (h != VariableHandles.NONE) (reader as VariableHandles).readIntH(h, idx)
                else reader.readInt(name, idx)
            } catch (e: CodeEE) {
                // 4.981 内置 1 维数组是 SparseArray 背板：**运行期**下标越界读返默认值、不抛
                // （PC 实测判词见 specs/variables.yaml 的 sparse_1d_backplane）。严格通道先
                // 抛出、再拿这次抛错当判据，比每次访问都先查一遍维度长度便宜：热路径零额外
                // 开销，只有真越界那一发多走一趟存储层。
                if (lastIndexIsDynamic(indexArray)) reader.readSparseIntOor(name, idx) else throw e
            }
        } finally {
            reader.returnIndices()
        }
    }

    override fun getStrValue(ctx: EvalContext): String {
        val n = indexArray.size
        if (n == 0) {
            val reader = ctx.variables
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).readStrH(h, NO_INDICES)
            return reader.readStr(name, NO_INDICES)
        }
        val reader = ctx.variables
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = true)
            return try {
                if (h != VariableHandles.NONE) (reader as VariableHandles).readStrH(h, idx)
                else reader.readStr(name, idx)
            } catch (e: CodeEE) {
                // 同 [getIntValue]：运行期下标的稀疏越界读返空串、不抛。
                if (lastIndexIsDynamic(indexArray)) reader.readSparseStrOor(name, idx) else throw e
            }
        } finally {
            reader.returnIndices()
        }
    }

    // ------------------------------------------------------------------
    // 写（PERF 2026-10-27）：语句层写路径接站点句柄 + 下标借还池
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 浮点读（4.981 fork 第三型变量）
    // ------------------------------------------------------------------

    /**
     * 浮点变量读：走 [VariableReader.readFloat]，**不走**默认的「整型通道宽化」。
     *
     * 为什么必须覆写：接口缺省实现是 `getIntValue(ctx).toDouble()`（[Expr.getFloatValue]），
     * 对本结点意味着把浮点变量当整数列读 —— `LOCALF = 1.25` 之后 `LOCALF * 2` 会算出 2.0
     * 而不是 2.5，即「编译过、执行错」。覆写后非浮点结点仍按原宽化，行为逐字不变。
     *
     * 为什么**不做句柄缓存**（与 [getIntValue] 的 `bindHandle` 不同）：浮点变量在语料里只有
     * 两条触发点（`DLC/魔法DLC/使用魔法.ERB:1248-1256`、`魔法相关函数.ERB:3190-3219`），
     * 不在 `FOR LOCAL` 那种百万级环体上；而句柄空间（`VariableHandles`）按整数/字符串
     * 两通道解析，给它加第三条通道要动的是**热路径**的缓存作废判据。冷路径按名读一次哈希，
     * 换零改动面 —— 若日后浮点进入热路径，再按 `bindInt` 那套加 `bindFloat`。
     *
     * `isNoArg` 形态同样按名读，保证「缺下标」的报错口径与整数/字符串侧同出一条
     * （见类 KDoc 的 VariableNoArgTerm 段）。
     */
    override fun getFloatValue(ctx: EvalContext): Double {
        if (operandType != OperandType.FLOAT) return getIntValue(ctx).toDouble()
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) return reader.readFloat(name, NO_INDICES)
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            return reader.readFloat(name, idx)
        } finally {
            reader.returnIndices()
        }
    }

    /** 同 [setIntValue] 的浮点侧；下标求值与借还口径逐字相同。 */
    fun setFloatValue(ctx: EvalContext, value: Double) {
        val n = indexArray.size
        val reader = ctx.variables
        val writer = writerOf(reader)
        if (n == 0) {
            writer.writeFloat(name, NO_INDICES, value)
            return
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            writer.writeFloat(name, idx, value)
        } finally {
            reader.returnIndices()
        }
    }

    /** 同 [addIntValue] 的浮点侧（`+= 常量` 的落点）；返回加完的新值。 */
    fun addFloatValue(ctx: EvalContext, delta: Double): Double {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) return reader.plusFloat(name, NO_INDICES, delta)
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            return reader.plusFloat(name, idx, delta)
        } finally {
            reader.returnIndices()
        }
    }

    /**
     * 把 [value] 写进本访问点（`SET X = …` 的落点）。
     *
     * ## 为什么放在 `VarRef` 上，而不是让语句层自己拼
     *
     * 读路径（[getIntValue]）在 T2-61 就拿到了两样东西：**访问点自己的句柄缓存**
     * （[bindInt]，结构上零冲突）与**下标借还池**（[VariableReader.borrowIndices]）。
     * 语句层写路径两样都没用 —— 它走 `ctx.variables.writeInt(name, idx, value)`，
     * 其中 `name` 每次都要过一次 `bindOf` 的 512 槽直映（真产物实测冲突率 66.5%），
     * `idx` 每次 `LongArray(n)` 现分配（JFR 分配画像里 `resolveIndices` 83.7 MB，
     * **全应用第 5 大分配点**，而 `SET` 占这个负载语句执行的 99.9%）。
     *
     * 搬到 [VarRef] 上之后，句柄缓存与借还池**自动复用读路径那一套**，
     * 语句层只剩一次虚调用。
     *
     * ## 语义红线
     *
     * - 句柄解析不出（[VariableHandles.NONE]）或 [isNoArg]：**原样回落按名路径**
     *   —— 迟到的 `#DIM` 必须能被后续写看见（`bindHandle` 在 NONE 时不缓存），
     *   `isNoArg` 的错误文案也必须照旧。
     * - 按句柄写与按名写共用同一个函数体（`VariableStore.writeIntEntry`），
     *   写观察口收到的名字仍是 [name] 的**原文拼写**（`VarWriteProbe.watches` 大小写敏感）。
     */
    fun setIntValue(ctx: EvalContext, value: Long) {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) {
                (reader as VariableHandles).writeIntH(h, name, NO_INDICES, value)
            } else {
                writerOf(reader).writeInt(name, NO_INDICES, value)
            }
            return
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            try {
                if (h != VariableHandles.NONE) (reader as VariableHandles).writeIntH(h, name, idx, value)
                else writerOf(reader).writeInt(name, idx, value)
            } catch (e: CodeEE) {
                // 同 [getIntValue]：运行期下标的稀疏越界写落溢出层（PC 侧就是写进
                // SparseArray 字典，随后读得回来）；常量下标与稠密数组照旧抛。
                if (lastIndexIsDynamic(indexArray)) writerOf(reader).writeSparseIntOor(name, idx, value) else throw e
            }
        } finally {
            reader.returnIndices()
        }
    }

    /**
     * `SET X += delta` / `++` / `--` 的落点；返回写入后的新值（与 `plusInt` 一致）。
     *
     * 与 [setIntValue] 同一套句柄/借还口径。注意 `+=` 的**语义**在语句层已经被解析期
     * 折成「整式放进 Term」或「IsConst + AddConst」，这里只负责把算好的增量落进变量。
     */
    fun addIntValue(ctx: EvalContext, delta: Long): Long {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = false)
            if (h != VariableHandles.NONE) return (reader as VariableHandles).plusIntH(h, NO_INDICES, delta)
            return reader.plusInt(name, NO_INDICES, delta)
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = false)
            return try {
                if (h != VariableHandles.NONE) (reader as VariableHandles).plusIntH(h, idx, delta)
                else reader.plusInt(name, idx, delta)
            } catch (e: CodeEE) {
                // `+=` 在稀疏背板上等价于「读默认值 0 再加」⇒ 返回值就是 delta（PC 实测
                // `ITEM:-1 += 5` 之后读回 5）。
                if (lastIndexIsDynamic(indexArray)) reader.plusSparseIntOor(name, idx, delta) else throw e
            }
        } finally {
            reader.returnIndices()
        }
    }

    /** 同 [setIntValue] 的字符串侧。 */
    fun setStrValue(ctx: EvalContext, value: String) {
        val n = indexArray.size
        val reader = ctx.variables
        if (n == 0) {
            val h = bindHandle(reader, wantStr = true)
            if (h != VariableHandles.NONE) {
                (reader as VariableHandles).writeStrH(h, name, NO_INDICES, value)
            } else {
                writerOf(reader).writeStr(name, NO_INDICES, value)
            }
            return
        }
        val idx = reader.borrowIndices(n)
        try {
            for (i in 0 until n) idx[i] = indexArray[i].getIntValue(ctx)
            val h = bindHandle(reader, wantStr = true)
            try {
                if (h != VariableHandles.NONE) (reader as VariableHandles).writeStrH(h, name, idx, value)
                else writerOf(reader).writeStr(name, idx, value)
            } catch (e: CodeEE) {
                // 同 [setIntValue] 的字符串侧（PC 实测 `STR:-1 = "ab"` 读回 "ab"）。
                if (lastIndexIsDynamic(indexArray)) writerOf(reader).writeSparseStrOor(name, idx, value) else throw e
            }
        } finally {
            reader.returnIndices()
        }
    }

    /**
     * 回落路径的写通道探测：读写是**独立能力**（[VariableWriter]，T2-23 的口径），
     * 拿不到就显式失败，绝不猜。这条路径只在「句柄解析不出」时走到
     * （`isNoArg` 错误哨兵 / 迟到的 `#DIM` / 只读宿主），所以探测本身不在热路径上。
     */
    private fun writerOf(reader: VariableReader): VariableWriter =
        reader as? VariableWriter
            ?: throw com.eratw.port.core.err.CodeEE(
                "变量池不支持写入（未实现 VariableWriter）：$name",
            )

    // ------------------------------------------------------------------
    // 装期绑定（T2-61）：访问点自己记住「名字 → 句柄」
    // ------------------------------------------------------------------

    /**
     * 该访问点缓存的整型 / 字符串句柄，键 = `(读取方身份, 作用域身份)`。
     *
     * ## 为什么按访问点缓存，而不是继续用按名字的全局表
     *
     * 全局表（`VariableStore.bindOf`，512 槽直接映射）在真产物实测里**命中率只有 33.45%**：
     * `t61.jfr` 一轮取证 2.75 亿次变量访问，其中 1.83 亿次（66.5%）是「同一槽里是另一个
     * 名字」—— 语料的热名字集合比 512 大得多，任何按名字直接映射的表都会被冲干净，而加大
     * 表又会让随机下标跳出 L1（4096 槽实测慢 12%）。按访问点缓存没有这个问题：每个
     * `VarRef` 自己就是一个槽，结构上不存在冲突。
     *
     * ## 键为什么还要带作用域
     *
     * 私有 `#DIM` 变量的句柄只在声明它的函数里有效。[VariableHandles.nameScoped] 告诉我们
     * 这个名字是否绑作用域 —— 全局/系统变量不绑（跨调用恒命中；实测作用域键只贡献 0.03%
     * 的未命中），只有「某处声明过同名私有 `#DIM`」的名字才比较 [VariableReader.scopeKey]。
     *
     * ## 不可缓存的形态
     *
     * - `isNoArg`（原版 `VariableNoArgTerm` 的错误哨兵，含依赖当前 `TARGET` 的形态，
     *   语义红线 §4 第 7 条）：一律回退按名路径，保证报错文案与触发条件不变。
     * - 解析不出（[VariableHandles.NONE]）：不缓存 —— 迟到的 `#DIM` 必须能被后续求值看见。
     *
     * ## 并发前提
     *
     * 这些字段是 `VarRef` 上的**可变**状态，只在 VM 线程上读写（ADR-003 单 VM 线程；
     * 脚本无法在调用内并行）。与它同一前提的还有 `VariableStore` 的 `frames` /
     * `currentPrivMap` / 绑定缓存本身 —— 这一层不是新增的并发要求，而是既有的。
     *
     * ## 为什么全部 `@Transient`
     *
     * 它们是**求值期状态**，不是 AST 模型的一部分。模型有四处反射遍历会看到实例字段：
     * `ArgInterner.buildShape`（装载期折叠判等）、`ProgramDiagnostics` 的 shape 探针
     * （真机 pool-audit 输出契约）、`ArgSignatureCheck.fieldsOf`（符号审计下降）、
     * `data class` 的 `equals`/`toString`（后者不进字段遍历，但同样只认构造参数）。
     * 前三处已按 `Modifier.isTransient` 跳过（与 `KeywordIndexArg` 的
     * `memoKeyword`/`memoValue`/`memoReader` 同一档：**运期缓存不属于结点结构**）。
     *
     * 不跳过的后果不是「测试要改期望」那么轻：`ArgInterner` 会把缓存里的
     * `VariableStore` 当成结点子图继续下降（实测在 e2e 全量执行后触发
     * `InaccessibleObjectException`，反射进 `java.util.HashSet` 的内部字段），
     * 且「同一结点在不同求值阶段」会被判成不同形态 ⇒ 去重漏合并。
     */
    @Transient private var bindReader: Any? = null
    @Transient private var bindScope: Any? = null
    @Transient private var bindScopeSensitive: Boolean = false
    @Transient private var bindInt: Int = VariableHandles.NONE
    @Transient private var bindStr: Int = VariableHandles.NONE

    private fun bindHandle(reader: VariableReader, wantStr: Boolean): Int {
        if (isNoArg) return VariableHandles.NONE
        val handles = reader as? VariableHandles ?: return VariableHandles.NONE
        val cached = if (wantStr) bindStr else bindInt
        if (bindReader === reader && cached != VariableHandles.NONE) {
            if (!bindScopeSensitive || bindScope === reader.scopeKey()) return cached
            // 作用域换了：两条句柄都可能指向上一条作用域的私有实例，一起作废。
            bindInt = VariableHandles.NONE
            bindStr = VariableHandles.NONE
        }
        if (bindReader !== reader) {
            // 读取方换了（「同一个 AST 配不同变量池」是设计内场景：测试夹具、宿主换 store
            // 重跑 —— `Vm.varHandleOwner` 的同款口径）。句柄是**变量池私有**的下标，跨池
            // 无意义；两个方向必须一起作废 —— 只重绑当前方向的话，另一方向还挂着旧池的
            // 句柄号，之后在同一池命中它就是拿旧池的下标读新池（同号时是静默错值，越界时
            // 才退化成「句柄无效」）。
            bindInt = VariableHandles.NONE
            bindStr = VariableHandles.NONE
            bindScopeSensitive = false
            bindScope = null
        }
        val h = if (wantStr) handles.resolveStr(name) else handles.resolveInt(name)
        if (h == VariableHandles.NONE) return VariableHandles.NONE
        bindReader = reader
        bindScopeSensitive = handles.nameScoped(name)
        bindScope = if (bindScopeSensitive) reader.scopeKey() else null
        if (wantStr) bindStr = h else bindInt = h
        return h
    }

    /**
     * 下标的数组形态（构造期一次转换）。
     *
     * `indices` 是 `List<Expr>`（编译期形态），热路径上 `ArrayList.get` + `Objects.checkIndex`
     * 在真机采样里占 `VarRef` 求值的相当一块；数组下标省掉边界检查的虚调用与装箱边界。
     * 列表本身仍保留在构造参数里（数据类契约/相等性不变）。
     */
    private val indexArray: Array<Expr> = indices.toTypedArray()

    // ------------------------------------------------------------------
    // 4.981 稀疏 1 维背板：动态下标越界不是错误（见 Eval.kt 的 readSparseIntOor 一族）
    // ------------------------------------------------------------------

    private companion object {
        /** 0 下标共享空数组（只读；存储层只读不写）。 */
        private val NO_INDICES = LongArray(0)
    }
}

/**
 * [VarRef] 的最后一个下标是不是**运行期才定**的值（PC 侧 `VariableTerm.Restructure` 对
 * `SingleTerm` 折得动的常量实参走解析期 `CheckElement`，折不动的才留到运行期）。
 *
 * 稀疏背板的容忍语义只对运行期下标开放：解析期常量下标在 4.981 仍然硬抛 OoRVarArg
 * （PC 实测矩阵见 specs/variables.yaml 的 sparse_1d_backplane），照旧走存储层严格通道、
 * 文案逐字不变。判据只依赖 AST、且只在**严格通道抛错后的补径**上求值 ⇒ 热路径零成本。
 *
 * 为什么是文件级函数而不是 [VarRef] 的成员：给 `VarRef` 加函数会顶破 detekt 的类函数条数
 * 阈值，加字段则会改掉 `ProgramDiagnostics` 的 shape 探针快照（真机 logcat 判读面，
 * `ProgramDiagnosticsTest` 钉着逐行格式）。
 */
private fun lastIndexIsDynamic(indices: Array<Expr>): Boolean = !indices[indices.size - 1].isCompileTimeConst()

/**
 * 字符串关键字下标包装（`ITEM:药` 之类）。spec: VariableParser.cs:182-188（VariableStrArgTerm 包装）。
 * 求值时经 VariableReader.keywordToInteger 转成整数下标。
 *
 * ## 为什么带一层记忆（PERF 2026-10-26）
 *
 * 关键字表（`ConstantData` 的 CSV 名表 / ERD 表 / 角色名表）**装载完成后就是只读的**，
 * 而同一个 [KeywordIndexArg] 结点在整局里被求值上百万次（`CFLAG:LOCAL:约会中` 这种下标
 * 在每次 `FOR LOCAL, 1, CHARANUM` 环体里都要重算）。原实现每次求值都走
 * `keywordToInteger` → `VariableRegistry.keywordIndex` → `csvTable`（线性扫 29 张表 +
 * 每次 `substringBefore('.')` 新建 String + `equals(ignoreCase)`），真机采样里这条链
 * 独占 44% 的脚本时间（`jfr-turn.jfr`：877/1989 样本入栈）。
 *
 * 记忆判据用「关键字串 == 上次的关键字串 && 读取方 == 上次的读取方」：
 * - 关键字串：绝大多数语料是字面量 ⇒ 同一 String 实例，`==` 先走引用比较，命中即返回；
 *   非常量关键字（`ITEM:GETSTR(...)`）退化成一个内容比较后重算，行为不变。
 * - 读取方：`VariableReader` 实例 = 一份变量存储。同一结点被两份存储求值（装置里
 *   把一份 Program 喂给两个 `VariableStore`）时不会串值。
 * - **解析失败不记忆**：`keywordToInteger` 抛出的 `CodeEE` 每次都照抛，错误时机与文案不变
 *   （折叠到装载期会把「运行期才报的未定义关键字」变成装载失败，属语义变更，不做）。
 *
 * 两个记忆字段是**派生状态**，不进结构判等/去重：`ArgInterner.buildShape` 按名跳过
 * （与 `VarRef.indexArray` 同款处置，见该处 KDoc）。
 */
data class KeywordIndexArg(
    val expr: Expr,
    val varName: String,
    val dim: Int,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    /** 上次解析成功时的关键字串；null = 尚未解析过。 */
    @Volatile
    private var memoKeyword: String? = null

    /** 上次解析成功时的结果。 */
    @Volatile
    private var memoValue: Long = 0L

    /** 上次解析成功时的读取方（`VariableReader` 实例身份）。 */
    @Volatile
    private var memoReader: Any? = null

    override val operandType get() = OperandType.INT

    override fun getIntValue(ctx: EvalContext): Long {
        val reader = ctx.variables
        val keyword = expr.getStrValue(ctx)
        if (keyword == memoKeyword && reader === memoReader) return memoValue
        val v = reader.keywordToInteger(varName, dim, keyword)
        memoKeyword = keyword
        memoValue = v
        memoReader = reader
        return v
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 函数调用
// ---------------------------------------------------------------------------

/**
 * 函数调用。解析期由 [IdentifierResolver.resolveFunction] 判定存在性与返回类型：
 * 未命中时解析器按原版 `IdentifierDictionary.ThrowException` 报错（spec: ExpressionParser.cs:376-392）；
 * 命中即用该类型参与静态类型检查（原版 `GetFunctionMethod` 在解析期返回带类型的对象）。
 * TODO(verify): 参数个数/类型检查（C# `CheckArgumentType`，spec: IdentifierDictionary.cs:580-584）仍属绑定层。
 *
 * args 允许 null 元素（参数空位），见 reduceArguments。
 */
data class FuncCall(
    val name: String,
    val args: List<Expr?>,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        val r = ctx.callFunctionRef(name, args)
        return (r as? Value.IntV)?.v ?: throw ExprEvalException("函数 $name 返回了非整数结果")
    }

    override fun getStrValue(ctx: EvalContext): String {
        val r = ctx.callFunctionRef(name, args)
        return (r as? Value.StrV)?.v ?: throw ExprEvalException("函数 $name 返回了非字符串结果")
    }

    /**
     * 浮点求值通道。
     *
     * 只有**静态类型是 FLOAT** 的调用（`TOFLOAT(...)` 一类）走本覆写；整型返回值的调用
     * （`ADD(...) * 0.5`、浮点左值 `LOCALF = 整型函数()`）必须按 [Expr.getFloatValue] 的
     * 默认实现宽化（`getIntValue().toDouble()`）。二者同构于 [VarRef.getFloatValue] 与
     * [Binary.getFloatValue] 的 `if (operandType != FLOAT)` 前置判据，也是执行层浮点赋值的
     * 公开前提（`sem/StatementExecutor.kt:4588-4589` 明写「逐项 getFloatValue 对 INT 右值
     * 自动宽化」）。
     *
     * 2026-11-02 审查修复：此前本覆写**无条件**要求 `Value.FloatV`，于是 `ADD() * 0.5`
     * 一类形态抛「函数 ADD 返回了非浮点结果」，与 [Expr.getFloatValue] 的 KDoc
     * （「返回 float 的 [FuncCall] 各自覆写」）自相矛盾，也与执行层注释对不上。
     * fork 侧对应形态 = 整型子式进 double 通道（`LOCALF = 整型函数()`、`整型函数() * 0.5`）。
     */
    override fun getFloatValue(ctx: EvalContext): Double {
        if (operandType != OperandType.FLOAT) return getIntValue(ctx).toDouble()
        val r = ctx.callFunctionRef(name, args)
        return (r as? Value.FloatV)?.v ?: throw ExprEvalException("函数 $name 返回了非浮点结果")
    }
}

// ---------------------------------------------------------------------------
// 一元 / 后置一元
// ---------------------------------------------------------------------------

/** 前置一元：- ! ~ ++ --（+ 在解析期被消除，直接返回操作数）。spec: OperatorMethodManager.cs:537-599 */
data class Unary(
    val op: OperatorCode,
    val operand: Expr,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    init {
        require(op == OperatorCode.MINUS || op == OperatorCode.NOT || op == OperatorCode.BIT_NOT ||
            op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT)
    }

    override val operandType get() = operand.operandType

    override fun getIntValue(ctx: EvalContext): Long {
        // 浮点操作数没有整型通道（见 [FloatLiteral] 的 KDoc）：解析期只放行 MINUS/PLUS 两种
        // 一元运算作用于浮点（Parser.kt reduceUnaryTerm），走到这里说明被当整型使用。
        if (operand.operandType == OperandType.FLOAT) {
            throw ExprEvalException("浮点表达式不能直接用于整型上下文（需 TOINT 转换）")
        }
        return when (op) {
            OperatorCode.MINUS -> {
                // 4.981 MinusInt（OperatorMethod.cs:753-765）只取**一次**操作数交给
                // SafeArithmetic.SafeNegate（SafeArithmetic.cs:52-65）：`-Long.MIN_VALUE`
                // 溢出时 PrintWarning 并返回 **MAX**（不是回绕成 MIN）。
                //
                // 1.824 的 MinusInt（OperatorMethodManager.cs:559-566）是「先取一次判
                // MIN_VALUE 打系统行、再取第二次求值取负」，移植侧此前照抄成双求值 + 回绕
                // （EX-3）；4.981 把这条双求值收成单次调用 ⇒ 操作数里的副作用（RAND 抽号、
                // `++`）只发动一次，返回值也不再回绕。语料侧 13 处 `-RAND` 的 RNG 序列
                // 随原版口径前移（golden E2E 不含 CASINO 无回归）。
                SafeArithmetic.negate(operand.getIntValue(ctx)) { ctx.reportArithmeticOverflow(it) }
            }
            OperatorCode.NOT -> if (operand.getIntValue(ctx) == 0L) 1L else 0L
            OperatorCode.BIT_NOT -> operand.getIntValue(ctx).inv()
            OperatorCode.INCREMENT -> changeValue(ctx, 1L)
            OperatorCode.DECREMENT -> changeValue(ctx, -1L)
            else -> throw ExprEvalException("非法一元运算符 $op")
        }
    }

    private fun changeValue(ctx: EvalContext, delta: Long): Long {
        // spec: OperatorMethodManager.cs:609-627 → VariableTerm.ChangeValue（VariableTerm.cs:218-235）
        // —— 下标只求值一遍，PlusValue 返回加完的新值；旧值不单独读。
        //
        // 2026-11-17（wave/rpt-sparse）：落点改走 [VarRef.addIntValue]，与语句层
        // `SET X += n` 共用「严格通道 → 只有动态下标才转稀疏背板」这一套口径
        // （c45357a86）。此前这里自己 `borrowIndices` + `reader.plusInt`，**绕开了稀疏补径**，
        // 于是 `X = ++ITEM:IDX`（IDX 运行期为 -1）照抛「数组变量"ITEM"的第1参数(-1)超出数组范围」；
        // 而原版 `ChangeValue`（VariableTerm.cs:218-235）转交 `Identifier.PlusValue`，1 维数组
        // 走 SparseArray 下标器（Runtime/Script/SparseArray.cs:25-40）⇒ 缺省 0 加增量写回字典，
        // 应得 1。顺带恢复访问点句柄快径，不再逐次按名查表。
        return (operand as VarRef).addIntValue(ctx, delta)
    }

    /**
     * 浮点取负/取正（4.981 升版）。`!` / `~` 没有浮点定义（解析期已拒）。
     *
     * ## 表达式位 `++`/`--` 不作用于浮点：g4 取证结论 = **保持拒绝，不放行**
     *
     * 判据是 4.981 fork 源码（`qirchen421/emuera.sk` 的 commit `48494165`，即 specs 里
     * `fork-src` 前缀指向的同一份）；1.824 参考实现的表达式层根本没有浮点
     * （AExpression.cs:5-40 只有 long/string，整个 Expression 目录 grep `double` 零命中，
     * OperatorMethodManager.cs:717-720 的一元登记只有整数四条，
     * VariableTerm.cs:202-223 的 `ChangeValue` 只收 `long`），做不了这条的正反证。
     *
     * - 前置 `ReduceUnaryTerm`（OperatorMethod.cs:126-163）的浮点支 :143-149 **只**登记
     *   Plus（原样返回操作数）与 Minus（MinusFloat），`Increment`/`Decrement` 不落 `method`
     *   ⇒ 落到 :152-162 抛错，类型名取 `Error.FloatType`（zhs「浮点型」）、模板
     *   `Error.CanNotAppliedUnaryOp`（「无法对{0}使用单相运算符」加运算符串）；
     * - 后置 `ReduceUnaryAfterTerm`（:165-191）更窄，只有 Integer 支取 `unaryAfterDic`；
     * - 登记面 :44-48 只有 IncrementInt / DecrementInt / IncrementAfterInt / DecrementAfterInt。
     *
     * 对照面：**语句位**浮点 `++`/`--` 在 fork 里确实开放 —— `VariableTerm.ChangeValue`
     * 带 double 重载（VariableTerm.cs:225-240，转交 `Identifier.PlusValue`，返回加完的新值）。
     * 所以本仓「语句位放行（[com.eratw.port.core.stmt.ArgBuilders] 的 set 浮点支折成
     * `x = x ± 1.0`）+ 表达式位拒绝」与 fork **同构**，不是待补的缺口。4.981 语料侧同样
     * 零命中：全库没有任何浮点变量的 `++`/`--` 形态（判据用例见
     * `core/e2e/FloatCorpusE2ETest` 的「全语料无浮点变量的自增自减形态」）。
     *
     * 偏离的处置（路次 g5 更新）：
     * 1. **已按 ADR-005 复刻官方文案缺陷**（g4 登记的第 4 条）：前置支按 fork 报
     *    「无法对**浮点型**使用单相运算符"++"」，后置支按 fork 的类型名 else 链漏判报
     *    「无法对**未知类型**使用单相运算符"++"」；两支都在 `Parser.kt` 的
     *    `reduceUnaryTerm` / `reduceUnaryAfterTerm` 里，模板逐字取 zhs xml:310。
     *    两支不一致是官方的，本仓照抄，不做「顺手统一」。
     * 2. 仍未对齐：报错时机 —— fork 在装载期的 `Restructure` 抛 `CodeEE`，本仓在表达式
     *    归约期抛 `ExprEvalException` ⇒ 同为硬错误，但异常族与定位行不同。
     * 3. [getFloatValue] 的 else 支（「一元运算符 X 不能作用于浮点型」）在源码解析下**走不到**
     *    —— 解析闸已把非 `+ -` 的浮点一元全拒；留着只防 AST 直构（夹具/旧 erbc）绕过闸。
     */
    override fun getFloatValue(ctx: EvalContext): Double = when (op) {
        OperatorCode.MINUS -> -operand.getFloatValue(ctx)
        else -> throw ExprEvalException("一元运算符 ${OperatorCode.toOperatorString(op)} 不能作用于浮点型")
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

/** 后置 ++/--。spec: OperatorMethodManager.cs:629-655 —— 返回自增/自减前的旧值 */
data class UnaryAfter(
    val op: OperatorCode,
    val operand: Expr,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    init {
        require(op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT)
    }

    override val operandType get() = OperandType.INT

    override fun getIntValue(ctx: EvalContext): Long {
        val vr = operand as VarRef
        // spec: OperatorMethodManager.cs:629-655（IncrementAfterInt / DecrementAfterInt）——
        // 先读改写一次、返回**改写前**的旧值。
        //
        // 2026-11-17（wave/rpt-sparse）：同 [Unary.changeValue] 改走 [VarRef.addIntValue]，
        // 否则 `X = ITEM:IDX++`（IDX 运行期为 -1）同样绕开稀疏补径照抛 OoRVarArg
        // （原版 VariableTerm.cs:218-235 → SparseArray.cs:25-40）。旧值 = 新值 ∓ 1 的口径不变：
        // 稀疏背板上 `ITEM:-1++` 的旧值 0、新值 1（读默认 0 后加 1 写回字典）。
        //
        // 溢出（4.981，OperatorMethod.cs:853-863 / :877-887）：`X = MAX; X++` 的新值被
        // VariableToken.cs:752 的 SafeAdd 钳成 MAX，旧值 = MAX-1；`--` 对 MIN 对称得
        // MIN+1。这里 `newVal ∓ 1` 恒不溢出（MAX-1 / MIN+1 都在域内），与 4.981 catch
        // 分支的返回值同值；告警由 plusIntEntry 的 SafeAdd 打一次（catch 那条不可达）。
        val newVal = vr.addIntValue(ctx, if (op == OperatorCode.INCREMENT) 1L else -1L)
        return if (op == OperatorCode.INCREMENT) newVal - 1 else newVal + 1
    }

    override fun getStrValue(ctx: EvalContext) = ""
}

// ---------------------------------------------------------------------------
// 二元 / 三目
// ---------------------------------------------------------------------------

data class Binary(
    val op: OperatorCode,
    val left: Expr,
    val right: Expr,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long {
        // 字符串比较（== != > < >= <=）：spec: OperatorMethodManager.cs:159-373
        // 注意：比较结果的 operandType 是 INT，必须按**操作数**类型分派
        if (left.operandType == OperandType.STR && right.operandType == OperandType.STR) {
            return strCompare(op, ctx, left, right)
        }
        // 浮点参与（4.981 升版）：解析期只在**比较**运算上给出 INT 结果
        // （Parser.kt reduceBinaryTerm 的提升表），其余浮点算术一律拒绝整型通道。
        if (left.operandType == OperandType.FLOAT || right.operandType == OperandType.FLOAT) {
            return floatCompareToLong(ctx)
        }
        return when (op) {
            // && 短路：原版 if (arg0 != 0 && arg1 != 0)，arg0==0 时 arg1 不求值。
            // spec: OperatorMethodManager.cs:383-391
            OperatorCode.AND ->
                if (left.getIntValue(ctx) == 0L) 0L else if (right.getIntValue(ctx) != 0L) 1L else 0L
            // || 短路：spec: OperatorMethodManager.cs:393-409
            OperatorCode.OR ->
                if (left.getIntValue(ctx) != 0L) 1L else if (right.getIntValue(ctx) != 0L) 1L else 0L
            // 4.981 整数除/余走 SafeArithmetic（SafeArithmetic.cs:67-84），调用点
            // `OperatorMethod.cs:374/:388` 是
            //   `SafeDivide(arguments[0].GetIntValue(exm), arguments[1].GetIntValue(exm))`
            // ⇒ **求值序变成左先右后**（C# 实参左到右），与 1.824「先取除数判 0」
            // （OperatorMethodManager.cs:121-157）相反。b == 0 时 PrintWarning 并返回 0，
            // 不再抛 DivideByZero —— 真机「除数为0的除法运算」那一簇即由此中断脚本。
            // 副作用顺序随之变：左子树的 RAND / ++ 现在会照常求值一次（不是「为取证补读」）。
            OperatorCode.DIV, OperatorCode.MOD -> {
                val l = left.getIntValue(ctx)
                val r = right.getIntValue(ctx)
                if (r == 0L) {
                    ctx.printSystemLine(zeroDivideWarning(l, op == OperatorCode.MOD))
                    0L
                } else if (op == OperatorCode.DIV) {
                    // `long.MinValue / -1` 是 .NET 唯一会溢出的整除：C# 抛 OverflowException，
                    // JVM 的 ldiv 静默回绕成 MIN_VALUE。按原版抛错，不回绕。
                    // C# 整除向零取整、% 符号随被除数，与 Kotlin Long./ 与 Long.% 语义一致。
                    if (l == Long.MIN_VALUE && r == -1L) {
                        throw ExprEvalException("除算溢出：-9223372036854775808 / -1")
                    }
                    l / r
                } else {
                    // `long.MinValue % -1` 在 C# 与 JVM 都得 0（ECMA 除余只在**除法**上抛溢出），不复刻
                    l % r
                }
            }
            // 与非/或非：原版是 C# 的 `arg0 == 0 || arg1 == 0` / `arg0 == 0 && arg1 == 0`，
            // **左操作数已决定结果时右侧不求值**（`&&`/`||` 的短路）——副作用与 RAND 消耗随之跳过。
            // spec: OperatorMethodManager.cs:439-446（NandIntInt，判据在 :441）、
            //       同文件 :457-464（NorIntInt，判据在 :459）
            OperatorCode.NAND ->
                if (left.getIntValue(ctx) == 0L) 1L else if (right.getIntValue(ctx) == 0L) 1L else 0L
            OperatorCode.NOR ->
                if (left.getIntValue(ctx) != 0L) 0L else if (right.getIntValue(ctx) == 0L) 1L else 0L
            else -> {
                val l = left.getIntValue(ctx)
                val r = right.getIntValue(ctx)
                // 4.981 的整型 + / - / * 已从「unchecked 回绕」改成 SafeArithmetic 钳制
                // （`OperatorMethod.cs:278` / :307 / :321 → `SafeArithmetic.cs:7-50`）：
                // 溢出时 PrintWarning（「整数溢出: a op b」）并饱和到 MAX/MIN，不中断脚本。
                // 求值序不变（左先右后，C# 实参左到右），副作用次数也不变。
                when (op) {
                    OperatorCode.PLUS -> SafeArithmetic.add(l, r) { ctx.reportArithmeticOverflow(it) }
                    OperatorCode.MINUS -> SafeArithmetic.subtract(l, r) { ctx.reportArithmeticOverflow(it) }
                    OperatorCode.MULT -> SafeArithmetic.multiply(l, r) { ctx.reportArithmeticOverflow(it) }
                    OperatorCode.EQUAL -> if (l == r) 1L else 0L
                    OperatorCode.NOT_EQUAL -> if (l != r) 1L else 0L
                    OperatorCode.GREATER -> if (l > r) 1L else 0L
                    OperatorCode.LESS -> if (l < r) 1L else 0L
                    OperatorCode.GREATER_EQUAL -> if (l >= r) 1L else 0L
                    OperatorCode.LESS_EQUAL -> if (l <= r) 1L else 0L
                    // ^^ 原版先把两个操作数取进局部变量，**不**短路（两侧都求值）。
                    // spec: OperatorMethodManager.cs:411-429
                    OperatorCode.XOR -> if ((l == 0L) != (r == 0L)) 1L else 0L
                    OperatorCode.BIT_AND -> l and r
                    OperatorCode.BIT_OR -> l or r
                    OperatorCode.BIT_XOR -> l xor r
                    // C# 对 64 位操作数的移位计数取低 6 位；JVM 同样按 63 取模，行为一致。
                    OperatorCode.RIGHT_SHIFT -> l shr r.toInt()
                    OperatorCode.LEFT_SHIFT -> l shl r.toInt()
                    else -> throw ExprEvalException("非法二元整数运算符 ${OperatorCode.toOperatorString(op)}")
                }
            }
        }
    }

    /**
     * 浮点比较 → 0/1（4.981 升版）。
     *
     * 解析期只把比较运算装配成「浮点操作数 + INT 结果」（Parser.kt reduceBinaryTerm），
     * 其余浮点运算（`+ - * / %`）的结点 operandType 是 FLOAT、走 [getFloatValue]，
     * 到位就说明整型通道被误用 ⇒ 报错而不隐式截断。
     */
    private fun floatCompareToLong(ctx: EvalContext): Long {
        val l = left.getFloatValue(ctx)
        val r = right.getFloatValue(ctx)
        return when (op) {
            OperatorCode.EQUAL -> if (l == r) 1L else 0L
            OperatorCode.NOT_EQUAL -> if (l != r) 1L else 0L
            OperatorCode.GREATER -> if (l > r) 1L else 0L
            OperatorCode.LESS -> if (l < r) 1L else 0L
            OperatorCode.GREATER_EQUAL -> if (l >= r) 1L else 0L
            OperatorCode.LESS_EQUAL -> if (l <= r) 1L else 0L
            else -> throw ExprEvalException("浮点表达式不能直接用于整型上下文（需 TOINT 转换）")
        }
    }

    /**
     * 浮点算术（4.981 升版）。`+ - * /` 按 IEEE-754 双精度求值；比较结点
     * （operandType=INT）走默认实现（[getIntValue] 的 0/1 宽化）。
     *
     * **没有 `%`**：fork-src 的两张浮点二元字典（float×float、int×float 混合）都没有
     * `OperatorCode.Mod`，全文件的取余只有 `ModIntInt` 一条 ⇒ 浮点取余在解析期就被拒
     * （`Parser.kt` 的 `reduceBinaryTerm` 浮点支，路次 g5 对齐）。此处不留 MOD 分支，免得
     * AST 直构（测试夹具、旧 erbc）绕过解析闸算出 fork 上不存在的结果。
     * 行号依据 `OperatorMethod.cs:80-89`（float×float 字典）、`:91-113`（int×float 混合字典）、
     * `:54`（ModIntInt 登记）、`:378`（ModIntInt 实现）在本机不可取证
     * （TODO(verify)：待 fork-src 就位后复核）。本机可达的替代依据：整型取余 `ModIntInt`
     * 实现见 `OperatorMethodManager.cs:140-157`、登记见 `:725`；本机全文无浮点 `%`，与上述同型。
     *
     * 求值序：**浮点 DIV 先右后左**，与整型通道**不同**（4.981 起分道，不是漏改）。
     * 整型支 `OperatorMethod.cs:374` 是 `SafeDivide(arguments[0], arguments[1])`，左先右后；
     * 浮点支 `DivFloatFloat` / `DivMixedFloat` 仍先取除数判零。
     *
     * 浮点除法判零硬错（2026-11-02 收口，原第 5 条偏离已对齐；2026-11-12 fork-src 复核）：
     * `OperatorMethod.cs:987`（DivFloatFloat）与 `:1137`（DivMixedFloat）都是
     * `if (divisor == 0.0) throw new CodeEE(trerror.DivideByZero.Text)`，不落 IEEE ±Infinity/NaN。
     * **反向证明**：`SafeArithmetic.cs:91-100` 虽定义了返回 NaN 的 `SafeFloatDivide`，但
     * 全仓无任何调用点，浮点除法字典挂的仍是上面两个硬错实现 ⇒ 4.981 的钳制只覆盖整数。
     * 文案依据 `emuera-zhs.xml:286`（`Error.DivideByZero` =「除数为0的除法运算」）。
     */
    override fun getFloatValue(ctx: EvalContext): Double =
        if (operandType != OperandType.FLOAT) getIntValue(ctx).toDouble()
        else when (op) {
            OperatorCode.PLUS -> left.getFloatValue(ctx) + right.getFloatValue(ctx)
            OperatorCode.MINUS -> left.getFloatValue(ctx) - right.getFloatValue(ctx)
            OperatorCode.MULT -> left.getFloatValue(ctx) * right.getFloatValue(ctx)
            OperatorCode.DIV -> {
                // 除数判零硬错（OperatorMethod.cs:987 / :1137，2026-11-12 fork-src 复核）：
                // 4.981 的 SafeArithmetic 钳制**只覆盖整数**；SafeFloatDivide 是未接线的死代码。
                // 不落 IEEE ±Infinity/NaN，文案见 emuera-zhs.xml:286。
                // 与上方整型支（零除→警告+0）刻意不同，别把两支合并。
                val r = right.getFloatValue(ctx)
                // 浮点通道只给表达式原文：Long 装不下 10.5 这类非整值，折损后的数会误导取证。
                if (r == 0.0) throw divModByZero(ctx, rightValue = null, leftValue = null)
                left.getFloatValue(ctx) / r
            }
            else -> throw ExprEvalException("非法的浮点二元运算符 ${OperatorCode.toOperatorString(op)}")
        }

    override fun getStrValue(ctx: EvalContext): String = when (op) {
        // 字符串语境的 `+` 重载：str+str 拼接。spec: OperatorMethodManager.cs:28-45
        OperatorCode.PLUS -> left.getStrValue(ctx) + right.getStrValue(ctx)
        // spec: OperatorMethodManager.cs:75-118 —— str*int（或 int*str）重复字符串
        OperatorCode.MULT -> {
            val intValue: Long
            val strValue: String
            if (left.operandType == OperandType.INT) {
                intValue = left.getIntValue(ctx)
                strValue = right.getStrValue(ctx)
            } else {
                strValue = left.getStrValue(ctx)
                intValue = right.getIntValue(ctx)
            }
            if (intValue < 0) {
                throw ExprEvalException(
                    renderMultiplyToStrLimit(MULTIPLY_NEGATIVE_TO_STR_TEMPLATE, intValue),
                )
            }
            if (intValue >= 10000) {
                throw ExprEvalException(
                    renderMultiplyToStrLimit(MULTIPLY_10K_TO_STR_TEMPLATE, intValue),
                )
            }
            if (strValue.isEmpty() || intValue == 0L) ""
            else strValue.repeat(intValue.toInt())
        }
        else -> throw ExprEvalException("非法二元字符串运算符 ${OperatorCode.toOperatorString(op)}")
    }
}

/**
 * 字符串比较求值，判据与 **4.981 反编译树**逐字对齐（specs/verify-notes.md §1）。
 * spec: OperatorMethod.cs:547 `GreaterEqualStrStr` 为 `c >= 0`、:578 `LessEqualStrStr` 为 `c <= 0`；
 *       GreaterStrStr `c > 0`（:484）、LessStrStr `c < 0`（:514）、Equal/NotEqual 直接串比。
 * 比较基准 = `Config.SCExpression`（Config.cs:523 为 `const StringComparison.Ordinal`）
 *   ⇒ 恒为序数比较，Kotlin String.compareTo 同口径。
 *
 * 方言（ENGC-4）：`>=`/`<=` 在四树里分两派 —— v24 把两处都编译成 `compare < 0`
 * （spec: OperatorMethod.cs:487/:518，官方 bug），em 与 4.981 是 `c >= 0` / `c <= 0`
 * （spec: em :478/:509；snake :547/:578）。选哪派由 [EvalContext.stringCompareDialect]
 * 决定，缺省 [StringCompareDialect.FIXED]（4.981 实跑口径）；v24 档由装配方下传
 * [StringCompareDialect.LEGACY_BOTH_LT]。
 *
 * 历史：移植早期按 **1.824** 的 `OperatorMethodManager.cs:331/:367`（两处均 `c < 0`）把 `>=`/`<=`
 * 复刻成反的；2026-11-08 按 4.981 校准回正（MODERN-T5）。这不再是「对/错」问题而是两档方言，
 * 缺省仍是 4.981 口径。语料对字符串 `>=`/`<=` 零命中，缺省档零漂移。
 *
 * **不得**把这里的 GREATER_EQUAL / LESS_EQUAL 拿去装配 `SELECTCASE` 的 `CASE lo TO hi`
 * 区间判定（CORE-01，2026-10-23）：原版的 TO 形态根本不走运算符重载，是 CaseExpression 里
 * 一段独立的 `string.Compare(...) <= 0` 判定（spec: CaseExpression.cs:62-70）。区间判定请用 [strCaseRange]。
 */
internal fun strCompare(op: OperatorCode, ctx: EvalContext, left: Expr, right: Expr): Long {
    val l = left.getStrValue(ctx)
    val r = right.getStrValue(ctx)
    val c = l.compareTo(r)  // String.Compare(..., StringComparison.Ordinal) ≙ Kotlin String.compareTo
    return when (op) {
        OperatorCode.EQUAL -> if (c == 0) 1L else 0L
        OperatorCode.NOT_EQUAL -> if (c != 0) 1L else 0L
        OperatorCode.GREATER -> if (c > 0) 1L else 0L
        OperatorCode.LESS -> if (c < 0) 1L else 0L
        // ENGC-4：legacy 档两处都按 `c < 0`（v24 spec: OperatorMethod.cs:487 / :518）；
        // fixed 档 `c >= 0` / `c <= 0`（snake :547 / :578；em :478 / :509）。
        OperatorCode.GREATER_EQUAL ->
            if (if (ctx.stringCompareDialect == StringCompareDialect.LEGACY_BOTH_LT) c < 0 else c >= 0) 1L else 0L
        OperatorCode.LESS_EQUAL ->
            if (if (ctx.stringCompareDialect == StringCompareDialect.LEGACY_BOTH_LT) c < 0 else c <= 0) 1L else 0L
        else -> throw ExprEvalException("非法字符串比较运算符")
    }
}

/**
 * 字符串 `SELECTCASE … CASE lo TO hi` 的区间判定装配（CORE-01，2026-10-23）。
 *
 * 原版 TO 形态**不走运算符重载**，而是 CaseExpression 上独立的区间判定
 * （spec: CaseExpression.cs:62-70 字符串版；整数版同文件 :45-60 同构，编译器侧走
 * `_GE`/`_LE` 整型指令不受影响）：
 * `string.Compare(LeftTerm, Is, Ordinal) <= 0` 成立时再判 `string.Compare(Is, RightTerm, Ordinal) <= 0`，
 * 下界不成立直接 `return false`（RightTerm 连求值都不求）。
 *
 * 原版 TO 形态不走运算符重载，本函数独立装配，故不依赖 [strCompare] 的 GREATER_EQUAL/LESS_EQUAL。
 * 装配用 `>` 表达同一个「小于等于」：`compare(a, b) <= 0` ⇔ `!(a > b)`
 * （GreaterStrStr / LessStrStr 判据 4.981 与 1.824 一致，spec: OperatorMethod.cs:484/:514）。
 * 移植早期 [strCompare] 曾按 1.824 把 `>=`/`<=` 复刻反了，旧实现因此把运算符接进区间判定、
 * 命中集合整体取反；改用本函数后区间判定与运算符判据解耦（MODERN-T5 已把运算符也校准回 4.981）。
 *
 * 装配成 `AND( NOT(lo > target), NOT(target > hi) )`，同时保住原版的**求值顺序与短路**：
 * [Binary] 的 `&&` 分支左假即跳过右侧（本文件 AND 分支的 OperatorMethodManager.cs:383-391
 * 口径）⇒ 上界表达式只在下界成立时求值，与 CaseExpression.cs:66-69 一致。
 *
 * 比较基准：原版此处用 `Config.SCExpression`（Config.cs:523 为 `const StringComparison.Ordinal`），
 * [strCompare] 侧同为 Ordinal 口径 ⇒ 两者一致。
 *
 * 形态说明：两条边界各是一条 `Unary(NOT, Binary(GREATER, …))`，**不是**新的 Expr 节点 ——
 * `Expr` 是 sealed 接口，新增节点要同步改 `ExprCanonicalizer` / `ArgInterner` / `BootMetadata`
 * / `ErbcWriter` 四处 exhaustive `when`（都不在本路文件所有权内）。降级遍历也不受影响：
 * `ErbProgramCompiler.lowerExpr` 的 Unary 分支照常递归操作数，上界那条仍落在 `&&` 右侧的
 * **短路位**（与改前同一口径：可能不执行的调用不前移，运行期在真正走到的一刻求值）。
 *
 * @param target SELECTCASE 目标（编译器侧是只读一次的目标槽哨兵，见 ErbProgramCompiler.selectTargetRead）
 * @param lo CASE 的 `TO` 左端（对应原版 LeftTerm）
 * @param hi CASE 的 `TO` 右端（对应原版 RightTerm）
 */
internal fun strCaseRange(target: Expr, lo: Expr, hi: Expr, line: Int): Expr {
    val lowerOk = Unary(
        OperatorCode.NOT,
        Binary(OperatorCode.GREATER, lo, target, OperandType.INT, line),
        line,
    )   // spec: CaseExpression.cs:66 —— compare(lo, target) <= 0
    val upperOk = Unary(
        OperatorCode.NOT,
        Binary(OperatorCode.GREATER, target, hi, OperandType.INT, line),
        line,
    )   // spec: CaseExpression.cs:68 —— compare(target, hi) <= 0
    return Binary(OperatorCode.AND, lowerOk, upperOk, OperandType.INT, line)
}

/** 三目 cond ? t : f（ERB 写作 cond ? t # f）。spec: OperatorMethodManager.cs:657-691 */
data class Ternary(
    val cond: Expr,
    val ifTrue: Expr,
    val ifFalse: Expr,
    override val operandType: OperandType,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override fun getIntValue(ctx: EvalContext): Long =
        if (cond.getIntValue(ctx) == 0L) ifFalse.getIntValue(ctx) else ifTrue.getIntValue(ctx)

    override fun getStrValue(ctx: EvalContext): String =
        if (cond.getIntValue(ctx) == 0L) ifFalse.getStrValue(ctx) else ifTrue.getStrValue(ctx)

    /** 浮点三目（4.981 升版）：两分支可为 FLOAT 或 INT（整型侧经默认实现宽化）。 */
    override fun getFloatValue(ctx: EvalContext): Double =
        if (cond.getIntValue(ctx) == 0L) ifFalse.getFloatValue(ctx) else ifTrue.getFloatValue(ctx)
}

// ---------------------------------------------------------------------------
// 除零 / 取模的取证面（2026-11-09 lane bug-divzero）
// ---------------------------------------------------------------------------

/**
 * **浮点**除零时的求值期错误，带上「哪个表达式、左右操作数」三件取证。
 *
 * 4.981 起整型 DIV/MOD 已在 [getIntValue] 改成钳制返回 0 + 警告，**不再经过这里**；
 * 本函数现只服务浮点支（唯一硬错通道）。[rightValue] / [leftValue] 因此恒传 null：
 * `Long?` 装不下 10.5 这类非整值，折损后的数会误导取证，宁可留空，只给 [exprText]。
 *
 * [ExprEvalException.operandA] 的「只在左子树无副作用时才补读」是 1.824 抛错通道的
 * 遗留纪律（口径见该属性 KDoc）；整型支不再抛错后，那里改为按 4.981 正常求值一次。
 */
private fun Binary.divModByZero(
    ctx: EvalContext,
    rightValue: Long?,
    leftValue: Long? = left.evidenceIntValueOrNull(ctx),
): ExprEvalException =
    ExprEvalException(
        DIVIDE_BY_ZERO_TEXT,
        operandA = leftValue,
        operandB = rightValue,
        exprText = renderSourceText(),
    )

/**
 * 取证用「补读一次整型值」——**只在子树无副作用时**执行，且读失败一律 null。
 *
 * 两条判据缺一不可：
 * * [isSideEffectFree] 挡住 `RAND` / 用户函数 / `++`（补读会多消耗 RNG 或再写一次变量，
 *   那是改变求值语义，不是取证）；
 * * 读失败（未定义变量、下标越界、子式自己除零）一律吞成 null —— 本函数处在**错误路径**上，
 *   不许用第二条新错误去顶替本来要报的那条（口径同 `VirtualMachine.currentScriptFile`
 *   的 `runCatching`）。
 */
private fun Expr.evidenceIntValueOrNull(ctx: EvalContext): Long? {
    if (!isSideEffectFree()) return null
    return try {
        if (operandType == OperandType.FLOAT) getFloatValue(ctx).toLong() else getIntValue(ctx)
    } catch (e: RuntimeException) {
        null
    }
}

/**
 * 该子树**再求值一次**是否不改变任何可观察状态（取证补读的准入判据）。
 *
 * 判为**有副作用**的五类，逐条对应语料里真实存在的形态：
 * * [FuncCall]：内建与用户方法都可能有副作用（`RAND` 家族、`SETVAR`、`PRINT*`）；
 * * [VarRef] 且名为 `RAND`：ERB 的 `RAND:n` 是**伪变量读**，每读一次推进一次 RNG
 *   （`VariableData.cs:845-853`）；
 * * 前置 `++`/`--`（[Unary] 的 INCREMENT/DECREMENT）与全部后置自增（[UnaryAfter]）：写变量；
 * * 取负**最小整数**会打一行系统提示（[Unary] 的 MINUS 支，spec: OperatorMethodManager.cs:561-564
 *   「对 -9223372036854775808 取负不生效」）—— 那是可观察输出，同样算副作用；
 *   只有操作数是**字面量**时能静态判掉这一支，其余取值不可知 ⇒ 判脏；
 * * 其余按子结点递归 —— 纯算术/比较/三目在两侧都纯时才纯。
 *
 * 保守方向是「宁可判脏」：判脏只是让 `operandA` 少一个数，判净则会偷偷多跑一次副作用。
 */
internal fun Expr.isSideEffectFree(): Boolean = when (this) {
    is IntLiteral, is StrLiteral, is FloatLiteral, is NullTerm -> true
    is VarRef -> name.uppercase() != "RAND" && indices.all { it.isSideEffectFree() }
    is KeywordIndexArg -> expr.isSideEffectFree()
    is FuncCall -> false
    is Unary -> when (op) {
        // 写变量
        OperatorCode.INCREMENT, OperatorCode.DECREMENT -> false
        // 取负 MIN_VALUE 会打系统行；字面量能静态判掉，其余取值不可知 ⇒ 保守判脏
        OperatorCode.MINUS -> when (val o = operand) {
            is IntLiteral -> o.value != Long.MIN_VALUE
            is FloatLiteral -> true
            else -> false
        }
        else -> operand.isSideEffectFree()
    }
    is UnaryAfter -> false
    is Binary -> left.isSideEffectFree() && right.isSideEffectFree()
    is Ternary -> cond.isSideEffectFree() && ifTrue.isSideEffectFree() && ifFalse.isSideEffectFree()
    is FormStringTerm -> parts.all { p ->
        p is FormPart.Text || (p as FormPart.Embedded).ast.isSideEffectFree()
    }
}

/**
 * 该子树能否在**解析期**折成常量 —— 判「下标是常量还是运行期表达式」用的判据。
 *
 * 语义对应原版两条：`Restructure` 把能折的结点换成 `SingleTerm`
 * （spec: FunctionMethodTerm.cs:35-59 —— `CanRestructure` 且全部实参已折成 SingleTerm），
 * 而 `VariableTerm.Restructure` 只对折出来的 `SingleTerm` 实参做 `CheckElement`
 * （spec: VariableTerm.cs:316-347）。⇒ 4.981 的稀疏 1 维数组上：「常量下标越界」装载/执行前
 * 就抛 OoRVarArg，「动态下标越界」根本不检查、落到 SparseArray 返回默认值。
 * 我们这条链**不做算术常量折叠**（`Parser.buildVariableTerm` 保留 `Binary(+, 1, 1)`，
 * 见下述实现），所以这里按「字面量 + 常量运算」递归判，与「折得动」等价而不是与
 * 「已折过」等价 —— 两种口径对同一个下标给出同一个结论，且不依赖折叠时机。
 *
 * 保守方向与 [isSideEffectFree] 相反但同样安全：判 **false**（当成运行期表达式）只是让
 * 越界走容忍分支（PC 也不检查该支），判 true 才会让越界硬抛 —— 只有「能证明是常量」的
 * 才判 true。
 */
internal fun Expr.isCompileTimeConst(): Boolean = when (this) {
    is IntLiteral, is FloatLiteral -> true
    // 字符串字面量只出现在关键字下标位：解析期会被 constantKeywordLookup 折成 IntLiteral，
    // 折不动时包成 KeywordIndexArg（运行期查表，可能查到越界值）⇒ 不算常量。
    is StrLiteral -> false
    // 前缀 ++/-- 写变量，其「值」依赖运行期；其余一元运算符对常量仍是常量。
    is Unary -> op != OperatorCode.INCREMENT && op != OperatorCode.DECREMENT && operand.isCompileTimeConst()
    is Binary -> left.isCompileTimeConst() && right.isCompileTimeConst()
    is Ternary -> cond.isCompileTimeConst() && ifTrue.isCompileTimeConst() && ifFalse.isCompileTimeConst()
    else -> false
}

/**
 * 把表达式 AST 重建为**源码形态**文本（除零取证面的 `exprText`）。
 *
 * ## 为什么是「重建」不是「原文」
 * ERB 原文不随产物保存：`.erbc` 的表达式池只有 AST（`ErbcWriter.kt:746` 起逐结点写），
 * 行号表只到「指令 → 0 基行」这一维。所以报告里能给的最忠实形态就是 AST → 文本的重建，
 * 它保证**标识符与下标逐字**（`MAXBASE:0:12`、`CFLAG:TARGET:0`），只把空格与括号规范化。
 *
 * ## 规范化口径（确定性，测试逐字钉住）
 * * 二元结点一律 `左 空格 运算符 空格 右`；子结点是二元/三目时加一层括号（优先级歧义在这里显式化）；
 * * 变量 = `名` + 可选的 `@私钥` + 每个下标前加一个冒号（`MAXBASE:0:12`）；
 * * 下标位的字符串字面量**不加引号**（ERB 的 `MAXBASE:TARGET:酒気` 就是裸关键字）；
 * * 函数调用 = `名(实参, 实参)`；空实参位渲染为空串（位置数守恒）；
 * * 字符串形式 `@"..."` 按 `{expr}` / `%expr%` / `\@cond?左#右\@` 三段还原。
 */
internal fun Expr.renderSourceText(): String = renderExprPart(inIndex = false)

private fun Expr.renderExprPart(inIndex: Boolean): String = when (this) {
    is IntLiteral -> value.toString()
    is StrLiteral -> if (inIndex) value else "\"" + value + "\""
    is FloatLiteral -> value.toString()
    is NullTerm -> "null"
    // 注意不要用 buildString 的 lambda 去拼：它的接收方是 StringBuilder（CharSequence），
    // 裸写 `indices` 会解析成 `CharSequence.indices`（IntRange），循环变量就成了 Int。
    is VarRef -> name + (if (subKey != null) "@" + subKey else "") +
        indices.joinToString("") { ":" + it.renderExprPart(inIndex = true) }
    is KeywordIndexArg -> expr.renderExprPart(inIndex = true)
    is FuncCall -> name + "(" + args.joinToString(", ") { it?.renderExprPart(inIndex = false) ?: "" } + ")"
    is Unary -> OperatorCode.toOperatorString(op) + operand.wrapIfCompound()
    is UnaryAfter -> operand.wrapIfCompound() + OperatorCode.toOperatorString(op)
    is Binary -> left.wrapIfCompound() + " " + OperatorCode.toOperatorString(op) + " " + right.wrapIfCompound()
    is Ternary ->
        cond.wrapIfCompound() + " ? " + ifTrue.renderExprPart(inIndex = false) +
            " # " + ifFalse.renderExprPart(inIndex = false)
    is FormStringTerm -> "@\"" + parts.joinToString("") { p ->
        when (p) {
            is FormPart.Text -> p.str
            is FormPart.Embedded -> when (val c = p.conversion) {
                is FormConversion.Curly -> "{" + p.ast.renderExprPart(inIndex = false) + "}"
                is FormConversion.Percent -> "%" + p.ast.renderExprPart(inIndex = false) + "%"
                is FormConversion.YenAt ->
                    "\\@" + p.ast.renderExprPart(inIndex = false) + "?" +
                        c.ifTrue.renderExprPart(inIndex = false) + "#" +
                        c.ifFalse.renderExprPart(inIndex = false) + "\\@"
            }
        }
    } + "\""
}

/** 复合子式加括号：二元/三目在**任何**父结点下都括起来，优先级歧义在重建文本里显式化。 */
private fun Expr.wrapIfCompound(): String =
    if (this is Binary || this is Ternary) "(" + renderExprPart(inIndex = false) + ")" else renderExprPart(inIndex = false)
