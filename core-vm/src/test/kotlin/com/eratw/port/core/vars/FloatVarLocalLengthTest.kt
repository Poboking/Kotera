package com.eratw.port.core.vars

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [g9] ARGF 的**形参声明长**接进 [VariableStore.LocalLengths.floatArg]，以及它与
 * VARIABLESIZE.CSV 浮点档、`#LOCALFSIZE` 三者的取上界关系。
 *
 * ## 证据（fork 4.981 = vendor emuera-skia @48494165）
 * - 形参声明长的来源：`ErbLoader.cs:600-602,709-710,787`
 *   （`maxArgF` 初值 **-1**，逐条 ARGF 形参用 `getEl1forArg + 1` 抬上界 → `label.ArgFloatLength`）；
 * - 生效方式（**只增不减**）：`VariableLocal.cs:46-53`
 *   （`ARGF ⇒ newSize = func.ArgFloatLength`；`newSize > 0` 且 `newSize < size` 且
 *   `varCode ∈ {ARG, ARGS, ARGF}` ⇒ `newSize = size`，`size` 就是 VARIABLESIZE.CSV 那一档）；
 * - `size` 从哪来：`VariableData.cs:376-379`（`new VariableLocal(ARGF, VariableFloatArrayLength[1], …)`），
 *   出厂 10 见 `ConstantData.cs:211-217`、可被 CSV 改写见 `:532-533`。
 *
 * ✅ 两处口径差已按组长裁决（2026-11-14，CONTRACT-RT2-M32A 判据 2）**严格照抄**落地：
 * 1. ARGF 只读 `ArgFloatLength`，`#LOCALFSIZE` 不抬 ARGF（`FloatDeclFaceTest`/
 *    `FloatVarLocalLengthTest` 受影响断言已逐例刷新）；
 * 2. 非事件函数的 LOCALF 不做下界钳制（声明可缩），档位下限只由事件组聚合抬
 *    （`ProcessRuntime.init` 聚合段，本仓 `resolveLocalSize` 整数侧同口径早已落地）。
 *
 * 浮点侧无 1.824 对拍（1.824 没有浮点变量）⇒ 逐条 TODO(verify)；
 * 每个用例都带一条 1.824 侧的 int/str 对照，钉住非浮点面未变。
 */
class FloatVarLocalLengthTest {

    private fun storeWith(
        floatLocal: Int = 0,
        floatArg: Int = 0,
        arg: Int = 0,
        args: Int = 0,
        local: Int = 0,
        locals: Int = 0,
        sizes: VariableSizes = VariableSizes(),
    ): VariableStore {
        val store = VariableStore(VariableRegistry(sizes))
        store.defineLocalLengths(
            "T",
            VariableStore.LocalLengths(
                local = local, locals = locals, arg = arg, args = args,
                floatLocal = floatLocal, floatArg = floatArg,
            ),
        )
        return store
    }

    // ------------------------------------------------------------------
    // 形参声明长的数法（ProcessRuntime 接线的那一步）
    // ------------------------------------------------------------------

    /**
     * `declaredLength`：形参表里 ARGF 的声明长 = `max(下标 + 1)`（fork `ErbLoader.cs:709-710` 的
     * `maxArgF`；初值 -1 ⇒ 无 ARGF 形参时为 0）。
     *
     * 形参表用 `paramRefs`（`:n` 原文），跳号形参 `ARGF:5` 与稠密的 `ARGF` 计数不同 ——
     * 整数侧同一函数（`ARG` / `ARGS`）也由它算，见 `SparseArgLengthTest`。
     */
    @Test
    fun `形参表数出ARGF声明长`() {
        fun f(names: List<String>) = VariableStore.LocalLengths.declaredLength(names, "ARGF", 0)
        assertEquals(0, f(emptyList()), "无形参（含 .erbc v1 不带形参名表）⇒ 0，与改造前恒 0 同值")
        assertEquals(0, f(listOf("ARG")), "纯整型形参不算 ARGF")
        assertEquals(1, f(listOf("ARG", "ARGF")), "一条 ARGF ⇒ 1")
        assertEquals(1, f(listOf("ARGF", "ARGF")), "重复形参不抬高（原版 max(下标+1)，同一下标只算一次）")
        assertEquals(1, f(listOf("ARGF", "ARG", "ARGS")), "ARG/ARGS 不误计")
        assertEquals(1, f(listOf("argf")), "大小写不敏感（原版 Config.cs:33 OrdinalIgnoreCase）")
        assertEquals(1, f(listOf("ARGF$")), "变长尾标记仍算一条 ARGF")
        assertEquals(0, f(listOf("ARGS", "ARGS$")), "ARGS 与 ARGF 不同名")
        assertEquals(0, f(listOf("A", "B$", "RESULT")), "普通私有形参一律不算")
        assertEquals(6, f(listOf("ARGF:5")), "跳号形参按 max(下标 + 1)（fork ErbLoader.cs:709-710）")
        assertEquals(6, f(listOf("ARGF", "ARGF:5")), "下界不封顶下标")
    }

    /** 整数侧同一口径：`ARG` / `ARGS` 的声明长也为 `max(下标 + 1)`，[count] 只是个下界。 */
    @Test
    fun `形参表数出整数声明长`() {
        fun arg(names: List<String>, count: Int = 0) =
            VariableStore.LocalLengths.declaredLength(names, "ARG", count)
        assertEquals(4, arg(listOf("ARG", "ARG:3")), "稀疏形参：个数 2、下标上界 4")
        assertEquals(0, arg(emptyList()), "无段 8 且没有元数据个数 ⇒ 0（旧口径同值）")
        assertEquals(2, arg(emptyList(), count = 2), "无段 8 ⇒ 回落元数据个数")
        assertEquals(2, arg(listOf("ARG", "X"), count = 2), "私有形参混排：个数作为下界保留")
        assertEquals(0, arg(listOf("ARGS", "ARGS:1"), count = 0), "ARGS 不误算进 ARG（ARGS:1 的 1 属另一桶）")
        assertEquals(9, arg(listOf("arg:8")), "大小写不敏感")
        assertEquals(0, arg(listOf("ARG:X")), "非十进制下标不猜（装载期已折成十进制）")
    }

    /**
     * ARGF 实例长度 = `max(VARSIZE 档, #LOCALFSIZE, 形参声明长)`，且**每函数独立**。
     * 下标判界跟着长度走。
     */
    @Test
    fun `形参声明长生效于ARGF且不牵动LOCALF`() {
        val store = storeWith(floatArg = 25)
        store.enterFunction("T")
        try {
            assertEquals(25, store.getLength("ARGF"), "声明长 25 ⇒ 生效长度 25（fork VariableLocal.cs:48-54）")
            assertEquals(10, store.getLength("LOCALF"), "ARGF 的声明长不抬高 LOCALF")
            store.writeFloat("ARGF", longArrayOf(24), 1.5)
            assertEquals(1.5, store.readFloat("ARGF", longArrayOf(24)))
            assertFailsWith<CodeEE> { store.writeFloat("ARGF", longArrayOf(25), 1.0) }
        } finally {
            store.leaveFunction()
        }
        // 另一个函数没声明 ⇒ 仍出厂 10
        val other = VariableStore()
        other.enterFunction("U")
        try {
            assertEquals(10, other.getLength("ARGF"), "逐函数注册（fork LabelDictionary.cs:116 的 resizeLocalVars 按函数名）")
        } finally {
            other.leaveFunction()
        }
        // 1.824 对照：整型 ARG 的声明长同一生效（max 口径）
        val intStore = storeWith(arg = 1200)
        intStore.enterFunction("T")
        try {
            assertEquals(1200, intStore.getLength("ARG"), "ARG 取 max(默认 1000, 声明 1200)")
            assertEquals(1000, intStore.getLength("LOCAL"), "LOCAL 的声明缺席 ⇒ 出厂 1000")
        } finally {
            intStore.leaveFunction()
        }
    }

    /** **只增不减**：声明比档位小时不得缩短（fork `VariableLocal.cs:52` 的 `newSize = size`）。 */
    @Test
    fun `浮点声明长只增不减`() {
        // 出厂档 10 + 声明 3 ⇒ 10
        val a = storeWith(floatArg = 3)
        a.enterFunction("T")
        try {
            assertEquals(10, a.getLength("ARGF"), "声明 3 < 档位 10 ⇒ 仍 10")
            assertFailsWith<CodeEE> { a.readFloat("ARGF", longArrayOf(10)) }
        } finally {
            a.leaveFunction()
        }
        // VARIABLESIZE.CSV 把档抬到 40，声明 5 ⇒ 40（档位是下界，不是上限）
        val sizes = VariableSizes().apply { setFloat1D("ARGF", 40) }
        val b = storeWith(floatArg = 5, sizes = sizes)
        b.enterFunction("T")
        try {
            assertEquals(40, b.getLength("ARGF"), "声明只增不减 ⇒ 档位 40 生效")
            b.writeFloat("ARGF", longArrayOf(39), 2.0)
            assertEquals(2.0, b.readFloat("ARGF", longArrayOf(39)))
        } finally {
            b.leaveFunction()
        }
        // 1.824 对照：ARG 的同一条下界（默认 1000，声明 5 不缩短）
        val c = storeWith(arg = 5)
        c.enterFunction("T")
        try {
            assertEquals(1000, c.getLength("ARG"), "整数侧同样只增不减（fork VariableLocal.cs:52 同一支）")
        } finally {
            c.leaveFunction()
        }
    }

    /**
     * `VARSIZE 档 20` + `#LOCALFSIZE 30` + 形参声明 12：LOCALF = 30（声明优先），
     * ARGF = `max(20, 12)` = 20 —— ARGF 与 `#LOCALFSIZE` **不互相抬界**
     * （严格照抄裁决落地，CONTRACT-RT2-M32A 判据 2 口径差 1；fork `VariableLocal.cs:48-49`
     * 的 ARGF 支只读 `ArgFloatLength`。原 TODO(verify) 已销）。
     */
    @Test
    fun `档位与形参声明定ARGF而LOCALFSIZE只管LOCALF`() {
        val sizes = VariableSizes().apply {
            setFloat1D("LOCALF", 20)
            setFloat1D("ARGF", 20)
        }
        val store = storeWith(floatLocal = 30, floatArg = 12, sizes = sizes)
        store.enterFunction("T")
        try {
            assertEquals(30, store.getLength("LOCALF"))
            assertEquals(20, store.getLength("ARGF"), "ARGF = max(档位 20, 形参声明 12)，不吃 #LOCALFSIZE 30")
            store.writeFloat("LOCALF", longArrayOf(29), 1.0)
            assertFailsWith<CodeEE> { store.readFloat("LOCALF", longArrayOf(30)) }
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：同一份 CSV 的 LOCAL/LOCALS 行照常（不受浮点面影响）。
        // 按 code 走 applySize：LOCAL 族不在 setInt1D/setStr1D 的名字清单里（那两只只认
        // SystemLayout.int1D / str1D，LOCAL/ARG/LOCALS/ARGS 在 localInt / localStr 里）。
        val s2 = VariableSizes().apply {
            applySize(VariableCode.LOCAL_VAR, intArrayOf(200))
            applySize(VariableCode.LOCALS, intArrayOf(300))
        }
        val store2 = VariableStore(VariableRegistry(s2))
        store2.enterFunction("T")
        try {
            assertEquals(200, store2.getLength("LOCAL"))
            assertEquals(300, store2.getLength("LOCALS"))
        } finally {
            store2.leaveFunction()
        }
    }

    /**
     * 本仓整数侧已是 fork 的**分型口径**（ARG 取 max、LOCAL 声明优先可缩）：
     * spec: fork-src VariableLocal.cs:50-53 的钳制名单只有 ARG/ARGS（+ARGF），
     * LOCAL/LOCALS 声明优先。浮点侧经 CONTRACT-RT2-M32A 判据 2 裁决后与整数侧
     * 分型对称：LOCALF 声明优先（可缩），ARGF 取上界；受影响的两条断言已刷新。
     */
    @Test
    fun `整数侧LOCAL声明优先可缩而ARG不可缩`() {
        val store = storeWith(local = 4, arg = 4)
        store.enterFunction("T")
        try {
            assertEquals(4, store.getLength("LOCAL"), "LOCAL 声明优先（可缩）")
            assertEquals(1000, store.getLength("ARG"), "ARG 取 max(默认, 声明)")
        } finally {
            store.leaveFunction()
        }
        // 浮点侧同分型：LOCALF 声明优先可缩（fork 钳制名单无 LOCALF，非事件不钳制）
        val f = storeWith(floatLocal = 4)
        f.enterFunction("T")
        try {
            assertEquals(4, f.getLength("LOCALF"), "LOCALF 声明优先（可缩，与 LOCAL 同分型）")
        } finally {
            f.leaveFunction()
        }
    }

    /**
     * 同一函数重复进帧共用一份实例（递归同长度），且 `floatArg` 只在建实例时读一次：
     * 实例长度缓存后即使随后改注册表也不追溯（与 `FloatLocal` 的懒建缓存同判据）。
     */
    @Test
    fun `重复进帧共用同一份浮点局部长度`() {
        val store = storeWith(floatArg = 25)
        repeat(2) {
            store.enterFunction("T")
            try {
                assertEquals(25, store.getLength("ARGF"))
                store.writeFloat("ARGF", longArrayOf(24), 7.0)
            } finally {
                store.leaveFunction()
            }
        }
        store.enterFunction("T")
        try {
            assertEquals(7.0, store.readFloat("ARGF", longArrayOf(24)), "递归共用同一份（fork VariableLocal.cs:21 的 per-subKey 字典）")
        } finally {
            store.leaveFunction()
        }
        // 1.824 对照：整型 ARG 同判据
        val i = storeWith(arg = 1200)
        i.enterFunction("T")
        try {
            i.writeInt("ARG", longArrayOf(1199), 3L)
        } finally {
            i.leaveFunction()
        }
        i.enterFunction("T")
        try {
            assertEquals(3L, i.readInt("ARG", longArrayOf(1199)))
        } finally {
            i.leaveFunction()
        }
    }
}
