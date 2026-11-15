package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M3-2-A「事件/局部尺寸面」：**装配期聚合**两判据（CONTRACT-RT2-M32A §1.1 / §1.3）。
 *
 * 1. **事件组聚合**：同名事件的全部成员共享 LOCAL/LOCALS/LOCALF 有效尺寸 = 组内声明
 *    max，再抬 `getLocalDefaultSize` 档位下限，回写全部成员（C# 锚 = `LabelDictionary.cs`
 *    → `SortLabel` 事件支 + `IdentifierDictionary.cs` → `getLocalDefaultSize`；
 *    单调不缩与 ARG 跨定义在 `VariableStore.defineLocalLengths` 的合并式见 §1.3 用例）。
 *    聚合在 `ProcessRuntime.init` 按 `eventCallOrder` 分组现算、不物化进 `FuncMeta`；
 *    执行派发面一行不动。
 * 2. **ARG 跨定义单调合并**：同名非事件多重定义的有效 ARG/ARGS = 跨定义 max（含被
 *    覆盖定义），「先短后长」「先长后短」两序一致（C# 锚 = `SortLabel` 非事件支的
 *    `resizeLocalVars` → `VariableLocal.cs` → `ResizeLocalVariableToken` 单调不缩）。
 *
 * ## 档位值锚定（游戏根 `$ERATW_ROOT/eraTW4.981-261017-EAGLOV3` 只读取证）
 *
 * - `CSV/VariableSize.csv:119-122`：`LOCAL,200` / `LOCALS,100` / `ARG,200` / `ARGS,100`；
 * - `LOCALF`/`ARGF` 该 CSV 未声明行 ⇒ 出厂 10（fork-src `ConstantData.cs:309`
 *   的 `VariableFloatArrayLength[i] = 10`）；
 * - 本文件用 [sizes] 把 registry 档位逐值设成上述实值（`applySize` 与 C#
 *   `changeVariableSizeData` 同一条写入路径），判据数值 = 游戏根 CSV 原值。
 */
class EventLocalAggregateTest {

    /** 游戏根 VariableSize.csv 实值档位（出处见类 KDoc「档位值锚定」）。 */
    private fun sizes(): VariableSizes = VariableSizes().apply {
        applySize(VariableCode.LOCAL_VAR, intArrayOf(200)) // LOCAL,200
        applySize(VariableCode.LOCALS, intArrayOf(100)) // LOCALS,100
        applySize(VariableCode.ARG, intArrayOf(200)) // ARG,200
        applySize(VariableCode.ARGS, intArrayOf(100)) // ARGS,100
        applySize(VariableCode.LOCALF, intArrayOf(10)) // CSV 无行 ⇒ 出厂 10
    }

    /** main@0 占位 + 其余按名单建唯一名函数（与 [FuncMeta] 下标一一对齐）。 */
    private fun program(fnNames: List<String>): Program {
        val src = buildString {
            append(".func main slots=1\n")
            append("  _HALT\n")
            for (n in fnNames) {
                append(".func $n slots=1\n")
                append("  _RET\n")
            }
        }
        return Assembler.assemble(src)
    }

    private fun store() = VariableStore(VariableRegistry(sizes()))

    // ------------------------------------------------------------------
    // 判据 1：事件组聚合
    // ------------------------------------------------------------------

    /**
     * A 成员声明 `#LOCALSIZE 300`、B 成员只声明 `#LOCALSIZE 1`（Mod 侧常见的小声明覆盖
     * 写法，RECON §2.4 潜在洞）⇒ 组聚合后全组有效尺寸 = max(300, 1, 档位 200) = 300，
     * 后到成员的小声明**不得**覆盖组 max（C# `SortLabel` 事件支回写全部成员）。
     */
    @Test
    fun `事件组聚合_声明成员抬全组含小声明成员`() {
        val p = program(listOf("ev0", "ev1"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "EV", name = "ev0", isEvent = true, localSize = 300, defIndex = 0),
            FuncMeta(index = 2, key = "EV", name = "ev1", isEvent = true, localSize = 1, defIndex = 1),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())

        // 同名事件成员共用注册键（帧 funcKey = 大写名）⇒ 组内全体共享同一聚合值；
        // 旧实现（无聚合 + plain put 后写覆盖）会给 1（B 的后写覆盖），与 C# 分叉。
        store.enterFunction("EV")
        try {
            assertEquals(300, store.getLength("LOCAL"), "组聚合 = max(A 300, B 1, 档位 200)；后写小声明不得覆盖")
            store.writeInt("LOCAL", longArrayOf(299), 7L)
            assertEquals(7L, store.readInt("LOCAL", longArrayOf(299)), "299 号位可读写（旧实现会 OOR）")
            assertEquals(100, store.getLength("LOCALS"), "组内无 #LOCALSSIZE 声明 ⇒ 档位下限 100")
            assertEquals(10, store.getLength("LOCALF"), "组内无 #LOCALFSIZE 声明 ⇒ 出厂档 10")
            assertEquals(200, store.getLength("ARG"), "事件成员无 ARG 声明 ⇒ 档位（C# 事件支不 resize ARG）")
        } finally {
            store.leaveFunction()
        }
    }

    /** 非事件函数不进聚合段（C# 只在事件支聚合）：`#LOCALSIZE 1` 缩到 1 是原版语义。 */
    @Test
    fun `非事件函数不聚合_声明一就缩到一`() {
        val p = program(listOf("f0", "f1"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "F1", name = "f0", localSize = 1, defIndex = 0),
            FuncMeta(index = 2, key = "F2", name = "f1", defIndex = 1),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())

        store.enterFunction("F1")
        try {
            assertEquals(1, store.getLength("LOCAL"), "非事件 `#LOCALSIZE 1` 缩到 1（不抬档位）")
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("F2")
        try {
            assertEquals(200, store.getLength("LOCAL"), "非事件未声明 ⇒ 档位 200（聚合前后同值）")
        } finally {
            store.leaveFunction()
        }
    }

    /** 单成员事件同样聚合（C# `SortLabel` 事件支对每组都跑）：档位下限抬进注册值。 */
    @Test
    fun `单成员事件也抬档位下限`() {
        val p = program(listOf("ev0"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "EV", name = "ev0", isEvent = true, defIndex = 0),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())
        store.enterFunction("EV")
        try {
            assertEquals(200, store.getLength("LOCAL"), "无声明事件成员的有效 LOCAL = 档位 200")
            assertEquals(100, store.getLength("LOCALS"), "无声明事件成员的有效 LOCALS = 档位 100")
        } finally {
            store.leaveFunction()
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：ARG 跨定义单调合并（端到端两序一致）
    // ------------------------------------------------------------------

    /** 先长后短：A 定义 `ARG` 声明长 250 先注册、B 定义 1 后注册 ⇒ 有效 ARG = 250。 */
    @Test
    fun `ARG跨定义单调_先长后短`() {
        val p = program(listOf("f0", "f1"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "F", name = "f0", argIntCount = 250, defIndex = 0),
            FuncMeta(index = 2, key = "F", name = "f1", argIntCount = 1, defIndex = 1),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())
        store.enterFunction("F")
        try {
            assertEquals(250, store.getLength("ARG"), "被覆盖定义的声明长仍生效（跨定义 max，含先到者）")
            store.writeInt("ARG", longArrayOf(249), 3L)
            assertEquals(3L, store.readInt("ARG", longArrayOf(249)), "旧实现 plain put 后写覆盖 ⇒ 这里会 OOR")
        } finally {
            store.leaveFunction()
        }
    }

    /** 先短后长：注册序反置 ⇒ 结果与「先长后短」一致（单调不缩的确定性表述）。 */
    @Test
    fun `ARG跨定义单调_先短后长两序一致`() {
        val p = program(listOf("f0", "f1"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "F", name = "f0", argIntCount = 1, defIndex = 0),
            FuncMeta(index = 2, key = "F", name = "f1", argIntCount = 250, defIndex = 1),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())
        store.enterFunction("F")
        try {
            assertEquals(250, store.getLength("ARG"), "两序同值（先短后长 = 先长后短 = 250）")
        } finally {
            store.leaveFunction()
        }
    }

    /** ARGS 面同判据（跨定义 max）；事件成员 arg 全零路径零行为差。 */
    @Test
    fun `ARGS跨定义单调且事件成员全零路径零差`() {
        val p = program(listOf("f0", "f1", "ev0"))
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN"),
            FuncMeta(index = 1, key = "F", name = "f0", argStrCount = 150, defIndex = 0),
            FuncMeta(index = 2, key = "F", name = "f1", argStrCount = 2, defIndex = 1),
            FuncMeta(index = 3, key = "EV", name = "ev0", isEvent = true, defIndex = 2),
        )
        val store = store()
        ProcessRuntime(p, store, FunctionTable(metas), VmConfig())
        store.enterFunction("F")
        try {
            assertEquals(150, store.getLength("ARGS"), "ARGS 跨定义 max（先 150 后 2）")
        } finally {
            store.leaveFunction()
        }
        store.enterFunction("EV")
        try {
            assertEquals(200, store.getLength("ARG"), "事件成员无 ARG 声明 ⇒ 档位（合并式对全零不回退）")
            assertEquals(100, store.getLength("ARGS"), "事件成员无 ARGS 声明 ⇒ 档位")
        } finally {
            store.leaveFunction()
        }
    }

    // ------------------------------------------------------------------
    // 判据 3（store 直连）：合并式语义逐条
    // ------------------------------------------------------------------

    /** 直连合并式：ARG 面两序一致（与端到端同判据的最小装置）。 */
    @Test
    fun `defineLocalLengths跨定义合并_ARG两序一致`() {
        fun build(longFirst: Boolean): VariableStore {
            val s = store()
            val long = VariableStore.LocalLengths(arg = 250, args = 150)
            val short = VariableStore.LocalLengths(arg = 1, args = 2)
            s.defineLocalLengths("F", if (longFirst) long else short)
            s.defineLocalLengths("F", if (longFirst) short else long)
            return s
        }
        for (longFirst in listOf(true, false)) {
            val s = build(longFirst)
            s.enterFunction("F")
            try {
                assertEquals(250, s.getLength("ARG"), "longFirst=$longFirst ⇒ ARG=跨定义 max")
                assertEquals(150, s.getLength("ARGS"), "longFirst=$longFirst ⇒ ARGS=跨定义 max")
            } finally {
                s.leaveFunction()
            }
        }
    }

    /** LOCAL 面后写胜（C# `noneventLabelDic[key] = label` 覆盖，生效定义 = 最后装载者）。 */
    @Test
    fun `defineLocalLengths的LOCAL面后写胜且单定义零差`() {
        val s = store()
        s.defineLocalLengths("F", VariableStore.LocalLengths(local = 300))
        s.defineLocalLengths("F", VariableStore.LocalLengths(local = 1))
        s.enterFunction("F")
        try {
            assertEquals(1, s.getLength("LOCAL"), "LOCAL 声明随生效定义（后写覆盖），不与被覆盖定义取 max")
        } finally {
            s.leaveFunction()
        }
        // 单定义（每 key 首次注册）逐位等价旧实现。
        val single = store()
        single.defineLocalLengths("F", VariableStore.LocalLengths(local = 4, arg = 250))
        single.enterFunction("F")
        try {
            assertEquals(4, single.getLength("LOCAL"))
            assertEquals(250, single.getLength("ARG"))
        } finally {
            single.leaveFunction()
        }
    }
}
