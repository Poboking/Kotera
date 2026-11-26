package com.eratw.port.modapi

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 作者面签名快照的反射渲染与落盘支点（机制层，不含断言；断言在 ModApiSurfaceSnapshotTest）。
 *
 * 作者面是给第三方群友的版本承诺：VERSION 每 +1，公开类型 / 签名 / KDoc 版本表要一起动。
 * 单靠人眼 review 挡不住「签名悄悄漂移」或「bump 了 VERSION 忘补 KDoc」，故把 JVM 反射面
 * 渲染成确定性文本，供快照闸逐行比对。渲染口径故意保留 JVM 名原样（含 value class 的
 * -impl / -hash 名称修饰、internal 成员的 $mod_api 后缀会被过滤掉），只加「类型 | 成员」
 * 两级结构，保证同一编译器下逐字稳定。
 *
 * 行格式：`<类型全名> | <kind>` 是类型行；成员行是
 * `<类型全名> | [static ]fun <JVM 名>(<参数类型>): <返回类型>`、
 * `<类型全名> | [static ]val <JVM 名>: <类型>`（0 参 getter 归并为属性）、
 * `<类型全名> | [static ]field <名>: <类型>[ = <常量值>]`。
 */
internal object ModApiSurfaceSupport {

    /** 作者面根包：反射只扫这个包在 main 输出目录里的 class 文件。 */
    const val PACKAGE_NAME = "com.eratw.port.modapi"

    private const val PACKAGE_PATH = "com/eratw/port/modapi"
    private const val CLASS_SUFFIX = ".class"
    private const val SNAPSHOT_RELATIVE = "src/test/resources/mod-api-surface.txt"
    private const val MOD_API_MARKER = "src/main/kotlin/com/eratw/port/modapi/ModApi.kt"
    private const val SETTINGS_MARKER = "settings.gradle.kts"
    private const val REFRESH_FLAG = "eratw.refreshApiSurface"
    private const val REFRESH_ENV = "ERATW_REFRESH_API_SURFACE"
    private const val NEWLINE = "\n"
    private const val CR = "\r"
    private const val STATIC_PREFIX = "static "
    private const val VARARGS_SUFFIX = "..."
    private const val ARRAY_SUFFIX = "[]"
    private const val ACCESS_PREFIX = "access$"
    private const val INSTANCE_FIELD = "INSTANCE"
    private const val COMPANION_FIELD = "Companion"
    private const val GET_PREFIX = "get"
    private const val IS_PREFIX = "is"
    private const val KIND_ANNOTATION = "annotation"
    private const val KIND_INTERFACE = "interface"
    private const val KIND_ENUM = "enum"
    private const val KIND_OBJECT = "object"
    private const val KIND_CLASS = "class"
    private const val CLASS_OUTPUT_MISSING = "找不到 mod-api 编译输出目录（反射扫不了作者面）"

    private val headerNotes = listOf(
        "// 由 ModApiSurfaceSnapshotTest 反射生成，勿手改；类名/签名/KDoc 版本表三处联动。",
        "// 刷新：.\\gradlew.bat -Deratw.refreshApiSurface=true :mod-api:test，然后按提示 bump VERSION 并补 KDoc。",
        "// 行格式：<类型> | <kind> 为类型行；成员行为 [static ]fun|val|field，JVM 名原样保留。"
    )
    private val booleanPrimitive: Class<*> = java.lang.Boolean.TYPE
    private val constantTypes: Set<Class<*>> = setOf(
        Int::class.java, Long::class.java, Short::class.java, Byte::class.java, Char::class.java,
        Boolean::class.java, Double::class.java, Float::class.java, String::class.java
    )
    private val excludedNestedTypes: Set<String> = setOf("DefaultImpls", "WhenMappings")
    private val lambdaNested: Regex = Regex("""\d+""")

    /** 渲染整份作者面文本（首行 `// surface v<N>` + 说明 + 按类型全名排序的类型块）。 */
    fun renderSurface(): String {
        val lines = mutableListOf(ModApiSurfaceGate.headerLine(ModApi.VERSION))
        lines += headerNotes
        surfaceClasses().forEach { type -> lines += typeLines(type) }
        return lines.joinToString(NEWLINE) + NEWLINE
    }

    /** 反射列出作者面全部 public 类型（按显示名排序，构造稳定文本的基础）。 */
    fun surfaceClasses(): List<Class<*>> {
        val loader = ModApi::class.java.classLoader
        return classBinaryNames()
            .mapNotNull { binaryName -> loadOrNull(binaryName, loader) }
            .filter(::isSurfaceClass)
            .sortedBy(::displayName)
    }

    /** 类型显示名：二进制名里的 `$` 换成 `.`，作者读起来就是源码里的嵌套类路径。 */
    fun displayName(type: Class<*>): String = type.name.replace('$', '.')

    /**
     * 刷新模式入口：置 `-Deratw.refreshApiSurface=true`（或环境变量 `ERATW_REFRESH_API_SURFACE=true`）
     * 时先把当前反射面写回快照再返回；未置位时纯只读。刷新写回幂等（内容相同不落盘）。
     */
    fun snapshotText(): String {
        val rendered = renderSurface()
        if (refreshRequested() && writeSnapshotIfChanged(rendered)) {
            println("作者面快照已刷新：" + snapshotPath())
        }
        return rendered
    }

    fun snapshotPath(): Path = moduleRoot().resolve(SNAPSHOT_RELATIVE)

    /** 读入库快照并归一换行（工作树可能是 CRLF），供断言逐行比较。 */
    fun readSnapshotNormalized(): String = normalizeNewlines(Files.readString(snapshotPath()))

    /** 读 ModApi.kt 源码（断言 c 的 KDoc 版本表与 VERSION 常量文本都从这里取）。 */
    fun readModApiSource(): String = Files.readString(moduleRoot().resolve(MOD_API_MARKER))

    fun moduleRoot(): Path {
        val cwd = Paths.get("").toAbsolutePath()
        return ancestors(cwd).firstOrNull { dir -> Files.isRegularFile(dir.resolve(MOD_API_MARKER)) }
            ?: error("找不到 mod-api 模块根：从 $cwd 向上找不到 $MOD_API_MARKER")
    }

    fun repoRoot(): Path {
        val cwd = Paths.get("").toAbsolutePath()
        return ancestors(cwd).firstOrNull { dir -> Files.isRegularFile(dir.resolve(SETTINGS_MARKER)) }
            ?: error("找不到 eratw-android 仓库根：从 $cwd 向上找不到 $SETTINGS_MARKER")
    }

    fun normalizeNewlines(text: String): String =
        text.replace(CR + NEWLINE, NEWLINE).replace(CR, NEWLINE)

    fun refreshRequested(): Boolean =
        System.getProperty(REFRESH_FLAG)?.toBooleanStrictOrNull() == true ||
            System.getenv(REFRESH_ENV)?.toBooleanStrictOrNull() == true

    /** 写回快照；返回是否真的落盘（内容相同返回 false = 幂等）。保留既有文件的换行风格。 */
    fun writeSnapshotIfChanged(rendered: String): Boolean =
        writeSnapshotIfChangedAt(rendered, snapshotPath())

    /** [writeSnapshotIfChanged] 的可指定路径版本（幂等负控用临时文件验证，不碰工作树）。 */
    fun writeSnapshotIfChangedAt(rendered: String, path: Path): Boolean {
        val target = rendered.replace(NEWLINE, detectedNewline(path))
        if (Files.isRegularFile(path) && Files.readString(path) == target) return false
        Files.createDirectories(path.parent)
        Files.writeString(path, target)
        return true
    }

    private fun detectedNewline(path: Path): String {
        val existing = if (Files.isRegularFile(path)) Files.readString(path) else ""
        return if (existing.contains(CR + NEWLINE)) CR + NEWLINE else NEWLINE
    }

    private fun ancestors(start: Path): Sequence<Path> =
        generateSequence(start.normalize()) { dir -> dir.parent }

    private fun classBinaryNames(): List<String> {
        val dir = classOutputDir()
        val simples = Files.newDirectoryStream(dir).use { entries ->
            entries.filter { entry ->
                Files.isRegularFile(entry) && entry.fileName.toString().endsWith(CLASS_SUFFIX)
            }
                .map { entry -> entry.fileName.toString().removeSuffix(CLASS_SUFFIX) }
                .sorted()
        }
        return simples.map { simple -> PACKAGE_NAME + "." + simple }
    }

    private fun classOutputDir(): Path {
        val location = ModApi::class.java.protectionDomain?.codeSource?.location
        val root = location?.let { url -> runCatching { Paths.get(url.toURI()) }.getOrNull() }
        val dir = root?.resolve(PACKAGE_PATH)
        return dir?.takeIf { candidate -> Files.isDirectory(candidate) } ?: error(CLASS_OUTPUT_MISSING)
    }

    private fun loadOrNull(binaryName: String, loader: ClassLoader): Class<*>? =
        runCatching { Class.forName(binaryName, false, loader) }.getOrNull()

    private fun isSurfaceClass(type: Class<*>): Boolean {
        val nested = type.name.substringAfterLast('$')
        val plain = Modifier.isPublic(type.modifiers) && !type.isSynthetic &&
            !type.isAnonymousClass && !type.isLocalClass
        return plain && nested !in excludedNestedTypes && !lambdaNested.matches(nested)
    }

    private fun hasInstanceField(type: Class<*>): Boolean =
        type.declaredFields.any { field ->
            field.name == INSTANCE_FIELD && Modifier.isStatic(field.modifiers) && field.type == type
        }

    private fun typeLines(type: Class<*>): List<String> {
        val name = displayName(type)
        val lines = mutableListOf(name + " | " + typeKind(type))
        lines += memberLines(type, name)
        return lines
    }

    private fun typeKind(type: Class<*>): String = when {
        type.isAnnotation -> KIND_ANNOTATION
        type.isInterface -> KIND_INTERFACE
        type.isEnum -> KIND_ENUM
        hasInstanceField(type) -> KIND_OBJECT
        else -> KIND_CLASS
    }

    private fun memberLines(type: Class<*>, name: String): List<String> {
        val methods = type.declaredMethods.filter(::isSurfaceMethod)
        val properties = methods.filter(::isPropertyGetter).sortedBy(::propertyName)
        val functions = methods.filterNot(::isPropertyGetter).sortedBy(::methodSortKey)
        val lines = functions.map { method -> name + " | " + methodLine(method) }.toMutableList()
        lines += properties.map { method -> name + " | " + propertyLine(method) }
        lines += surfaceFields(type).map { field -> name + " | " + fieldLine(field) }
        return lines
    }

    private fun isSurfaceMethod(method: Method): Boolean =
        Modifier.isPublic(method.modifiers) && !method.isSynthetic && !method.isBridge &&
            !method.name.contains('$') && !method.name.startsWith(ACCESS_PREFIX)

    private fun isPropertyGetter(method: Method): Boolean {
        val name = method.name
        val plain = method.parameterTypes.isEmpty() && method.returnType != Void.TYPE
        return plain && (
            (name.startsWith(GET_PREFIX) && name.length > GET_PREFIX.length) ||
                (name.startsWith(IS_PREFIX) && method.returnType == booleanPrimitive)
            )
    }

    private fun propertyName(method: Method): String {
        val name = method.name
        if (!name.startsWith(GET_PREFIX)) return name
        val body = name.removePrefix(GET_PREFIX)
        return if (body.length > 1 && body[1].isLowerCase()) body.replaceFirstChar { it.lowercaseChar() } else body
    }

    private fun propertyLine(method: Method): String =
        modifierPrefix(method.modifiers) + "val " + propertyName(method) + ": " + method.genericReturnType.typeName

    private fun methodLine(method: Method): String =
        modifierPrefix(method.modifiers) + "fun " + method.name + "(" + parameterList(method) + "): " +
            method.genericReturnType.typeName

    private fun methodSortKey(method: Method): String =
        method.name + "(" + parameterList(method) + ")" + method.genericReturnType.typeName

    private fun parameterList(method: Method): String {
        val types = method.genericParameterTypes.map { type -> type.typeName }.toMutableList()
        if (method.isVarArgs && types.isNotEmpty()) {
            types[types.lastIndex] = types.last().removeSuffix(ARRAY_SUFFIX) + VARARGS_SUFFIX
        }
        return types.joinToString(", ")
    }

    private fun modifierPrefix(modifiers: Int): String =
        if (Modifier.isStatic(modifiers)) STATIC_PREFIX else ""

    private fun surfaceFields(type: Class<*>): List<Field> =
        type.declaredFields.filter(::isSurfaceField).sortedBy { field -> field.name }

    private fun isSurfaceField(field: Field): Boolean {
        val name = field.name
        return Modifier.isPublic(field.modifiers) && !field.isSynthetic &&
            name != INSTANCE_FIELD && name != COMPANION_FIELD && !name.startsWith('$')
    }

    private fun fieldLine(field: Field): String {
        val value = constantValue(field)?.let { literal -> " = " + literal }.orEmpty()
        return modifierPrefix(field.modifiers) + "field " + field.name + ": " + field.genericType.typeName + value
    }

    private fun constantValue(field: Field): String? {
        val constant = Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) &&
            field.type in constantTypes
        return if (constant) runCatching { field.get(null) }.getOrNull()?.let(::literalOf) else null
    }

    private fun literalOf(value: Any): String = when (value) {
        is String -> "\"" + value + "\""
        is Char -> "'" + value + "'"
        else -> value.toString()
    }
}
