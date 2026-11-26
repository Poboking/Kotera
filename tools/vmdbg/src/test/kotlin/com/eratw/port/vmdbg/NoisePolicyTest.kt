package com.eratw.port.vmdbg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NoisePolicy 的模式判定（CONTRACT-DBG-TOOLING §4）。
 *
 * 样例 = core-vm 文案模板的**渲染后形态**（变量名/数字按真实报错填充；模板出处逐条在
 * NoisePolicy.PATTERNS 注释里）。模式的对手方是 sweep 记录的原始 msg——这里同时断言
 * 「数字不同仍是同族」与「文案形不同（其他 CodeEE）不算噪声」。
 */
class NoisePolicyTest {

    @Test
    fun `数组下标越界族命中（OoRVarArg 渲染形）`() {
        val msg = "数组变量\"ITEMNAME\"的第1参数(1000)超出数组范围"
        assertTrue(NoisePolicy.isKnownNoise("CodeEE", msg), msg)
        // 数字/变量名换样仍命中（同模板不同实例）
        assertTrue(NoisePolicy.isKnownNoise("CodeEE", "数组变量\"EXPLV\"的第2参数(5002)超出数组范围"))
    }

    @Test
    fun `角色变量越界族命中（OoRCharaVarArg 渲染形）`() {
        // 「第{1}个变量」是官方误译原文，照抄纪律不许改写（VarErrorText.kt:34）
        val msg = "角色数组变量\"CFLAG\"的第3个变量(0)超出角色登记编号的最大范围"
        assertTrue(NoisePolicy.isKnownNoise("CodeEE", msg), msg)
    }

    @Test
    fun `EmptyRefVar 族命中`() {
        val msg = "引用类型变量\"ITEM\"缺少引用对象"
        assertTrue(NoisePolicy.isKnownNoise("CodeEE", msg), msg)
    }

    @Test
    fun `FUNCTION 拒调族命中（官方拼写错误 FUCNTION 原样）`() {
        val msg = "对标记为#FUCNTION(S)的函数\"@MINI_METHOD\"(ERB/mini.erb:第6行)进行CALL调用"
        assertTrue(NoisePolicy.isKnownNoise("CodeEE", msg), msg)
    }

    @Test
    fun `其他 CodeEE 文案不算噪声`() {
        // 未定义函数（MiniCorpus.MINI_CODEEE 的族）是真线索候选，不许进噪声层
        assertFalse(NoisePolicy.isKnownNoise("CodeEE", "函数名\"MINI_NOPE_XYZ\"未定义"))
        assertFalse(NoisePolicy.isKnownNoise("CodeEE", ""))
        assertFalse(NoisePolicy.isKnownNoise("CodeEE", "引用类型变量缺少引用对象")) // 缺变量名段，模板不合
    }

    @Test
    fun `非 CodeEE 异常类不算噪声`() {
        // STEP_LIMIT/WALL_LIMIT 等有界重负载是真线索重点桶，cls 闸拦住它们
        assertFalse(NoisePolicy.isKnownNoise("StepLimitExceeded", "引用类型变量\"X\"缺少引用对象"))
        assertFalse(NoisePolicy.isKnownNoise("-", ""))
    }

    @Test
    fun `partitionClusters 按簇首条分层且保持稳定序`() {
        val noiseRec = SweptRecord(
            fn = "MINI_METHOD", key = "MINI_METHOD", status = "CODEEE", cls = "CodeEE",
            line = 6, pc = 12,
            msg = "对标记为#FUCNTION(S)的函数\"@MINI_METHOD\"(ERB/mini.erb:第6行)进行CALL调用",
            steps = 5, elapsedMs = 1,
        )
        val freshRec = SweptRecord(
            fn = "MINI_CODEEE", key = "MINI_CODEEE", status = "CODEEE", cls = "CodeEE",
            line = 1, pc = 3,
            msg = "函数名\"MINI_NOPE_XYZ\"未定义",
            steps = 7, elapsedMs = 1,
        )
        val (noise, fresh) = CmdSweep.partitionClusters(listOf(noiseRec, freshRec))
        assertEquals(1, noise.size, "噪声层只收 NoisePolicy 命中的簇")
        assertEquals(1, fresh.size, "fresh 层收其余簇")
        assertEquals("MINI_METHOD", noise.single().second.single().fn)
        assertEquals("MINI_CODEEE", fresh.single().second.single().fn)
    }
}
