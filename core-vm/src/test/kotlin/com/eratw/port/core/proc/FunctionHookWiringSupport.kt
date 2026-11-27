package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
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
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.vars.VariableStore

/**
 * [FunctionHookWiringTest] 两半（宿主调用 / 事件派发）共用的真运行时夹具。
 *
 * 字节码手工汇编（与 [ProcessRuntimeTest] 同口径：compile agent 的产物形态等价）：
 * - `F(ARG:0)` 打印 `orig` 并返回 ARG:0 —— 返回值可观测、副作用可观测（前缀 Replace
 *   短路时 `orig` 不出现）；
 * - `EVT` 打印 `ev` —— 事件成员链是否真的跑了；
 * - `F_OVERRIDE` 与 `F` 共用逻辑键 `F`，仅在 [overrideWinner] 为真时进 LSC，打印
 *   `override` 并返回 `ARG:0 + 100` —— 用来证明 hook 包在最终 Override 胜者外层；
 * - `MAIN` 只 `_HALT`，仅占 `Program.functions[0]`。
 *
 * 夹具**不预装接线**：每个用例自己决定要不要 [attach]，这样「未装 = 完全旁路」可以被
 * 直接断言（golden 零变化的前提）。
 */
internal class HookHarness(overrideWinner: Boolean = false) {

    val store = VariableStore()

    /** 接线日志面（[FunctionHookWiring] 的 logSink），用来断言隔离 / 丢弃告警。 */
    val logs = ArrayList<String>()

    private val program: Program = Assembler.assemble(SOURCE, listOf(argZeroExpr(store)))

    private val baseMeta = meta(program, "f", argInt = 1)
    private val overrideMeta = meta(program, "f_override", logicalKey = FUNCTION_KEY, argInt = 1)
    private val metas: List<FuncMeta> = if (overrideWinner) {
        listOf(
            meta(program, "main"),
            baseMeta,
            overrideMeta,
            meta(program, "evt", isEvent = true),
        )
    } else {
        listOf(
            meta(program, "main"),
            baseMeta,
            meta(program, "evt", isEvent = true),
        )
    }

    private val catalog: SymbolCatalog? = if (!overrideWinner) null else SymbolCatalog.build(
        listOf(
            SymbolCandidate(metas[0], ownerPackageId = "base"),
            SymbolCandidate(baseMeta, ownerPackageId = "base", signatureHash = "F(int)->int"),
            SymbolCandidate(
                overrideMeta,
                declaredOverride = true,
                ownerPackageId = "mod.override",
                signatureHash = "F(int)->int",
            ),
            SymbolCandidate(metas[3], ownerPackageId = "base"),
        ),
        ResolverPolicy.MODERN,
    )

    val runtime: ProcessRuntime = ProcessRuntime(
        program,
        store,
        FunctionTable(metas),
        VmConfig(),
        symbolCatalog = catalog,
    )

    /** 剧本打印面（前缀短路 / SkipOriginal 的旁路证据）。 */
    val output: String get() = runtime.output.toString()

    fun pipeline(vararg declarations: DeclaredHook): HookPipeline = HookPipeline.of(declarations.toList())

    fun attach(pipeline: HookPipeline) = FunctionHookWiring.install(store, pipeline, logs::add)

    fun detach(pipeline: HookPipeline) = FunctionHookWiring.uninstall(store, pipeline)

    fun callF(arg: Long = ARG_VALUE): Long = runtime.callFunction(FUNCTION_KEY, intArgs = longArrayOf(arg))

    fun runEvent(): Boolean = runtime.executeEvent(EVENT_KEY)

    companion object {
        const val FUNCTION_KEY: String = "F"
        const val EVENT_KEY: String = "EVT"
        const val ARG_VALUE: Long = 10L

        private val SOURCE: String = """
            .func main slots=1
              _HALT

            .func f arity=1 slots=2
              _EVAL_INT ${'$'}0, R1
              _PRINT_LITERAL "orig", 1
              _RET R1

            .func f_override arity=1 slots=3
              _EVAL_INT ${'$'}0, R1
              _SET_INT R2, 100
              _ADD R1, R1, R2
              _PRINT_LITERAL "override", 1
              _RET R1

            .func evt slots=2
              _PRINT_LITERAL "ev", 1
              _RET
        """.trimIndent()

        private fun argZeroExpr(store: VariableStore): Expr =
            parseOrThrow(listOf(id("ARG"), colon(), i(0)), StoreResolver(store))

        /** asm 函数名小写、meta 键按 ERB 习惯大写：查找用 IGNORE CASE。 */
        private fun meta(
            program: Program,
            name: String,
            isEvent: Boolean = false,
            argInt: Int = 0,
            logicalKey: String? = null,
        ): FuncMeta {
            val index = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            require(index >= 0) { "函数 $name 不在程序中" }
            return FuncMeta(
                index = index,
                key = (logicalKey ?: name).uppercase(),
                name = name,
                isEvent = isEvent,
                argIntCount = argInt,
                defIndex = index,
            )
        }
    }
}

/** 夹具里的表达式只读 `ARG` 一维，不做函数名解析。 */
private class StoreResolver(private val store: VariableStore) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name, null)

    override fun resolveFunction(name: String): OperandType? = null
}

/** 声明侧便捷构造：[HookDeclaration] 的 6 个具名参数在用例里重复太吵。 */
internal object HookDecls {

    fun prefix(
        packageId: String,
        targetKey: String = HookHarness.FUNCTION_KEY,
        priority: Int = 0,
        mayMutateArgs: Boolean = false,
        runOnFailure: Boolean = false,
    ): DeclaredHook = declared(
        packageId = packageId,
        declaration = HookDeclaration(
            targetKey = targetKey,
            phase = HookPhase.PREFIX,
            priority = priority,
            mayMutateArgs = mayMutateArgs,
            mayMutateReturn = false,
            runOnFailure = runOnFailure,
        ),
    )

    fun postfix(
        packageId: String,
        targetKey: String = HookHarness.FUNCTION_KEY,
        priority: Int = 0,
        mayMutateReturn: Boolean = false,
        runOnFailure: Boolean = false,
    ): DeclaredHook = declared(
        packageId = packageId,
        declaration = HookDeclaration(
            targetKey = targetKey,
            phase = HookPhase.POSTFIX,
            priority = priority,
            mayMutateArgs = false,
            mayMutateReturn = mayMutateReturn,
            runOnFailure = runOnFailure,
        ),
    )

    fun event(
        packageId: String,
        phase: HookPhase = HookPhase.PREFIX,
        priority: Int = 0,
        mayMutateReturn: Boolean = false,
    ): DeclaredHook = declared(
        packageId = packageId,
        declaration = HookDeclaration(
            targetKey = HookHarness.EVENT_KEY,
            phase = phase,
            priority = priority,
            mayMutateArgs = false,
            mayMutateReturn = mayMutateReturn,
            runOnFailure = false,
        ),
    )

    private fun declared(packageId: String, declaration: HookDeclaration): DeclaredHook =
        DeclaredHook(packageId = packageId, declaration = declaration)
}
