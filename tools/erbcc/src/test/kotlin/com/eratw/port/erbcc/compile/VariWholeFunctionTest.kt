package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 第三方语料（eraFL）暴露的两处装载期缺陷的回归闸：
 *
 * 1. **`#DIM` 维度计数只认顶层逗号**：原版每段交
 *    `ReduceIntegerTerm(wc, TermEndWith.Comma_Assignment)`（spec:
 *    UserDefinedVariableData.cs:325；em 同形 UserDefinedVariable.cs:217）整条表达式求值，
 *    括号里的 `,` 是函数实参、不切段。语料实形
 *    `#DIM SAVEDATA DAILY_DISABLE, VARSIZE("DAILY_EVENT_NAME_ENG", 1)`
 *    （eraFL ERB/SYSTEM/EVENT_DAILY/VARIABLE_DAILY.ERH:31）被朴素 `split(',')` 数成 2 维，
 *    使用点 `DAILY_DISABLE:i` 于是误报「下标不能省略」。
 * 2. **VARI/VARS 整函数可见**：原版在装载期 `ParseLine` 扫到声明行就
 *    `parentLine.AddPrivateVariable`（spec: LogicalLineParser.cs:463-471 / 500-508），
 *    而实参求值推迟到整文件扫描之后（ErbLoader.cs:514-521 + ArgumentParser.cs:2687-2705）
 *    ⇒ 声明写在引用之后也合法。语料实形 eraFL ERB/EQUIP/EQUIP_MENU.ERB：`F_COL` 先用
 *    （:388 一带）后 `VARI F_COL`（:399/:470）。侧车还必须带上这些声明，否则运行期
 *    `userVar(name)` 取不到（`execVarDeclFlat` 静默 no-op，VariableStore.kt:2484-2511）。
 */
class VariWholeFunctionTest {

    private fun scanOne(dir: Path, text: String): CorpusScan {
        val f = dir.resolve("a.ERB")
        f.writeText(text)
        return CorpusScan.scan(listOf(f))
    }

    @Test
    fun `DIM维度只按顶层逗号计数`(@TempDir dir: Path) {
        val scan = scanOne(
            dir,
            """
            @F
            #DIM A, POWER(2, 2)
            #DIM B, 5, 10
            #DIM REF C, ,
            #DIMS D, 3
            """.trimIndent(),
        )
        val vars = scan.privateVars.values.single()
        assertEquals(1, vars.getValue("A").dimension, "括号内逗号是函数实参、不是维度（cs:325）")
        assertEquals(2, vars.getValue("B").dimension, "顶层两个尺寸段 ⇒ 2 维")
        assertEquals(2, vars.getValue("C").dimension, "REF 每个顶层逗号贡献 0 尺寸（cs:309-319）")
        assertEquals(1, vars.getValue("D").dimension)
        assertEquals(OperandType.STR, vars.getValue("D").operandType, "#DIMS ⇒ STR")
    }

    @Test
    fun `VARI_VARS整函数登记且先到者胜`(@TempDir dir: Path) {
        val scan = scanOne(
            dir,
            """
            @F
            VARI A, 3, 2
            VARS B
            vari C, 2, 3
            VARI A, 9
            	PRINTL 1
            """.trimIndent(),
        )
        val vars = scan.privateVars.values.single()
        assertEquals(2, vars.getValue("A").dimension, "AddPrivateVariable 先到者胜（FunctionLabelLine.cs:98-111）")
        assertEquals(OperandType.INT, vars.getValue("A").operandType)
        assertEquals(OperandType.STR, vars.getValue("B").operandType, "VARS ⇒ STR")
        assertEquals(1, vars.getValue("B").dimension, "无尺寸段默认 [1]（cs:427-433）")
        assertEquals(2, vars.getValue("C").dimension, "指令名大小写不敏感（IGNORE CASE）")
    }

    @Test
    fun `先引用后声明的VARI可编译且进侧车`(@TempDir dir: Path) {
        val root = dir.resolve("corpus")
        java.nio.file.Files.createDirectories(root)
        root.resolve("main.ERB").writeText(
            """
            @MENU
            	F_COL = 1
            	COLUMN:COLUMN_IDX = 2
            	VARI COLUMN, 10
            	VARI COLUMN_IDX
            	VARI F_COL
            	RETURN
            @DIMTEST
            	#DIM DYNAMIC MYX, POWER(2, 2)
            	MYX:0 = 1
            	RETURN
            """.trimIndent(),
        )
        val result = CorpusCompile.compile(root)
        assertTrue(result.ok, "先引用后声明必须可编译: ${result.failures}")

        val byName = result.bootMetadata.privateDims.associateBy { it.spec.name }
        val fCol = byName["F_COL"]
        assertNotNull(fCol, "VARI F_COL 必须进 privateDims: ${byName.keys}")
        assertFalse(fCol.spec.isStr, "VARI ⇒ 整数")
        assertEquals(listOf(1), fCol.spec.dims.toList())
        assertFalse(fCol.spec.isStatic, "VARI/VARS 是动态私有变量（Static=false，cs:463-470）")
        assertTrue(fCol.spec.isPrivate)

        val column = byName["COLUMN"]
        assertNotNull(column, "VARS/VARI 带尺寸声明必须进 privateDims")
        assertEquals(listOf(10), column.spec.dims.toList())

        val x = byName["MYX"]
        assertNotNull(x, "#DIM 私有声明仍在侧车里: 现得=${byName.keys} 告警=${result.dimWarnings.map { it.message }}")
        assertEquals(listOf(4), x.spec.dims.toList(), "POWER(2, 2) 单段求值 ⇒ 1 维、长度 4")
    }
}
