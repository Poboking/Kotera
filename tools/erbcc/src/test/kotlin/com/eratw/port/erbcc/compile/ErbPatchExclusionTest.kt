package com.eratw.port.erbcc.compile

import com.eratw.port.core.policy.DisabledErbPatchCatalog
import com.eratw.port.core.policy.ErbPatchPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ERB 补丁**整体禁载**的编译期行为闸（PLUGIN-POLICY 第二轮，2026-11-07）。
 *
 * 判据不是 grep 源码，而是同一份合成小语料**真编译两遍**对拍：
 * - 默认档吃 [DisabledErbPatchCatalog]：命中目录的 `.ERB` 一个函数体都不进产物；
 * - 空清单档（`patchPolicy = emptyList()`）完全关闭能力，同一批脚本正常编译。
 *
 * 三条例外必须同时成立，否则「整体禁载」会把游戏编坏：
 * 1. `.ERH` 变量头照常装载 —— 未被禁载的 `OTHER/正常.ERB` 引用 `PATCH_SWITCH`，
 *    若 ERH 被一起排除，两档都会出现解析失败（这条判据有判定力，不是摆设）；
 * 2. 被禁脚本的 `@` 标签仍在函数表里（编成零语句空体），目录外的静态
 *    `CALL AI_MESSAGE` 才不会撞「未定义函数」；
 * 3. 源文件清单仍登记被排除的脚本（freshness 对账口径不变），可见性由 manifest 留痕承担。
 *
 * 另钉「换一张清单就换一批补丁」：传自定义策略时默认清单的文件反而正常编译 ——
 * 证明这是引擎侧通用能力，不是给 AI对话补丁写死的特判。
 */
class ErbPatchExclusionTest {

    private val patchScriptA = "DLC/AI对话补丁/对话调用.erb"
    private val patchScriptB = "DLC/AI对话补丁/参数返回.ERB"
    private val patchHeader = "DLC/AI对话补丁/AI返回参数.ERH"
    private val outsideScript = "OTHER/正常.ERB"

    /** 合成语料：两个被禁脚本 + 一个变量头 + 一个目录外脚本（引用变量头与空体标签）。
     *
     * 形状刻意复刻 4.981 真实面：`@AI_MESSAGE` 是**带三个命名形参**的函数，目录外
     * `CALL AI_MESSAGE(…)` 传三个实参且第三个是字符串；补丁与目录外各挂一个
     * `@EVENTCOMEND` 成员（同名事件组多定义）。这三条正是「整体禁载」最容易编坏
     * 游戏的地方：形参 arity 变窄会触发运行期 `参数过多`、事件组按函数名一刀切会
     * 误伤目录外成员。 */
    private fun writeCorpus(root: Path) {
        fun put(rel: String, text: String) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.write(p, text.trimIndent().toByteArray(Charsets.UTF_8))
        }
        put(
            patchHeader,
            """
            #DIM PATCH_SWITCH
            """,
        )
        put(
            patchScriptA,
            """
            @CHAT_ACTION
                PRINTL AI_PATCH_BODY_MARKER_A
                RETURN

            @EVENTCOMEND
                PRINTL AI_PATCH_EVENT_MARKER
                RETURN
            """,
        )
        put(
            patchScriptB,
            """
            @AI_MESSAGE(KOJO_NAME, COM_ID, COM_TYPE)
            #DIMS DYNAMIC KOJO_NAME
            #DIM DYNAMIC COM_ID
            #DIMS DYNAMIC COM_TYPE
                PRINTL AI_PATCH_BODY_MARKER_B
                RETURN
            """,
        )
        put(
            outsideScript,
            """
            @NORMAL
            #LOCALSSIZE 1
                LOCALS = "X"
                PATCH_SWITCH = 1
                CALL AI_MESSAGE(1, 2, LOCALS)
                PRINTL NORMAL_BODY_MARKER
                RETURN

            @EVENTCOMEND
                PRINTL OUTSIDE_EVENT_MARKER
                RETURN
            """,
        )
    }

    @Test
    fun `默认清单整体禁载 两档真编译对拍`(@TempDir root: Path) {
        writeCorpus(root)
        val excluded = CorpusCompile.compile(root)
        val plain = CorpusCompile.compile(root, patchPolicy = emptyList())

        val ep = assertNotNull(excluded.program, "禁载档必须编得出 Program")
        val pp = assertNotNull(plain.program, "关闭禁载的对照档必须编得出 Program")
        assertTrue(excluded.ok, "禁载档不得有编译失败：${excluded.failures}")
        assertTrue(plain.ok, "对照档不得有编译失败：${plain.failures}")

        assertEquals(
            setOf(patchScriptA, patchScriptB),
            excluded.disabledErbFiles.toSet(),
            "命中清单的脚本必须逐条留痕",
        )
        assertEquals(2, excluded.stats.disabledErbFiles)
        assertEquals(emptyList(), plain.disabledErbFiles, "空清单档零禁载")
        assertEquals(0, plain.stats.disabledErbFiles)
        assertTrue(
            excluded.notes.any { it.contains("ERB 补丁整体禁载") },
            "禁载必须在 notes 可见（不静默少产物）",
        )

        // 行为判据：补丁脚本的函数体一条语句都没进产物（指令、字符串池都变少），
        // 但函数表条目数不变（标签保留为空体，目录外的 CALL 仍解析得过）。
        assertTrue(
            ep.instructionCount < pp.instructionCount,
            "禁载档指令数必须少于对照档（${ep.instructionCount} < ${pp.instructionCount}）",
        )
        assertTrue(
            ep.statementCount < pp.statementCount,
            "补丁函数体的语句不得进产物（${ep.statementCount} < ${pp.statementCount}；" +
                "指令 ${ep.instructionCount}/${pp.instructionCount}，" +
                "串 ${ep.stringCount}/${pp.stringCount}，常量 ${ep.constantCount}/${pp.constantCount}，" +
                "表达式 ${ep.exprCount}/${pp.exprCount}）",
        )
        assertEquals(
            pp.functions.size,
            ep.functions.size,
            "标签必须保留（空体函数），否则目录外静态 CALL 会撞未定义函数",
        )
        assertTrue(ep.functions.any { it.name.equals("AI_MESSAGE", ignoreCase = true) })
        assertTrue(ep.functions.any { it.name.equals("NORMAL", ignoreCase = true) })

        // 形参面零变化：CALL 带 3 个实参（第三个是字符串）打进空体标签时，运行期
        // `checkTooManyArgs` 用 `argc > arity` 抛 CodeEE —— arity 若因禁载变窄就是新 bug。
        // 两档取的是同一个 LabelDef，arity / 函数表条目数必须逐字相同。
        fun arityOf(p: com.eratw.port.core.code.Program, name: String): Int =
            p.functions.first { it.name.equals(name, ignoreCase = true) }.arity
        assertEquals(3, pp.functions.first { it.name.equals("AI_MESSAGE", ignoreCase = true) }.arity,
            "合成语料必须真的复刻了 3 形参（否则这条判据没有判定力）")
        assertEquals(arityOf(pp, "AI_MESSAGE"), arityOf(ep, "AI_MESSAGE"),
            "禁载不得改变头部形参数，否则目录外 CALL 会撞『参数过多』")
        assertEquals(
            plain.failures.map { it.file to it.message },
            excluded.failures.map { it.file to it.message },
            "禁载不得新增编译失败（含 CALL 静态链接未定义函数）",
        )

        // 事件组同名多定义：补丁内成员被禁载，目录外成员必须原样保留。
        fun eventMembers(p: com.eratw.port.core.code.Program) =
            p.functions.count { it.upperKey.startsWith("EVENTCOMEND#") }
        assertEquals(eventMembers(pp), eventMembers(ep), "事件组成员数不得因禁载变化")

        // 函数体长度差分：合成小语料没有字符串常量（PRINTL 的裸文本不进 ConstantPool，
        // 串池恒 0，故不用串池做判据），改用相邻入口 pc 的间距量每个函数体长度。
        // 被禁标签变空体，目录外脚本与目录外事件成员必须逐字不变。
        fun bodyLen(p: com.eratw.port.core.code.Program, name: String): Int {
            val fs = p.functions
            val i = fs.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            assertTrue(i >= 0, "函数表里必须有 $name")
            val start = fs[i].entryPc
            val end = (i + 1 until fs.size)
                .firstOrNull { fs[it].entryPc > start }
                ?.let { fs[it].entryPc } ?: p.instructionCount
            return end - start
        }
        assertTrue(
            bodyLen(ep, "AI_MESSAGE") < bodyLen(pp, "AI_MESSAGE"),
            "被禁标签必须变成空体（指令数 ${bodyLen(ep, "AI_MESSAGE")} < ${bodyLen(pp, "AI_MESSAGE")}）",
        )
        assertEquals(
            bodyLen(pp, "NORMAL"), bodyLen(ep, "NORMAL"),
            "目录外脚本的函数体不得因禁载增减一条指令",
        )
        val evNames = pp.functions.filter { it.name.startsWith("EVENTCOMEND#") }.map { it.name }
        assertEquals(
            evNames,
            ep.functions.filter { it.name.startsWith("EVENTCOMEND#") }.map { it.name },
            "事件组成员名单不得因禁载变化（不得按函数名一刀切）",
        )
        assertTrue(evNames.size >= 2, "合成语料必须真的有补丁内 + 目录外两个事件成员")
        assertEquals(
            1, evNames.count { bodyLen(ep, it) < bodyLen(pp, it) },
            "恰好一个事件成员（补丁内那个）变空体",
        )
        assertEquals(
            evNames.size - 1, evNames.count { bodyLen(ep, it) == bodyLen(pp, it) },
            "其余事件成员的函数体逐字不变",
        )
        // 例外 3：源清单照常登记（含 ERH 与被排除的脚本），两档逐条一致。
        assertEquals(
            plain.sourceFiles.map { it.relPath }.toSet(),
            excluded.sourceFiles.map { it.relPath }.toSet(),
        )
        assertTrue(excluded.sourceFiles.any { it.relPath == patchHeader })
        assertTrue(excluded.sourceFiles.any { it.relPath == patchScriptA })
        // 例外 1：ERH 变量仍被目录外脚本消费（若 ERH 被误排除，上面两档的 ok 就会是 false）。
        assertTrue(plain.ok && excluded.ok)
    }

    @Test
    fun `兼容档不得把禁载标签降级成 THROW 桩`(@TempDir root: Path) {
        writeCorpus(root)
        // Main.cmdCompile 在 compat 档传 degradedStub=true；无切片函数按既有口径会被编成
        // 「函数在装载时出错，无法使用」的 THROW 桩。禁载标签必须绕开这条链保持零语句空体，
        // 否则玩家一碰残留按钮就会吃新报错（比原缺插件形态更糟）。
        val strict = CorpusCompile.compile(root)
        val compat = CorpusCompile.compile(root, degradedStub = true)
        assertEquals(strict.disabledErbFiles, compat.disabledErbFiles)
        assertTrue(strict.ok && compat.ok)
        val a = assertNotNull(strict.program)
        val b = assertNotNull(compat.program)
        assertEquals(a.instructionCount, b.instructionCount, "禁载标签不得因编译档位多出 THROW 指令")
        assertEquals(a.statementCount, b.statementCount, "禁载标签不得因编译档位多出 THROW 语句")
        assertEquals(a.stringCount, b.stringCount, "禁载标签不得因编译档位多出报错文案串")
    }

    @Test
    fun `自定义清单换一批补丁 默认清单不再生效`(@TempDir root: Path) {
        writeCorpus(root)
        val custom = listOf(ErbPatchPolicy.Entry("outside-only", listOf("OTHER/")))
        val result = CorpusCompile.compile(root, patchPolicy = custom)
        val defaultResult = CorpusCompile.compile(root)

        assertEquals(listOf(outsideScript), result.disabledErbFiles, "自定义清单只命中自己那条规则")
        assertTrue(result.ok, "自定义清单档不得有编译失败：${result.failures}")
        assertEquals(emptyList<String>(), result.disabledErbFiles.filter { it.startsWith("DLC/AI对话补丁/") })
        assertTrue(
            result.stats.slices > defaultResult.stats.slices,
            "换成自定义清单后，AI对话补丁的两个脚本应恢复解析（切片数 " +
                "${result.stats.slices} > ${defaultResult.stats.slices}）",
        )
        assertFalse(result.notes.any { it.contains("（ai-chat）") })
    }

    @Test
    fun `manifest 留痕 非空才写且空表字节不变`(@TempDir root: Path) {
        writeCorpus(root)
        val result = CorpusCompile.compile(root)
        fun text(disabled: List<String>) = ErbcArtifacts.manifestText(
            corpusRoot = root,
            artifacts = emptyList(),
            sources = result.sourceFiles,
            compilerDigest = "0".repeat(64),
            compilerSources = 0,
            stats = result.stats,
            degraded = emptyList(),
            disabledErb = disabled,
        )
        val withTrace = text(result.disabledErbFiles)
        val clean = text(emptyList())
        assertTrue(withTrace.contains("disabled-erb 2\n"))
        assertTrue(withTrace.contains("disabled-erb-file $patchScriptA\n"))
        assertTrue(withTrace.contains("disabled-erb-file $patchScriptB\n"))
        assertFalse(clean.contains("disabled-erb"), "空表时清单字节必须与老产物一致")
        assertEquals(
            clean.length + ("disabled-erb 2\n".length) +
                result.disabledErbFiles.sorted().sumOf { "disabled-erb-file $it\n".length },
            withTrace.length,
            "留痕段长度守恒（排序固定、无额外状态）",
        )
        assertEquals(listOf("ai-chat"), DisabledErbPatchCatalog.all.map { it.id })
    }
}
