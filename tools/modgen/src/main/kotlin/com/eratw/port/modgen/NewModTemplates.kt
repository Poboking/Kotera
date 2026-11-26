package com.eratw.port.modgen

import java.nio.file.Path

/**
 * `new-mod` 的一个生成条目：文件写内容，目录只建目录（资源包骨架 `*.assets/` 用）。
 *
 * 条目（而不仅是文件）参与冲突判定与 `--check` 预览：目标目录路径上已有同名**文件**时算冲突。
 */
internal sealed interface ScaffoldEntry {
    /** 模块内相对路径，`/` 分隔。 */
    val relativePath: String
}

/**
 * 一份要写的文件：模块内相对路径 + UTF-8 文本内容。
 *
 * 内容一律**以换行结尾**，保证同一输入两次生成的字节完全一致（幂等由 `--force` 路径复用同一份内容）。
 */
internal data class ScaffoldFile(override val relativePath: String, val content: String) : ScaffoldEntry

/** 一个要建的目录（不写内容；已存在且是目录时不算冲突）。 */
internal data class ScaffoldDirectory(override val relativePath: String) : ScaffoldEntry

/**
 * 模板把 [ScaffoldRequest.packageId] 直接派生成三语文案键的**最长后缀**（含前导点）：
 * onLoad 的 `declare`、onEnable 的 `altText`、onTurnBoundary 的 `of`/`has` 都用
 * 「包身份 + 本后缀」这一个键（`MENU_LABEL` 的 `.menu` 更短、不构成上限约束）。
 *
 * 单一真源：[NewModScaffold] 用它把 `--package` 长度上限钉在运行期文案键上限
 * （[ModStringsLimits.MAX_KEY_LENGTH]）之内——派生键超限会让骨架「生成成功」而 mod
 * 装载 onLoad 即抛 IAE。运行期键口径 = 全小写 `[a-z0-9_.-]+`、≤ 64 字符
 * （ModStringsRegistry 的 KEY_PATTERN / requireValidKey），所以脚手架同时拒大写包身份。
 * 改派生后缀时同步 [NewModScaffoldTest] 的边界用例。
 */
internal const val LONGEST_STRINGS_KEY_SUFFIX: String = ".greeting"

/**
 * 校验完成的脚手架输入。
 *
 * [packageId] 同时是 mod 包身份（manifest/存档分片/日志归属）与 Kotlin 包名：
 * `new-mod` 不做「包身份 vs 包名」两套参数，避免作者把两者写岔；含 `.` 以外的分隔符不支持，
 * 校验会明确拒绝（见 [NewModScaffold] 的 `--package` 说明）。
 */
internal data class ScaffoldRequest(
    val packageId: String,
    val kotlinPackage: String,
    val className: String,
    val moduleName: String,
    val outputDir: Path,
)

/**
 * 最小可编译 mod 骨架的模板：五个文件 + 一个资源包目录（`<packageId>.assets/` 里带一份示例文件）。
 *
 * 形状对着仓库里两份**能编译的真实样板**写：`mod-template`（手写键 + 生命周期四段）与
 * `mod-family`（`api(project(":mod-api"))` + `src/generated/kotlin` 源目录 + JUnit 测试面）。
 * 这里不引入任何新依赖：生成物只依赖 `:mod-api`，测试面只依赖 `kotlin("test")` + JUnit 5。
 *
 * 资源目录按**运行期口径**命名：`ModResourceStore` 用 `packageId + ".assets"` 派生包资源根
 * （`mods/rt2/<packageId>.assets/`），所以骨架目录是 `<packageId>.assets/`，不是类名前缀。
 * 目录本身也作为 [ScaffoldDirectory] 条目参与冲突判定与 `--check` 预览。
 *
 * 为什么不生成 `src/generated/kotlin` 里的访问器：那需要 `:tools:modgen-accessors` 的生成任务
 * 按 schema 产出，属于作者接线面（见 `schemas/<Name>.schema.tsv` 的 KDoc 与交付报告）。
 * 手写的 `object Keys` 与 schema 的 field 行**线名逐一对齐**，接上生成器后可以整体替换。
 *
 * 大段模板拆成顶层私有函数：detekt 的 LongMethod 默认上限 60 行，整段塞进一个函数会直接红，
 * 而本仓禁 `@Suppress`、禁改阈值 —— 拆函数是唯一合规出口。
 */
internal object NewModTemplates {

    /** 全部生成条目（顺序稳定：build 文件 → 声明 → 生命周期 → schema → 测试 → 资源目录）。 */
    fun files(request: ScaffoldRequest): List<ScaffoldEntry> {
        val sourceRoot = request.kotlinPackage.replace('.', '/')
        val assetsRoot = request.packageId + ModResourceLimits.ASSET_DIR_SUFFIX
        return listOf(
            ScaffoldFile("build.gradle.kts", buildGradleKts),
            ScaffoldFile(
                "src/main/kotlin/$sourceRoot/${request.className}Declarations.kt",
                declarationsHeader(request) + declarationsKeys(request) + declarationsManifest(request),
            ),
            ScaffoldFile(
                "src/main/kotlin/$sourceRoot/${request.className}Mod.kt",
                // 生命周期实现（六段回调，能力面示范各落一处、全是真调用）：
                modHeader(request) +
                    modOnLoad(request) +
                    modOnEnable(request) +
                    modOnDisableUnload(request) +
                    modNotifications(request) +
                    modDailyTick(request) +
                    serviceContract(request),
            ),
            ScaffoldFile("schemas/${request.className}.schema.tsv", schema(request)),
            ScaffoldFile(
                "src/test/kotlin/$sourceRoot/${request.className}ModTest.kt",
                modTestHeader(request) +
                    modTestManifestAssertions(request) +
                    modTestFacesWiring(request) +
                    modTestFacesBehavior(request) +
                    modTestFacesBehaviorTail(request),
            ),
            ScaffoldDirectory(assetsRoot),
            ScaffoldFile("$assetsRoot/README.txt", assetsReadme(request)),
        )
    }

    /**
     * 随包 README.txt 的小节标题（按生成正文出现顺序，逐字冻结）。
     *
     * 与 [assetsReadmeResources] / [assetsReadmeAudio] / [assetsReadmeFaces] / [assetsReadmeWiring] /
     * [assetsReadmeCodePackage] / [assetsReadmeInstall] 里的标题行一一对应；对拍闸
     * `TemplateScaffoldParityTest` 拿这份清单核 README 正文，防「README 写了某能力但骨架没生成 /
     * 骨架加了能力但 README 没写」。
     * 改 README 小节标题时两处一起改，否则闸红。末条「装」不是能力面，是五步总纲最后一步的
     * 玩家侧指引（导入 / 重开一局生效 / 诊断读数），同样按小节标题冻结。
     */
    internal val README_CAPABILITY_SECTIONS: List<String> = listOf(
        "打包与引擎下限（minApi，可选）",
        "三语文案（v10）",
        "图片元素（浮层，可选）",
        "音频（v11）",
        "设置面（per-mod settings）",
        "服务面（跨 mod Kotlin 服务）",
        "耗时面（读 + 写，v13 起写侧也是作者面）",
        "日志面（context.logger，作者排查用，不是玩家可见文案）",
        "互动面（interactions.registerHook，示例见 <Name>Mod.kt 的 onEnable 与 Declarations 的 manifest）",
        "事件 / 钩子 / 相位 / 任务面（示例见 <Name>Mod.kt 的 onEnable 与 Declarations 的 manifest）",
        "第三方代码包（路 B：自带 classes.dex，可选）",
        "装（导入→生效→验证）",
    )

    /**
     * 模块构建脚本：照 `mod-family/build.gradle.kts` 的形状（`api(project(":mod-api"))`、
     * 生成物源目录、JUnit 5）。`libs` 是根工程版本目录，新模块 `settings.gradle.kts` include 后即可用。
     */
    private val buildGradleKts: String = """
        |// new-mod 生成的最小可编译骨架。第一步：根 settings.gradle.kts 里 include 本模块（CLI 输出有 NEXT 行），
        |// 然后 ./gradlew :<模块目录>:test 跑生成物自检；声明面三面一致的自检命令见 Declarations 头注。
        |// 随包资源放模块根的 <packageId>.assets/（图片/音频/文本都进这），名字规则见目录内 README.txt。
        |
        |plugins {
        |    alias(libs.plugins.kotlin.jvm)
        |}
        |
        |kotlin {
        |    jvmToolchain(libs.versions.jvmTarget.get().toInt())
        |}
        |
        |// mod 作者工具链：接入 :tools:modgen-accessors 的生成物放这里。
        |// 目录不存在不影响编译；接上生成器后 schema 改动由 checkXxxAccessors 闸判过期。
        |kotlin.sourceSets.named("main") {
        |    kotlin.srcDir("src/generated/kotlin")
        |}
        |
        |// 依赖方向铁律：mod 只依赖 :mod-api，不碰 :core-vm 等引擎内部。
        |dependencies {
        |    api(project(":mod-api"))
        |
        |    testImplementation(kotlin("test"))
        |    testImplementation(libs.junit.jupiter)
        |    // 作者面台架（TestWorld + 录制替身），只在测试里用、不进产物 —— 生成物的行为断言靠它真跑。
        |    testImplementation(project(":mod-testing"))
        |    testRuntimeOnly(libs.junit.platform.launcher)
        |}
        |
        |tasks.withType<Test>().configureEach {
        |    useJUnitPlatform()
        |}
        |
    """.trimMargin()

    /** 生成物测试的包声明 / import / 前两条清单断言（拆函数只为压 detekt 行数上限）。 */
    private fun modTestHeader(request: ScaffoldRequest): String = """
        |package ${request.kotlinPackage}
        |
        |import com.eratw.port.modapi.BuiltinPhase
        |import com.eratw.port.modapi.EventResult
        |import com.eratw.port.modapi.FieldType
        |import com.eratw.port.modapi.HookResult
        |import com.eratw.port.modapi.InteractionPhase
        |import com.eratw.port.modtesting.TestWorld
        |import kotlin.test.Test
        |import kotlin.test.assertEquals
        |import kotlin.test.assertFalse
        |import kotlin.test.assertTrue
        |
        |/** 生成器的自检：清单必须真的声明了骨架里的那几项，改声明漏同步时这里先红。 */
        |class ${request.className}ModTest {
        |
        |    @Test
        |    fun manifestKeepsPackageIdentity() {
        |        assertEquals(
        |            ${request.className}Declarations.PACKAGE_ID,
        |            ${request.className}Declarations.manifest.packageId,
        |        )
        |        assertEquals(${request.className}Declarations.VERSION, ${request.className}Declarations.manifest.version)
        |    }
        |
        |    @Test
        |    fun manifestDeclaresComponentFields() {
        |        val manifest = ${request.className}Declarations.manifest
        |        val component = manifest.components.single {
        |            it.name == ${request.className}Declarations.Keys.COMPONENT.name
        |        }
        |        assertEquals(listOf(FieldType.INT, FieldType.STR), component.fields.map { it.type })
        |        assertEquals(
        |            listOf(
        |                ${request.className}Declarations.Keys.LEVEL.name,
        |                ${request.className}Declarations.Keys.NICKNAME.name,
        |            ),
        |            component.fields.map { it.name },
        |        )
        |    }
    """.trimMargin()

    /** 清单声明面（原型 / 关系 / 任务槽 + 三档设置键）：拆出来只为压 detekt 行数上限。 */
    private fun modTestManifestAssertions(request: ScaffoldRequest): String = """
        |
        |    @Test
        |    fun manifestDeclaresArchetypeRelationAndJob() {
        |        val manifest = ${request.className}Declarations.manifest
        |        assertTrue(manifest.archetypes.any { it.name == ${request.className}Declarations.Keys.ARCHETYPE.name })
        |        assertTrue(manifest.relationships.any { it.name == ${request.className}Declarations.Keys.KNOWS.name })
        |        assertTrue(manifest.jobs.any { it.jobId == ${request.className}Declarations.Keys.DAILY_TICK.name })
        |        // 设置面：三档声明都要在清单里（设置页据此渲染，context.settings 据此放行读）。
        |        assertEquals(
        |            setOf(
        |                ${request.className}Declarations.Keys.SETTING_UPGRADE_ENABLED,
        |                ${request.className}Declarations.Keys.SETTING_UPGRADE_CHANCE,
        |                ${request.className}Declarations.Keys.SETTING_ROLL_MODE,
        |            ),
        |            manifest.settings.map { it.key }.toSet(),
        |        )
        |        // 钩子面：槽必须先声明，运行期 register 只认这里声明过的槽。
        |        assertTrue(
        |            manifest.hooks.any { it.targetKey == ${request.className}Declarations.Keys.HOOK_TARGET.name },
        |            "钩子槽没在 manifest 里声明",
        |        )
        |    }
        |
        |    @Test
        |    fun newModBuildsLifecycleInstance() {
        |        assertTrue(${request.className}Declarations.newMod() is ${request.className}Mod)
        |    }
        |}
        |
    """.trimMargin()

    /**
     * 行为面自检（上半）：台架装配 + 事件 / 钩子 / 相位三面真派发断言。
     *
     * 生成物测试用 `:mod-testing` 的 [TestWorld] 与录制替身（RecordingEvents / RecordingHooks ...），
     * 断言的是「真调用」而不是文本里出现了某字符串 —— 只改注释、漏了注册时这里先红。
     */
    private fun modTestFacesWiring(request: ScaffoldRequest): String = """
        |
        |/**
        | * 行为面自检：用 :mod-testing 的 TestWorld + 录制替身，断言各能力面**真的接上了** ——
        | * 只改注释、漏了真调用时这里先红。台架与生产同一份 rng / settings / timing 实现。
        | */
        |class ${request.className}FacesTest {
        |
        |    @Test
        |    fun eventsHooksAndPhasesAreReallyWired() {
        |        val world = TestWorld.of(${request.className}Declarations)
        |        try {
        |            val context = world.newContext(${request.className}Declarations.PACKAGE_ID)
        |            val mod = ${request.className}Declarations.newMod() as ${request.className}Mod
        |            mod.onLoad(context)
        |            mod.onEnable()
        |            assertTrue(
        |                context.boundJobIds.any { it.endsWith("DAILYTICK") },
        |                "每日任务没绑上：" + context.boundJobIds,
        |            )
        |            // 事件面：登记过的处理器在派发时真跑并回 EventResult。
        |            assertEquals(
        |                listOf(EventResult.CONTINUE),
        |                context.dispatchEvent(${request.className}Declarations.Keys.EVENT_KEY),
        |                "事件处理器没被调用",
        |            )
        |            // 钩子面：manifest 声明的槽在派发时被调用、回 HookResult。
        |            assertEquals(
        |                HookResult.Continue,
        |                context.dispatchHook(${request.className}Declarations.Keys.HOOK_TARGET.name),
        |                "钩子处理器没被调用",
        |            )
        |            // 相位面：内建 DAY_START 派发到本 mod 的订阅者。
        |            assertEquals(
        |                1,
        |                context.firePhase(BuiltinPhase.DAY_START.name),
        |                "DAY_START 相位没派发到本 mod",
        |            )
        |            // 互动面 / 音频面：manifest 声明的相位槽真注册了钩子；骨架不随包示例音频 ⇒ play 降级回
        |            // false 并落 WARN（契约不抛），onDisable 必须调 stop() 收尾。
        |            assertEquals(
        |                listOf(InteractionPhase.PRE),
        |                context.interactionsRecorder.registeredPhases,
        |                "互动相位钩子没注册",
        |            )
        |            assertFalse(mod.themeAudioStarted, "缺随包音频时 play 必须回 false 不抛")
        |            assertTrue(
        |                context.audioRecorder.warnings.any { it.contains("audio/theme.ogg") },
        |                "缺随包音频没落 WARN：" + context.audioRecorder.warnings,
        |            )
        |            mod.onDisable()
        |            assertEquals(1, context.audioRecorder.stopCount, "onDisable 没调 audio.stop()")
        |            mod.onUnload()
        |        } finally {
        |            world.close()
        |        }
        |    }
        |
    """.trimMargin()

    /** 行为面自检（下半之一）：界面 / 设置 / rng / 耗时四面的行为断言（每面都是真读数）。 */
    private fun modTestFacesBehavior(request: ScaffoldRequest): String = """
        |    @Test
        |    fun uiSettingsRngAndTimingDriveRealBehavior() {
        |        val world = TestWorld.of(${request.className}Declarations)
        |        try {
        |            val context = world.newContext(${request.className}Declarations.PACKAGE_ID)
        |            val actor = world.entities.create(${request.className}Declarations.Keys.ARCHETYPE.name)
        |            val mod = ${request.className}Declarations.newMod()
        |            mod.onLoad(context)
        |            mod.onEnable()
        |            // 界面面：图片元素按注册顺序进宿主快照（资源文件缺失时渲染降级，不影响登记）。
        |            assertEquals(
        |                listOf(${request.className}Declarations.Keys.BANNER_IMAGE),
        |                context.uiImages.map { it.resourceName },
        |            )
        |            // 可见性谓词现求值：tickCount = 0 时交互项收起。
        |            assertTrue(!context.uiRecorder.visibleKeys().contains(${request.className}Declarations.Keys.MENU_LABEL))
        |            // 设置面读值改行为：概率 0 不升级、概率 100 必升级（各跑一次每日任务）。
        |            context.settingsRecorder.change(${request.className}Declarations.Keys.SETTING_UPGRADE_CHANCE, "0")
        |            assertTrue(context.fireJob(${request.className}Declarations.Keys.DAILY_TICK))
        |            assertEquals(0L, context.entities.getInt(actor, ${request.className}Declarations.Keys.LEVEL.name))
        |            context.settingsRecorder.change(${request.className}Declarations.Keys.SETTING_UPGRADE_CHANCE, "100")
        |            assertTrue(context.fireJob(${request.className}Declarations.Keys.DAILY_TICK))
        |            assertEquals(
        |                1L,
        |                context.entities.getInt(actor, ${request.className}Declarations.Keys.LEVEL.name),
        |                "100% 概率下实体没升级",
        |            )
        |            // 耗时面（写侧）：measure 包的业务段落进本包读数（scope 名与 Mod.kt 对齐）。
        |            assertTrue(
        |                context.timingRecorder.scopeNames().contains("author.${request.packageId}.dailyTick"),
        |                "业务段没记耗时：" + context.timingRecorder.scopeNames(),
        |            )
        |
    """.trimMargin()

    /** 行为面自检（下半之二）：交互项点击 + 持久化回写 + 类闭合。 */
    private fun modTestFacesBehaviorTail(request: ScaffoldRequest): String = """
        |            // 跑过每日任务后谓词变真：交互项出现，点击回调经录制替身派发并取本包三语文案。
        |            assertTrue(context.uiRecorder.visibleKeys().contains(${request.className}Declarations.Keys.MENU_LABEL))
        |            assertTrue(
        |                context.uiRecorder.click(${request.className}Declarations.Keys.MENU_LABEL).handled,
        |                "交互项点击没命中",
        |            )
        |            assertTrue(context.logEntries.any { it.message.contains("示例菜单项") }, "点击回调没执行")
        |            // 持久化面：onDisable 把累计计数回写到 storage。
        |            mod.onDisable()
        |            assertEquals("2", context.storage.get(${request.className}Declarations.Keys.TICK_COUNT.name))
        |        } finally {
        |            world.close()
        |        }
        |    }
        |
        |    @Test
        |    fun interactionAndAudioFacesDriveRealBehavior() {
        |        val world = TestWorld.of(${request.className}Declarations)
        |        try {
        |            // 音频面：随包音频存在 ⇒ play 真受理并回 true，plays 记下名字 / 循环 / 生效音量。
        |            world.resource(
        |                ${request.className}Declarations.PACKAGE_ID,
        |                "audio/theme.ogg",
        |                byteArrayOf(0x4F, 0x67, 0x67, 0x53),
        |            )
        |            val context = world.newContext(${request.className}Declarations.PACKAGE_ID)
        |            val actor = world.entities.create(${request.className}Declarations.Keys.ARCHETYPE.name)
        |            val mod = ${request.className}Declarations.newMod() as ${request.className}Mod
        |            mod.onLoad(context)
        |            mod.onEnable()
        |            assertTrue(mod.themeAudioStarted, "随包音频在时 play 应受理并回 true")
        |            assertEquals(listOf("audio/theme.ogg"), context.audioRecorder.plays.map { it.resourceName })
        |            assertEquals(0.8f, context.audioRecorder.plays.single().volume, "音量应按 play 实参生效")
        |            assertTrue(context.audioRecorder.warnings.isEmpty(), "有资源还落 WARN：" + context.audioRecorder.warnings)
        |            // 互动面：manifest 声明的 PRE 相位钩子在台架相位机里真跑，回调把命中数 +1。
        |            assertEquals(listOf(InteractionPhase.PRE), context.interactionsRecorder.registeredPhases)
        |            val run = context.interactionsRecorder.runOnce(actor, listOf(actor))
        |            assertTrue(run.failures.isEmpty(), "互动钩子抛了：" + run.failures)
        |            assertEquals(1, mod.interactionPreHits, "互动钩子回调没执行")
        |            // 音频面收尾：onDisable 真调 stop()（幂等）。
        |            mod.onDisable()
        |            assertEquals(1, context.audioRecorder.stopCount, "onDisable 没调 audio.stop()")
        |        } finally {
        |            world.close()
        |        }
        |    }
        |}
        |
    """.trimMargin()

    /**
     * 资源包目录里的示例文件：内容就是作者要遵守的读取口径（README 本身就是一个可读资源）。
     *
     * 目录名与上限跟运行期 `ModResourceStore` 同源（[ModResourceLimits]，文本哨兵测试防漂移）。
     */
    private fun assetsReadme(request: ScaffoldRequest): String =
        assetsReadmeResources(request) + assetsReadmeAudio + assetsReadmeFaces + assetsReadmeWiring +
            assetsReadmeCodePackage(request) + assetsReadmeInstall

    /**
     * 装段（五步总纲的最后一步，玩家侧）：设置页导入卡 / 口上市场 / 改启停与排序要重开一局 /
     * 设置页诊断读数。只写运行期既有口径，措辞逐字对齐运行期语义（锚 = 下面的行内清单，进本
     * KDoc、不进 README 正文）：
     * - 生效口径 = `ModEnablement.kt:13-14`（装配在会话构造期一次性完成，改完要重开一局；
     *   与总闸、装载优先级同口径）；
     * - 导入卡名 = ModImportCopy.kt「补丁（Mod）导入」（cardTitle）；预览先于导入 = SettingsActivity
     *   组合处的「只读预览」注释；入口在设置页 = ModImportUi.kt:34；
     * - 市场卡名 = MarketCopy.kt「市场」/「打开市场」/「装进游戏」；市场已装包走既有导入链 =
     *   SettingsActivity.kt 的 marketGameBridge KDoc；
     * - 总闸未开时导入不生效 = ModImportCopy.kt 的 modRuntimeOffHint；
     * - 诊断卡 = SettingsStrings.kt 的 modDiagnosticsTitle 一族 + ModGateDiagnostics 快照七读数；
     * - 装载失败点名 = SettingsActivity.kt 的 ModManageFailures（按整张已装清单渲染）。
     *
     * 注意排版：trimMargin 会剥掉 raw string 的**首尾空行**——所以「与上一节的分隔空行」靠开头
     * 的单个 `|` 行补出（首个空行被剥、这个 `|` 成为分隔行），「内容以换行结尾」的产物合同靠
     * 末行的 `|` 补出；且正文行不得以全角右括号收尾（对拍闸 readmeSections 按顶格 + 「）」收尾
     * 抽小节标题，正文行误收尾会被当成小节、闸红）。
     */
    private val assetsReadmeInstall: String = """
        |
        |装（导入→生效→验证）
        |pack-mod 出的 zip 装进游戏：设置页「补丁（Mod）导入」卡选 zip，先只读预览、确认后才编译落盘；
        |口上市场（设置页「市场」入口卡，点「打开市场」）下载的包先装进本机市场库，点「装进游戏」
        |走同一条导入链。生效：装配在会话构造期一次性完成，所以导入之后、在「已安装的 Mod」列表里
        |改启停、在「Mod 装载优先级」卡里改排序，都要重开一局才生效（与「Mod 运行时」总闸同口径；
        |总闸没开时导入的内容也不生效，开闸后同样要重开一局）。验证：设置页「Mod 运行时诊断」卡
        |（只读、可复制）看总闸开关、本局 Mod 运行时是否生效、安装 / 启用 / 读不出计数与存档侧车，
        |「已安装的 Mod」列表会点名装载失败的包。per-mod 设置不用重开：保存后本局下一拍生效
        |（见「设置面」）。
        |
    """.trimMargin()

    /**
     * 第三方代码包（路 B）的作者口径：自带 classes.dex 的 zip 怎么编、内层清单怎么写、怎么出包、
     * 为什么默认不生效。正文只写运行期既有语义（锚 = 下面清单，进本 KDoc、不进 README 正文）：
     * - 总闸 = `UiPrefs.kt` 的 `KEY_MOD_DEX_LOADER`（"mod.dex.loader"）+ `DEFAULT_MOD_DEX_LOADER =
     *   false`；信任确认 = `ModDexGate` 的 `<packageId>.trusted` 标记（`ModDexGate.kt` 的
     *   `TRUST_MARKER_SUFFIX`）；同进程 / 无沙箱 = SettingsStrings 的 `modTrustHonestNote`；
     * - 内层清单字段与校验顺序 = `PackModCode.problemOf`；键序 = `PackModCodeJson.encodeInnerManifest`；
     * - `code.mod` 条目名与上限 = `PackModCode.kt` 的 `CODE_MOD_ENTRY`（4 MiB）/
     *   `MAX_CODE_MOD_MANIFEST_BYTES`（4096）；用法错 / 退出码 = `PackModOptions.entrypointProblem`；
     * - 入口类要求与六种失败 = `ModDexModuleLoader` 的六个 outcome；形状闸 =
     *   `PackModCodeManifest.CODE_ENTRYPOINT_SHAPE`；
     * - 多版本拒猜 = `ModDexLoader` 的「盘上存在多个版本的代码产物…拒绝猜用哪一个」。
     */
    private fun assetsReadmeCodePackage(request: ScaffoldRequest): String = """
        |
        |第三方代码包（路 B：自带 classes.dex，可选）
        |普通 mod 只有 ERB、声明与资源，代码全由引擎内建 API 提供；路 B 让你把自己的 Kotlin 代码
        |（一个 classes.dex）随包分发，在引擎同一进程里跑。它默认不生效，玩家要手动放行两步：
        |① 打开「第三方代码包」总闸（键 mod.dex.loader，缺省关）；② 在设置页「第三方代码包」卡里
        |对本包点「确认信任」。两步都做完仍要重开一局才装载。注意没有沙箱、也不是独立进程：
        |代码与应用同权限同进程，崩溃会带走整局游戏 —— 只在你了解来源时才信任。
        |
        |第一步：编 classes.dex
        |用 Android SDK 的 d8 把本模块编译产物打成 dex（Android Studio 的 Build 产物里也有
        |classes 目录可直接喂）。dex 里必须真有入口类（见第三步），否则装载时报「类不在 dex」：
        |
        |  <Android SDK>/build-tools/<版本>/d8 --lib <Android SDK>/platforms/android-34/android.jar \
        |      --output build/dex build/classes/kotlin/main
        |
        |产物是 build/dex/classes.dex。本骨架的入口类 FQN（第三步原样用）：
        |  ${request.kotlinPackage}.${request.className}Mod
        |
        |第二步：写内层清单 manifest.json
        |和 classes.dex 一起打成内层 zip（两个条目都在 zip 根级）。manifest.json 是严格 JSON，
        |键序照下面顺序（version / apiVersion 可省，apiVersion 是数字）；packageId 必须等于
        |Declarations 里的 PACKAGE_ID，entrypoint 必须与第三步的 --entrypoint 逐字相等：
        |
        |  {
        |    "packageId": "${request.packageId}",
        |    "version": "0.1",
        |    "entrypoint": "${request.kotlinPackage}.${request.className}Mod"
        |  }
        |
        |第三步：出包
        |  modgen pack-mod <模块目录> --out <包.zip> --code <内层 zip> \
        |      --entrypoint ${request.kotlinPackage}.${request.className}Mod
        |pack-mod 把内层 zip 原样收成外层 zip 根级的 code.mod 条目，并逐项校验：code.mod ≤ 4 MiB、
        |manifest.json ≤ 4096 字节、packageId 与模块身份一致、entrypoint 形状为
        |<小写包名>.<大写类名> 且与命令行逐字相等。只给 --code 不给 --entrypoint（或反过来）是
        |用法错、直接退出；内容不合格退出码 1；--code 指向的文件不存在退出码 2。
        |入口类要求：实现 EraMod、有 public 无参构造器、类名首字母大写。装载失败会点名六种之一：
        |入口形状非法 / 类不在 dex / 没有无参构造器 / 不是 EraMod / 构造器抛异常 / 静态初始化抛异常。
        |
        |升级与排错
        |改 VERSION 重新编译 dex、重新出包、重新导入即可。代码产物按内容哈希存盘，重导同内容复用
        |不新增；盘上残留的旧版本由装载期回收链按引用清。装载期若判不准用哪一份，宁拒不猜，失败
        |原因在「已安装的 Mod」列表点名（例：「盘上存在多个版本的代码产物而包清单未指定引用」）。
        |总闸与每包信任状态在设置页「第三方代码包概览」卡（只读）核对。
        |
    """.trimMargin()

    /** 事件 / 钩子 / 相位 / 任务 / 界面五面的注册口径（与 Mod.kt 的 onEnable 真调用同源）。 */
    private val assetsReadmeWiring: String = """
        |
        |互动面（interactions.registerHook，示例见 <Name>Mod.kt 的 onEnable 与 Declarations 的 manifest）
        |交互相位钩子同样先声明后注册：manifest 里声明一次相位，运行期才能给本包注册：
        |
        |  interactionHook(InteractionPhase.PRE)                                     // manifest 声明相位槽
        |  context.interactions.registerHook(InteractionPhase.PRE) { hook ->          // 运行期注册（只认已声明相位）
        |      InteractionResult.CONTINUE                                            // CONTINUE 放行 / INTERRUPT 中止 / REPLACE 提交
        |  }
        |
        |绑未声明相位 / 别的包的槽当场抛「交互阶段钩子未声明」；回调收到 InteractionHookContext
        |（interactionId / actor / targets / primaryTarget / phase / replacedResult）。相位序
        |PRECONDITION → PRE → MAIN → POST → SETTLEMENT；作者只做旁路（日志 / 前置拦截 / 结算前替换），
        |MAIN 之外的结算语义交给引擎。示例的 PRE 钩子只累计命中数，见 <Name>Mod.kt 的 onEnable。
        |
        |事件 / 钩子 / 相位 / 任务面（示例见 <Name>Mod.kt 的 onEnable 与 Declarations 的 manifest）
        |任务槽、钩子槽都先在 manifest 里声明，运行期才能绑（未声明槽 / 重复绑定当场抛）：
        |
        |  job(Keys.DAILY_TICK, everyDays = 1L)                                   // 每个游戏日一次
        |  hook(Keys.HOOK_TARGET, HookPhase.PREFIX, HookOptions(priority = 10))   // 钩子槽
        |
        |  context.jobs.bind(Keys.DAILY_TICK) { /* 每日任务 */ }                   // 绑已声明任务槽
        |  context.events.register("EVENTFIRST", EventBucket.NORMAL) { EventResult.CONTINUE }
        |  context.hooks.register(Keys.HOOK_TARGET.name, HookPhase.PREFIX) { HookResult.Continue }
        |  context.phases.subscribe(BuiltinPhase.DAY_START.name) { /* 日边界 */ }
        |  context.ui.registerImage("images/banner.png", altText = "示例浮层图片")
        |  context.ui.registerInteraction(Keys.MENU_LABEL, visible = { tickCount > 0L }) { /* 点击 */ }
        |
        |事件名 trim + 大写归一再查引擎事件表（拼错当场抛并附候选）；相位只订阅内建
        |DAY_START / DAY_END / ACTION（自定义相位要先声明，本波只有内建相位有引擎派发者）。
        |所有注册都返回 Registration，在 onDisable 里统一 unregister（生成的骨架已写好）。
        |
    """.trimMargin()

    /** 资源与打包 / 三语文案 / 图片 / 音频四节的作者口径（拆函数只为压 detekt 行数上限）。 */
    private fun assetsReadmeResources(request: ScaffoldRequest): String = """
        |${request.packageId}：随包资源目录示例
        |
        |本目录随 mod 包（zip）一起分发：pack-mod 把它整体收进包，玩家导入时解到游戏的
        |mods/rt2/<导入端派生的包身份>.assets/ 下（导入包身份由内容哈希派生、形如 kojo-1a2b3c4d，
        |不沿用本模块的 packageId —— 别指望目录名等于 ${request.packageId}）。运行期一律经
        |context.resources 按相对名读（/ 分隔），不要拼磁盘路径、不要依赖目录名，例如：
        |
        |  context.resources.open("README.txt")   // 读字节；缺文件返回 null
        |  context.resources.exists("README.txt") // 缺文件返回 false
        |  context.resources.keys()               // 全部相对名（升序）
        |  context.resources.size("README.txt")   // 字节数；缺文件返回 null
        |
        |规则：只许 / 分隔的相对路径；禁前导/尾随斜杠、空段、点段/.. 段、反斜杠、控制字符、盘符冒号。
        |大小写按磁盘原名逐字匹配（Android 上敏感）。单文件不超过 4 MiB，整包不超过 16 MiB。
        |更细的语义见运行期 ModResourceStore 的 KDoc。
        |
        |打包与引擎下限（minApi，可选）
        |默认不设闸；只在用到较新 mod-api 能力、且不希望旧引擎装上时才声明 —— 在
        |<Name>Declarations.kt 的 modDeclarations 实参里加命名实参 minApi = <N>（N > 0 才把
        |manifest 升到 RT2MANIFEST/6 并写 min-api 段；缺省 0 = 不设闸，产物字节与旧版一致）：
        |
        |  modDeclarations(PACKAGE_ID, version = VERSION, minApi = 6) { … }
        |
        |出包时同值传 pack-mod --min-api <N>（写进包元数据的 minApi 键）；装配期引擎
        |ModApi.VERSION 低于 N 的包整链拒载（提示「需要更新 app」）。check-mod 对负数字面量报
        |ERROR、对高于当前 ModApi.VERSION 的声明报 WARN（预留写法不判错），0 / 等于当前版本不报。
        |
        |三语文案（v10）
        |只在 onLoad 里声明，一次给齐 ZH/EN/JA 三档原文；运行时按玩家设置语言取原文：
        |
        |  context.strings.declare("${request.packageId}.greeting", zh = "你好", en = "Hello", ja = "こんにちは")
        |  context.strings.of("${request.packageId}.greeting")   // 取当前语言原文；未声明时原样回键，不抛
        |  context.strings.has("${request.packageId}.greeting")  // 本包是否声明过该键
        |  context.strings.language()                            // 当前语言码（zh / en / ja）
        |
        |规则：只在 onLoad 声明；三档原文必须一次给齐且非空（各 <= 512 字符）；键只许小写字母/数字/_/./-，
        |长度 <= 64。check-mod 对「of/has 用了但本模块没 declare」的键报 WARN（抽不到字面量就静默，不假红）。
        |
        |图片元素（浮层，可选）
        |图片字节也放本目录（如 images/banner.png），源码里只声明名字：
        |  modUiImage("images/banner.png", altText = "示例浮层图片")        // DSL 糖
        |  ModUiElement.Image(resourceName = "images/banner.png")          // 构造器，同一套校验
        |缺文件 / 空文件 / 非支持格式 / 解码失败：宿主渲染 altText 或占位框并记 WARN，不会崩。
        |宿主用 BitmapFactory 解码（PNG / JPEG / WebP 等常见位图格式）；建议边长不超过 512px。
        |check-mod 会校验声明名与本目录文件是否一致（缺文件 WARN、名字非法 ERROR）。
        |运行期注册（图片 + 交互项，见 <Name>Mod.kt 的 onEnable）：
        |  context.ui.registerImage("images/banner.png", altText = "示例浮层图片", maxSidePx = 512)
        |  context.ui.registerInteraction("${request.packageId}.menu", visible = { tickCount > 0L }) { … }
        |交互项的 labelKey 指向本包三语文案键（禁止内联玩家可见文案）；visible 每次查询现求值。
    """.trimMargin()

    /** 音频面的作者口径（v11：真调用 + 缺资源降级契约；拆函数只为压 detekt 行数上限）。 */
    private val assetsReadmeAudio: String = """
        |
        |
        |音频（v11）
        |音频字节也放本目录（如 audio/theme.ogg），源码里只声明名字；骨架把名字放在 Keys.THEME_AUDIO，
        |onEnable 经常量引用真调用：
        |  context.audio.play(Keys.THEME_AUDIO, loop = true, volume = 0.8f)  // true = 宿主已受理
        |  context.audio.stop()                                               // 幂等，没有在播也安全
        |只在 VM 线程调用（引擎线程；宿主把 prepare/start 挪到后台）。缺文件 / 单文件超 4 MiB /
        |坏容器 / 上游播放器被占用：一律返回 false 并落 WARN，不抛、不砖游戏；设备静音时也可能无声。
        |骨架不随包示例音频：新模块第一次跑 play 必然是 false + 一条 WARN，这是契约内的降级、不是错误；
        |把音频字节放进本目录（同名 audio/theme.ogg）后同一行就真播放。
        |check-mod 会校验 play("...") 的**字面量**名字与本目录文件是否一致（缺文件 WARN、名字非法 ERROR）；
        |常量引用 / 变量 / 拼接抽不到就静默（宁可漏报），要让自检盯这个名字就把调用写成字面量。
    """.trimMargin()

    /** 设置 / 服务 / 耗时 / 日志四面的作者口径（与 Mod.kt 的注释示范同源）。 */
    private val assetsReadmeFaces: String = """
        |
        |设置面（per-mod settings）
        |在 <Name>Declarations.kt 的 manifest 里声明一次，设置页自动出控件；值按包持久化在设备侧，
        |跨存档 / 跨局保留。运行时每拍现读（见 <Name>Mod.kt 的 onDailyTick）：
        |
        |  intSetting(Keys.SETTING_UPGRADE_CHANCE, default = 50L, range = 0L..100L)
        |  boolSetting(Keys.SETTING_UPGRADE_ENABLED, default = true)
        |  enumSetting(Keys.SETTING_ROLL_MODE, default = Keys.ROLL_MODE_RANDOM, candidates = listOf(...))
        |  strSetting(Keys.SETTING_TEXT, default = "")               // 文本设置同形
        |
        |  context.settings.int(Keys.SETTING_UPGRADE_CHANCE)        // 未声明 / 类型不符直接抛
        |  context.settings.bool(...) / .str(...) / .enumName(...)
        |
        |设置面没有写入口：玩家在设置页改，mod 只读；别把值缓存进字段，否则改设置后要重载才生效。
        |
        |服务面（跨 mod Kotlin 服务）
        |发布方 onLoad 把实现挂到接口类型上；消费方在 Manifest 里 loadAfter 本包后 find：
        |
        |  context.services.publish(MyWorldService::class.java, impl, context.packageId)
        |  val service = context.services.find(MyWorldService::class.java)   // 没人发布返回 null
        |
        |同类型只许一个发布方（多包发布时 find 抛错并点名双方）；卸载 / 安装失败回滚即撤销发布；
        |只在 VM 线程调用。消费方不得跨对方生命周期长期持有服务引用。
        |
        |耗时面（读 + 写，v13 起写侧也是作者面）
        |宿主在 lifecycle / tick / hook / event 四个点给每个 mod 自动记账；作者也能给自己的业务段补记录：
        |
        |  context.timing.samples()   // 按 totalNanos 降序；没有读数返回空表（未装配则抛）
        |  context.timing.reset()     // 清空本包读数（不影响别的 mod）
        |  context.timing.record("author.myMod.step", nanos)              // 手动记一笔
        |  context.timing.measure("author.myMod.step") { doSomething() }  // 包一段，起止自动
        |
        |统一墙钟 nanoTime 口径（含调度等待与 IO）；scope 空白 / 超 128 字符 / 负 nanos 抛错，
        |单包 scope 数到 256 后新 scope 只 WARN 忽略；只看到自己包的读数。
        |
        |日志面（context.logger，作者排查用，不是玩家可见文案）
        |三档：context.logger.info("...") / warn("...") / error("...", cause)。玩家该读的字走上面的
        |三语文案面，别塞进日志。生产 PackageLogger 有三条硬化，防止作者日志刷爆 logcat / 拖慢设备：
        |
        |  - 单条超过 1000 字符：截断为前 1000 字符 + 「…(截断)」，cause 在截断之后拼接；
        |  - 空白消息（isBlank() 且无 cause）：静默丢弃，不占行数额度；
        |  - 每个 context 最多 1000 行正文，越限后正文静默、只落一条元告警
        |    （「日志超过 1000 条上限，后续同类日志静默（本行之前已抑制 N 条）」）。
        |
        |所以日志要写成「有信息量的少量行」，不要放在 onFrame / 每拍回调里当调试打印。
        |:mod-testing 台架缺省逐条原样录制（断言看到原文）；要复现生产这三条就调
        |context.hardenLogger()（缺省参数 = 生产 1000/1000 口径），再断言 context.logEntries。
        |
    """.trimMargin()
    /**
     * schema TSV：`schema` / `component` / `field` / `archetype` 四种行，格式与
     * `tools/modgen-accessors/src/main/kotlin/com/eratw/port/modgen/accessors/SchemaDecl.kt` 的解析器一致。
     *
     * 字段线名带包前缀（`<packageId>.level`）防全局撞名；属性名由第 5 列显式覆盖成短名
     * （`level` / `nickname`）—— 线名里有 `.`，默认的属性名折出规则要求纯标识符，覆盖列是它的
     * 合法出口（见 `AccessorEmit` 的 `property = overrides[field.name] ?: camelCase(field.name)`）。
     * 生成物包名带 `.generated` 后缀：与手写代码分目录，跟 `mod-memory` 等车的既有口径一致。
     *
     * 放 object 内：模板函数拆细后 object 与文件两侧都有 detekt 函数数阈值，各留额度。
     */
    private fun schema(request: ScaffoldRequest): String = """
        |schema	${request.kotlinPackage}.generated	${request.className}
        |component	${request.packageId}.profile	profile
        |field	${request.packageId}.level	INT	false	level
        |field	${request.packageId}.nickname	STR	false	nickname
        |archetype	${request.packageId}.actor
        |
    """.trimMargin()

}

/**
 * 载体声明的前半段：包名 / import / KDoc / `@ModCarrier` 载体对象头 / 包身份常量 / newMod。
 *
 * `@ModCarrier` 独占一行（`:mod-registry` 的构建期扫描按行匹配注解与下一行 object 声明，
 * 写在同行或塞进注释里都会漏扫 —— 漏扫不报错，只是 mod 静默不生效）。
 */
private fun declarationsHeader(request: ScaffoldRequest): String = """
    |package ${request.kotlinPackage}
    |
    |import com.eratw.port.modapi.ArchetypeKey
    |import com.eratw.port.modapi.ComponentKey
    |import com.eratw.port.modapi.EraMod
    |import com.eratw.port.modapi.FieldKey
    |import com.eratw.port.modapi.FunctionKey
    |import com.eratw.port.modapi.HookOptions
    |import com.eratw.port.modapi.HookPhase
    |import com.eratw.port.modapi.InteractionPhase
    |import com.eratw.port.modapi.JobKey
    |import com.eratw.port.modapi.ModCarrier
    |import com.eratw.port.modapi.ModCarrierDeclaration
    |import com.eratw.port.modapi.ModManifest
    |import com.eratw.port.modapi.RelationKey
    |import com.eratw.port.modapi.StorageKey
    |import com.eratw.port.modapi.intKey
    |import com.eratw.port.modapi.modDeclarations
    |import com.eratw.port.modapi.strKey
    |
    |/**
    | * 载体声明：一个 mod 只此一份。
    | *
    | * 由 `modgen new-mod` 生成。改包身份或声明面时，同步改
    | * `schemas/${request.className}.schema.tsv` 并重跑 accessor 生成器 —— 两边的**字段线名**
    | * 必须一致，否则引擎按名字查表查不到，读写只会静默返回失败哨兵（读 0 / 读空串 / 写 false）。
    | * schema 是 TSV：解析器不认注释行，这里的头注就是它的唯一使用说明。
    | *
    | * 包身份三处必须同值：[PACKAGE_ID]、[packageId]（本文件已直接回 PACKAGE_ID，不用另改）、
    | * 下面 modDeclarations 的**首参** —— 改身份要三处一起改，漏一处 mod 可能静默不生效。
    | * 改完跑自检：`modgen check-mod <本模块目录>`（退出码 0 = 无 ERROR；身份/键/schema 三面都由它核）。
    | * 发布打包：`modgen pack-mod <本模块目录> --out <zip 路径>`；要声明引擎下限就在后面加
    | * `--min-api <N>`（N ≥ 1，写进包元数据的 minApi 键，装配期 modApi 低于 N 的引擎整链拒载；
    | * 不写（或写 0）= 不设闸，老引擎照常装配 —— 只在用到新 mod-api 能力时才声明）。
    | *
    | * manifest 段的声明 DSL 全集（component/archetype/relation/job/设置/钩子）见 mod-api 的
    | * DeclDsl.kt 一族，本骨架各挑一例（钩子真声明在 manifest 里、绑定在 Mod.kt 的 onEnable）。设置项：声明在本文件 manifest（intSetting 等），
    | * 读数在 ${request.className}Mod.kt 的 onDailyTick（context.settings.int/bool/enumName）——
    | * 声明与读数是一对，键一律经 Keys 常量引用，不写字面量。
    | */
    |@ModCarrier
    |object ${request.className}Declarations : ModCarrierDeclaration {
    |
    |    const val PACKAGE_ID: String = "${request.packageId}"
    |
    |    const val VERSION: String = "0.1"
    |
    |    override val packageId: String get() = PACKAGE_ID
    |
    |    override fun newMod(): EraMod = ${request.className}Mod()
    |
""".trimMargin()

/** 载体声明的 `object Keys`：类型化键，字段线名与 schema 的 field 行对齐（带包前缀防全局撞名）。 */
private fun declarationsKeys(request: ScaffoldRequest): String = """
    |    /** 声明与业务共用的类型化键；字段线名与 schema 的 field 行对齐（带包前缀防全局撞名）。 */
    |    object Keys {
    |        val COMPONENT: ComponentKey = ComponentKey("${request.packageId}.profile")
    |        val ARCHETYPE: ArchetypeKey = ArchetypeKey("${request.packageId}.actor")
    |        val KNOWS: RelationKey = RelationKey("${request.packageId}.knows")
    |        val DAILY_TICK: JobKey = JobKey("${request.packageId}.dailyTick")
    |        val TICK_COUNT: StorageKey = StorageKey("${request.packageId}.tickCount")
    |        val LEVEL: FieldKey<Long> = intKey("${request.packageId}.level")
    |        val NICKNAME: FieldKey<String> = strKey("${request.packageId}.nickname")
    |
    |        // 设置项的键：manifest 里声明一次，运行时经 context.settings 现读（见 onDailyTick）。
    |        const val SETTING_UPGRADE_ENABLED: String = "${request.packageId}.upgradeEnabled"
    |        const val SETTING_UPGRADE_CHANCE: String = "${request.packageId}.upgradeChancePercent"
    |        const val SETTING_ROLL_MODE: String = "${request.packageId}.rollMode"
    |
    |        // rollMode 的候选值：声明与业务共用，避免两处写字面量写岔。
    |        const val ROLL_MODE_RANDOM: String = "random"
    |        const val ROLL_MODE_ALWAYS: String = "always"
    |
    |        // 事件面的键：引擎事件名（宿主 trim + 大写归一再查表）。EVENTFIRST = 每局首次事件，必定触发。
    |        const val EVENT_KEY: String = "EVENTFIRST"
    |
    |        // 钩子面的目标：引擎函数键（大写归一）；槽先在 manifest 里 hook(...) 声明，运行期才能 register。
    |        val HOOK_TARGET: FunctionKey = FunctionKey("SYSTEM_TITLE")
    |
    |        // 界面面：交互项文案键（三语在 onLoad 一次 declare 齐）与浮层图片在本包资源里的相对名。
    |        const val MENU_LABEL: String = "${request.packageId}.menu"
    |        const val BANNER_IMAGE: String = "images/banner.png"
    |
    |        // 音频面（v11）：播放名相对本包资源目录（<packageId>.assets/）。骨架刻意不随包示例音频，
    |        // 所以经常量引用传参：check-mod 的音频率只抽 play("...") 字面量（常量引用静默，宁可漏报），
    |        // 新骨架自检才能保持 0 发现；放进音频文件后要让它自检这个名字，就把调用改回字面量。
    |        const val THEME_AUDIO: String = "audio/theme.ogg"
    |    }
    |
""".trimMargin()

/** 载体声明的 manifest 段：component / archetype / relation / job 各一例，外加对象闭合。 */
private fun declarationsManifest(request: ScaffoldRequest): String = """
    |    // 想让本 mod 拒绝旧引擎：在 modDeclarations 实参里加 minApi = <N>（N > 0 才升 manifest
    |    // 到 RT2MANIFEST/6 并写 min-api 段；缺省 0 = 不设闸，产物字节与旧版一致）。
    |    //   modDeclarations(PACKAGE_ID, version = VERSION, minApi = 6) { … }
    |    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = VERSION) {
    |        displayName("${request.className}")
    |        author("TODO: 换成你的名字")
    |        description("modgen new-mod 生成的最小骨架：组件 / 原型 / 关系 / 周期任务各一例")
    |        component(Keys.COMPONENT) {
    |            int(Keys.LEVEL)
    |            str(Keys.NICKNAME)
    |        }
    |        archetype(Keys.ARCHETYPE) {
    |            uses(Keys.COMPONENT)
    |        }
    |        // 单向关系最保守（有向、去重、destroy 级联清边）。要双向就写 inverse = 反向键，
    |        // 且反向类型自己也必须在这里声明（freeze 对 autoInverse 有「反向已声明」硬闸）。
    |        relation(Keys.KNOWS)
    |        // everyDays = 1L：每游戏日一次；null = 一次性。phase 缺省是引擎日终相位。
    |        job(Keys.DAILY_TICK, everyDays = 1L)
    |        // 钩子槽：先声明后绑定 —— 槽在链接期冻结，运行期 register 只能绑已声明槽（绑未声明槽会抛）。
    |        // priority 升序先跑；同函数相邻两条槽且包名不同 = 撞车嫌疑。绑定与自查见 Mod.kt onEnable。
    |        hook(Keys.HOOK_TARGET, HookPhase.PREFIX, HookOptions(priority = 10))
    |        // 互动面：交互相位钩子槽与函数钩子同口径 —— 先在这里声明，运行期 interactions.registerHook
    |        // 只接受已声明相位（未声明就注册会抛「交互阶段钩子未声明」）。绑定见 Mod.kt 的 onEnable。
    |        interactionHook(InteractionPhase.PRE)
    |        // 设置面：声明一次，设置页自动出控件；值按包持久化在设备侧，跨存档/跨局保留。
    |        // 键习惯「包身份.项名」；文本设置同形：strSetting(Keys.SETTING_TEXT, default = "x")，
    |        // 读法 context.settings.str(Keys.SETTING_TEXT)。
    |        intSetting(
    |            Keys.SETTING_UPGRADE_CHANCE,
    |            default = 50L,
    |            range = 0L..100L,
    |            title = "升级概率（%）",
    |            description = "每日任务给全部原型实体升一级的概率；抽取模式为 always 时忽略。",
    |        )
    |        boolSetting(
    |            Keys.SETTING_UPGRADE_ENABLED,
    |            default = true,
    |            title = "启用每日升级",
    |            description = "关掉后每日任务直接返回：不抽随机、不写存档。",
    |        )
    |        enumSetting(
    |            Keys.SETTING_ROLL_MODE,
    |            default = Keys.ROLL_MODE_RANDOM,
    |            candidates = listOf(Keys.ROLL_MODE_RANDOM, Keys.ROLL_MODE_ALWAYS),
    |            title = "抽取模式",
    |            description = "random = 按概率抽；always = 每拍必升（调试用）。",
    |        )
    |    }
    |}
    |
""".trimMargin()

/**
 * Mod.kt 头注的上手总纲索引块（建 → 写 → 测 → 包 → 装 → 发，每步一行指向既有正文，不复制到这里）。
 * 「发」步是 ERAFIN-C3 的作者分发指引（「包→发」一步）：pack-mod 出的源态 zip 玩家侧要走编译链，
 * 面向分发的**成品包**是把编译产物两件套打进 zip 根，玩家直收（导入卡）或经口上市场（mod-pack）。
 * 与 [modHeader] 拆开只为压 detekt LongMethod 上限（模板正文行计入函数体行数）；拼接位在
 * [modHeader] 的 import 段之后、能力面清单之前。注意 trimMargin 会剥掉 raw string 的**首尾空行**，
 * 所以两处接缝的空行都由 [modHeader] 用显式 "\n\n" 拼接补出，不靠模板行表达。
 */
private val modHeaderFiveStepIndex: String = """
    |/**
    | * 最小 EraMod 骨架：四段必需生命周期 + 新开一局 / 回合边界两个可选回调。
    | *
    | * 上手总纲（建 → 写 → 测 → 包 → 装 → 发）：每步一行指向既有正文，不复制到这里（单一真源仍各归其位）：
    | * 1. 建 —— 根 settings.gradle.kts 里 include 本模块，然后 `./gradlew :<模块目录>:test` 跑生成物
    | *    自检（build.gradle.kts 头注第一步；CLI 输出有 NEXT 行）；
    | * 2. 写 —— 本文件六段生命周期回调，能力面示范各落一处、全是真调用（见下面的面清单）；
    | * 3. 测 —— 随骨架生成的测试类 <Name>ModTest.kt：清单自检（ModTest）与行为面自检（FacesTest）
    | *    同在这一个文件里；
    | * 4. 包 —— 先 `modgen check-mod <本模块目录>`（0 error），再 `modgen pack-mod` 出 zip；
    | *    两条命令与 minApi 的全解见 <Name>Declarations.kt 头注；
    | * 5. 装 —— <packageId>.assets/README.txt 末段「装（导入→生效→验证）」：设置页「补丁（Mod）导入」
    | *    卡 / 口上市场 / 改启停与装载优先级要重开一局生效 / 设置页「Mod 运行时诊断」卡读数；
    | * 6. 发 —— 面向分发的成品包（编译产物态）：把 `<packageId>.erbm` + `<packageId>.manifest`
    | *    两件套打进 zip **根**（manifest 的 artifact-sha256 段必须钉 .erbm 实际摘要，照
    | *    modgen DemoModuleBuild 的两件套先例），玩家设置页「补丁（Mod）导入」卡直收（跳过
    | *    编译链直接落盘）或经口上市场以 mod-pack kind 分发（「装进游戏」走同一份成品包合同）。
""".trimMargin()

/** 生命周期实现的头：包名 / import / KDoc（上手总纲索引块 = [modHeaderFiveStepIndex]）/ 类头 / 两个状态字段。 */
private fun modHeader(request: ScaffoldRequest): String = """
    |package ${request.kotlinPackage}
    |
    |import com.eratw.port.modapi.BuiltinPhase
    |import com.eratw.port.modapi.EraMod
    |import com.eratw.port.modapi.EventBucket
    |import com.eratw.port.modapi.EventResult
    |import com.eratw.port.modapi.HookPhase
    |import com.eratw.port.modapi.HookResult
    |import com.eratw.port.modapi.InteractionPhase
    |import com.eratw.port.modapi.InteractionResult
    |import com.eratw.port.modapi.ModContext
    |import com.eratw.port.modapi.Registration
    |import com.eratw.port.modapi.bind
    |import com.eratw.port.modapi.get
    |import com.eratw.port.modapi.getInt
    |import com.eratw.port.modapi.measure
    |import com.eratw.port.modapi.put
    |import com.eratw.port.modapi.query
    |import com.eratw.port.modapi.setInt
""".trimMargin() + "\n\n" + modHeaderFiveStepIndex + "\n" + """
    | *
    | * 能力面示范都在本文件里真调用（不是注释）：
    | * - 任务面：[onEnable] 把 [onDailyTick] 绑到 Manifest 已声明的 DAILY_TICK 槽；
    | * - 设置面：Manifest 声明（见 Declarations），[onDailyTick] 每拍现读并改变行为；
    | * - 事件面 / 钩子面：[onEnable] 分别注册事件处理器、绑定 Manifest 已声明的钩子槽；
    | * - 相位面 / 界面面：[onEnable] 订阅内建 DAY_START 相位、注册浮层图片与交互项；
    | * - 互动面 / 音频面：[onEnable] 注册 Manifest 已声明相位的交互钩子、真调 audio.play（缺随包音频时
    | *   契约是返回 false + WARN 不抛），[onDisable] 调 audio.stop() 收尾；
    | * - 服务面：[onLoad] 发布 [${request.className}WorldService] 再 find 回读自检；
    | * - 耗时面：[onDailyTick] 用 timing.measure 记账，[onTurnBoundary] 读 samples() 打最慢 scope；
    | * - 日志面：[onLoad] / [onTurnBoundary] 真调 info / warn，error(message, cause) 见 [onLoad]；
    | *   生产三条硬化（单条 1000 字符截断、空白丢弃、每 context 1000 行上限）见随包 README.txt。
    | *
    | * 业务动作从 [onDailyTick] 换成你自己的；其余段落改包名/键名即可。
    | */
    |class ${request.className}Mod : EraMod {
    |
    |    private lateinit var context: ModContext
    |
    |    /** 本会话注册的任务处理器，[onDisable] 统一撤销。 */
    |    private val registrations = mutableListOf<Registration>()
    |
    |    private var tickCount = 0L
    |
    |    /** 互动面：PRE 相位钩子被调的次数（生成物自检断言它真跑过）。 */
    |    var interactionPreHits: Int = 0
    |        private set
    |
    |    /** 音频面：最近一次 play 的返回值（骨架不随包示例音频，缺文件时按契约是 false，不抛）。 */
    |    var themeAudioStarted: Boolean = false
    |        private set
    |
""".trimMargin()

/** onLoad：持久化读回、三语文案、随包资源，外加服务发布与回读自检；与「注册/清账」三段分开写，单函数不超 detekt LongMethod 上限。 */
private fun modOnLoad(request: ScaffoldRequest): String = """
    |    override fun onLoad(context: ModContext) {
    |        this.context = context
    |        // mod 自持久化面：缺键返回 null，不抛。禁用→重启用后状态要对得上。
    |        tickCount = context.storage.get(${request.className}Declarations.Keys.TICK_COUNT)?.toLongOrNull() ?: 0L
    |        // 三语文案面（v10）：onLoad 一次声明齐 ZH/EN/JA（键 <= 64）；declare 与 of 的键必须逐字一致。
    |        context.strings.declare(
    |            "${request.packageId}$LONGEST_STRINGS_KEY_SUFFIX",
    |            zh = "你好，这是 ${request.className} 的世界",
    |            en = "Hello from ${request.className}",
    |            ja = "${request.className} の世界へようこそ",
    |        )
    |        // 界面交互项的文案键（v10）：UI 只拿 labelKey，浮层按玩家语言显示 —— 同样 onLoad 一次声明齐三语。
    |        context.strings.declare(
    |            ${request.className}Declarations.Keys.MENU_LABEL,
    |            zh = "示例菜单项",
    |            en = "Example menu entry",
    |            ja = "サンプルメニュー",
    |        )
    |        // 资源面（v6，可选）：随包资源放 <packageId>.assets/，按相对名读；缺文件 null/false，非法名抛。
    |        if (context.resources.exists("README.txt")) {
    |            val readme = context.resources.open("README.txt")
    |            context.logger.info("随包资源 README.txt：" + (readme?.size ?: 0) + " 字节")
    |        }
    |        // context.resources.keys().forEach { name -> context.logger.info(name) }  // 全部相对名（升序）
    |        // 浮层图片元素（可选）：图片字节放 <packageId>.assets/images/（如 images/banner.png），运行期在
    |        // onEnable 用 context.ui.registerImage(...) 注册；缺文件 / 解码失败时宿主渲染 altText 或占位框，
    |        // 不会崩。DSL 糖同款（声明期校验是同一份），想直接声明就取消下面这行注释：
    |        //   val banner = com.eratw.port.modapi.modUiImage("images/banner.png", altText = "示例浮层图片")
    |        // 服务面：把实现挂到接口类型上；owner 必须是本包 packageId（冒名发布宿主会抛）。
    |        context.services.publish(
    |            ${request.className}WorldService::class.java,
    |            ${request.className}WorldServiceImpl { tickCount },
    |            context.packageId,
    |        )
    |        // 与 find 对称：回读自己刚发布的服务，验证类型/owner 接线没写岔。
    |        // 跨 mod 消费方要在自己的 Manifest 里 loadAfter 本包，再 find（见类尾接口 KDoc）。
    |        val wired = context.services.find(${request.className}WorldService::class.java)
    |        if (wired == null) {
    |            context.logger.warn("服务自检失败：find 没取回刚发布的接口")
    |        } else {
    |            context.logger.info("服务自检通过：tickCount=" + wired.tickCount())
    |        }
    |        // 日志面（作者排查用，不是玩家可见文案）：info / warn 上面已真调，error 形如
    |        //   context.logger.error("可恢复的业务失败", exception)   // 第二参 = cause（记类名+消息）
    |        // 生产对作者日志有三条硬化：单条超 1000 字符截断并加「…(截断)」（cause 在截断之后拼接）、
    |        // 空白消息静默丢弃、每个 context 最多 1000 行正文，越限后只落一条元告警 —— 所以日志要写成
    |        // 「有信息量的少量行」，别放在 onFrame / 每拍回调里当调试打印。台架调 context.hardenLogger()
    |        // 可复现这套硬化（缺省关，逐条原样），详见 README.txt 的「日志面」节。
    |    }
    |
""".trimMargin()

/** onEnable：任务绑定 + 事件 / 钩子 / 相位 / 界面四面的真注册（拆函数只为压 detekt 行数上限）。 */
private fun modOnEnable(request: ScaffoldRequest): String = """
    |    override fun onEnable() {
    |        // 任务面：只能绑 Manifest 已声明的任务槽（未声明 / 重复绑定会抛 IllegalArgumentException）。
    |        registrations += context.jobs.bind(${request.className}Declarations.Keys.DAILY_TICK) {
    |            onDailyTick()
    |        }
    |        // 事件面：注册引擎事件处理器。事件名 trim + 大写归一后查引擎事件表，拼错的当场抛
    |        // IllegalArgumentException 并附最近候选，不会静默不触发；不确定先问 context.events.exists("...")。
    |        // 返回 SINGLE 只截断本桶余下成员；ONLY 桶运行期不可注册（会抛）。
    |        registrations += context.events.register(
    |            ${request.className}Declarations.Keys.EVENT_KEY,
    |            EventBucket.NORMAL,
    |        ) { event ->
    |            context.logger.info("事件到达：" + event.eventKey)
    |            EventResult.CONTINUE
    |        }
    |        // 钩子面：槽已在 Declarations 的 manifest 里 hook(...) 声明，运行期才能绑；
    |        // 绑未声明槽 / 重复绑定都抛 IllegalArgumentException。绑定后可用 context.hooks.registrations() 自查。
    |        registrations += context.hooks.register(
    |            ${request.className}Declarations.Keys.HOOK_TARGET.name,
    |            HookPhase.PREFIX,
    |        ) {
    |            HookResult.Continue
    |        }
    |        // 相位面：内建相位由引擎按相位计划真派发（自定义相位要先在 manifest 声明；本波只有内建相位有派发者）。
    |        registrations += context.phases.subscribe(BuiltinPhase.DAY_START.name) { phase ->
    |            context.logger.info("相位到达：" + phase.phase + " day=" + phase.time.day)
    |        }
    |        // 界面面：浮层图片 + 交互项。图片走本包资源（<packageId>.assets/images/），缺文件时宿主渲染
    |        // altText 或占位框；交互项 labelKey 指向 onLoad 声明的三语文案，visible 每次查询现求值。
    |        registrations += context.ui.registerImage(
    |            ${request.className}Declarations.Keys.BANNER_IMAGE,
    |            altText = context.strings.of("${request.packageId}$LONGEST_STRINGS_KEY_SUFFIX"),
    |            maxSidePx = 512,
    |        )
    |        registrations += context.ui.registerInteraction(
    |            ${request.className}Declarations.Keys.MENU_LABEL,
    |            visible = { tickCount > 0L },
    |        ) {
    |            context.logger.info(context.strings.of(${request.className}Declarations.Keys.MENU_LABEL))
    |        }
    |        // 互动面：相位槽先在 Declarations 的 manifest 里 interactionHook(...) 声明，运行期才能注册本包钩子
    |        // （绑未声明相位 / 别的包的槽会抛 IllegalArgumentException）。回调返回 InteractionResult 决定下一步。
    |        registrations += context.interactions.registerHook(InteractionPhase.PRE) { hook ->
    |            interactionPreHits += 1
    |            if (hook.phase == InteractionPhase.PRE) InteractionResult.CONTINUE else InteractionResult.INTERRUPT
    |        }
    |        // 音频面（v11，可选）：音频字节放 <packageId>.assets/audio/（如 audio/theme.ogg），播放名走
    |        // Keys.THEME_AUDIO。骨架不随包该文件，所以这一行现在必然走降级分支：play 返回 false 并落 WARN，
    |        // 不抛；放好文件后同一行就真播放。
    |        themeAudioStarted = context.audio.play(
    |            ${request.className}Declarations.Keys.THEME_AUDIO,
    |            loop = true,
    |            volume = 0.8f,
    |        )
    |    }
    |
""".trimMargin()

/** onDisable / onUnload：撤销注册、回写持久化、清状态。 */
private fun modOnDisableUnload(request: ScaffoldRequest): String = """
    |    override fun onDisable() {
    |        // 音频面（v11）：播放起过 BGM 就必须在这里收；stop() 幂等，没有在播也安全。
    |        context.audio.stop()
    |        registrations.forEach { registration -> registration.unregister() }
    |        registrations.clear()
    |        context.storage.put(${request.className}Declarations.Keys.TICK_COUNT, tickCount.toString())
    |    }
    |
    |    override fun onUnload() {
    |        registrations.clear()
    |        tickCount = 0L
    |    }
    |
""".trimMargin()

/** 两个可选通知回调（不需要可以删掉整个覆写）。 */
private fun modNotifications(request: ScaffoldRequest): String = """
    |    /** 新开一局：引擎**不重建** mod 实例，回调可能多次触发，必须自复位。 */
    |    override fun onNewGame() {
    |        tickCount = 0L
    |    }
    |
    |    /** 回合边界：作者要按回合结算就写这里；不需要可以删掉整个覆写。 */
    |    override fun onTurnBoundary() {
    |        // 文案面（v10）：of(key) 按玩家设置语言取原文；未声明时原样回键（不抛），has() 先问更稳。
    |        if (context.strings.has("${request.packageId}$LONGEST_STRINGS_KEY_SUFFIX")) {
    |            context.logger.info(context.strings.of("${request.packageId}$LONGEST_STRINGS_KEY_SUFFIX"))
    |        }
    |        // 耗时面：宿主在 lifecycle / tick / hook / event 四个点给每个 mod 单独记账。
    |        // v13 起写侧也是作者面，下面只是注释示范（模板不写读数，保持 check-mod 0 发现基线）：
    |        //   val started = System.nanoTime()
    |        //   doSomething()
    |        //   context.timing.record("author.myMod.step", System.nanoTime() - started)
    |        //   或包一段：val r = context.timing.measure("author.myMod.step") { doSomething() }
    |        // samples() 已按 totalNanos 降序，firstOrNull() 就是最慢 scope；没有读数时返回空表。
    |        val slowest = context.timing.samples().firstOrNull()
    |        if (slowest != null) {
    |            context.logger.info(
    |                "最慢 scope=" + slowest.scope +
    |                    " 平均=" + slowest.averageNanos / 1_000L + "us",
    |            )
    |        }
    |    }
    |
""".trimMargin()

/**
 * 每游戏日一拍 + 类闭合。
 *
 * rng 与 ERB 的 `RAND` 是两条独立流：这里抽数不会让引擎侧已校准的随机消费序漂移。
 * 三个设置项都在这里现读（而不是 onLoad 读一次缓存）：玩家在设置页改完，下一拍就生效。
 */
private fun modDailyTick(request: ScaffoldRequest): String = """
    |    /** 每游戏日一拍：先读设置，再决定今天是否给全部原型实体升一级。 */
    |    private fun onDailyTick() {
    |        // 设置面：每拍现读；未声明 / 类型不符由宿主 fail-closed 抛，不返默认值。
    |        if (!context.settings.bool(${request.className}Declarations.Keys.SETTING_UPGRADE_ENABLED)) {
    |            return
    |        }
    |        tickCount += 1L
    |        val always = context.settings.enumName(${request.className}Declarations.Keys.SETTING_ROLL_MODE) ==
    |            ${request.className}Declarations.Keys.ROLL_MODE_ALWAYS
    |        val chance = context.settings.int(${request.className}Declarations.Keys.SETTING_UPGRADE_CHANCE)
    |        // 耗时面（写侧）：给自定业务段包一层，起止与 scope 归属由宿主统一记账（读数见 onTurnBoundary）。
    |        context.timing.measure("author.${request.packageId}.dailyTick") {
    |            if (always || context.rng.chance(chance.toInt())) {
    |                for (actor in context.entities.query(${request.className}Declarations.Keys.ARCHETYPE)) {
    |                    val level = context.entities.getInt(actor, ${request.className}Declarations.Keys.LEVEL)
    |                    context.entities.setInt(actor, ${request.className}Declarations.Keys.LEVEL, level + 1L)
    |                }
    |            }
    |        }
    |        context.storage.put(${request.className}Declarations.Keys.TICK_COUNT, tickCount.toString())
    |    }
    |}
    |
""".trimMargin()

/**
 * 文件尾的跨 mod 服务契约：接口是消费方 `find` 的唯一类型，实现类不暴露。
 *
 * 服务面四条契约（线程 / 顺序 / 同类型唯一发布方 / 卸载即撤销）见 `ModServiceRegistry` 的 KDoc；
 * 消费方必须先在 Manifest 里 `loadAfter(发布方 packageId)`，否则 `find` 早于 `publish` 只会拿到 null。
 */
private fun serviceContract(request: ScaffoldRequest): String = """
    |/**
    | * 跨 mod Kotlin 服务契约（示例）：消费方在 Manifest 里 loadAfter 本包后，
    | * 在自己的 onLoad 里 context.services.find(${request.className}WorldService::class.java) 取用。
    | *
    | * 只在 VM 线程调用；同类型只许一个发布方；本 mod 卸载/安装失败回滚即撤销发布。
    | */
    |interface ${request.className}WorldService {
    |    /** 本会话已推进的游戏日数（每日任务维护，随 .rt2 持久化）。 */
    |    fun tickCount(): Long
    |}
    |
    |/** 发布出去的实现：只暴露接口，内部读 mod 的私有状态。 */
    |private class ${request.className}WorldServiceImpl(
    |    private val readTickCount: () -> Long,
    |) : ${request.className}WorldService {
    |    override fun tickCount(): Long = readTickCount()
    |}
    |
""".trimMargin()

