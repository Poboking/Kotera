package com.eratw.port.core.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ErbPatchPolicy]（引擎侧 ERB 补丁整体禁载能力）的形状闸。
 *
 * 这批用例刻意**不用默认清单**也能成立：能力是「给定任意 [ErbPatchPolicy.Entry] 清单，
 * 按相对 ERB 根的路径规则判定脚本是否整体不装载」，不是「认得 AI对话补丁」。
 * 默认清单的内容另由最后一组用例钉住（语料证据在 [DisabledErbPatchCatalog] KDoc）。
 *
 * 编译期真的不装载脚本的行为判据在 `:tools:erbcc` 的 `ErbPatchExclusionTest`（两次真编译
 * 对拍）；这里只钉判定面，两侧共用同一份 [ErbPatchPolicy.isExcluded]。
 */
class ErbPatchPolicyTest {

    private val custom = listOf(
        ErbPatchPolicy.Entry("patch-a", listOf("DLC/PATCH_A/")),
        ErbPatchPolicy.Entry("patch-b", listOf("DLC/PATCH_B/单文件.ERB"), listOf("SomeNative")),
    )

    @Test
    fun `空清单零行为 且自定义清单无需改引擎代码`() {
        val rel = "DLC/PATCH_A/核心.ERB"
        assertFalse(ErbPatchPolicy.isExcluded(rel, emptyList()), "空的禁载清单必须完全关闭能力")
        assertNull(ErbPatchPolicy.ownerOf(rel, emptyList()))
        assertTrue(ErbPatchPolicy.isExcluded(rel, custom), "换成完全不相干的补丁目录，同一套判定照样生效")
        assertSame(custom[0], ErbPatchPolicy.ownerOf(rel, custom))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/PATCH_B/单文件.ERB", custom))
        assertFalse(ErbPatchPolicy.isExcluded("DLC/PATCH_B/别的.ERB", custom), "精确文件规则不得扩到同目录其它文件")
        assertFalse(ErbPatchPolicy.isExcluded("DLC/PATCH_C/核心.ERB", custom), "未登记的目录不受影响")
    }

    @Test
    fun `目录边界 前缀相似目录不得误伤`() {
        val entries = listOf(ErbPatchPolicy.Entry("x", listOf("DLC/X/")))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/X/a.ERB", entries))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/X/sub/b.ERB", entries), "目录前缀应覆盖子孙目录")
        assertFalse(ErbPatchPolicy.isExcluded("DLC/XY/a.ERB", entries))
        assertFalse(ErbPatchPolicy.isExcluded("DLC/X2/a.ERB", entries))
        assertFalse(ErbPatchPolicy.isExcluded("OTHER/DLC/X/a.ERB", entries))
    }

    @Test
    fun `只对 ERB 脚本生效 ERH 变量头一律保留`() {
        val entries = listOf(ErbPatchPolicy.Entry("x", listOf("DLC/X/")))
        assertTrue(ErbPatchPolicy.isErbScript("DLC/X/a.ERB"))
        assertTrue(ErbPatchPolicy.isErbScript("DLC/X/a.erb"))
        assertFalse(ErbPatchPolicy.isErbScript("DLC/X/a.ERH"))
        assertFalse(ErbPatchPolicy.isExcluded("DLC/X/变量.ERH", entries), "ERH 是全局符号面，删了主语料编译判红")
        assertFalse(ErbPatchPolicy.isExcluded("DLC/X/变量.erh", entries))
        assertFalse(ErbPatchPolicy.isExcluded("DLC/X/readme.txt", entries))
        assertEquals(
            false,
            ErbPatchPolicy.isExcluded("DLC/AI对话补丁/AI返回参数.ERH"),
            "默认清单里的 AI 补丁 ERH 同样必须保留",
        )
    }

    @Test
    fun `路径归一 反斜杠 前导点 大小写不敏感`() {
        val entries = listOf(ErbPatchPolicy.Entry("x", listOf("DLC/X/")))
        assertEquals("DLC/X/a.ERB", ErbPatchPolicy.normalize("./DLC\\X\\a.ERB"))
        assertTrue(ErbPatchPolicy.isExcluded("DLC\\X\\a.ERB", entries))
        assertTrue(ErbPatchPolicy.isExcluded("./DLC/X/a.ERB", entries))
        assertTrue(ErbPatchPolicy.isExcluded("/DLC/X/a.ERB", entries))
        assertTrue(ErbPatchPolicy.isExcluded("dlc/x/A.ErB", entries), "目录与扩展名大小写不敏感")
    }

    @Test
    fun `默认配置数据 与 4_981 语料落点对齐`() {
        assertEquals(listOf("ai-chat"), DisabledErbPatchCatalog.all.map { it.id })
        assertEquals(listOf("DLC/AI对话补丁/"), DisabledErbPatchCatalog.AI_CHAT.erbPathPrefixes)
        assertEquals(listOf("ModelSet", "Chat"), DisabledErbPatchCatalog.AI_CHAT.nativeMethodNames)
        assertEquals(
            listOf("ModelSet", "Chat"),
            ErbPatchPolicy.absentNativeMethodNames(),
            "运行期缺席位名单与编译期禁载目录同源",
        )
        assertEquals(emptyList(), ErbPatchPolicy.absentNativeMethodNames(emptyList()))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/AI对话补丁/对话调用.erb"))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/AI对话补丁/参数返回.ERB"))
        assertTrue(ErbPatchPolicy.isExcluded("DLC/AI对话补丁/人设.erb"))
        assertFalse(ErbPatchPolicy.isExcluded("DLC/AI对话补丁/AI返回参数.ERH"))
        assertFalse(ErbPatchPolicy.isExcluded("SHOP関連/OPTION_COM_FILTER.ERB"))
    }

    @Test
    fun `条目构造校验 非法路径与空规则一律判红`() {
        assertFailsWith<IllegalArgumentException> { ErbPatchPolicy.Entry(" ", listOf("DLC/X/")) }
        assertFailsWith<IllegalArgumentException> { ErbPatchPolicy.Entry("x", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErbPatchPolicy.Entry("x", listOf("/DLC/X/")) }
        assertFailsWith<IllegalArgumentException> { ErbPatchPolicy.Entry("x", listOf("DLC\\X\\")) }
        assertFailsWith<IllegalArgumentException> { ErbPatchPolicy.Entry("x", listOf("DLC/../X/")) }
        // nativeMethodNames 允许为空：不涉及本机方法的补丁也能整体禁载。
        assertEquals(emptyList(), ErbPatchPolicy.Entry("x", listOf("DLC/X/")).nativeMethodNames)
    }
}
