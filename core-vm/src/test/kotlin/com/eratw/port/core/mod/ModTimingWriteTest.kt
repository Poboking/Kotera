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
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.TimingApi
import com.eratw.port.modapi.TimingSample
import com.eratw.port.modapi.measure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 作者侧耗时写入口闸（RT2 P1 写侧扩展；`TimingApi.record` / `TimingApi.measure`）。
 *
 * 钉住六件事：
 * 1. 作者 scope 与引擎测量点进**同一张表**，count / total / max / average / last 口径完全一致；
 * 2. `measure` 返回值与异常原样透传，异常路径照样落账（`try/finally`）；
 * 3. 写入口校验：空白 / 超长 scope、负耗时一律 `IllegalArgumentException` 且不留半笔账；
 * 4. 单包 scope 数到换成只 WARN 一次并忽略新 scope，既有 scope 继续累加（表不无界增长）；
 * 5. 上限按包独立，`reset` / `resetPackage` 只清本包并复位上限计数；
 * 6. 未接线时 `record` 显式 fail-closed（不是静默丢弃），接口缺省实现同样显式抛。
 */
class ModTimingWriteTest {

    @Test
    fun `作者record与引擎测量点同表且口径一致`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modA")
        host.install(listOf("modA" to quietMod()))
        val timing = host.contextOf("modA")!!.timing
        timing.record("author.custom", 100)
        timing.record("author.custom", 300)

        val samples = timing.samples()
        val authored = samples.single { it.scope == "author.custom" }
        assertEquals(2L, authored.count)
        assertEquals(400L, authored.totalNanos)
        assertEquals(300L, authored.maxNanos)
        assertEquals(200L, authored.averageNanos)
        assertEquals(300L, authored.lastNanos)
        assertTrue(
            samples.any { it.scope == "lifecycle.onLoad" },
            "引擎测量点与作者 scope 同表：${samples.map { it.scope }}",
        )
        assertEquals(
            samples.map { it.totalNanos }.sortedDescending(),
            samples.map { it.totalNanos },
            "作者 scope 与引擎 scope 同表按总耗时降序",
        )
    }

    @Test
    fun `作者measure值异常都原样透传且异常照记`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modM")
        host.install(listOf("modM" to quietMod()))
        val timing = host.contextOf("modM")!!.timing

        assertEquals(42, timing.measure("author.ok") { 42 })
        val ex = assertThrows<IllegalStateException> {
            timing.measure("author.ok") { error("故意抛") }
        }
        assertEquals("故意抛", ex.message)

        val sample = timing.samples().single { it.scope == "author.ok" }
        assertEquals(2L, sample.count, "异常那一次也算一次")
        assertTrue(sample.totalNanos >= 0L)
    }

    @Test
    fun `measure非法scope到落账时才抛且block已执行`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modB")
        host.install(listOf("modB" to quietMod()))
        val timing = host.contextOf("modB")!!.timing

        var ran = false
        assertThrows<IllegalArgumentException> { timing.measure("  ") { ran = true } }
        assertTrue(ran, "记录期校验在 finally 才跑，block 已经执行（与生产 measureValue 同口径）")
        assertTrue(timing.samples().none { it.scope.isBlank() })
    }

    @Test
    fun `record非法参数IAE且不留半笔账`() {
        val ledger = ModTimingLedger()
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "tick", -1) }
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "", 1) }
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "\t\n", 1) }
        val tooLong = "s".repeat(ModTimingLedger.MAX_SCOPE_LENGTH + 1)
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", tooLong, 1) }
        assertTrue(ledger.snapshot().isEmpty(), "非法写入不得留下半笔账")
    }

    @Test
    fun `scope长度上限边界128可记129拒`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "s".repeat(ModTimingLedger.MAX_SCOPE_LENGTH), 7)
        assertEquals(1, ledger.snapshot().size)
        val tooLong = "s".repeat(ModTimingLedger.MAX_SCOPE_LENGTH + 1)
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", tooLong, 7) }
        assertEquals(1, ledger.snapshot().size, "超限那条不得留下半笔账")
    }

    @Test
    fun `单包scope数到顶只WARN一次并忽略新scope`() {
        val logs = mutableListOf<String>()
        val ledger = ModTimingLedger(logSink = { logs += it })
        repeat(ModTimingLedger.MAX_DISTINCT_SCOPES) { index ->
            ledger.record("capMod", "scope.$index", 1)
        }
        assertEquals(ModTimingLedger.MAX_DISTINCT_SCOPES, ledger.forPackage("capMod").size)

        ledger.record("capMod", "scope.extra", 9)
        assertEquals(ModTimingLedger.MAX_DISTINCT_SCOPES, ledger.forPackage("capMod").size, "新 scope 被忽略")
        assertEquals(1, logs.size)
        val warn = logs.single()
        assertTrue(warn.contains("capMod") && warn.contains("scope.extra"), "WARN 要点名包与 scope：$warn")
        assertTrue(warn.contains(ModTimingLedger.MAX_DISTINCT_SCOPES.toString()), "WARN 要报上限：$warn")

        ledger.record("capMod", "scope.0", 4)
        assertEquals(2L, ledger.forPackage("capMod").single { it.scope == "scope.0" }.count)
        ledger.record("capMod", "scope.more", 9)
        assertEquals(1, logs.size, "每包只 WARN 一次")
        assertEquals(ModTimingLedger.MAX_DISTINCT_SCOPES, ledger.forPackage("capMod").size)
    }

    @Test
    fun `上限按包独立resetPackage复位本包上限计数`() {
        val logs = mutableListOf<String>()
        val ledger = ModTimingLedger(logSink = { logs += it })
        repeat(ModTimingLedger.MAX_DISTINCT_SCOPES) { index ->
            ledger.record("capA", "a.$index", 1)
        }
        ledger.record("capB", "b.0", 5)
        assertEquals(ModTimingLedger.MAX_DISTINCT_SCOPES, ledger.forPackage("capA").size)
        assertEquals(1, ledger.forPackage("capB").size, "别的包不受 A 包上限影响")

        ledger.resetPackage("capA")
        assertTrue(ledger.forPackage("capA").isEmpty())
        ledger.record("capA", "a.after-reset", 3)
        assertEquals(1, ledger.forPackage("capA").size, "resetPackage 复位上限计数，腾出位置")
        assertEquals(1, ledger.forPackage("capB").size, "只清本包")
    }

    @Test
    fun `作者reset只清本包读数`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modX", "modY")
        host.install(listOf("modX" to quietMod(), "modY" to quietMod()))
        val timingX = host.contextOf("modX")!!.timing
        val timingY = host.contextOf("modY")!!.timing
        timingX.record("author.reset", 10)
        timingY.record("author.reset", 20)

        timingX.reset()
        assertTrue(timingX.samples().none { it.scope == "author.reset" })
        assertEquals(1L, timingY.samples().single { it.scope == "author.reset" }.count)
    }

    @Test
    fun `未接线时record与measure显式失败不静默丢弃`() {
        val host = newHost(mutableListOf(), null, "modNoTiming")
        host.install(listOf("modNoTiming" to quietMod()))
        val timing = host.contextOf("modNoTiming")!!.timing

        val ex = assertThrows<UnsupportedOperationException> { timing.record("author.x", 1) }
        assertTrue(ex.message!!.contains("耗时归因面未装配"), "must name the reason: ${ex.message}")
        assertTrue(ex.message!!.contains("modNoTiming"), "must name the package: ${ex.message}")

        var ran = false
        assertThrows<UnsupportedOperationException> { timing.measure("author.x") { ran = true } }
        assertTrue(ran, "写入口未装配也要先把 block 跑完再在 finally 抛（不吞 block 的执行）")
    }

    @Test
    fun `TimingApi缺省record显式抛而非静默`() {
        val bare = object : TimingApi {
            override fun samples(): List<TimingSample> = emptyList()

            override fun reset() = Unit
        }
        val ex = assertThrows<UnsupportedOperationException> { bare.record("x", 1) }
        assertTrue(ex.message!!.contains("耗时写入面未装配"), "缺省实现必须 fail-closed：${ex.message}")
    }

    // ---------------------------------------------------------------- 夹具

    private fun quietMod(): EraMod = object : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(
        logs: MutableList<String>,
        ledger: ModTimingLedger?,
        vararg packages: String,
    ): ModRuntimeHost {
        val host = ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )
        host.timing = ledger
        return host
    }

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }
}
