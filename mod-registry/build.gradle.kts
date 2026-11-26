// 内置载体注册表（2026-11-16）：`@ModCarrier` 自动发现的唯一名单。
//
// 以前新增一个 Kotlin 载体要在两处各抄一行：构建期资产生产者
// （`tools/modgen/.../BuiltinManifestBuild.carriers`）与设备侧装配
// （`app/.../ModCarrierRegistry.providers()`）。漏一处不报错、不失败，只是那个 mod
// **静默不生效**。本模块把这两处收成一处：扫 `mod-*` 模块 main 源集里打了
// `@ModCarrier` 的声明 object，生成 `ModRegistry`，两边都只读它。
//
// 「哪些模块算载体模块」由**目录名**决定（`mod-*`，除 `:mod-api` 契约层、本模块自己，
// 以及 `:mod-template` / `:mod-testing` 两个作者工具链模块 —— 模板的 `@ModCarrier` 是给人
// 抄的样例，不该被装进生产注册表；测试台架根本不是载体），依赖也据此自动挂上 ——
// 新增载体模块不需要在这里登记。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":mod-api"))
}

/** 载体模块目录（`mod-*`，排除契约层 `:mod-api` 与本模块）。 */
val carrierModuleDirs: List<File> =
    (rootProject.projectDir.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.startsWith("mod-") }
        .filter { it.name != "mod-api" && it.name != "mod-registry" }
        .filter { it.name != "mod-template" && it.name != "mod-testing" }
        .filter { rootProject.findProject(":${it.name}") != null && it.resolve("build.gradle.kts").isFile }
        .sortedBy { it.name }

// 依赖自动挂：每个载体模块都要在**运行期**可见（生成的注册表直接引用它的声明 object）。
// 用 api 而不是 implementation，是为了让 `:app` / `:tools:modgen` 也能看见那些声明类型。
dependencies {
    carrierModuleDirs.forEach { dir -> api(rootProject.project(":${dir.name}")) }
}

val modRegistryOutput = layout.buildDirectory.dir("generated/modRegistry/kotlin")

val generateModRegistry by tasks.registering {
    group = "build"
    description = "Scan @ModCarrier declarations across mod-* modules and generate ModRegistry.kt"
    carrierModuleDirs.forEach { dir ->
        val sources = dir.resolve("src/main/kotlin")
        if (sources.isDirectory) {
            inputs.dir(sources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("carrier-${dir.name}")
        }
    }
    outputs.dir(modRegistryOutput)
    doLast {
        val declarations = carrierModuleDirs.flatMap { dir ->
            scanCarriers(dir.resolve("src/main/kotlin"))
        }
        val targetDir = modRegistryOutput.get().asFile.resolve("com/eratw/port/modapi/registry")
        targetDir.mkdirs()
        val body = declarations.joinToString("\n") { "        $it," }
        val source = buildString {
            append("package com.eratw.port.modapi.registry\n\n")
            append("import com.eratw.port.modapi.ModCarrierDeclaration\n\n")
            append("/**\n")
            append(" * 内置载体注册表 —— **生成物，请勿手改**。\n")
            append(" *\n")
            append(" * 源 = 各 `mod-*` 模块 main 源集里打了 `@ModCarrier` 的声明 object（共 ${declarations.size} 个）。\n")
            append(" * 新增载体只要在其中实现 `ModCarrierDeclaration` 并打上注解，本文件由\n")
            append(" * `:mod-registry:generateModRegistry` 重新生成，设备侧装配与资产生产者同时跟上。\n")
            append(" */\n")
            append("object ModRegistry {\n")
            append("    /** 全部内置载体，按 packageId 升序（与目录扫描顺序无关，保证逐位确定）。 */\n")
            append("    val carriers: List<ModCarrierDeclaration> = listOf(\n")
            if (body.isNotEmpty()) append(body).append('\n')
            append("    ).sortedBy { it.packageId }\n")
            append("}\n")
        }
        targetDir.resolve("ModRegistry.kt").writeText(source)
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateModRegistry)
}

tasks.named("compileKotlin") {
    dependsOn(generateModRegistry)
}

/**
 * 扫一个 main 源集目录，返回打了 `@ModCarrier` 的声明 object 的**全限定名**。
 *
 * 判据刻意极小：`@ModCarrier` 独占一行，紧跟的那个 `object <名字>` 就是载体声明对象，
 * 包名取同文件的 `package` 声明。不做语法分析 —— 需要的话（比如注解带参数、写在类上）
 * 应当先改契约，而不是让这里长大。
 */
private fun scanCarriers(sourceRoot: File): List<String> {
    if (!sourceRoot.isDirectory) return emptyList()
    val found = mutableListOf<String>()
    sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { file ->
            val lines = file.readLines()
            val packageName = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            if (packageName.isNullOrBlank()) return@forEach
            lines.forEachIndexed { index, line ->
                if (line.trim() != "@ModCarrier") return@forEachIndexed
                val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: return@forEachIndexed
                val match = Regex("""^\s*(?:public\s+)?object\s+([A-Za-z][A-Za-z0-9_]*)""").find(next)
                    ?: error("${file.path}: @ModCarrier 后面必须是 object 声明，实际是「${next.trim()}」")
                found += "$packageName.${match.groupValues[1]}"
            }
        }
    return found.sorted()
}
