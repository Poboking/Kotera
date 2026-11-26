package com.eratw.port.core.entity

import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.save.SaveService

/**
 * MR-1b 身份原型装置的确定性夹具（测试侧，生产代码零改动）。
 *
 * 本夹具建模 era-arch-v61u.md §7:156 钉死的「持久实体引用完整名单 = 三面，缺一不可」，
 * 在测试侧实现一个「导入/装载相位的三面遍历编排器」[IdentityImportDevice]。核心只提供
 * 最内层动作 [EntityIdRemapTable.remap] 与载荷内两面的打包方法 [EntitySnapshot.remapped]；
 * 第三面（Manifest entityRefs 变量槽）按 EntitySnapshot.kt:20 KDoc 归 VM 变量层、生产侧
 * 尚未编排（§12.1），本装置用 [IdentityImportDevice.variableSlots] 在测试侧建模它。
 *
 * 位域 / 跨设备重映射是 RT2 自研语义，Emuera C# 无对应物，权威 = 蓝图 §7 + §10。
 * 纯 JVM：无 Android API、无 kotlin.random、无静态可变全局（状态全挂装置实例）。
 * 确定性：实体槽号、generation、shortId、边值、变量值全部常量喂入，不用任何随机。
 */

/** 三面遍历的「面」枚举（对应 §7:156 的①②③）。 */
enum class RemapFace {
    /** ① 实体图组件载荷中 ComponentSchema 声明为 EntityRef 的字段。 */
    COMPONENT_ENTITY_REF,

    /** ② 全部关系边端点。 */
    RELATION_EDGE,

    /** ③ 各 Package Manifest 显式声明的 entityRefs 变量槽。 */
    MANIFEST_ENTITY_REF,
}

/**
 * 三面完整名单（正确协议 = 三面齐全）。反向证明① 逐次从本集合摘掉一面
 * （只改装置、不改核心），对应面的恢复用例必红；恢复逐字节复绿。
 */
val ALL_THREE_FACES: Set<RemapFace> = setOf(
    RemapFace.COMPONENT_ENTITY_REF,
    RemapFace.RELATION_EDGE,
    RemapFace.MANIFEST_ENTITY_REF,
)

/**
 * 未声明裸 Long 是否「一并重映射」。正确协议 = false（不猜值、不误伤，§7 诚实边界）。
 * 反向证明② 临时置 true 制造红，恢复 false 复绿。此常量是装置的反向证明锚，
 * 不是让红变绿的白名单：默认值即蓝图要求的正确语义。
 */
const val INCLUDE_UNDECLARED_LONGS_IN_REMAP: Boolean = false

/**
 * 重复 EntityId 导入是否拒绝。正确协议 = true（v1 不支持分支自动合并，§7:156）。
 * 反向证明③ 临时置 false（静默重分配）制造红，恢复 true 复绿。
 */
const val REJECT_DUPLICATE_ENTITY_IDS: Boolean = true

/**
 * 装置自定义的重复 EntityId 导入拒绝（§7:156）。核心未实现此编排
 * （EntityIdRemapTable.kt:19 KDoc 明示归 MR-1b 装置职责），故由夹具建模。
 */
class DuplicateEntityIdImportException(val duplicates: Set<Long>) :
    RuntimeException(
        "导入出现重复 EntityId，v1 不支持分支自动合并，拒绝并报告：" +
            duplicates.joinToString { it.toString(16) },
    )

/** 常量与 schema / descriptor / kernel 构造源（每次调用返回新实例，无共享可变状态）。 */
object IdentityFixtureConstants {
    const val SCHOOL: String = "school.mod"
    const val CLINIC: String = "clinic.mod"

    /** ① 声明为 EntityRef 的整型字段。 */
    const val FIELD_REF: String = "homeroomTeacherId"

    /** 普通 INT 字段（非 ref），存普通整数。 */
    const val FIELD_ORDINARY: String = "score"

    /** 未声明 EntityRef 的 INT 字段，却存了旧 EntityId（负控：不猜值）。 */
    const val FIELD_UNDECLARED_HOLDER: String = "legacyHolder"

    const val FIELD_STR: String = "nickname"
    const val RELATION: String = "MENTOR"
    const val ARCHETYPE: String = "STUDENT"

    /** ③ school 包 Manifest 声明的 entity-ref 变量槽。 */
    const val VAR_SCHOOL: String = "LAST_STUDENT_ID"

    /** ③ clinic 包 Manifest 声明的 entity-ref 变量槽。 */
    const val VAR_CLINIC: String = "LAST_CLINIC_ID"

    /** 未声明的裸 Long 变量槽（负控：存旧 EntityId，原样不动）。 */
    const val VAR_RAW: String = "RAW_OLD_ID"

    const val EDGE_VALUE: Long = 100L
    const val ORDINARY_VALUE: Long = 88L
    const val NICKNAME: String = "alice"

    fun schemas(): List<ComponentSchema> = listOf(
        ComponentSchema(
            packageId = SCHOOL,
            name = "Enrollment",
            fields = listOf(
                ComponentFieldSpec(FIELD_REF, isEntityRef = true),
                ComponentFieldSpec(FIELD_ORDINARY),
                ComponentFieldSpec(FIELD_UNDECLARED_HOLDER),
                ComponentFieldSpec(FIELD_STR, ComponentFieldType.STR),
            ),
        ),
    )

    fun relations(): List<RelationshipSchema> = listOf(RelationshipSchema(RELATION))

    fun archetypes(): List<EntityArchetype> =
        listOf(EntityArchetype(ARCHETYPE, listOf("Enrollment")))

    fun newKernel(): EntityKernel = EntityKernel(schemas(), relations(), archetypes(), 8)

    /** 两个 Package Manifest：各声明自己的 entityRefs，school 另带组件 schema 声明。 */
    fun sourceDescriptors(): List<ModuleDescriptor> = listOf(
        ModuleDescriptor(
            packageId = SCHOOL,
            entityRefs = setOf(VAR_SCHOOL),
            componentSchemas = schemas(),
        ),
        ModuleDescriptor(packageId = CLINIC, entityRefs = setOf(VAR_CLINIC)),
    )
}

/**
 * 最小宿主桥：本装置不测 Roster 投射，projectedEntities 恒空即可让
 * [SaveService.captureEntityPayload] 只做 exportSnapshot + encode，不触发任何脱水。
 * 实现 [ICharaSlotProjector] 全接口，纯默认返回，无状态。
 */
private class NoOpProjector : ICharaSlotProjector {
    override fun project(entityId: Long): Int = -1
    override fun unproject(entityId: Long): Boolean = false
    override fun charIndexOf(entityId: Long): Int = -1
    override val rosterGeneration: Long = 0L
    override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1
    override fun entityAt(charIndex: Int): Long = EntityIdCodec.NULL
    override fun projectedEntities(): LongArray = LongArray(0)
    override fun syncFromCharaSlot(entityId: Long) = Unit
    override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
    override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
    override fun onWorldReset() = Unit
}

/** 造一个带 NoOp 桥的 [SaveService]（virtualNoRegistry 缺省 null，本装置不涉虚拟 NO）。 */
fun newIdentitySaveService(kernel: EntityKernel, registry: PackageShortIdRegistry): SaveService =
    SaveService(kernel, NoOpProjector(), registry)

/**
 * 一台设备上的完整身份状态 + 导入/装载相位的三面遍历编排器。
 *
 * @property variableSlots 面③ 的值：键来自各 Manifest 的 normalizedEntityRefKeys。
 * @property rawLongs 未声明裸 Long 槽（负控；永不被当成实体引用）。
 */
class IdentityImportDevice(
    val registry: PackageShortIdRegistry,
    val snapshot: EntitySnapshot,
    val descriptors: List<ModuleDescriptor>,
    val variableSlots: Map<String, Long>,
    val rawLongs: Map<String, Long>,
) {
    /** 面③ 的声明键集合（归一）。 */
    val declaredRefKeys: Set<String> =
        descriptors.flatMap { it.normalizedEntityRefKeys() }.toSet()

    /** 面① 的声明键集合（归一，来自 ComponentSchema）。 */
    val componentEntityRefKeys: Set<String> =
        descriptors.flatMap { it.componentSchemas }
            .flatMap { it.normalizedEntityRefFieldKeys() }
            .toSet()

    /**
     * 按 [table] 整体重映射，返回**新装置**（原装置不变，便于逐面对拍）。
     *
     * 三面遍历（缺一不可，§7:156）：
     * - 面① [RemapFace.COMPONENT_ENTITY_REF]：snapshot.fields 中 isEntityRef 的整型字段值；
     * - 面② [RemapFace.RELATION_EDGE]：snapshot.edges 两端点（边值不是引用，原样带走）；
     * - 面③ [RemapFace.MANIFEST_ENTITY_REF]：declaredRefKeys 命中的变量槽值。
     *
     * 槽表 pkgShortId（实体自身归属包身份）恒重映射：它不是「引用面」，但身份必须
     * 跟着换，否则组件/边/变量槽指向新包、实体自身却仍是旧包，整图不自洽。
     * rawLongs 恒不参与（诚实边界；受 [INCLUDE_UNDECLARED_LONGS_IN_REMAP] 反向证明锚控制）。
     * 只换 pkg 段、不动 generation 与 handleSlot 由 [EntityIdRemapTable.remap] 保证。
     */
    fun remap(table: EntityIdRemapTable, faces: Set<RemapFace> = ALL_THREE_FACES): IdentityImportDevice {
        val newSlots = snapshot.slots.map { s ->
            val newPkg = table.targetShortIdOf(s.pkgShortId) ?: s.pkgShortId
            EntitySnapshot.SlotSnapshot(s.slot, s.generation, newPkg, s.state, s.archetypeIdx)
        }
        val newFields = snapshot.fields.map { f ->
            if (f.isEntityRef && RemapFace.COMPONENT_ENTITY_REF in faces) {
                EntitySnapshot.FieldSnapshot(
                    f.name,
                    f.type,
                    true,
                    f.intValues.map { EntitySnapshot.IntLongPair(it.slot, table.remap(it.value)) },
                    f.strValues,
                )
            } else {
                f
            }
        }
        val newEdges = snapshot.edges.map { e ->
            val from = if (RemapFace.RELATION_EDGE in faces) table.remap(e.from) else e.from
            val to = if (RemapFace.RELATION_EDGE in faces) table.remap(e.to) else e.to
            EntitySnapshot.EdgeSnapshot(from, e.kindIdx, to, e.value)
        }
        val newSnapshot = EntitySnapshot(
            newSlots, snapshot.archetypes, newFields, snapshot.relationKinds, newEdges,
        )

        val newVarSlots = variableSlots.toMutableMap()
        if (RemapFace.MANIFEST_ENTITY_REF in faces) {
            for (key in declaredRefKeys) {
                newVarSlots[key]?.let { newVarSlots[key] = table.remap(it) }
            }
        }
        val newRawLongs = rawLongs.toMutableMap()
        if (INCLUDE_UNDECLARED_LONGS_IN_REMAP) {
            for (key in newRawLongs.keys) {
                newRawLongs[key] = table.remap(newRawLongs.getValue(key))
            }
        }
        return IdentityImportDevice(registry, newSnapshot, descriptors, newVarSlots, newRawLongs)
    }

    /** 收集本装置全部持久实体引用（三面 + 槽身份），供重复 EntityId 导入检测。 */
    fun allEntityIds(): Set<Long> {
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
            if (k in declaredRefKeys && !EntityIdCodec.isNull(v)) out += v
        }
        return out
    }

    /** 取一个 EntityRef 字段在 [slot] 上的值（面① 对拍用）。 */
    fun entityRefFieldValue(field: String, slot: Int): Long {
        val key = field.uppercase()
        val f = snapshot.fields.first { it.name.uppercase() == key && it.isEntityRef }
        return f.intValues.first { it.slot == slot }.value
    }

    /** 取一个普通（非 ref）INT 字段在 [slot] 上的值（负控对拍用）。 */
    fun ordinaryFieldValue(field: String, slot: Int): Long {
        val key = field.uppercase()
        val f = snapshot.fields.first { it.name.uppercase() == key && !it.isEntityRef }
        return f.intValues.first { it.slot == slot }.value
    }
}

/** 源设备 A 的完整装置 + 建出的真实 EntityId（不硬编码槽位，取 kernel 返回值）。 */
class SourceDevice(
    val device: IdentityImportDevice,
    val s1: Long,
    val s2: Long,
    val c1: Long,
    val oldSchoolShortId: Int,
    val oldClinicShortId: Int,
)

/**
 * 建设备 A：core=1、school.mod=2、clinic.mod=3；三面各存引用；未声明裸 Long 存旧 ID。
 *
 * 面① s1.homeroomTeacherId = c1；面② s1 --MENTOR--> c1（边值 100）；
 * 面③ LAST_STUDENT_ID = s1、LAST_CLINIC_ID = c1；
 * 负控：legacyHolder（未声明 INT 字段）= c1、RAW_OLD_ID（未声明裸 Long）= c1。
 */
fun buildSourceDeviceA(): SourceDevice {
    val c = IdentityFixtureConstants
    val reg = PackageShortIdRegistry()
    val schoolId = reg.allocate(c.SCHOOL) // 2
    val clinicId = reg.allocate(c.CLINIC) // 3
    val k = c.newKernel()
    val s1 = k.create(schoolId, c.ARCHETYPE)
    val s2 = k.create(schoolId, c.ARCHETYPE)
    val c1 = k.create(clinicId, c.ARCHETYPE)

    assertTrueSource(k.setInt(s1, c.FIELD_REF, c1))
    assertTrueSource(k.setInt(s1, c.FIELD_ORDINARY, c.ORDINARY_VALUE))
    assertTrueSource(k.setInt(s1, c.FIELD_UNDECLARED_HOLDER, c1))
    assertTrueSource(k.setStr(s1, c.FIELD_STR, c.NICKNAME))
    assertTrueSource(k.addRelation(s1, c.RELATION, c1))
    assertTrueSource(k.setRelationValue(s1, c.RELATION, c1, c.EDGE_VALUE))

    val varSlots = linkedMapOf(c.VAR_SCHOOL to s1, c.VAR_CLINIC to c1)
    val rawLongs = linkedMapOf(c.VAR_RAW to c1)
    val device = IdentityImportDevice(reg, k.exportSnapshot(), c.sourceDescriptors(), varSlots, rawLongs)
    return SourceDevice(device, s1, s2, c1, schoolId, clinicId)
}

private fun assertTrueSource(ok: Boolean) {
    require(ok) { "夹具构造失败：内核 set/add 返回 false" }
}

/** 导入侧（设备 B）：本地 shortId 2/3 已被别的包占用 ⇒ 源包冲突。 */
fun conflictingImportRegistry(): PackageShortIdRegistry {
    val reg = PackageShortIdRegistry()
    reg.allocate("local.alpha") // 2 占用源 school 的 2
    reg.allocate("local.beta") // 3 占用源 clinic 的 3
    return reg
}

/** 冲突重映射方案：给冲突包分配的新 shortId + 重映射表。 */
class ConflictPlan(
    val table: EntityIdRemapTable,
    val newSchool: Int,
    val newClinic: Int,
)

/**
 * 检测源包 shortId 在导入侧的冲突，给冲突包分配**新** shortId（单调 allocate），
 * 产出重映射表。对应 §7「导入侧给冲突包分配新 shortId，然后整体重映射」。
 */
fun planConflictRemap(src: SourceDevice, importReg: PackageShortIdRegistry): ConflictPlan {
    val c = IdentityFixtureConstants
    val occupantSchool = importReg.packageIdOf(src.oldSchoolShortId)
    val occupantClinic = importReg.packageIdOf(src.oldClinicShortId)
    require(occupantSchool != null && occupantSchool != c.SCHOOL) {
        "构造前提：源 school 短 ID 在导入侧已被别的包占用"
    }
    require(occupantClinic != null && occupantClinic != c.CLINIC) {
        "构造前提：源 clinic 短 ID 在导入侧已被别的包占用"
    }
    val newSchool = importReg.allocate(c.SCHOOL)
    val newClinic = importReg.allocate(c.CLINIC)
    val table = EntityIdRemapTable.of(
        mapOf(src.oldSchoolShortId to newSchool, src.oldClinicShortId to newClinic),
    )
    return ConflictPlan(table, newSchool, newClinic)
}

/**
 * 导入两个独立分支的实体集合。v1 不支持分支自动合并（§7:156）：两分支的 EntityId
 * 集合若有交集 ⇒ [DuplicateEntityIdImportException] 拒绝并报告，不静默重分配。
 * 受 [REJECT_DUPLICATE_ENTITY_IDS] 反向证明锚控制（置 false 时静默继续 ⇒ 拒绝用例红）。
 */
fun importBranches(a: IdentityImportDevice, b: IdentityImportDevice): Set<Long> {
    val idsA = a.allEntityIds()
    val idsB = b.allEntityIds()
    val duplicates = idsA.intersect(idsB)
    if (REJECT_DUPLICATE_ENTITY_IDS && duplicates.isNotEmpty()) {
        throw DuplicateEntityIdImportException(duplicates)
    }
    return idsA + idsB
}

/** 造一个与 [overlap] 存在 EntityId 重叠的独立分支（两分支自动合并负控用）。 */
fun independentBranchWithOverlap(overlap: Long): IdentityImportDevice {
    val c = IdentityFixtureConstants
    val reg = PackageShortIdRegistry()
    reg.allocate("branch.b")
    val k = c.newKernel()
    // 用与源实体相同的 (pkg, generation, slot) 造出数值相同的 EntityId ⇒ 重复。
    val x = k.create(EntityIdCodec.pkgShortIdOf(overlap), c.ARCHETYPE)
    require(x == overlap) { "构造前提：独立分支复现了同源 EntityId 数值" }
    return IdentityImportDevice(
        reg, k.exportSnapshot(), c.sourceDescriptors(), emptyMap(), emptyMap(),
    )
}

/** 造一个与源设备无任何 EntityId 重叠的独立分支（拒绝用例的正向对照）。 */
fun independentBranchDisjoint(): IdentityImportDevice {
    val c = IdentityFixtureConstants
    val reg = PackageShortIdRegistry()
    reg.allocate("branch.c")
    val k = c.newKernel()
    // pkg 段取一个源集合里没用到的号，确保 id 数值不相交。
    val disjointPkg = 200
    k.create(disjointPkg, c.ARCHETYPE)
    return IdentityImportDevice(
        reg, k.exportSnapshot(), c.sourceDescriptors(), emptyMap(), emptyMap(),
    )
}
