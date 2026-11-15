package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 可达性报告的静态面（MODERN-R6 §3.1 / §3.2）。
 *
 * 判据（权威 = `IdentifierDictionary.cs:72-89` 事件名 / `:91-135` 系统名）：
 * 根 = 事件名 ∪ 系统名；调用边按语句位关键字走 BFS，深度 = 最短调用层数。
 * FORMS 模板里嵌入项**全是**编译期常量时按常量值代入、能完整求值成标识符的升级成静态边
 * （R6-C：`#DIM CONST` 字面量；R6-D：`#DEFINE` 宏体收敛成单个字面量 / 单个常量名链，
 * 只落嵌入位 —— 引擎的宏展开在词法 token 流，FORM 文本段与 CALL 目标不查宏表，
 * 裸宏名目标维持 S1 静态边指向宏名本身，契约 §0.1）；其余静态不可判定的目标
 * （非常量嵌入项 / 列表 / 表达式 / 被遮蔽的宏）落成 `dynamic-call` 行 —— 报告是下界，
 * 且不做任何猜测式匹配。
 *
 * 语料全部 `@TempDir` 现场写，不依赖 `ERATW_CORPUS`。
 */
class ReachabilityAuditTest {

    @TempDir
    lateinit var dir: Path

    private fun write(files: Map<String, String>): Path {
        val erb = dir.resolve("game").resolve("ERB")
        Files.createDirectories(erb)
        for ((name, text) in files) {
            val p = erb.resolve(name)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        return erb
    }

    private fun reachableRows(report: ReachabilityReport): Map<String, String> =
        report.rows
            .filter { it.kind == ReachabilityKind.REACHABLE }
            .associate { it.function to it.detail }

    @Test
    fun `根集合来自事件与系统名 孤儿不可达`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALL SUB_A

                    @SUB_A
                    CALL SUB_B

                    @SUB_B
                    PRINTFORML B

                    @ORPHAN
                    PRINTFORML O
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        assertEquals(1, report.roots, "只有 @SYSTEM_TITLE 是根")
        assertEquals(3, report.reachable, "SYSTEM_TITLE → SUB_A → SUB_B 共 3 个，@ORPHAN 不可达")
        val rows = reachableRows(report)
        assertEquals("depth=0 root=SYSTEM_TITLE", rows["SYSTEM_TITLE"])
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_A"])
        assertEquals("depth=2 root=SYSTEM_TITLE", rows["SUB_B"])
        assertFalse(rows.containsKey("ORPHAN"), "@ORPHAN 没有调用边，不能出现在可达集里")
        assertEquals(
            "reachability: roots=1 reachable=3 dynamic-calls=0 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `事件名同样是根`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @EVENTFIRST
                    CALL EV_ONLY

                    @EV_ONLY
                    PRINTFORML E

                    @SYSTEM_TITLE
                    PRINTFORML T
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        assertEquals(2, report.roots, "EVENTFIRST（IdentifierDictionary.cs:76-84）与 SYSTEM_TITLE 都是根")
        assertEquals(3, report.reachable)
        val rows = reachableRows(report)
        assertEquals("depth=0 root=EVENTFIRST", rows["EVENTFIRST"])
        assertEquals("depth=1 root=EVENTFIRST", rows["EV_ONLY"])
        assertEquals("depth=0 root=SYSTEM_TITLE", rows["SYSTEM_TITLE"])
    }

    @Test
    fun `静态不可判定的调用落成 dynamic-call 行`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%C_OK%
                    TRYCALLLIST TARGETS
                    CALL "SUB_X"

                    @SUB_X
                    PRINTFORML X
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        // 引号字面量目标能静态解析 ⇒ 只有前两条是动态
        assertEquals(2, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        assertEquals(listOf(2, 3), dynamic.map { it.line }, "动态调用行应落在原文行号（1 基）")
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%C_OK%",
                "TRYCALLLIST TRYCALLLIST TARGETS",
            ),
            dynamic.map { it.detail },
        )
        assertTrue(dynamic.all { it.grade == "-" }, "dynamic-call 的 grade 列固定为 -")
        assertEquals("depth=1 root=SYSTEM_TITLE", reachableRows(report)["SUB_X"], "CALL \"SUB_X\" 是静态边")
    }

    @Test
    fun `FORMS 模板的嵌入项全是常量时升级成静态边`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DIM CONST C_OK = 7
                    #DIM CONST C_HEX = 0x0A
                    #DIMS CONST S_TARGET = "SUB_9"
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%C_OK%
                    CALLFORM SUB_{C_OK}
                    CALLFORM SUB_%C_HEX%
                    CALLFORM %S_TARGET%
                    CALLFORM "SUB_%C_OK%"
                    CALLFORM SUB_%C_MISSING%
                    CALL PRIVATE_ROUTER
                    PRINTFORML T

                    @PRIVATE_ROUTER
                    #DIM CONST C_LOCAL = 5
                    CALLFORM SUB_%C_LOCAL%
                    PRINTFORML R

                    @SUB_5
                    PRINTFORML 5

                    @SUB_7
                    PRINTFORML 7

                    @SUB_9
                    PRINTFORML 9

                    @SUB_10
                    PRINTFORML 10
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        // S2（%C_OK%）/ S3（{C_OK}）/ 引号形态都指向 SUB_7；S4（%S_TARGET%）指向 SUB_9；
        // 私有 #DIM CONST C_LOCAL = 5 在自身定义体内可见 ⇒ SUB_5；十六进制常量按值折成 SUB_10。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_7"], "CALLFORM SUB_%C_OK% 应升级成静态边")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_9"], "整串一个字符串常量也应升级（S4）")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_10"], "0x0A 按十进制值代入")
        assertEquals("depth=2 root=SYSTEM_TITLE", rows["SUB_5"], "私有 CONST 只在自身函数体内可见")
        assertEquals(1, report.roots)
        // 只有「常量未声明」那条留在 dynamic-call。
        assertEquals(1, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        assertEquals(
            listOf("CALLFORM CALLFORM SUB_%C_MISSING%"),
            report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }.map { it.detail },
        )
        assertEquals(
            "reachability: roots=1 reachable=6 dynamic-calls=1 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `常量不成立或嵌入项非常量时仍落 dynamic-call`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DIM CONST C_OK = 7
                    #DIM CONST C_ARR, 2 = 3, 4
                    #DIM CONST C_EXPR = 1 + 2
                    #DIM CONST C_SHADOW = 8
                    #DEFINE C_MACRO 1 + 2
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM LOCAL, 2
                    #DIM C_SHADOW
                    CALLFORM SUB_%C_MISSING%
                    CALLFORM SUB_%LOCAL:0%
                    CALLFORM SUB_%C_ARR%
                    CALLFORM SUB_%C_EXPR%
                    CALLFORM SUB_%C_MACRO%
                    CALLFORM SUB_%C_SHADOW%
                    CALLFORM SUB_{LOCAL}
                    CALLFORM SUB_%C_OK%_%LOCAL%
                    CALLFORM "SUB_%C_MISSING%"
                    PRINTFORML T

                    @SUB_
                    PRINTFORML P

                    @SUB_7
                    PRINTFORML 7
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        // MODERN-R13-R6D-②：`#DEFINE C_MACRO 1 + 2` 的宏体落在常量表达式窄折叠面内
        // （引擎 Restructure 同判据可折）⇒ `SUB_%C_MACRO%` 升级成静态目标 SUB_3；
        // @SUB_3 本语料未定义 ⇒ 不产生任何报告行（静态边指向不存在的函数既不可达、
        // 也不是 dynamic-call，报告不虚报）。声明表达式初值 `C_EXPR = 1 + 2` 仍不折
        // （契约 ① 窄口径：值表 null ⇒ 嵌入不可求值）。
        assertEquals(8, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%C_MISSING%",
                "CALLFORM CALLFORM SUB_%LOCAL:0%",
                "CALLFORM CALLFORM SUB_%C_ARR%",
                "CALLFORM CALLFORM SUB_%C_EXPR%",
                "CALLFORM CALLFORM SUB_%C_SHADOW%",
                "CALLFORM CALLFORM SUB_{LOCAL}",
                "CALLFORM CALLFORM SUB_%C_OK%_%LOCAL%",
                "CALLFORM CALLFORM \"SUB_%C_MISSING%\"",
            ),
            dynamic.map { it.detail },
        )
        val rows = reachableRows(report)
        // 没有一条被「猜」成静态：`SUB_` 只是字面量前缀（前缀匹配会把它当目标）、`SUB_7` 的常量
        // 在本函数被同名非常量遮蔽、`C_ARR` 是数组常量、`C_EXPR` 是声明表达式初值（① 窄口径
        // 不折）—— 引擎侧这些形态是运行期求值或装载报错，报告保持下界。
        assertFalse(rows.containsKey("SUB_"), "不许把 SUB_{LOCAL} 的前缀当成静态目标")
        assertFalse(rows.containsKey("SUB_7"), "常量未声明 / 被遮蔽时不能升级")
        assertFalse(rows.containsKey("SUB_3"), "C_MACRO 折出的 SUB_3 未定义 ⇒ 无报告行，不虚报边")
        assertEquals(1, report.reachable)
    }

    @Test
    fun `FORMS 嵌入项是宏且宏体收敛成单个字面量时升级成静态边`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DEFINE M_INT 7
                    #DEFINE M_HEX 0x0A
                    #DEFINE M_STR "SUB_9"
                    #DEFINE M_CHAIN M_INT2
                    #DEFINE M_INT2 5
                    #DIM CONST C_OK = 3
                    #DEFINE M_TO_CONST C_OK
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%M_INT%
                    CALLFORM SUB_{M_HEX}
                    CALLFORM %M_STR%
                    CALLFORM SUB_%M_CHAIN%
                    CALLFORM SUB_%M_TO_CONST%
                    CALLFORM "SUB_%M_INT%"
                    PRINTFORML T

                    @SUB_3
                    PRINTFORML 3

                    @SUB_5
                    PRINTFORML 5

                    @SUB_7
                    PRINTFORML 7

                    @SUB_9
                    PRINTFORML 9

                    @SUB_10
                    PRINTFORML 10
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        // 嵌入位宏展开（契约 §0.1-①）：引擎把宏体重新词法化后折叠成编译期常量
        // （LexicalAnalyzer.kt:724-765 只换 Token.Identifier → 表达式常量），审计同判。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_7"], "宏体单个整数字面量按值代入")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_10"], "宏体 0x 十六进制按值折十进制")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_9"], "宏体单个引号串整串代入（S4 同形）")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_5"], "宏→宏链继续展开到字面量")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_3"], "宏体标识符链落在 #DIM CONST 上")
        assertEquals(0, report.dynamicCalls, "六条调用全部可完整求值，报告行:\n${report.rows.joinToString("\n")}")
        assertEquals(
            "reachability: roots=1 reachable=6 dynamic-calls=0 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `宏体不可求值或宏被声明遮蔽时维持 dynamic-call`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DEFINE M_EXPR 1 + 2
                    #DEFINE M_CONCAT "A" + "B"
                    #DEFINE M_EMPTY
                    #DEFINE M_VAR_ALIAS GLOBAL_VAR
                    #DEFINE M_SELF M_SELF
                    #DEFINE M_LOOP M_A
                    #DEFINE M_A M_LOOP
                    #DEFINE M_SHADOW 5
                    #DIM CONST M_SHADOW = 6
                    #DEFINE M_LOCALSHADOW 8
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM M_LOCALSHADOW
                    CALLFORM SUB_%M_EXPR%
                    CALLFORM SUB_%M_CONCAT%
                    CALLFORM SUB_%M_EMPTY%
                    CALLFORM SUB_%M_VAR_ALIAS%
                    CALLFORM SUB_%M_SELF%
                    CALLFORM SUB_%M_LOOP%
                    CALLFORM SUB_%M_SHADOW%
                    CALLFORM SUB_%M_LOCALSHADOW%
                    PRINTFORML T

                    @SUB_5
                    PRINTFORML 5

                    @SUB_6
                    PRINTFORML 6

                    @SUB_8
                    PRINTFORML 8
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        // 六条不可求值（契约 §0.1-① 保守侧）：空体 / 体是普通变量名 / 自引用与互引用循环
        // （引擎 100 上限装载报错）/ 宏名撞全局 #DIM CONST（合法语料装载期拒载，审计不替
        // 坏语料挑边、也不 fallback 到声明值）/ 宏名撞私有 #DIM。
        // MODERN-R13-R6D-②：M_EXPR（`1 + 2`）与 M_CONCAT（`"A" + "B"`）落在常量表达式
        // 窄折叠面内 ⇒ 升级成静态目标 SUB_3 / SUB_AB（本语料均未定义 ⇒ 无报告行、不虚报），
        // 从本负例清单移出，正例见 `宏体常量表达式按引擎同判据折成静态边`。
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%M_EMPTY%",
                "CALLFORM CALLFORM SUB_%M_VAR_ALIAS%",
                "CALLFORM CALLFORM SUB_%M_SELF%",
                "CALLFORM CALLFORM SUB_%M_LOOP%",
                "CALLFORM CALLFORM SUB_%M_SHADOW%",
                "CALLFORM CALLFORM SUB_%M_LOCALSHADOW%",
            ),
            dynamic.map { it.detail },
        )
        val rows = reachableRows(report)
        assertFalse(rows.containsKey("SUB_3"), "M_EXPR 折出的 SUB_3 未定义 ⇒ 无报告行")
        assertFalse(rows.containsKey("SUB_AB"), "M_CONCAT 折出的 SUB_AB 未定义 ⇒ 无报告行")
        assertFalse(rows.containsKey("SUB_5"), "撞名的宏不许按宏体值升级")
        assertFalse(rows.containsKey("SUB_6"), "撞名的宏也不许 fallback 到声明值（那也是猜）")
        assertFalse(rows.containsKey("SUB_8"), "撞名的宏不许按私有场景取值")
        assertEquals(1, report.reachable)
    }

    @Test
    fun `CONST 初值传递引用更早 CONST 时按声明序折成字面值`() {
        // 语料实形（4.980，MODERN-R13-R6D-① 勘察）：ADD_SEX_MODULES.ERH:2-3（ERH→ERH 同文件链）、
        // MAP_NODE_TO_XML.ERH:26 → MAP_NODE_TO_XML.ERB:16（ERB→ERH，且字典序 ERB 先于 ERH ——
        // 引擎靠「ERH 全量先装」（Process.cs:309-327）成立，审计靠 refVisible 的 isErh 全可见档
        // 镜像）、グラフィック特殊効果.ERH:19 → ERB:64（#DIMS 串链，此用例同时钉「串值不是
        // 合法标识符时维持 dynamic」）。
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM CONST C_DEFAULT_COST = DEFAULT_MOVECOST ;既定のノード間移動コスト
                    CALLFORM SUB_%C_DEFAULT_COST%
                    CALLFORM SUB_%ADD_SEX_MAX_PARTICIPANTS%
                    CALL PRIVATE_ROUTER
                    PRINTFORML T

                    @PRIVATE_ROUTER
                    #DIM CONST P1 = 3
                    #DIM CONST P2 = P1
                    CALLFORM SUB_%P2%
                    CALLFORM %C_SPLITWORD%
                    PRINTFORML R

                    @SUB_1
                    PRINTFORML 1

                    @SUB_3
                    PRINTFORML 3

                    @SUB_5
                    PRINTFORML 5
                """.trimIndent(),
                "DIM.ERH" to """
                    #DIM CONST DEFAULT_MOVECOST = 1
                    #DIMS CONST EFFNAME_SPLIT = ","
                    #DIM CONST ADD_SEX_MODULE_COUNT = 5
                    #DIM CONST ADD_SEX_MAX_PARTICIPANTS = ADD_SEX_MODULE_COUNT
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_1"], "私有 CONST 引 ERH 全局（ERB 文件序在前）按传递值 1 折")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_5"], "ERH→ERH 链（MODULE_COUNT=5 → MAX_PARTICIPANTS）经嵌入位折成静态边")
        assertEquals("depth=2 root=SYSTEM_TITLE", rows["SUB_3"], "同函数私有链 P2→P1=3 折成静态边")
        // `CALLFORM %C_SPLITWORD%`：#DIMS 串链可折（值 ","），但嵌入结果不是合法标识符
        // ⇒ 维持 dynamic（与引擎一致：调用目标 "," 运行期也是找不到函数）。
        assertEquals(1, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        assertEquals(
            listOf("CALLFORM CALLFORM %C_SPLITWORD%"),
            report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }.map { it.detail },
        )
        assertEquals(
            "reachability: roots=1 reachable=5 dynamic-calls=1 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `CONST 传递链断裂或循环时维持 dynamic`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DIM CONST C_EXPR = 1 + 2
                    #DIM CONST C_VIA_EXPR = C_EXPR
                    #DIM CONST C_SELF = C_SELF
                    #DIM CONST C_FWD_SRC = C_LATER
                    #DIM CONST C_LATER = 9
                    #DIM CONST C_NOTCONST_REF = PLAIN_VAR
                    #DIM PLAIN_VAR = 5
                    #DIMS CONST S_SRC = "X"
                    #DIM CONST C_TYPE_MIX = S_SRC
                    #DIM CONST C_OK = 4
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM CONST C_P_FWD = P_LATER
                    #DIM CONST P_LATER = 7
                    #DIM CONST C_P_OK_LATER = C_P_FWD
                    CALLFORM SUB_%C_VIA_EXPR%
                    CALLFORM SUB_%C_SELF%
                    CALLFORM SUB_%C_FWD_SRC%
                    CALLFORM SUB_%C_NOTCONST_REF%
                    CALLFORM SUB_%C_TYPE_MIX%
                    CALLFORM SUB_%C_P_FWD%
                    CALLFORM SUB_%C_P_OK_LATER%
                    CALLFORM SUB_%C_OK%
                    PRINTFORML T

                    @SUB_4
                    PRINTFORML 4
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        // 断链面逐条：C_VIA_EXPR 的目标是**声明表达式初值**（① 契约窄口径：值表 null ⇒ 链断）；
        // C_SELF 自引用（引擎首条即装载报错）；C_FWD_SRC 引同表**更晚**声明的 CONST（引擎在
        // 其解析时刻尚未注册 ⇒ 装载报错；审计按声明序不可见 ⇒ 断）；C_NOTCONST_REF 引非 CONST
        // （`UserDefinedVariableToken.cs:35` CanRestructure = isConst ⇒ 不折）；C_TYPE_MIX 跨型
        // （DIM 引 DIMS ⇒ NotMatchVarTypeAndInitialValue `UserDefinedVariableData.cs:410-413`）；
        // 私有 C_P_FWD 引同函数更晚的私有 P_LATER（尚未注册）⇒ 断，C_P_OK_LATER 顺链同断。
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%C_VIA_EXPR%",
                "CALLFORM CALLFORM SUB_%C_SELF%",
                "CALLFORM CALLFORM SUB_%C_FWD_SRC%",
                "CALLFORM CALLFORM SUB_%C_NOTCONST_REF%",
                "CALLFORM CALLFORM SUB_%C_TYPE_MIX%",
                "CALLFORM CALLFORM SUB_%C_P_FWD%",
                "CALLFORM CALLFORM SUB_%C_P_OK_LATER%",
            ),
            dynamic.map { it.detail },
        )
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_4"], "链上合法字面量 C_OK 不受同图断链影响")
        assertEquals(2, report.reachable)
    }

    @Test
    fun `CONST 传递链超过深度闸 100 时不折 闸内照折`() {
        // 深度闸口径 = R6D 宏展开闸 100（引擎对 CONST 链无递归上限，闸是审计防御面，
        // 超深少报不猜）。A99 = 第 99 跳（最深处深度 99 < 100）⇒ 折；A100 起闸住。
        val erh = (0..120).joinToString("\n") { i ->
            if (i == 0) "#DIM CONST A0 = 1" else "#DIM CONST A$i = A${i - 1}"
        }
        val erb = write(
            mapOf(
                "DIM.ERH" to erh,
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%A99%
                    CALLFORM SUB_%A100%
                    CALLFORM SUB_%A120%
                    PRINTFORML T

                    @SUB_1
                    PRINTFORML 1
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_1"], "闸内链 A99 照折（值 = A0 = 1）")
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%A100%",
                "CALLFORM CALLFORM SUB_%A120%",
            ),
            report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }.map { it.detail },
        )
        assertEquals(2, report.reachable)
    }

    @Test
    fun `宏体常量表达式按引擎同判据折成静态边`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DEFINE M_E 1 + 2
                    #DEFINE M_HEX 0x10 + 1
                    #DEFINE M_PAREN (2 + 3) * 2
                    #DEFINE M_STR "A" + "B"
                    #DEFINE M_REPEAT "AB" * 2
                    #DEFINE M_REPEAT_INT 3 * "CD"
                    #DEFINE M_OPERAND M_BASE * 2
                    #DEFINE M_BASE 4
                    #DEFINE M_NEG -5 + 10
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%M_E%
                    CALLFORM SUB_%M_HEX%
                    CALLFORM SUB_%M_PAREN%
                    CALLFORM SUB_%M_STR%
                    CALLFORM SUB_%M_REPEAT%
                    CALLFORM SUB_%M_REPEAT_INT%
                    CALLFORM SUB_%M_OPERAND%
                    CALLFORM SUB_%M_NEG%
                    PRINTFORML T

                    @SUB_3
                    PRINTFORML 3

                    @SUB_5
                    PRINTFORML 5

                    @SUB_8
                    PRINTFORML 8

                    @SUB_10
                    PRINTFORML 10

                    @SUB_17
                    PRINTFORML 17

                    @SUB_AB
                    PRINTFORML AB

                    @SUB_ABAB
                    PRINTFORML ABAB

                    @SUB_CDCDCD
                    PRINTFORML CDCDCD
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        // 判据链（macroBodyValue KDoc）：CALLFORM 目标装载相位 Restructure（ArgumentParser.cs:725-731）
        // + 算术 OperatorMethod CanRestructure = true（OperatorMethodManager.cs:18/51/65/125/144/32/79）
        // + FunctionMethodTerm.cs:35-58 折 GetValue ⇒ 宏体整串常量表达式按值折进嵌入位。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_3"], "宏体 1 + 2 折 3（契约 ② 的基准形态）")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_17"], "0x10 + 1 按值折 17")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_10"], "括号与乘法优先级：(2 + 3) * 2 = 10")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_AB"], "串拼接 \"A\" + \"B\" 折 AB（PlusStrStr）")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_ABAB"], "串重复 \"AB\" * 2 折 ABAB（MultStrInt）")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_CDCDCD"], "整数*串 3 * \"CD\" 双序同收")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_8"], "宏名操作数递归展开：M_BASE(4) * 2 = 8")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_5"], "一元负号：-5 + 10 = 5")
        assertEquals(0, report.dynamicCalls, "报告行:\n${report.rows.joinToString("\n")}")
        assertEquals(
            "reachability: roots=1 reachable=9 dynamic-calls=0 portability=0 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `宏体表达式窄判据之外维持 dynamic`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DEFINE M_DIV 1 / 0
                    #DEFINE M_MOD 1 % 0
                    #DEFINE M_MIX "A" + 1
                    #DEFINE M_CMP 1 > 2
                    #DEFINE M_LOGIC 1 && 2
                    #DEFINE M_BIT 1 & 2
                    #DEFINE M_CALL VARSIZE("TALENT") + 1
                    #DEFINE M_UNICODE UNICODE(0x2665)
                    #DEFINE M_TRAIL 1 + 2 +
                    #DEFINE M_COLON X:1
                """.trimIndent(),
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM SUB_%M_DIV%
                    CALLFORM SUB_%M_MOD%
                    CALLFORM SUB_%M_MIX%
                    CALLFORM SUB_%M_CMP%
                    CALLFORM SUB_%M_LOGIC%
                    CALLFORM SUB_%M_BIT%
                    CALLFORM SUB_%M_CALL%
                    CALLFORM SUB_%M_UNICODE%
                    CALLFORM SUB_%M_TRAIL%
                    CALLFORM SUB_%M_COLON%
                    TRYCALLLIST TARGETS
                    PRINTFORML T
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val dynamic = report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }
        // 逐条理由（macroBodyValue KDoc 的窄判据不收面）：除零 / 模零是引擎装载报错
        // （OperatorMethodManager.cs:132-135/:151-155）⇒ 无从静态判定；混型算术装载报错
        // （CanNotAppliedBinaryOp :847）；比较 / 逻辑 / 位运算引擎虽可折（:159-691），
        // 窄判据不镜像其分词与语义 ⇒ 少报；方法调用（VARSIZE / UNICODE）引擎可折与否
        // 因方法而异，审计无内建方法表 —— 两条都是 4.980 语料实形；缺右操作数与下标
        // 形态窄判据外；TRYCALLLIST 目标是列表变量，永远 dynamic（R6D 铁律钉子）。
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%M_DIV%",
                "CALLFORM CALLFORM SUB_%M_MOD%",
                "CALLFORM CALLFORM SUB_%M_MIX%",
                "CALLFORM CALLFORM SUB_%M_CMP%",
                "CALLFORM CALLFORM SUB_%M_LOGIC%",
                "CALLFORM CALLFORM SUB_%M_BIT%",
                "CALLFORM CALLFORM SUB_%M_CALL%",
                "CALLFORM CALLFORM SUB_%M_UNICODE%",
                "CALLFORM CALLFORM SUB_%M_TRAIL%",
                "CALLFORM CALLFORM SUB_%M_COLON%",
                "TRYCALLLIST TRYCALLLIST TARGETS",
            ),
            dynamic.map { it.detail },
        )
        assertEquals(1, report.reachable)
        assertEquals(11, report.dynamicCalls)
    }

    @Test
    fun `裸宏名目标维持指向宏名本身 引擎不展开FORM文本段`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to "#DEFINE M_ALIAS SUB_9\n#DEFINE M_ALIAS2 M_ALIAS2_REAL\n",
                "01.ERB" to """
                    @SYSTEM_TITLE
                    CALLFORM M_ALIAS(0)
                    CALL M_ALIAS2
                    PRINTFORML T

                    @M_ALIAS
                    PRINTFORML A

                    @M_ALIAS2
                    PRINTFORML B

                    @M_ALIAS2_REAL
                    PRINTFORML C

                    @SUB_9
                    PRINTFORML 9
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        // 引擎事实（契约 §0.1-②）：FORM 串文本段是原样字符（LexicalAnalyzer.cs:1073-1146，
        // Kotlin analyseFormattedString 同构）、非 FORM 的 CALL 目标走 ReadString 原文
        // （ArgumentParser.cs:730）—— 都不查宏表。`CALLFORM M_ALIAS(0)` 调的是 @M_ALIAS 本身，
        // 不是宏体值；升级到展开值 = 虚报引擎没有的边（违反下界铁律）。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["M_ALIAS"], "裸 FORMS 宏名目标静态边指向宏名本身")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["M_ALIAS2"], "非 FORM 语句的宏名目标同样指向宏名本身")
        assertFalse(rows.containsKey("SUB_9"), "不许把裸宏名目标展开成宏体值")
        assertFalse(rows.containsKey("M_ALIAS2_REAL"), "非 FORM 宏名目标不许按宏体改写")
        assertEquals(0, report.dynamicCalls)
        assertEquals(3, report.reachable)
    }

    @Test
    fun `JUMP 先本函数再本文件`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    JUMP SHARED

                    @HELPER
                    ${'$'}SHARED
                    PRINTFORML H
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        // `$SHARED` 不在 @SYSTEM_TITLE 体内 ⇒ 退到同文件的首个声明者（契约 §3.2.3）。
        assertEquals("depth=1 root=SYSTEM_TITLE", reachableRows(report)["HELPER"])
    }

    @Test
    fun `显式追加根按 depth 0 计入`() {
        val erb = write(
            mapOf(
                "01.ERB" to """
                    @SYSTEM_TITLE
                    PRINTFORML T

                    @ISLAND
                    PRINTFORML I
                """.trimIndent(),
            ),
        )
        val plain = ReachabilityAudit.audit(erb)
        assertEquals(1, plain.roots)
        assertEquals(1, plain.reachable)
        val extra = ReachabilityAudit.audit(erb, extraRoots = listOf("island"))
        assertEquals(2, extra.roots, "名字大小写不敏感，island 应命中 @ISLAND")
        assertEquals(2, extra.reachable)
        assertEquals("depth=0 root=ISLAND", reachableRows(extra)["ISLAND"])
    }

    @Test
    fun `ERB函数外顶层DIM剔除不进全局表 ERH与函数内口径不变`() {
        // MODERN-R13-SUPER：ERB 顶层（首个 @ 行之前）的 #DIM 在引擎是 InvalidSharp 警告 +
        // 整行忽略（ErbLoader.cs:413-424：上一条经 addLine 加入的行不是 FunctionLabelLine；
        // 文件开头 logicalLine2 是 NullLine，ErbLoader.cs:378；文案 Lang.cs:2430，icon=1
        // 非致命、noError 不置 false）⇒ 名字从未注册，审计镜像为不进 globalDecls。
        // 正例保留两条合法注册口：ERH 全局（ErhLoader.cs:249-268 analyzeSharpDimLines 逐条
        // Create + AddUseDefinedVariable）与 ERB 函数上下文内（ParseSharpLine 的
        // Create(isPrivate: true) + label.AddPrivateVariable，LogicalLineParser.cs:20）。
        // 4.980 语料实测顶层 ERB #DIM 0 例（R13-SUPER 勘察）⇒ 本用例的负例面只能由
        // 现场语料钉住；CLI 语料级零漂移由 reachability.expect 逐字节同 sha 背书。
        val erb = write(
            mapOf(
                "DIM.ERH" to """
                    #DIM CONST C_ERH = 7
                    #DIMS CONST S_ERH = "SUB_9"
                    #DEFINE M_TOP 3
                """.trimIndent(),
                "01.ERB" to """
                    #DIM CONST C_TOP = 21
                    #DIMS CONST S_TOP = "SUB_8"
                    #DIM M_TOP
                    @SYSTEM_TITLE
                    CALLFORM SUB_%C_ERH%
                    CALLFORM %S_ERH%
                    CALLFORM SUB_%C_TOP%
                    CALLFORM %S_TOP%
                    CALLFORM SUB_%M_TOP%
                    #DIM CONST C_LOCAL = 5
                    CALLFORM SUB_%C_LOCAL%
                    C_TOP = CLIENTWIDTH()
                    PRINTFORML T

                    @SUB_3
                    PRINTFORML 3

                    @SUB_5
                    PRINTFORML 5

                    @SUB_7
                    PRINTFORML 7

                    @SUB_8
                    PRINTFORML 8

                    @SUB_9
                    PRINTFORML 9
                """.trimIndent(),
            ),
        )
        val report = ReachabilityAudit.audit(erb)
        val rows = reachableRows(report)
        // 正例（口径不变）：ERH 全局声明照常进 globalDecls、照常折成静态边。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_7"], "ERH #DIM CONST 照常折叠")
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_9"], "ERH #DIMS CONST 照常折叠")
        // 正例（口径不变）：ERB 函数上下文内的私有声明照常进 privateDecls。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_5"], "函数内 #DIM CONST 照常折叠（私有表）")
        // 正例（口径不变）：宏遮蔽口径只看真实存在的声明 —— 顶层 ERB #DIM 剔除后，
        // 同名宏不再被「引擎侧不存在的声明」遮蔽，按宏体值折叠。
        assertEquals("depth=1 root=SYSTEM_TITLE", rows["SUB_3"], "同名宏不再被顶层 ERB #DIM 遮蔽")
        // 负例（本波剔除）：顶层 ERB 的 C_TOP / S_TOP 不进 globalDecls ⇒ 嵌入位求不出值，
        // 落 dynamic-call（下界：引擎侧该名运行期同样未注册）。静态边指向的 @SUB_21
        // 未定义 ⇒ 不产生报告行，不虚报。
        assertEquals(
            listOf(
                "CALLFORM CALLFORM SUB_%C_TOP%",
                "CALLFORM CALLFORM %S_TOP%",
            ),
            report.rows.filter { it.kind == ReachabilityKind.DYNAMIC_CALL }.map { it.detail },
        )
        assertFalse(rows.containsKey("SUB_21"), "C_TOP 未注册 ⇒ SUB_21 既不可达也不虚报静态边")
        assertFalse(rows.containsKey("SUB_8"), "S_TOP 未注册 ⇒ SUB_8 不可达（引擎侧同名未注册）")
        // 赋值目标档位钉现状：C_TOP 已从本审计的 globalDecls 剔除，但 classify 的兜底
        // `scan.global`（CorpusScan.scan 自己的顶层收集，compile/CorpusScan.kt，非本波
        // 所权文件）仍登记该名 ⇒ 依旧 PLAIN/display。该残余超集面属 erbcc 编译侧车道，
        // 4.980 语料顶层 ERB #DIM 0 例 ⇒ 对语料读数零影响，见交付回执「需组长接线」。
        val port = report.rows.filter { it.kind == ReachabilityKind.PORTABILITY }
        assertEquals(1, port.size, "报告行:\n${report.rows.joinToString("\n")}")
        assertEquals("display", port.single().grade, "残余面：scan.global 兜底仍解析 C_TOP ⇒ display")
        assertEquals(
            "reachability: roots=1 reachable=5 dynamic-calls=2 portability=1 persist=0",
            report.summaryLine(),
        )
    }

    @Test
    fun `同一语料两次运行报告逐字节相同`() {
        val erb = write(
            mapOf(
                "DIM.ERH" to "#DIM CONST C_ONE = 1\n",
                "01.ERB" to """
                    @SYSTEM_TITLE
                    #DIM LOCAL, 3
                    LOCAL:0 = CLIENTWIDTH()
                    CALL SUB_A
                    CALLFORM SUB_%C_ONE%

                    @SUB_A
                    PRINTFORML A
                """.trimIndent(),
            ),
        )
        val first = ReachabilityAudit.audit(erb)
        val second = ReachabilityAudit.audit(erb)
        assertEquals(first.rows.map { it.toStringRow() }, second.rows.map { it.toStringRow() })
        val a = dir.resolve("a").resolve("reach.tsv")
        val b = dir.resolve("b").resolve("reach.tsv")
        first.writeTsv(a)
        second.writeTsv(b)
        val ta = Files.readString(a)
        assertEquals(ta, Files.readString(b), "两次运行的 TSV 应逐字节相同")
        assertTrue(ta.startsWith("kind\tgrade\tfunction\tfile\tline\tdetail\n"))
        assertFalse(ta.contains(dir.toAbsolutePath().normalize().toString()), "TSV 不应含绝对路径")
    }
}

/** 报告行的可比较形态（只给测试内做逐字节对拍用）。 */
private fun ReachabilityRow.toStringRow(): String =
    listOf(kind.label, grade, function, file, line.toString(), detail).joinToString("\t")
