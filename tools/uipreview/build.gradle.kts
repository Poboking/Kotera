// uipreview —— 桌面服务基座 + 浏览器贴帧 MVP（CONTRACT-UP1）。
// 工具链/依赖风格照抄 tools/vmdbg（纯 JVM 工具模块口径：禁 Android API、零第三方运行时依赖；
// HTTP 用 JDK 自带 com.sun.net.httpserver，PNG 编码用 javax.imageio）。
// 入口用 application 插件：installDist 后 tools/uipreview/build/install/uipreview/bin/uipreview <子命令>。
plugins {
    alias(libs.plugins.kotlin.jvm)
    // 壳层预览（2026-11-11）：Compose 件的桌面渲染口（Compose Desktop/Skiko）——
    // 画布帧走本模块既有 AWT 链，Compose 壳层（右栏常用区/指令盘/设置卡）走这两件。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
}

application {
    mainClass.set("com.eratw.port.uipreview.MainKt")
}

kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（全仓 JVM 面同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

// 设备档案随 jar 分发（组长接线）：档案数据在模块根 profiles/（不在 src/main/resources，
// 保留「仓库里可直接编辑」的布局），这里把它并入主资源集的 profiles/ 前缀——
// installDist 产物在任意 cwd 跑 `serve --device` 都能从 classpath 兜底拿到内置档案
// （DeviceProfiles.loadDefault 的搜索顺序：环境变量 → 文件系 → classpath）。
tasks.named<Copy>("processResources") {
    from("profiles") {
        into("profiles")
    }
}

dependencies {
    implementation(project(":core-vm"))
    implementation(project(":render"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // 合成语料装置（MiniTitle）要用 erbcc 的 CorpusCompile + ErbcArtifacts 把临时 mini ERB
    // 编成 .erbc 产物目录。方向安全（与 tools/vmdbg 的测试依赖同一先例）：
    // erbcc(main) → core-vm(main)；uipreview(main) → core-vm + render(main)；uipreview(test) → erbcc(main) 无环。
    testImplementation(project(":tools:erbcc"))
}

// 0436e2f70 / a7e9ef27e 引入的两个 PNG 视觉回归测试 import 了 app 模块（com.eratw.port.android.*）
// 的 Compose 件，而 uipreview 是纯 JVM 模块、方向闸不允许依赖 application 模块（Gradle 也不许）——
// 单体时代即编译不过（从未生效）。归属 = app 侧设置卡/壳层视觉回归，待 pocket 侧建 Compose Desktop
// 渲染测试面后迁家；在迁走前排除出 test 编译面（文件保留在树，意图不丢）。
sourceSets.test {
    kotlin.exclude("com/eratw/port/uipreview/SettingsCardsPreviewPngTest.kt")
    kotlin.exclude("com/eratw/port/uipreview/ShellPreviewPngTest.kt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 单测全部用合成 mini 语料（临时目录），不依赖 ERATW_* 环境变量、不依赖真语料；
    // 环境透传只为了与本仓其他工具模块同款（变量缺失时用例照常全跑，不做 Assumptions 跳过）。
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
    // 手动旋钮透传（GalleryPngTest 的属性门控）：`-Deratw.up2.gallery=1` 要进测试 JVM
    // 才生效 —— Gradle 命令行 -D 只落在 daemon，必须在这里转交给 worker。
    System.getProperties().filterKeys { it is String && it.startsWith("eratw.") }
        .forEach { (k, v) -> systemProperty(k as String, v as String) }
}

// ---------------------------------------------------------------------------
// 壳层预览源（2026-11-11）：把 app 里**零 Android 依赖**的两个 Compose 件原样拷进本模块编译。
//
// 为什么是「拷源码」而不是「依赖 app 模块」：app 是 Android 模块（AGP），纯 JVM 工具模块
// 依赖不了它；而这两件本身只用 androidx.compose 的公共 API，Compose Multiplatform 的桌面
// 产物提供**同名同包**的实现 ⇒ 同一份源码在两端都能编译，单一来源、不维护副本。
// ---------------------------------------------------------------------------
val shellPreviewSrc = layout.buildDirectory.dir("generated/shell-preview/kotlin")
val copyShellPreviewSources by tasks.registering(Sync::class) {
    from("../../app/src/main/kotlin/com/eratw/port/android/RailOptionRules.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/QuickMacroUi.kt")    // 设置页三张长名单卡（2026-11-12 紧凑化预览）：卡片源码逐件拷入，不维护副本。
    // 这些件都只用 Compose 公共 API + core-vm 的配置表，Android 侧没有依赖。
    from("../../app/src/main/kotlin/com/eratw/port/android/SettingsStrings.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/SettingsCardKit.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/SettingsListPolicy.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/RailFilterCard.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/RailPinnedCard.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/RailQuickMacroCard.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/MacroDraft.kt")
    from("../../app/src/main/kotlin/com/eratw/port/android/ui/EratwInk.kt")
    into(shellPreviewSrc)
}
kotlin {
    sourceSets["main"].kotlin.srcDir(shellPreviewSrc)
    sourceSets["test"].kotlin.srcDir(shellPreviewSrc)
}
tasks.named("compileKotlin") { dependsOn(copyShellPreviewSources) }
tasks.named("compileTestKotlin") { dependsOn(copyShellPreviewSources) }

dependencies {
    // Compose Desktop（当前 OS 的 Skiko 产物）：只在 test 面用（渲染验证），不进 CLI 运行时。
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
}
