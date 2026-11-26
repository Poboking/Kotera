package com.eratw.port.core.patch

import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.proc.FuncMeta

/**
 * 缺名残桩（2026-11-24 S9 改判 L3，CONTRACT-S9-MISSING-CALL.md）：链接器为每个
 * **未解析外部导入名**（组合里没有非事件胜者提供 / 目标是事件名）合成一个 3 指令微函数，
 * 死 `_CALL` 改写为跳它的「运行期按名调用」：
 *
 * ```text
 * _EVAL_STR      R0, <expr: StrLiteral(name)>   ; 名字常量进 R0
 * _CALL_BY_NAME  R0, 0, none                    ; 运行期 resolveFunctionIndex
 * _RET           none
 * ```
 *
 * 运行期三条路与原版逐字同口径（零新机器、零文案复制——单一真源 = 运行期解析器）：
 *
 * - 未命中（缺提供者 / base 月更删函数）→ 非 try 抛 `NotDefinedFunc`
 *   （`没有找到指定的函数"@X"`，spec: FunctionIdentifier.cs:3581）——装载成功、
 *   **执行到调用点才报错**，与原版装载行为一致；
 * - 目标是事件名 → `CallToEventFunc`（CompatiCallEvent:NO）/ **YES 时真调通首成员**
 *   （spec: CalledFunction.cs:100-103、LabelDictionary.cs:181-184——补丁 CALL 自己的
 *   事件名在 YES 配置下按原版语义工作）；
 * - 多余实参被 [com.eratw.port.core.code.VirtualMachine.callInto] 的三重夹取忽略
 *   （残桩 arity=0，原版「多出的实参直接忽略」同款）。
 *
 * 确定性纪律：残桩按**大写名全域去重一份**、sort 后落物理表尾——同一组合同名只合成
 * 一个，链接产物字节可复现（LinkFingerprint 十因子已含 externalCalls 列表序）。
 */
object MissingCallStub {

    /** 残桩候选在诊断/冲突报告里的归属名（合成标记风格，同 [ModuleLinker.BASE_OWNER_ID] 约定）。 */
    const val OWNER_ID: String = "·missing·"

    /**
     * 残桩 [FuncMeta.key] 前缀：ERB 标识符不可能含 `·` ⇒ 不与任何真函数名撞键、
     * 不进事件桶（`isEventLabelName` 对 `·MISSING·…` 恒假）、dispatch 恒等
     * （无覆盖链）；名字通道（按名解析）永远查不到它——残桩只经改写后的物理下标可达。
     */
    const val KEY_PREFIX: String = "·MISSING·"

    /** 残桩函数表 position 标记（合成约定，与转码器 `·event-entry·` 同风格、恒不同值）。 */
    const val POSITION: String = "·missing-stub·"

    /** 每个残桩占用的指令条数（`_EVAL_STR` + `_CALL_BY_NAME` + `_RET`）。 */
    const val INSTRUCTIONS: Int = 3

    /** 每个残桩占用的指令字数（[INSTRUCTIONS] × [Instruction.WORDS]）。 */
    const val WORDS: Int = INSTRUCTIONS * Instruction.WORDS

    /**
     * 一个残桩的指令字（12 字 = 3 条）。[exprIndex] = 组合池里 `StrLiteral(name)` 的
     * 下标（链接器在模块池追加完后统一落尾，下标确定后才填字）。
     *
     * `_CALL_BY_NAME` 的 b = 0：零实参、无 try/尾跳/CALLF/str-ret 位——非 try 缺名
     * 抛原版原文；`_RET` 无返回值（死名场景永远到不了这里；CompatiCallEvent=YES
     * 真调通时返回值丢弃 = CALL 语义，返回值保真属已知近似，见契约 §1 L3）。
     */
    fun words(exprIndex: Int): IntArray = intArrayOf(
        Opcode._EVAL_STR, exprIndex, SlotAddr.local(0), 0,
        Opcode._CALL_BY_NAME, SlotAddr.local(0), 0, SlotAddr.NONE,
        Opcode._RET, SlotAddr.NONE, 0, 0,
    )

    /** 残桩函数记录：`name` = 导入原文名（栈可读），arity 0 / slotCount 1（只占 R0）。 */
    fun functionInfo(rawName: String, entryPc: Int): FunctionInfo =
        FunctionInfo(rawName, entryPc, 0, 1, emptyList())

    /** 残桩元数据：合成键不撞名、非事件非方法、position 标记合成约定。 */
    fun funcMeta(index: Int, upperKey: String, rawName: String): FuncMeta =
        FuncMeta(
            index = index,
            key = KEY_PREFIX + upperKey,
            name = rawName,
            defIndex = index,
            position = POSITION,
        )
}
