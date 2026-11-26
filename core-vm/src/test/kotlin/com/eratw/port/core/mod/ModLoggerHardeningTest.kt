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
 * 作者日志面（[com.eratw.port.modapi.ModLogger]）的三条硬化：超长截断、空白丢弃、洪水抑制。
 *
 * 为什么要硬化：mod 的日志走宿主 sink（Android 上最终到 logcat / 应用内日志缓冲）。作者在
 * `onFrame` 或每日一拍里打一行，长跑就是每秒几十行 —— 不截断会撑爆单行、不抑制会把 logcat
 * 刷空并把玩家设备拖慢，最后表现成「装了这个 mod 就卡」而不是「这个 mod 打日志太狠」。
 *
 * 断言一律走注入的 sink（外部可观测面），不读内部计数 —— 计数是实现细节，改了不该弄红。
 * 上限口径：单条 1000 Char（截断点追加 `…(截断)`）、每个 context 1000 行（第 1001 行起静默 +
 * 一条元告警）。
 */
class ModLoggerHardeningTest {

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
    fun `三档行格式带包名与等级且 cause 渲染成简单类名加消息`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("hello")
        ctx.logger.warn("careful")
        ctx.logger.error("boom", IllegalStateException("bad"))
        assertEquals("[modA][INFO] hello", logs[0])
        assertEquals("[modA][WARN] careful", logs[1])
        assertEquals("[modA][ERROR] boom cause=IllegalStateException:bad", logs[2])
    }

    @Test
    fun `超长消息截断到 1000 个字符并追加截断标记`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("x".repeat(5000))
        val line = logs.single()
        assertTrue(line.startsWith("[modA][INFO] "), line.take(40))
        assertTrue(line.endsWith("…(截断)"), "截断标记要在行尾：${line.takeLast(20)}")
        val body = line.removePrefix("[modA][INFO] ").removeSuffix("…(截断)")
        assertEquals(1000, body.length, "正文长度 = 单条上限")
    }

    @Test
    fun `恰好等于上限的消息不截断`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("y".repeat(1000))
        val line = logs.single()
        assertEquals("[modA][INFO] " + "y".repeat(1000), line)
    }

    @Test
    fun `空白消息静默丢弃且不占行数额度`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.info("")
        ctx.logger.info("   ")
        ctx.logger.info("\n\t ")
        assertTrue(logs.isEmpty(), "空白消息不该落到 sink：$logs")
        // 只打错等级也不该因为空白而吃掉额度：上限内仍能落满 1000 条有内容的行。
        repeat(1000) { ctx.logger.info("line$it") }
        assertEquals(1000, logs.size)
    }

    @Test
    fun `每 context 最多 1000 行且只打一条洪水元告警`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        repeat(1005) { ctx.logger.info("n$it") }
        val notice = logs.filter { it.contains("日志超过") }
        assertEquals(1, notice.size, "元告警只能有一条：$notice")
        assertTrue(notice.single().startsWith("[modA][WARN] "), notice.single())
        assertTrue(notice.single().contains("1000"), "元告警要点名上限：${notice.single()}")
        // 1000 条正文 + 1 条元告警；第 1001~1005 条被静默。
        assertEquals(1001, logs.size, "落行数应为 1000 + 1 条元告警")
        assertEquals("[modA][INFO] n999", logs[999])
        assertEquals(notice.single(), logs[1000])
    }

    @Test
    fun `不同 packageId 各自计数互不影响`() {
        val logsA = mutableListOf<String>()
        val logsB = mutableListOf<String>()
        val a = newContext("modA", logsA)
        val b = newContext("modB", logsB)
        repeat(1000) { a.logger.info("a$it") }
        b.logger.info("still fine")
        assertEquals(1000, logsA.size)
        assertEquals(listOf("[modB][INFO] still fine"), logsB)
    }

    @Test
    fun `超长但带 cause 的 error 也走同一截断口径`() {
        val logs = mutableListOf<String>()
        val ctx = newContext("modA", logs)
        ctx.logger.error("z".repeat(2000), IllegalStateException("x"))
        val line = logs.single()
        assertTrue(line.contains("…(截断)"), "截断标记要出现")
        assertTrue(line.endsWith("cause=IllegalStateException:x"), "cause 走截断之后拼接：${line.takeLast(40)}")
    }
}
