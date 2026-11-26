// vmdbg —— JVM 调试器 CLI（CONTRACT-DBG1 路2）。
// 工具链/依赖风格照抄 tools/erbcc（纯 JVM 工具模块口径：禁 Android API），
// 但入口用 application 插件：installDist 后 tools/vmdbg/build/install/vmdbg/bin/vmdbg <子命令>。
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.eratw.port.vmdbg.MainKt")
}

kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（全仓 JVM 面同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    // SQLite 迁移（CONTRACT-SQLITE-MIGRATION W2）：vmdbg 桌面宿主换真实 SQLite。
    // :sqlite-adapter 是 core SqlDriver ↔ AndroidX 的转换层（含 seed/write 路径与沙箱）；
    // sqlite-bundled-jvm 与 Android 侧 sqlite-bundled 同版本（2.5.2 / SQLite 3.46.0），
    // 保证桌面调试与真机驱动 API、SQL 能力（UPSERT / UNIQUE 索引 / ALTER ADD COLUMN）一致。
    implementation(project(":sqlite-adapter"))
    implementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // 合成语料装置（MiniCorpus）要用 erbcc 的 CorpusCompile + ErbcArtifacts 把临时 mini ERB
    // 编成 .erbc 产物目录。方向安全（与 core-vm 对 erbcc 的测试依赖同一口径）：
    // erbcc(main) → core-vm(main)；vmdbg(main) → core-vm(main)；vmdbg(test) → erbcc(main) 无环。
    testImplementation(project(":tools:erbcc"))
    // 产物新鲜度闸（DBG-TOOLING 波）：assemble 用 ErbcCompilerDigest.compute/locateRepoRoot
    // 对 manifest 的 compiler-digest 做重算比对（STALE 静默跑完是明确缺口，探查确认 vmdbg
    // 全 main 源集零判定点）。erbcc(main) 本就是 vmdbg(test) 依赖，提到 main 无新环。
    implementation(project(":tools:erbcc"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 单测全部用合成 mini 语料（临时目录），不依赖 ERATW_* 环境变量、不依赖真语料；
    // 环境透传只为了与本仓其他工具模块同款（变量缺失时用例照常全跑，不做 Assumptions 跳过）。
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}

// 桌面冷启链路分段量测（task-1 读源）。口径照 :core-vm:erbcBootProfile（core-vm/build.gradle.kts）：
// 走 test runtimeClasspath（BootChainProfile 在 src/test 下，与 :core-vm 的探针同规格），
// 6g 堆（361 MiB erbc + 58 MiB 侧车 + SQL 图距离），ERATW_* 环境变量显式透传（缺了探针自己报错，不静默）。
tasks.register<JavaExec>("bootChainProfile") {
    group = "verification"
    description = "桌面冷启链路分段量测（真 SQLite + TITLE→QOL_DB_INIT→建图链；非 ART 绝对值，段间比例判据）"
    mainClass.set("com.eratw.port.vmdbg.BootChainProfileKt")
    classpath(sourceSets["test"].runtimeClasspath)
    maxHeapSize = "6g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}
