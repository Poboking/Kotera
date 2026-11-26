plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    // RT2-B③：内置 manifest 的唯一真源在各载体模块的声明 object。2026-11-16 起不再逐个点名
    // 载体模块 —— 名单由 :mod-registry 扫 @ModCarrier 生成（ModRegistry.carriers），
    // 本模块只读它；于是「新增载体要在这里加一行」这条双登记义务消失了。
    // 方向仍是 mod 侧只依赖 :mod-api；引擎内部只由 :tools:modgen 这个构建期工具看见。
    implementation(project(":mod-registry"))
    // 兼容入口（assetFileName / bytes()）仍以 family 为载体样板，源码里显式引用它。
    implementation(project(":mod-family"))
    // RT2-M91：记忆 Mod 声明真源（M9-1），同上——构建期工具读声明，方向闸不破。
    implementation(project(":mod-memory"))
    // M10-1：Living World Demo 四包声明真源（eratw.lw.*），同上——构建期工具读声明。
    implementation(project(":mod-livingworld"))
    // RT2-ERBM：demo 模块真源（erbm-demo 下的 ERB）要经 erbcc 的零基线模块编译档
    // （ErbModuleCompiler.specFromErb + compile + write）落成 .erbm；modgen 与 erbcc 同属
    // 构建期工具层，无方向闸（tools 内部互依不违反 P1-6 的「mod 侧不依赖引擎内部」判据）。
    implementation(project(":tools:erbcc"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // 生成物「真跑」面：新设置/服务/耗时三面示范要有运行时断言（TestWorld 发 context、
    // 手动 fire 每日任务、录制 settings/log），所以测试源集依赖台架模块（只进 test，不进产物）。
    testImplementation(project(":mod-testing"))
    // check-mod 与 new-mod 对齐用例直接复用 accessor 生成器的解析/校验/渲染（modgen-accessors
    // 不依赖 modgen，无环；生成器口径只有一份真源，免得 check 里再抄一套校验规则）。
    implementation(project(":tools:modgen-accessors"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // RT2 闭环夹具自刷新旋钮：`.\gradlew.bat -Deratw.refreshPackFixture=true :tools:modgen:test`
    // 直接生效。命令行 -D 进不了 fork 出来的 test worker JVM，必须在这里显式转发；
    // 缺省 "false" ⇒ 未指定时行为与未接线前逐位一致（照旧逐字节比对冻结夹具）。
    systemProperty(
        "eratw.refreshPackFixture",
        System.getProperty("eratw.refreshPackFixture") ?: "false",
    )
}

tasks.register<JavaExec>("generateSampleManifest") {
    group = "build"
    description = "Generate the minimal RT2 Manifest asset (-PmanifestOutput=<file>)."
    mainClass.set("com.eratw.port.modgen.ManifestBuildKt")
    classpath = sourceSets["main"].runtimeClasspath
    val outputFile = providers.gradleProperty("manifestOutput")
        .map { file(it) }
        .orElse(layout.buildDirectory.file("manifests/minimal.manifest").map { it.asFile })
    outputs.file(outputFile)
    doFirst {
        args(outputFile.get().absolutePath)
    }
}

// RT2-B③：内置 Kotlin 载体 manifest 的重生入口。产物是**入库的静态资产**
// （app 模块 assets 目录下 mods 子树的 rt2 子目录），不是每次构建都生成：
// 声明改了就跑一次本任务，随后资产对账用例会逼你把新字节一起提交。
// RT2-M91 起一次跑出全部载体资产（family + memory 双真源，见 BuiltinManifestBuild.carriers），
// 产物参数从单文件改为输出目录。
tasks.register<JavaExec>("generateBuiltinRt2Manifest") {
    group = "build"
    description = "Rebuild the bundled RT2 manifest assets from all carrier declarations (-PmanifestOutputDir=<dir>)."
    // 入口是 object 内的 @JvmStatic main（不是 BuiltinManifestBuildKt 顶层 main），
    // 以免与 ManifestBuild.kt 的顶层 main 争抢同包裸 main(...) 的解析。
    mainClass.set("com.eratw.port.modgen.BuiltinManifestBuild")
    classpath = sourceSets["main"].runtimeClasspath
    val defaultOutput = rootProject.file("app/src/main/assets/mods/rt2")
    val outputDir = providers.gradleProperty("manifestOutputDir")
        .map { file(it) }
        .orElse(defaultOutput)
    outputs.dir(outputDir)
    // 重生入口不做增量：声明没变也允许重跑，字节由资产对账用例兜底。
    outputs.upToDateWhen { false }
    doFirst {
        args(outputDir.get().absolutePath)
    }
}

// RT2-ERBM：demo ERB Mod 两件套（eratw.demo.erbm + eratw.demo.manifest）的重生入口。
// 与 generateBuiltinRt2Manifest 同口径：产物是**入库的静态资产**，不是每次构建都生成；
// 真源 DEMO.ERB 改了就跑一次本任务，随后 DemoModuleAssetTest 会把新字节逼进提交。
tasks.register<JavaExec>("generateDemoErbModule") {
    group = "build"
    description = "Rebuild the bundled eratw.demo .erbm + manifest assets (-PdemoModuleOutput=<dir>)."
    // 入口是 object 内的 @JvmStatic main（同 BuiltinManifestBuild 的先例，不与既有顶层 main 抢解析）。
    mainClass.set("com.eratw.port.modgen.DemoModuleBuild")
    classpath = sourceSets["main"].runtimeClasspath
    val defaultOutput = rootProject.file("app/src/main/assets/mods/rt2")
    val outputDir = providers.gradleProperty("demoModuleOutput")
        .map { file(it) }
        .orElse(defaultOutput)
    outputs.dir(outputDir)
    // 重生入口不做增量：真源没变也允许重跑，字节由资产对账用例兜底。
    outputs.upToDateWhen { false }
    doFirst {
        args(outputDir.get().absolutePath)
    }
}

// RT2-SCAFFOLD：`new-mod` 的冒烟面。真跑一次 CLI 生成骨架，再把生成物挂进 test 源集 ——
// 模板一旦写出语法错/路径越界/引用缺失，:tools:modgen:compileTestKotlin 立刻红；生成的
// SmokeModTest 也会被 JUnit 真跑。「生成物能编译」不靠人工目测，靠这条构建依赖兜底。
val scaffoldSmokeModule = layout.buildDirectory.dir("scaffold-smoke/mod-smoke")
val generateScaffoldSmoke by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generate a mod skeleton via new-mod for test-source compilation smoke."
    // 入口是 object 内的 @JvmStatic main（同 BuiltinManifestBuild 的先例）。
    mainClass.set("com.eratw.port.modgen.NewModScaffold")
    classpath = sourceSets["main"].runtimeClasspath
    inputs.files(sourceSets["main"].output)
    outputs.dir(scaffoldSmokeModule)
    args(
        "new-mod",
        "--package", "eratw.smoke",
        "--name", "Smoke",
        "--out", scaffoldSmokeModule.get().asFile.absolutePath,
        "--force",
    )
}

kotlin.sourceSets.named("test") {
    kotlin.srcDir(generateScaffoldSmoke)
}
tasks.named("compileTestKotlin") {
    mustRunAfter(generateScaffoldSmoke)
}

tasks.named("processTestResources") {
    // 仅排序显式的资产重生成；普通测试不得重写 golden 来掩盖字节差异。
    mustRunAfter("generateSampleManifest")
    mustRunAfter("generateBuiltinRt2Manifest")
    mustRunAfter("generateDemoErbModule")
}
