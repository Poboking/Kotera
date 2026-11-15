package com.eratw.port.perf

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.SpSetArgument
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * `SET` 形态普查：真产物里多少条赋值能被 `_EVAL_SET_*`（无下标 / 1 维下标 0）覆盖，
 * 其余是 2 维角色变量、动态下标还是常量下标 >0。用来决定下一条融合指令的形态。
 *
 * 默认跳过；`-Deratw.perf.assignStats=true` + `ERATW_ERBC_DIR` 指到 `erbc.v1` 才跑。
 */
class AssignShapeProbeTest {

    private enum class Shape { NO_INDEX, INDEX_0, CONST, DYNAMIC, MULTI }

    @Test
    fun `SET 目标形态普查`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("eratw.perf.assignStats"),
            "设置 -Deratw.perf.assignStats=true 才跑",
        )
        val dir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }
            ?: error("缺 ERATW_ERBC_DIR")
        val erbc = Path.of(dir, "program.erbc")
        check(Files.exists(erbc)) { "缺产物: $erbc" }
        val program = ProgramLoader.loadMapped(erbc)
        val shapes = IntArray(Shape.entries.size)
        val names = HashMap<String, Int>()
        var assigns = 0
        var addConst = 0
        var isConst = 0
        var termNull = 0
        for (i in 0 until program.statementCount) {
            val s = program.statement(i)
            if (s !is AssignStmt) continue
            assigns++
            val d = s.dest
            val shape = when {
                d.indices.isEmpty() -> Shape.NO_INDEX
                d.indices.size == 1 && (d.indices[0] as? IntLiteral)?.value == 0L -> Shape.INDEX_0
                d.indices.size == 1 && d.indices[0] is IntLiteral -> Shape.CONST
                d.indices.size == 1 -> Shape.DYNAMIC
                else -> Shape.MULTI
            }
            shapes[shape.ordinal]++
            names.merge(d.name.uppercase(), 1, Int::plus)
            val a = s.args
            if (a is SpSetArgument) {
                if (a.addConst) addConst++
                if (a.isConst) isConst++
                if (a.term == null) termNull++
            }
        }
        println("ASSIGN_STATS total=$assigns statements=${program.statementCount}")
        for (sh in Shape.entries) {
            println("ASSIGN_STATS ${sh.name}=${shapes[sh.ordinal]} (${"%.1f".format(100.0 * shapes[sh.ordinal] / assigns)}%)")
        }
        println("ASSIGN_STATS addConst=$addConst isConst=$isConst termNull=$termNull")
        println("ASSIGN_STATS top names:")
        names.entries.sortedByDescending { it.value }.take(20).forEach { (n, c) ->
            println("ASSIGN_STATS   $n=$c")
        }
    }
}
