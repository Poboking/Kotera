package com.eratw.port.core.entity

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 纯跨设备导入计划。目标注册表只读；计划产出后才允许提交。
 *
 * 同设备模式保持双向冲突 fail-closed；跨设备模式按源包名字典序，从目标 nextId
 * 水位依次给每个非 core 源包分配新短 ID，因此目标视图与源字节相同即计划相同。
 */
class EntityIdImportPlan private constructor(
    val registryBytes: ByteArray,
    val remapTable: EntityIdRemapTable,
    val mergedRegistry: PackageShortIdRegistry.Snapshot,
    private val sourceEntries: Map<String, Int>,
) {
    val sourcePackageIds: Set<String> get() = sourceEntries.keys
    /** 先重映射实体图，再检查活 EntityId 单射；重复引用字段/边不属于本闸。 */
    fun remapped(snapshot: EntitySnapshot): EntitySnapshot {
        requireUniqueLiveIds(snapshot, "导入前")
        for (slot in snapshot.slots) {
            if (slot.state != 0) {
                require(slot.pkgShortId in sourceEntries.values) {
                    "实体归属包短 ID ${slot.pkgShortId} 不在源注册表"
                }
            }
        }
        val remapped = snapshot.remapped(remapTable)
        requireUniqueLiveIds(remapped, "重映射后")
        return remapped
    }

    /** 虚拟 NO 绑定也属于持久身份，必须与实体图使用同一张表。 */
    fun remappedVirtualNos(snapshot: VirtualCharaNoRegistry.Snapshot): VirtualCharaNoRegistry.Snapshot =
        VirtualCharaNoRegistry.Snapshot(
            snapshot.entries.mapValues { (_, id) -> remapTable.remap(id) },
            snapshot.nextCandidate,
        )

    private fun requireUniqueLiveIds(snapshot: EntitySnapshot, phase: String) {
        val ids = HashSet<Long>()
        for (slot in snapshot.slots) {
            if (slot.state == 0) continue
            val id = EntityIdCodec.encode(slot.pkgShortId, slot.generation, slot.slot)
            require(ids.add(id)) { "$phase 活 EntityId 重复：${id.toString(16)}" }
        }
    }

    companion object {
        /** 默认同设备严格 merge；crossDevice=true 才启用确定性重分配。 */
        fun of(
            target: PackageShortIdRegistry.Snapshot,
            sourceBytes: ByteArray,
            crossDevice: Boolean = false,
        ): EntityIdImportPlan {
            val source = PackageShortIdRegistry.decode(sourceBytes).snapshot()
            val entries = LinkedHashMap(target.entries)
            val locks = target.lockedIds.toMutableSet()
            var nextId = target.nextId
            if (!crossDevice) {
                for ((name, id) in source.entries) {
                    val mine = entries[name]
                    if (mine != null && mine != id) {
                        throw RegistryConflictException("包 [$name] 短 ID 分裂：本地 $mine vs 快照 $id")
                    }
                    val occupant = entries.entries.firstOrNull { it.value == id }?.key
                    if (occupant != null && occupant != name) {
                        throw RegistryConflictException("短 ID $id 分裂：本地 [$occupant] vs 快照 [$name]")
                    }
                }
                entries.putAll(source.entries)
                locks += source.lockedIds
                nextId = maxOf(nextId, source.nextId)
                return EntityIdImportPlan(
                    encodeSnapshot(entries, nextId, locks),
                    EntityIdRemapTable.IDENTITY,
                    PackageShortIdRegistry.Snapshot(entries.toMap(), nextId, locks.toSet()),
                    source.entries,
                )
            }

            val mappings = LinkedHashMap<Int, Int>()
            for ((name, oldId) in source.entries.entries.sortedBy { it.key }) {
                if (name == PackageShortIdRegistry.CORE_PACKAGE_ID) continue
                check(nextId <= EntityIdCodec.MAX_PKG_SHORT_ID) {
                    "跨设备导入 pkgShortId 容量耗尽（上界 ${EntityIdCodec.MAX_PKG_SHORT_ID}）"
                }
                val newId = nextId++
                if (oldId != newId) mappings[oldId] = newId
                entries[name] = newId
                if (oldId in source.lockedIds) locks += newId
            }
            val usedIds = entries.values.toSet()
            val validLocks = locks.filterTo(HashSet()) { it in usedIds }
            val result = EntityIdImportPlan(
                encodeSnapshot(entries, nextId, validLocks),
                EntityIdRemapTable.of(mappings),
                PackageShortIdRegistry.Snapshot(entries.toMap(), nextId, validLocks.toSet()),
                source.entries,
            )
            return result
        }

        private fun encodeSnapshot(
            entries: Map<String, Int>,
            nextId: Int,
            locks: Set<Int>,
        ): ByteArray {
            val buffer = ByteArrayOutputStream()
            val out = DataOutputStream(buffer)
            out.writeInt(PackageShortIdRegistry.MAGIC)
            out.writeShort(PackageShortIdRegistry.FORMAT_VERSION)
            out.writeInt(nextId)
            out.writeInt(entries.size)
            for ((name, id) in entries) {
                val bytes = name.toByteArray(Charsets.UTF_8)
                out.writeInt(id)
                out.writeByte(if (id in locks) 1 else 0)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.flush()
            return buffer.toByteArray()
        }
    }
}
