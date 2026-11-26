package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType

/**
 * 一个组件的声明 + 它在生成物 `<base>Keys` 里的**键属性名**。
 *
 * 为什么组件要单独带一个属性名：一个原型挂多个组件时（`lw.char.npc` = 性格 + 日程），
 * `Keys` 里得给每个组件各一个 `ComponentKey`，而两个都叫 `component` 显然不行。单组件
 * 的老 schema 缺省叫 `component`（`FamilyKeys.component` 这类已入库生成物因此一个字节不变）。
 */
data class ComponentSpec(
    /** 组件声明本体（名字是线格式，字段名进全局表）。 */
    val declaration: ComponentDecl,
    /** 生成物 `Keys` 里的属性名，如 `component` / `persona` / `schedule`。 */
    val keyProperty: String,
)

/**
 * 一份 schema TSV 的解析结果。
 *
 * [baseName] 是**基名**，生成器由它派生出三个类型：
 * `<base>Keys`（类型化声明键）/ `<base>View`（`var` 属性视图）/ `<base>Facade`（创建与绑定）。
 *
 * [archetype] 可选：给了才生成 `Facade.create()`。没有原型名的 schema 只能绑定已有实体。
 *
 * [components] 是**有序**的：组件序决定生成物里键属性的顺序与字段的展开顺序。字段跨组件
 * 共享**一张全局表**（引擎按字段名寻址，组件名不参与，见 `EntityKernel`），所以多组件
 * 在这里只是「给键分组」，`View` 上的属性仍是平铺的一层 —— 这正是用起来最顺手的形状：
 * 一个实体一个 View，`npc.warmth` / `npc.eveningSlot`，不用先挑组件。
 */
data class SchemaDecl(
    val packageName: String,
    val baseName: String,
    val components: List<ComponentSpec>,
    val archetype: String? = null,
    /**
     * 字段名 → 生成属性名的覆盖表（`field` 行的可选第 5 列）。
     *
     * 存在的理由只有一类：**字段名本身是 Kotlin 硬关键字，而字段名又不能改**。字段名是线格式
     * （`RT2MANIFEST/3` 里逐字节落盘，还挂在已发布的 `.manifest` 资产上），`object` 这种名字
     * 一旦为了好看的属性名去动它，manifest 字节就变了。所以让**属性名**让路：
     * `field  object  INT  true  obj` ⇒ 键仍是 `object`，属性叫 `obj`。
     */
    val propertyOverrides: Map<String, String> = emptyMap(),
) {
    /**
     * 单组件便捷构造：键属性名固定 `component`，与头行第 4 列那种老写法等价。
     *
     * 只在测试与单组件 schema 用手搓 `SchemaDecl` 时方便；多组件一律走 [components]。
     */
    constructor(
        packageName: String,
        baseName: String,
        component: ComponentDecl,
        archetype: String? = null,
        propertyOverrides: Map<String, String> = emptyMap(),
    ) : this(packageName, baseName, listOf(ComponentSpec(component, "component")), archetype, propertyOverrides)

    /** 单组件 schema 的便捷读法（同 [components] 的首项）；多组件时不要用它做判断。 */
    val component: ComponentDecl get() = components.first().declaration

    /** 全部字段，按组件序摊平（就是 `View` 上属性的顺序，也是 `Keys` 里键的顺序）。 */
    val fields: List<FieldDecl> get() = components.flatMap { it.declaration.fields }
}

/** `schema` 头行的列数下界：3 列 = `schema, package, base`（单组件老写法）。 */
private const val HEADER_MIN_COLUMNS = 3

/** `schema` 头行的列数上界：4 列多一个组件名（多组件 schema 改用独立 `component` 行）。 */
private const val HEADER_MAX_COLUMNS = 4

/** `component` 行的列数下界：2 列 = `component, name`，键属性名按组件名折。 */
private const val COMPONENT_MIN_COLUMNS = 2

/** `component` 行的列数上界：3 列多了显式键属性名。 */
private const val COMPONENT_MAX_COLUMNS = 3

/** `field` 行的列数下界：4 列 = `field, name, type, entityRef`。 */
private const val FIELD_MIN_COLUMNS = 4

/** `field` 行的列数上界：5 列多了属性名覆盖（见 [SchemaDecl.propertyOverrides]）。 */
private const val FIELD_MAX_COLUMNS = 5

/** `archetype` 行固定 2 列：`archetype, name`。 */
private const val ARCHETYPE_COLUMNS = 2

/**
 * 解析 UTF-8 TSV。
 *
 * ```
 * schema    <package>        <base>       [<component>]
 * component <name>           [<keyProperty>]                （可选，可多行）
 * archetype <archetype>                                     （可选，至多一行）
 * field     <name>    INT|STR    true|false    [property]   （第 5 列可选）
 * ```
 *
 * 组件有两种写法：头行的第 4 列（老写法，单组件，缺省键属性名 = `component`），或独立的
 * `component` 行（引入于多组件支持，可重复，缺省键属性名 = 组件名最后一个点段折成驼峰）。
 * 两种不能同时用 —— 头行给了组件名就说明是单组件 schema，再写 `component` 行视为冲突。
 *
 * `archetype` 行引入于 2026-11-16（生成器升级为 Keys + View + Facade 那次）；`field` 行的
 * 第 5 列 `property` 同时引入。老文件（四列头 + 四列 `field`、无 `archetype` 行）解析行为不变。
 */
fun parseSchema(text: String): SchemaDecl {
    val lines = text.removeSuffix("\n").removeSuffix("\r").split('\n').map { it.removeSuffix("\r") }
    require(lines.isNotEmpty() && lines.first().isNotBlank()) { "line 1: schema file is empty" }
    val header = parseHeaderLine(lines.first().split('\t'))

    var archetype: String? = null
    val overrides = mutableMapOf<String, String>()
    // 组件按出现序累积；每个组件自己攒字段（ComponentDecl.fields 是只读 List，攒完再定型）。
    val components = mutableListOf<ComponentBuilder>()
    if (header.component != null) {
        components += ComponentBuilder(header.component, "component")
    }

    lines.drop(1).forEachIndexed { index, line ->
        if (line.isBlank()) return@forEachIndexed
        val columns = line.split('\t')
        val number = index + 2
        when (columns[0]) {
            "component" -> components += parseComponentLine(columns, number, header.component, components)
            "archetype" -> archetype = parseArchetypeLine(columns, number, archetype)
            "field" -> parseFieldLine(columns, number, components, overrides)
            else -> throw IllegalArgumentException("line $number: expected component, field or archetype (TSV)")
        }
    }
    require(components.isNotEmpty()) { "line 1: schema declares no component" }
    return SchemaDecl(
        packageName = header.packageName,
        baseName = header.baseName,
        components = components.map { ComponentSpec(ComponentDecl(it.name, it.fields.toList()), it.keyProperty) },
        archetype = archetype,
        propertyOverrides = overrides.toMap(),
    )
}

/** 头行解析结果：包名、基名、头行可选的第 4 列组件名。 */
private class ParsedHeader(val packageName: String, val baseName: String, val component: String?)

/** 解析 `schema` 头行（列数、首列、可选组件名非空）。 */
private fun parseHeaderLine(columns: List<String>): ParsedHeader {
    require(columns.size in HEADER_MIN_COLUMNS..HEADER_MAX_COLUMNS) {
        "line 1: expected schema, package, base[, component] (TSV)"
    }
    require(columns[0] == "schema") { "line 1: expected schema, package, base[, component] (TSV)" }
    val component = columns.getOrNull(3)
    require(component == null || component.isNotBlank()) { "line 1: component must not be blank" }
    return ParsedHeader(packageName = columns[1], baseName = columns[2], component = component)
}

/**
 * 解析一行 `component`：列数、与头行第 4 列冲突、名字非空且不重名、键属性名给定或按组件名折。
 *
 * 缺省取组件名最后一个点段、首字母小写：`lw.char.persona` → `persona`。
 * 只做「折」不做校验 —— 是不是合法 Kotlin 标识符、撞不撞关键字，由 emit 统一判
 * （那里本来就要判，两边各判一次会漂）。
 */
private fun parseComponentLine(
    columns: List<String>,
    number: Int,
    headerComponent: String?,
    existing: List<ComponentBuilder>,
): ComponentBuilder {
    require(columns.size in COMPONENT_MIN_COLUMNS..COMPONENT_MAX_COLUMNS) {
        "line $number: expected component, name[, keyProperty] (TSV)"
    }
    require(headerComponent == null) {
        "line $number: component line conflicts with the component column in the header"
    }
    require(columns[1].isNotBlank()) { "line $number: component name must not be blank" }
    require(existing.none { it.name == columns[1] }) {
        "line $number: duplicate component name ${columns[1]}"
    }
    val keyProperty = if (columns.size == COMPONENT_MAX_COLUMNS) {
        require(columns[2].isNotBlank()) { "line $number: key property must not be blank" }
        columns[2]
    } else {
        columns[1].substringAfterLast('.').replaceFirstChar { it.lowercaseChar() }
    }
    return ComponentBuilder(columns[1], keyProperty)
}

/** 解析一行 `archetype`：两列且名字非空、至多一行。 */
private fun parseArchetypeLine(columns: List<String>, number: Int, existing: String?): String {
    require(columns.size == ARCHETYPE_COLUMNS && columns[1].isNotBlank()) {
        "line $number: expected archetype, name (TSV)"
    }
    require(existing == null) { "line $number: duplicate archetype line" }
    return columns[1]
}

/** 解析一行 `field`：列数、必须已有组件、类型/entityRef 严格、名字非空、第 5 列覆盖去重。 */
private fun parseFieldLine(
    columns: List<String>,
    number: Int,
    components: MutableList<ComponentBuilder>,
    overrides: MutableMap<String, String>,
) {
    require(columns.size in FIELD_MIN_COLUMNS..FIELD_MAX_COLUMNS) {
        "line $number: expected field, name, type, entityRef[, property] (TSV)"
    }
    require(components.isNotEmpty()) { "line $number: field appears before any component" }
    val type = FieldType.entries.singleOrNull { it.name == columns[2] }
    require(type != null) { "line $number: type must be INT or STR" }
    val entityRef = columns[3].toBooleanStrictOrNull()
    require(entityRef != null) { "line $number: entityRef must be true or false" }
    require(columns[1].isNotBlank()) { "line $number: field name must not be blank" }
    if (columns.size == FIELD_MAX_COLUMNS) {
        require(columns[4].isNotBlank()) { "line $number: property override must not be blank" }
        require(overrides.put(columns[1], columns[4]) == null) {
            "line $number: duplicate property override for ${columns[1]}"
        }
    }
    components.last().fields += FieldDecl(columns[1], type, entityRef)
}

/** 解析期的可变组件累积器（[ComponentSpec] 是定型后的只读形状）。 */
private class ComponentBuilder(val name: String, val keyProperty: String) {
    val fields = mutableListOf<FieldDecl>()
}
