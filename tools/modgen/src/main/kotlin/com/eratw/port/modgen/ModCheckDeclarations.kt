package com.eratw.port.modgen

/**
 * Declarations/manifest 面：@ModCarrier 的包身份自洽 + schema 组件/原型名都有对应的声明调用。
 *
 * 只扫含 `@ModCarrier` 的文件找调用点 —— 业务代码里也有 `archetype(...)` 查询计划（例如
 * mod-memory 的 `MemoryMod`），那些不是声明面，不能当证据。
 */
internal object ModCheckDeclarations {
    private const val PREFIX_SEPARATOR = "."
    private const val GROUP_INDEX = 1
    private const val SECOND_GROUP_INDEX = 2

    private val CARRIER = Regex("""@ModCarrier\b""")
    private val PACKAGE_ID_LITERAL = Regex("""const val PACKAGE_ID: String = "([^"]*)"""")
    private val PACKAGE_ID_OVERRIDE =
        Regex("""override val packageId: String[^=]*=\s*([A-Za-z0-9_.]+|"[^"]*")""")
    private val MANIFEST_CALL = Regex("""modDeclarations\s*\(\s*([A-Za-z0-9_.]+)\s*,\s*version\s*=\s*([^,)]+)""")
    private val CONST_STRING = Regex("""(?:const )?val ([A-Za-z_]\w*): String = "([^"]*)"""")
    private val COMPONENT_CALL = Regex("""\bcomponent\s*\(\s*([^)]+?)\s*\)""")
    private val ARCHETYPE_CALL = Regex("""\barchetype\s*\(\s*([^)]+?)\s*\)""")

    fun check(schemas: List<LoadedSchema>, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val declarationFiles = sources.filter { source -> source.code.any { CARRIER.containsMatchIn(it.text) } }
        if (declarationFiles.isEmpty()) {
            findings += Finding(Severity.WARN, PREFIX_SEPARATOR, null, "未发现 @ModCarrier 声明文件；本项跳过")
            return
        }
        declarationFiles.forEach { file -> checkCarrier(file, findings) }
        val symbols = ModCheckSymbols.build(sources)
        checkSchemaNames(schemas, declarationFiles, symbols, findings)
    }

    /** 载体身份：PACKAGE_ID 字面量 = packageId = manifest 首个实参；version 必须解析成非空字符串。 */
    private fun checkCarrier(file: SourceFile, findings: MutableList<Finding>) {
        val packageLiteral = find(file, PACKAGE_ID_LITERAL)
        val packageOverride = find(file, PACKAGE_ID_OVERRIDE)
        val manifestCall = find(file, MANIFEST_CALL)
        val constants = constStrings(file)
        if (packageLiteral == null) {
            val message = "@ModCarrier 缺少 const val PACKAGE_ID；" +
                "补 const val PACKAGE_ID = \"<包名>\"，并与 override val packageId、manifest 首参三处一致"
            findings += Finding(Severity.ERROR, file.label, null, message)
        }
        if (packageOverride == null) {
            val message = "@ModCarrier 缺少 override val packageId；" +
                "EraMod 的 override val packageId 应返回与 PACKAGE_ID 相同的包名"
            findings += Finding(Severity.ERROR, file.label, null, message)
        }
        if (manifestCall == null) {
            val message = "未找到 modDeclarations(...) manifest 声明；" +
                "@ModCarrier 载体类里应有 modDeclarations(<包名>, version = \"<版本>\") 调用"
            findings += Finding(Severity.ERROR, file.label, null, message)
            return
        }
        val expectedPackage = packageLiteral?.match?.groupValues?.get(GROUP_INDEX)
        val actualPackage = packageOverride?.let {
            ModCheckSymbols.resolveValue(it.match.groupValues[GROUP_INDEX], constants)
        }
        if (expectedPackage != null && actualPackage != null && expectedPackage != actualPackage) {
            val message = "packageId 与 PACKAGE_ID 不一致：$actualPackage vs $expectedPackage"
            findings += Finding(Severity.ERROR, file.label, packageOverride.line, message)
        }
        val manifestPackage = ModCheckSymbols.resolveValue(manifestCall.match.groupValues[GROUP_INDEX], constants)
        if (expectedPackage != null && manifestPackage != null && expectedPackage != manifestPackage) {
            val message = "manifest 包身份与 PACKAGE_ID 不一致：$manifestPackage vs $expectedPackage"
            findings += Finding(Severity.ERROR, file.label, manifestCall.line, message)
        }
        val version = ModCheckSymbols.resolveValue(manifestCall.match.groupValues[SECOND_GROUP_INDEX], constants)
        if (version == null) {
            findings += Finding(Severity.ERROR, file.label, manifestCall.line, "manifest.version 无法解析为字符串")
        } else if (version.isBlank()) {
            findings += Finding(Severity.ERROR, file.label, manifestCall.line, "manifest.version 为空")
        }
    }

    /** schema 的组件名/原型名必须出现在声明文件的 `component(...)`/`archetype(...)` 实参里。 */
    private fun checkSchemaNames(
        schemas: List<LoadedSchema>,
        declarationFiles: List<SourceFile>,
        symbols: Symbols,
        findings: MutableList<Finding>,
    ) {
        if (schemas.isEmpty()) return
        val declaredComponents = declarationFiles
            .flatMap { file -> callArgs(file, COMPONENT_CALL).mapNotNull { symbols.resolve(it.first) } }
            .toSet()
        val declaredArchetypes = declarationFiles
            .flatMap { file -> callArgs(file, ARCHETYPE_CALL).mapNotNull { symbols.resolve(it.first) } }
            .toSet()
        schemas.forEach { schema ->
            schema.decl.components.forEach { spec ->
                val name = spec.declaration.name
                if (name !in declaredComponents) {
                    val message = "schema 组件 $name 未在 Declarations 的 component(...) 中声明"
                    findings += Finding(Severity.ERROR, schema.label, schema.componentLines[name], message)
                }
            }
            val archetype = schema.decl.archetype
            if (archetype != null && archetype !in declaredArchetypes) {
                val message = "schema 原型 $archetype 未在 Declarations 的 archetype(...) 中声明"
                findings += Finding(Severity.ERROR, schema.label, schema.archetypeLine, message)
            }
        }
    }

    private fun callArgs(file: SourceFile, pattern: Regex): List<Pair<String, Int>> =
        file.code.flatMap { line ->
            pattern.findAll(line.text).map { it.groupValues[GROUP_INDEX].trim() to line.number }
        }

    /**
     * 抽模块自述身份（pack-mod 写包级元数据用）：第一个能同时解析出包名与版本的 `@ModCarrier`。
     *
     * 与 [checkCarrier] 同一套正则与 [ModCheckSymbols.resolveValue] 口：这里只取值、不记账——
     * 报错与否已由 `check-mod` 决定（pack-mod 在 ERROR 上直接拒打包）。取不到 ⇒ null
     * （打包器不写元数据，导入端按「没有元数据」回落 `"1"`，与改造前行为逐字相同）。
     */
    fun declaredPackMeta(sources: List<SourceFile>): DeclaredPackMeta? =
        sources
            .filter { source -> source.code.any { line -> CARRIER.containsMatchIn(line.text) } }
            .firstNotNullOfOrNull { source -> declaredPackMetaOf(source) }

    private fun declaredPackMetaOf(file: SourceFile): DeclaredPackMeta? {
        val manifestCall = find(file, MANIFEST_CALL)
        val constants = constStrings(file)
        val packageId = find(file, PACKAGE_ID_LITERAL)?.match?.groupValues?.get(GROUP_INDEX)
            ?: manifestCall?.let { call ->
                ModCheckSymbols.resolveValue(call.match.groupValues[GROUP_INDEX], constants)
            }
        val version = manifestCall?.let { call ->
            ModCheckSymbols.resolveValue(call.match.groupValues[SECOND_GROUP_INDEX], constants)
        }
        return if (manifestCall == null || packageId.isNullOrBlank() || version.isNullOrBlank()) {
            null
        } else {
            DeclaredPackMeta(packageId, version)
        }
    }

    private class Found(val match: MatchResult, val line: Int)

    private fun find(file: SourceFile, pattern: Regex): Found? =
        file.code.firstNotNullOfOrNull { line -> pattern.find(line.text)?.let { Found(it, line.number) } }

    private fun constStrings(file: SourceFile): Map<String, String> =
        file.code.flatMap { line ->
            CONST_STRING.findAll(line.text).map { it.groupValues[GROUP_INDEX] to it.groupValues[SECOND_GROUP_INDEX] }
        }.toMap()
}

/**
 * 模块自述身份：`@ModCarrier` 里的 `const val PACKAGE_ID` 与 `modDeclarations(..., version = …)`
 * 的解析值（pack-mod 写进 [PACK_META_ENTRY_NAME] 条目）。
 */
internal class DeclaredPackMeta(val packageId: String, val version: String)

