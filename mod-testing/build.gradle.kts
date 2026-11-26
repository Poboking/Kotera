plugins {
    alias(libs.plugins.kotlin.jvm)
}

// mod 作者工具链（2026-11-17）：作者单测台架。
//
// 给 `:mod-api` 的实体 / 关系 / 存储 / 查询能力面各做一份**内存实现**，外加一个
// `runMod(mod, world)` 式的装配小工具，让作者不启动 `:core-vm` 引擎就能验业务逻辑。
//
// main 依赖面：**除资源面外只依赖 :mod-api** —— 台架一旦依赖引擎内部，
// 作者写出的用例就会在真引擎上假绿（内存实现与生产语义悄悄分叉）。
// 唯一例外 = 资源面（2026-11-17，task-32）：资源只有「读盘 + 名字/大小闸」这一份真实现
// （`:core-vm` 的 ModResourceStore），台架再写一份内存版必然与引擎分叉，故这里直接复用真实现
// （对拍纪律优先于依赖面最小化）。其余能力面仍是台架内存实现，不借引擎背书。
// test 面对齐 main：台架自身的行为用台架自测。
kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":mod-api"))

    // 资源面复用引擎真实现（见上方注释唯一例外）：mod-testing main → core-vm(main)。
    // 方向检查：core-vm 只在 **test 源集** 依赖 mod-testing(main)，task 依赖链
    // core-vm:compileKotlin → mod-testing:compileKotlin → core-vm:compileTestKotlin 无环（测试源集独立）。
    implementation(project(":core-vm"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
