package com.eratw.port.core.rand

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.clampWarnings
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.981 权重池抽选链的初始化器回归锁（lane rand0，2026-11-14；收口
 * `RandZeroArgChainAuditTest` KDoc 里挂的 TODO(verify)）。
 *
 * 背景：玩家上报簇 `RAND 的参数必须为正数（实际 0） @ QOL_GATHERING_DRAWING 行69`
 * 的取值根是 **4.980** 语料的 `CHOSEN_ID = FINAL_POOL:(RAND:FINAL_COUNT)`（0 基 69 =
 * 物理 70），那半边的引擎侧缺陷（1.824 抛错语义）已由 76a360ec0 按 4.981 12.2.0 钳制
 * 收口。4.981 把同一函数整段重写成权重池（`ROLL_VAL = RAND:TOTAL_WEIGHT`，0 基 66），
 * 上一波只对齐了 4.980 的行号，权重池这条链留了 TODO(verify)：
 * 「验 TOTAL_WEIGHT 的累加与 VALID_WEIGHT:VALID_COUNT 回读」。
 *
 * 链路四节，本类各钉一节（4.981 语料原文
 * `ERB/魔改内容/qol/QOL_COMF445 採集する.ERB`）：
 * ① 声明解析：`#DIM CONST GATHER_WEIGHT, 6 = 100, 70, 40, 20, 10, 10`（物理 37，
 *    函数体内 = 私有）→ [DimSpec.defaultsInt] 逐元素、`isStatic=true`（CONST 与
 *    DYNAMIC 互斥，UserVariables.kt 的关键字冲突表；vanilla 私有常量走
 *    `StaticInt1DVariableToken`，spec: VariableData.cs:1295-1321）。首元素为 0 的
 *    `GATHER_VALUE_LEVEL`（物理 95）同时钉——防「前导 0 被丢」的回归。
 * ② 运行期回填：私有静态数组的懒分配抄 `defaultsInt`（UserVariables.kt
 *    `StaticIntUserVar.arr()`，spec: VariableData.cs:1311-1321 IfNullInitArray），
 *    跨 CALL 持久不重置。
 * ③ 累加 + 加权抽选：`VALID_WEIGHT:VALID_COUNT = GATHER_WEIGHT:<等级>`（物理 49）→
 *    `TOTAL_WEIGHT += …`（物理 50）→ `ROLL_VAL = RAND:TOTAL_WEIGHT`（物理 67）的
 *    加权走池。等级行照 `RandZeroArgChainAuditTest.qolLoop` 的同款简化（真函数的
 *    `GET_GATHERING_LEVEL(ITEMPRICE:…)` 换成铺好的 `LVL` 读数，避免拖整条物品链）。
 * ④ 反向锁：若哪天初始化器链断掉（defaultsInt 丢失 ⇒ 权重全 0 ⇒ TOTAL_WEIGHT=0），
 *    4.981 12.2.0 的钳制必须让会话**活着**（返回 0 + 一次性告警 + 不消耗 RNG）——
 *    这正是玩家那簇崩溃在权重池语料上的「假如复发」形态；断掉时抽选退化但不再中断。
 *
 * 真语料端到端读数（本波取证，桌面 vmdbg + 4.981 全量 compat 档 erbc）：
 * `call QOL_Gathering_Drawing --args 1,500,0` PASS、steps=4241618、控制台零告警、
 * `TOTAL_WEIGHT` 的 var 事件按 100/40/70/10 步进真实累加、两次复跑 steps 逐位一致
 * （种子 20260226）。本类把同一链路的**单元级**形状钉死，语料闸不必再为它跑全量。
 */
class WeightedPoolInitChainTest {

    // ------------------------------------------------------------------
    // 装置：与 RandZeroArgChainAuditTest 同款最小装置（解析 + 编译 + 真 store）
    // ------------------------------------------------------------------

    /** RAND 上界桩：记账收到的 bound，返回 `bound-1`（可复现、不越界）。 */
    private class RecordingRand : RandProvider {
        val bounds = ArrayList<Long>()
        override fun next(bound: Long): Long {
            bounds.add(bound)
            return bound - 1
        }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("GATHER_WEIGHT", OperandType.INT, 1)
            .declareVariable("LVL", OperandType.INT, 1)
            .declareVariable("TOTAL_WEIGHT", OperandType.INT, 1)
            .declareVariable("VALID_POOL", OperandType.INT, 1)
            .declareVariable("VALID_WEIGHT", OperandType.INT, 1)
            .declareVariable("VALID_COUNT", OperandType.INT, 1)
            .declareVariable("BONUS_ROLLS", OperandType.INT, 1)
            .declareVariable("ROLL_VAL", OperandType.INT, 1)
            .declareVariable("CUR_WEIGHT", OperandType.INT, 1)
            .declareVariable("ROLLED_ITEM", OperandType.INT, 1)
            .declareVariable("CHOSEN_ID", OperandType.INT, 1),
    )

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "wpool.erb", i + 1, text))
        }
    }

    /**
     * 4.981 `QOL_COMF445 採集する.ERB:43-55 / :66-80` 的权重累加 + 加权抽选，
     * 逐行同形（变量名照抄；等级由 `LVL` 铺，见类 KDoc ③）。
     * 「保留等级最高」的跨轮比较换成 `ROLLED_ITEM > 0` 收口——本轮判据只钉
     * 权重链与走池读数，等级比较归物品链（不在本类范围）。
     */
    private val weightedDraw = listOf(
        "FOR LOCAL, 0, VALID_COUNT",
        "\tVALID_WEIGHT:LOCAL = GATHER_WEIGHT:(LVL:(VALID_POOL:LOCAL))",
        "\tTOTAL_WEIGHT += VALID_WEIGHT:LOCAL",
        "NEXT",
        "FOR LOCAL, 0, BONUS_ROLLS",
        "\tROLL_VAL = RAND:TOTAL_WEIGHT",
        "\tCUR_WEIGHT = 0",
        "\tFOR LOCAL:1, 0, VALID_COUNT",
        "\t\tCUR_WEIGHT += VALID_WEIGHT:(LOCAL:1)",
        "\t\tIF ROLL_VAL < CUR_WEIGHT",
        "\t\t\tROLLED_ITEM = VALID_POOL:(LOCAL:1)",
        "\t\t\tBREAK",
        "\t\tENDIF",
        "\tNEXT",
        "\tIF ROLLED_ITEM > 0",
        "\t\tCHOSEN_ID = ROLLED_ITEM",
        "\tENDIF",
        "NEXT",
    )

    private class Runner(val store: VariableStore, val rand: RecordingRand, val runtime: ProcessRuntime)

    /**
     * 编 `@MAIN` 并跑 [body]；[weightSpec] 非空时按装载链同款
     * `declareUserVar(spec, functionKey)` 注册为 **MAIN 的私有变量**。
     */
    private fun run(body: List<String>, weightSpec: DimSpec?): Runner {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), parse(*body.toTypedArray()), setOf("MAIN"))
        val rand = RecordingRand()
        val store = VariableStore().apply {
            randProvider = rand
            for (n in listOf(
                "TOTAL_WEIGHT", "VALID_COUNT", "BONUS_ROLLS", "ROLL_VAL",
                "CUR_WEIGHT", "ROLLED_ITEM", "CHOSEN_ID",
            )) {
                declareUserVar(DimSpec(n, isStr = false, dims = intArrayOf(1)))
            }
            for (n in listOf("VALID_POOL", "VALID_WEIGHT")) {
                declareUserVar(DimSpec(n, isStr = false, dims = intArrayOf(500)))
            }
            declareUserVar(DimSpec("LVL", isStr = false, dims = intArrayOf(1000)))
            if (weightSpec != null) declareUserVar(weightSpec, functionKey = "MAIN")
        }
        val runtime = ProcessRuntime(
            c.build("MAIN"),
            store,
            FunctionTable(
                listOf(
                    FuncMeta(
                        index = c.functions.single().index,
                        key = "MAIN",
                        name = "MAIN",
                        position = "wpool.erb",
                    ),
                ),
            ),
            VmConfig(),
        )
        return Runner(store, rand, runtime)
    }

    /** 铺池：3 件素材（id 100..102）等级 [0,1,2] ⇒ 权重 100+70+40 = 210。 */
    private fun Runner.seed() {
        store.set0("VALID_COUNT", 3L)
        store.set0("BONUS_ROLLS", 2L)
        listOf(100L to 0L, 101L to 1L, 102L to 2L).forEachIndexed { i, (id, lv) ->
            store.writeInt("VALID_POOL", longArrayOf(i.toLong()), id)
            store.writeInt("LVL", longArrayOf(id), lv)
        }
    }

    private fun VariableStore.i0(name: String): Long = readInt(name, longArrayOf(0L))
    private fun VariableStore.set0(name: String, v: Long) = writeInt(name, longArrayOf(0L), v)

    // ------------------------------------------------------------------
    // ① 声明解析：4.981 原文 → DimSpec
    // ------------------------------------------------------------------

    @Test
    fun `四九八一权重池声明原文折进 defaultsInt`() {
        // 物理行 37 原文（`= ` 后的空格与逗号间距照抄）
        val w = DimParser.parse("#DIM", "CONST GATHER_WEIGHT, 6 = 100, 70, 40, 20, 10, 10", isPrivate = true)
        assertEquals("GATHER_WEIGHT", w.name)
        assertEquals(listOf(6), w.dims.toList(), "显式尺寸 6")
        assertEquals(true, w.isConst)
        assertEquals(true, w.isPrivate)
        assertEquals(true, w.isStatic, "CONST 与 DYNAMIC 互斥 ⇒ 私有常量走静态 token（vanilla StaticInt1D）")
        assertEquals(false, w.isStr)
        assertEquals(listOf(100L, 70L, 40L, 20L, 10L, 10L), w.defaultsInt?.toList(), "逐元素初值，顺序与原文一致")

        // 物理行 95（#FUNCTION 体里的同族声明）：首元素是 0，防「前导 0 被丢」
        val lvl = DimParser.parse(
            "#DIM",
            "CONST GATHER_VALUE_LEVEL, 6 = 0, 500, 2000, 4000, 7000, 10000",
            isPrivate = true,
        )
        assertEquals(listOf(0L, 500L, 2000L, 4000L, 7000L, 10000L), lvl.defaultsInt?.toList())
    }

    // ------------------------------------------------------------------
    // ② 运行期回填：懒分配抄初值、跨 CALL 持久
    // ------------------------------------------------------------------

    @Test
    fun `私有静态常量数组懒回填初值且跨调用持久`() {
        val spec = DimParser.parse("#DIM", "CONST GATHER_WEIGHT, 6 = 100, 70, 40, 20, 10, 10", isPrivate = true)
        val store = VariableStore()
        store.declareUserVar(spec, functionKey = "QOL_GATHERING_DRAWING")

        val expected = listOf(100L, 70L, 40L, 20L, 10L, 10L)
        store.enterFunction("QOL_GATHERING_DRAWING")
        expected.forEachIndexed { i, v ->
            assertEquals(v, store.readInt("GATHER_WEIGHT", longArrayOf(i.toLong())), "GATHER_WEIGHT:$i")
        }
        store.leaveFunction()

        // 第二次 CALL：静态 token 不重置（vanilla IfNullInitArray 只在 array==null 时建），
        // 读数必须仍是初值——不许出现「第二次进函数读 0」的回归。
        store.enterFunction("QOL_GATHERING_DRAWING")
        expected.forEachIndexed { i, v ->
            assertEquals(v, store.readInt("GATHER_WEIGHT", longArrayOf(i.toLong())), "第二次 CALL 后 GATHER_WEIGHT:$i")
        }
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // ③ 累加 + 加权抽选（编译产物真跑）
    // ------------------------------------------------------------------

    @Test
    fun `权重池累加出二一零且加权走池命中末件`() {
        val spec = DimParser.parse("#DIM", "CONST GATHER_WEIGHT, 6 = 100, 70, 40, 20, 10, 10", isPrivate = true)
        val r = run(weightedDraw, weightSpec = spec)
        r.seed()
        r.runtime.execute()

        assertEquals(210L, r.store.i0("TOTAL_WEIGHT"), "100+70+40：权重来自私有常量数组的初值")
        assertEquals(listOf(210L, 210L), r.rand.bounds, "每轮 RAND 的上界 = TOTAL_WEIGHT（正数，未触钳制）")
        // 桩按 bound-1 返回 209：走池 100 → 170 → 210，209 < 210 在第 3 件命中
        assertEquals(102L, r.store.i0("ROLLED_ITEM"), "ROLL_VAL=209 落在第 3 段权重区间")
        assertEquals(102L, r.store.i0("CHOSEN_ID"), "两轮都命中同一件（同桩同值）")
        assertTrue(
            r.store.aux.clampWarnings().lines().isEmpty(),
            "权重正常时不许出现钳制告警：${r.store.aux.clampWarnings().lines()}",
        )
    }

    // ------------------------------------------------------------------
    // ④ 反向锁：初始化器链若断（权重全 0），钳制保命不中断
    // ------------------------------------------------------------------

    /**
     * defaultsInt 缺席的私有静态数组读数恒 0（懒分配没有初值可抄）⇒ TOTAL_WEIGHT=0 ⇒
     * `RAND:0`。4.981 12.2.0 起这一步钳制返回 0、首次告警、不消耗 RNG——玩家那簇
     * 「启动失败」在新语料上的「假如复发」形态就是这条。抽选退化（CHOSEN_ID 停 0）
     * 但会话必须活着。
     */
    @Test
    fun `权重全零时上界零被钳制且不中断`() {
        // 与 ① 同名同形状、但没有初值段 ⇒ defaultsInt = null（模拟初始化器链断裂）
        val broken = DimSpec(
            "GATHER_WEIGHT",
            isStr = false,
            dims = intArrayOf(6),
            isConst = true,
            isStatic = true,
            isPrivate = true,
        )
        val r = run(weightedDraw, weightSpec = broken)
        r.seed()
        val t = runCatching { r.runtime.execute() }.exceptionOrNull()
        assertEquals(null, t, "4.981 12.2.0：RAND:0 钳制返回 0 不中断，实际 ${t?.javaClass?.name}: ${t?.message}")
        assertEquals(0L, r.store.i0("TOTAL_WEIGHT"), "无初值 ⇒ 权重全 0 ⇒ 累加停在 0")
        assertTrue(r.rand.bounds.isEmpty(), "bound 0 在取值桩之前就钳制返回，桩不该被调到：${r.rand.bounds}")
        assertEquals(0L, r.store.i0("ROLLED_ITEM"), "走池 `ROLL_VAL < CUR_WEIGHT` 恒假 ⇒ 无命中")
        assertEquals(0L, r.store.i0("CHOSEN_ID"), "抽选退化：保持初值 0")
        assertTrue(
            r.store.aux.clampWarnings().lines().single().startsWith("RAND不能指定0以下的值(0)"),
            "钳制告警留痕（一次性），便于定位初始化器链断点",
        )
    }
}
