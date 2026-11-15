package com.eratw.port.core.entity

import com.eratw.port.core.patch.ModuleDescriptor

/**
 * RT2 波 D 车 D3「跨设备身份重映射原型装置」的确定性夹具（测试侧，生产代码零改动）。
 *
 * 建模 era-arch-v61u.md §7:156 钉死的「持久实体引用完整名单 = 三面，缺一不可」：
 * ① ComponentSchema 声明为 EntityRef 的组件字段；② 全部关系边端点；③ 各 Package
 * Manifest 显式声明的 entityRefs 变量槽（ModuleDescriptor.kt:107-112）。三面遍历的
 * 编排器 [CrossDeviceRemapDevice] 在测试侧实现；核心只提供最内层动作
 * [EntityIdRemapTable.remap]（EntityIdRemapTable.kt:18-19 KDoc 明示：遍历面与重复
 * EntityId 导入拒绝归 MR-1b 原型装置职责，核心不持有存储、不扫描任何载荷）。
 * 槽身份（实体自身归属包）恒随重映射走（EntitySnapshot.kt:20-24）。
 *
 * 位域与跨设备重映射是 RT2 自研语义，Emuera C# 无对应物，权威 = 蓝图 §7 + §10
 * （TODO(verify)：原版无 EntityId，不拿 C# 里任何 Long 变量的处理当锚）。
 * 纯 JVM：无 Android API、无 kotlin.random、无静态可变全局（状态全挂装置实例）。
 * 确定性：槽号 / generation / shortId / 边值 / 变量值全部常量喂入，身份常量逐位写死。
 */

/**
 * 未声明槽（非 EntityRef 整型组件字段 + 裸 Long 变量）是否「一并重映射」。
 *
 * 正确协议 = false：引擎无法从数值区分普通整数与 EntityId，未声明的旧 ID 不重映射、
 * 不猜值、不误伤（ModuleDescriptor.kt:111-112 诚实边界）。本常量是装置的反向证明锚
 * （CONTRACT-RT2-D3 §4.3）：临时置 true = 全量重映射的猜值语义 ⇒ 负控用例必红；
 * 恢复 false 复绿。默认值即蓝图要求的正确语义，不是让红变绿的白名单。
 */
const val REMAP_UNDECLARED_LONGS: Boolean = false

/**
 * 重复 EntityId 导入是否拒绝。正确协议 = true（蓝图 §7:156：v1 不支持分支自动合并，
 * 拒绝并报告，不静默重分配）。反向证明锚：临时置 false（静默放行）⇒ 拒绝用例红。
 */
const val REJECT_DUPLICATE_CROSS_DEVICE_ENTITY_IDS: Boolean = true

/** 装置侧的重复 EntityId 导入拒绝（EntityIdRemapTable.kt:19 KDoc：装置职责）。 */
class CrossDeviceDuplicateEntityIdException(val duplicates: Set<Long>) :
    RuntimeException(
        "导入出现重复 EntityId，v1 不支持分支自动合并，拒绝并报告：" +
            duplicates.joinToString { it.toString(16) },
    )

/** 装置常量与 schema / descriptor / kernel 构造源（每次调用返回新实例，无共享可变状态）。 */
object CrossDeviceRemapConstants {
    const val TAVERN: String = "tavern.mod"
    const val GUILD: String = "guild.mod"

    /** 面① 声明为 EntityRef 的整型组件字段。 */
    const val FIELD_REF: String = "patronId"

    /** 普通 INT 字段（未声明 EntityRef），存普通整数。 */
    const val FIELD_PLAIN: String = "rank"

    /** 未声明 EntityRef 的整型字段，却存了旧 EntityId（负控：不猜值、不误伤）。 */
    const val FIELD_UNDECLARED: String = "memoId"

    const val FIELD_NOTE: String = "note"

    const val RELATION: String = "SPONSORS"
    const val ARCHETYPE: String = "MEMBER"

    /** 面③ tavern 包 Manifest 声明的 entityRefs 变量槽。 */
    const val VAR_TAVERN: String = "LAST_MEMBER_ID"

    /** 面③ guild 包 Manifest 声明的 entityRefs 变量槽。 */
    const val VAR_GUILD: String = "LAST_PATRON_ID"

    /** 未声明的裸 Long 变量槽（负控：存旧 EntityId，原样不动）。 */
    const val VAR_RAW: String = "SCRATCH_ID"

    const val EDGE_VALUE: Long = 777L
    const val PLAIN_VALUE: Long = 88L
    const val NOTE_VALUE: String = "vera"

    // ---- 源设备身份（逐位写死；建源时用 require 逐位断言，保证夹具确定性） ----

    /** tavern 第 1 个实体：pkg=2、generation=0、槽 0。 */
    val SRC_MEMBER: Long = EntityIdCodec.encode(2, 0, 0)

    /** guild 第 1 个实体：pkg=3、generation=0、槽 1。 */
    val SRC_PATRON: Long = EntityIdCodec.encode(3, 0, 1)

    // ---- 导入侧新分配（写死：导入注册表已占 2/3，单调分配必得 4/5） ----

    /** 重映射后 member：pkg 段 2 → 4，generation 与槽不变。 */
    val NEW_MEMBER: Long = EntityIdCodec.encode(4, 0, 0)

    /** 重映射后 patron：pkg 段 3 → 5，generation 与槽不变。 */
    val NEW_PATRON: Long = EntityIdCodec.encode(5, 0, 1)

    fun schemas(): List<ComponentSchema> = listOf(
        ComponentSchema(
            packageId = TAVERN,
            name = "Membership",
            fields = listOf(
                ComponentFieldSpec(FIELD_REF, isEntityRef = true),
                ComponentFieldSpec(FIELD_PLAIN),
                ComponentFieldSpec(FIELD_UNDECLARED),
                ComponentFieldSpec(FIELD_NOTE, ComponentFieldType.STR),
            ),
        ),
    )

    fun relations(): List<RelationshipSchema> = listOf(RelationshipSchema(RELATION))

    fun archetypes(): List<EntityArchetype> =
        listOf(EntityArchetype(ARCHETYPE, listOf("Membership")))

    fun newKernel(): EntityKernel = EntityKernel(schemas(), relations(), archetypes(), 8)

    /** 两个 Package Manifest：各声明自己的 entityRefs，tavern 另带组件 schema 声明。 */
    fun sourceDescriptors(): List<ModuleDescriptor> = listOf(
        ModuleDescriptor(
            packageId = TAVERN,
            entityRefs = setOf(VAR_TAVERN),
            componentSchemas = schemas(),
        ),
        ModuleDescriptor(packageId = GUILD, entityRefs = setOf(VAR_GUILD)),
    )
}

/**
 * 一台设备上的完整身份状态 + 导入/装载相位的三面遍历编排器。
 *
 * @property variableSlots 面③ 的值：键来自各 Manifest 的 normalizedEntityRefKeys。
 * @property rawLongs 未声明裸 Long 槽（负控；永不被当成实体引用）。
 */
class CrossDeviceRemapDevice(
    val registry: PackageShortIdRegistry,
    val snapshot: EntitySnapshot,
    val descriptors: List<ModuleDescriptor>,
    val variableSlots: Map<String, Long>,
    val rawLongs: Map<String, Long>,
) {
    /** 面③ 的声明键集合（归一，来自 Manifest entityRefs）。 */
    val declaredVarRefKeys: Set<String> =
        descriptors.flatMap { it.normalizedEntityRefKeys() }.toSet()

    /** 面① 的声明键集合（归一，来自 ComponentSchema 的 EntityRef 字段）。 */
    val declaredComponentRefKeys: Set<String> =
        descriptors.flatMap { it.componentSchemas }
            .flatMap { it.normalizedEntityRefFieldKeys() }
            .toSet()

    /**
     * 按表整体重映射，返回新装置（原装置不变，便于逐面对拍）。
     *
     * 三面遍历（§7:156 缺一不可）：面① isEntityRef 字段值、面② 边两端点（边值不是
     * 引用，原样带走）、面③ 声明键命中的变量槽；槽身份 pkg 段恒随行。只换 pkg 段、
     * 不动 generation 与 handleSlot 由核心 [EntityIdRemapTable.remap] 保证。
     * rawLongs 与非 EntityRef 整型字段恒不参与，除非反向证明锚
     * [REMAP_UNDECLARED_LONGS] 被置 true（全量重映射的猜值语义）。
     */
    fun remap(table: EntityIdRemapTable): CrossDeviceRemapDevice {
        val newSlots = snapshot.slots.map { s ->
            val newPkg = table.targetShortIdOf(s.pkgShortId) ?: s.pkgShortId
            EntitySnapshot.SlotSnapshot(s.slot, s.generation, newPkg, s.state, s.archetypeIdx)
        }
        val newFields = snapshot.fields.map { f ->
            if (f.isEntityRef) {
                EntitySnapshot.FieldSnapshot(
                    f.name,
                    f.type,
                    true,
                    f.intValues.map { EntitySnapshot.IntLongPair(it.slot, table.remap(it.value)) },
                    f.strValues,
                )
            } else if (REMAP_UNDECLARED_LONGS && f.type == ComponentFieldType.INT) {
                // 反向证明锚开的「全量重映射」口子：未声明整型字段也被改写（猜值语义）。
                EntitySnapshot.FieldSnapshot(
                    f.name,
                    f.type,
                    false,
                    f.intValues.map { EntitySnapshot.IntLongPair(it.slot, table.remap(it.value)) },
                    f.strValues,
                )
            } else {
                f
            }
        }
        val newEdges = snapshot.edges.map { e ->
            EntitySnapshot.EdgeSnapshot(table.remap(e.from), e.kindIdx, table.remap(e.to), e.value)
        }
        val newSnapshot = EntitySnapshot(
            newSlots, snapshot.archetypes, newFields, snapshot.relationKinds, newEdges,
        )
        val newVars = variableSlots.toMutableMap()
        for (key in declaredVarRefKeys) {
            newVars[key]?.let { newVars[key] = table.remap(it) }
        }
        val newRaw = rawLongs.toMutableMap()
        if (REMAP_UNDECLARED_LONGS) {
            for (key in newRaw.keys) newRaw[key] = table.remap(newRaw.getValue(key))
        }
        return CrossDeviceRemapDevice(registry, newSnapshot, descriptors, newVars, newRaw)
    }

    /**
     * 收集本装置全部持久实体引用（三面 + 槽身份），供重复 EntityId 导入检测。
     * 这是装置侧的引用名单编排（核心不扫描载荷，EntityIdRemapTable.kt:19）。
     */
    fun collectedEntityIds(): Set<Long> {
        val out = HashSet<Long>()
        for (s in snapshot.slots) {
            if (s.state != 0) out += EntityIdCodec.encode(s.pkgShortId, s.generation, s.slot)
        }
        for (f in snapshot.fields) {
            if (f.isEntityRef) {
                for (p in f.intValues) {
                    if (!EntityIdCodec.isNull(p.value)) out += p.value
                }
            }
        }
        for (e in snapshot.edges) {
            if (!EntityIdCodec.isNull(e.from)) out += e.from
            if (!EntityIdCodec.isNull(e.to)) out += e.to
        }
        for ((k, v) in variableSlots) {
            if (k in declaredVarRefKeys && !EntityIdCodec.isNull(v)) out += v
        }
        return out
    }

    /** 取一个 EntityRef 字段在 [slot] 上的值（面① 对拍用）。 */
    fun entityRefFieldValue(field: String, slot: Int): Long {
        val key = field.uppercase()
        val f = snapshot.fields.first { it.name.uppercase() == key && it.isEntityRef }
        return f.intValues.first { it.slot == slot }.value
    }

    /** 取一个非 EntityRef 整型字段在 [slot] 上的值（负控对拍用）。 */
    fun ordinaryFieldValue(field: String, slot: Int): Long {
        val key = field.uppercase()
        val f = snapshot.fields.first { it.name.uppercase() == key && !it.isEntityRef }
        return f.intValues.first { it.slot == slot }.value
    }
}

/** 源设备（tavern+guild）的完整装置 + 建出的真实 EntityId（逐位等于写死常量）。 */
class TavernGuildSource(
    val device: CrossDeviceRemapDevice,
    val member: Long,
    val patron: Long,
    val oldTavernShortId: Int,
    val oldGuildShortId: Int,
)

/**
 * 建源设备：core=1、tavern.mod=2、guild.mod=3；三面各存引用；未声明槽存旧 ID。
 *
 * 面① member.patronId = patron；面② member --SPONSORS--> patron（边值 777）；
 * 面③ LAST_MEMBER_ID = member、LAST_PATRON_ID = patron；
 * 负控：memoId（未声明 INT 字段）= patron、SCRATCH_ID（未声明裸 Long）= patron。
 */
fun buildTavernGuildSource(): TavernGuildSource {
    val c = CrossDeviceRemapConstants
    val reg = PackageShortIdRegistry()
    val tavernId = reg.allocate(c.TAVERN)
    val guildId = reg.allocate(c.GUILD)
    require(tavernId == 2 && guildId == 3) {
        "夹具前提：源注册表单调分配（0=NULL、1=__core__）必得 tavern=2、guild=3"
    }
    val k = c.newKernel()
    val member = k.create(tavernId, c.ARCHETYPE)
    val patron = k.create(guildId, c.ARCHETYPE)
    require(member == c.SRC_MEMBER && patron == c.SRC_PATRON) {
        "夹具前提：实体身份逐位等于写死常量（槽序 0/1、generation 0）"
    }
    require(k.setInt(member, c.FIELD_REF, patron))
    require(k.setInt(member, c.FIELD_PLAIN, c.PLAIN_VALUE))
    require(k.setInt(member, c.FIELD_UNDECLARED, patron))
    require(k.setStr(member, c.FIELD_NOTE, c.NOTE_VALUE))
    require(k.addRelation(member, c.RELATION, patron))
    require(k.setRelationValue(member, c.RELATION, patron, c.EDGE_VALUE))
    val varSlots = linkedMapOf(c.VAR_TAVERN to member, c.VAR_GUILD to patron)
    val rawLongs = linkedMapOf(c.VAR_RAW to patron)
    val device = CrossDeviceRemapDevice(reg, k.exportSnapshot(), c.sourceDescriptors(), varSlots, rawLongs)
    return TavernGuildSource(device, member, patron, tavernId, guildId)
}

/** 导入侧（另一台设备）：本地 shortId 2/3 已被别的包占用 ⇒ 源两包都冲突。 */
fun crossDeviceConflictingImportRegistry(): PackageShortIdRegistry {
    val reg = PackageShortIdRegistry()
    val a = reg.allocate("local.one")
    val b = reg.allocate("local.two")
    require(a == 2 && b == 3) { "夹具前提：导入注册表已占 2/3，与源包短 ID 冲突" }
    return reg
}

/** 冲突重映射方案：给冲突包分配的新 shortId + 重映射表。 */
class CrossDeviceConflictPlan(
    val table: EntityIdRemapTable,
    val newTavern: Int,
    val newGuild: Int,
)

/**
 * 检测源包 shortId 在导入侧的冲突，给冲突包分配新 shortId（单调 allocate），
 * 产出重映射表。对应蓝图 §7「导入侧给冲突包分配新 shortId，然后整体重映射」。
 */
fun planConflictRemap(src: TavernGuildSource, importReg: PackageShortIdRegistry): CrossDeviceConflictPlan {
    val c = CrossDeviceRemapConstants
    require(importReg.packageIdOf(src.oldTavernShortId) != c.TAVERN) {
        "构造前提：源 tavern 短 ID 在导入侧已被别的包占用"
    }
    require(importReg.packageIdOf(src.oldGuildShortId) != c.GUILD) {
        "构造前提：源 guild 短 ID 在导入侧已被别的包占用"
    }
    val newTavern = importReg.allocate(c.TAVERN)
    val newGuild = importReg.allocate(c.GUILD)
    require(newTavern == 4 && newGuild == 5) {
        "夹具前提：导入侧单调分配必得 tavern=4、guild=5"
    }
    val table = EntityIdRemapTable.of(
        mapOf(src.oldTavernShortId to newTavern, src.oldGuildShortId to newGuild),
    )
    return CrossDeviceConflictPlan(table, newTavern, newGuild)
}

/**
 * 导入两个独立分支的实体集合。v1 不支持分支自动合并（蓝图 §7:156）：两分支的
 * EntityId 集合若有交集 ⇒ [CrossDeviceDuplicateEntityIdException] 拒绝并报告，
 * 不静默重分配。受 [REJECT_DUPLICATE_CROSS_DEVICE_ENTITY_IDS] 反向证明锚控制。
 */
fun importBranches(a: CrossDeviceRemapDevice, b: CrossDeviceRemapDevice): Set<Long> {
    val idsA = a.collectedEntityIds()
    val idsB = b.collectedEntityIds()
    val duplicates = idsA.intersect(idsB)
    if (REJECT_DUPLICATE_CROSS_DEVICE_ENTITY_IDS && duplicates.isNotEmpty()) {
        throw CrossDeviceDuplicateEntityIdException(duplicates)
    }
    return idsA + idsB
}

/** 造一个与 [overlap] 数值相同的独立分支（同 (pkg, generation, slot) 复现同 id）。 */
fun overlappingBranchReproducing(overlap: Long): CrossDeviceRemapDevice {
    val c = CrossDeviceRemapConstants
    val reg = PackageShortIdRegistry()
    reg.allocate("branch.x")
    val k = c.newKernel()
    val x = k.create(EntityIdCodec.pkgShortIdOf(overlap), c.ARCHETYPE)
    require(x == overlap) { "构造前提：独立分支复现同源 EntityId 数值" }
    return CrossDeviceRemapDevice(reg, k.exportSnapshot(), c.sourceDescriptors(), emptyMap(), emptyMap())
}

/** 造一个与源设备无任何 EntityId 重叠的独立分支（拒绝用例的正向对照）。 */
fun disjointBranch(): CrossDeviceRemapDevice {
    val c = CrossDeviceRemapConstants
    val reg = PackageShortIdRegistry()
    reg.allocate("branch.y")
    val k = c.newKernel()
    // pkg 段取 200（源集合只用过 2/3），数值必不相交。
    k.create(200, c.ARCHETYPE)
    return CrossDeviceRemapDevice(reg, k.exportSnapshot(), c.sourceDescriptors(), emptyMap(), emptyMap())
}
