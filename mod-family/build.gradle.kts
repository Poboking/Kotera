plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 家族 Mod（MOD-1 起步，era-arch-v61u.md §23.2 F1 / §17 MOD-1..8）。
//
// **main 只允许依赖 :mod-api** —— 这是 §23.0 追加裁定 1 的机器形态：业务零耦合不靠 grep 词表，
// 靠 Gradle 依赖方向在编译期拦住（`:core-vm` 永不依赖本模块，本模块 main 也看不见 `core.*`）。
// 哪天有人想让家族代码直接调引擎内部，这里会编译不过，而不是审查时看漏。
//
// testImplementation 那一条是**故意开的例外**：F1 的纵向验收必须把本模块的 EraMod 装进真实
// `ModRuntimeHost` 跑一遍，而那台宿主在 :core-vm 里。测试面碰引擎不会污染生产依赖方向，
// 所以它开在 test、main 一个都不许加。
kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

kotlin.sourceSets.named("main") {
    // :tools:modgen-accessors 生成的类型化访问器（P1-6 ② 的 Mod 侧解法）随本模块一起编译。
    kotlin.srcDir("src/generated/kotlin")
}

dependencies {
    api(project(":mod-api"))
    testImplementation(project(":core-vm"))
    // 轻量内存台架：作者不启引擎也能验业务逻辑（`FamilyModHarnessTest` 是示范）。
    // 它只依赖 :mod-api，不进 main 的依赖可见面，与本文件「test 例外」的既有纪律同口径。
    testImplementation(project(":mod-testing"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
