package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.SaveCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MAPID 的 **CHARA 一场逐函数步数归因**（VM `fnHistogram`，off/on 双产物）。
 *
 * 用 VM 的 `fnHistogram`（按 functionIndex 累计指令数；嵌套派生 VM 共享同一数组）
 * 跑一次 `CHARA_MOVEMENT`，逐函数 `off-on`；总差必须等于各函数归因之和。
 */
class MapIdCharaAttributionTest {

    @Tag("realArtifacts")
    @Test
    fun `MAPID CHARA 一场逐函数步数归因`() {
        assumeTrue(java.lang.Boolean.getBoolean("eratw.perf.mapidChara"), "设置 -Deratw.perf.mapidChara=true 才跑")
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val offDir = System.getenv("ERATW_ERBC_DIR_OFF")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-mapid-off", "erbc.v1")
        val onDir = System.getenv("ERATW_ERBC_DIR_ON")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-mapid-on", "erbc.v1")
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && Files.exists(save), "缺环境或存档")
        assumeTrue(Files.exists(offDir.resolve("program.erbc")), "缺 off 产物: $offDir")
        assumeTrue(Files.exists(onDir.resolve("program.erbc")), "缺 on 产物: $onDir")

        data class Run(val total: Long, val perFunc: Map<String, Long>, val error: String?, val ns: Long, val digest: Long)

        fun run(dir: Path): Run {
            val program = ProgramLoader.loadMapped(dir.resolve("program.erbc"), flatStatements = true) { }
            val metadata = BootMetadata.read(dir.resolve("program.meta"))
            val session = GameSession(
                gameRoot = gameRoot!!,
                program = program,
                metadata = metadata,
                maxStepsPerRun = 20_000_000L,
                randSeed = 20260226L,
            )
            val vm = vmOf(session.runtime)
            val fnHist = IntArray(program.functions.size)
            setVmField(vm, "fnHistogram", fnHist)
            // QOL 初始化（4.981 专有；4.980 没有就跳过）
            if (program.functions.any { it.name.equals("QOL_DB_INIT", ignoreCase = true) }) {
                runCatching { session.runtime.callFunction("QOL_DB_INIT") }
            }
            val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
            data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
            // 打开 DLC「幻想乡大地图」重负载路径
            if (program.functions.any { it.name.equals("GMF_SETBIT", ignoreCase = true) }) {
                runCatching { session.runtime.callFunction("GMF_SETBIT", LongArray(0), arrayOf("幻想乡大地图")) }
            }
            fnHist.fill(0)
            val t0 = System.nanoTime()
            var error: String? = null
            try {
                session.runtime.callFunction("CHARA_MOVEMENT")
            } catch (t: Throwable) {
                error = "${t.javaClass.simpleName}: ${t.message}"
            }
            val ns = System.nanoTime() - t0
            val total = fnHist.sumOf { it.toLong() }
            var dg = -3750763034362895579L
            fun mix(v: Long) { dg = (dg xor v) * 1099511628211L }
            mix(session.store.readInt("MAIN_MAP", longArrayOf(0)))
            mix(session.store.readInt("MAXROOM", longArrayOf(0)))
            mix(session.store.readInt("ENTRANCE", longArrayOf(0)))
            mix(session.store.readInt("MAP_PAGES", longArrayOf(0)))
            val kw1 = session.store.keywordToInteger("CFLAG", 1, "GMF_约会中同行转换")
            val kw2 = session.store.keywordToInteger("CFLAG", 1, "约会中")
            val nChar = session.store.readInt("CHARANUM", LongArray(0)).toInt()
            for (cc in 1 until nChar) {
                mix(session.store.readInt("CFLAG", longArrayOf(cc.toLong(), kw1)))
                mix(session.store.readInt("CFLAG", longArrayOf(cc.toLong(), kw2)))
            }
            val names = program.functions.map { it.name }
            val per = HashMap<String, Long>()
            for (i in fnHist.indices) {
                if (fnHist[i] != 0) per[names[i]] = per.getOrDefault(names[i], 0L) + fnHist[i].toLong()
            }
            session.close()
            return Run(total, per, error, ns, dg)
        }

        val off = run(offDir)
        val on = run(onDir)
        println("MAPID CHARA off total=${off.total} err=${off.error} on total=${on.total} err=${on.error}")
        assumeTrue(off.error == null && on.error == null, "CHARA_MOVEMENT 未跑通: off=${off.error} on=${on.error}")
        assertEquals(off.digest, on.digest, "CHARA 状态摘要不等 ⇒ MAPID 内联语义不对")

        val names = (off.perFunc.keys + on.perFunc.keys).sorted()
        val deltas = names.associateWith { n -> (off.perFunc[n] ?: 0L) - (on.perFunc[n] ?: 0L) }
        val deltaTotal = off.total - on.total
        assertEquals(deltaTotal, deltas.values.sum(), "总差 != 各函数归因之和")
        val getMapIdDelta = deltas["GET_MAPID"] ?: 0L
        val updateDelta = deltas["GMF_METHOD_UPDATE"] ?: 0L
        val lines = ArrayList<String>()
        fun out(s: String) { lines.add(s); println(s) }
        out("MAPID CHARA total off=${off.total} on=${on.total} delta=$deltaTotal")
        out("MAPID CHARA wall off=${off.ns / 1_000_000}ms on=${on.ns / 1_000_000}ms ratio=${"%.3f".format(off.ns.toDouble() / on.ns)}")
        out("MAPID CHARA GET_MAPID delta=$getMapIdDelta GMF_METHOD_UPDATE delta=$updateDelta")
        out("MAPID CHARA top 12 逐函数归因（off-on，正=省）:")
        deltas.entries.sortedByDescending { it.value }.take(12).forEach { (n, d) -> out("  $n: $d") }
        val report = Path.of("..", "..", ".tmp", "mapid-chara.txt")
        Files.createDirectories(report.parent)
        Files.writeString(report, lines.joinToString(System.lineSeparator()) + System.lineSeparator())
        println("MAPID CHARA report=${report.toAbsolutePath()}")
        assertTrue(getMapIdDelta in 790_000L..1_600_000L, "GET_MAPID delta=$getMapIdDelta 不在 0.79M–1.60M")
        assertTrue(deltaTotal > 0L, "CHARA total delta 应 > 0")
    }

    private fun vmOf(rt: ProcessRuntime): Any? = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        f.get(rt)
    }.getOrNull()

    private fun setVmField(vm: Any?, field: String, value: Any?) {
        val target = vm ?: return
        target.javaClass.getField(field).set(target, value)
    }
}
