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
    "mod-family/src/generated/kotlin/com/eratw/port/modfamily/generated/FamilyAccessors.kt",
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

val freshSources = layout.buildDirectory.dir("generated/testAccessors")
val generateTestAccessors by tasks.registering(JavaExec::class) {
    mainClass.set(application.mainClass)
    classpath = sourceSets["main"].runtimeClasspath
    val schema = layout.projectDirectory.file("src/test/resources/fresh.schema.tsv")
    inputs.file(schema)
    outputs.dir(freshSources)
    args(schema.asFile.absolutePath, freshSources.get().file("FreshAccessors.kt").asFile.absolutePath)
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
    systemProperty("accessors.freshOutput", freshSources.get().file("FreshAccessors.kt").asFile.absolutePath)
}
