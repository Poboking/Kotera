plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Living World Demo（M10-1，era-arch-v61u.md §21.9 四 Mod 组合 / §21.10 M10-1）。
//
// **main 只允许依赖 :mod-api** —— 照 `mod-family/build.gradle.kts`、`mod-memory/build.gradle.kts`
// 的依赖方向纪律（§23.0 追加裁定 1 的机器形态）：业务零耦合不靠 grep 词表，靠 Gradle 依赖
// 方向在编译期拦住（`:core-vm` 永不依赖本模块，本模块 main 也看不见 `core.*`）。
//
// testImplementation 那一条是**故意开的例外**（先例同上）：§21.9 七判据的纵向验收必须把四个
// EraMod 装进真实 `ModRuntimeHost`（真 DecisionPipeline 注入）跑一遍，而宿主与仲裁器在
// :core-vm 里。测试面碰引擎不会污染生产依赖方向，所以它开在 test、main 一个都不许加。
kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

// `NpcKeys` / `BondKeys` / `LwFactKeys` / `LwEventKeys`（连同各自的 View / Facade）由
// `:tools:modgen-accessors` 从四包 schema 生成后入库（照 mod-family / mod-memory 先例）。
// 生成物**入库**而不是构建期现生：这样 `api(project(":mod-api"))` 之外零插件依赖，
// 且 CI 上「盘上产物是否过期」由生成器的 `--check` 闸单独判。
kotlin.sourceSets.named("main") {
    kotlin.srcDir("src/generated/kotlin")
}

dependencies {
    api(project(":mod-api"))
    testImplementation(project(":core-vm"))
    // 声明事务共存判据（§21.9 判据 4）要拿生产注册表的**全部六只载体**同一次 freeze：
    // family / memory 两包的声明真源仅测试面可见，不进 main 依赖方向。
    testImplementation(project(":mod-family"))
    testImplementation(project(":mod-memory"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
