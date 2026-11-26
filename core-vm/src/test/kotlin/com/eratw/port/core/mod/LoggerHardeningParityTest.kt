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
import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 台架补的「可开关日志硬化模拟」与生产 `PackageLogger`（core-vm 的 `ModContextImpl`）**逐字对拍**。
 *
 * 为什么要有这层对拍：台架缺省不硬化（作者断言原文），但作者若在 `:mod-testing` 里调
 * `context.hardenLogger()` 自己验洪水保护，就必须与真实引擎落同一条行、同一个截断点、同一句
 * 元告警。两侧硬编码各写一份 1000/1000 常量与两段文案，**任何一侧漂移这里就红** —— 这是两条
 * 实现之间唯一的锁。
 *
 * 对拍方式：同一批 `logger.info/warn/error` 调用分别喂给生产 context 的 `logSink` 与硬化后的
 * 台架 context，把台架的结构化 [LogEntry] 按生产行格式重新拼回字符串，然后整列表逐字比较。
 * 断言一律走外部可观测面（生产 sink 行 / 台架 `logEntries`），不读两侧内部计数。
 */
class LoggerHardeningParityTest {

    @Test
    fun `三档日志行逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger ->
                logger.info("hello")
                logger.warn("careful")
                logger.error("boom", IllegalStateException("bad"))
            }
            pair.assertSame()
            assertEquals(
                listOf(
                    "[$PKG][INFO] hello",
                    "[$PKG][WARN] careful",
                    "[$PKG][ERROR] boom cause=IllegalStateException:bad",
                ),
                pair.productionLines(),
            )
        }
    }

    @Test
    fun `超长消息截断逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger -> logger.info("x".repeat(LONG_MESSAGE_CHARS)) }
            pair.assertSame()
            val line = pair.productionLines().single()
            assertTrue(line.endsWith(TRUNCATION_MARK), "截断标记要在行尾：${line.takeLast(20)}")
            val body = line.removePrefix("[$PKG][INFO] ").removeSuffix(TRUNCATION_MARK)
            assertEquals(MESSAGE_LIMIT, body.length, "正文长度 = 单条上限")
        }
    }

    @Test
    fun `恰好等于单条上限的消息不截断`() {
        withPair { pair ->
            pair.runBoth { logger -> logger.info("y".repeat(MESSAGE_LIMIT)) }
            pair.assertSame()
            assertEquals("[$PKG][INFO] " + "y".repeat(MESSAGE_LIMIT), pair.productionLines().single())
        }
    }

    @Test
    fun `超长 error 带 cause 时截断标记与 cause 的先后逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger ->
                logger.error("z".repeat(LONG_MESSAGE_CHARS), IllegalStateException("x"))
            }
            pair.assertSame()
            val line = pair.productionLines().single()
            assertTrue(line.contains(TRUNCATION_MARK), "截断标记要出现")
            assertTrue(
                line.endsWith("cause=IllegalStateException:x"),
                "cause 在截断之后拼接：${line.takeLast(40)}",
            )
        }
    }

    @Test
    fun `空白消息静默丢弃且不占行数额度`() {
        withPair { pair ->
            pair.runBoth { logger ->
                repeat(LINE_LIMIT) { logger.info("") }
                repeat(LINE_LIMIT - 1) { logger.info("line$it") }
                logger.info("   ")
                logger.info("\n\t ")
                logger.info("last")
            }
            pair.assertSame()
            assertEquals(LINE_LIMIT, pair.productionLines().size, "空白不占额度，上限内应落满")
            assertEquals("[$PKG][INFO] last", pair.productionLines().last())
        }
    }

    @Test
    fun `空白消息带 cause 仍落行且行格式逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger -> logger.error("   ", IllegalStateException("bad")) }
            pair.assertSame()
            assertEquals(
                "[$PKG][ERROR] " + "   " + " cause=IllegalStateException:bad",
                pair.productionLines().single(),
            )
        }
    }
}

/**
 * 洪水（行数上限）维度的逐字对拍：1200 条只留 1000 行 + 1 条元告警、抑制计数口径、
 * 越限后各等级同等静默、超长与洪水组合。与 [LoggerHardeningParityTest] 同一套对拍手法。
 */
class LoggerHardeningParityFloodTest {

    @Test
    fun `1200 条洪水只落 1000 行加一条逐字元告警`() {
        withPair { pair ->
            pair.runBoth { logger -> repeat(FLOOD_COUNT) { logger.info("n$it") } }
            pair.assertSame()
            val lines = pair.productionLines()
            assertEquals(LINE_LIMIT + 1, lines.size, "落行数应为 1000 行正文 + 1 条元告警")
            assertEquals("[$PKG][INFO] n${LINE_LIMIT - 1}", lines[LINE_LIMIT - 1])
            assertEquals(expectedNotice(suppressedAtFirstOverflow = 1), lines[LINE_LIMIT])
            assertEquals(FLOOD_COUNT - LINE_LIMIT, pair.bench.suppressedLogLineCount, "被抑制条数")
        }
    }

    @Test
    fun `洪水期空白消息不计入抑制条数`() {
        withPair { pair ->
            pair.runBoth { logger ->
                repeat(LINE_LIMIT) { logger.info("n$it") }
                repeat(BLANK_IN_FLOOD) { logger.info("   ") }
                repeat(OVER_FLOW_CALLS) { logger.info("over$it") }
            }
            pair.assertSame()
            assertEquals(LINE_LIMIT + 1, pair.productionLines().size)
            assertEquals(OVER_FLOW_CALLS, pair.bench.suppressedLogLineCount, "空白在越限判断之前就被丢弃，不计抑制")
        }
    }

    @Test
    fun `越限后各等级都静默且元告警只有一条`() {
        withPair { pair ->
            pair.runBoth { logger ->
                repeat(LINE_LIMIT) { logger.info("n$it") }
                logger.info("late")
                logger.warn("late-warn")
                logger.error("late-error", IllegalStateException("x"))
            }
            pair.assertSame()
            val lines = pair.productionLines()
            assertEquals(LINE_LIMIT + 1, lines.size, "越限后的三种等级都不该再落正文")
            assertEquals(1, lines.count { it.contains("日志超过") }, "元告警只许一条")
            assertEquals(LATE_CALLS, pair.bench.suppressedLogLineCount)
        }
    }

    @Test
    fun `超长消息与洪水组合时落行口径逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger ->
                repeat(LINE_LIMIT - 1) { logger.info("n$it") }
                logger.info("z".repeat(LONG_MESSAGE_CHARS))
                logger.info("after-limit")
            }
            pair.assertSame()
            val lines = pair.productionLines()
            assertEquals(LINE_LIMIT + 1, lines.size)
            assertTrue(lines[LINE_LIMIT - 1].endsWith(TRUNCATION_MARK), "上限内最后一条仍按单条口径截断")
            assertEquals(expectedNotice(suppressedAtFirstOverflow = 1), lines[LINE_LIMIT])
        }
    }

    @Test
    fun `多行制表符与 emoji 消息逐字一致`() {
        withPair { pair ->
            pair.runBoth { logger ->
                logger.info("第一行\n第二行\t尾")
                logger.warn("中文…测试🚀🎮")
                logger.error("")
            }
            pair.assertSame()
            assertEquals("[$PKG][INFO] 第一行\n第二行\t尾", pair.productionLines()[0])
            assertEquals("[$PKG][WARN] 中文…测试🚀🎮", pair.productionLines()[1])
        }
    }
}

private const val PKG = "parity.mod"
private const val MESSAGE_LIMIT = 1000
private const val LINE_LIMIT = 1000
private const val LONG_MESSAGE_CHARS = 5000
private const val FLOOD_COUNT = 1200
private const val BLANK_IN_FLOOD = 50
private const val OVER_FLOW_CALLS = 200
private const val LATE_CALLS = 3
private const val TRUNCATION_MARK = "…(截断)"

/** 生产元告警整行（含包名与等级）；`suppressedAtFirstOverflow` 是首次越限时的抑制计数值。 */
private fun expectedNotice(suppressedAtFirstOverflow: Int): String =
    "[$PKG][WARN] 日志超过 $LINE_LIMIT 条上限，后续同类日志静默（本行之前已抑制 $suppressedAtFirstOverflow 条）"

/** 把台架的结构化记录按生产行格式拼回一行；生产侧格式漂移同样会让对拍变红。 */
private fun renderLine(entry: LogEntry): String {
    val tail = entry.cause?.let { " cause=${it::class.java.simpleName}:${it.message}" } ?: ""
    return "[${entry.packageId}][${entry.level}] ${entry.message}$tail"
}

/**
 * 生产 context（真 `PackageLogger`）与硬化后台架 context 的成对夹具。
 *
 * 两侧各自独立计数（生产按构造函数、台架按 `hardenLogger()`），对同一批调用后比较落行。
 */
private class ParityPair(packageId: String) {

    private val productionLogs = mutableListOf<String>()
    private val world: TestWorld
    val production: ModContextImpl
    val bench: TestContext

    init {
        production = newProductionContext(packageId, productionLogs)
        world = TestWorld.ofManifests(ModManifest(packageId = packageId))
        bench = world.newContext(packageId)
        bench.hardenLogger()
    }

    fun runBoth(block: (ModLogger) -> Unit) {
        block(production.logger)
        block(bench.logger)
    }

    fun productionLines(): List<String> = productionLogs

    fun assertSame() {
        assertEquals(productionLines(), bench.logEntries.map(::renderLine), "台架硬化行必须与生产 sink 行逐字一致")
    }

    fun close() = world.close()
}

private fun withPair(block: (ParityPair) -> Unit) {
    val pair = ParityPair(PKG)
    try {
        block(pair)
    } finally {
        pair.close()
    }
}

private fun newSnapshot(pkg: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    registry.addPackage(
        ModDeclarationAdapter.toDescriptor(
            ModManifest(packageId = pkg, hooks = listOf(HookDecl("COM300", HookPhase.PREFIX))),
        ),
    )
    return registry.freeze()
}

private class ParityRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true
    override fun submit(action: () -> Unit) = action()
}

private fun newProductionContext(packageId: String, logs: MutableList<String>): ModContextImpl = ModContextImpl(
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
    runtime = ParityRuntime(),
    intVarView = { 0L },
    strVarView = { "" },
    registrationSink = {},
)
