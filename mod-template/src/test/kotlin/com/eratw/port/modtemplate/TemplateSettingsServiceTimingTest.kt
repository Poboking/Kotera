package com.eratw.port.modtemplate

import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模板 mod 的第三组作者面闭环：per-mod 设置（`context.settings`）、跨 mod 服务（`context.services`）、
 * 耗时读数（`context.timing`）都不是注释示例，而是 [TemplateMod] 里真跑真读的调用。
 *
 * 台架口径：[TestContext] 对 `services` 是 fail-closed（未装配直接抛）、对 `timing` 是**录制型**
 * （`RecordingTiming`，聚合/降序口径与生产 `ModTimingLedger` 一致；v13 起写侧 `record` / `measure`
 * 本身就是作者面，录制型同样实现），所以这里照 `TemplateShowcaseTest` 的音频面写法，委托真 context、
 * 只把 `services` 换成内存注册表；耗时用 `context.delegate.timingRecorder` 的 `record` / `measure` 自己记账。
 * 作者给自己的 mod 测服务/耗时可以直接抄这段。
 */
class TemplateSettingsServiceTimingTest {

    @Test
    fun manifestDeclaresThreeSettings() {
        val keys = TemplateDeclarations.manifest.settings.map { it.key }

        assertEquals(
            listOf(
                TemplateDeclarations.GREETING_MODE_KEY,
                TemplateDeclarations.GREET_COUNT_KEY,
                TemplateDeclarations.VERBOSE_KEY,
            ),
            keys,
            "manifest 应真声明三项设置（声明了才会出现在设置页、context.settings 才读得到）",
        )
    }

    @Test
    fun greetingLinesFollowSettings() {
        val brief = loadedMod(
            mapOf(
                TemplateDeclarations.GREETING_MODE_KEY to TemplateDeclarations.GREETING_MODE_BRIEF,
                TemplateDeclarations.GREET_COUNT_KEY to "1",
                TemplateDeclarations.VERBOSE_KEY to "false",
            ),
        )
        val briefLines = brief.mod.greetingLines()
        val declared = brief.context.delegate.stringsRecorder.text(TemplateMod.GREETING_KEY, "zh")

        assertEquals(1, briefLines.size, "greetCount=1 应只出一句：$briefLines")
        assertEquals(listOf(declared), briefLines, "brief 语气应原样取 v10 三语文案")
        assertTrue(brief.context.delegate.logEntries.isEmpty(), "verbose=false 不应写诊断日志")

        val formal = loadedMod(
            mapOf(
                TemplateDeclarations.GREETING_MODE_KEY to TemplateDeclarations.GREETING_MODE_FORMAL,
                TemplateDeclarations.GREET_COUNT_KEY to FORMAL_GREET_COUNT.toString(),
                TemplateDeclarations.VERBOSE_KEY to "true",
            ),
        )
        val formalLines = formal.mod.greetingLines()

        assertEquals(FORMAL_GREET_COUNT, formalLines.size, "greetCount 读值应真的改变输出条数")
        assertTrue(
            formalLines.all { it.endsWith("[" + TemplateDeclarations.PACKAGE_ID + "]") },
            "formal 语气应带 [包身份] 落款（读值真的改变了行为）：$formalLines",
        )
        assertTrue(formalLines.none { it == declared }, "formal 与 brief 的输出应不同")
        assertEquals(1, formal.context.delegate.logEntries.size, "verbose=true 应写一条诊断日志")
        val logMessage = formal.context.delegate.logEntries.single().message
        assertTrue(
            logMessage.contains("mode=" + TemplateDeclarations.GREETING_MODE_FORMAL),
            "诊断日志应带实际读到的模式值：$logMessage",
        )
    }

    @Test
    fun servicePublishThenFind() {
        val loaded = loadedMod(emptyMap())
        val type = TemplateGreetingService::class.java

        assertNull(loaded.mod.greetingFromService(), "没人发布 ⇒ find 返回 null（不抛、不等待）")

        loaded.mod.installGreetingService()

        assertEquals(
            "hello from " + TemplateDeclarations.PACKAGE_ID + " (service)",
            loaded.mod.greetingFromService(),
            "publish 之后另一处 find 应拿到同一实现",
        )
        assertEquals(listOf(TemplateDeclarations.PACKAGE_ID), loaded.context.services.owners(type))
        assertTrue(
            loaded.context.services.publishedTypes().contains(type.name),
            "publishedTypes 应含已发布接口",
        )

        loaded.mod.installGreetingService()
        assertEquals(
            listOf(TemplateDeclarations.PACKAGE_ID),
            loaded.context.services.owners(type),
            "同包重复发布 = 幂等替换（不新增发布方）",
        )
    }

    @Test
    fun timingReadsFrameworkSamples() {
        val loaded = loadedMod(emptyMap())
        val timing = loaded.context.delegate.timingRecorder

        assertNull(loaded.mod.slowestScope(), "空账本 ⇒ 无读数")

        timing.record("tick", FAST_NANOS)
        timing.record("lifecycle.onLoad", SLOW_NANOS)

        assertEquals("lifecycle.onLoad", loaded.mod.slowestScope()?.scope, "应取累计耗时最大的一档")
        assertEquals(SLOW_NANOS, loaded.mod.slowestScope()?.totalNanos)

        timing.reset()
        assertNull(loaded.mod.slowestScope(), "reset 后账本清空（作者自测归因窗从零开始）")
    }

    @Test
    fun timingMeasureRecordsBusinessScope() {
        val loaded = loadedMod(emptyMap())
        val timing = loaded.context.delegate.timingRecorder

        assertNull(loaded.mod.slowestScope(), "空账本 ⇒ 无读数")

        val greeting = timing.measure("tick") { loaded.mod.greetingFromService() }
        assertNull(greeting, "没人发布服务，业务段返回 null；耗时要照记")

        assertEquals(setOf("tick"), timing.scopeNames(), "measure 应把业务段记进 scope 集合")
        val tick = timing.samples().single()
        assertEquals("tick", tick.scope)
        assertEquals(1L, tick.count, "同一业务段跑一次应计一次")
        assertEquals(tick.totalNanos, tick.averageNanos, "count=1 时均值 = 总量")

        timing.record("lifecycle.onLoad", SLOW_NANOS)
        assertEquals("lifecycle.onLoad", loaded.mod.slowestScope()?.scope, "更慢的一档应顶上 slowestScope")
    }

    /** 按给定设置值装载一个 mod，并把 fail-closed 的 `services` 换成内存注册表。 */
    private fun loadedMod(
        settings: Map<String, String>,
    ): LoadedMod {
        val world = TestWorld.of(TemplateDeclarations)
        val delegate = world.newContext(TemplateDeclarations.PACKAGE_ID)
        for ((key, value) in settings) {
            delegate.settingsRecorder.declare(key, value)
        }
        val context = AuthorFacesContext(delegate, InMemoryServices())
        val mod = TemplateMod()
        mod.onLoad(context)
        return LoadedMod(mod, context)
    }

    /** `mod` 与它装载时用的 context（要让用例拿到录制面真身）。 */
    private class LoadedMod(
        val mod: TemplateMod,
        val context: AuthorFacesContext,
    )

    /** 委托真台架 context，只把 fail-closed 的 `services` 换成替身（`timing` 直接用台架录制型真身）。 */
    private class AuthorFacesContext(
        val delegate: TestContext,
        override val services: ModServiceRegistry,
    ) : ModContext by delegate

    /** 内存服务注册表：语义对齐生产（同接口只许一个发布方、同包重复发布替换）。 */
    private class InMemoryServices : ModServiceRegistry {

        private val byType = LinkedHashMap<Class<*>, LinkedHashMap<String, Any>>()

        override fun <T : Any> publish(type: Class<T>, service: T, owner: String) {
            byType.getOrPut(type) { LinkedHashMap() }[owner] = service
        }

        override fun <T : Any> find(type: Class<T>): T? {
            val owners = byType[type] ?: return null
            check(owners.size <= 1) {
                "服务 ${type.name} 有多个发布方：${owners.keys}"
            }
            return owners.values.firstOrNull()?.let(type::cast)
        }

        override fun <T : Any> owners(type: Class<T>): List<String> =
            byType[type]?.keys?.toList().orEmpty()

        override fun publishedTypes(): Set<String> = byType.keys.map { it.name }.toSet()
    }

    private companion object {
        const val FORMAL_GREET_COUNT = 3
        const val FAST_NANOS = 1_000L
        const val SLOW_NANOS = 5_000_000L
    }
}
