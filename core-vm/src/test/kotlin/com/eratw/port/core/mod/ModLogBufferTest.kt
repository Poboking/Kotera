package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 每包最近日志环（MODDX2 二期 G2）：容量逐出、空白不进环、截断后入环、抑制元告警只一次、
 * 两包隔离、只读副本与读数缝同源。
 *
 * 记账口径见 [MOD_LOG_BUFFER_CAPACITY] 的 KDoc：环只记 PackageLogger.emit 已实际发出的行，
 * 与 sink 收到的行逐字节一致 —— 所以用例一律经 logger 触发后比对 [ModContextImpl.recentLogLines]
 * 缝（或直读 `logBuffer`），不 mock emit 内部。
 */
class ModLogBufferTest {

    private fun newSnapshot(pkg: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(packageId = pkg, hooks = listOf(HookDecl("COM300", HookPhase.PREFIX))),
            ),
        )
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun newContext(packageId: String, logs: MutableList<String>): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = newSnapshot(packageId),
        entityKernel = EntityKernel(
            schemas = listOf(
                ComponentSchema(
                    packageId = packageId,
                    name = "Stats",
                    fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
                ),
            ),
            relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
            archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
        ),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
        intVarView = { 0L },
        strVarView = { "" },
        registrationSink = {},
    )

    @Test
    fun `环超容逐出最旧且时序保持`() {
        val ring = ModLogBuffer()
        repeat(60) { ring.append("l$it") }
        val lines = ring.recentLines()
        assertEquals(50, lines.size, "容量 = 最近 50 行")
        assertEquals((10..59).map { "l$it" }, lines, "最旧的 l0..l9 被逐出，余序旧到新")
    }

    @Test
    fun `recentLines 是只读副本后续写入不改变已取快照`() {
        val ring = ModLogBuffer()
        ring.append("a")
        val snap = ring.recentLines()
        ring.append("b")
        assertEquals(listOf("a"), snap, "快照在取出那一刻冻结")
        assertEquals(listOf("a", "b"), ring.recentLines(), "环本身继续推进")
    }

    @Test
    fun `空白消息不进环与 emit 丢弃同拍`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("")
        ctx.logger.info("   ")
        ctx.logger.info("\n\t ")
        assertTrue(ctx.recentLogLines().isEmpty(), "空白行 sink 上不存在，环上也不能存在：${ctx.recentLogLines()}")
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `超长消息按截断后内容入环且与 sink 逐字节一致`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("x".repeat(5000))
        val lines = ctx.recentLogLines()
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.startsWith("[modA][INFO] "), line.take(20))
        assertTrue(line.endsWith("…(截断)"), "环记的是截断后的内容：${line.takeLast(20)}")
        val body = line.removePrefix("[modA][INFO] ").removeSuffix("…(截断)")
        assertEquals(1000, body.length, "正文长度 = 单条上限")
        assertEquals(logs.single(), lines.single(), "环与 sink 同拍同内容")
    }

    @Test
    fun `抑制元告警入环且只一次且位于环尾`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        repeat(1005) { ctx.logger.info("n$it") }
        val lines = ctx.recentLogLines()
        assertEquals(50, lines.size, "环按容量 50 逐出，不随 1001 条 sink 行增长")
        assertEquals(1, lines.count { it.contains("日志超过") }, "元告警只入环一次")
        assertTrue(lines.last().startsWith("[modA][WARN] 日志超过"), "元告警是最后发出的行：${lines.last()}")
        assertEquals("[modA][INFO] n951", lines.first(), "环内最旧 = 预算内最后 49 条正文行的首条")
        assertEquals("[modA][INFO] n999", lines[48])
        assertEquals(1001, logs.size, "sink 落行数照旧 = 1000 正文 + 1 元告警（既有抑制语义未被环改动）")
    }

    @Test
    fun `两包日志环互不串`() {
        val logsA = mutableListOf<String>()
        val logsB = mutableListOf<String>()
        val a = newContext("modA", logsA)
        val b = newContext("modB", logsB)
        repeat(60) { a.logger.info("a$it") }
        b.logger.info("b0")
        b.logger.info("b1")
        val aLines = a.recentLogLines()
        assertEquals(50, aLines.size)
        assertTrue(aLines.all { it.startsWith("[modA][INFO] a") }, "A 环只含 A 的行：${aLines.take(2)}")
        assertEquals("[modA][INFO] a10", aLines.first())
        assertEquals(listOf("[modB][INFO] b0", "[modB][INFO] b1"), b.recentLogLines(), "B 环独立计数与记录")
    }

    @Test
    fun `读数缝与环直读同源`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("hello")
        ctx.logger.warn("careful")
        assertEquals(listOf("[modA][INFO] hello", "[modA][WARN] careful"), ctx.recentLogLines())
        assertEquals(ctx.logBuffer.recentLines(), ctx.recentLogLines(), "缝 = buffer 的只读投影")
    }
}
