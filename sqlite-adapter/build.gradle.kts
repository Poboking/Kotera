// SQLite 迁移：core 纯 JVM 端口 ↔ AndroidX SQLite 通用 API 的**唯一**适配器。
//
// - 主源对 AndroidX `sqlite` 只做 compileOnly：适配器字节码由 Android app 与 JVM 工具共同消费，
//   Android 侧的实际 native 驱动由 app 注入 `androidx.sqlite:sqlite-bundled`；
// - 测试/vmdbg 用同版本 `sqlite-bundled-jvm`，保证两边驱动 API 与 SQLite 版本一致；
// - 本模块禁止 Android API（仍是 JVM 模块），路径/沙箱策略在这里单点实现。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    compileOnly(libs.androidx.sqlite)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}
