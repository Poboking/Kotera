package com.eratw.port.core.mod

import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.EnumSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.core.patch.StrSettingDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.RandomApi
import com.eratw.port.modapi.SettingsApi
import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.StringsSpec
import com.eratw.port.modtesting.TestWorld
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
 * 台架四张**此前完全未对拍**的作者面 × 生产实现的语义对拍（Lane DN / task-112）：
 * settings / services / strings / rng。
 *
 * 左边 = `:core-vm` 真宿主实现；右边 = `:mod-testing` 台架（`TestWorld.newContext(...)`）。判据与
 * `ModTestingParityTest` / `ModResourceRecordingParityTest` 同口径：**语义一致的锁一致**；
 * 尚未收敛的写「差异登记」用例钉住现状 —— 每条都精确到可翻转（改台架后该断言应当红，
 * 由后续台架 lane 把断言翻成两侧等价）。
 *
 * ## 翻转记录（paritysem 波 / task-113，2026-11-18）
 *
 * 台架已改为**复用生产实现**（`ModServiceRegistryImpl.forPackage`、`ModSettingsSnapshot.apiFor`、
 * `ModStringsRegistry.apiFor`、`ModRandomApi`），因此下列五条曾登记为漂移的用例已翻成「两侧等价」：
 * `services` 发布查找/owners/publishedTypes、`services` 多包冲突与冒名发布（同类型 + 逐字文案）、
 * `strings` 重复声明 WARN（台架同文案落 `TestWorld.logEntries`）、`rng` 同 `decisionSeed` + 同包逐值同流
 * （台架经 `TestWorld.ofManifests(..., decisionSeed = …)` 注入）、`rng` 非法入参文案逐字一致。
 *
 * **仍保留的现状锁**（settings 三条）：本文件的世界用 `ModManifest(packageId = PKG)` 构造，
 * **没有 settings 声明段**，此时台架`RecordingSettings` 走的是「无声明 ⇒ 便利面」分支
 * （生产无对应面，作者面不存在这个状态）⇒ 未声明键文案、类型协商/坏值回落、声明默认回落
 * 三条仍与生产不同。这属台架**便利面**而非作者可见语义；生产的声明期语义已由
 * `mod-testing` 的 `ParitySemanticsTest` 与 `mod-family`/`mod-memory` 的消费侧用例覆盖。
 *
 * ## 语义对照表（引擎 vs 台架）
 *
 * 逐条格式：`- 语义：引擎 = …；台架 = …；判定 = …`。
 *
 * - settings 合法读：引擎 `PackageSettingsApi`（`core-vm/src/main/kotlin/com/eratw/port/core/mod/
 *   ModSettingsApi.kt:97-124`）按声明类型取值；台架 `RecordingSettings`（`mod-testing/src/main/
 *   kotlin/com/eratw/port/modtesting/RecordingSettings.kt:17-71`）同读法；一致 = 是（合法持久值、
 *   `keys()`、`has()` 逐行同值）。
 * - settings 未声明键：两侧同抛 IAE，但拒绝文案不同：引擎用「包名/键」分隔并写「未声明的键不可读」
 *   （`ModSettingsApi.kt:49-50`），台架用「包名.键」分隔并写「测试替身只认 [RecordingSettings] 里
 *   声明过的键」（`RecordingSettings.kt:67-70`）；判定 = 漂移（文案级，登记）。
 * - settings 类型协商与坏值回落：引擎先 `declarationAs` 判声明类型（`ModSettingsApi.kt:64-74`），
 *   再按声明合法域校验持久值、非法/越界/枚举不在候选/BOOL 非 `true|false` **回退声明默认值**
 *   （`ModSettingsApi.kt:58-62`、`:76-88`）；台架只有「键 → 文本」表，`int()/enumName()` 直接原样
 *   解析（`RecordingSettings.kt:28-42`）；判定 = 漂移（语义级，最贵：作者「越界会回落默认」的
 *   台架绿在真机上不成立，反之台架给的非法值真机根本读不到）。
 * - settings 声明默认值：引擎快照带声明列表，未落值/缺文件即 `defaultText()` 回落
 *   （`ModSettingsApi.kt:24-27`、`:58-62`）；台架 `declare(key, value)` **必须同时给值**
 *   （`RecordingSettings.kt:44-47`），无法表达「声明了但没落值」；判定 = 漂移（能力缺口，登记）。
 * - services：引擎 `ModServiceRegistryImpl` + `PackageServiceRegistry` 适配器（`core-vm/src/main/
 *   kotlin/com/eratw/port/core/mod/ModServiceRegistryImpl.kt`）支持发布/查找/owners/publishedTypes、
 *   多包冲突点名、冒名发布拒绝；台架 `TestContext`（`mod-testing/src/main/kotlin/com/eratw/port/
 *   modtesting/TestContext.kt:63-265`）**未覆写 `services`** ⇒ 走 `ModContext.kt:145-148` 缺省
 *   桩，四入口一律 UOE；判定 = 漂移（能力缺口：作者在台架连 `context.services` 都调不了）。
 * - strings 三语/回落/校验：两侧同一 `ModStringsRegistry` 真实现（台架 `RecordingStrings.kt:25-33`
 *   直接复用，生产 `LiveStringsApi` 薄封装）；一致 = 是（含坏键/坏原文拒绝文案逐字一致）。
 * - strings 重复声明：引擎 `LiveStringsApi.declare` 走 warn sink（`ModStringsRegistry.kt:106-109`，
 *   文案「文案键重复声明，后值覆盖前值：pkg.key」）；台架 `RecordingStrings.declare` 直调 registry
 *   无日志面（`RecordingStrings.kt:27-29`）；判定 = 漂移（日志可见性，登记）。
 * - rng 派生：引擎 `ModRandomApi(decisionSeed, packageId)` + SHA-256 派生种子 + SFMT19937
 *   （`core-vm/src/main/kotlin/com/eratw/port/core/mod/ModRandomApi.kt:24-29`、`:89-99`）；台架
 *   `TestRandom(packageId.hashCode().toLong())` + 自研 LCG（`TestContext.kt:74`、
 *   `TestRandom.kt:14-66`），**不接受 decisionSeed**；判定 = 漂移（数值级，最贵：同种子同序列在
 *   台架上结构性不可满足，作者写死随机值的用例在真机上全不同）。
 * - rng chance 消耗/端点、非法入参 fail-closed 类型、`nextDouble` 值域、`permutationIndices` 置换
 *   性质：两侧结构一致（端点 0/100 不耗流、中间恰耗一次 `nextInt(100)`；越界/非法 size 抛 IAE；
 *   引擎 `ModRandomApi.kt` 的 chance/permutation 与台架 `TestRandom.kt:30-53` 同形）；一致 = 是。
 *   **例外**：非法入参 IAE 文案措辞不同（引擎「需要…实参=」、台架「必须…实际」），另记漂移。
 *
 * ## 现状锁（非契约）口径
 *
 * 标了「现状锁（非契约）」的用例断言的是**当前台架实现**，不是产品契约。paritysem 波已按 lead 裁决
 * 改台架（services 补面、strings 接 warn sink、rng 注入 decisionSeed 并复用 `ModRandomApi`），
 * 对应断言已翻成两侧等价；仅 settings 的「无声明便利面」三条仍按现状登记（见上「翻转记录」）。
 *
 * ## 反向证明
 *
 * 本文件里的 `settings 未声明键…` 与 `rng 同decisionSeed与包名两面逐值同流` 两条同时用作反向证明哨兵：
 * 临时改坏台架侧（`RecordingSettings.raw` 的 fail-closed 回落、`TestContext` 的 rng 播种）后必须红；
 * 改回后 `git status` 零残留（本 lane 不 commit、不 push）。
 *
 * TODO(verify)：本节只对拍 API 级语义。生产 `LiveStringsApi` 经宿主装配后的 WARN 是否带包名前缀、
 * `ModRandomApi` 的 decisionSeed 在真机存档链里如何取，属装配面，本用例直接注入 sink / 直接构造，
 * 台架补面后需在宿主装配面复验一条。
 */
class ModTestingParityExtraFacesTest {

    private lateinit var world: TestWorld

    @BeforeTest
    fun setUp() {
        world = TestWorld.ofManifests(ModManifest(packageId = PKG))
    }

    @AfterTest
    fun tearDown() {
        world.close()
    }

    // ---------------- settings ----------------

    @Test
    fun `settings 合法持久值与声明序键集两面读数一致`() {
        val production = productionSettings(
            persisted = mapOf(
                VOLUME_KEY to "7",
                LABEL_KEY to "改过的标签",
                AUTO_KEY to "false",
                MODE_KEY to "c",
            ),
        )
        val context = world.newContext(PKG)
        mapOf(
            VOLUME_KEY to "7",
            LABEL_KEY to "改过的标签",
            AUTO_KEY to "false",
            MODE_KEY to "c",
        ).forEach { (key, value) -> context.settingsRecorder.declare(key, value) }
        val bench = context.settings

        val prodReads = listOf(
            render { production.int(VOLUME_KEY) },
            render { production.str(LABEL_KEY) },
            render { production.bool(AUTO_KEY) },
            render { production.enumName(MODE_KEY) },
            render { production.has(VOLUME_KEY) },
            render { production.has(GHOST_KEY) },
        )
        val benchReads = listOf(
            render { bench.int(VOLUME_KEY) },
            render { bench.str(LABEL_KEY) },
            render { bench.bool(AUTO_KEY) },
            render { bench.enumName(MODE_KEY) },
            render { bench.has(VOLUME_KEY) },
            render { bench.has(GHOST_KEY) },
        )
        assertEquals(prodReads, benchReads, "同一读调用序列两面必须逐行一致")
        assertEquals(SETTINGS_KEYS, production.keys, "生产 keys = 声明键集")
        assertEquals(production.keys, bench.keys, "keys 集合两面一致")
    }

    /**
     * 现状锁（非契约）：两侧都拒绝未声明键，但拒绝文案不同。台架文案带「测试替身」字样，
     * 作者若断言原文（如日志/崩溃屏抄文案）会与真机不一致。
     */
    @Test
    fun `settings 未声明键两面同抛IAE但文案不同`() {
        val production = productionSettings(persisted = emptyMap())
        val bench = world.newContext(PKG).settings

        val prodFailure = assertFailsWith<IllegalArgumentException> { production.int(GHOST_KEY) }
        val benchFailure = assertFailsWith<IllegalArgumentException> { bench.int(GHOST_KEY) }
        assertTrue(
            prodFailure.message.orEmpty().startsWith("设置项未声明：$PKG/$GHOST_KEY"),
            "生产文案（包名与键用 / 分隔）：${prodFailure.message}",
        )
        assertTrue(
            benchFailure.message.orEmpty().startsWith("设置项未声明：$PKG.$GHOST_KEY"),
            "台架文案（包名与键用 . 分隔）：${benchFailure.message}",
        )
        assertTrue(benchFailure.message.orEmpty().contains("测试替身"), "台架文案点明替身：${benchFailure.message}")
        assertNotEquals(prodFailure.message, benchFailure.message, "现状漂移：未声明键的拒绝文案不同（登记）")
        assertFalse(production.has(GHOST_KEY), "生产 has(未声明) = false")
        assertFalse(bench.has(GHOST_KEY), "台架 has(未声明) = false")
    }

    /**
     * 现状锁（非契约）：台架不 mimick 声明类型与合法域 —— 类型不符照读、越界/非法枚举原样返回、
     * BOOL 非规范文本直接抛（生产是静默回退默认）。修台架（复用生产语义）后本用例应当红。
     */
    @Test
    fun `settings 类型协商与坏值回落两面行为不同`() {
        val production = productionSettings(
            persisted = mapOf(
                VOLUME_KEY to "99",
                LABEL_KEY to "42",
                MODE_KEY to "zzz",
                AUTO_KEY to "TRUE",
            ),
        )
        val context = world.newContext(PKG)
        context.settingsRecorder.declare(VOLUME_KEY, "99")
        context.settingsRecorder.declare(LABEL_KEY, "42")
        context.settingsRecorder.declare(MODE_KEY, "zzz")
        context.settingsRecorder.declare(AUTO_KEY, "TRUE")
        val bench = context.settings

        // 1b 坏值回落：生产按声明合法域回退默认值，台架原样交出持久化文本。
        assertEquals(5L, production.int(VOLUME_KEY), "生产 INT 越界 99>10 回退默认 5")
        assertEquals(99L, bench.int(VOLUME_KEY), "台架 int 原样解析越界值 99（现状漂移：不校验 min/max）")
        assertEquals("b", production.enumName(MODE_KEY), "生产 ENUM 不在候选回退默认 b")
        assertEquals("zzz", bench.enumName(MODE_KEY), "台架 enumName 原样返回 zzz（现状漂移）")
        assertEquals(true, production.bool(AUTO_KEY), "生产 BOOL 非 true|false 回退默认 true")
        val benchBool = assertFailsWith<IllegalArgumentException> { bench.bool(AUTO_KEY) }
        assertTrue(benchBool.message.orEmpty().contains("不是布尔原文"), "台架 BOOL 非规范文本直接抛：${benchBool.message}")

        // 1a 类型不符：生产先判声明类型（不隐式转换），台架只按文本解析。
        val typeFailure = assertFailsWith<IllegalArgumentException> { production.int(LABEL_KEY) }
        assertTrue(typeFailure.message.orEmpty().contains("类型不符"), "生产类型不符文案：${typeFailure.message}")
        assertEquals(42L, bench.int(LABEL_KEY), "台架 int 对 STR 声明照读 42L（现状漂移：无类型协商）")
    }

    /**
     * 现状锁（非契约）：台架的设置替身只有「声明 + 同时给值」一种形态，表达不出「声明了但没落值
     * ⇒ 回退声明默认值」—— 而这正是生产读侧的主路径（缺文件/首装）。
     */
    @Test
    fun `settings 声明未落值时的默认回落台架不可表达`() {
        val production = productionSettings(persisted = emptyMap())
        val context = world.newContext(PKG)
        val bench = context.settings

        assertEquals(5L, production.int(VOLUME_KEY), "生产未落值回退 INT 默认 5")
        assertEquals("默认标签", production.str(LABEL_KEY), "生产未落值回退 STR 默认")
        assertEquals(true, production.bool(AUTO_KEY), "生产未落值回退 BOOL 默认 true")
        assertEquals("b", production.enumName(MODE_KEY), "生产未落值回退 ENUM 默认 b")
        assertTrue(context.settingsRecorder.keys.isEmpty(), "台架没有「只声明不给值」的形态（现状漂移）")
        val benchFailure = assertFailsWith<IllegalArgumentException> { bench.int(VOLUME_KEY) }
        assertTrue(benchFailure.message.orEmpty().contains("测试替身"), "台架把未落值当未声明：${benchFailure.message}")
    }

    // ---------------- services ----------------

    /**
     * 服务面：台架自 paritysem 波起复用生产 `ModServiceRegistryImpl.forPackage(...)`，
     * 发布/查找/owners/publishedTypes 与生产逐条同语义（原为「台架整面未装配」现状锁）。
     */
    @Test
    fun `services 发布查找owners与publishedTypes两面一致`() {
        val shared = ModServiceRegistryImpl()
        val production: ModServiceRegistry = PackageServiceRegistry(shared, PKG)
        val impl = ProbeServiceImpl("pong")
        production.publish(ProbeService::class.java, impl, PKG)

        val bench = world.newContext(PKG).services
        val benchImpl = ProbeServiceImpl("pong")
        bench.publish(ProbeService::class.java, benchImpl, PKG)

        assertSame(impl, production.find(ProbeService::class.java), "生产同包发布后可查到同一实例")
        assertSame(benchImpl, bench.find(ProbeService::class.java), "台架同包发布后可查到同一实例")
        assertEquals(production.owners(ProbeService::class.java), bench.owners(ProbeService::class.java), "owners 按发布序")
        assertEquals(listOf(PKG), bench.owners(ProbeService::class.java), "owners = 发布者包名")
        assertEquals(production.publishedTypes(), bench.publishedTypes(), "publishedTypes 可观测且两面一致")
        assertEquals(setOf(ProbeService::class.java.name), bench.publishedTypes(), "已发布类型集")
    }

    /**
     * 服务面失败语义：多包冲突与冒名发布，台架与生产抛同类型、文案逐字一致、且同样点名双方
     * （原为「台架不可表达」现状锁）。
     */
    @Test
    fun `services 多包冲突与冒名发布两面拒绝语义逐字一致`() {
        val shared = ModServiceRegistryImpl()
        val prodA: ModServiceRegistry = PackageServiceRegistry(shared, PKG)
        val prodB: ModServiceRegistry = PackageServiceRegistry(shared, OTHER_PKG)
        prodA.publish(ProbeService::class.java, ProbeServiceImpl("a"), PKG)
        prodB.publish(ProbeService::class.java, ProbeServiceImpl("b"), OTHER_PKG)
        assertEquals(listOf(PKG, OTHER_PKG), prodA.owners(ProbeService::class.java), "owners 按发布序含双方")

        val conflict = assertFailsWith<IllegalStateException> { prodA.find(ProbeService::class.java) }
        val conflictMessage = conflict.message.orEmpty()
        assertTrue(conflictMessage.contains(PKG) && conflictMessage.contains(OTHER_PKG), "冲突点名双方：$conflictMessage")

        val spoof = assertFailsWith<IllegalArgumentException> {
            prodA.publish(ProbeService::class.java, ProbeServiceImpl("spoof"), OTHER_PKG)
        }
        assertTrue(spoof.message.orEmpty().contains("冒名"), "冒名发布被拒：${spoof.message}")

        // 台架 = 同一个 registry 实现、同一会话级表 ⇒ 同样能表达多包与冒名。
        val benchA = world.newContext(PKG).services
        val benchB = world.newContext(OTHER_PKG).services
        benchA.publish(ProbeService::class.java, ProbeServiceImpl("a"), PKG)
        benchB.publish(ProbeService::class.java, ProbeServiceImpl("b"), OTHER_PKG)

        val benchConflict = assertFailsWith<IllegalStateException> { benchA.find(ProbeService::class.java) }
        assertEquals(conflictMessage, benchConflict.message, "多包冲突文案两面逐字一致")
        val benchSpoof = assertFailsWith<IllegalArgumentException> {
            benchA.publish(ProbeService::class.java, ProbeServiceImpl("spoof"), OTHER_PKG)
        }
        assertEquals(spoof.message, benchSpoof.message, "冒名发布拒绝文案两面逐字一致")
    }

    // ---------------- strings ----------------

    @Test
    fun `strings 三语回落链与has keys language两面一致`() {
        val registry = ModStringsRegistry()
        var prodLanguage = "en"
        val prodWarnings = mutableListOf<String>()
        val production: StringsApi = LiveStringsApi(registry, PKG, { prodLanguage }, { prodWarnings += it })
        val context = world.newContext(PKG)
        val bench = context.strings

        DECLARED_TEXTS.forEach { (key, texts) ->
            production.declare(key, texts[0], texts[1], texts[2])
            context.stringsRecorder.declare(key, texts[0], texts[1], texts[2])
        }

        prodLanguage = "en"
        context.stringsRecorder.languageCode = "en"
        assertEquals("hello", production.of(GREET_KEY), "生产当前语言 en 取 en 档")
        assertEquals(production.of(GREET_KEY), bench.of(GREET_KEY), "en 档两面一致")

        prodLanguage = "ja"
        context.stringsRecorder.languageCode = "ja"
        assertEquals(production.of(GREET_KEY), bench.of(GREET_KEY), "ja 档两面一致")

        prodLanguage = "fr"
        context.stringsRecorder.languageCode = "fr"
        assertEquals("你好", production.of(GREET_KEY), "生产未知语言回落 ZH")
        assertEquals(production.of(GREET_KEY), bench.of(GREET_KEY), "未知语言回落两面一致")

        assertEquals(GHOST_KEY, production.of(GHOST_KEY), "生产未声明键原样返回键")
        assertEquals(production.of(GHOST_KEY), bench.of(GHOST_KEY), "未声明键两面一致")
        assertTrue(production.has(GREET_KEY), "生产 has(已声明) = true")
        assertEquals(production.has(GREET_KEY), bench.has(GREET_KEY), "has(已声明) 两面一致")
        assertEquals(production.has(GHOST_KEY), bench.has(GHOST_KEY), "has(未声明) 两面一致")
        assertEquals(prodLanguage, production.language(), "生产 language 返回注入语言码")
        assertEquals(production.language(), bench.language(), "language 两面一致")
        assertEquals(registry.keys(PKG), context.stringsRecorder.keys(), "已声明键集两面一致")
        assertEquals(DECLARED_TEXTS.keys, registry.keys(PKG), "键集 = 声明键集")
        assertTrue(prodWarnings.isEmpty(), "首发声明不产 WARN：$prodWarnings")
    }

    @Test
    fun `strings 坏键与坏原文声明两面同抛IAE且文案逐字一致`() {
        val registry = ModStringsRegistry()
        val prodWarnings = mutableListOf<String>()
        val production: StringsApi = LiveStringsApi(registry, PKG, { "zh" }, { prodWarnings += it })
        val bench = world.newContext(PKG).strings

        BAD_DECLARATIONS.forEach { decl ->
            val prodFailure = assertFailsWith<IllegalArgumentException> {
                production.declare(decl.key, decl.zh, decl.en, decl.ja)
            }
            val benchFailure = assertFailsWith<IllegalArgumentException> {
                bench.declare(decl.key, decl.zh, decl.en, decl.ja)
            }
            assertEquals(prodFailure.message, benchFailure.message, "坏声明 [${decl.key}] 文案必须逐字一致")
        }
        assertTrue(prodWarnings.isEmpty(), "声明期拒绝不走 WARN：$prodWarnings")
        assertEquals(emptySet(), registry.keys(PKG), "全部坏声明都不落键")
    }

    /**
     * 重复声明：两侧都后值覆盖，且台架自 paritysem 波起也走同一句 WARN（落 [TestWorld.logEntries]），
     * 不再静默（原为「生产 WARN / 台架静默」现状锁）。
     */
    @Test
    fun `strings 重复声明后值覆盖一致且WARN逐字落会话日志`() {
        val registry = ModStringsRegistry()
        val prodWarnings = mutableListOf<String>()
        val production: StringsApi = LiveStringsApi(registry, PKG, { "zh" }, { prodWarnings += it })
        val context = world.newContext(PKG)
        val bench = context.strings

        production.declare(GREET_KEY, "你好", "hello", "こんにちは")
        bench.declare(GREET_KEY, "你好", "hello", "こんにちは")
        production.declare(GREET_KEY, "你好2", "hello2", "こんにちは2")
        bench.declare(GREET_KEY, "你好2", "hello2", "こんにちは2")

        assertEquals("你好2", production.of(GREET_KEY), "生产重复声明后值覆盖")
        assertEquals(production.of(GREET_KEY), bench.of(GREET_KEY), "覆盖后的读值两面一致")
        assertEquals(listOf("文案键重复声明，后值覆盖前值：$PKG.$GREET_KEY"), prodWarnings, "生产重复声明走 warn sink")
        assertEquals(
            prodWarnings,
            context.logEntries.map { it.message },
            "台架 WARN 逐字同文案且落在会话日志（不再静默）",
        )
        assertTrue(context.logEntries.all { it.level == "WARN" && it.packageId == PKG }, "台架 WARN 行带级别与包名")
    }

    // ---------------- rng ----------------

    /**
     * rng 派生：台架自 paritysem 波起复用生产 `ModRandomApi`，并把会话 `decisionSeed` 注入台架
     * （`TestWorld.ofManifests(..., decisionSeed = …)`）⇒ 同 `(decisionSeed, packageId)` 两条流逐值相同
     * （原为「台架按 packageId.hashCode() 派生、不吃 decisionSeed」现状锁）。
     */
    @Test
    fun `rng 同decisionSeed与包名两面逐值同流`() {
        val prodFirst = sequence(ModRandomApi(DECISION_SEED, PKG))
        val prodSecond = sequence(ModRandomApi(DECISION_SEED, PKG))
        assertEquals(prodFirst, prodSecond, "生产同 (decisionSeed, packageId) 同流")

        val seeded = TestWorld.ofManifests(ModManifest(packageId = PKG), decisionSeed = DECISION_SEED)
        try {
            val benchFirst = sequence(seeded.newContext(PKG).rng)
            val benchSecond = sequence(seeded.newContext(PKG).rng)

            assertEquals(benchFirst, benchSecond, "台架同种子同包同流")
            assertEquals(SEQUENCE_LENGTH, prodFirst.size, "取满序列长度")
            assertEquals(prodFirst, benchFirst, "同 decisionSeed + 同包 ⇒ 台架与生产逐值相同（不再结构性不可满足）")
            assertEquals(
                prodFirst,
                sequence(ModRandomApi(DECISION_SEED, PKG)),
                "生产侧可复现（对照组）",
            )
            assertNotEquals(prodFirst, sequence(ModRandomApi(DECISION_SEED, OTHER_PKG)), "生产换包换流")
            assertNotEquals(benchFirst, sequence(seeded.newContext(OTHER_PKG).rng), "台架换包换流")
        } finally {
            seeded.close()
        }
    }

    @Test
    fun `rng chance端点不耗流且内部恰耗一次抽数两面一致`() {
        assertChanceParity({ ModRandomApi(DECISION_SEED, PKG) }, "生产")
        assertChanceParity({ world.newContext(PKG).rng }, "台架")
    }

    /**
     * rng 非法入参：两侧都 fail-closed 抛 IAE，且台架自 paritysem 波复用生产实现 ⇒ 文案逐字一致
     * （原为「措辞不同：生产『需要』/台架『必须』」现状锁）。
     */
    @Test
    fun `rng 非法入参两面同抛IAE且文案逐字一致`() {
        val production = ModRandomApi(DECISION_SEED, PKG)
        val bench = world.newContext(PKG).rng
        val operations: List<(RandomApi) -> Unit> = listOf(
            { rng -> rng.nextInt(0) },
            { rng -> rng.nextInt(5, 5) },
            { rng -> rng.chance(PERCENT_RANGE + 1) },
            { rng -> rng.chance(-1) },
            { rng -> rng.permutationIndices(-1) },
        )
        val prodMessages = mutableListOf<String>()
        val benchMessages = mutableListOf<String>()
        operations.forEach { operation ->
            prodMessages += assertFailsWith<IllegalArgumentException> { operation(production) }.message.orEmpty()
            benchMessages += assertFailsWith<IllegalArgumentException> { operation(bench) }.message.orEmpty()
        }
        assertEquals(prodMessages, benchMessages, "fail-closed 文案两面逐字一致")
        assertTrue(prodMessages.all { it.isNotBlank() }, "每条拒绝都带非空原因：$prodMessages")
    }

    @Test
    fun `rng nextDouble范围 permutation置换与nextInt区间两面一致`() {
        assertRandomProperties({ ModRandomApi(DECISION_SEED, PKG) }, "生产")
        assertRandomProperties({ world.newContext(PKG).rng }, "台架")
    }

    // ---------------- 夹具与断言助手 ----------------

    /** 造生产设置面：声明矩阵固定（与台架用例逐条同名），持久化文本由用例给。 */
    private fun productionSettings(persisted: Map<String, String>): SettingsApi =
        PackageSettingsApi(
            ModSettingsSnapshot(
                packageId = PKG,
                declarations = SETTINGS_DECLARATIONS,
                persistedValues = persisted,
            ),
        )

    /** 台架随机流固定取值序列（同一 factory 两次调用必须得到同一序列）。 */
    private fun sequence(rng: RandomApi, length: Int = SEQUENCE_LENGTH): List<Int> =
        List(length) { rng.nextInt(SEQUENCE_BOUND) }

    /** chance 的消耗语义对拍：端点不耗流、中间恰耗一次 `nextInt(100)`，且与对照流逐值相等。 */
    private fun assertChanceParity(factory: () -> RandomApi, label: String) {
        val zero = factory()
        assertFalse(zero.chance(0), "$label chance(0) 必须恒 false")
        assertEquals(sequence(factory()), sequence(zero), "$label chance(0) 不得消耗随机流")

        val full = factory()
        assertTrue(full.chance(PERCENT_RANGE), "$label chance(100) 必须恒 true")
        assertEquals(sequence(factory()), sequence(full), "$label chance(100) 不得消耗随机流")

        val mid = factory()
        val control = factory()
        assertEquals(control.nextInt(PERCENT_RANGE) < CHANCE_PERCENT, mid.chance(CHANCE_PERCENT), "$label chance 中间档判定")
        assertEquals(sequence(control), sequence(mid), "$label chance 中间档恰耗一次 nextInt(100)")
    }

    /** 值域与结构性质对拍（不比数值，只比契约性质）。 */
    private fun assertRandomProperties(factory: () -> RandomApi, label: String) {
        val doubles = factory()
        repeat(DOUBLE_SAMPLES) {
            val value = doubles.nextDouble()
            assertTrue(value >= 0.0 && value < 1.0, "$label nextDouble 必须落在 [0,1)：$value")
        }
        val ranged = factory()
        repeat(INT_SAMPLES) {
            val value = ranged.nextInt(RANGE_MIN, RANGE_MAX)
            assertTrue(value in RANGE_MIN until RANGE_MAX, "$label nextInt(min,max) 必须落在区间：$value")
        }
        val permutation = factory().permutationIndices(PERM_SIZE)
        assertEquals(PERM_SIZE, permutation.size, "$label permutationIndices 长度 = size")
        assertEquals((0 until PERM_SIZE).toList(), permutation.sorted(), "$label permutationIndices 必须是一个置换")
        assertEquals(0, factory().permutationIndices(0).size, "$label permutationIndices(0) = 空数组")
        assertEquals(
            permutation.toList(),
            factory().permutationIndices(PERM_SIZE).toList(),
            "$label 同流 permutationIndices 可复现",
        )
    }

    /** 把调用结果渲染成可逐行比对的字符串：`ok:<value>` 或 `throw:<类型>:<文案>`。 */
    private inline fun render(block: () -> Any?): String = runCatching(block).fold(
        onSuccess = { value -> "ok:$value" },
        onFailure = { failure -> "throw:${failure::class.simpleName}:${failure.message}" },
    )

    /** 作者面被测接口（消费方只认接口类型，实现类不可见）。 */
    private interface ProbeService {
        fun ping(): String
    }

    private class ProbeServiceImpl(private val label: String) : ProbeService {
        override fun ping(): String = label
    }

    private companion object {
        /** 与台架 `TestWorld.DEFAULT_PACKAGE_ID` 解耦的独立包名（本文件直接构造生产实现）。 */
        const val PKG: String = "parity.extra"
        const val OTHER_PKG: String = "parity.extra.other"

        const val VOLUME_KEY: String = "cfg.volume"
        const val LABEL_KEY: String = "cfg.label"
        const val AUTO_KEY: String = "cfg.auto"
        const val MODE_KEY: String = "cfg.mode"
        const val GHOST_KEY: String = "cfg.ghost"

        const val GREET_KEY: String = "greet"

        /** 声明矩阵：覆盖 INT(带 min/max) / STR / BOOL / ENUM 四种类型协商。 */
        val SETTINGS_DECLARATIONS: List<SettingDeclaration> = listOf(
            IntSettingDeclaration(key = VOLUME_KEY, default = 5L, min = 0L, max = 10L),
            StrSettingDeclaration(key = LABEL_KEY, default = "默认标签"),
            BoolSettingDeclaration(key = AUTO_KEY, default = true),
            EnumSettingDeclaration(key = MODE_KEY, default = "b", candidates = listOf("a", "b", "c")),
        )

        val SETTINGS_KEYS: Set<String> = SETTINGS_DECLARATIONS.map { it.key }.toSet()

        /** 三语矩阵：`key -> [zh, en, ja]`。 */
        val DECLARED_TEXTS: Map<String, List<String>> = linkedMapOf(
            GREET_KEY to listOf("你好", "hello", "こんにちは"),
            "bye" to listOf("再见", "bye", "さようなら"),
        )

        /** 一条非法声明的完整四参形态（两侧同参调用，比对拒绝文案）。 */
        private data class BadDecl(val key: String, val zh: String, val en: String, val ja: String)

        /** 覆盖声明期全部拒绝面：空键 / 键形态 / 键超长 / ZH 原文空白 / EN 原文超长。 */
        val BAD_DECLARATIONS: List<BadDecl> = listOf(
            BadDecl("", "a", "b", "c"),
            BadDecl("Bad Key", "a", "b", "c"),
            BadDecl("a".repeat(StringsSpec.MAX_KEY_LENGTH + 1), "a", "b", "c"),
            BadDecl("ok", " ", "b", "c"),
            BadDecl("ok", "a", "b".repeat(StringsSpec.MAX_TEXT_LENGTH + 1), "c"),
        )

        const val DECISION_SEED: Long = 20261118L
        const val SEQUENCE_LENGTH: Int = 12
        const val SEQUENCE_BOUND: Int = 1_000
        const val PERCENT_RANGE: Int = 100
        const val CHANCE_PERCENT: Int = 50
        const val RANGE_MIN: Int = 3
        const val RANGE_MAX: Int = 9
        const val PERM_SIZE: Int = 10
        const val DOUBLE_SAMPLES: Int = 8
        const val INT_SAMPLES: Int = 32
    }
}
