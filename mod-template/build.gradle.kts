plugins {
    alias(libs.plugins.kotlin.jvm)
}

// mod 作者工具链（2026-11-17）：最小载体模板。
//
// 存在的理由：新作者第一次写 mod 时缺一个「clone 下来就能编译、能对着改」的骨架。
// 本模块的每个文件都是可抄的，KDoc 里逐段写「抄写模板时改哪里」。
//
// **main 只依赖 :mod-api** —— 与真实 mod 同一条依赖方向铁律（§23.0 追加裁定 1）：
// 模板如果偷看 `com.eratw.port.core.*`，抄它的人就会把引擎内部依赖带进业务 mod。
//
// 刻意**不接** `src/generated/kotlin`：本模块要保证干净 clone 上直接
// `:mod-template:compileKotlin` 通过，不要求先跑生成任务。代价是手写的 Keys/View/Facade
// 不受 `check*Accessors` 过期闸保护 —— 真实 mod 走生成器就有（见 TemplateKeys 的 KDoc）。
kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":mod-api"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":mod-testing"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
