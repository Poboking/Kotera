package com.eratw.port.modapi

/** 组件块内的字段声明器。 */
class ComponentBuilder internal constructor(private val componentName: String) {
    private val fields = mutableListOf<FieldDecl>()

    /** 整型字段（裸名；只有在生成器产不出键时才用）。 */
    fun int(name: String) {
        fields += FieldDecl(name, FieldType.INT, false)
    }

    /** 整型字段（类型化键，推荐）。 */
    fun int(key: FieldKey<Long>) {
        fields += intField(key)
    }

    /** 字符串字段（裸名）。 */
    fun str(name: String) {
        fields += FieldDecl(name, FieldType.STR, false)
    }

    /** 字符串字段（类型化键，推荐）。 */
    fun str(key: FieldKey<String>) {
        fields += strField(key)
    }

    /** 实体引用字段：整型列，跨设备重映射时按 schema 改写 EntityId。 */
    fun ref(key: FieldKey<EntityId>) {
        fields += refField(key)
    }

    internal fun build(): ComponentDecl = ComponentDecl(componentName, fields.toList())
}

/** 实体原型块内的组件挂载器。 */
class ArchetypeBuilder internal constructor(private val archetypeName: String) {
    private val components = mutableListOf<String>()

    /** 挂载一个组件（按组件名）。 */
    fun uses(component: String) {
        components += component
    }

    /** 挂载一个组件（按类型化键）。 */
    fun uses(key: ComponentKey) {
        components += key.name
    }

    internal fun build(): ArchetypeDecl = ArchetypeDecl(archetypeName, components.toList())
}

/**
 * 声明 DSL 的实体 Schema 段：组件（`component`）与原型（`archetype`）。
 *
 * 继承链位置见 `DeclDsl.kt` 顶部「按域拆分」：本段只把声明追加进
 * [ModManifestBaseBuilder] 的 `components` / `archetypes` 容器，校验仍由各 `*Decl` 与 core
 * 的声明事务负责。
 */
open class ModManifestSchemaBuilder internal constructor(
    packageId: String,
    version: String,
) : ModManifestBaseBuilder(packageId, version) {
    /** 声明一个组件及其字段。 */
    fun component(name: String, block: ComponentBuilder.() -> Unit) {
        components += ComponentBuilder(name).apply(block).build()
    }

    /** 声明一个组件（类型化键）。 */
    fun component(key: ComponentKey, block: ComponentBuilder.() -> Unit) = component(key.name, block)

    /** 声明一个实体原型，并挂载已有组件。 */
    fun archetype(name: String, block: ArchetypeBuilder.() -> Unit = {}) {
        archetypes += ArchetypeBuilder(name).apply(block).build()
    }

    /** 声明一个实体原型（类型化键）。 */
    fun archetype(key: ArchetypeKey, block: ArchetypeBuilder.() -> Unit = {}) = archetype(key.name, block)
}
