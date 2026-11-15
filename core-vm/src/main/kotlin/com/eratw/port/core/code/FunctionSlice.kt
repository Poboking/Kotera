package com.eratw.port.core.code

import com.eratw.port.core.compile.CallRef
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.stmt.Statement

/**
 * 一个 `@函数` 在共享 [CodeBuilder] 上留下的**全部可重放副作用**（MODERN-R11-R4B2）。
 *
 * ## 为什么必须有它（缺陷面）
 *
 * `ErbProgramCompiler.compileFunction` 不是「返回值 = 产物」的纯函数：它往**共享**
 * [CodeBuilder] 追加指令、池条目、调用点实参表、待链接 `_CALL` 引用、标签表、帧槽数
 * （`ErbProgramCompiler.kt:348-419`）。函数级编译缓存若在命中时「跳过」真编译，产物的
 * 指令流就会缺一整段（后续函数的入口 pc、跳转偏移、函数表下标全线错位）。
 * 所以命中只能**重放字节**：把当初追加的一切按原序再追加一遍，只把「跨函数才定的绝对值」
 * （池下标、pc）换成新基址下的值。
 *
 * ## 绝对 / 相对的分界
 *
 * | 面 | 切片里的口径 | 重放时 |
 * |---|---|---|
 * | 指令字（[words]） | 原值（池下标 = **旧**绝对下标） | 按 [PoolOp] 记下的旧→新映射改写 |
 * | 常量/字符串/表达式/语句池追加（[poolOps]） | 原序 + 当初返回的绝对下标 | 原序重新追加（语句按身份追加，**不去重**） |
 * | 调用点实参表（[callArgs]） | **绝对** pc | 加 delta |
 * | 标签（[labels]） | **相对**入口的 pc | 入口新基址 + 相对值 |
 * | `_CALL` 待链接引用（[callRefs]） | **相对**入口的 pc | 同上；`_CALL.a` 本来就由 `build()` 回填 |
 * | REF 回填点（[varArgSites]） | **相对**入口的 pc | 同上 |
 *
 * 跳转指令（`_JUMP` / `_JUMP_IF_*` / `_STK_J*` / `_EVAL_INT_JZ` / `_LT_JZ` / `_GT_JZ`）的
 * `a` 是「相对下一条指令的条数」—— 两端同处一个平移后的 pc 空间，差值不变，
 * **一个字都不许动**（`CodeBuilder` 的类 KDoc 口径 2：[Program.kt:2103-2104]）。
 *
 * ## 重放等价性的前提（不是「相信」，是结构性论证）
 *
 * 重放 = 用**同一串**池追加调用（同序、同值、同实例）跑在**同一个** [CodeBuilder] 上。
 * 池的取值只由「调用序 + 当前池状态」决定（`addLong`/`addString`/`addExpr` 的去重、
 * `addStatement` 的身份追加，[Program.kt:455-542]），所以重放得到的每个下标都与
 * 「在同一状态上真编一遍」逐位相同 —— 这正是 `FunctionSliceAdoptTest` 钉的那条。
 *
 * @property sig 编译单元签名（`FuncSig`）
 * @property slotCount 帧槽数（`maxOf(arity, maxTemp + 1)`，[ErbProgramCompiler.kt:401]）
 * @property entryPc 捕获时函数的入口 pc（**旧**空间；只作诊断与 `wordCount` 的锚，重放不用它）
 * @property wordCount 指令条数（`_RET` 收尾在内）
 */
class FunctionSlice internal constructor(
    val sig: FuncSig,
    val slotCount: Int,
    val entryPc: Int,
    val wordCount: Int,
    /** 指令字（每条 [Instruction.WORDS] 个字，原值）。 */
    internal val words: IntArray,
    /** 逐条源码行号（与 [wordCount] 等长）。 */
    internal val lines: IntArray,
    /** 本次追加进池的条目（**原序**，含被去重命中的调用）。 */
    internal val poolOps: List<PoolOp>,
    /** 调用点实参表（pc = 旧绝对 pc）。 */
    internal val callArgs: List<CallArgsEntry>,
    /** 函数内 `$标签`（相对入口 pc，序 = 当初 `declareLabel` 的序）。 */
    internal val labels: List<LabelPc>,
    /** 待链接 `_CALL` 引用（pc 相对入口）。 */
    internal val callRefs: List<SliceCallRef>,
    /** REF 位回填点（pc 相对入口）。 */
    internal val varArgSites: List<RelVarArgSite>,
) {

    /** 切片覆盖的旧 pc 区间上界（不含）。 */
    val endPc: Int get() = entryPc + wordCount

    /** 池追加条数（诊断/闸门读数：命中重放了多少条池条目）。 */
    val poolOpCount: Int get() = poolOps.size
}

/**
 * 一条池追加（[CodeBuilder.constant] / [string] / [expr] / [statement] 的一次调用）。
 *
 * [oldIndex] 是**当初**这次调用的返回值（绝对下标）—— 它是重放时建立
 * 「旧下标 → 新下标」映射的**唯一**依据：去重命中会让多次调用返回同一个下标，
 * 而同一值在新池里未必还落在同一下标上（前面的函数可能少追加/多追加了若干条）。
 */
class PoolOp internal constructor(
    val kind: Int,
    /** [KIND_CONSTANT] 的取值。 */
    val longValue: Long,
    /** [KIND_STRING] 的取值。 */
    val stringValue: String?,
    /** [KIND_EXPR] 的取值（结构相等即复用同一条目）。 */
    val exprValue: Expr?,
    /** [KIND_STATEMENT] 的取值（按**身份**追加，严禁去重）。 */
    val stmtValue: Statement?,
    val oldIndex: Int,
) {
    internal companion object {
        const val KIND_CONSTANT = 0
        const val KIND_STRING = 1
        const val KIND_EXPR = 2
        const val KIND_STATEMENT = 3
    }
}

/** 一个调用点的逐位实参元数据（[pc] = 捕获时的**绝对** pc）。 */
internal class CallArgsEntry(val pc: Int, val args: List<CallArg>)

/** 一个 `$标签` 与其**相对**入口的 pc。 */
internal class LabelPc(val name: String, val relPc: Int)

/** 一条待链接 `_CALL` 引用（[relPc] 相对入口）。 */
internal class SliceCallRef(val target: String, val relPc: Int, val line: Int) {

    /** 按新入口基址还原成 [CallRef]（`build()` 的 `_CALL.a` 回填面）。 */
    fun at(entryPc: Int): CallRef = CallRef(entryPc + relPc, target, line)
}

/**
 * 一个 REF 位回填点。
 *
 * 调用方（[com.eratw.port.core.compile.ErbProgramCompiler.captureSlice] 的喂入侧）交的是
 * **绝对** pc；[FunctionSlice] 捕获时换成相对入口的值存进 [relEvalPc]，
 * 重放时再加新入口基址。
 */
class SliceVarArgSite(
    /** 被调函数名（原样，查表大写归一）。 */
    val target: String,
    /** 实参位（0 起，= 被调头部形参位）。 */
    val position: Int,
    /** **绝对** pc：该实参的值求值指令（`_EVAL_INT`/`_EVAL_STR`）。 */
    val evalPc: Int,
    /** 字符串通道（回填成空串常量而不是 0）。 */
    val isStr: Boolean,
)

/** [SliceVarArgSite] 的相对形态（[relEvalPc] 相对函数入口 pc）。 */
internal class RelVarArgSite(val target: String, val position: Int, val relEvalPc: Int, val isStr: Boolean)
