package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §20.1 函数 hook 的**嵌套调用侧**（task-25）：ERB 内部 `CALL` 指令 → `Vm.callInto`/`jumpInto`
 * 调用边界 → [com.eratw.port.core.code.VmHost.dispatchNestedHookCall] →
 * [FunctionHookWiring.dispatchCall]。
 *
 * 这一半锁「脚本函数里的 `CALL`」这条漏斗（与 [FunctionHookWiringCallTest] 锁的宿主按名调用
 * 互为对照）：槽语义全在**真 VM + 真嵌套 VM**上跑。反向证明（最重要的一条）：未装接线、
 * 或该键无声明槽时，[ProcessRuntime.nestedHookArgDecodes] 必须保持 0 —— 即**连实参数组都
 * 没构造过**，返回值与打印面与改造前逐位一致。
 */
class FunctionHookWiringNestedCallTest {

    @Test
    fun `嵌套CALL命中PREFIX_原函数照常执行`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        var hits = 0
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            hits++
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(10L, h.callMain(), "钩子不得改变返回值")
        assertEquals("orig\n", h.output, "Continue 必须放行原函数体")
        assertEquals(1, hits, "嵌套 _CALL 必须派发一次 PREFIX")
        assertEquals(1, h.runtime.nestedHookArgDecodes, "有槽时实参投影发生一次")
    }

    @Test
    fun `无接线_嵌套调用完全旁路且不构造实参`() {
        val h = NestedHarness()
        assertEquals(10L, h.callMain())
        assertEquals("orig\n", h.output)
        assertEquals(0, h.runtime.nestedHookArgDecodes, "无接线时必须零实参构造（旁路近零成本）")
        assertTrue(h.logs.isEmpty(), "旁路路径不该有任何接线日志")
    }

    @Test
    fun `装了接线但该键无声明槽_仍然旁路且不构造实参`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "OTHER"))
        h.attach(pipeline)
        assertEquals(10L, h.callMain())
        assertEquals("orig\n", h.output)
        assertEquals(0, h.runtime.nestedHookArgDecodes, "无槽键 = 在解实参前旁路")
    }

    @Test
    fun `前缀Replace_短路嵌套原函数`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        assertEquals(42L, h.callMain(), "前缀 Replace 的返回值就是本次调用返回值")
        assertEquals("", h.output, "短路后嵌套原函数体不许执行")
    }

    @Test
    fun `后缀声明mayMutateReturn_改嵌套返回值`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.postfix("mod.a", targetKey = "F", mayMutateReturn = true))
        pipeline.bind("F", ModHookPhase.POSTFIX, "mod.a") { context ->
            context.returnValue = ModValue.IntValue(77L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(77L, h.callMain())
        assertEquals("orig\n", h.output, "后缀在原函数之后，原函数必须跑过")
    }

    @Test
    fun `SkipOriginal_嵌套原函数体不跑_后缀照跑`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(
            HookDecls.prefix("mod.a", targetKey = "F"),
            HookDecls.postfix("mod.b", targetKey = "F", mayMutateReturn = true),
        )
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { HookResult.SkipOriginal }
        pipeline.bind("F", ModHookPhase.POSTFIX, "mod.b") { context ->
            context.returnValue = ModValue.IntValue(999L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(999L, h.callMain(), "跳过原函数后，返回值为后缀改出的值")
        assertEquals("", h.output, "SkipOriginal 后嵌套原函数体不许执行")
    }

    @Test
    fun `声明mayMutateArgs_实参回流到嵌套原函数`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F", mayMutateArgs = true))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { context ->
            context.args[0].intValue = 99L
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(99L, h.callMain(), "授权的实参改动必须流到嵌套原函数")
        assertEquals("orig\n", h.output)
    }

    @Test
    fun `未声明mayMutateArgs_实参改动被隔离`() {
        val h = NestedHarness()
        var observed = -1L
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F", mayMutateArgs = false))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { context ->
            observed = context.args[0].intValue
            context.args[0].intValue = 99L
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(10L, h.callMain(), "未授权 = 隔离，改不动嵌套调用的实参")
        assertEquals(10L, observed, "钩子必须看到调用点的真实实参")
    }

    @Test
    fun `两层嵌套调用逐层派发`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(
            HookDecls.prefix("mod.a", targetKey = "CHAIN"),
            HookDecls.prefix("mod.a", targetKey = "G"),
        )
        var chainHits = 0
        var gHits = 0
        pipeline.bind("CHAIN", ModHookPhase.PREFIX, "mod.a") {
            chainHits++
            HookResult.Continue
        }
        pipeline.bind("G", ModHookPhase.PREFIX, "mod.a") {
            gHits++
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(10L, h.callChainMain(), "链式调用返回值必须原样带回")
        assertEquals("orig\n", h.output)
        assertEquals(1, chainHits, "第一层 CHAIN 派发一次")
        assertEquals(1, gHits, "第二层 G（在嵌套 VM 里）派发一次")
        assertEquals(2, h.runtime.nestedHookArgDecodes, "两层各投影一次实参")
    }

    @Test
    fun `递归深度有界_抛CodeEE而非StackOverflowError且状态复位`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "REC"))
        var hits = 0
        pipeline.bind("REC", ModHookPhase.PREFIX, "mod.a") {
            hits++
            HookResult.Continue
        }
        h.attach(pipeline)
        val e = assertFailsWith<CodeEE> { h.callRec(5_000L) }
        assertTrue(
            e.message!!.contains("函数调用栈溢出"),
            "深度上限必须走既有方法栈口径的 CodeEE，实际：${e.message}",
        )
        assertTrue(hits > 0, "递归链上钩子确实被逐层派发过")
        // 失败路径必须把 methodDepth / nestedVmPool 复位：小递归再跑一遍应正常。
        assertEquals(3L, h.callRec(3L), "溢出失败后必须可继续正常嵌套调用")
    }

    @Test
    fun `顶层与嵌套调用语义一致_Replace对照`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        assertEquals(42L, h.callF(10L), "顶层宿主按名调用")
        assertEquals(42L, h.callMain(), "嵌套 _CALL 调用")
        assertEquals("", h.output, "两条通道都被前缀短路，原函数体都不跑")
    }

    @Test
    fun `卸载接线后嵌套调用回到旁路`() {
        val h = NestedHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "F"))
        pipeline.bind("F", ModHookPhase.PREFIX, "mod.a") { HookResult.Continue }
        h.attach(pipeline)
        assertEquals(10L, h.callMain())
        h.detach(pipeline)
        h.runtime.nestedHookArgDecodes = 0
        assertEquals(10L, h.callMain(), "卸载后必须回到完全旁路")
        assertEquals("orig\norig\n", h.output, "两次调用都真的执行了原函数体")
        assertEquals(0, h.runtime.nestedHookArgDecodes, "卸载后不得再投影实参")
    }

    // ---------------------------------------------------------------- 实参投影单测

    @Test
    fun `实参投影_有元数据按类型桶分列`() {
        val decoded = decodeNestedHookArgs(
            listOf(
                CallArg(position = 0, bucket = 0, isStr = false, name = null, present = true),
                CallArg(position = 1, bucket = 0, isStr = true, name = null, present = true),
            ),
            argc = 2,
            readInt = { 7L },
            readStr = { "x" },
        )
        assertContentEquals(longArrayOf(7L), decoded!!.intArgs)
        assertContentEquals(arrayOf("x"), decoded.strArgs)
    }

    @Test
    fun `实参投影_省略位或桶不连续时放弃`() {
        assertNull(
            decodeNestedHookArgs(
                listOf(CallArg(position = 0, bucket = 0, isStr = false, name = null, present = false)),
                argc = 1,
                readInt = { 0L },
                readStr = { "" },
            ),
            "省略位的原生语义是装载期默认顶替，双桶 API 表达不了缺口 ⇒ 必须整体放弃",
        )
        assertNull(
            decodeNestedHookArgs(
                listOf(CallArg(position = 0, bucket = 1, isStr = false, name = null, present = true)),
                argc = 1,
                readInt = { 0L },
                readStr = { "" },
            ),
            "类型桶不连续 ⇒ 无法按位置投影",
        )
    }

    @Test
    fun `实参投影_无元数据按引擎v1类型桶回退`() {
        val decoded = decodeNestedHookArgs(null, argc = 3, readInt = { it * 10L }, readStr = { "s" })
        assertContentEquals(longArrayOf(0L, 10L, 20L), decoded!!.intArgs, "v1 产物实参全按 int 桶位置读")
        assertEquals(0, decoded.strArgs.size, "v1 产物没有类型元数据，str 桶为空")
    }
}

/**
 * 嵌套调用测试夹具（与 [HookHarness] 同口径，但 MAIN 的函数体会真的 `_CALL` 子函数）：
 * - `MAIN`：置 R0=10 后 `_CALL F, 1, R1`，把返回值带出；
 * - `CHAINMAIN`：同上但调 `CHAIN`（`CHAIN` 内部再 `_CALL G`，用来验两层嵌套）；
 * - `F` / `G`：打印 `orig` 并返回 ARG:0（副作用/短路可观测）；
 * - `REC`：`REC(n) = (n < 1) ? 0 : REC(n-1)+1`，递归深度安全用；
 * - `OTHER`：只有声明、不被调用（验「整链装了但本键无槽」）。
 */
private class NestedHarness {

    val store = VariableStore()

    val logs = ArrayList<String>()

    val program: Program = Assembler.assemble(SOURCE, listOf(argZeroExpr(store)))

    private val metas: List<FuncMeta> = listOf(
        meta(program, "main"),
        meta(program, "chainmain"),
        meta(program, "f", argInt = 1),
        meta(program, "chain", argInt = 1),
        meta(program, "g", argInt = 1),
        meta(program, "rec", argInt = 1),
        meta(program, "other"),
    )

    val runtime: ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())

    /** 剧本打印面（短路 / SkipOriginal 的旁路证据）。 */
    val output: String get() = runtime.output.toString()

    fun pipeline(vararg declarations: DeclaredHook): HookPipeline = HookPipeline.of(declarations.toList())

    fun attach(pipeline: HookPipeline) = FunctionHookWiring.install(store, pipeline, logs::add)

    fun detach(pipeline: HookPipeline) = FunctionHookWiring.uninstall(store, pipeline)

    /** 宿主按名调用 MAIN（顶层），MAIN 内部再 `_CALL F`（嵌套）。 */
    fun callMain(): Long = runtime.callFunction("MAIN")

    /** 顶层宿主按名调用 F（与 [callMain] 的嵌套通道做对照）。 */
    fun callF(arg: Long = 10L): Long = runtime.callFunction("F", intArgs = longArrayOf(arg))

    fun callChainMain(): Long = runtime.callFunction("CHAINMAIN")

    fun callRec(n: Long): Long = runtime.callFunction("REC", intArgs = longArrayOf(n))

    private companion object {

        val SOURCE: String = """
            .func main slots=4
              _SET_INT R0, 10
              _CALL f, 1, R1
              _RET R1

            .func chainmain slots=4
              _SET_INT R0, 10
              _CALL chain, 1, R1
              _RET R1

            .func f arity=1 slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "orig", 1
              _RET R1

            .func chain arity=1 slots=4
              _EVAL_INT ${'$'}0, R0
              _CALL g, 1, R1
              _RET R1

            .func g arity=1 slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "orig", 1
              _RET R1

            .func rec arity=1 slots=5
              _EVAL_INT ${'$'}0, R1
              _SET_INT R2, 1
              _SET_INT R3, 0
              _LT R4, R1, R2
              _JUMP_IF_TRUE R4, done
              _SUB R0, R1, R2
              _CALL rec, 1, R3
              _ADD R0, R3, R2
              _RET R0
            done:
              _RET R3

            .func other slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "other", 1
              _RET R1
        """.trimIndent()

        fun argZeroExpr(store: VariableStore): Expr =
            parseOrThrow(listOf(id("ARG"), colon(), i(0)), NestedStoreResolver(store))

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
private class NestedStoreResolver(private val store: VariableStore) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name, null)

    override fun resolveFunction(name: String): OperandType? = null
}
