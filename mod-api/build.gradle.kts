plugins {
    alias(libs.plugins.kotlin.jvm)
}

// RT2 Kotlin Mod SDK 的 facade 契约层（路 A，era-arch-v61u.md §18.2/§18.3）。
//
// 铁边界：本模块**零 :core-vm 依赖**——mod 作者只依赖 :mod-api，不依赖引擎内部。
// 这里只有接口、值类型与声明形态（纯 JVM，禁 Android API、禁 kotlin.random、禁静态可变全局，
// AGENTS.md §2 铁律 4）；facade 的适配器（wrap IEntityKernel / NativeMethodRegistry /
// DeclarationRegistry / SaveService）落在 :core-vm 侧（`com.eratw.port.core.mod`），
// 依赖方向单向 :core-vm -> :mod-api，本模块永不反向依赖引擎。
//
// clean 语义（主人 2026-11-09 裁定，§18.7）：本 facade 契约取干净语义，不复刻官方 bug；
// ADR-005 仍约束 ERB/vanilla VM 面（语料逐位不变），mod 经 API 边界参与、不执行 ERB opcode、
// 不替换 ERB dispatch，故不继承 VM 怪癖，但须守引擎契约（roster 世代 §7、转移边界 §18.4、事件序 §6.1）。
kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（全仓 JVM 面同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
}