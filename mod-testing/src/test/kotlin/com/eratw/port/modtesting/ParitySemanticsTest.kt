package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRandomApi
import com.eratw.port.core.mod.ModServiceRegistryImpl
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.ModStringsRegistry
import com.eratw.port.core.mod.apiFor
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.modapi.BoolSettingDecl
import com.eratw.port.modapi.EnumSettingDecl
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.SettingDecl
import com.eratw.port.modapi.StrSettingDecl
import com.eratw.port.modapi.StringsApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 台架四面（settings / services / strings / rng）与生产语义的**正向对齐锁**（Lane paritysem / task-113）。
 *
 * 与 core-vm 侧 `ModTestingParityExtraFacesTest` 的「现状锁」互为反面：那边钉住漂移，
 * 这边断言台架已经复用生产实现 —— 同一读调用序列两侧**逐行同值 / 同文案 / 同序列**。
 * 判据不是「台架≈生产」，而是「台架代码路径就是生产实现」：
 * - settings：声明经 [ModDeclarationAdapter] 翻译 → 生产 [ModSettingsSnapshot] → 其公开工厂；
 * - services：会话级 [ModServiceRegistryImpl] + [ModServiceRegistryImpl.forPackage] 适配器；
 * - strings：生产 `LiveStringsApi`（经 [ModStringsRegistry.apiFor]）；
 * - rng：生产 [ModRandomApi]。
 */
class ParitySemanticsTest {

    private lateinit var world: TestWorld

    @BeforeTest
    fun setUp() {
        world = TestWorld.ofManifests(Fixtures.manifest())
    }

    @AfterTest
    fun tearDown() {
        world.close()
    }

    // ---------------- settings ----------------

    @Test
    fun `settings 声明未落值时四型都读声明默认值`() {
        val settings = world.newContext(Fixtures.PKG).settings

        assertEquals(5L, settings.int(Fixtures.VOLUME), "INT 未落值回退默认 5")
        assertEquals("默认标签", settings.str(Fixtures.LABEL), "STR 未落值回退默认")
        assertEquals(true, settings.bool(Fixtures.AUTO), "BOOL 未落值回退默认 true")
        assertEquals("b", settings.enumName(Fixtures.MODE), "ENUM 未落值回退默认 b")
        assertEquals(Fixtures.SETTING_KEYS, settings.keys, "keys = manifest 声明键集")
        assertTrue(settings.has(Fixtures.VOLUME), "has(已声明) = true")
        assertFalse(settings.has(Fixtures.GHOST), "has(未声明) = false")
    }

    @Test
    fun `settings 坏值回落声明默认值且与生产逐行同值`() {
        val bad = mapOf(
            Fixtures.VOLUME to "99",
            Fixtures.LABEL to "42",
            Fixtures.MODE to "zzz",
            Fixtures.AUTO to "TRUE",
        )
        val production = Fixtures.production(bad)
        val context = world.newContext(Fixtures.PKG)
        bad.forEach { (key, value) -> context.settingsRecorder.declare(key, value) }
        val bench = context.settings

        val prodReads = listOf(
            production.int(Fixtures.VOLUME),
            production.str(Fixtures.LABEL),
            production.bool(Fixtures.AUTO),
            production.enumName(Fixtures.MODE),
        )
        val benchReads = listOf(
            bench.int(Fixtures.VOLUME),
            bench.str(Fixtures.LABEL),
            bench.bool(Fixtures.AUTO),
            bench.enumName(Fixtures.MODE),
        )
        assertEquals(listOf(5L, "42", true, "b"), prodReads, "生产：越界/非候选/非规范布尔一律回落默认")
        assertEquals(prodReads, benchReads, "台架坏值回落与生产逐行同值")
    }

    @Test
    fun `settings 类型不符抛与生产逐字相同的IAE`() {
        val production = Fixtures.production(emptyMap())
        val bench = world.newContext(Fixtures.PKG).settings

        val prodFailure = assertFailsWith<IllegalArgumentException> { production.int(Fixtures.LABEL) }
        val benchFailure = assertFailsWith<IllegalArgumentException> { bench.int(Fixtures.LABEL) }
        assertEquals(prodFailure.message, benchFailure.message, "STR 声明调 int() 的拒绝文案逐字一致")
        assertTrue(prodFailure.message.orEmpty().contains("类型不符"), "文案点是类型不符：${prodFailure.message}")

        val prodBool = assertFailsWith<IllegalArgumentException> { production.bool(Fixtures.VOLUME) }
        val benchBool = assertFailsWith<IllegalArgumentException> { bench.bool(Fixtures.VOLUME) }
        assertEquals(prodBool.message, benchBool.message, "INT 声明调 bool() 的拒绝文案逐字一致")
    }

    @Test
    fun `settings 未声明键抛与生产逐字相同的IAE`() {
        val production = Fixtures.production(emptyMap())
        val bench = world.newContext(Fixtures.PKG).settings

        val prodFailure = assertFailsWith<IllegalArgumentException> { production.int(Fixtures.GHOST) }
        val benchFailure = assertFailsWith<IllegalArgumentException> { bench.int(Fixtures.GHOST) }
        assertEquals(prodFailure.message, benchFailure.message, "未声明键拒绝文案逐字一致（不再有「测试替身」字样）")
        assertEquals(
            "设置项未声明：${Fixtures.PKG}/${Fixtures.GHOST}（未声明的键不可读）",
            benchFailure.message,
            "台架文案 = 生产原文",
        )
    }

    @Test
    fun `settings 无声明的manifest仍保留台架便利面`() {
        val bare = TestWorld.ofManifests(ModManifest(packageId = Fixtures.PKG))
        try {
            val context = bare.newContext(Fixtures.PKG)
            context.settingsRecorder.change("volume", "7")
            assertEquals("7", context.settings.str("volume"), "无声明表时便利面照旧可造值")
            assertEquals(setOf("volume"), context.settings.keys, "keys = 已写入键")
            assertEquals("subtitle", context.settingsRecorder.change("subtitle", "on"), "change 返回键名")
        } finally {
            bare.close()
        }
    }

    // ---------------- services ----------------

    @Test
    fun `services 发布查找owners与publishedTypes与生产同语义`() {
        val production = ModServiceRegistryImpl().forPackage(Fixtures.PKG)
        val context = world.newContext(Fixtures.PKG)
        val service = ProbeServiceImpl("pong")

        production.publish(ProbeService::class.java, service, Fixtures.PKG)
        context.services.publish(ProbeService::class.java, service, Fixtures.PKG)

        assertSame(service, production.find(ProbeService::class.java), "生产同包发布后可查")
        assertSame(service, context.services.find(ProbeService::class.java), "台架同包发布后可查同一实例")
        assertEquals(
            production.owners(ProbeService::class.java),
            context.services.owners(ProbeService::class.java),
            "owners 一致",
        )
        assertEquals(
            production.publishedTypes(),
            context.services.publishedTypes(),
            "publishedTypes 一致",
        )
        assertEquals(
            production.publishedTypes(),
            world.services.publishedTypes(),
            "宿主视角可观测到本包发布（world.services）",
        )
    }

    @Test
    fun `services 冒名发布抛与生产逐字相同的IAE`() {
        val production = ModServiceRegistryImpl().forPackage(Fixtures.PKG)
        val bench = world.newContext(Fixtures.PKG).services

        val prodFailure = assertFailsWith<IllegalArgumentException> {
            production.publish(ProbeService::class.java, ProbeServiceImpl("x"), Fixtures.OTHER_PKG)
        }
        val benchFailure = assertFailsWith<IllegalArgumentException> {
            bench.publish(ProbeService::class.java, ProbeServiceImpl("x"), Fixtures.OTHER_PKG)
        }
        assertEquals(prodFailure.message, benchFailure.message, "冒名发布拒绝文案逐字一致")
        assertTrue(prodFailure.message.orEmpty().contains("冒名"), "文案点名冒名：${prodFailure.message}")
        assertEquals(emptyList(), bench.owners(ProbeService::class.java), "被拒的发布不留痕")
    }

    @Test
    fun `services 多包冲突抛与生产逐字相同的ISE并点名双方`() {
        val production = ModServiceRegistryImpl()
        val prodA = production.forPackage(Fixtures.PKG)
        val prodB = production.forPackage(Fixtures.OTHER_PKG)
        prodA.publish(ProbeService::class.java, ProbeServiceImpl("a"), Fixtures.PKG)
        prodB.publish(ProbeService::class.java, ProbeServiceImpl("b"), Fixtures.OTHER_PKG)

        val benchA = world.newContext(Fixtures.PKG).services
        val benchB = world.newContext(Fixtures.OTHER_PKG).services
        benchA.publish(ProbeService::class.java, ProbeServiceImpl("a"), Fixtures.PKG)
        benchB.publish(ProbeService::class.java, ProbeServiceImpl("b"), Fixtures.OTHER_PKG)

        val prodFailure = assertFailsWith<IllegalStateException> { prodA.find(ProbeService::class.java) }
        val benchFailure = assertFailsWith<IllegalStateException> { benchA.find(ProbeService::class.java) }
        assertEquals(prodFailure.message, benchFailure.message, "多包冲突裁决文案逐字一致")
        assertTrue(prodFailure.message.orEmpty().contains(Fixtures.PKG), "点名第一方")
        assertTrue(prodFailure.message.orEmpty().contains(Fixtures.OTHER_PKG), "点名第二方")
        assertEquals(
            listOf(Fixtures.PKG, Fixtures.OTHER_PKG),
            benchA.owners(ProbeService::class.java),
            "owners 按发布序含双方，跨 context 共享同一张表",
        )
    }

    @Test
    fun `services 同包重复发布替换旧绑定`() {
        val bench = world.newContext(Fixtures.PKG).services
        val first = ProbeServiceImpl("first")
        val second = ProbeServiceImpl("second")

        bench.publish(ProbeService::class.java, first, Fixtures.PKG)
        bench.publish(ProbeService::class.java, second, Fixtures.PKG)

        assertSame(second, bench.find(ProbeService::class.java), "同包同类型重复发布 = 替换（不冲突）")
        assertEquals(listOf(Fixtures.PKG), bench.owners(ProbeService::class.java), "owners 仍只有本包一条")
    }

    @Test
    fun `services world关闭时整包撤销已发布服务`() {
        val local = TestWorld.ofManifests(Fixtures.manifest())
        val a = local.newContext(Fixtures.PKG)
        val b = local.newContext(Fixtures.OTHER_PKG)
        a.services.publish(ProbeService::class.java, ProbeServiceImpl("a"), Fixtures.PKG)
        b.services.publish(MarkerService::class.java, MarkerServiceImpl("b"), Fixtures.OTHER_PKG)
        assertEquals(2, local.services.publishedTypes().size, "关闭前两包各发布一条")

        local.close()

        assertTrue(local.services.publishedTypes().isEmpty(), "close 后 publishedTypes 清空")
        assertEquals(null, a.services.find(ProbeService::class.java), "close 后本包视角查不到（已撤销）")
        assertEquals(null, b.services.find(MarkerService::class.java), "close 后另一包视角也查不到")
        assertTrue(local.services.owners(ProbeService::class.java).isEmpty(), "close 后 owners 为空")
        assertTrue(local.services.owners(MarkerService::class.java).isEmpty(), "close 后 owners 为空（另一包）")
        local.close()
    }

    // ---------------- strings ----------------

    @Test
    fun `strings 重复声明WARN与生产逐字一致且落会话日志`() {
        val registry = ModStringsRegistry()
        val prodWarnings = mutableListOf<String>()
        val production: StringsApi = registry.apiFor(Fixtures.PKG, { "zh" }, { prodWarnings += it })
        val context = world.newContext(Fixtures.PKG)

        production.declare(Fixtures.GREET, "你好", "hello", "こんにちは")
        context.stringsRecorder.declare(Fixtures.GREET, "你好", "hello", "こんにちは")
        production.declare(Fixtures.GREET, "你好2", "hello2", "こんにちは2")
        context.stringsRecorder.declare(Fixtures.GREET, "你好2", "hello2", "こんにちは2")

        val expected = "文案键重复声明，后值覆盖前值：${Fixtures.PKG}.${Fixtures.GREET}"
        assertEquals(listOf(expected), prodWarnings, "生产重复声明走 warn sink")
        assertEquals("你好2", context.strings.of(Fixtures.GREET), "后值覆盖前值（读值同生产）")
        assertEquals(production.of(Fixtures.GREET), context.strings.of(Fixtures.GREET), "覆盖后读值两面一致")
        assertTrue(
            world.logEntries.any { it.message == expected && it.level == "WARN" && it.packageId == Fixtures.PKG },
            "台架 WARN 落会话日志（TestWorld.logEntries）：${world.logEntries}",
        )
        assertTrue(context.logEntries.any { it.message == expected }, "WARN 同时进本 context 日志（生产侧链同位）")
    }

    @Test
    fun `strings 首次声明不产WARN`() {
        val registry = ModStringsRegistry()
        val prodWarnings = mutableListOf<String>()
        registry.apiFor(Fixtures.PKG, { "zh" }, { prodWarnings += it })
            .declare(Fixtures.GREET, "你好", "hello", "こんにちは")
        val context = world.newContext(Fixtures.PKG)
        context.stringsRecorder.declare(Fixtures.GREET, "你好", "hello", "こんにちは")

        assertTrue(prodWarnings.isEmpty(), "生产首发声明无 WARN")
        assertTrue(context.logEntries.isEmpty(), "台架首发声明无 WARN：${context.logEntries}")
        assertTrue(world.logEntries.isEmpty(), "会话日志无 WARN：${world.logEntries}")
        assertEquals(context.stringsRecorder.keys(), registry.keys(Fixtures.PKG), "声明键集与生产同一份")
    }

    // ---------------- rng ----------------

    @Test
    fun `rng 同decisionSeed同包与生产逐位相同序列`() {
        val seeded = TestWorld.ofManifests(Fixtures.manifest(), decisionSeed = Fixtures.SEED)
        try {
            val expected = sequence(ModRandomApi(Fixtures.SEED, Fixtures.PKG))

            assertEquals(expected, sequence(ModRandomApi(Fixtures.SEED, Fixtures.PKG)), "生产同种子同流（自洽）")
            assertEquals(
                expected,
                sequence(seeded.newContext(Fixtures.PKG).rng),
                "台架与生产同 (decisionSeed, packageId) 逐位相同",
            )
            assertEquals(expected, sequence(seeded.newContext(Fixtures.PKG).rng), "台架同包两次 context 同流")
            assertNotEquals(
                expected,
                sequence(seeded.newContext(Fixtures.OTHER_PKG).rng),
                "换包换流（派生材料含 packageId）",
            )
        } finally {
            seeded.close()
        }
    }

    @Test
    fun `rng 台架缺省decisionSeed为零与生产同流`() {
        val bench = world.newContext(Fixtures.PKG).rng
        assertEquals(sequence(ModRandomApi(0L, Fixtures.PKG)), sequence(bench), "缺省 decisionSeed = 0（既有用例逐位不变）")
    }

    @Test
    fun `rng 换decisionSeed换流`() {
        val other = TestWorld.ofManifests(Fixtures.manifest(), decisionSeed = Fixtures.SEED + 1)
        try {
            assertNotEquals(
                sequence(ModRandomApi(Fixtures.SEED, Fixtures.PKG)),
                sequence(other.newContext(Fixtures.PKG).rng),
                "decisionSeed 变则流变",
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun `rng chance端点不耗流且非法入参拒绝文案与生产逐字一致`() {
        assertChanceNoDraw({ ModRandomApi(Fixtures.SEED, Fixtures.PKG) }, "生产")
        assertChanceNoDraw({ world.newContext(Fixtures.PKG).rng }, "台架")

        val production = ModRandomApi(Fixtures.SEED, Fixtures.PKG)
        val bench = world.newContext(Fixtures.PKG).rng
        listOf(
            Triple("nextInt(0)", { production.nextInt(0) }, { bench.nextInt(0) }),
            Triple("nextInt(5,5)", { production.nextInt(5, 5) }, { bench.nextInt(5, 5) }),
            Triple("chance(-1)", { production.chance(-1) }, { bench.chance(-1) }),
            Triple("permutationIndices(-1)", { production.permutationIndices(-1) }, { bench.permutationIndices(-1) }),
        ).forEach { (label, prodCall, benchCall) ->
            val prodFailure = assertFailsWith<IllegalArgumentException>(label) { prodCall() }
            val benchFailure = assertFailsWith<IllegalArgumentException>(label) { benchCall() }
            assertEquals(prodFailure.message, benchFailure.message, "$label 拒绝文案逐字一致")
        }
    }

    private companion object {

        /** 取一段跨各入口的确定性序列（整数 / 置换 / 浮点 / 布尔），用于逐位比对。 */
        fun sequence(rng: com.eratw.port.modapi.RandomApi): List<String> = buildList {
            repeat(6) { add("i" + rng.nextInt(100)) }
            add("p" + rng.permutationIndices(5).joinToString(","))
            add("d" + rng.nextDouble())
            repeat(3) { add("c" + rng.chance(50)) }
        }

        /**
         * 端点不耗流：先在同一实例上打 `chance(0)` / `chance(100)`，再与**另一条未动过端点**的
         * 同种子流逐位比对 —— 两条序列全等即证明端点一次抽数都没消耗。
         */
        fun assertChanceNoDraw(factory: () -> com.eratw.port.modapi.RandomApi, side: String) {
            val subject = factory()
            assertEquals(false, subject.chance(0), "$side: chance(0) = false")
            assertEquals(true, subject.chance(100), "$side: chance(100) = true")
            assertEquals(sequence(factory()), sequence(subject), "$side: 端点调用不推进随机流")
        }
    }
}

/** services 面对拍用的探针接口（同一类型跨包发布才可能冲突）。 */
private interface ProbeService {
    fun value(): String
}

private class ProbeServiceImpl(private val value: String) : ProbeService {
    override fun value(): String = value
}

/** 第二个探针类型：`publishedTypes()` 是类型名集合，两包发同一类型只会算一条。 */
private interface MarkerService {
    fun tag(): String
}

private class MarkerServiceImpl(private val tagValue: String) : MarkerService {
    override fun tag(): String = tagValue
}

/** 设置/文案/manifest 夹具：声明经 mod-api DSL 面（生产同一翻译落点）构造。 */
private object Fixtures {

    const val PKG: String = "test.parity"

    const val OTHER_PKG: String = "test.parity.other"

    const val SEED: Long = 20261111L

    const val VOLUME: String = "volume"

    const val LABEL: String = "label"

    const val AUTO: String = "auto"

    const val MODE: String = "mode"

    const val GHOST: String = "ghost"

    const val GREET: String = "greet.hello"

    val SETTING_KEYS: Set<String> = setOf(VOLUME, LABEL, AUTO, MODE)

    val SETTINGS: List<SettingDecl> = listOf(
        IntSettingDecl(key = VOLUME, default = 5L, min = 0L, max = 10L, title = "音量"),
        StrSettingDecl(key = LABEL, default = "默认标签", title = "标签"),
        BoolSettingDecl(key = AUTO, default = true, title = "自动"),
        EnumSettingDecl(key = MODE, default = "b", candidates = listOf("a", "b", "c"), title = "模式"),
    )

    fun manifest(packageId: String = PKG): ModManifest =
        ModManifest(packageId = packageId, settings = SETTINGS)

    /** 声明清单 → 生产口径（经同一翻译落点）；供两侧对照。 */
    fun coreDeclarations(): List<SettingDeclaration> =
        ModDeclarationAdapter.toDescriptor(manifest()).settings

    /** 生产逐包设置面（与台架同一实现，仅数据不同）。 */
    fun production(persisted: Map<String, String>): com.eratw.port.modapi.SettingsApi =
        ModSettingsSnapshot(PKG, coreDeclarations(), persisted).apiFor()
}
