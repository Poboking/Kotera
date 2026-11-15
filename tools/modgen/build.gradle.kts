plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    // RT2-B③：内置 manifest 的唯一真源在 :mod-family（EraMod 声明），构建期工具可读它。
    // 方向仍是 mod 侧只依赖 :mod-api；引擎内部只由 :tools:modgen 这个构建期工具看见。
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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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

tasks.named("processTestResources") {
    // 仅排序显式的资产重生成；普通测试不得重写 golden 来掩盖字节差异。
    mustRunAfter("generateSampleManifest")
    mustRunAfter("generateBuiltinRt2Manifest")
    mustRunAfter("generateDemoErbModule")
}
