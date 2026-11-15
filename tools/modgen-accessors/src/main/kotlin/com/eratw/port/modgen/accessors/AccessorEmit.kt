package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.FieldType

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
    // Value-class parameters mangle JVM names; reservations use their Kotlin source names.
    private val reservedMethods = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet() +
        setOf("equals", "hashCode", "toString")

    fun emit(schema: SchemaDecl): String {
        require(schema.packageName.substringBefore('.') != "kotlin" &&
            schema.packageName.split('.').all { identifier.matches(it) && it !in keywords }
        ) {
            "Invalid Kotlin package: ${schema.packageName}"
        }
        require(identifier.matches(schema.accessorName) && schema.accessorName !in keywords &&
            schema.accessorName !in setOf("EntityApi", "EntityId", "Long", "String", "Boolean")
        ) { "Invalid or conflicting accessor class: ${schema.accessorName}" }
        require(identifier.matches(schema.component.name)) { "Invalid component name: ${schema.component.name}" }
        val declaredNames = mutableSetOf<String>()
        val methodOwners = mutableMapOf<String, String>()
        val methods = schema.component.fields.map { field ->
            val getter = camelCase(field.name)
            require(declaredNames.add(field.name.uppercase())) { "Duplicate field name: ${field.name}" }
            require(!field.isEntityRef || field.type == FieldType.INT) { "EntityRef field must be INT: ${field.name}" }
            val setter = "set" + getter.replaceFirstChar { it.uppercaseChar() }
            for (method in listOf(getter, setter)) {
                require(method !in reservedMethods) { "Field ${field.name} conflicts with EntityApi member: $method" }
                val previous = methodOwners.putIfAbsent(method, field.name)
                require(previous == null) { "Generated method $method conflicts between fields $previous and ${field.name}" }
            }
            Triple(field, getter, setter)
        }
        return buildString {
            append("package ${schema.packageName}\n\n")
            append("import com.eratw.port.modapi.EntityApi\n")
            append("import com.eratw.port.modapi.EntityId\n\n")
            append("class ${schema.accessorName}(private val entities: EntityApi) {\n")
            for ((field, getter, setter) in methods) {
                // 键 = 字段名本身，不拼组件名：引擎按 `f.name.uppercase()` 单张表寻址
                // （EntityKernel.kt:57-58、270-271，KernelEntityApi 原样透传），组件名不参与。
                // 拼成「组件名.字段名」会静默撞上失败哨兵（读 0 / 读 "" / 写 false）而声明事务全绿。
                val key = field.name
                val type = when {
                    field.isEntityRef -> "EntityId"
                    field.type == FieldType.INT -> "Long"
                    else -> "String"
                }
                // P1-6 ② / E 车：EntityRef 字段改走类型化 getRef/setRef，生成物里
                // 0 个 .raw、0 个 EntityId(getInt(...))。底层仍是同一张 INT 列
                // （KernelEntityApi 只做 EntityId 包装/解包），不改 codec 不改列存。
                val suffix = when {
                    field.isEntityRef -> "Ref"
                    field.type == FieldType.INT -> "Int"
                    else -> "Str"
                }
                val read = "entities.get$suffix(e, \"$key\")"
                append("    fun $getter(e: EntityId): $type = $read\n")
                append("    fun $setter(e: EntityId, v: $type): Boolean = entities.set$suffix(e, \"$key\", v)\n")
            }
            append("}\n")
        }
    }

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
