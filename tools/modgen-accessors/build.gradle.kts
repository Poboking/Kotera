plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":mod-api"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.eratw.port.modgen.accessors.MainKt")
}

val familySchema = layout.projectDirectory.file("src/test/resources/family.schema.tsv")
val familyOutput = rootProject.layout.projectDirectory.file(
    "mod-family/src/generated/kotlin/com/eratw/port/modfamily/generated/Family.kt",
)

val generateFamilyAccessors by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Explicitly regenerate the checked-in family accessor sample."
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    inputs.file(familySchema)
    outputs.file(familyOutput)
    args(familySchema.asFile.absolutePath, familyOutput.asFile.absolutePath)
}

tasks.register<JavaExec>("checkFamilyAccessors") {
    group = "verification"
    description = "Check the family accessor sample without rewriting it."
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    args("--check", familySchema.asFile.absolutePath, familyOutput.asFile.absolutePath)
    mustRunAfter(generateFamilyAccessors)
}

// 记忆 Mod 的 schema 刻意住在 `schemas/`（而不是 `src/test/resources/`）：它是**入库的产物源**，
// 不是测试夹具 —— 盘上那份 `Memory.kt` 就是由它生成的。family 的 schema 早年落在
// `src/test/resources/` 是历史包袱，两者位置不同不影响生成口径。
val memorySchema = layout.projectDirectory.file("schemas/memory.schema.tsv")
val memoryOutput = rootProject.layout.projectDirectory.file(
    "mod-memory/src/generated/kotlin/com/eratw/port/modmemory/generated/Memory.kt",
)

tasks.register<JavaExec>("generateMemoryAccessors") {
    group = "build"
    description = "Explicitly regenerate the checked-in memory accessor sample."
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    inputs.file(memorySchema)
    outputs.file(memoryOutput)
    args(memorySchema.asFile.absolutePath, memoryOutput.asFile.absolutePath)
}

tasks.register<JavaExec>("checkMemoryAccessors") {
    group = "verification"
    description = "Check the memory accessor sample without rewriting it."
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    args("--check", memorySchema.asFile.absolutePath, memoryOutput.asFile.absolutePath)
    mustRunAfter("generateMemoryAccessors")
}

val freshSources = layout.buildDirectory.dir("generated/testAccessors")
val generateTestAccessors by tasks.registering(JavaExec::class) {
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    val schema = layout.projectDirectory.file("src/test/resources/fresh.schema.tsv")
    inputs.file(schema)
    outputs.dir(freshSources)
    args(schema.asFile.absolutePath, freshSources.get().file("Fresh.kt").asFile.absolutePath)
}

// 盘上那份生成物现在由 :mod-family 的 main source set 自己编译（见 mod-family/build.gradle.kts），
// 本模块的测试只要**看得见**那个类，不再把它抄进自己的 source set。
dependencies {
    testImplementation(project(":mod-family"))
}

// CLI 现场生成的那份仍然挂进测试编译：它验的是「刚生成的代码能不能编译且行为正确」，
// 必须在 build/ 下现生现编，不能用盘上副本顶替。
kotlin.sourceSets.named("test") {
    kotlin.srcDir(generateTestAccessors)
}

tasks.named("compileTestKotlin") {
    mustRunAfter(generateFamilyAccessors)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.file(familyOutput)
    systemProperty("accessors.familyOutput", familyOutput.asFile.absolutePath)
    systemProperty("accessors.freshOutput", freshSources.get().file("Fresh.kt").asFile.absolutePath)
}

// livingworld 的四个包各有一份 schema（一个 Gradle 模块里住四个 packageId，各自一份声明）。
// 口径同 memory：schema 住 `schemas/`、生成物入库、`--check` 闸单独判过期。
// 任务名带 `Lw` 前缀，避免与上面的 `generateMemoryAccessors`（那是 mod-memory 的）撞名。
private data class CheckedAccessor(val taskInfix: String, val schemaPath: String, val outputPath: String)

private val livingWorldAccessors = listOf(
    CheckedAccessor(
        "LwCharacter",
        "schemas/character.schema.tsv",
        "mod-livingworld/src/generated/kotlin/com/eratw/port/modlivingworld/generated/Npc.kt",
    ),
    CheckedAccessor(
        "LwRelationship",
        "schemas/relationship.schema.tsv",
        "mod-livingworld/src/generated/kotlin/com/eratw/port/modlivingworld/generated/Bond.kt",
    ),
    CheckedAccessor(
        "LwMemory",
        "schemas/lwmemory.schema.tsv",
        "mod-livingworld/src/generated/kotlin/com/eratw/port/modlivingworld/generated/LwFact.kt",
    ),
    CheckedAccessor(
        "LwEvent",
        "schemas/event.schema.tsv",
        "mod-livingworld/src/generated/kotlin/com/eratw/port/modlivingworld/generated/LwEvent.kt",
    ),
)

val livingWorldGenerated = livingWorldAccessors.map { spec ->
    val schema = layout.projectDirectory.file(spec.schemaPath)
    val output = rootProject.layout.projectDirectory.file(spec.outputPath)
    val generate = tasks.register<JavaExec>("generate${spec.taskInfix}Accessors") {
        group = "build"
        description = "Explicitly regenerate the checked-in ${spec.taskInfix} accessor sample."
        mainClass.set(application.mainClass)
        classpath = sourceSets["main"].runtimeClasspath
        inputs.file(schema)
        outputs.file(output)
        args(schema.asFile.absolutePath, output.asFile.absolutePath)
    }
    tasks.register<JavaExec>("check${spec.taskInfix}Accessors") {
        group = "verification"
        description = "Check the ${spec.taskInfix} accessor sample without rewriting it."
        mainClass.set(application.mainClass)
        classpath = sourceSets["main"].runtimeClasspath
        args("--check", schema.asFile.absolutePath, output.asFile.absolutePath)
        mustRunAfter(generate)
    }
    generate
}

tasks.register("generateLivingWorldAccessors") {
    group = "build"
    description = "Regenerate every livingworld accessor sample."
    dependsOn(livingWorldGenerated)
}

tasks.register("checkLivingWorldAccessors") {
    group = "verification"
    description = "Check every livingworld accessor sample without rewriting them."
    dependsOn(livingWorldAccessors.map { "check${it.taskInfix}Accessors" })
}
