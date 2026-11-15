package com.eratw.port.erbcc.compile

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 函数级编译缓存主体闸 + 反向证明靶子（MODERN-R11-R4B2）。
 *
 * 主体闸：同一份「含重复函数体」的合成夹具，关态一跑、开态一跑，四件产物逐字节相同，
 * 且开态 hits > 0。重复函数体（AAA 与 ZZZ）分居两个文件、中间夹着填充函数，制造
 * 「切片录制环境与命中重放环境不同」的形态 —— adoptSlice 若不按新基址重写 pool 操作数，
 * 这里会先在 program.erbc 上红（反向证明 D 的靶子）。
 *
 * 四条反向证明靶子：
 * - A 键去掉环境指纹：[环境指纹对全局环境敏感] 钉「不同全局环境 ⇒ 不同 digest/key」；
 * - B 命中跳过登记：主体闸的 program.meta 逐字节对拍 + 开态侧车函数元数据读数；
 * - C 缺省改开：关态基线断言 funcCacheStats == null（主体闸内）；
 * - D adoptSlice 不重写 pool 操作数：跨文件重复函数体的 program.erbc 逐字节对拍。
 */
class FunctionCompileCacheTest {

    private companion object {
        const val PROP = "eratw.erbcc.funcCache"
        val ARTIFACTS = listOf("program.erbc", "program.meta", "program.stmt.idx", "manifest.txt")
    }

    private fun repoRoot(): Path = assertNotNull(
        ErbcCompilerDigest.locateRepoRoot(),
        "定位不到仓库根：compiler-digest 没有判据（测试工作目录应在仓库内）",
    )

    /**
     * 合成夹具：AAA 与 ZZZ 的函数体逐行原文相同、非名字 FuncSig 相同、且在各自文件里
     * 处于同一组物理行号（键含行号，位置不同就不会命中），但分居两个文件、中间夹着
     * 20_fill.ERB 的三个填充函数。于是 ZZZ 必然命中 AAA 的切片，且命中时池状态与
     * 切片录制时不同。
     */
    private fun writeFixture(root: Path) {
        fun put(rel: String, text: String) {
            val p = root.resolve(rel)
            p.parent?.let { Files.createDirectories(it) }
            Files.write(p, text.trimIndent().toByteArray(Charsets.UTF_8))
        }
        put(
            "00_globals.ERH", """
            #DIM G_X
            """
        )
        put(
            "00_helper.ERB", """
            @HELPER
            RETURN
            """
        )
        put(
            "10_common.ERB", """
            @AAA
            G_X = 1 + 2
            PRINTFORML A={G_X}
            CALL HELPER, 9, 8
            RETURN
            """
        )
        put(
            "20_fill.ERB", """
            @FILL_1
            PRINTL FILL_ONE
            G_X = 101
            RETURN
            @FILL_2
            PRINTL FILL_TWO
            G_X = 102
            RETURN
            @FILL_3
            PRINTL FILL_THREE
            G_X = 103
            RETURN
            """
        )
        // ZZZ 与 AAA 的函数体逐行原文相同、且在各自文件里处于同一组物理行号：
        // 键已含行号（避免不同位置错命中），故 ZZZ 必然命中 AAA 的切片，
        // 而两者 Statement.line 相同 ⇒ 重放行号与真编译一致。
        put(
            "30_dup.ERB", """
            @ZZZ
            G_X = 1 + 2
            PRINTFORML A={G_X}
            CALL HELPER, 9, 8
            RETURN
            """
        )
    }

    private fun compileTo(corpus: Path, out: Path): Pair<Map<String, ByteArray>, CompileResult> {
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成夹具必须编译通过；失败 ${result.failures.size} 条：${result.failures.take(5)}")
        val program = assertNotNull(result.program)
        val report = ErbcArtifacts.write(
            outDir = out,
            program = program,
            corpusRoot = corpus,
            sourceFiles = result.sourceFiles,
            stats = result.stats,
            bootMetadata = result.bootMetadata,
            repoRoot = repoRoot(),
        )
        val dir = report.artifactPath.parent
        return ARTIFACTS.associateWith { Files.readAllBytes(dir.resolve(it)) } to result
    }

    private fun assertSameBytes(what: String, expected: ByteArray, actual: ByteArray) {
        val common = minOf(expected.size, actual.size)
        for (i in 0 until common) {
            if (expected[i] != actual[i]) {
                throw AssertionError(
                    "$what 必须逐字节相同，首个不同偏移=$i（长度 ${expected.size} vs ${actual.size}）；" +
                        "sha256 期望=${ErbcArtifacts.sha256Hex(expected)} 实际=${ErbcArtifacts.sha256Hex(actual)}",
                )
            }
        }
        if (expected.size != actual.size) {
            throw AssertionError(
                "$what 一侧是另一侧真前缀（长度 ${expected.size} vs ${actual.size}）；" +
                    "sha256 期望=${ErbcArtifacts.sha256Hex(expected)} 实际=${ErbcArtifacts.sha256Hex(actual)}",
            )
        }
    }

    /** 关态/开态两跑之间只动这一个 system property，finally 里恢复原值。 */
    private fun <T> withProperty(value: String?, body: () -> T): T {
        val saved = System.getProperty(PROP)
        try {
            if (value == null) System.clearProperty(PROP) else System.setProperty(PROP, value)
            return body()
        } finally {
            if (saved == null) System.clearProperty(PROP) else System.setProperty(PROP, saved)
        }
    }

    // ------------------------------------------------------------------
    // 主体闸
    // ------------------------------------------------------------------

    @Test
    fun `合成夹具 关态与开态 四件产物逐字节相同且开态命中数大于零`() {
        val corpus = Files.createTempDirectory("r4b2-fixture")
        writeFixture(corpus)
        val (offBytes, offRes) = withProperty(null) {
            compileTo(corpus, Files.createTempDirectory("r4b2-off"))
        }
        // C 靶子：缺省关 ⇒ 不建缓存实例，读数一律为 0 / null。
        assertNull(offRes.funcCacheStats, "关态不得建缓存实例（funcCacheStats 必须 null）")
        assertEquals(0, offRes.funcCacheHits, "关态命中数必须为 0")
        assertEquals(0, offRes.funcCacheMisses, "关态未命中数必须为 0")

        val (onBytes, onRes) = withProperty("true") {
            compileTo(corpus, Files.createTempDirectory("r4b2-on"))
        }
        assertNotNull(onRes.funcCacheStats, "开态必须暴露 funcCache 读数")
        assertTrue(onRes.funcCacheHits > 0, "开态必须真的命中（防空转），实际 hits=${onRes.funcCacheHits}")
        // B/D 靶子：四件产物（含 program.meta 的 metas/labelPcs/fileByKey 登记、program.erbc
        // 的 pool 下标重映射）逐字节相同。
        for (f in ARTIFACTS) assertSameBytes(f, offBytes.getValue(f), onBytes.getValue(f))
        // B 靶子的显式读数：开/关两态函数表与源文件清单条数须一致（命中不跳过登记）。
        assertEquals(offRes.program?.functions?.size, onRes.program?.functions?.size, "函数表条数")
        assertEquals(offRes.sourceFiles.size, onRes.sourceFiles.size, "源文件清单条数")
    }

    // ------------------------------------------------------------------
    // 反向证明 A 靶子：键必须含环境指纹
    // ------------------------------------------------------------------

    @Test
    fun `环境指纹对全局环境敏感`() {
        val body = "G_X = 1 + 2".toByteArray(Charsets.UTF_8)
        val sigKey = "arity=0"
        val base = CompileEnvFingerprint("lexerA", "constsA", "dimA", "cfgA", "symA", "digestA")
        val changed = CompileEnvFingerprint("lexerB", "constsA", "dimA", "cfgA", "symA", "digestA")
        assertNotEquals(base.digest(body, sigKey), changed.digest(body, sigKey), "lexerConfig 变了 digest 必须变")
        val cache = FunctionCompileCache()
        assertNotEquals(
            cache.key(base, "G_X = 1 + 2", sigKey),
            cache.key(changed, "G_X = 1 + 2", sigKey),
            "键必须含环境指纹：函数体原文相同、全局环境不同时键必须不同",
        )
        // 同一环境、同一输入两次 digest 必须逐位相同（确定性，防 HashMap 迭代序泄漏）。
        assertEquals(base.digest(body, sigKey), base.digest(body, sigKey), "digest 必须是纯函数")
        assertEquals(
            cache.key(base, "G_X = 1 + 2", sigKey),
            cache.key(base, "G_X = 1 + 2", sigKey),
            "同一环境同一输入两次组键必须相同",
        )
    }

    @Test
    fun `环境指纹序列化 Map 按 key 集合按自然序`() {
        val m1 = linkedMapOf("b" to 2, "a" to 1, "c" to 3)
        val m2 = linkedMapOf("c" to 3, "a" to 1, "b" to 2)
        assertEquals(CompileEnvFingerprint.canonicalMap(m1), CompileEnvFingerprint.canonicalMap(m2), "Map 迭代序不得进指纹")
        assertEquals(
            CompileEnvFingerprint.canonicalSet(listOf("z", "a", "m")),
            CompileEnvFingerprint.canonicalSet(listOf("m", "z", "a")),
            "集合须按自然序",
        )
        val body = "x".toByteArray(Charsets.UTF_8)
        val e1 = CompileEnvFingerprint("l", CompileEnvFingerprint.canonicalMap(m1), "d", "cfg", "s", "g")
        val e2 = CompileEnvFingerprint("l", CompileEnvFingerprint.canonicalMap(m2), "d", "cfg", "s", "g")
        assertEquals(e1.digest(body, "s"), e2.digest(body, "s"), "Map 迭代序不同但环境相同 ⇒ digest 必须相同")
    }

    @Test
    fun `缓存本体 命中未命中计数与命中重放`() {
        val slice = ErbProgramCompiler().compileFunctionRecorded(FuncSig("X"), emptyList())
        val cache = FunctionCompileCache()
        val env = CompileEnvFingerprint("l", "c", "d", "cfg", "s", "g")
        val key = cache.key(env, "RETURN", "s")
        assertNull(cache.lookup(key), "首次查键必须未命中")
        assertEquals(0, cache.hits)
        assertEquals(1, cache.misses)
        cache.store(key, slice)
        assertEquals(slice, cache.lookup(key), "存后再查必须命中同一片切片")
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
        assertTrue(cache.stats().contains("hits=1") && cache.stats().contains("misses=1"))
    }
}
