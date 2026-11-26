package com.eratw.port.modgen

/**
 * 类型化键符号表：生成物里的 `ComponentKey("...")`/`ArchetypeKey("...")` 字面量，加上声明文件里的
 * 别名（`val FACT: ArchetypeKey = MemoryKeys.archetype`）。实参解析先精确匹配 `Object.prop`，
 * 再退回唯一的简单名 —— 与 Kotlin 的作用域直觉一致，但不需要真编译。
 */
internal class Symbols(
    private val literals: Map<String, String>,
    private val aliases: Map<String, String>,
) {
    /**
     * 解析实参到字面量值；别名链经 [resolveWithin] 展开。
     *
     * 链上环（源码文本 `val x: ComponentKey = x` 这类写一半的非法 Kotlin）按断链处理返回
     * null —— 递归无界会栈溢出，把整个 check-mod 进程炸掉，坏输入必须落在可读诊断上。
     */
    fun resolve(arg: String): String? = resolveWithin(arg, HashSet())

    private fun resolveWithin(arg: String, visited: MutableSet<String>): String? {
        if (!visited.add(arg)) return null
        val simple = arg.substringAfterLast(ModCheckSymbols.PREFIX_SEPARATOR)
        return literals[arg]
            ?: aliases[arg]?.let { resolveWithin(it, visited) }
            ?: literals.filterKeys { it.substringAfterLast(ModCheckSymbols.PREFIX_SEPARATOR) == simple }
                .values.singleOrNull()
            ?: aliases.filterKeys { it.substringAfterLast(ModCheckSymbols.PREFIX_SEPARATOR) == simple }
                .values.singleOrNull()?.let { resolveWithin(it, visited) }
    }
}

/** 符号表构建：按 `object` 归属记 `Object.prop` 字面量，别名链留给 [Symbols.resolve] 递归。 */
internal object ModCheckSymbols {
    const val PREFIX_SEPARATOR = "."
    private const val GROUP_INDEX = 1
    private const val SECOND_GROUP_INDEX = 2
    private const val EMPTY = 0

    private val STRING_LITERAL = Regex("\"([^\"]*)\"")
    private val COMPONENT_KEY = Regex("""val ([A-Za-z_]\w*)\s*:\s*ComponentKey\s*=\s*ComponentKey\(\s*"([^"]*)"\s*\)""")
    private val ARCHETYPE_KEY =
        Regex("""val ([A-Za-z_]\w*)\s*:\s*ArchetypeKey\s*=\s*ArchetypeKey\(\s*"([^"]*)"\s*\)""")
    private val KEY_ALIAS = Regex("""val ([A-Za-z_]\w*)\s*:\s*(?:ComponentKey|ArchetypeKey)\s*=\s*([A-Za-z_][\w.]*)""")
    private val OBJECT_OPEN = Regex("""\bobject\s+([A-Za-z_]\w*)""")

    fun build(sources: List<SourceFile>): Symbols {
        val literals = mutableMapOf<String, String>()
        val aliases = mutableMapOf<String, String>()
        sources.forEach { source -> scan(source, literals, aliases) }
        return Symbols(literals, aliases)
    }

    /** 版本/包身份实参解析：字符串字面量，或声明文件里唯一命名的 String 常量。 */
    fun resolveValue(expression: String, constants: Map<String, String>): String? {
        val trimmed = expression.trim()
        val literal = STRING_LITERAL.matchEntire(trimmed)
        return if (literal != null) {
            literal.groupValues[GROUP_INDEX]
        } else {
            constants[trimmed.substringAfterLast(PREFIX_SEPARATOR)]
        }
    }

    private fun scan(source: SourceFile, literals: MutableMap<String, String>, aliases: MutableMap<String, String>) {
        val state = State()
        source.code.forEach { line -> applyLine(line.text.trim(), state, literals, aliases) }
    }

    private class State(var objectName: String = "", var depth: Int = EMPTY)

    private fun applyLine(
        text: String,
        state: State,
        literals: MutableMap<String, String>,
        aliases: MutableMap<String, String>,
    ) {
        val open = OBJECT_OPEN.find(text)
        if (open != null) {
            state.objectName = open.groupValues[GROUP_INDEX]
            state.depth = braceDelta(text)
            return
        }
        recordSymbols(text, state.objectName, literals, aliases)
        if (state.objectName.isEmpty()) return
        state.depth += braceDelta(text)
        if (state.depth <= EMPTY) state.objectName = ""
    }

    private fun recordSymbols(
        text: String,
        objectName: String,
        literals: MutableMap<String, String>,
        aliases: MutableMap<String, String>,
    ) {
        val prefix = if (objectName.isEmpty()) "" else objectName + PREFIX_SEPARATOR
        val record = { match: MatchResult ->
            literals[prefix + match.groupValues[GROUP_INDEX]] = match.groupValues[SECOND_GROUP_INDEX]
        }
        COMPONENT_KEY.findAll(text).forEach(record)
        ARCHETYPE_KEY.findAll(text).forEach(record)
        KEY_ALIAS.findAll(text).forEach { match ->
            val target = match.groupValues[SECOND_GROUP_INDEX]
            if (!target.endsWith("Key")) aliases[prefix + match.groupValues[GROUP_INDEX]] = target
        }
    }

    private fun braceDelta(text: String): Int = text.count { it == '{' } - text.count { it == '}' }
}
