package com.eratw.port.core.e2e

import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.audio.FakeChannelDecoder
import com.eratw.port.core.audio.SoundSourceResolver
import com.eratw.port.core.audio.TableSoundSink
import com.eratw.port.core.audio.soundChannelTable
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SoundResourcePaths
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-014「ERB 侧按路径消费旁挂资源」的**运行时端到端**验收。
 *
 * 装置：真 [LineParser] + 真 [ErbProgramCompiler] + 真 [VariableStore] + 真 [ProcessRuntime]，
 * 宿主是真 [RootedBuiltinHost]（`fileOps` = [com.eratw.port.core.proc.RootedFileOps]，根 =
 * 临时 gameRoot），音频走生产 [TableSoundSink]（`TableSoundSink(store.aux, resolver)`）。
 * 与函数层直调（`BuiltinEvalContext.call`）的区别：这里的内建由**真 ERB 片段经 VM 派发**，
 * 断言的路径是 ERB 源码里写的那串名字被引擎解析后的真实落点。
 *
 * 目录形状 = mod 媒体导入端（`eratw-android/app/src/main/kotlin/com/eratw/port/android/ModImportMediaAssets.kt`）：
 * 落盘根 [RESOURCE_ROOT] = `gameRoot/resources/mod/<dir>/`（:49、:444 与 commit :159-170）、
 * 音频根 [SOUND_ROOT] = `gameRoot/sound/mod/<dir>/`（:52 同段）；两树根都由清单
 * `RT2MEDIA/1 dir=<dir>` 给出（:43、:46）。
 *
 * 每条断言都标了消费端实现行号（GraphicsMethods.kt / HostMethods.kt），并与导入端落点对照。
 * 反证用「改路径前缀」：把导入端那一层 `resources/` / `sound/` 前缀去掉或多加一层，必须红。
 */
class ModResourceConsumeE2ETest {

    // ------------------------------------------------------------------
    // 测试夹具：按导入端落盘形状造临时 gameRoot
    // ------------------------------------------------------------------

    private fun gameRoot(): Path {
        val root = Files.createTempDirectory("resconsume")
        val resDir = root.resolve("resources/mod/testmod")
        Files.createDirectories(resDir)
        // 导入端 RESOURCE_EXTENSIONS 含 txt；正文带 CRLF 以顺带钉 LOADTEXT 的 \r 归一。
        Files.writeString(resDir.resolve("hello.txt"), "mod-hello\r\nline2\r\n")
        Files.write(resDir.resolve("pic.png"), pngBytes(4, 3))
        val soundDir = root.resolve("sound/mod/testmod")
        Files.createDirectories(soundDir)
        Files.write(soundDir.resolve("se.mp3"), byteArrayOf(0x49, 0x44, 0x33, 0x04))
        return root
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, 0xFF000000.toInt() or (x * 16 + y))
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    private fun strOf(store: VariableStore, name: String): String = store.readStr(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 装置（与 FloatRuntimeE2ETest 里的 ErbSnippetHarness 同源，额外开放宿主/音频注入位）
    // ------------------------------------------------------------------

    private fun runSnippet(
        gameRoot: Path,
        source: List<String>,
        entry: String,
        setup: (VariableStore) -> Unit = {},
        sink: (VariableStore) -> com.eratw.port.core.host.SoundSink = { com.eratw.port.core.host.NoopSoundSink },
    ): VariableStore {
        val store = VariableStore()
        setup(store)
        val fragment = parseFragment(source, LineParser(SymbolTable()))
        val compiler = ErbProgramCompiler()
        val known = fragment.first.keys.toSet()
        for ((key, body) in fragment.first) {
            val ps = fragment.second[key].orEmpty()
            compiler.compileFunction(
                FuncSig(key, ps.size, isMethod = false, returnsStr = false, paramNames = ps),
                body,
                known,
            )
        }
        val program: Program = compiler.build(entry.uppercase())
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name.uppercase(),
                    argIntCount = f.arity,
                    argStrCount = f.arity,
                    isMethod = false,
                )
            },
        )
        // 真宿主：fileOps / readFileBytes 都以 gameRoot 为根（BootHosts.kt:397、:588-592）。
        // 音频四项覆盖成 store.aux 的同一张声道表（SemConfigHost.kt:61-64 的生产口径），
        // 否则 BuiltinHost 的缺省实现恒说「0 个声道 / 未播放」，ISPLAYINGSOUND 断言无意义。
        val host = SoundAwareHost(RootedBuiltinHost(gameRoot), store.aux)
        ProcessRuntime(
            program,
            store,
            table,
            VmConfig(maxStepsPerRun = 200_000),
            builtinHost = host,
            soundSink = sink(store),
        ).execute()
        return store
    }

    private fun parseFragment(
        lines: List<String>,
        parser: LineParser,
    ): Pair<LinkedHashMap<String, MutableList<Statement>>, HashMap<String, List<String>>> {
        val functions = LinkedHashMap<String, MutableList<Statement>>()
        val params = HashMap<String, List<String>>()
        var current: String? = null
        lines.forEachIndexed { i, raw ->
            val t = raw.trimStart()
            if (t.isEmpty() || t.startsWith(";")) return@forEachIndexed
            val kind = when {
                t.startsWith("@") -> LineKind.FUNCTION_LABEL
                t.startsWith("$") -> LineKind.GOTO_LABEL
                t.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            val stmt = parser.parseLogicalLine(LogicalLine(kind, "resconsume.erb", i + 1, t))
            when (stmt) {
                null -> {}
                is SharpStmt -> {}
                is FuncLabelStmt -> {
                    current = stmt.name.uppercase()
                    functions.getOrPut(current!!) { ArrayList() }
                    params[current!!] = stmt.paramNames
                }
                else -> functions.getOrPut(current ?: error("函数体外的语句: ${stmt::class.simpleName}")) {
                    ArrayList()
                }.add(stmt)
            }
        }
        return functions to params
    }

    private class SoundAwareHost(delegate: RootedBuiltinHost, private val aux: RuntimeAux) : BuiltinHost by delegate {
        override val soundChannels: Int get() = soundChannelTable(aux).channelCount

        override fun isPlayingSound(channel: Int): Boolean = soundChannelTable(aux).isPlayingSound(channel)

        override fun isPlayingBgm(): Boolean = soundChannelTable(aux).isPlayingBgm()
    }

    /** 记录宿主解码请求的路径（`ImageDecoderHost` 槽，见 host/ImageDecoderHost.kt:36/:64）。 */
    private class RecordingDecoder(private val width: Int, private val height: Int) : ImageDecoderHost {
        val paths = ArrayList<String>()

        override fun decode(path: String, bytes: ByteArray): PngImage? {
            paths += path
            return null
        }

        override fun probe(path: String, bytes: ByteArray): IntArray? {
            paths += path
            return intArrayOf(width, height)
        }
    }

    /**
     * 生产口径的音频解析：`SoundResourcePaths.resolveInGameRoot`（host/SoundResourcePaths.kt:123）
     * = `.\\sound\\ + name`（FunctionIdentifier.cs:2622）。记录 name → gameRoot 相对落点。
     */
    private class RecordingResolver(private val gameRoot: Path) : SoundSourceResolver {
        /** 每次 resolve 的 (sink 收到的原始名字, 解析落点相对 gameRoot；null = 不存在)。 */
        val attempts = ArrayList<Pair<String, String?>>()

        override fun resolve(name: String): ChannelDecoder? {
            val file = SoundResourcePaths.resolveInGameRoot(gameRoot.toFile(), name)
            attempts += name to file?.let { gameRoot.relativize(it.toPath()).toString().replace('\\', '/') }
            return if (file != null && file.isFile) FakeChannelDecoder(total = 1.0) else null
        }
    }

    private fun soundSinkWith(resolver: SoundSourceResolver): (VariableStore) -> com.eratw.port.core.host.SoundSink =
        { store -> TableSoundSink(store.aux, resolver) }

    // ------------------------------------------------------------------
    // ① LOADTEXT：ERB 写 `resources/mod/<dir>/…`，引擎根 = gameRoot
    // 消费端：HostMethods.kt:270 注册、:276-302 getValidPath（原串原样保留，相对路径由
    // 宿主 RootedFileOps 按 gameRoot 解析）、:302 BOM/\r 归一。
    // ------------------------------------------------------------------

    private val loadOk = listOf(
        "@LOAD_OK",
        // 字符串变量的值表达式赋值必须用 `'=`（AssignmentStr，LexicalAnalyzer.kt:368）；
        // 裸 `=` 对字符串左值是「form 串」赋值（ArgBuilders.kt:1908-1929），会把 RHS 当字面量。
        "RESULTS '= LOADTEXT(\"resources/mod/testmod/hello.txt\")",
        "A = 1",
    )

    @Test
    fun `LOADTEXT 按导入端落点读到 mod 树文本并归一换行`() {
        val store = runSnippet(gameRoot(), loadOk, "LOAD_OK")
        assertEquals(
            "mod-hello\nline2\n",
            strOf(store, "RESULTS"),
            "LOADTEXT 应读到 resources/mod/testmod/hello.txt 正文，\\r 已按 HostMethods.kt:302 去掉",
        )
        assertEquals(1L, intOf(store, "A"), "脚本应继续执行")
    }

    @Test
    fun `LOADTEXT 缺文件返回空串且不中断脚本`() {
        val store = runSnippet(
            gameRoot(),
            listOf(
                "@LOAD_MISSING",
                "RESULTS '= LOADTEXT(\"resources/mod/testmod/missing.txt\")",
                "A = 1",
            ),
            "LOAD_MISSING",
        )
        assertEquals("", strOf(store, "RESULTS"), "文件不存在：HostMethods.kt:295-299 的 catch 口径 ⇒ 空串，不升级成致命错")
        assertEquals(1L, intOf(store, "A"), "下一行仍执行 ⇒ 缺文件不砖")
    }

    /** 反证 1：少一层 `resources/` ⇒ 按 gameRoot 相对找 `<gameRoot>/mod/…`，必须读不到。 */
    @Test
    fun `反证 LOADTEXT 去掉 resources 前缀就读不到`() {
        val store = runSnippet(
            gameRoot(),
            listOf(
                "@LOAD_NO_PREFIX",
                "RESULTS '= LOADTEXT(\"mod/testmod/hello.txt\")",
                "A = 1",
            ),
            "LOAD_NO_PREFIX",
        )
        assertEquals(
            "",
            strOf(store, "RESULTS"),
            "少了 resources/ 前缀 ⇒ 落点变成 <gameRoot>/mod/testmod/hello.txt，文件不在 ⇒ 空串；这证明消费根是 gameRoot + resources/",
        )
        assertEquals(1L, intOf(store, "A"))
    }

    // ------------------------------------------------------------------
    // ② GCREATEFROMFILE：ERB 写 `mod/<dir>/pic.png`，引擎补 CONTENT_DIR = `resources/`
    // 消费端：GraphicsMethods.kt:136（注册 :136，rawPath 第 3 参 :147）、:148 contentDirPath、
    // :1231-1232 前缀规则、:1792 host.readFileBytes（gameRoot 相对）。
    // ------------------------------------------------------------------

    @Test
    fun `GCREATEFROMFILE 请求带 resources 前缀抵达图片宿主`() {
        val root = gameRoot()
        val recorder = RecordingDecoder(4, 3)
        val store = runSnippet(
            root,
            listOf(
                "@GFX_HOST",
                "A = GCREATEFROMFILE(0, \"mod/testmod/pic.png\")",
            ),
            "GFX_HOST",
            setup = { s ->
                // 显式泛型：setup 的返回类型是 Unit，若让 Kotlin 从期望类型推 T 会存进 Unit。
                s.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { recorder }
            },
        )
        assertEquals(1L, intOf(store, "A"), "延迟装载路径（probe 给尺寸）应返回 1")
        assertEquals(
            listOf("resources/mod/testmod/pic.png"),
            recorder.paths,
            "宿主看到的路径必须 = CONTENT_DIR + ERB 相对名（GraphicsMethods.kt:1231-1232）",
        )
    }

    @Test
    fun `GCREATEFROMFILE 真 PNG 解码并量出尺寸`() {
        val store = runSnippet(
            gameRoot(),
            listOf(
                "@GFX_DECODE",
                "A = GCREATEFROMFILE(0, \"mod/testmod/pic.png\")",
                "B = GWIDTH(0)",
                "C = GHEIGHT(0)",
            ),
            "GFX_DECODE",
        )
        assertEquals(1L, intOf(store, "A"), "真 PNG 经 PngDecoder（GraphicsMethods.kt:1792 起）应成功")
        assertEquals(4L, intOf(store, "B"), "GWIDTH = 夹具 4 列")
        assertEquals(3L, intOf(store, "C"), "GHEIGHT = 夹具 3 行")
    }

    /** 反证 2：第 3 参 = 1 关掉 ContentDir 前缀（FunctionMethodCreator.cs:6883-6895）⇒ 落点不在 resources 树 ⇒ 0。 */
    @Test
    fun `反证 GCREATEFROMFILE 第 3 参置 1 跳过前缀就失败`() {
        val store = runSnippet(
            gameRoot(),
            listOf(
                "@GFX_RAW",
                "A = GCREATEFROMFILE(0, \"mod/testmod/pic.png\", 1)",
            ),
            "GFX_RAW",
        )
        assertEquals(
            0L,
            intOf(store, "A"),
            "raw 模式按 <gameRoot>/mod/… 找，文件只在 resources/mod/… ⇒ 返回 0；这证明导入端落点必须靠 resources/ 前缀才对得上",
        )
    }

    // ------------------------------------------------------------------
    // ③ PLAYSOUND：ERB 写 `mod/<dir>/se.mp3`，引擎补 `sound/`
    // 消费端：StatementExecutor.kt:3800-3812（PLAYSOUND 原样传名）→ TableSoundSink
    // （AudioAdapters.kt:168-170）→ resolver。生产 resolver 在 :platform 的
    // FileSoundSourceResolver.kt:50/101/122，本测试用同源的 core SoundResourcePaths（:123）。
    // ------------------------------------------------------------------

    @Test
    fun `PLAYSOUND 按 sound 根解析并占道`() {
        val root = gameRoot()
        val resolver = RecordingResolver(root)
        val store = runSnippet(
            root,
            listOf(
                "@SOUND_OK",
                "PLAYSOUND \"mod/testmod/se.mp3\"",
                "A = ISPLAYINGSOUND(0)",
                "B = 1",
            ),
            "SOUND_OK",
            sink = soundSinkWith(resolver),
        )
        assertEquals(
            listOf<Pair<String, String?>>("mod/testmod/se.mp3" to "sound/mod/testmod/se.mp3"),
            resolver.attempts,
            "resolver 收到裸名，按 .\\sound\\ + 名字（FunctionIdentifier.cs:2622）找到 sound/mod/testmod/se.mp3",
        )
        assertEquals(0L, intOf(store, "A"), "ISPLAYINGSOUND(0) 在播时返回声道号本身 0（HostMethods.kt:80/:94），不是 1")
        assertEquals(1L, intOf(store, "B"))
    }

    @Test
    fun `PLAYSOUND 缺文件静默且不中断脚本`() {
        val root = gameRoot()
        val resolver = RecordingResolver(root)
        val store = runSnippet(
            root,
            listOf(
                "@SOUND_MISSING",
                "PLAYSOUND \"mod/testmod/missing.mp3\"",
                "A = ISPLAYINGSOUND(0)",
                "B = 1",
            ),
            "SOUND_MISSING",
            sink = soundSinkWith(resolver),
        )
        assertEquals(
            listOf<Pair<String, String?>>("mod/testmod/missing.mp3" to null),
            resolver.attempts,
            "名字仍按 sound 根解析：sound/mod/testmod/missing.mp3 不存在 ⇒ null（FunctionIdentifier.cs:2625-2628 静默 return）",
        )
        assertEquals(
            -1L,
            intOf(store, "A"),
            "文件不存在 ⇒ resolver 返回 null ⇒ 不占道 ⇒ ISPLAYINGSOUND(0) = -1（FunctionIdentifier.cs:2625-2628 静默 return）",
        )
        assertEquals(1L, intOf(store, "B"), "下一行仍执行 ⇒ 不崩")
    }

    /** 反证 3：ERB 自带 `sound/` 前缀 ⇒ 解析成 `<gameRoot>/sound/sound/…` 读不到。 */
    @Test
    fun `反证 PLAYSOUND 自带 sound 前缀就解析不到`() {
        val root = gameRoot()
        val resolver = RecordingResolver(root)
        val store = runSnippet(
            root,
            listOf(
                "@SOUND_DOUBLE_PREFIX",
                "PLAYSOUND \"sound/mod/testmod/se.mp3\"",
                "A = ISPLAYINGSOUND(0)",
            ),
            "SOUND_DOUBLE_PREFIX",
            sink = soundSinkWith(resolver),
        )
        assertEquals(
            listOf<Pair<String, String?>>("sound/mod/testmod/se.mp3" to null),
            resolver.attempts,
            "多一层 sound/ ⇒ 落点 sound/sound/mod/testmod/se.mp3 不存在 ⇒ null",
        )
        assertEquals(-1L, intOf(store, "A"), "该落点没有文件 ⇒ 不占道（ISPLAYINGSOUND(0) = -1）；这证明 ERB 里写的是 sound/ **之内**的相对名")
    }
}
