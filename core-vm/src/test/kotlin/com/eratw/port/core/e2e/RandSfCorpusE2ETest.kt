package com.eratw.port.core.e2e

import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RANDSF 语料函数端到端验收（2026-11-15 玩家报红排查的收尾证明）。
 *
 * 背景与判定：261111 语料大量使用 `RANDSF(...)`（日常指令、TRACHECK 好感/信赖结算、
 * 魔法 DLC 等 20+ 文件）。取证结论：它是**语料自带的 ERB 用户函数**（`#FUNCTION`，
 * 定义在 `ERB/魔改内容/Misc.ERB:320-343`，官方注释齐全：「带符号的随机偏移值生成」），
 * **不是宿主内建** —— 三个反编译树（emuera-cs 1.824 / skia-4.981 / Emuera1824+v24）
 * 与本仓内建注册表均无此名，且 4.980 语料时代它就存在。因此引擎侧**无需**注册内建，
 * 只需用户函数表达式位重入通道正常工作。
 *
 * 本测试用 [ErbSnippetHarness]（真 LineParser + 真 ErbProgramCompiler + 真
 * ProcessRuntime）把 RANDSF 原文片段真跑一遍，按官方注释钉语义：
 * - ARG > 0：每次 `RAND:(1+ARG)`（0..ARG）累加 ARG:1 次 ⇒ 结果 ∈ [0, ARG×次数]；
 * - ARG < 0：每次以 1/(1-ARG) 概率 +1，执行 ARG:1 次 ⇒ 结果 ∈ [0, 次数]；
 * - ARG:1 < 0：绝对值为次数、结果取负；
 * - ARG = 0 或次数 = 0：返回 0（两个分支都不进 / 循环不执行）。
 *
 * 取证口径：4.980 语料同函数已存在（`RandZeroArgChainAuditTest` 的排除说明「RANDSF /
 * RAND_ADVANTAGE 不算」即指此函数）；261111 换代波 strict 档 4307 文件编译失败 0，
 * 含定义文件 Misc.ERB。fixture 镜像段逐字来源 = Misc.ERB 320-343（1 基），
 * 第 2 段语料原文锚点（吃 `ERATW_CORPUS`）逐字核对后再真跑，证明「抄的是真话」。
 *
 * KDoc 纪律：正文不写通配星号对，避免 Kotlin 块注释嵌套吞掉闭合符。
 */
class RandSfCorpusE2ETest {

    private fun runDriver(lines: List<String>): VariableStore =
        ErbSnippetHarness.run(
            lines = lines,
            entry = "RANDSF_DRIVER",
            store = VariableStore().apply { randProvider = Sfmt19937(1L) },
        )

    private fun assertWitness(store: VariableStore) {
        // 官方注释逐条钉区间（见类 KDoc）：
        val r1 = store.readInt("R1", longArrayOf(0))
        assertTrue(r1 in 0..200, "RANDSF(40,5)：5 次 0..40 累加 ⇒ 0..200，实际 $r1")
        val r2 = store.readInt("R2", longArrayOf(0))
        assertTrue(r2 in 0..100, "RANDSF(-10,100)：100 次 1/11 概率 +1 ⇒ 0..100，实际 $r2")
        val r3 = store.readInt("R3", longArrayOf(0))
        assertTrue(r3 in -15..0, "RANDSF(5,-3)：次数取绝对值、符号取负 ⇒ -15..0，实际 $r3")
        assertEquals(0L, store.readInt("R4", longArrayOf(0)), "RANDSF(40,0)：次数 0 ⇒ 循环不执行，返回 0")
        assertEquals(0L, store.readInt("R5", longArrayOf(0)), "RANDSF(0,5)：ARG=0 ⇒ 两个分支都不进，返回 0")
        val r6 = store.readInt("R6", longArrayOf(0))
        assertTrue(r6 in 0..3, "RANDSF(1,3)：3 次 0..1 累加 ⇒ 0..3，实际 $r6")
    }

    /** 镜像片段实跑：同种子两次独立运行读数逐一相等（RANDSF 是纯函数 + 确定性 RNG）。 */
    @Test
    fun `RANDSF 镜像片段实跑 区间与确定性`() {
        val lines = ErbSnippetHarness.lines("rand_sf_corpus.erb")
        val first = runDriver(lines)
        assertWitness(first)

        val second = runDriver(lines)
        for (k in 1..6) {
            val key = "R$k"
            assertEquals(first.readInt(key, longArrayOf(0)), second.readInt(key, longArrayOf(0)), "同种子两次跑 $key 必须一致")
        }
    }

    /** 语料原文锚点：先逐字核对 Misc.ERB 的关键行，再把现读原文行拼进装置真跑同一驱动。 */
    @Test
    fun `语料原文 RANDSF 实跑 与镜像同语义`() {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(!corpus.isNullOrBlank(), "缺 ERATW_CORPUS：跳过")
        val file = File(corpus, "魔改内容/Misc.ERB")
        Assumptions.assumeTrue(file.isFile, "语料根缺 魔改内容/Misc.ERB：跳过")

        val corpusLines = file.readLines(Charsets.UTF_8)
        // 1 基行号核对（0 基下标 = 行号-1）：函数头 / #FUNCTION / 核心累加与概率行 / 收口
        assertEquals("@RANDSF(ARG,ARG:1)", corpusLines[319].trim(), "1 基 320 = 函数头（原文锚点）")
        assertEquals("#FUNCTION", corpusLines[323].trim(), "1 基 324 = #FUNCTION（表达式位调用的前提）")
        assertEquals("LOCAL += RAND:(1+ARG)", corpusLines[333].trim(), "1 基 334 = 正幅度累加行")
        assertEquals("SIF !RAND:(1-ARG)", corpusLines[338].trim(), "1 基 339 = 负幅度概率行")
        assertEquals("RETURNF LOCAL * RESULT_SIGN", corpusLines[342].trim(), "1 基 343 = 收口行")

        // 现读原文行（1 基 320-343 = 0 基 319..343）替换镜像片段的同段，其余原样
        val mirror = ErbSnippetHarness.lines("rand_sf_corpus.erb")
        val iAt = mirror.indexOf("@RANDSF(ARG,ARG:1)")
        val iDrv = mirror.indexOf("@RANDSF_DRIVER")
        check(iAt >= 0 && iDrv > iAt) { "镜像片段结构异常：@RANDSF=$iAt @RANDSF_DRIVER=$iDrv" }
        val spliced = mirror.subList(0, iAt) + corpusLines.subList(319, 343) + mirror.subList(iDrv, mirror.size)

        assertWitness(runDriver(spliced))
    }
}
