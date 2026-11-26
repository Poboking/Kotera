package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType

/**
 * 从 [SchemaDecl] 生成 mod 用的三个类型（2026-11-16 升级）。
 *
 * ## 生成什么
 *
 * - `<base>Keys`：类型化声明键。字段名在整个 mod 里**只在这里出现一次**，声明文件写
 *   `int(FamilyKeys.age)`、视图写 `entities.getInt(id, FamilyKeys.age)`，谁都不用再手打
 *   `"age"`。旧生成器直接往访问器里塞字符串字面量，于是「声明一份、常量一份、访问器一份」
 *   三处手抄，任一处打错都是静默失败哨兵（读 0 / 读空串 / 写 false）。
 * - `<base>View`：`var` 属性视图。调用点从 `accessors.setAge(child, 0L)` 变成 `child.age = 0`。
 * - `<base>Facade`：`create()`（仅当 schema 声明了 archetype）与 `of(id)`。
 *
 * ## 属性 setter 丢掉 Boolean 返回值的取舍
 *
 * 引擎的 `setInt/setStr/setRef` 返回 Boolean，false 表示「字段未在声明里存在」或
 * 「实体不存在」。属性 setter 必须返回 Unit，所以这个信号在这里被忽略 —— 换来的是
 * `child.age = 0` 这种能读的调用点。要判失败就别用属性，直接走
 * `entities.setInt(id, FamilyKeys.age, v)`：键是同一份，写错了编译期就红，不会退化成静默。
 */
object AccessorEmit {
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val fieldIdentifier = Regex("[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z][A-Za-z0-9]*)*")
    private val keywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
        "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
        "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion",
        "const", "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline",
        "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
        "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg", "value",
        "context",
    )

    /** `View` 自己占用的成员名；字段折成这些名字会撞成员的 JVM 名，直接拒绝。 */
    private val viewReserved = setOf("id", "entities", "equals", "hashCode", "toString")

    /** `Keys` 对象自己占用的成员名。 */
    private val keysReserved = setOf("component", "archetype", "equals", "hashCode", "toString")

    /** 三段流水线入口：校验/解析（[analyze]）→ 渲染（[render]）。产物与旧实现逐字节一致。 */
    fun emit(schema: SchemaDecl): String = render(analyze(schema))

    /**
     * 校验 schema 并折出渲染用的中间模型。
     *
     * 下面每条 require 都对应一个实测踩过的静默失败形态（线名少一个字符、属性撞名后生成物
     * 编译不过、关键字把字面量变成别的东西）；KDoc 里写过判例的注释一律原样保留，别因为
     * 「看起来不会发生」删掉。
     */
    private fun analyze(schema: SchemaDecl): Emission {
        require(
            schema.packageName.substringBefore('.') != "kotlin" &&
                schema.packageName.split('.').all { identifier.matches(it) && it !in keywords },
        ) {
            "Invalid Kotlin package: ${schema.packageName}"
        }
        val base = schema.baseName
        require(
            identifier.matches(base) && base !in keywords &&
                base !in setOf("EntityApi", "EntityId", "Long", "String", "Boolean"),
        ) { "Invalid or conflicting schema base name: $base" }
        // 组件名只是线格式里的一个字符串（`ComponentKey("…")` 的字面量），**不参与字段寻址**，
        // 所以它不必是 Kotlin 标识符 —— `memory.factData`、`lw.char.persona` 这种带点的名字是
        // 正常的。只要塞进字符串字面量不炸就行。这条校验早期是「组件名当 Kotlin 类名用」的
        // 残留，2026-11-16 迁移 mod-memory 时撞上。
        require(schema.components.isNotEmpty()) { "Schema declares no component: ${schema.baseName}" }
        schema.components.forEach { spec ->
            require(spec.declaration.name.isNotBlank() && literalSafe(spec.declaration.name)) {
                "Invalid component name: ${spec.declaration.name}"
            }
        }
        // 原型名同样只当 `ArchetypeKey("…")` 的字面量，受同一套约束。这条检查漏过一次
        // （只挡了 tab/换行），实测 `a"b` / `$x` / `a\b` 都能过：前者产物不是合法 Kotlin，
        // 中者变成字符串模板插值，后者是退格转义 —— **静默改名**，线名少一个字符，最难查。
        val archetype = schema.archetype
        require(archetype == null || (archetype.isNotBlank() && literalSafe(archetype))) {
            "Invalid archetype name: $archetype"
        }
        // 生成物 KDoc 里点名的组件清单（单组件时就是那一个名字，与旧版逐字节一致）。
        val componentNames = schema.components.joinToString("`, `") { it.declaration.name }
        val componentKeys = resolveComponents(schema)
        val properties = resolveProperties(schema, componentKeys)
        return Emission(schema, componentNames, properties, resolveImports(schema, properties))
    }

    /**
     * 校验并收集 `Keys` 里的组件键属性名。
     *
     * 多组件时 `Keys` 里一个组件一个键属性 —— 它们自己不能重名，也不能与字段属性重名
     * （同一个 object 里两个成员同名 = 编译不过，且报错位置在生成物里，很难查回 schema）。
     */
    private fun resolveComponents(schema: SchemaDecl): Set<String> {
        val componentKeyProperties = mutableSetOf<String>()
        schema.components.forEach { spec ->
            require(identifier.matches(spec.keyProperty) && spec.keyProperty !in keywords) {
                "Invalid key property for component ${spec.declaration.name}: ${spec.keyProperty}"
            }
            require(componentKeyProperties.add(spec.keyProperty)) {
                "Duplicate key property: ${spec.keyProperty}"
            }
        }
        return componentKeyProperties
    }

    /** 把每个字段折成 [TypedField]，并校验字段之间、字段与组件键之间的命名不撞。 */
    private fun resolveProperties(schema: SchemaDecl, componentKeys: Set<String>): List<TypedField> {
        val seen = mutableSetOf<String>()
        val properties = schema.fields.map { field -> typedField(schema, field, seen) }
        val propertyNames = mutableSetOf<String>()
        properties.forEach {
            require(propertyNames.add(it.property)) { "Duplicate property name: ${it.property}" }
        }
        // 键属性名与字段属性名同住 `Keys`：撞了就在生成物里编译不过，这里先拦。
        properties.forEach {
            require(it.property !in componentKeys) {
                "Field ${it.decl.name} collides with a component key property: ${it.property}"
            }
        }
        val fieldNames = schema.fields.map { it.name }.toSet()
        val unknownOverrides = schema.propertyOverrides.keys - fieldNames
        require(unknownOverrides.isEmpty()) {
            "Property override targets an unknown field: $unknownOverrides"
        }
        return properties
    }

    /**
     * 折出一个字段的属性名并逐条校验；[seenWireNames] 跨字段累积，按大写归一判重。
     *
     * 属性名默认由字段名折出；命中 Kotlin 关键字（`object` 这种）时由 schema 第 5 列
     * 指定替代名 —— 字段名是线格式不能动，让属性名让路（见 SchemaDecl.propertyOverrides）。
     */
    private fun typedField(
        schema: SchemaDecl,
        field: FieldDecl,
        seenWireNames: MutableSet<String>,
    ): TypedField {
        val property = schema.propertyOverrides[field.name] ?: camelCase(field.name)
        require(identifier.matches(property) && property !in keywords) {
            "Invalid property name for ${field.name}: $property"
        }
        require(seenWireNames.add(field.name.uppercase())) { "Duplicate field name: ${field.name}" }
        require(!field.isEntityRef || field.type == FieldType.INT) {
            "EntityRef field must be INT: ${field.name}"
        }
        require(property !in viewReserved) { "Field ${field.name} collides with a View member: $property" }
        require(property !in keysReserved) { "Field ${field.name} collides with a Keys member: $property" }
        return TypedField(field, property)
    }

    /** 汇总生成物需要的 import：键工厂、类型化读写扩展、基础类型。 */
    private fun resolveImports(schema: SchemaDecl, properties: List<TypedField>): List<String> {
        // 类型化读写的扩展函数**必须逐个 import**：Kotlin 不会因为同包就看见别的文件里的扩展。
        // 漏了它们的症状不是「找不到符号」而是「Argument type mismatch：FieldKey<Long> 而期望 String」
        // —— 编译器退到 EntityApi 上那个吃 String 的成员方法去了（2026-11-16 实际踩过）。
        val accessorImports = properties.flatMap { listOf("get" + it.suffix, "set" + it.suffix) }.distinct()
        val apiImports = listOf(
            "com.eratw.port.modapi.ComponentKey",
            "com.eratw.port.modapi.EntityApi",
            "com.eratw.port.modapi.EntityId",
            "com.eratw.port.modapi.FieldKey",
        )
        val archetypeImports = if (schema.archetype != null) {
            listOf("com.eratw.port.modapi.ArchetypeKey", "com.eratw.port.modapi.create")
        } else {
            emptyList()
        }
        val propertyImports = properties.map { it.keyFactory.importPath } +
            accessorImports.map { "com.eratw.port.modapi.$it" }
        return (apiImports + archetypeImports + propertyImports).distinct().sorted()
    }

    /**
     * 把中间模型渲染成三份类型。
     *
     * 拼接顺序、换行位置都与旧实现逐字节一致：生成物已入库，`--check` 闸逐字节比对，
     * 这里不许「顺手整理」。
     */
    private fun render(emission: Emission): String = buildString {
        val schema = emission.schema
        val base = schema.baseName
        val archetype = schema.archetype
        append("package ${schema.packageName}\n\n")
        emission.imports.forEach { append("import $it\n") }
        append('\n')

        append(objectDoc(schema))
        append("object ${base}Keys {\n")
        schema.components.forEach { spec ->
            append("    val ${spec.keyProperty}: ComponentKey = ComponentKey(\"${spec.declaration.name}\")\n")
        }
        if (archetype != null) {
            append('\n')
            append("    val archetype: ArchetypeKey = ArchetypeKey(\"$archetype\")\n")
        }
        emission.properties.forEach {
            append('\n')
            append(it.keysBlock)
        }
        append("}\n\n")

        append("/** `${emission.componentNames}` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */\n")
        append("class ${base}View internal constructor(\n")
        append("    private val entities: EntityApi,\n")
        append("    /** 实体身份。 */\n")
        append("    val id: EntityId,\n")
        append(") {\n")
        emission.properties.forEachIndexed { index, property ->
            if (index > 0) append('\n')
            append(property.viewBlock(base))
        }
        append("}\n\n")

        append("/** `${emission.componentNames}` 组件的创建/绑定门面。 */\n")
        append("class ${base}Facade(private val entities: EntityApi) {\n")
        if (archetype != null) {
            append("    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */\n")
            append("    fun create(): ${base}View = ")
            append("${base}View(entities, entities.create(${base}Keys.archetype))\n\n")
        }
        append("    /** 绑定一个已存在的实体。 */\n")
        append("    fun of(id: EntityId): ${base}View = ${base}View(entities, id)\n")
        append("}\n")
    }

    /** 渲染前解析好的中间模型：schema 本体 + 组件名清单（进 KDoc）+ 字段片段 + import 表。 */
    private data class Emission(
        val schema: SchemaDecl,
        val componentNames: String,
        val properties: List<TypedField>,
        val imports: List<String>,
    )

    /**
     * 生成物三个类型的 KDoc。源 schema 的出处写进注释，是为了让人知道「改字段名要改哪份 TSV」，
     * 而不是回头去手改生成文件（`--check` 闸会红）。
     */
    private fun objectDoc(schema: SchemaDecl): String = buildString {
        append("/**\n")
        append(" * 生成物，请勿手改。\n")
        append(" *\n")
        append(" * 源 = `<base>.schema.tsv`（`schema` ${schema.packageName}，组件 `${schema.component.name}`）")
        append("，由 `:tools:modgen-accessors` 生成。\n")
        append(" *\n")
        append(" * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，\n")
        append(" * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。\n")
        append(" */\n")
    }

    /**
     * 一个字段的三种产物片段。
     *
     * 构造参数叫 [decl] 而不是 `field`：`field` 是 Kotlin 的软关键字，在属性访问器内部指代
     * **backing field**，写成 `get() = "${field.type}"` 会被解析成访问 `keysBlock` 自己的
     * backing field（报「Property must be initialized」+「Unresolved reference 'type'」）。
     */
    private class TypedField(val decl: FieldDecl, val property: String) {
        val kotlinType: String = when {
            decl.isEntityRef -> "EntityId"
            decl.type == FieldType.INT -> "Long"
            else -> "String"
        }

        val suffix: String = when {
            decl.isEntityRef -> "Ref"
            decl.type == FieldType.INT -> "Int"
            else -> "Str"
        }

        val keyFactory: KeyFactory = when {
            decl.isEntityRef -> KeyFactory.REF
            decl.type == FieldType.INT -> KeyFactory.INT
            else -> KeyFactory.STR
        }

        val keysBlock: String
            get() = "    /** ${decl.type} 字段。 */\n" +
                "    val $property: FieldKey<$kotlinType> = ${keyFactory.functionName}(\"${decl.name}\")\n"

        fun viewBlock(base: String): String = buildString {
            append("    var $property: $kotlinType\n")
            append("        get() = entities.get$suffix(id, ${base}Keys.$property)\n")
            append("        set(value) {\n")
            append("            entities.set$suffix(id, ${base}Keys.$property, value)\n")
            append("        }\n")
        }
    }

    private enum class KeyFactory(val functionName: String, val importPath: String) {
        INT("intKey", "com.eratw.port.modapi.intKey"),
        STR("strKey", "com.eratw.port.modapi.strKey"),
        REF("refKey", "com.eratw.port.modapi.refKey"),
    }

    /**
     * 名字能不能原样塞进 Kotlin 字符串字面量。
     *
     * 四个字符各自坏在不同地方：`"` 提前闭合字面量（产物不是合法 Kotlin）、`\` 开一个转义
     * （`a\b` 变退格 ⇒ **线名静默少一个字符**，这类错最难查）、`$` 变成模板插值、tab/换行
     * 直接断行。组件名与原型名共用这一条判据（都只做 `XxxKey("…")` 的字面量）。
     */
    private fun literalSafe(name: String): Boolean =
        name.none { it == '"' || it == '\\' || it == '$' || it == '\t' || it == '\n' || it == '\r' }

    /** ASCII words separated by single underscores: fold ALL_CAPS words, lower first initial, upper later initials; preserve other case. */
    private fun camelCase(name: String): String {
        require(fieldIdentifier.matches(name)) { "Invalid field identifier: $name" }
        val words = name.split('_').map { word -> if (word.none { it in 'a'..'z' }) word.lowercase() else word }
        val result = words.first().replaceFirstChar { it.lowercaseChar() } +
            words.drop(1).joinToString("") { it.replaceFirstChar { initial -> initial.uppercaseChar() } }
        require(name !in keywords && result !in keywords) { "Field name is a Kotlin keyword: $name ($result)" }
        return result
    }
}
