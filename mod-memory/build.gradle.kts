plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 记忆 Mod（M9-1 参考实现，era-arch-v61u.md §21.10 M9-1 / §21.2）。
//
// **main 只允许依赖 :mod-api** —— 照 `mod-family/build.gradle.kts` 的依赖方向纪律（§23.0 追加
// 裁定 1 的机器形态）：业务零耦合不靠 grep 词表，靠 Gradle 依赖方向在编译期拦住（`:core-vm`
// 永不依赖本模块，本模块 main 也看不见 `core.*`）。
//
// testImplementation 那一条是**故意开的例外**（先例同上）：判据的纵向验收必须把本模块的
// EraMod 装进真实 `ModRuntimeHost` 跑一遍，而那台宿主在 :core-vm 里。测试面碰引擎不会污染
// 生产依赖方向，所以它开在 test、main 一个都不许加。
kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":mod-api"))
    testImplementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
