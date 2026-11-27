package com.eratw.port.core.patch

/** Manifest entity-ref 变量槽的持久化作用域。 */
enum class EntityRefScope { NORMAL, GLOBAL, CHARA }

/** freeze 裁决后的持久实体引用槽；键已是 ModuleDescriptor 的 canonical 形。 */
data class DeclaredEntityRef(
    val packageId: String,
    val key: String,
    val scope: EntityRefScope,
)
