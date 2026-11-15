package com.eratw.port.vmdbg

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * `srcmap` CLI 面（MODERN-R4-C 契约 §2.2/§2.4）：三个退出码 + 输出字段 + 真产物集成。
 *
 * 退出码与字段用 [MiniCorpus] 合成产物跑（不依赖真语料/真产物）；真产物只做一条
 * 只读集成项，缺产物或堆不够时用 [Assumptions] 显式跳过（跳过原因写进测试输出，
 * 不静默假绿 —— 本波真产物的完整读数由 CLI 侧跑出，写在交付回执里）。
 *
 * 断言口径钉死：`line0` = `Program.lineOf(pc)` 原值（0 基）、`line1 = line0 + 1`
 * （`line0 < 0` 时原样）。
 */
class CmdSrcMapTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-srcmap-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** 跑一次 CLI：返回（退出码, stdout, stderr）。 */
    private fun run(vararg args: String): Triple<Int, String, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val err = PrintStream(ebuf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, err)
        return Triple(code, buf.toString(Charsets.UTF_8), ebuf.toString(Charsets.UTF_8))
    }

    /** 带 --erbc 跑一次 srccmap（mini 产物）。 */
    private fun srcmap(vararg rest: String): Triple<Int, String, String> =
        run(*MiniCorpus.args(built, work, "srcmap"), *rest)

    /** `key=value` → 表。单条记录传一行；`--audit` 传整段（十行键不重复，合并等价）。 */
    private fun fields(stdout: String): Map<String, String> =
        stdout.lineSequence().filter { it.isNotBlank() }
            .flatMap { it.split(' ').filter { t -> t.contains('=') } }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    // ------------------------------------------------------------------ 退出码
    @Test
    fun `用法错误_无参数退出码1`() {
        val (code, out, err) = srcmap()
        assertEquals(1, code)
        assertTrue(out.isEmpty())
        assertTrue(err.contains("用法"), "stderr 应给用法: $err")
    }

    @Test
    fun `用法错误_n非正退出码1且不装载产物`() {
        // --erbc 指到不存在目录也不影响：参数校验在装载之前 ⇒ 退出码 1 来自用法而非装载。
        val (code, _, err) = run("--erbc", tmp.resolve("nope").toString(), "srcmap", "3", "-n", "0")
        assertEquals(1, code)
        assertTrue(err.contains("-n"), "stderr 应点名 -n: $err")
    }

    @Test
    fun `pc越界退出码2`() {
        val (code, out, _) = srcmap("99999999")
        assertEquals(2, code)
        val f = fields(out)
        assertEquals("OUT_OF_RANGE", f["status"])
        assertEquals("99999999", f["pc"])
    }

    @Test
    fun `函数名不存在退出码2`() {
        val (code, out, _) = srcmap("--func", "NO_SUCH_FUNCTION_XYZ")
        assertEquals(2, code)
        val f = fields(out)
        assertEquals("NOT_FOUND", f["status"])
    }

    // ------------------------------------------------------------------ 输出字段
    @Test
    fun `单pc输出字段齐全且行号口径与 Program lineOf 一致`() {
        val program = ProgramLoader.loadMapped(built.erbcDir.resolve("program.erbc"))
        val metadata = BootMetadata.read(built.erbcDir.resolve("program.meta"))
        val map = SourceMap.of(program, metadata)
        val limit = minOf(program.instructionCount, 6)
        assertTrue(limit > 0, "mini 产物至少一条指令")

        for (pc in 0 until limit) {
            val (code, out, _) = srcmap(pc.toString())
            assertEquals(0, code)
            val f = fields(out)
            assertEquals(pc.toString(), f["pc"])
            val loc = map.at(pc)
            assertEquals(loc.owned.toString(), f["owned"] ?: "true", "pc=$pc")
            if (loc.owned) {
                assertEquals(loc.funcName, f["func"])
                assertEquals(loc.funcIndex.toString(), f["funcIndex"])
                assertEquals(program.lineOf(pc).toString(), f["line0"], "line0 = Program.lineOf 原值")
                val expect1 = if (program.lineOf(pc) < 0) program.lineOf(pc) else program.lineOf(pc) + 1
                assertEquals(expect1.toString(), f["line1"], "line1 = line0 + 1（负数原样）")
                if (loc.synthetic) assertEquals("true", f["synthetic"])
            } else {
                assertEquals("-", f["func"])
                assertEquals("-1", f["funcIndex"])
                assertEquals("-1", f["line0"])
                assertEquals("-1", f["line1"])
            }
        }
    }

    @Test
    fun `多pc_从pc起连续n条且不越界补行`() {
        val program = ProgramLoader.loadMapped(built.erbcDir.resolve("program.erbc"))
        val n = minOf(program.instructionCount, 4)
        val (code, out, _) = srcmap("0", "-n", n.toString())
        assertEquals(0, code)
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(n, lines.size)
        lines.forEachIndexed { i, line ->
            assertEquals(i.toString(), fields(line)["pc"], "第 $i 行应是 pc=$i")
        }

        // 越尾截断：pc 给最后一个，-n 给 100，只应有一行（不补假行、退出码仍 0）。
        val lastPc = program.instructionCount - 1
        val (code2, out2, _) = srcmap(lastPc.toString(), "-n", "100")
        assertEquals(0, code2)
        assertEquals(1, out2.lines().count { it.isNotBlank() })
    }

    @Test
    fun `audit七计数齐全且pcUnowned与逐pc输出自洽`() {
        val program = ProgramLoader.loadMapped(built.erbcDir.resolve("program.erbc"))
        val (code, out, _) = srcmap("--audit")
        assertEquals(0, code, "审计报了异常也退 0")
        val f = fields(out)
        for (k in listOf(
            "pcTotal", "pcUnowned", "funcNoPosition", "funcSyntheticPosition",
            "funcNonIncreasingEntry", "distinctFiles", "linesUnknown",
        )) {
            assertTrue(f.containsKey(k), "缺计数 $k: $out")
        }
        assertEquals(program.instructionCount.toString(), f["pcTotal"])
        assertEquals(program.functions.size.toString(), f["funcTotal"])

        // 交叉自洽：--audit 的 pcUnowned 必须等于逐 pc 走一遍数出来的 owned=false 条数。
        val total = program.instructionCount
        val (code2, out2, _) = srcmap("0", "-n", total.toString())
        assertEquals(0, code2)
        val unowned = out2.lines().filter { it.isNotBlank() }.count { fields(it)["owned"] == "false" }
        assertEquals(unowned.toString(), f["pcUnowned"])
    }

    @Test
    fun `func摘要给出区间文件与行号范围`() {
        val (code, out, _) = srcmap("--func", "MINI_PASS")
        assertEquals(0, code, out)
        val f = fields(out)
        assertEquals("MINI_PASS", f["func"])
        assertTrue(f["funcIndex"]!!.toInt() >= 0)
        assertTrue(f["entryPc"]!!.toInt() >= 0)
        assertTrue(f["instrCount"]!!.toInt() > 0)
        assertTrue(f["file"]!!.endsWith("mini.erb"), "file=${f["file"]}")
        assertFalse(SourceMap.isAbsolutePath(f["file"]!!), "输出文件列不得是绝对路径: ${f["file"]}")
        assertTrue(f["posLine"]!!.toInt() > 0)
        assertTrue(f["line0Max"]!!.toInt() >= f["line0Min"]!!.toInt())
        assertEquals(
            f["line0Max"]!!.toInt() + 1,
            f["line1Max"]!!.toInt(),
            "line1Max 与 line1 = line0 + 1 同口径",
        )

        // 大小写不敏感（Config.StrComper = OrdinalIgnoreCase）。
        assertEquals(0, srcmap("--func", "mini_pass").first)
    }

    @Test
    fun `合成标记函数在同名双定义事件语料上可见且不打成真实文件`() {
        // 合成事件入口只在「同名事件有两个以上定义」时产出（CorpusCompile 的 eventKeys 判据
        // 是 ds.size 大于 1）。mini 语料每个事件只有一个定义，故本项自建双定义夹具。
        val dir = Files.createTempDirectory("vmdbg-srcmap-dup")
        try {
            val erbc = buildDuplicateEventCorpus(dir)
            val metadata = BootMetadata.read(erbc.resolve("program.meta"))
            val synthetic = metadata.functions.filter { it.position.startsWith("·") }
            assertTrue(synthetic.isNotEmpty(), "同名双定义事件应产出合成入口 position")
            val program = ProgramLoader.loadMapped(erbc.resolve("program.erbc"))
            val hit = synthetic.first { it.index in program.functions.indices }
            val name = program.functions[hit.index].name

            val (code, out, _) = run("--erbc", erbc.toString(), "srcmap", "--func", name)
            assertEquals(0, code, out)
            val f = fields(out)
            assertEquals("true", f["synthetic"])
            assertEquals(hit.position, f["file"], "合成标记原样输出，不剥成文件名")
            assertEquals("-1", f["posLine"])

            val (acode, aout, _) = run("--erbc", erbc.toString(), "srcmap", "--audit")
            assertEquals(0, acode)
            val a = fields(aout)
            assertTrue(a["funcSyntheticPosition"]!!.toInt() >= 1, aout)
            assertTrue(a["pcSynthetic"]!!.toInt() >= 1, aout)
            assertEquals("·event-entry·", hit.position)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 自建「同名事件双定义」mini 语料（只为触发合成事件入口，形状照 MiniCorpus）。 */
    private fun buildDuplicateEventCorpus(dir: Path): Path {
        val game = dir.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        Files.writeString(game.resolve("emuera.config"), "")
        // TAB 缩进（ERB 的语句行口径，与 MiniCorpus 同款）；用行表拼串，避免源码里出现裸 TAB。
        val src = listOf(
            "@EVENTFIRST",
            "\tPRINTL dup-a",
            "",
            "@EVENTFIRST",
            "\tPRINTL dup-b",
            "",
        ).joinToString("\n")
        Files.writeString(erbDir.resolve("dup.erb"), src)
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "双定义事件语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = dir.resolve("erbc-dup")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        return outDir.resolve("erbc.v1")
    }

    // ------------------------------------------------------------------ 真产物（只读）
    @Test
    fun `真产物集成_4980_srcmap_audit`() {
        val dir = System.getenv("ERATW_ERBC_4980")?.let { Path.of(it) }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            dir != null,
            "公开仓不带真产物：设 ERATW_ERBC_4980 指向 erbc.v1 目录后可跑",
        )
        val programPath = dir!!.resolve("program.erbc")
        val metaPath = dir.resolve("program.meta")
        Assumptions.assumeTrue(
            Files.isRegularFile(programPath) && Files.isRegularFile(metaPath),
            "真产物缺席（$dir）⇒ 跳过；真读数见交付回执的 CLI 跑",
        )
        val est = ProgramLoader.estimateHeap(programPath, metaPath)
        val maxMiB = Runtime.getRuntime().maxMemory() / (1L shl 20)
        Assumptions.assumeTrue(
            est.sufficient,
            "真产物需要 ${est.requiredMiB} MiB，本测试 JVM 上限 ${maxMiB} MiB（vmdbg 测试 maxHeapSize=1g）⇒ 跳过；" +
                "真读数见交付回执的 desktop.py cmd --xmx 3g 跑",
        )

        val (code, out, err) = run("--erbc", dir.toString(), "srcmap", "--audit")
        assertEquals(0, code, err)
        val f = fields(out)
        val pcTotal = f["pcTotal"]!!.toInt()
        val pcUnowned = f["pcUnowned"]!!.toInt()
        assertTrue(pcTotal > 1_000_000, "4.980 全量产物的 pc 数应是百万级: $pcTotal")
        assertTrue(pcUnowned >= 0)
        val distinct = f["distinctFiles"]!!.toInt()
        assertTrue(distinct > 100, "全量语料至少上百个源文件: $distinct")
        assertTrue(
            f["funcNoPosition"]!!.toInt() + f["funcSyntheticPosition"]!!.toInt() <=
                f["funcTotal"]!!.toInt(),
        )
    }
}
