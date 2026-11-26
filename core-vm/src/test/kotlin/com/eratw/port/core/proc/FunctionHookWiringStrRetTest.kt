package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.HookPipeline
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-11-26 B1/B2：嵌套 hook **三类调用点接管 + 双返回通道**。
 *
 * 旧实现三道旁路（REVIEW-MOD-ARCH-20261123 B1）拆除后的钉：
 * ① 字符串返回位（`_CALL.b` bit28，CALLF / `#FUNCTIONS` 家族）—— 宿主按 [HookedCallReturn.strValue]
 *    交割，串返回不再被按 0 丢弃（B2）；
 * ② `CALL_STR` 运行期调用点（`_CALL_BY_NAME` / `_CALL_STR`）—— 实参元数据在宿主槽（消费式），
 *    **确定接管才消费**（[ProcessRuntime.resolveNestedHookTarget]），不走接管时表原样留给
 *    原生绑定，同帧后续原生运行期调用不受污染；
 * ③ `depth == 0` 尾跳 —— 经 [com.eratw.port.core.code.Vm.STUB_TAIL_RETURN] 哨兵携值交回 run
 *    （int 通道回 run 结果、str 通道写 `Vm.stringResult`）。
 *
 * 反向证明贯穿每一条：无接线时 `nestedHookArgDecodes` 恒 0（连实参数组都不构造），
 * 输出与返回值与改造前逐位一致。
 */
class FunctionHookWiringStrRetTest {

    // ---------------------------------------------------- ① isStrRet 静态 CALL（CALLF 家族）

    @Test
    fun `strRet静态CALL_前缀Replace按串交割`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("hooked-s"))
        }
        h.attach(pipeline)
        h.callMain("STRMAIN")
        assertEquals("hooked-s\n", h.output, "串替换值必须原样写进调用方返回槽")
        assertEquals(1, h.runtime.nestedHookArgDecodes, "isStrRet 调用点必须真的被接管")
    }

    @Test
    fun `strRet静态CALL_无接线_原生串返回`() {
        val h = StrRetHarness()
        h.callMain("STRMAIN")
        assertEquals("orig-s\n", h.output)
        assertEquals(0, h.runtime.nestedHookArgDecodes, "反向：无接线零投影（旁路近零成本）")
    }

    @Test
    fun `strRet静态CALL_后缀mayMutateReturn_改串`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.postfix("mod.a", targetKey = "S", mayMutateReturn = true))
        pipeline.bind("S", ModHookPhase.POSTFIX, "mod.a") { context ->
            context.returnValue = ModValue.StrValue("changed-s")
            HookResult.Continue
        }
        h.attach(pipeline)
        h.callMain("STRMAIN")
        assertEquals("changed-s\n", h.output, "后缀改串交割（S 体不打印，调用位只收交割值 changed-s）")
    }

    @Test
    fun `strRet通道错配_整数替换值折空串并告警`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        h.callMain("STRMAIN")
        assertEquals("\n", h.output, "str 调用位收到整数替换值 ⇒ 折空串（不静默不抛）")
        assertTrue(
            h.logs.any { it.contains("折成空串") },
            "错配必须告警，实际日志：${h.logs}",
        )
    }

    @Test
    fun `int通道错配_串替换值折0并告警`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("not-a-number"))
        }
        h.attach(pipeline)
        h.callMain("RTMAIN")
        assertEquals("0\n", h.output, "int 调用位收到字符串替换值 ⇒ 折 0（既有 B2 前口径不变）")
        assertTrue(h.logs.any { it.contains("按 0 返回") }, "错配必须告警，实际日志：${h.logs}")
    }

    // ---------------------------------------------------- ② CALL_STR 运行期调用点

    @Test
    fun `CALL_STR运行期调用点_钩住_实参投影与Replace短路`() {
        val h = StrRetHarness()
        val observed = ArrayList<Long>()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { context ->
            observed += context.args[0].intValue
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        h.callMain("RTMAIN")
        assertEquals(listOf(41L), observed, "运行期实参（名字串 f,41）必须按位投影给钩子")
        assertEquals("42\n", h.output, "Replace 短路后原函数体不跑")
        assertEquals(1, h.runtime.nestedHookArgDecodes)
    }

    @Test
    fun `CALL_STR_无接线_原生照旧`() {
        val h = StrRetHarness()
        h.callMain("RTMAIN")
        assertEquals("orig-f\n41\n", h.output, "反向：无接线 = 原生绑定与执行，输出逐位一致")
        assertEquals(0, h.runtime.nestedHookArgDecodes)
    }

    @Test
    fun `CALL_STR_串返回位_Replace按串交割`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("hooked-rt"))
        }
        h.attach(pipeline)
        h.callMain("RTSTRMAIN")
        assertEquals("hooked-rt\n", h.output, "运行期串调用点的串替换值同样按位交割")
    }

    @Test
    fun `CALL_STR钩住后_同帧后续原生CALL_STR不受污染`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        h.callMain("MIXEDMAIN")
        assertEquals(
            "orig-g\n42\n9\n",
            h.output,
            "被钩的 F（Replace 42，体不跑）与未钩的 G（原生，先打 orig-g）在同一帧内先后运行，" +
                "两次 _CALL_STR 之后才 PRINT：期望序 = orig-g（G 体打印）→ 42（R3）→ 9（R4）；" +
                "F 接管消费自己的元数据表后，G 的原生绑定必须拿到 G 自己的表（实参 9 生效）",
        )
        assertEquals(1, h.runtime.nestedHookArgDecodes, "只有被钩的 F 投影一次；G 无声明槽直接旁路（零投影）")
    }

    // ---------------------------------------------------- ③ depth == 0 尾跳

    @Test
    fun `depth0尾跳_钩子携值交回run`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(99L))
        }
        h.attach(pipeline)
        assertEquals(99L, h.callMain("TAILMAIN"), "depth==0 尾跳的钩子返回值就是 run 的结果")
        assertEquals("", h.output, "短路后原函数体不跑")
        assertEquals(1, h.runtime.nestedHookArgDecodes)
    }

    @Test
    fun `depth0尾跳_无接线_原生照旧`() {
        val h = StrRetHarness()
        assertEquals(10L, h.callMain("TAILMAIN"), "反向：原生尾跳 = 被调函数返回值直达 run 结果")
        assertEquals("orig-f\n", h.output)
        assertEquals(0, h.runtime.nestedHookArgDecodes)
    }

    @Test
    fun `depth0串尾跳_钩子Replace真串交stringResult`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("tail-str"))
        }
        h.attach(pipeline)
        assertEquals(0L, h.callMain("TAILSTRMAIN"), "串尾跳的 run 结果恒 0（串经 stringResult 取，既有契约）")
        assertEquals("tail-str", h.runtime.vmStringResultForTest(), "钩子的串替换值必须写进 stringResult")
    }

    @Test
    fun `depth0串尾跳_Continue_原生串经stringResult`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") { HookResult.Continue }
        h.attach(pipeline)
        h.callMain("TAILSTRMAIN")
        assertEquals("orig-s", h.runtime.vmStringResultForTest(), "放行后原生 _RETS 的串照常写寄存器")
    }

    @Test
    fun `depth0串尾跳_整数替换值折空串`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(5L))
        }
        h.attach(pipeline)
        h.callMain("TAILSTRMAIN")
        assertEquals("", h.runtime.vmStringResultForTest(), "str 通道错配 ⇒ 折空串")
        assertTrue(h.logs.any { it.contains("折成空串") }, "错配必须告警，实际日志：${h.logs}")
    }

    @Test
    fun `depth大于0串尾跳_经leaveFrameStr交割调用方返回槽`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "S"))
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("deep-tail"))
        }
        h.attach(pipeline)
        h.callMain("TAILOUTER")
        assertEquals("deep-tail\n", h.output, "depth>0 尾跳走既有退栈路径，串值写调用方返回槽")
    }

    // ---------------------------------------------------- 卸载回旁路

    @Test
    fun `卸载接线后三类调用点全回旁路`() {
        val h = StrRetHarness()
        val pipeline = h.pipeline(
            HookDecls.prefix("mod.a", targetKey = "F"),
            HookDecls.prefix("mod.a", targetKey = "S"),
        )
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { HookResult.Continue }
        pipeline.bind("S", ModHookPhase.PREFIX, "mod.a") { HookResult.Continue }
        h.attach(pipeline)
        h.callMain("RTMAIN")
        assertEquals(1, h.runtime.nestedHookArgDecodes)
        h.detach(pipeline)
        h.runtime.nestedHookArgDecodes = 0
        h.runtime.output.clear() // 剧本打印面跨 run 累积：只比对卸载后这一次的输出
        h.callMain("RTMAIN")
        assertEquals("orig-f\n41\n", h.output, "卸载后运行期调用点回原生")
        assertEquals(0, h.runtime.nestedHookArgDecodes, "卸载后零投影")
    }
}

/**
 * 三类调用点测试夹具（[NestedHarness] 的 B1 扩展版）：
 * - `STRMAIN`：`_CALL s, 0, R1, strret`（isStrRet 静态 CALL）→ `_PRINT_STR R1`；
 * - `RTMAIN`：`_CALL_STR R2, 0, R3`（名字串 "f,41"，int 运行期调用点）→ `_PRINT_INT R3`；
 * - `RTSTRMAIN`：`_CALL_STR R2, STR_RET_FLAG, R3`（名字串 "s"，串返回运行期调用点）→ `_PRINT_STR R3`；
 * - `MIXEDMAIN`：先钩的 F（"f,7"）再未钩的 G（"g,9"），两次 _CALL_STR 后才 PRINT ⇒
 *   期望输出序 = orig-g → 42 → 9；验元数据表消费不串；
 * - `TAILMAIN`：`_CALL f, 1, none, jump`（depth==0 int 尾跳）；
 * - `TAILSTRMAIN`：`_CALL s, 0, none, jump, strret`（depth==0 串尾跳）；
 * - `TAILOUTER`：常规调 `INNER`，`INNER` 尾跳 s（depth>0 串尾跳）→ `_PRINT_STR R2`；
 * - `F` / `G`：打印各自名并返回 ARG:0；`S`：`_SET_STR R0, "orig-s"; _RETS R0`。
 */
private class StrRetHarness {

    val store = VariableStore()

    val logs = ArrayList<String>()

    val program: Program = Assembler.assemble(SOURCE, listOf(argZeroExpr(store)))

    private val metas: List<FuncMeta> = listOf(
        meta(program, "main"),
        meta(program, "strmain"),
        meta(program, "rtmain"),
        meta(program, "rtstrmain"),
        meta(program, "mixedmain"),
        meta(program, "tailmain"),
        meta(program, "tailstrmain"),
        meta(program, "tailouter"),
        meta(program, "inner", argInt = 1),
        meta(program, "f", argInt = 1),
        meta(program, "g", argInt = 1),
        meta(program, "s"),
    )

    val runtime: ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())

    /** 剧本打印面（短路 / 交割的可观测面）。 */
    val output: String get() = runtime.output.toString()

    fun pipeline(vararg declarations: DeclaredHook): HookPipeline = HookPipeline.of(declarations.toList())

    fun attach(pipeline: HookPipeline) = FunctionHookWiring.install(store, pipeline, logs::add)

    fun detach(pipeline: HookPipeline) = FunctionHookWiring.uninstall(store, pipeline)

    /** 宿主按名调用指定入口（各调用点用例按名选 MAIN 变体）。 */
    fun callMain(entry: String): Long = runtime.callFunction(entry)

    private companion object {

        val SOURCE: String = """
            .func main slots=1
              _HALT

            .func strmain slots=4
              _CALL s, 0, R1, strret
              _PRINT_STR R1, 1
              _RET

            .func rtmain slots=4
              _SET_STR R2, "f,41"
              _CALL_STR R2, 0, R3
              _PRINT_INT R3, 1
              _RET

            .func rtstrmain slots=4
              _SET_STR R2, "s"
              _CALL_STR R2, 268435456, R3
              _PRINT_STR R3, 1
              _RET

            .func mixedmain slots=6
              _SET_STR R2, "f,7"
              _CALL_STR R2, 0, R3
              _SET_STR R2, "g,9"
              _CALL_STR R2, 0, R4
              _PRINT_INT R3, 1
              _PRINT_INT R4, 1
              _RET

            .func tailmain slots=3
              _SET_INT R0, 10
              _CALL f, 1, none, jump

            .func tailstrmain slots=4
              _SET_STR R2, "pad"
              _CALL s, 0, none, jump, strret

            .func tailouter slots=4
              _SET_INT R0, 10
              _CALL inner, 1, R2
              _PRINT_STR R2, 1
              _RET

            .func inner arity=1 slots=2
              _EVAL_INT ${'$'}0, R1
              _CALL s, 0, none, jump, strret

            .func f arity=1 params=ARG slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "orig-f", 1
              _RET R1

            .func g arity=1 params=ARG slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "orig-g", 1
              _RET R1

            .func s slots=2
              _SET_STR R0, "orig-s"
              _RETS R0
        """.trimIndent()

        fun argZeroExpr(store: VariableStore): Expr =
            parseOrThrow(listOf(id("ARG"), colon(), i(0)), StrRetStoreResolver(store))

        /** asm 函数名小写、meta 键按 ERB 习惯大写：查找用 IGNORE CASE。 */
        fun meta(program: Program, name: String, argInt: Int = 0): FuncMeta {
            val index = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            require(index >= 0) { "函数 $name 不在程序中" }
            return FuncMeta(
                index = index,
                key = name.uppercase(),
                name = name,
                isEvent = false,
                argIntCount = argInt,
                defIndex = index,
            )
        }
    }
}

/** 夹具里的表达式只读 `ARG` 一维，不做函数名解析。 */
private class StrRetStoreResolver(private val store: VariableStore) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name, null)

    override fun resolveFunction(name: String): OperandType? = null
}
