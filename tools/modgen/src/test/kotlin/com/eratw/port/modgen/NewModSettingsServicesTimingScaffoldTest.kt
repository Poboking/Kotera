package com.eratw.port.modgen

import eratw.smoke.SmokeDeclarations
import eratw.smoke.SmokeMod
import eratw.smoke.SmokeWorldService
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.TimingApi
import com.eratw.port.modapi.TimingSample
import com.eratw.port.modtesting.TestWorld
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `new-mod` 生成物必须**真示范**三面：设置（声明 + 生命周期现读 + 行为随值变）、
 * 跨 mod 服务（publish + find）、耗时（只读 samples 取最慢 scope 打日志）。
 *
 * 一半是文本断言（作者看得见生成物里就有这行），一半是运行时断言（把生成的 `SmokeMod`
 * 装进 [TestWorld] 真跑 `onLoad`/`onEnable` 并手动 fire 每日任务）—— 只有后者能证明
 * 「声明了但没读」「publish 了没接上」这类半吊子示范不存在。
 */
class NewModSettingsServicesTimingScaffoldTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generated files declare and consume the three faces`() {
        val module = scaffoldModule(tempDir)
        val declarations = declarationsFile(module).readText()
        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()

        // 设置面：三类 DSL 都声明在 manifest 里，键是 Keys 常量（作者改键只改一处）。
        assertTrue(declarations.contains("intSetting("), "缺 intSetting 声明")
        assertTrue(declarations.contains("boolSetting("), "缺 boolSetting 声明")
        assertTrue(declarations.contains("enumSetting("), "缺 enumSetting 声明")
        assertTrue(declarations.contains("range = 0L..100L"), "intSetting 应带闭区间示范")
        assertTrue(
            declarations.contains("candidates = listOf(Keys.ROLL_MODE_RANDOM, Keys.ROLL_MODE_ALWAYS)"),
            "enumSetting 应带候选清单示范",
        )

        // 设置面：生命周期里真的读（不是声明完就闲置）。
        assertTrue(mod.contains("context.settings.bool("), "生命周期没读 bool 设置")
        assertTrue(mod.contains("context.settings.int("), "生命周期没读 int 设置")
        assertTrue(mod.contains("context.settings.enumName("), "生命周期没读 enum 设置")

        // 服务面：publish 与 find 都有实调用，且接口类型是生成物自己的契约。
        assertTrue(mod.contains("context.services.publish("), "没发布服务")
        assertTrue(mod.contains("context.services.find("), "没回读服务")
        assertTrue(mod.contains("interface MyWorldService"), "缺服务契约接口")

        // 耗时面：只读 samples()，取最慢 scope（列表已降序）。
        assertTrue(mod.contains("context.timing.samples()"), "没读耗时读数")
        assertTrue(mod.contains("firstOrNull()"), "应取最慢 scope（samples 已降序）")
    }

    @Test
    fun `generated mod really runs settings services and timing`() {
        val world = TestWorld.of(SmokeDeclarations)
        val bare = world.newContext(SmokeDeclarations.PACKAGE_ID)
        val settings = bare.settingsRecorder
        settings.declare(SmokeDeclarations.Keys.SETTING_UPGRADE_ENABLED, "true")
        settings.declare(SmokeDeclarations.Keys.SETTING_UPGRADE_CHANCE, "0")
        settings.declare(SmokeDeclarations.Keys.SETTING_ROLL_MODE, SmokeDeclarations.Keys.ROLL_MODE_RANDOM)

        val registry = InMemoryServiceRegistry()
        val timing = FixedTiming(
            listOf(
                TimingSample(
                    scope = "tick",
                    count = 2L,
                    totalNanos = 200L,
                    maxNanos = 150L,
                    averageNanos = 100L,
                    lastNanos = 100L,
                ),
            ),
        )
        val context = WiringContext(bare, registry, timing)
        val mod = SmokeMod()

        mod.onLoad(context)
        mod.onEnable()
        // 台架把任务槽名归一成大写（Normalize.kt 的口径），断言同一归一结果。
        assertTrue(
            bare.boundJobIds.contains(SmokeDeclarations.Keys.DAILY_TICK.name.uppercase()),
            "每日任务没绑定：${bare.boundJobIds}",
        )

        // 服务面：onLoad 发布后能从同一注册表 find 回来，owner 是本包。
        val published = assertNotNull(registry.find(SmokeWorldService::class.java), "服务没发布")
        assertEquals(0L, published.tickCount(), "发布时 tickCount 应为 0")
        assertEquals(listOf(SmokeDeclarations.PACKAGE_ID), registry.owners(SmokeWorldService::class.java))
        assertTrue(
            bare.logEntries.any { entry -> entry.message.contains("服务自检通过") },
            "onLoad 的服务回读自检没打日志",
        )

        // 耗时面：onTurnBoundary 读 samples() 并把最慢 scope 打进日志（宿主度量，mod 只读）。
        mod.onTurnBoundary()
        assertTrue(
            bare.logEntries.any { entry -> entry.message.contains("最慢 scope=tick") },
            "onTurnBoundary 没打最慢 scope",
        )

        val actor = world.entities.create(SmokeDeclarations.Keys.ARCHETYPE.name)

        // 设置面：chance=0 ⇒ 不升级（但任务照跑、tickCount 照进）。
        assertTrue(bare.fireJob(SmokeDeclarations.Keys.DAILY_TICK))
        assertEquals(0L, world.entities.getInt(actor, SmokeDeclarations.Keys.LEVEL))
        assertEquals(1L, published.tickCount())

        // 设置面：chance=100 ⇒ 升级 —— 同一实例改值即生效，证明每拍现读而不是缓存在 onLoad。
        settings.declare(SmokeDeclarations.Keys.SETTING_UPGRADE_CHANCE, "100")
        assertTrue(bare.fireJob(SmokeDeclarations.Keys.DAILY_TICK))
        assertEquals(1L, world.entities.getInt(actor, SmokeDeclarations.Keys.LEVEL))

        // 设置面：rollMode=always 时忽略概率（chance=0 也照升）。
        settings.declare(SmokeDeclarations.Keys.SETTING_ROLL_MODE, SmokeDeclarations.Keys.ROLL_MODE_ALWAYS)
        settings.declare(SmokeDeclarations.Keys.SETTING_UPGRADE_CHANCE, "0")
        assertTrue(bare.fireJob(SmokeDeclarations.Keys.DAILY_TICK))
        assertEquals(2L, world.entities.getInt(actor, SmokeDeclarations.Keys.LEVEL))

        // 设置面：enabled=false 时整拍跳过（不升级、不推进 tickCount）。
        settings.declare(SmokeDeclarations.Keys.SETTING_UPGRADE_ENABLED, "false")
        assertTrue(bare.fireJob(SmokeDeclarations.Keys.DAILY_TICK))
        assertEquals(2L, world.entities.getInt(actor, SmokeDeclarations.Keys.LEVEL))
        assertEquals(3L, published.tickCount())

        // 卸载：注册撤销、状态清空（服务撤销由宿主生命周期负责，这里只验 mod 侧清账）。
        mod.onDisable()
        mod.onUnload()
    }

    @Test
    fun `check mod reports zero errors and warnings on generated skeleton`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        assertEquals(emptyList(), findingsOf(module))
        val out = ByteArrayOutputStream()
        val exit = ModCheck.runCli(arrayOf(module.toString()), PrintStream(out), PrintStream(System.err))
        assertEquals(0, exit)
        assertTrue(
            out.toString().contains("check-mod: 0 error(s), 0 warning(s)"),
            "生成物应 0 error(s) 0 warning(s)，实际输出：$out",
        )
    }
}

/** 只做「按接口类型存/取」的最小服务注册表：替身不实现多发布方冲突等宿主语义。 */
private class InMemoryServiceRegistry : ModServiceRegistry {
    private val entries = LinkedHashMap<Class<*>, Pair<Any, String>>()

    override fun <T : Any> publish(type: Class<T>, service: T, owner: String) {
        entries[type] = service to owner
    }

    override fun <T : Any> find(type: Class<T>): T? = entries[type]?.let { entry -> type.cast(entry.first) }

    override fun <T : Any> owners(type: Class<T>): List<String> = listOfNotNull(entries[type]?.second)

    override fun publishedTypes(): Set<String> = entries.keys.map { type -> type.name }.toSet()
}

/** 固定读数：测试要断言「读到最慢 scope 并打日志」，不依赖真宿主的记账时序。 */
private class FixedTiming(private val samples: List<TimingSample>) : TimingApi {
    override fun samples(): List<TimingSample> = samples

    override fun record(scope: String, nanos: Long) = Unit

    override fun reset() = Unit
}

/**
 * 把台架 context 包一层，补上 [TestContext] 尚未装配的两面：`services` 与 `timing`。
 *
 * 其余成员原样委托（实体/任务/设置/日志都走台架真语义），所以日志断言仍看 `bare.logEntries`。
 */
private class WiringContext(
    delegate: ModContext,
    override val services: ModServiceRegistry,
    override val timing: TimingApi,
) : ModContext by delegate
