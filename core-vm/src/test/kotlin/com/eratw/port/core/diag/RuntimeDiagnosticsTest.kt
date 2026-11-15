package com.eratw.port.core.diag

import com.eratw.port.modapi.Registration
import java.io.File
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * RuntimeDiagnostics 契约闸（CONTRACT-RT2-E3 §3 的 16 条 Gate + 2 条补充，共 18 例）。
 *
 * 钉住的东西（逐条对契约）：
 * 1. 容量边界（`capacity >= 1`；容量 1 只留最新）；
 * 2. 环溢出丢最旧 + snapshot 最旧→最新 + seq 连续且**与容量无关**；
 * 3. latest(n) 的三种降级（n<=0 / n>size / 正常尾段）；
 * 4. 关闭态 record **零构造**：detail thunk 计数 = 0、seq 不推进、snapshot 空、不通知订阅者；
 * 5. setEnabled 关闭清空 + 再开从空环与 seq=1 重来；
 * 6. detail 惰性（开启才求值、只一次、快照/导出不重算）+ 异常吞成 `<detail failed: 类名>`；
 * 7. 订阅同步回调 + 注册序 + 退订后不再收（退订幂等）；
 * 8. 单 listener 抛异常隔离（后续 listener 仍收到、记录仍入库）；
 * 9. **回调在实例锁外**：回调期间另一线程能取快照、同线程可重入 record（超时即红）；
 * 10. JSON 逐字节精确（null 字段 / 非 ASCII / 引号 / 反斜杠 / 换行 / 制表 / 控制字符）；
 * 11. 空环导出 = 零字节；两条记录逐行、末行带换行；
 * 12. 多线程并发 record 的不变量（seq 无重复无缺口、快照封顶）；
 * 13. 源码形状：不读墙钟、无静态可变全局（顶层 object / companion var）；
 * 14. domain / outcome 枚举序；
 * 15. 未知 reasonCode 原样保存（不校验词汇、不做枚举映射）；
 * 16. 1000 条导出的行数与首尾行；
 * 17. snapshot 是不可变副本；
 * 18. 本轮订阅快照不受途中退订影响（契约 §2.1 第 4 条）。
 *
 * 非 bug 修复：本文件是 P1 工程契约新增（era-arch-v61u.md §21.6 / §12.1），
 * 根因层断言「不适用 §3.1」。
 */
class RuntimeDiagnosticsTest {

    /** record 的便捷包装（只为了让用例读起来是「一条诊断」而不是九个位置参数）。 */
    private fun RuntimeDiagnostics.put(
        key: String = "k",
        day: Long? = null,
        phase: String? = null,
        domain: DiagnosticDomain = DiagnosticDomain.PHASE,
        packageId: String? = null,
        outcome: DiagnosticOutcome = DiagnosticOutcome.OK,
        reasonCode: String = "R",
        detail: (() -> String)? = null,
    ) = record(
        day = day,
        phase = phase,
        domain = domain,
        packageId = packageId,
        key = key,
        outcome = outcome,
        reasonCode = reasonCode,
        detail = detail,
    )

    /** 源码形状用例的定位口（防假绿：两处候选取不到就 error，不静默跳过）。 */
    private fun runtimeDiagnosticsSource(): String {
        val rel = "src/main/kotlin/com/eratw/port/core/diag/RuntimeDiagnostics.kt"
        val file = listOf(File(rel), File("core-vm/$rel")).firstOrNull { it.isFile }
            ?: error("定位不到源码 $rel（防假绿）：cwd=${System.getProperty("user.dir")}")
        return file.readText()
    }

    // ------------------------------------------------------------------
    // Gate 1-3：有界环 / 顺序 / seq / latest
    // ------------------------------------------------------------------

    @Test
    fun `容量必须为正且容量1只留最新`() {
        assertFailsWith<IllegalArgumentException>("capacity=0 必须拒绝") { RuntimeDiagnostics(capacity = 0) }
        assertFailsWith<IllegalArgumentException>("capacity=-3 必须拒绝") { RuntimeDiagnostics(capacity = -3) }
        val d = RuntimeDiagnostics(capacity = 1)
        d.put(key = "a")
        d.put(key = "b")
        val snap = d.snapshot()
        assertEquals(1, snap.size, "容量 1 只留一条")
        assertEquals("b", snap[0].key, "留下的必须是最新一条")
        assertEquals(2L, snap[0].seq, "seq 随记录推进，与容量无关")
    }

    @Test
    fun `环溢出丢最旧且顺序与seq连续且seq不受容量约束`() {
        val d = RuntimeDiagnostics(capacity = 3)
        repeat(5) { i -> d.put(key = "k${i + 1}") }
        val snap = d.snapshot()
        assertEquals(listOf("k3", "k4", "k5"), snap.map { it.key }, "溢出丢最旧，snapshot 最旧→最新")
        assertEquals(listOf(3L, 4L, 5L), snap.map { it.seq }, "seq 严格 +1 且不被环回收")
    }

    @Test
    fun `latest取最后n条且越界降级`() {
        val d = RuntimeDiagnostics(capacity = 4)
        repeat(3) { i -> d.put(key = "k${i + 1}") }
        assertTrue(d.latest(0).isEmpty(), "n=0 返回空")
        assertTrue(d.latest(-1).isEmpty(), "n<0 返回空")
        assertEquals(listOf("k2", "k3"), d.latest(2).map { it.key }, "n<size 取尾段且仍最旧→最新")
        assertEquals(listOf("k1", "k2", "k3"), d.latest(9).map { it.key }, "n>size 返回全部")
        // 环绕后的尾段（物理下标回卷路径）
        val wrapped = RuntimeDiagnostics(capacity = 2)
        repeat(3) { i -> wrapped.put(key = "w${i + 1}") }
        assertEquals(listOf(2L, 3L), wrapped.latest(2).map { it.seq })
        assertEquals(listOf(3L), wrapped.latest(1).map { it.seq })
    }

    // ------------------------------------------------------------------
    // Gate 4-5：关闭态零构造 / 开关清空与 seq 重置
    // ------------------------------------------------------------------

    @Test
    fun `关闭态record先于detail求值且零构造`() {
        val d = RuntimeDiagnostics.disabled()
        assertFalse(d.isEnabled, "disabled() 必须是关闭态")
        var detailCalls = 0
        var notified = 0
        d.subscribe { notified += 1 }
        d.put(key = "k", detail = { detailCalls += 1; "boom" })
        assertEquals(0, detailCalls, "关闭态必须在求值 detail thunk 之前返回（契约 §2.1 第 2 条）")
        assertEquals(0, notified, "关闭态不通知订阅者")
        assertTrue(d.snapshot().isEmpty(), "关闭态不分配记录")
        assertEquals("", d.exportJsonLines(), "关闭态导出为零字节")
        d.setEnabled(true)
        d.put(key = "k1", detail = { detailCalls += 1; "ok" })
        assertEquals(1, detailCalls, "开启态才求值 detail")
        assertEquals(1L, d.snapshot().single().seq, "再开必须从 seq=1 重新开始")
        assertEquals(1, notified, "开启后订阅照常收到")
    }

    @Test
    fun `setEnabled关闭清空且再开从seq1重来`() {
        val d = RuntimeDiagnostics(capacity = 4)
        val seen = ArrayList<Long>()
        d.subscribe { seen.add(it.seq) }
        d.put()
        d.put()
        assertEquals(listOf(1L, 2L), seen)
        d.setEnabled(false)
        assertFalse(d.isEnabled)
        assertTrue(d.snapshot().isEmpty(), "关闭必须清空环")
        d.put()
        assertTrue(d.snapshot().isEmpty(), "关闭态不记录")
        assertEquals(listOf(1L, 2L), seen, "关闭态不通知订阅者")
        d.setEnabled(true)
        d.put()
        assertEquals(1L, d.snapshot().single().seq, "再开从 seq=1 重新开始（会话语义）")
        assertEquals(listOf(1L, 2L, 1L), seen, "重开后既有订阅仍在")
        d.setEnabled(true)
        assertEquals(1, d.snapshot().size, "重复 setEnabled(true) 是 no-op，不清环")
    }

    // ------------------------------------------------------------------
    // Gate 6-8：detail 惰性 / 订阅序 / 异常隔离
    // ------------------------------------------------------------------

    @Test
    fun `detail惰性只求值一次且异常被吞为占位串`() {
        val d = RuntimeDiagnostics(capacity = 4)
        var calls = 0
        d.put(key = "lazy", detail = { calls += 1; "v$calls" })
        assertEquals(1, calls, "开启态 record 必须求值一次")
        assertEquals("v1", d.snapshot()[0].detail, "求值结果缓存在记录里")
        d.exportJsonLines()
        d.latest(1)
        d.snapshot()
        assertEquals(1, calls, "快照/导出不得再次求值 detail")
        d.put(key = "boom") { throw IllegalStateException("nope") }
        assertEquals("<detail failed: IllegalStateException>", d.snapshot()[1].detail, "异常必须吞成占位串")
        d.put(key = "none", detail = null)
        assertEquals(null, d.snapshot()[2].detail, "无 detail 记 null")
    }

    @Test
    fun `订阅同步按注册序回调且退订后不再收`() {
        val d = RuntimeDiagnostics(capacity = 4)
        val order = ArrayList<String>()
        val r1 = d.subscribe { order.add("a") }
        val r2 = d.subscribe { order.add("b") }
        d.put()
        assertEquals(listOf("a", "b"), order, "record 返回前已同步回调，且按注册序")
        r1.unregister()
        r1.unregister()
        d.put()
        assertEquals(listOf("a", "b", "b"), order, "退订后不再收到（撤销幂等）")
        r2.unregister()
        d.put()
        assertEquals(listOf("a", "b", "b"), order)
    }

    @Test
    fun `listener抛异常被隔离不影响记录与其他listener`() {
        val d = RuntimeDiagnostics(capacity = 4)
        d.subscribe { throw IllegalStateException("boom") }
        var got = 0
        d.subscribe { got += 1 }
        d.put(key = "still-here")
        assertEquals(1, got, "后续 listener 仍须收到")
        assertEquals(1, d.snapshot().size, "记录仍须入库")
        assertEquals("still-here", d.snapshot()[0].key)
    }
    // ------------------------------------------------------------------
    // Gate 9 / 18：回调在实例锁外（含重入）/ 本轮订阅快照
    // ------------------------------------------------------------------

    @Test
    fun `回调在实例锁外_回调期间别的线程能取快照且可重入record`() {
        val d = RuntimeDiagnostics(capacity = 8)
        val helperStarted = CountDownLatch(1)
        val helperFinished = CountDownLatch(1)
        val helperObserved = AtomicBoolean(false)
        val blockedByInstanceLock = AtomicBoolean(false)
        var innerSeq = -1L
        d.subscribe { rec ->
            if (rec.seq == 1L) {
                // ① 回调期间另一线程必须能进实例锁（锁内派发 ⇒ helper 一直等到本回调返回）
                val helper = thread(name = "rd-lock-probe") {
                    helperStarted.countDown()
                    val snap = d.snapshot()
                    if (snap.isNotEmpty()) helperObserved.set(true)
                    helperFinished.countDown()
                }
                helperStarted.await(2, TimeUnit.SECONDS)
                if (!helperFinished.await(2, TimeUnit.SECONDS)) blockedByInstanceLock.set(true)
                helper.join(2_000)
                // ② 同线程重入 record / snapshot（reentrant monitor 下不挂；锁外派发时必须仍保序）
                d.snapshot()
                d.put(key = "depth2")
                innerSeq = d.snapshot().last().seq
            }
        }
        val recorder = thread(name = "rd-record") { d.put(key = "outer") }
        recorder.join(5_000)
        assertFalse(recorder.isAlive, "record 线程未在 5s 内返回（疑似死锁）")
        assertFalse(
            blockedByInstanceLock.get(),
            "回调期间另一线程取快照被阻塞 ⇒ 回调疑似在实例锁内执行（契约 §2.1 第 5 条）",
        )
        assertTrue(helperObserved.get(), "另一线程必须能读到本轮记录")
        assertEquals(2L, innerSeq, "重入 record 必须正常入库（深度 2）")
        assertEquals(listOf(1L, 2L), d.snapshot().map { it.seq })
    }

    @Test
    fun `本轮订阅快照不受途中退订影响`() {
        val d = RuntimeDiagnostics(capacity = 4)
        val aSeqs = ArrayList<Long>()
        var bCalls = 0
        var rb: Registration? = null
        d.subscribe { rec ->
            aSeqs.add(rec.seq)
            if (rec.seq == 1L) rb?.unregister()
        }
        rb = d.subscribe { bCalls += 1 }
        d.put(key = "one")
        assertEquals(1, bCalls, "本轮派发用快照 ⇒ 途中退订不影响本轮（契约 §2.1 第 4 条）")
        d.put(key = "two")
        assertEquals(1, bCalls, "退订只影响后续记录")
        assertEquals(listOf(1L, 2L), aSeqs)
    }

    // ------------------------------------------------------------------
    // Gate 10-11：JSON lines 逐字节 / 空环 / 逐行
    // ------------------------------------------------------------------

    @Test
    fun `JSON行逐字节精确_含null字段与非ASCII与转义与控制字符`() {
        val d = RuntimeDiagnostics(capacity = 4)
        d.record(
            day = 12L,
            phase = "朝",
            domain = DiagnosticDomain.DECISION,
            packageId = null,
            key = "a\"b\\c",
            outcome = DiagnosticOutcome.REJECTED,
            reasonCode = "R-1",
            detail = { "line1\nline2\ttab\rend\u0001ctl\u001F尾" },
        )
        val expected =
            "{\"seq\":1,\"day\":12,\"phase\":\"朝\",\"domain\":\"DECISION\",\"package\":null," +
                "\"key\":\"a\\\"b\\\\c\",\"outcome\":\"REJECTED\",\"reason\":\"R-1\"," +
                "\"detail\":\"line1\\nline2\\ttab\\rend\\u0001ctl\\u001F尾\"}\n"
        assertEquals(expected, d.exportJsonLines(), "九键固定序 + 转义规则必须逐字节可复现")
    }

    @Test
    fun `空环导出为零字节且两条记录逐行末行带换行`() {
        val d = RuntimeDiagnostics(capacity = 4)
        assertEquals("", d.exportJsonLines(), "空环导出必须是零字节")
        assertEquals(0, d.exportJsonLines().length)
        d.record(1L, "p", DiagnosticDomain.JOB, "pkg", "k1", DiagnosticOutcome.OK, "r1", detail = null)
        d.record(null, null, DiagnosticDomain.SAVE, null, "k2", DiagnosticOutcome.SKIPPED, "r2") { "d2" }
        val out = d.exportJsonLines()
        assertTrue(out.endsWith("\n"), "末行必须带行尾换行")
        val lines = out.split("\n")
        assertEquals(3, lines.size, "两条记录 = 两行 + 拆分出的空尾项")
        assertEquals(
            "{\"seq\":1,\"day\":1,\"phase\":\"p\",\"domain\":\"JOB\",\"package\":\"pkg\"," +
                "\"key\":\"k1\",\"outcome\":\"OK\",\"reason\":\"r1\",\"detail\":null}",
            lines[0],
        )
        assertEquals(
            "{\"seq\":2,\"day\":null,\"phase\":null,\"domain\":\"SAVE\",\"package\":null," +
                "\"key\":\"k2\",\"outcome\":\"SKIPPED\",\"reason\":\"r2\",\"detail\":\"d2\"}",
            lines[1],
        )
        assertEquals("", lines[2], "行尾换行之后没有多余内容")
    }

    // ------------------------------------------------------------------
    // Gate 12：并发不变量
    // ------------------------------------------------------------------

    @Test
    fun `并发record的seq无重复且快照大小封顶且无异常`() {
        val d = RuntimeDiagnostics(capacity = 256)
        val total = 8 * 100
        val seen = Collections.synchronizedSet(HashSet<Long>())
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        d.subscribe { seen.add(it.seq) }
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread(name = "rd-conc-$t") {
                try {
                    start.await()
                    repeat(100) { i -> d.put(key = "t$t-$i", day = i.toLong()) }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertTrue(errors.isEmpty(), "并发 record 抛异常：$errors")
        val snap = d.snapshot()
        assertEquals(256, snap.size, "快照大小 = min(capacity, 800)")
        assertEquals(total, seen.size, "800 条必须全部落过订阅 ⇒ seq 无重复")
        assertEquals((1L..total.toLong()).toSet(), seen.toSet(), "seq 必须是 1..800 的完整序列")
        val seqs = snap.map { it.seq }
        assertEquals(seqs.sorted(), seqs, "快照必须按 seq 升序（最旧→最新）")
        assertEquals(seqs.distinct().size, seqs.size, "快照内 seq 不得重复")
    }
    // ------------------------------------------------------------------
    // Gate 13-16：源码形状 / 枚举序 / 不透明原因码 / 1000 条导出
    // ------------------------------------------------------------------

    @Test
    fun `源码不读墙钟且无静态可变全局`() {
        val text = runtimeDiagnosticsSource()
        for (forbidden in listOf(
            "currentTimeMillis",
            "nanoTime",
            "Instant.now",
            "Instant",
            "LocalDateTime",
            "System.currentTime",
            "java.time",
            "kotlin.random",
            "Random(",
            "android.",
        )) {
            assertFalse(forbidden in text, "RuntimeDiagnostics.kt 不得出现 `$forbidden`（无墙钟 / 纯 JVM）")
        }
        val topLevelObject = Regex("(?m)^(internal |public |private )?object ").findAll(text).toList()
        assertTrue(topLevelObject.isEmpty(), "不得有顶层 object（无静态可变全局）：$topLevelObject")
        val companionBlocks = Regex("(?ms)^    companion object \\{.*?^    \\}").findAll(text).toList()
        assertEquals(1, companionBlocks.size, "必须恰好一个 companion object")
        val companionVars = Regex("\\bvar ").findAll(companionBlocks[0].value).toList()
        assertTrue(companionVars.isEmpty(), "companion object 里不得有 var（无静态可变全局）：$companionVars")
    }

    @Test
    fun `domain与outcome枚举序钉死`() {
        assertEquals(
            listOf("PHASE", "JOB", "HOOK", "INTERACTION", "DECISION", "ACTION", "SAVE"),
            DiagnosticDomain.entries.map { it.name },
            "七域顺序同 era-arch-v61u.md §21.6",
        )
        assertEquals(
            listOf("OK", "SKIPPED", "FAILED", "REJECTED"),
            DiagnosticOutcome.entries.map { it.name },
            "四态顺序同 era-arch-v61u.md §21.6",
        )
    }

    @Test
    fun `未知reasonCode原样保存不校验词汇`() {
        val d = RuntimeDiagnostics(capacity = 4)
        val codes = listOf("", "SOMETHING_THE_ENGINE_NEVER_HEARD_OF", "原因-未定义", "a\"b")
        for (code in codes) d.put(reasonCode = code)
        assertEquals(codes, d.snapshot().map { it.reasonCode }, "reasonCode 是不透明字符串，必须原样保存")
        assertTrue(
            "\"reason\":\"原因-未定义\"" in d.exportJsonLines(),
            "非 ASCII 原因码按 UTF-8 原样导出",
        )
    }

    @Test
    fun `一千条导出按行数断言`() {
        val d = RuntimeDiagnostics(capacity = 1000)
        repeat(1000) { i -> d.put(key = "k$i") }
        val out = d.exportJsonLines()
        assertEquals(1000, out.count { it == '\n' }, "1000 条 = 1000 个行尾换行")
        val lines = out.split("\n")
        assertEquals(1001, lines.size, "拆行后多一个空尾项")
        assertEquals("", lines.last(), "末行换行之后没有多余内容")
        assertEquals(1000, lines.count { it.isNotEmpty() })
        assertTrue(lines[0].startsWith("{\"seq\":1,"), "首行是 seq=1")
        assertTrue(lines[999].startsWith("{\"seq\":1000,"), "末条是 seq=1000")
        val small = RuntimeDiagnostics(capacity = 256)
        repeat(1000) { i -> small.put(key = "k$i") }
        val smallOut = small.exportJsonLines()
        assertEquals(256, smallOut.count { it == '\n' }, "容量 256 只留 256 行")
        assertTrue(smallOut.startsWith("{\"seq\":745,"), "留下的最旧一条 seq=745（1000-256+1）")
    }

    // ------------------------------------------------------------------
    // Gate 17：snapshot 是不可变副本
    // ------------------------------------------------------------------

    @Test
    fun `snapshot是不可变副本`() {
        val d = RuntimeDiagnostics(capacity = 4)
        d.put(key = "a")
        val first = d.snapshot()
        d.put(key = "b")
        assertEquals(1, first.size, "已返回的快照不受后续记录影响")
        assertEquals(listOf("a", "b"), d.snapshot().map { it.key })
        assertNotSame(first, d.snapshot(), "每次 snapshot 都是新副本")
        @Suppress("UNCHECKED_CAST")
        val mutable = first as MutableList<DiagnosticRecord>
        assertFailsWith<UnsupportedOperationException>("快照必须不可变") { mutable.add(first[0]) }
    }
}